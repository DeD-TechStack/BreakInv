package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Central storage policy at the mutation boundaries (DESENHO-BRK31 D-Q2, §5.3). For user writes and the daily
 * snapshot: measured free space on the data volume below the write limit refuses the write, saying how much is
 * missing; an unmeasured space lets it go on with a warning. Installed in {@link Database} as its write gate. The
 * measure is reused for at most one second, so a loop of writes does not query the disk on every row.
 */
public final class StorageGuard {

    static final long CACHE_NANOS = 1_000_000_000L;

    public sealed interface Decision permits Allowed, Unmeasured, Refused {
    }

    public record Allowed(long freeBytes) implements Decision {
    }

    public record Unmeasured(String warning) implements Decision {
    }

    public record Refused(String message, long shortfallBytes) implements Decision {
    }

    private record Cached(SpaceProbe.Space space, long atNanos) {
    }

    private final Path dataFolder;
    private final Path tempFolder;
    private final SpaceProbe probe;
    private final Supplier<StorageLimits> limits;
    private final LongSupplier nanoTime;
    private volatile Cached cached;
    private volatile String lastWarning;

    public StorageGuard(Path databaseFile, SpaceProbe probe, Supplier<StorageLimits> limits, LongSupplier nanoTime) {
        this(databaseFile, sqliteTempFolder(), probe, limits, nanoTime);
    }

    /** @param tempFolder where SQLite writes its temp files (sorts, VACUUM) */
    public StorageGuard(Path databaseFile, Path tempFolder, SpaceProbe probe, Supplier<StorageLimits> limits,
                        LongSupplier nanoTime) {
        this.dataFolder = databaseFile.toAbsolutePath().getParent();
        this.tempFolder = tempFolder;
        this.probe = probe;
        this.limits = limits;
        this.nanoTime = nanoTime;
    }

    /** The guard of the running application: the real volume and the user's limits. */
    public static StorageGuard forDatabase(Path databaseFile, StoragePreferences preferences) {
        return new StorageGuard(databaseFile, SpaceProbe.fileStores(), () -> preferences.load().limits(),
                System::nanoTime);
    }

    public Decision userWrite() {
        SpaceProbe.Space space = dataSpace();
        StoragePolicy.Evaluation e = StoragePolicy.evaluate(-1, space, limits.get());
        if (e.undetermined().contains(StoragePolicy.Level.WRITE_LIMIT)) {
            String w = "Gravação feita com espaço livre não medido no disco dos dados (" + why(space) + ").";
            lastWarning = w;
            return new Unmeasured(w);
        }
        lastWarning = null;
        if (e.is(StoragePolicy.Level.WRITE_LIMIT)) {
            long missing = e.shortfall(StoragePolicy.Level.WRITE_LIMIT);
            return new Refused("Pouco espaço livre no disco dos dados: há " + Sizes.text(e.freeBytes())
                    + " livres e o mínimo para gravar é " + Sizes.text(e.limits().writeLimitBytes())
                    + ". Libere pelo menos " + Sizes.text(missing) + " e tente de novo. Nada foi gravado.", missing);
        }
        return new Allowed(e.freeBytes());
    }

    /**
     * Whether an operation fits (DESENHO-BRK31 §5.2): every place it uses is measured now, the places on the same
     * volume are summed within each phase, and each volume must have at least its peak free. One place without a
     * measure makes the whole decision unmeasured; the caller applies §5.3 (refuse, postpone or confirm).
     *
     * @param places folder of each place; DATA and TEMP default to the database folder and SQLite's temp folder
     */
    public Decision operation(SpaceEstimate.Estimate estimate, Map<SpaceEstimate.Place, Path> places) {
        if (!estimate.known()) {
            return new Unmeasured("Não foi possível estimar o espaço necessário (" + estimate.unknownReason() + ").");
        }
        Map<SpaceEstimate.Place, Path> where = new EnumMap<>(SpaceEstimate.Place.class);
        where.put(SpaceEstimate.Place.DATA, dataFolder);
        where.put(SpaceEstimate.Place.TEMP, tempFolder);
        where.putAll(places);
        Map<SpaceEstimate.Place, Object> volumeOf = new EnumMap<>(SpaceEstimate.Place.class);
        Map<Object, Long> free = new LinkedHashMap<>();
        Map<Object, Path> shownAs = new LinkedHashMap<>();
        for (SpaceEstimate.Phase phase : estimate.phases()) {
            for (SpaceEstimate.Place place : phase.live().keySet()) {
                if (volumeOf.containsKey(place)) continue;
                Path at = where.get(place);
                SpaceProbe.Space s = at == null ? new SpaceProbe.Unavailable("pasta não informada") : probe.usableSpace(at);
                if (!(s instanceof SpaceProbe.Measured m) || m.volume() == null) {
                    return new Unmeasured("Espaço livre não medido em " + at + " (" + why(s) + ").");
                }
                volumeOf.put(place, m.volume());
                free.put(m.volume(), m.usableBytes());
                shownAs.putIfAbsent(m.volume(), at);
            }
        }
        long worst = 0;
        String message = null;
        for (Map.Entry<Object, Long> need : estimate.peakPerVolume(volumeOf).entrySet()) {
            long have = free.get(need.getKey());
            if (have < need.getValue() && need.getValue() - have > worst) {
                worst = need.getValue() - have;
                message = "Falta espaço no disco de " + shownAs.get(need.getKey()) + ": a " + estimate.operation()
                        + " precisa de até " + Sizes.text(need.getValue()) + " livres e há " + Sizes.text(have)
                        + ". Libere pelo menos " + Sizes.text(worst) + " e tente de novo.";
            }
        }
        if (message != null) return new Refused(message, worst);
        return new Allowed(free.values().stream().mapToLong(Long::longValue).min().orElse(-1));
    }

    /**
     * Where SQLite puts its temp files when the application does not set a folder: on Windows TMP, TEMP or the
     * user's folder (GetTempPath); elsewhere SQLITE_TMPDIR, TMPDIR, /var/tmp, /usr/tmp or /tmp.
     */
    public static Path sqliteTempFolder() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> candidates = windows
                ? Arrays.asList(System.getenv("TMP"), System.getenv("TEMP"), System.getProperty("user.home"))
                : Arrays.asList(System.getenv("SQLITE_TMPDIR"), System.getenv("TMPDIR"), "/var/tmp", "/usr/tmp", "/tmp");
        for (String c : candidates) {
            if (c != null && !c.isBlank() && Files.isDirectory(Path.of(c))) return Path.of(c);
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    /** For {@link Database#setWriteGate}: the refusal message, or null to let the write go on. */
    public Database.WriteGate writeGate() {
        return () -> userWrite() instanceof Refused r ? r.message() : null;
    }

    /** The warning of the last decision taken without a measure; empty once a measure is back. */
    public Optional<String> lastWarning() {
        return Optional.ofNullable(lastWarning);
    }

    private SpaceProbe.Space dataSpace() {
        long now = nanoTime.getAsLong();
        Cached c = cached;
        if (c != null && now - c.atNanos() < CACHE_NANOS) return c.space();
        SpaceProbe.Space s = probe.usableSpace(dataFolder);
        cached = new Cached(s, now);
        return s;
    }

    private static String why(SpaceProbe.Space s) {
        if (s instanceof SpaceProbe.Unavailable u) return u.reason();
        if (s instanceof SpaceProbe.Failed f) return f.error();
        return "sem medida";
    }
}

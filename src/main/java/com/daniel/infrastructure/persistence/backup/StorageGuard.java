package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;

import java.nio.file.Path;
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
    private final SpaceProbe probe;
    private final Supplier<StorageLimits> limits;
    private final LongSupplier nanoTime;
    private volatile Cached cached;
    private volatile String lastWarning;

    public StorageGuard(Path databaseFile, SpaceProbe probe, Supplier<StorageLimits> limits, LongSupplier nanoTime) {
        this.dataFolder = databaseFile.toAbsolutePath().getParent();
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

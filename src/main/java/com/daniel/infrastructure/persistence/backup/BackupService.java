package com.daniel.infrastructure.persistence.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verified copies of the live database (DECISAO-ARMAZENAMENTO §5.1 and §5.3).
 *
 * <p>A copy is {@code VACUUM INTO} a registered temp in the destination folder, verified on a new
 * read-only connection (integrity, schema version and the same content fingerprint as the source),
 * forced to disk and moved atomically to a new unique name. {@code PRAGMA data_version} read before
 * and after on the source connection refuses a copy if another connection committed meanwhile.
 *
 * <p>In the managed folder every copy is registered in {@link BackupManifest}; retention
 * ({@link RetentionPolicy}) only ever deletes registered automatic copies whose names match
 * {@link BackupNames}, as regular files directly inside a folder that is not a link. Copies saved to
 * another folder ({@link #createIn(Path)}) are the user's and are never touched again.
 *
 * <p>One operation at a time: concurrent requests get {@link Outcome#BUSY}.
 */
public final class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** How the service reaches the live database. */
    public interface SourceAccess {
        <T> T use(SqlWork<T> work) throws SQLException;

        /** A fresh connection per use, closed afterwards (production: {@code Database::newConnection}). */
        static SourceAccess opening(SqlSupplier<Connection> opener) {
            return new SourceAccess() {
                @Override
                public <T> T use(SqlWork<T> work) throws SQLException {
                    try (Connection c = opener.get()) {
                        return work.run(c);
                    }
                }
            };
        }

        /** A caller-owned connection that is not closed here (the connection about to migrate). */
        static SourceAccess borrowed(Connection conn) {
            return new SourceAccess() {
                @Override
                public <T> T use(SqlWork<T> work) throws SQLException {
                    return work.run(conn);
                }
            };
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    public enum Outcome {
        /** A new verified copy was published. */
        CREATED,
        /** Nothing changed since the last valid copy, which was re-checked and is used instead. */
        REUSED,
        /** Nothing changed since the last valid copy; no new copy (manual/daily). */
        UNCHANGED,
        /** New or empty database: nothing worth copying. */
        EMPTY,
        /** The daily copy already exists for today. */
        ALREADY_TODAY,
        /** Another data operation is running. */
        BUSY,
        /** Nothing was published; {@link Result#message()} says why. */
        FAILED,
        /** BRK-31: the measured free space is below the estimate of the copy; nothing was copied. */
        NO_SPACE,
        /** BRK-31: the free space could not be measured; a manual copy needs an explicit confirmation. */
        UNMEASURED,
        /** BRK-31: automatic copy postponed (not enough measured space, or no measure). */
        POSTPONED
    }

    /**
     * @param file          published or reused copy (null otherwise)
     * @param fingerprint   content digest of that copy (null otherwise, or for an unverified raw copy)
     * @param changeCounter SQLite header change counter of the source the copy holds (-1 when unknown)
     * @param warnings      problems the copy shares with its source, or retention notices
     */
    public record Result(Outcome outcome, BackupKind kind, Path file, Instant createdAt, String fingerprint,
                         long changeCounter, String message, List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }

        public boolean ok() {
            return outcome == Outcome.CREATED || outcome == Outcome.REUSED;
        }
    }

    public record CopyInfo(String file, BackupKind kind, Instant createdAt, long sizeBytes, boolean healthy) {
    }

    /**
     * @param notice problem with the registry or the budget, in pt-BR (null when none)
     */
    public record Status(Path folder, Optional<CopyInfo> lastValid, int managedCopies, long managedBytes,
                         RetentionPolicy policy, String notice, Optional<Result> lastAutomatic) {
    }

    /** Test seams between the steps of a copy. */
    interface Hooks {
        default void afterSourceSnapshot() throws SQLException {
        }

        default void beforePublish(Path verifiedTemp) throws IOException {
        }
    }

    private final Path databaseFile;
    private final Path folder;
    private final SourceAccess source;
    private final Clock clock;
    private final RetentionPolicy policy;
    private final Supplier<String> suffixes;
    private final ReentrantLock busy = new ReentrantLock();
    private volatile Hooks hooks = new Hooks() { };
    private volatile SpaceCheck spaceCheck = (estimate, places) -> new StorageGuard.Allowed(-1);
    private volatile Result lastAutomatic;
    /** Link folder or budget conflict, from the last rotation (shown on the screen). */
    private volatile String rotationNotice;
    /** An unreadable registry was kept aside in this session (shown on the screen). */
    private volatile String asideNotice;
    /** Same notice, still to be reported in the result of the operation that set it aside (under busy). */
    private String freshAside;

    public BackupService(Path databaseFile, Path folder, SourceAccess source, Clock clock, RetentionPolicy policy) {
        this(databaseFile, folder, source, clock, policy, BackupService::randomSuffix);
    }

    BackupService(Path databaseFile, Path folder, SourceAccess source, Clock clock, RetentionPolicy policy,
                  Supplier<String> suffixes) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.folder = folder.toAbsolutePath().normalize();
        this.source = source;
        this.clock = clock;
        this.policy = policy;
        this.suffixes = suffixes;
    }

    /** {@code <data folder>/backups}: same volume as the database (the screen says so). */
    public static Path defaultFolder(Path databaseFile) {
        return databaseFile.toAbsolutePath().normalize().getParent().resolve("backups");
    }

    public Path folder() {
        return folder;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public RetentionPolicy policy() {
        return policy;
    }

    void hooks(Hooks h) {
        this.hooks = h;
    }

    ReentrantLock busyLock() {
        return busy;
    }

    /** BRK-31: whether an operation fits the measured free space; installed by AppConfig (StorageGuard). */
    @FunctionalInterface
    public interface SpaceCheck {
        StorageGuard.Decision check(SpaceEstimate.Estimate estimate, Map<SpaceEstimate.Place, Path> places);
    }

    /** Without one, every operation is allowed (the copy itself still fails cleanly on a full disk). */
    public void spaceCheck(SpaceCheck check) {
        this.spaceCheck = check;
    }

    StorageGuard.Decision checkSpace(SpaceEstimate.Estimate estimate, Map<SpaceEstimate.Place, Path> places) {
        return spaceCheck.check(estimate, places);
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** "Criar cópia de segurança": skipped when empty or unchanged since the last valid copy. */
    public Result createManual() {
        return create(BackupKind.MANUAL, source, false);
    }

    /** The same, after the user confirmed copying without a measure of the free space (§5.3). */
    public Result createManual(boolean confirmedWithoutMeasure) {
        return create(BackupKind.MANUAL, source, confirmedWithoutMeasure);
    }

    /** First opening of the day: one daily copy, only when the database changed and has data. */
    public Result dailyIfNeeded() {
        Result r = create(BackupKind.DAILY, source);
        lastAutomatic = r;
        return r;
    }

    /**
     * Copy before an operation that replaces or rewrites the database (restore, maintenance). Reuses
     * the last valid copy when nothing changed and its file still matches; never skipped for an empty
     * database, so there is always a way back.
     */
    public Result ensureCurrentCopy(BackupKind kind) {
        if (kind != BackupKind.PRE_RESTORE && kind != BackupKind.PRE_MAINTENANCE) {
            throw new IllegalArgumentException("only for restore/maintenance: " + kind);
        }
        return create(kind, source);
    }

    /**
     * Pre-migration hook: copies an existing database before a migration changes it. Throws when the
     * copy fails, so the migration does not run.
     */
    public void beforeMigration(Connection candidate) {
        Result r = create(BackupKind.PRE_MIGRATION, SourceAccess.borrowed(candidate));
        lastAutomatic = r;
        if (r.outcome() == Outcome.NO_SPACE) {
            throw new IllegalStateException("A atualização dos dados foi bloqueada antes de começar: " + r.message()
                    + " Os dados não foram alterados; tente de novo depois de liberar espaço.");
        }
        if (r.outcome() == Outcome.FAILED || r.outcome() == Outcome.BUSY) {
            throw new IllegalStateException("A cópia de segurança antes de atualizar os dados falhou ("
                    + r.message() + "); os dados não foram alterados.");
        }
    }

    /** "Salvar cópia em outro local": verified manual copy in a folder of the user's choice, unmanaged. */
    public Result createIn(Path destination) {
        return createIn(destination, false);
    }

    /** The same, after the user confirmed copying without a measure of the free space (§5.3). */
    public Result createIn(Path destination, boolean confirmedWithoutMeasure) {
        if (!busy.tryLock()) return simple(Outcome.BUSY, BackupKind.MANUAL, "outra operação com os dados está em andamento");
        try {
            Path dest = destination.toAbsolutePath().normalize();
            if (!Files.isDirectory(dest)) return simple(Outcome.FAILED, BackupKind.MANUAL, "a pasta escolhida não existe");
            if (dest.equals(folder)) return createLocked(BackupKind.MANUAL, source, confirmedWithoutMeasure);
            if (!source.use(c -> DatabaseFingerprint.userRows(DatabaseFingerprint.countRows(c)) > 0)) {
                return simple(Outcome.EMPTY, BackupKind.MANUAL, "ainda não há dados para copiar");
            }
            Result noRoom = spaceRefusal(BackupKind.MANUAL, dest, SpaceEstimate.Place.DESTINATION, confirmedWithoutMeasure);
            if (noRoom != null) return noRoom;
            return copy(BackupKind.MANUAL, source, dest, null);
        } catch (Exception e) {
            return failure(BackupKind.MANUAL, e, destination);
        } finally {
            busy.unlock();
        }
    }

    /** Removes temps left by an interrupted copy or restore (registered in the manifest only). */
    public int cleanupInterrupted() {
        if (!busy.tryLock()) return 0;
        try {
            if (!Files.isDirectory(folder)) return 0;
            BackupManifest.Loaded loaded = BackupManifest.load(folder);
            if (loaded.status() != BackupManifest.Status.VALID) return 0;
            List<BackupManifest.Entry> entries = new ArrayList<>(loaded.entries());
            int removed = 0;
            for (BackupManifest.Entry e : loaded.entries()) {
                if (!e.inProgress()) continue;
                if (deleteManaged(e.file()) && deleteManagedJournalOf(e.file())) {
                    entries.remove(e);
                    removed++;
                }
            }
            if (removed > 0) BackupManifest.save(folder, entries);
            return removed;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cleanup of interrupted copies failed", e);
            return 0;
        } finally {
            busy.unlock();
        }
    }

    public Optional<Result> lastAutomatic() {
        return Optional.ofNullable(lastAutomatic);
    }

    /** Read-only view for the screen. */
    public Status status() {
        BackupManifest.Loaded loaded = BackupManifest.load(folder);
        String problem = loaded.status() == BackupManifest.Status.INVALID
                ? "O registro das cópias está ilegível (" + loaded.problem() + "); nenhuma cópia será apagada automaticamente."
                : joinNotices(asideNotice, rotationNotice);
        List<BackupManifest.Entry> valid = existingValid(loaded.entries());
        long bytes = valid.stream().mapToLong(BackupManifest.Entry::sizeBytes).sum();
        Optional<CopyInfo> last = valid.stream().max(Comparator.comparing(BackupManifest.Entry::created))
                .map(e -> new CopyInfo(e.file(), BackupKind.fromTag(e.kind()).orElse(BackupKind.MANUAL), e.created(),
                        e.sizeBytes(), "ok".equals(e.health())));
        return new Status(folder, last, valid.size(), bytes, policy, problem, lastAutomatic());
    }

    // ------------------------------------------------------------------
    // Restore support (package-private, used by RestoreService under the same lock)
    // ------------------------------------------------------------------

    /** Copies {@code chosen} to a registered temp in the managed folder; the original is never touched. */
    Path stageForRestore(Path chosen) throws IOException {
        Files.createDirectories(folder);
        List<BackupManifest.Entry> entries = loadForWrite();
        String name = BackupNames.tempName(true, LocalDateTime.now(clock), suffixes.get());
        BackupManifest.Entry pending = pendingEntry(name, BackupNames.RESTORE_SOURCE_TAG);
        entries.add(pending);
        BackupManifest.save(folder, entries);
        Path temp = folder.resolve(name);
        try {
            Files.copy(chosen, temp);
            return temp;
        } catch (IOException e) {
            discardStaged(temp);
            throw e;
        }
    }

    /** Deletes a staged temp and its registration. */
    void discardStaged(Path temp) {
        try {
            String name = temp.getFileName().toString();
            deleteManaged(name);
            deleteManagedJournalOf(name);
            BackupManifest.Loaded loaded = BackupManifest.load(folder);
            if (loaded.status() == BackupManifest.Status.VALID) {
                List<BackupManifest.Entry> entries = new ArrayList<>(loaded.entries());
                if (entries.removeIf(e -> e.inProgress() && e.file().equals(name))) BackupManifest.save(folder, entries);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not discard staged restore file", e);
        }
    }

    /** Byte copy of a damaged live file; it is not registered and the application never deletes it. */
    record RawCopy(Path file, String sha256, long sizeBytes) {
    }

    /**
     * Keeps the live database file exactly as it is, for a restore over data too damaged for a
     * verified copy. The caller holds exclusivity (no connection open, the exclusive lock probed), so
     * the file is quiet; a leftover {@code -journal} means an interrupted transaction and is refused.
     * The copy must match the file byte for byte.
     */
    RawCopy preserveRaw() throws IOException, CopyFailure {
        Path journal = databaseFile.resolveSibling(databaseFile.getFileName() + "-journal");
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            throw new CopyFailure("há uma transação interrompida nos dados atuais (arquivo -journal ao lado deles)");
        }
        Files.createDirectories(folder);
        LocalDateTime now = LocalDateTime.now(clock);
        for (int attempt = 0; attempt < 8; attempt++) {
            Path target = folder.resolve(BackupNames.damagedName(now, suffixes.get()));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue; // never replace an existing file
            Files.copy(databaseFile, target);
            force(target);
            String copied = sha256(target);
            if (!copied.equals(sha256(databaseFile))) {
                throw new CopyFailure("a cópia do arquivo atual não conferiu byte a byte (ela ficou em " + target + ")");
            }
            return new RawCopy(target, copied, Files.size(target));
        }
        throw new CopyFailure("não foi possível escolher um nome livre para guardar o arquivo atual");
    }

    /**
     * Header bytes a restore (sqlite3_backup) writes for the destination, so they may differ from the
     * file it came from (sqlite.org/fileformat2.html §1.3; backup.c): file change counter (24-27) and
     * version-valid-for (92-95), set by the destination's commit; schema cookie (40-43), set to the
     * destination's own value + 1; SQLITE_VERSION_NUMBER (96-99) of the library that committed.
     * Characterized with sqlite-jdbc 3.51.2.0 in BackupServiceTest.
     */
    private static boolean restoreStamp(int offset) {
        return (offset >= 24 && offset < 28) || (offset >= 40 && offset < 44) || (offset >= 92 && offset < 100);
    }

    /**
     * {@code restored} holds the same database as {@code original} after a restore from it: equal size,
     * equal bytes after the 100-byte header, and an equal header except the four restore stamps. Page
     * size, text encoding, format versions, page count, freelist and settings must all match; an
     * in-header page count that was stale in {@code original} is refused (conservative: the file is
     * still kept, only reported as not confirmed).
     */
    static boolean samePagesAsRestored(Path original, Path restored) throws IOException {
        long size = Files.size(original);
        if (size != Files.size(restored) || size < 100) return false;
        try (InputStream ia = Files.newInputStream(original); InputStream ib = Files.newInputStream(restored)) {
            byte[] ha = ia.readNBytes(100);
            byte[] hb = ib.readNBytes(100);
            if (ha.length != 100 || hb.length != 100) return false;
            for (int i = 0; i < 100; i++) {
                if (ha[i] != hb[i] && !restoreStamp(i)) return false;
            }
            byte[] ba = new byte[1 << 16];
            byte[] bb = new byte[1 << 16];
            while (true) {
                int na = ia.readNBytes(ba, 0, ba.length);
                int nb = ib.readNBytes(bb, 0, bb.length);
                if (na != nb || !java.util.Arrays.equals(ba, 0, na, bb, 0, nb)) return false;
                if (na < ba.length) return true;
            }
        }
    }

    /** Created-at of a managed copy, when {@code file} is one. */
    Optional<Instant> managedCreatedAt(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (!folder.equals(abs.getParent())) return Optional.empty();
        String name = abs.getFileName().toString();
        return BackupManifest.load(folder).entries().stream()
                .filter(e -> e.valid() && e.file().equals(name)).findFirst().map(BackupManifest.Entry::created);
    }

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    Result create(BackupKind kind, SourceAccess via) {
        return create(kind, via, false);
    }

    private Result create(BackupKind kind, SourceAccess via, boolean confirmedWithoutMeasure) {
        if (!busy.tryLock()) return simple(Outcome.BUSY, kind, "outra operação com os dados está em andamento");
        try {
            return createLocked(kind, via, confirmedWithoutMeasure);
        } catch (Exception e) {
            return failure(kind, e, folder);
        } finally {
            busy.unlock();
        }
    }

    private Result createLocked(BackupKind kind, SourceAccess via, boolean confirmedWithoutMeasure) throws Exception {
        boolean mayBeEmpty = kind == BackupKind.PRE_RESTORE || kind == BackupKind.PRE_MAINTENANCE;
        if (!mayBeEmpty && !via.use(c -> DatabaseFingerprint.userRows(DatabaseFingerprint.countRows(c)) > 0)) {
            return simple(Outcome.EMPTY, kind, "ainda não há dados para copiar");
        }
        Files.createDirectories(folder);
        List<BackupManifest.Entry> entries = loadForWrite();
        List<BackupManifest.Entry> valid = existingValid(entries);
        if (kind == BackupKind.DAILY) {
            LocalDate today = LocalDate.now(clock);
            ZoneId zone = clock.getZone();
            boolean done = valid.stream().anyMatch(e -> BackupKind.DAILY.tag().equals(e.kind())
                    && LocalDate.ofInstant(e.created(), zone).equals(today));
            if (done) return simple(Outcome.ALREADY_TODAY, kind, "a cópia de hoje já existe");
        }
        long counter = changeCounter(databaseFile);
        Optional<BackupManifest.Entry> last = valid.stream().max(Comparator.comparing(BackupManifest.Entry::created));
        // A pre-migration copy is always new: the latest one is protected from rotation as such.
        boolean reusable = kind != BackupKind.PRE_MIGRATION;
        if (reusable && counter >= 0 && last.isPresent() && last.get().changeCounter() == counter
                && stillIntact(last.get())) {
            BackupManifest.Entry e = last.get();
            Outcome o = (kind == BackupKind.MANUAL || kind == BackupKind.DAILY) ? Outcome.UNCHANGED : Outcome.REUSED;
            return new Result(o, kind, folder.resolve(e.file()), e.created(), e.fingerprint(), e.changeCounter(),
                    "nada mudou desde a última cópia, que continua válida", asideWarnings());
        }
        Result noRoom = spaceRefusal(kind, folder, SpaceEstimate.Place.COPIES, confirmedWithoutMeasure);
        if (noRoom != null) return noRoom;
        return copy(kind, via, folder, entries);
    }

    /**
     * BRK-31 §5.3, before anything is copied: a manual copy is refused below the estimate and, without a measure,
     * waits for an explicit confirmation; an automatic copy is postponed in both cases; a pre-migration copy is
     * refused below the estimate (the migration does not run) and tried without a measure. Pre-restore and
     * pre-maintenance copies are part of the estimate of the operation that asks for them.
     */
    private Result spaceRefusal(BackupKind kind, Path dest, SpaceEstimate.Place place, boolean confirmedWithoutMeasure)
            throws IOException {
        if (kind == BackupKind.PRE_RESTORE || kind == BackupKind.PRE_MAINTENANCE) return null;
        long dbBytes = Files.size(databaseFile);
        SpaceEstimate.Estimate need = kind == BackupKind.PRE_MIGRATION ? SpaceEstimate.migration(dbBytes)
                : SpaceEstimate.verifiedCopy(dbBytes, place);
        StorageGuard.Decision d = spaceCheck.check(need, Map.of(SpaceEstimate.Place.DATA, databaseFile.getParent(),
                place, dest));
        if (d instanceof StorageGuard.Refused r) {
            if (kind == BackupKind.DAILY) return simple(Outcome.POSTPONED, kind, r.message() + " A cópia automática foi adiada.");
            return simple(Outcome.NO_SPACE, kind, r.message() + " Nenhuma cópia foi criada.");
        }
        if (d instanceof StorageGuard.Unmeasured u) {
            if (kind == BackupKind.DAILY) {
                return simple(Outcome.POSTPONED, kind, u.warning() + " A cópia automática foi adiada: nada automático roda sem medida.");
            }
            if (kind == BackupKind.MANUAL && !confirmedWithoutMeasure) {
                return simple(Outcome.UNMEASURED, kind, u.warning() + " Confirme para criar a cópia mesmo assim.");
            }
        }
        return null;
    }

    /**
     * @param managedEntries manifest content when {@code dest} is the managed folder, null otherwise
     */
    private Result copy(BackupKind kind, SourceAccess via, Path dest, List<BackupManifest.Entry> managedEntries)
            throws Exception {
        boolean managed = managedEntries != null;
        LocalDateTime now = LocalDateTime.now(clock);
        Instant createdAt = clock.instant();
        String tempName = BackupNames.tempName(false, now, suffixes.get());
        Path temp = dest.resolve(tempName);
        BackupManifest.Entry pending = null;
        if (managed) {
            pending = pendingEntry(tempName, kind.tag());
            managedEntries.add(pending);
            BackupManifest.save(dest, managedEntries);
        }
        boolean published = false;
        try {
            Snapshot snap = via.use(conn -> snapshotInto(conn, temp));
            BackupVerifier.Report report = BackupVerifier.verify(temp);
            if (!report.faithfulTo(snap.fingerprint())) {
                throw new CopyFailure("a cópia não conferiu com os dados de origem"
                        + (report.integrityOk() ? "" : " (" + report.integrityDetail() + ")"));
            }
            force(temp);
            long size = Files.size(temp);
            String sha = sha256(temp);
            hooks.beforePublish(temp);
            Path target = publish(temp, dest, kind, now);
            published = true;
            List<String> warnings = new ArrayList<>(report.healthWarnings());
            if (managed) {
                DatabaseFingerprint fp = snap.fingerprint();
                BackupManifest.Entry done = new BackupManifest.Entry(target.getFileName().toString(), kind.tag(),
                        BackupManifest.VALID, createdAt.toString(), fp.schemaVersion(), snap.changeCounter(), size, sha,
                        fp.contentSha256(), fp.rowCounts(), warnings.isEmpty() ? "ok" : String.join("; ", warnings));
                managedEntries.remove(pending);
                managedEntries.add(done);
                registerPublished(dest, managedEntries, warnings);
            }
            warnings.addAll(asideWarnings());
            return new Result(Outcome.CREATED, kind, target, createdAt, snap.fingerprint().contentSha256(),
                    snap.changeCounter(), "cópia criada e conferida", warnings);
        } finally {
            if (!published) {
                deleteQuietly(temp);
                deleteQuietly(dest.resolve(tempName + "-journal"));
                if (managed) {
                    managedEntries.remove(pending);
                    try {
                        BackupManifest.save(dest, managedEntries);
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "manifest not updated after a failed copy; cleaned on next start", e);
                    }
                }
            }
        }
    }

    private record Snapshot(DatabaseFingerprint fingerprint, long changeCounter) {
    }

    private Snapshot snapshotInto(Connection conn, Path temp) throws SQLException {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master")) {
                rs.next(); // fixes this connection's data_version baseline
            }
            long before = dataVersion(st);
            long counter;
            try {
                counter = changeCounter(databaseFile);
            } catch (IOException e) {
                throw new SQLException("cabeçalho do banco ilegível: " + e.getMessage(), e);
            }
            DatabaseFingerprint fp = DatabaseFingerprint.of(conn);
            hooks.afterSourceSnapshot();
            try (PreparedStatement ps = conn.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, temp.toString());
                ps.execute();
            }
            if (dataVersion(st) != before) {
                throw new CopyFailure("os dados mudaram durante a cópia; tente de novo");
            }
            return new Snapshot(fp, counter);
        }
    }

    private static long dataVersion(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA data_version")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private Path publish(Path temp, Path dest, BackupKind kind, LocalDateTime now) throws IOException, CopyFailure {
        for (int attempt = 0; attempt < 8; attempt++) {
            Path target = dest.resolve(BackupNames.copyName(kind, now, suffixes.get()));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue; // never replace an existing file
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        }
        throw new CopyFailure("não foi possível escolher um nome livre para a cópia");
    }

    /**
     * Registers a published copy and applies the retention. From here on the copy exists and is
     * verified, so a failure is a warning, never a failed copy (a failed pre-migration copy would keep
     * the app from opening). Inside another data operation (the copies a restore makes, including the
     * pre-migration copy of a restored older file) the rotation waits for the next copy, so it can
     * never delete the way back of the operation that is still running.
     */
    private void registerPublished(Path dest, List<BackupManifest.Entry> entries, List<String> warnings) {
        try {
            BackupManifest.save(dest, entries);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "copy published but the registry was not updated", e);
            warnings.add("a cópia foi criada e conferida, mas o registro das cópias não pôde ser atualizado ("
                    + describe(e, dest) + "); ela não será apagada automaticamente");
            return;
        }
        if (busy.getHoldCount() > 1) return;
        try {
            warnings.addAll(rotate(entries));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "retention failed after a published copy", e);
            warnings.add("a cópia foi criada e conferida, mas as cópias antigas não puderam ser revisadas agora ("
                    + describe(e, dest) + ")");
        }
    }

    /** Applies the retention plan; returns notices for the result. */
    private List<String> rotate(List<BackupManifest.Entry> entries) throws IOException {
        List<String> out = new ArrayList<>();
        if (isLink(folder)) {
            rotationNotice = "A pasta das cópias é um atalho para outro lugar; nenhuma cópia é apagada automaticamente.";
            out.add(rotationNotice);
            return out;
        }
        List<BackupManifest.Entry> present = existingValid(entries);
        entries.removeIf(e -> e.valid() && !present.contains(e)); // files removed by the user: forget them
        RetentionPolicy.Plan plan = policy.plan(present);
        int deleted = 0;
        for (BackupManifest.Entry e : plan.delete()) {
            try {
                if (deleteManaged(e.file())) {
                    entries.remove(e);
                    deleted++;
                } else {
                    out.add("não foi possível apagar a cópia antiga " + e.file());
                }
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "could not delete an old copy", ex);
                out.add("não foi possível apagar a cópia antiga " + e.file() + " (" + describe(ex, folder) + ")");
            }
        }
        BackupManifest.save(folder, entries);
        if (plan.budgetDeleted() > 0 && deleted > 0) {
            out.add("para caber no limite de " + Sizes.text(policy.budgetBytes()) + ", " + plan.budgetDeleted()
                    + " cópia(s) automática(s) antiga(s) foram apagadas");
        }
        if (plan.overBudget()) {
            rotationNotice = "As cópias que o BreakInv nunca apaga sozinho (as manuais e as mais recentes que guardam "
                    + "o caminho de volta) somam " + Sizes.text(plan.protectedBytes()) + ", acima do limite de "
                    + Sizes.text(policy.budgetBytes()) + ". Nenhuma cópia foi apagada para caber no limite; apague "
                    + "cópias manuais que não forem mais necessárias.";
            out.add(rotationNotice);
        } else {
            rotationNotice = null;
        }
        return out;
    }

    /** The registry-aside notice, once, in the result of the operation that set it aside. */
    private List<String> asideWarnings() {
        String fresh = freshAside;
        freshAside = null;
        return fresh == null ? List.of() : List.of(fresh);
    }

    private static String joinNotices(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " " + b;
    }

    // ------------------------------------------------------------------
    // Manifest and file helpers
    // ------------------------------------------------------------------

    private List<BackupManifest.Entry> loadForWrite() throws IOException {
        BackupManifest.Loaded loaded = BackupManifest.load(folder);
        if (loaded.status() == BackupManifest.Status.INVALID) {
            Path aside = BackupManifest.setAside(folder, LocalDateTime.now(clock).format(STAMP));
            asideNotice = "O registro das cópias estava ilegível (" + loaded.problem() + "); ele foi guardado como "
                    + aside.getFileName() + " e as cópias anteriores não serão apagadas automaticamente.";
            freshAside = asideNotice;
            LOG.warning("backup manifest unreadable; kept aside as " + aside.getFileName());
            return new ArrayList<>();
        }
        return new ArrayList<>(loaded.entries());
    }

    private BackupManifest.Entry pendingEntry(String tempName, String kindTag) {
        return new BackupManifest.Entry(tempName, kindTag, BackupManifest.IN_PROGRESS, clock.instant().toString(),
                0, -1, 0, null, null, Map.of(), null);
    }

    private List<BackupManifest.Entry> existingValid(List<BackupManifest.Entry> entries) {
        List<BackupManifest.Entry> out = new ArrayList<>();
        for (BackupManifest.Entry e : entries) {
            if (e.valid() && Files.isRegularFile(folder.resolve(e.file()), LinkOption.NOFOLLOW_LINKS)) out.add(e);
        }
        return out;
    }

    private boolean stillIntact(BackupManifest.Entry e) {
        try {
            Path p = folder.resolve(e.file());
            return Files.size(p) == e.sizeBytes() && sha256(p).equals(e.sha256());
        } catch (IOException ex) {
            return false;
        }
    }

    /** Deletes a file of this feature directly inside the managed folder; refuses anything else. */
    private boolean deleteManaged(String name) throws IOException {
        boolean ours = BackupNames.isTemp(name)
                || BackupNames.kindOfCopy(name).filter(BackupKind::automatic).isPresent();
        if (!ours || isLink(folder)) return false;
        Path p = folder.resolve(name);
        if (!folder.equals(p.getParent())) return false;
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true;
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) return false;
        Files.delete(p);
        return true;
    }

    private boolean deleteManagedJournalOf(String tempName) throws IOException {
        if (!BackupNames.isTemp(tempName) || isLink(folder)) return false;
        Path j = folder.resolve(tempName + "-journal");
        if (!Files.exists(j, LinkOption.NOFOLLOW_LINKS)) return true;
        BasicFileAttributes a = Files.readAttributes(j, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) return false;
        Files.delete(j);
        return true;
    }

    private static boolean isLink(Path dir) {
        try {
            BasicFileAttributes a = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isSymbolicLink() || a.isOther();
        } catch (IOException e) {
            return true;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not delete " + p.getFileName(), e);
        }
    }

    private static void force(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    static String sha256(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** SQLite header "file change counter" (offset 24), or -1 for a missing/new file. */
    static long changeCounter(Path dbFile) throws IOException {
        try (FileChannel ch = FileChannel.open(dbFile, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            if (ch.read(buf, 24) < 4) return -1;
            buf.flip();
            return Integer.toUnsignedLong(buf.getInt());
        } catch (NoSuchFileException e) {
            return -1;
        }
    }

    private static String randomSuffix() {
        return String.format(Locale.ROOT, "%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    private static Result simple(Outcome o, BackupKind kind, String message) {
        return new Result(o, kind, null, null, null, -1, message, List.of());
    }

    private Result failure(BackupKind kind, Exception e, Path where) {
        LOG.log(Level.WARNING, "backup failed (" + kind.tag() + ")", e);
        return simple(Outcome.FAILED, kind, describe(e, where));
    }

    /** pt-BR reason, without row values. */
    static String describe(Throwable e, Path where) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CopyFailure cf) return cf.getMessage();
            if (t instanceof AccessDeniedException) return "sem permissão para gravar em " + where;
            String m = String.valueOf(t.getMessage());
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("sqlite_full") || lower.contains("disk is full") || lower.contains("not enough space")
                    || lower.contains("espaço insuficiente")) {
                return "o disco está cheio";
            }
            if (lower.contains("sqlite_cantopen") || lower.contains("unable to open")) {
                return "não foi possível criar o arquivo da cópia em " + where + " (verifique a permissão de gravação)";
            }
            if (lower.contains("sqlite_busy") || lower.contains("database is locked")) {
                return "os dados estavam em uso; tente de novo";
            }
            if (t instanceof FileSystemException fse && fse.getReason() != null) {
                return "erro ao gravar a cópia: " + fse.getReason();
            }
        }
        String first = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
        return "erro ao criar a cópia: " + first;
    }

    /** A copy step failed for a known reason (message is shown as is). */
    static final class CopyFailure extends SQLException {
        CopyFailure(String message) {
            super(message);
        }
    }
}

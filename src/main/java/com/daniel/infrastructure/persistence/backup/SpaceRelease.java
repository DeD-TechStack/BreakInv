package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * "Liberar espaço" without a screen (BRK-32 S2, flow A of the inventory): measures the pages, makes sure there is a
 * verified copy, compacts the data file ({@code VACUUM}) only when the expected gain passes the threshold, and checks
 * that the content is identical afterwards. When something goes wrong the data stay as they were or come back from the
 * copy. Nothing is deleted: not the copies, not the {@code -journal}, not the pre-maintenance copy.
 *
 * <p>The steps, in order: the {@code busy} lock of the {@link BackupService}; exclusivity among the instances; the
 * pages and the threshold; the space guard, always with the estimate of a <em>new</em> copy (the worst case; the reuse
 * of a copy is only known inside {@link BackupService#ensureCurrentCopy}); the copy; the expected gain; and, with every
 * other reader and writer held off ({@link Database#runExclusive}), the probe {@code BEGIN EXCLUSIVE}, the
 * {@code changedSince} check, the compaction, the verification and the reopening of the shared connection. Every exit
 * gives the instance guard and the {@code busy} lock back.
 *
 * <p>The failures of the compaction and of its verification share one ladder: the data are checked ("intact" means
 * {@code integrity_check} ok and the content digest of the pre-maintenance copy); when the compactor threw and the data
 * are intact they are kept; when the check after the compaction diverged, or the data are not intact, they come back
 * from the copy ({@code DB.restore}) and are checked again; a way
 * back that throws or does not check is critical, and the result names the copy. The pre-maintenance copy is not
 * protected by the retention: after a critical result a later rotation may delete it (risk R3, accepted; the screen of
 * S3 shows the path and asks to save the copy elsewhere).
 *
 * <p>The collaborators come in by constructor so the tests can replace each one: who reads the pages, who compacts, who
 * verifies, who goes back and who holds the exclusivity.
 */
public final class SpaceRelease {

    private static final Logger LOG = Logger.getLogger(SpaceRelease.class.getName());
    private static final org.sqlite.core.DB.ProgressObserver NO_PROGRESS = (remaining, pageCount) -> { };

    /** The threshold is a constant of the service (Q1 of the inventory), not one of the adjustable limits. */
    public static final long MIN_GAIN_BYTES = 1L << 20;

    public enum Outcome {
        /** Compacted, checked and the shared connection reopened (or only its reopening failed: see the detail). */
        FEITA,
        /** The reusable space, or the expected gain, does not pass the threshold; nothing was compacted. */
        SEM_GANHO,
        /** Another instance, the space guard, another reader or writer, or a change after the copy: nothing was touched. */
        RECUSADA,
        /** Another data operation is running. */
        BUSY,
        /** Something failed and the data are as they were, or are back from the copy and checked. */
        FALHOU_MANTIDA,
        /** The way back failed or did not check; the detail names the pre-maintenance copy. */
        FALHOU_CRITICA
    }

    /**
     * @param bytesBefore    D, the size of the data file before the operation (-1 when it was not measured)
     * @param bytesAfter     the size of the data file at the end (-1 when it could not be read)
     * @param freePagesBefore free pages before (-1 when not measured)
     * @param freePagesAfter  free pages at the end (-1 when they could not be read; that does not change the outcome)
     * @param detail         technical, for the log and for the screen of S3 to turn into words
     */
    public record Result(Outcome outcome, long bytesBefore, long bytesAfter, long freePagesBefore, long freePagesAfter,
                         String detail) {
    }

    /** Who reads the pages of the data file. */
    @FunctionalInterface
    interface Pages {
        DatabasePages.Reading read() throws SQLException;
    }

    /** Who compacts: on a new connection, in auto-commit, with no statement open. */
    @FunctionalInterface
    interface Compactor {
        void compact(Connection conn) throws SQLException;
    }

    /** Who verifies a database on a new connection (production: {@link BackupVerifier#verify(Connection)}). */
    @FunctionalInterface
    interface Verifier {
        BackupVerifier.Report verify(Connection conn);
    }

    /** Who goes back: puts the pre-maintenance copy over the data (production: {@code DB.restore}). */
    @FunctionalInterface
    interface WayBack {
        void restore(Connection conn, Path copy) throws SQLException;
    }

    /** Who holds the exclusivity (production: {@link Database#runExclusive}). */
    @FunctionalInterface
    interface Exclusive {
        Result run(Database.ExclusiveBody<Result> body) throws Exception;
    }

    private final BackupService backups;
    private final InstanceGuard guard;
    private final Pages pages;
    private final Compactor compactor;
    private final Verifier verifier;
    private final WayBack wayBack;
    private final Exclusive exclusive;

    /** The production wiring: the real pages, {@code VACUUM}, the verifier of the copies, {@code DB.restore}. */
    public SpaceRelease(BackupService backups, InstanceGuard guard) {
        this(backups, guard, new DatabasePages(backups.databaseFile())::read, SpaceRelease::vacuum,
                BackupVerifier::verify, SpaceRelease::restoreFromCopy, Database::runExclusive);
    }

    SpaceRelease(BackupService backups, InstanceGuard guard, Pages pages, Compactor compactor, Verifier verifier,
                 WayBack wayBack, Exclusive exclusive) {
        this.backups = backups;
        this.guard = guard;
        this.pages = pages;
        this.compactor = compactor;
        this.verifier = verifier;
        this.wayBack = wayBack;
        this.exclusive = exclusive;
    }

    /** What the service carries between the steps. */
    private record Plan(long d, long freeBefore, BackupService.Result copy) {
    }

    private sealed interface Attempt permits Compacted, Stopped, Failed {
    }

    /** Compacted and checked. */
    private record Compacted() implements Attempt {
    }

    /** Stopped before the compaction; the data were not touched. */
    private record Stopped(Result result) implements Attempt {
    }

    /** The compaction threw ({@code thrown}) or the check after it diverged ({@code divergence}); exactly one is set. */
    private record Failed(Throwable thrown, String divergence) implements Attempt {
    }

    // ------------------------------------------------------------------
    // The threshold
    // ------------------------------------------------------------------

    /**
     * The threshold, as a pure function: at least 1 MiB <em>and</em> at least 5% of the size of the data file D. Applied
     * to the reusable space (free pages times page size) before the copy, and to the expected gain (D minus the size of
     * the verified copy) after it.
     */
    public static boolean passesThreshold(long reusableBytes, long dataBytes) {
        return reusableBytes >= MIN_GAIN_BYTES && reusableBytes >= (dataBytes + 19) / 20;
    }

    // ------------------------------------------------------------------
    // The flow
    // ------------------------------------------------------------------

    /** Releases the space the data file does not use; blocks (a copy and a compaction): never on the FX thread. */
    public Result release() {
        ReentrantLock busy = backups.busyLock();
        if (!busy.tryLock()) return noMeasure(Outcome.BUSY, "outra operação com os dados está em andamento");
        try {
            if (!guard.tryAcquireExclusive()) return noMeasure(Outcome.RECUSADA, guard.refusal());
            try {
                return measureAndRun();
            } catch (RuntimeException e) {
                // the exclusive body below catches its own failures: what reaches this point happened before the data were touched
                LOG.log(Level.WARNING, "space release failed before touching the data", e);
                return noMeasure(Outcome.FALHOU_MANTIDA,
                        "a liberação de espaço falhou antes de tocar nos dados (" + why(e) + "). Nada foi alterado.");
            } finally {
                guard.releaseExclusive();
            }
        } finally {
            busy.unlock();
        }
    }

    private Result measureAndRun() {
        long d;
        DatabasePages.Reading before;
        try {
            d = Files.size(backups.databaseFile());
            before = pages.read();
        } catch (IOException | SQLException | RuntimeException e) {
            LOG.log(Level.WARNING, "space release: the pages could not be measured", e);
            return noMeasure(Outcome.FALHOU_MANTIDA,
                    "não foi possível medir o espaço dos dados (" + why(e) + "). Nada foi alterado.");
        }
        long free = before.freelistCount();
        if (!passesThreshold(before.reusableBytes(), d)) {
            return untouched(Outcome.SEM_GANHO, d, free, "o espaço reutilizável (" + Sizes.text(before.reusableBytes())
                    + ") não passa do limiar de pelo menos 1 MiB e 5% dos dados; nada foi feito");
        }
        String noRoom = spaceRefusal(SpaceEstimate.maintenance(d, false));
        if (noRoom != null) return untouched(Outcome.RECUSADA, d, free, noRoom);

        BackupService.Result copy = backups.ensureCurrentCopy(BackupKind.PRE_MAINTENANCE);
        if (!copy.ok()) {
            return untouched(Outcome.FALHOU_MANTIDA, d, free, "não foi possível guardar uma cópia antes de liberar "
                    + "espaço (" + copy.message() + "). Nada foi alterado.");
        }
        long copyBytes = size(copy.file());
        if (copyBytes < 0) {
            return untouched(Outcome.FALHOU_MANTIDA, d, free,
                    "não foi possível medir a cópia pré-manutenção em " + copy.file() + ". Nada foi alterado.");
        }
        if (!passesThreshold(d - copyBytes, d)) {
            return untouched(Outcome.SEM_GANHO, d, free, "o ganho previsto (" + Sizes.text(Math.max(0, d - copyBytes))
                    + ") não passa do limiar de pelo menos 1 MiB e 5% dos dados; a cópia ficou como cópia normal");
        }

        Plan plan = new Plan(d, free, copy);
        try {
            return exclusive.run(access -> compactExclusive(access, plan));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "space release: exclusive access refused", e);
            return untouched(Outcome.FALHOU_MANTIDA, d, free,
                    "não foi possível obter acesso exclusivo aos dados (" + why(e) + "). Nada foi alterado.");
        }
    }

    /** Step 4: below the estimate, and without a measure, the operation does not start. */
    private String spaceRefusal(SpaceEstimate.Estimate need) {
        StorageGuard.Decision decision = backups.checkSpace(need, Map.of(SpaceEstimate.Place.DATA,
                backups.databaseFile().getParent(), SpaceEstimate.Place.COPIES, backups.folder()));
        if (decision instanceof StorageGuard.Refused refused) return refused.message() + " Nada foi alterado.";
        if (decision instanceof StorageGuard.Unmeasured unmeasured) {
            return unmeasured.warning() + " A liberação de espaço só começa com o espaço livre medido. Nada foi alterado.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Inside the exclusivity
    // ------------------------------------------------------------------

    /** Total: every failure becomes a result, and the shared connection is reopened on every exit. */
    private Result compactExclusive(Database.ExclusiveAccess access, Plan plan) {
        Connection conn;
        try {
            conn = access.newConnection();
        } catch (SQLException | RuntimeException e) {
            reopenQuietly(access);
            return untouched(Outcome.FALHOU_MANTIDA, plan, "não foi possível abrir os dados para compactar ("
                    + why(e) + "). Nada foi alterado.");
        }
        Attempt attempt;
        try {
            attempt = compactOn(conn, plan);
        } finally {
            closeQuietly(conn);
        }
        if (attempt instanceof Stopped stopped) {
            reopenQuietly(access);
            return stopped.result();
        }
        if (attempt instanceof Failed failed) {
            Result result = recover(access, plan, failed);
            reopenQuietly(access);
            return result;
        }
        return succeeded(access, plan);
    }

    /** The probe, the {@code changedSince} check, the compaction and its verification, on one new connection. */
    private Attempt compactOn(Connection conn, Plan plan) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 1000");
            try {
                st.execute("BEGIN EXCLUSIVE");
                st.execute("ROLLBACK");
            } catch (SQLException e) {
                if (SqliteErrors.ofChain(e) == SqliteErrors.Category.BUSY) {
                    return new Stopped(untouched(Outcome.RECUSADA, plan, "outra operação está usando os dados; tente de "
                            + "novo em instantes. Nada foi alterado."));
                }
                return new Stopped(untouched(Outcome.FALHOU_MANTIDA, plan, "os dados não puderam ser reservados para a "
                        + "compactação (" + why(e) + "). Nada foi alterado."));
            }
        } catch (SQLException e) {
            return new Stopped(untouched(Outcome.FALHOU_MANTIDA, plan, "os dados não puderam ser preparados para a "
                    + "compactação (" + why(e) + "). Nada foi alterado."));
        }
        if (changedSince(plan.copy())) {
            return new Stopped(untouched(Outcome.RECUSADA, plan,
                    "os dados mudaram depois da cópia; tente de novo. Nada foi alterado."));
        }
        try {
            compactor.compact(conn);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "space release: the compaction failed", e);
            return new Failed(e, null);
        }
        String divergence = checkAfterCompaction(conn, plan);
        if (divergence != null) {
            return new Failed(null, divergence);
        }
        return new Compacted();
    }

    /** True when the header change counter moved since the copy (or cannot be read); an unknown counter never refuses. */
    private boolean changedSince(BackupService.Result copy) {
        if (copy.changeCounter() < 0) return false;
        try {
            return BackupService.changeCounter(backups.databaseFile()) != copy.changeCounter();
        } catch (IOException e) {
            return true;
        }
    }

    /** Null when the compacted data check against the pre-maintenance copy; otherwise the divergence. */
    private String checkAfterCompaction(Connection conn, Plan plan) {
        try {
            return divergence(verifier.verify(conn), plan.copy().fingerprint());
        } catch (RuntimeException e) {
            return "a conferência dos dados compactados não pôde ser feita (" + why(e) + ")";
        }
    }

    /**
     * "Intact" (and "checks"): the file is a readable SQLite database, {@code integrity_check} is ok and the content
     * digest is the one of the pre-maintenance copy. Null when it is; otherwise what differs.
     */
    private static String divergence(BackupVerifier.Report report, String expectedSha) {
        if (!report.sqliteFile() || !report.integrityOk()) {
            return "a verificação de integridade falhou (" + report.integrityDetail() + ")";
        }
        if (report.fingerprint() == null || !report.fingerprint().contentSha256().equals(expectedSha)) {
            return "o conteúdo não corresponde ao da cópia pré-manutenção";
        }
        return null;
    }

    /** Compacted and checked: measure the pages and the size, then reopen the shared connection. */
    private Result succeeded(Database.ExclusiveAccess access, Plan plan) {
        Result done = measured(Outcome.FEITA, plan, "dados compactados e conferidos");
        try {
            access.reopenShared();
            return done;
        } catch (RuntimeException e) {
            // the compacted file was checked on disk: the result stays FEITA and carries the error
            LOG.log(Level.WARNING, "space release: checked, but the shared connection did not reopen", e);
            return new Result(done.outcome(), done.bytesBefore(), done.bytesAfter(), done.freePagesBefore(),
                    done.freePagesAfter(), done.detail() + "; a conexão compartilhada não pôde ser reaberta (" + why(e)
                    + "); o arquivo compactado está conferido no disco");
        }
    }

    // ------------------------------------------------------------------
    // The ladder of failures
    // ------------------------------------------------------------------

    private Result recover(Database.ExclusiveAccess access, Plan plan, Failed failed) {
        String cause;
        if (failed.thrown() != null) {
            cause = "a compactação falhou (" + why(failed.thrown()) + ")";
            String problem = intactProblem(access, plan);
            if (problem == null) {
                return measured(Outcome.FALHOU_MANTIDA, plan,
                        cause + "; os dados continuam como estavam, conferidos. Nada foi alterado.");
            }
            cause += " e os dados não puderam ser conferidos como intactos (" + problem + ")";
        } else {
            cause = "a conferência depois da compactação divergiu (" + failed.divergence() + ")";
        }
        return goBack(access, plan, cause);
    }

    /** {@code DB.restore} from the pre-maintenance copy, then the check again; the copy is never deleted. */
    private Result goBack(Database.ExclusiveAccess access, Plan plan, String cause) {
        Path copy = plan.copy().file();
        LOG.warning("space release: going back to the pre-maintenance copy " + copy + ": " + cause);
        try {
            Connection conn = access.newConnection();
            try {
                wayBack.restore(conn, copy);
            } finally {
                closeQuietly(conn);
            }
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.SEVERE, "space release: the way back failed", e);
            return measured(Outcome.FALHOU_CRITICA, plan, cause + "; e a volta da cópia também falhou (" + why(e)
                    + "). Os dados anteriores estão guardados em " + copy + ".");
        }
        String again = intactProblem(access, plan);
        if (again == null) {
            return measured(Outcome.FALHOU_MANTIDA, plan, cause + "; os dados voltaram da cópia e foram conferidos.");
        }
        return measured(Outcome.FALHOU_CRITICA, plan, cause + "; os dados que voltaram da cópia não puderam ser "
                + "conferidos (" + again + "). Os dados anteriores estão guardados em " + copy + ".");
    }

    /** Null when the data file is intact, checked on a new connection; otherwise why it is not. */
    private String intactProblem(Database.ExclusiveAccess access, Plan plan) {
        Connection conn = null;
        try {
            conn = access.newConnection();
            return divergence(verifier.verify(conn), plan.copy().fingerprint());
        } catch (SQLException | RuntimeException e) {
            return "não foi possível conferir os dados: " + why(e);
        } finally {
            closeQuietly(conn);
        }
    }

    // ------------------------------------------------------------------
    // Production collaborators
    // ------------------------------------------------------------------

    /** The compaction: {@code VACUUM} on the connection, in auto-commit, with no statement left open. */
    static void vacuum(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("VACUUM");
        }
    }

    /**
     * The way back: {@code DB.restore} of the copy over the data. The return code is not trusted (the driver can report
     * success without restoring); the caller checks the data afterwards.
     */
    static void restoreFromCopy(Connection conn, Path copy) throws SQLException {
        conn.unwrap(SQLiteConnection.class).getDatabase().restore("main", copy.toString(), NO_PROGRESS);
    }

    // ------------------------------------------------------------------
    // Results and helpers
    // ------------------------------------------------------------------

    /** The sizes were not measured (busy, guard): -1. */
    private static Result noMeasure(Outcome outcome, String detail) {
        return new Result(outcome, -1, -1, -1, -1, detail);
    }

    /** The data file was not touched: the sizes after are the sizes before. */
    private static Result untouched(Outcome outcome, long d, long free, String detail) {
        return new Result(outcome, d, d, free, free, detail);
    }

    private static Result untouched(Outcome outcome, Plan plan, String detail) {
        return untouched(outcome, plan.d(), plan.freeBefore(), detail);
    }

    /** The size and the pages after, read now; what cannot be read stays -1 and does not change the outcome. */
    private Result measured(Outcome outcome, Plan plan, String detail) {
        long bytes = size(backups.databaseFile());
        long free = -1;
        try {
            free = pages.read().freelistCount();
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.FINE, "space release: the pages after could not be read", e);
        }
        return new Result(outcome, plan.d(), bytes, plan.freeBefore(), free, detail);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /** The category of the SQLite classification and the first line of the message, never a row value. */
    private static String why(Throwable e) {
        String first = String.valueOf(e.getMessage()).lines().findFirst().orElse(e.getClass().getSimpleName());
        return SqliteErrors.ofChain(e) + ": " + first;
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "space release: could not close a connection", e);
        }
    }

    private static void reopenQuietly(Database.ExclusiveAccess access) {
        try {
            access.reopenShared();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "space release: could not reopen the shared connection", e);
        }
    }
}

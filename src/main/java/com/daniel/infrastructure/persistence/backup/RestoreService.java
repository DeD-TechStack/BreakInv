package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.Schema;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replaces the live data with a chosen copy (DECISAO-ARMAZENAMENTO §5.2).
 *
 * <ol>
 *   <li>{@link #prepare(Path)}: the chosen file is copied to a registered temp (the original is never
 *       touched) and verified; the preview lists date, version and counts.</li>
 *   <li>{@link #restore(Preview)}, after the user's confirmation: a verified pre-restore copy of the
 *       current data, exclusivity (no other instance, then {@code BEGIN EXCLUSIVE} with a 1 s wait),
 *       {@code DB.restore} on a dedicated connection, verification of the live database against the
 *       fingerprint of the verified file, and the normal migration for an older copy.</li>
 * </ol>
 * The driver's return code is not trusted: sqlite-jdbc 3.51.2.0 can report success without restoring
 * when another connection holds the file. Any mismatch or failure after the replacement restores the
 * pre-restore copy and reports that the previous data were kept.
 */
public final class RestoreService {

    private static final Logger LOG = Logger.getLogger(RestoreService.class.getName());
    private static final org.sqlite.core.DB.ProgressObserver NO_PROGRESS = (remaining, pageCount) -> { };

    /**
     * @param chosen      the user's file (never modified)
     * @param staged      verified temp copy that will be restored
     * @param createdAt   registry date for a managed copy, otherwise the file's modification time
     */
    public record Preview(Path chosen, Path staged, BackupVerifier.Report report, Instant createdAt,
                          boolean fromRegistry, long sizeBytes) {
        public int schemaVersion() {
            return report.schemaVersion();
        }

        public boolean olderVersion() {
            return report.schemaVersion() < Schema.CURRENT_VERSION;
        }

        public long count(String table) {
            return report.fingerprint().count(table);
        }
    }

    /** Either a preview or the reason the file cannot be restored. */
    public record Prepared(Preview preview, String refusal) {
        public boolean ok() {
            return preview != null;
        }
    }

    public enum Outcome {
        RESTORED,
        /** Another instance or operation holds the data, or they changed meanwhile; nothing changed. */
        REFUSED,
        BUSY,
        /**
         * The current data are damaged, so no verified copy of them could be made; nothing changed.
         * The preview stays prepared: {@link #restoreOverDamaged(Preview)} after a second confirmation,
         * or {@link #discard(Preview)}.
         */
        CURRENT_DAMAGED,
        /** The replacement did not complete; the previous data are back and verified. */
        FAILED_KEPT,
        /** The previous data could not be verified after a failed replacement (see message). */
        FAILED_CRITICAL
    }

    /**
     * @param safetyCopy     copy of the data that were replaced (or kept)
     * @param safetyVerified the safety copy is a verified copy (false: the damaged file kept byte for byte)
     */
    public record Result(Outcome outcome, String message, Path safetyCopy, boolean safetyVerified) {
        public Result(Outcome outcome, String message, Path safetyCopy) {
            this(outcome, message, safetyCopy, true);
        }
    }

    /** Test seams. */
    interface Hooks {
        default boolean skipExclusivityProbe() {
            return false;
        }

        /** After the safety copy, before the exclusive replacement. */
        default void beforeExclusive() throws Exception {
        }

        default void afterReplacement() throws Exception {
        }
    }

    /** What the replacement can go back to. */
    private record Safety(Path file, String fingerprint, long changeCounter, BackupService.RawCopy raw) {
        static Safety verified(BackupService.Result r) {
            return new Safety(r.file(), r.fingerprint(), r.changeCounter(), null);
        }

        static Safety raw(BackupService.RawCopy raw) {
            return new Safety(raw.file(), null, -1, raw);
        }

        boolean isRaw() {
            return raw != null;
        }
    }

    private final BackupService backups;
    private final InstanceGuard guard;
    private volatile Hooks hooks = new Hooks() { };

    public RestoreService(BackupService backups, InstanceGuard guard) {
        this.backups = backups;
        this.guard = guard;
    }

    void hooks(Hooks h) {
        this.hooks = h;
    }

    public Prepared prepare(Path chosen) {
        ReentrantLock busy = backups.busyLock();
        if (!busy.tryLock()) return refused("outra operação com os dados está em andamento");
        Path file = chosen.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(file)) return refused("o arquivo não existe");
            if (file.equals(backups.databaseFile())) return refused("este é o arquivo dos dados em uso");
            if (Files.exists(sibling(file, "-journal")) || Files.exists(sibling(file, "-wal"))) {
                return refused("o arquivo parece estar em uso ou não foi fechado corretamente "
                        + "(há um arquivo -journal ou -wal ao lado dele)");
            }
            if (!BackupVerifier.hasSqliteHeader(file)) return refused("o arquivo não é um banco de dados do BreakInv");
            Path staged = backups.stageForRestore(file);
            BackupVerifier.Report report = BackupVerifier.verify(staged);
            Optional<String> refusal = report.restoreRefusal();
            if (refusal.isPresent()) {
                backups.discardStaged(staged);
                return refused(refusal.get());
            }
            Optional<Instant> registered = backups.managedCreatedAt(file);
            Instant created = registered.orElse(Files.getLastModifiedTime(file).toInstant());
            return new Prepared(new Preview(file, staged, report, created, registered.isPresent(), Files.size(file)), null);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "restore preparation failed", e);
            return refused("não foi possível ler o arquivo: " + BackupService.describe(e, file.getParent()));
        } finally {
            busy.unlock();
        }
    }

    /** The user cancelled: drop the staged temp. */
    public void discard(Preview preview) {
        ReentrantLock busy = backups.busyLock();
        busy.lock();
        try {
            backups.discardStaged(preview.staged());
        } finally {
            busy.unlock();
        }
    }

    /**
     * Replaces the current data by the prepared copy (§5.2): exclusivity first, then a verified copy
     * of the current data, then the replacement. When the current data are too damaged for a verified
     * copy the result is {@link Outcome#CURRENT_DAMAGED} and the preview stays prepared.
     */
    public Result restore(Preview preview) {
        return run(preview, false);
    }

    /**
     * After {@link Outcome#CURRENT_DAMAGED} and a second confirmation: tries the verified copy again
     * and, if the current data still cannot be copied with verification, keeps the current file byte
     * for byte ({@code breakinv-danificado-*.db}, never deleted by the application) and replaces it.
     */
    public Result restoreOverDamaged(Preview preview) {
        return run(preview, true);
    }

    private Result run(Preview preview, boolean overDamaged) {
        ReentrantLock busy = backups.busyLock();
        if (!busy.tryLock()) return new Result(Outcome.BUSY, "outra operação com os dados está em andamento", null);
        boolean keepStaged = false;
        try {
            if (!Files.isRegularFile(preview.staged())) {
                return new Result(Outcome.FAILED_KEPT, "o arquivo preparado não existe mais; escolha a cópia de novo. "
                        + "Nada foi alterado.", null);
            }
            // no other window may open or write while the current data are copied and replaced
            if (!guard.tryAcquireExclusive()) return new Result(Outcome.REFUSED, guard.refusal(), null);
            try {
                BackupService.Result safety = backups.ensureCurrentCopy(BackupKind.PRE_RESTORE);
                if (!safety.ok()) {
                    // the byte-for-byte path is only for data that are damaged, never a way around a failed copy
                    if (!currentDataDamaged()) {
                        return new Result(Outcome.FAILED_KEPT, "não foi possível guardar os dados atuais antes de "
                                + "restaurar (" + safety.message() + "). Nada foi alterado.", null);
                    }
                    if (!overDamaged) {
                        keepStaged = true;
                        return new Result(Outcome.CURRENT_DAMAGED, "os dados atuais estão danificados e não puderam ser "
                                + "copiados com conferência (" + safety.message() + ")", null);
                    }
                }
                hooks.beforeExclusive();
                Safety verified = safety.ok() ? Safety.verified(safety) : null;
                return Database.runExclusive(access -> replace(access, preview, verified));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "restore failed outside the verified path", e);
                return new Result(Outcome.FAILED_CRITICAL, "a restauração foi interrompida (" + firstLine(e)
                        + "). Os dados anteriores estão guardados na pasta das cópias.", null);
            } finally {
                guard.releaseExclusive();
            }
        } finally {
            if (!keepStaged) backups.discardStaged(preview.staged());
            busy.unlock();
        }
    }

    /** @param verified safety copy made before; null = keep the damaged file byte for byte here */
    private Result replace(Database.ExclusiveAccess access, Preview preview, Safety verified) {
        Safety safety = verified;
        String why;
        boolean attempted = false;
        try (Connection conn = access.newConnection(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 1000");
            if (!hooks.skipExclusivityProbe()) {
                try {
                    st.execute("BEGIN EXCLUSIVE");
                    st.execute("ROLLBACK");
                } catch (SQLException e) {
                    reopenQuietly(access);
                    if (busy(e)) {
                        return new Result(Outcome.REFUSED, "os dados estão em uso por outra operação; tente de novo em "
                                + "instantes. Nada foi alterado.", verified == null ? null : verified.file());
                    }
                    // not "in use": a damaged header fails the same way on every attempt. The way out is
                    // the manual one, with full paths (another breakinv.db may exist elsewhere); a stale
                    // journal, WAL or shared-memory file must leave with it, or it would be applied over
                    // the copy put in its place.
                    Path live = backups.databaseFile();
                    String name = String.valueOf(live.getFileName());
                    return new Result(Outcome.FAILED_KEPT, "o arquivo de dados não pôde ser aberto para a troca ("
                            + firstLine(e) + "). Nada foi alterado. Se for um bloqueio passageiro, como um antivírus, "
                            + "tente de novo. Se continuar, com o BreakInv fechado, mova " + live + ", e os arquivos "
                            + name + "-journal, " + name + "-wal e " + name + "-shm que houver ao lado dele, para outra "
                            + "pasta; depois copie " + preview.chosen() + " para " + live + ".",
                            verified == null ? null : verified.file());
                }
            }
            if (safety == null) {
                try {
                    safety = Safety.raw(backups.preserveRaw());
                } catch (IOException | SQLException e) {
                    LOG.log(Level.WARNING, "could not keep the damaged file", e);
                    reopenQuietly(access);
                    return new Result(Outcome.FAILED_KEPT, "não foi possível guardar o arquivo atual antes de restaurar ("
                            + BackupService.describe(e, backups.folder()) + "). Nada foi alterado.", null);
                }
            } else if (changedSince(safety)) {
                // a write landed between the safety copy and the exclusivity: the copy would miss it
                reopenQuietly(access);
                return new Result(Outcome.REFUSED, "os dados mudaram depois da cópia de segurança; tente de novo. "
                        + "Nada foi alterado.", safety.file());
            }
            attempted = true;
            int rc = conn.unwrap(SQLiteConnection.class).getDatabase()
                    .restore("main", preview.staged().toString(), NO_PROGRESS);
            why = rc == 0 ? null : "o SQLite recusou a restauração (código " + rc + ")";
        } catch (SQLException e) {
            why = "erro ao restaurar: " + firstLine(e);
        }
        if (!attempted) {
            // failed before the replacement started: nothing to go back from
            reopenQuietly(access);
            return new Result(Outcome.FAILED_KEPT, "a restauração não começou (" + why + "). Nada foi alterado.",
                    safety == null ? null : safety.file(), safety == null || !safety.isRaw());
        }
        if (why == null) why = checkLive(access, preview.report().fingerprint().contentSha256());
        if (why == null) {
            try {
                hooks.afterReplacement();
                access.reopenShared(); // migrates an older copy
                return new Result(Outcome.RESTORED, "dados restaurados", safety.file(), !safety.isRaw());
            } catch (Exception e) {
                why = "a cópia restaurada não pôde ser aberta nesta versão: " + firstLine(e);
            }
        }
        return safety.isRaw() ? rollbackRaw(access, safety, why) : rollback(access, safety, why);
    }

    /** True when the header change counter moved since the safety copy (or cannot be read). */
    private boolean changedSince(Safety safety) {
        if (safety.changeCounter() < 0) return false;
        try {
            return BackupService.changeCounter(backups.databaseFile()) != safety.changeCounter();
        } catch (IOException e) {
            return true;
        }
    }

    private Result rollback(Database.ExclusiveAccess access, Safety safety, String why) {
        LOG.warning("restore rolled back: " + why);
        try (Connection conn = access.newConnection()) {
            conn.unwrap(SQLiteConnection.class).getDatabase().restore("main", safety.file().toString(), NO_PROGRESS);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "rollback restore failed", e);
        }
        String again = checkLive(access, safety.fingerprint());
        if (again == null) {
            try {
                access.reopenShared();
                return new Result(Outcome.FAILED_KEPT, "a restauração não se completou (" + why
                        + "). Os dados anteriores foram mantidos.", safety.file());
            } catch (RuntimeException e) {
                again = firstLine(e);
            }
        }
        return new Result(Outcome.FAILED_CRITICAL, "a restauração não se completou (" + why + ") e os dados "
                + "anteriores não puderam ser conferidos (" + again + "). Eles estão guardados em " + safety.file()
                + ".", safety.file());
    }

    /** Way back to the damaged file: unchanged, or its pages restored and compared byte for byte. */
    private Result rollbackRaw(Database.ExclusiveAccess access, Safety safety, String why) {
        LOG.warning("restore over damaged data rolled back: " + why);
        Path live = backups.databaseFile();
        Path kept = safety.file();
        String again;
        try {
            boolean untouched = BackupService.sha256(live).equals(safety.raw().sha256());
            if (!untouched) {
                try (Connection conn = access.newConnection()) {
                    conn.unwrap(SQLiteConnection.class).getDatabase().restore("main", kept.toString(), NO_PROGRESS);
                } catch (SQLException e) {
                    LOG.log(Level.SEVERE, "rollback restore of the damaged file failed", e);
                }
            }
            if (untouched || BackupService.samePagesAsRestored(kept, live)) {
                reopenQuietly(access);
                return new Result(Outcome.FAILED_KEPT, "a restauração não se completou (" + why + "). Os dados "
                        + "anteriores, danificados, continuam como estavam; uma cópia deles está em " + kept + ".",
                        kept, false);
            }
            again = "o conteúdo não voltou igual ao arquivo guardado";
        } catch (IOException e) {
            again = firstLine(e);
        }
        return new Result(Outcome.FAILED_CRITICAL, "a restauração não se completou (" + why + ") e os dados "
                + "anteriores não puderam ser conferidos (" + again + "). O arquivo anterior, como estava, está em "
                + kept + ".", kept, false);
    }

    /**
     * {@code integrity_check} of the live database: damaged structure, index entries that no longer
     * match their rows (quick_check misses those, and the verified copy carries them and fails), or a
     * read failing as corrupt. Only asked after the verified copy has failed.
     */
    private static boolean currentDataDamaged() {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("PRAGMA integrity_check(1)")) {
            return !(rs.next() && "ok".equalsIgnoreCase(rs.getString(1)));
        } catch (SQLException | RuntimeException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            return m.contains("malformed") || m.contains("corrupt") || m.contains("not a database");
        }
    }

    /** SQLITE_BUSY or SQLITE_LOCKED (primary codes 5 and 6): someone else holds the file right now. */
    static boolean busy(SQLException e) {
        int primary = e.getErrorCode() & 0xFF;
        return primary == 5 || primary == 6;
    }

    /** Null when the live database holds exactly {@code expectedSha}; otherwise the reason. */
    private static String checkLive(Database.ExclusiveAccess access, String expectedSha) {
        try (Connection conn = access.newConnection()) {
            BackupVerifier.Report live = BackupVerifier.verify(conn);
            if (!live.integrityOk()) return "a verificação de integridade falhou depois da restauração";
            if (live.fingerprint() == null || !live.fingerprint().contentSha256().equals(expectedSha)) {
                return "o conteúdo restaurado não corresponde à cópia escolhida";
            }
            return null;
        } catch (SQLException e) {
            return "não foi possível conferir os dados: " + firstLine(e);
        }
    }

    private static void reopenQuietly(Database.ExclusiveAccess access) {
        try {
            access.reopenShared();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not reopen the shared connection", e);
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    private static Prepared refused(String why) {
        return new Prepared(null, why);
    }

    private static String firstLine(Throwable e) {
        return String.valueOf(e.getMessage()).lines().findFirst().orElse(e.getClass().getSimpleName());
    }
}

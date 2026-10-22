package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Restoration (DECISAO-ARMAZENAMENTO §5.2): verified preview, pre-restore copy, exclusivity,
 * verification against the chosen file and the way back when anything fails after the replacement.
 */
class RestoreServiceTest {

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-22T06:00:00Z"));
    BackupService backups;
    DailyTrackingUseCase uc;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = tempDir.resolve("dados d'água");
        Files.createDirectories(dir);
        db = dir.resolve("breakinv.db");
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        uc = seed("a");
        backups = service(db, folder, clock);
    }

    @AfterEach
    void tearDown() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    record Snapshot(PortfolioState state, List<Movement> history, DatabaseFingerprint fingerprint) {
    }

    static Snapshot snapshot() throws Exception {
        PortfolioService p = PortfolioService.onDatabase();
        return new Snapshot(p.state(), p.history(), liveFingerprint());
    }

    /** Backup of state A, then more movements (state B). */
    Path copyOfA() {
        BackupService.Result r = backups.createManual();
        assertEquals(BackupService.Outcome.CREATED, r.outcome(), r.message());
        clock.advance(java.time.Duration.ofMinutes(5));
        change(uc, "b-1");
        change(uc, "b-2");
        return r.file();
    }

    RestoreService restore(InstanceGuard guard) {
        return new RestoreService(backups, guard);
    }

    @Test
    void restore_bringsBackStateAndHistory_andSurvivesReopen() throws Exception {
        Snapshot a = snapshot();
        Path copy = copyOfA();
        String chosenSha = BackupService.sha256(copy);
        Snapshot b = snapshot();
        assertNotEquals(a.state(), b.state());

        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Prepared prepared = svc.prepare(copy);
        assertTrue(prepared.ok(), prepared.refusal());
        RestoreService.Preview preview = prepared.preview();
        assertTrue(preview.fromRegistry(), "date comes from the registry for a managed copy");
        assertEquals(a.fingerprint().count("portfolio_movement"), preview.count("portfolio_movement"));
        assertEquals(Schema.CURRENT_VERSION, preview.schemaVersion());

        RestoreService.Result r = svc.restore(preview);

        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        assertEquals(a, snapshot(), "state, history and every table back to A");
        assertTrue(r.safetyCopy().getFileName().toString().startsWith("breakinv-pre-restauracao-"));
        assertTrue(BackupVerifier.verify(r.safetyCopy()).faithfulTo(b.fingerprint()), "B kept in the safety copy");
        assertEquals(chosenSha, BackupService.sha256(copy), "the chosen file is never modified");
        assertFalse(Files.exists(preview.staged()), "staged temp removed");

        Database.close();
        Database.configure(url(db));
        Database.open();
        assertEquals(a, snapshot(), "same result after closing and reopening");
    }

    @Test
    void refusesFilesThatAreNotBreakInvDatabases() throws Exception {
        RestoreService svc = restore(InstanceGuard.SOLE);
        Path text = tempDir.resolve("notas.db");
        Files.writeString(text, "não é um banco");
        assertTrue(svc.prepare(text).refusal().contains("não é um banco de dados do BreakInv"));

        Path other = tempDir.resolve("outro.db");
        try (Connection c = DriverManager.getConnection(url(other)); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE foo (x INTEGER)");
            st.execute("INSERT INTO foo VALUES (1)");
        }
        assertTrue(svc.prepare(other).refusal().contains("não é um banco de dados do BreakInv"));
        assertEquals(List.of(), names(folder).stream().filter(n -> n.endsWith(".partial")).toList());
    }

    @Test
    void refusesACopyFromANewerVersion() throws Exception {
        Path copy = copyOfA();
        Path future = tempDir.resolve("futuro.db");
        Files.copy(copy, future);
        try (Connection c = DriverManager.getConnection(url(future)); Statement st = c.createStatement()) {
            st.execute("INSERT INTO schema_migrations(version, description, applied_at) VALUES (99, 'futuro', 'x')");
        }
        String why = restore(InstanceGuard.SOLE).prepare(future).refusal();
        assertTrue(why.contains("versão mais nova"), why);
    }

    @Test
    void refusesInconsistentReferences_andAFileThatWasNotClosed() throws Exception {
        Path copy = copyOfA();
        Path broken = tempDir.resolve("referencias.db");
        Files.copy(copy, broken);
        try (Connection c = DriverManager.getConnection(url(broken)); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            st.execute("INSERT INTO investment_snapshots(date, investment_type_id, value_cents) VALUES ('2026-01-01', 999, 1)");
        }
        String why = restore(InstanceGuard.SOLE).prepare(broken).refusal();
        assertTrue(why.contains("referências inconsistentes"), why);

        Path open = tempDir.resolve("aberto.db");
        Files.copy(copy, open);
        Files.writeString(tempDir.resolve("aberto.db-journal"), "journal");
        String why2 = restore(InstanceGuard.SOLE).prepare(open).refusal();
        assertTrue(why2.contains("-journal"), why2);
    }

    @Test
    void anotherInstanceOpen_refusesWithoutChangingAnything() throws Exception {
        Path copy = copyOfA();
        Snapshot b = snapshot();
        InstanceGuard other = new InstanceGuard() {
            @Override
            public boolean tryAcquireExclusive() {
                return false;
            }

            @Override
            public void releaseExclusive() {
            }
        };
        RestoreService svc = restore(other);
        RestoreService.Result r = svc.restore(svc.prepare(copy).preview());
        assertEquals(RestoreService.Outcome.REFUSED, r.outcome());
        assertTrue(r.message().contains("outra janela"), r.message());
        assertEquals(b, snapshot());
        assertTrue(copies(folder).stream().noneMatch(n -> n.contains("-pre-restauracao-")),
                "exclusivity comes first: no useless copy per refused attempt");
    }

    @Test
    void instanceLockUnavailable_saysSo_insteadOfBlamingAnotherWindow() throws Exception {
        Path copy = copyOfA();
        RestoreService svc = restore(InstanceGuard.UNVERIFIABLE);
        RestoreService.Result r = svc.restore(svc.prepare(copy).preview());
        assertEquals(RestoreService.Outcome.REFUSED, r.outcome());
        assertTrue(r.message().contains("não foi possível confirmar"), r.message());
    }

    @Test
    void writeBetweenTheSafetyCopyAndTheReplacement_isRefused_andKept() throws Exception {
        Path copy = copyOfA();
        RestoreService svc = restore(InstanceGuard.SOLE);
        svc.hooks(new RestoreService.Hooks() {
            @Override
            public void beforeExclusive() throws Exception {
                try (Connection other = DriverManager.getConnection(url(db)); Statement st = other.createStatement()) {
                    st.execute("INSERT INTO app_settings(key, value) VALUES ('escrita_tardia', '1')");
                }
            }
        });

        RestoreService.Result r = svc.restore(svc.prepare(copy).preview());

        assertEquals(RestoreService.Outcome.REFUSED, r.outcome(), r.message());
        assertTrue(r.message().contains("mudaram depois da cópia"), r.message());
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM app_settings WHERE key = 'escrita_tardia'")) {
            assertTrue(rs.next(), "the late write is still there");
        }
    }

    @Test
    void restoringAnOlderCopyWithTheMigrationHook_deletesNoCopyWhileItRuns() throws Exception {
        // one automatic copy allowed: any rotation during the restore would delete the earlier daily copy
        backups = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection), clock,
                new RetentionPolicy(1, 1L << 30));
        Database.setPreMigrationHook((candidate, file) -> backups.beforeMigration(candidate));
        BackupService.Result daily = backups.dailyIfNeeded();
        assertEquals(BackupService.Outcome.CREATED, daily.outcome(), daily.message());
        change(uc, "antes-da-restauracao"); // so the restore makes a new pre-restore copy
        Snapshot before = snapshot();
        Path legacy = tempDir.resolve("antigo.db");
        PreMigrationBackupTest.legacy(legacy, true);
        clock.advance(java.time.Duration.ofHours(1));

        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Result r = svc.restore(svc.prepare(legacy).preview());

        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        List<String> present = copies(folder);
        assertTrue(present.stream().anyMatch(n -> n.startsWith("breakinv-pre-migracao-")), present.toString());
        assertTrue(Files.exists(r.safetyCopy()), "the copy of the replaced data survives the restore");
        assertTrue(BackupVerifier.verify(r.safetyCopy()).faithfulTo(before.fingerprint()));
        assertTrue(present.contains(daily.file().getFileName().toString()), "no rotation inside the restore");

        // the rotation waits for the next copy made on its own
        clock.advance(java.time.Duration.ofHours(1));
        change(uc, "depois-1");
        assertEquals(BackupService.Outcome.CREATED, backups.createManual().outcome());
        assertFalse(copies(folder).contains(daily.file().getFileName().toString()), "then the rotation resumes");
        assertTrue(Files.exists(r.safetyCopy()), "the way back from the last restore stays protected");
    }

    @Test
    void damagedCurrentData_canBeReplaced_afterKeepingThemByteForByte() throws Exception {
        Path copy = copyOfA();
        DatabaseFingerprint chosen = BackupVerifier.verify(copy).fingerprint();
        Database.close();
        corruptTable(db, "portfolio_movement");
        Database.configure(url(db));
        Database.open();
        String damagedSha = BackupService.sha256(db);
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy).preview();

        RestoreService.Result first = svc.restore(preview);

        assertEquals(RestoreService.Outcome.CURRENT_DAMAGED, first.outcome(), first.message());
        assertEquals(damagedSha, BackupService.sha256(db), "nothing changed before the second confirmation");
        assertTrue(Files.exists(preview.staged()), "the prepared copy waits for the second confirmation");

        RestoreService.Result r = svc.restoreOverDamaged(preview);

        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        assertFalse(r.safetyVerified());
        String kept = r.safetyCopy().getFileName().toString();
        assertTrue(kept.startsWith("breakinv-danificado-"), kept);
        assertTrue(BackupNames.kindOfCopy(kept).isEmpty() && !BackupNames.isTemp(kept), "never rotated");
        assertEquals(damagedSha, BackupService.sha256(r.safetyCopy()), "the damaged file is kept byte for byte");
        assertEquals(chosen, liveFingerprint(), "the chosen copy replaced the damaged data");
        assertFalse(Files.exists(preview.staged()));
    }

    @Test
    void failureAfterReplacingDamagedData_putsTheDamagedPagesBack() throws Exception {
        Path copy = copyOfA();
        Database.close();
        corruptTable(db, "portfolio_movement");
        Database.configure(url(db));
        Database.open();
        RestoreService svc = restore(InstanceGuard.SOLE);
        svc.hooks(new RestoreService.Hooks() {
            @Override
            public void afterReplacement() {
                throw new IllegalStateException("falha simulada depois da troca");
            }
        });
        RestoreService.Preview preview = svc.prepare(copy).preview();
        assertEquals(RestoreService.Outcome.CURRENT_DAMAGED, svc.restore(preview).outcome());

        RestoreService.Result r = svc.restoreOverDamaged(preview);

        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome(), r.message());
        assertTrue(r.message().contains("danificados, continuam como estavam"), r.message());
        // apart from the production comparator: every byte after the header, and what SQLite itself reads
        byte[] kept = Files.readAllBytes(r.safetyCopy());
        byte[] live = Files.readAllBytes(db);
        assertEquals(kept.length, live.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(kept, 100, kept.length),
                java.util.Arrays.copyOfRange(live, 100, live.length), "every byte after the header");
        List<String> report = sqliteReport(db);
        assertEquals(sqliteReport(r.safetyCopy()), report, "same page size, encoding, size, settings and damage");
        assertFalse(report.contains("integrity_check=ok"), "the damage is back as well: " + report);
        assertTrue(BackupService.samePagesAsRestored(r.safetyCopy(), db), "the damaged pages are back");
    }

    /** What SQLite itself reports about a file, read from a byte copy: header settings and integrity_check. */
    List<String> sqliteReport(Path file) throws Exception {
        Path copy = Files.copy(file, tempDir.resolve("relatorio-" + System.nanoTime() + ".db"));
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(url(copy)); Statement st = c.createStatement()) {
            for (String pragma : List.of("page_size", "encoding", "page_count", "freelist_count", "auto_vacuum",
                    "user_version", "application_id", "integrity_check")) {
                try (ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
                    while (rs.next()) out.add(pragma + "=" + rs.getString(1));
                } catch (java.sql.SQLException e) { // a damaged file fails the same way, and that is compared too
                    out.add(pragma + "=erro " + e.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * Review r2, 2.1: index entries that no longer match their rows, on valid pages (common after a
     * power loss). quick_check misses it and the verified copy carries it and fails; it is damaged
     * data all the same, so the second confirmation must be offered instead of a dead end.
     */
    @Test
    void indexDamageThatQuickCheckMisses_isDamagedData_andCanBeReplaced() throws Exception {
        Path copy = copyOfA();
        DatabaseFingerprint chosen = BackupVerifier.verify(copy).fingerprint();
        Database.close();
        corruptIndex(db, "idx_investment_category", "investment_type", "name");
        Database.configure(url(db));
        Database.open();
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA quick_check(1)")) {
            assertTrue(rs.next());
            assertEquals("ok", rs.getString(1), "quick_check does not compare index entries with their rows");
        }
        assertEquals(BackupService.Outcome.FAILED, backups.createManual().outcome(),
                "the verified copy carries the damaged index and fails");
        String damagedSha = BackupService.sha256(db);
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy).preview();

        RestoreService.Result first = svc.restore(preview);

        assertEquals(RestoreService.Outcome.CURRENT_DAMAGED, first.outcome(), first.message());
        RestoreService.Result r = svc.restoreOverDamaged(preview);
        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        assertEquals(damagedSha, BackupService.sha256(r.safetyCopy()), "the damaged file is kept byte for byte");
        assertEquals(chosen, liveFingerprint(), "the chosen copy replaced the damaged data");
    }

    /**
     * Review r2, 2.2: a header damaged while the app is open makes the exclusivity probe fail as "not
     * a database", which is not "in use" (trying again would fail forever). Say what happened and the
     * way out, with the app closed.
     */
    @Test
    void damagedHeader_saysWhatHappened_insteadOfDataInUse() throws Exception {
        Path copy = copyOfA();
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy).preview();
        corruptHeader(db);
        String damagedSha = BackupService.sha256(db);

        assertEquals(RestoreService.Outcome.CURRENT_DAMAGED, svc.restore(preview).outcome());
        RestoreService.Result r = svc.restoreOverDamaged(preview);

        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome(), r.message());
        assertFalse(r.message().contains("em uso"), r.message());
        assertTrue(r.message().contains("Nada foi alterado") && r.message().contains("BreakInv fechado"), r.message());
        // review r3-1: full paths, so nobody moves another breakinv.db or forgets which copy was chosen;
        // and a passing lock (an antivirus) only needs another try
        assertTrue(r.message().contains(db.toString()) && r.message().contains(copy.toString()), r.message());
        assertTrue(r.message().contains("tente de novo"), r.message());
        // review r4: a stray -wal (or -shm) left behind would be applied over the copy put in place
        assertTrue(r.message().contains("-journal") && r.message().contains("-wal") && r.message().contains("-shm"),
                r.message());
        assertEquals(damagedSha, BackupService.sha256(db), "nothing changed");
    }

    /**
     * Review r2, missing test: over damaged data, a replacement that did not happen (a reader holds
     * the file, so the driver's restore returns without effect) leaves the damaged file exactly as it
     * was, and its byte copy stays.
     */
    @Test
    void replacementThatDidNotHappen_overDamagedData_leavesTheFileUntouched() throws Exception {
        Path copy = copyOfA();
        Database.close();
        corruptTable(db, "portfolio_movement");
        Database.configure(url(db));
        Database.open();
        String damagedSha = BackupService.sha256(db);
        RestoreService svc = restore(InstanceGuard.SOLE);
        svc.hooks(new RestoreService.Hooks() {
            @Override
            public boolean skipExclusivityProbe() {
                return true;
            }
        });
        RestoreService.Preview preview = svc.prepare(copy).preview();
        assertEquals(RestoreService.Outcome.CURRENT_DAMAGED, svc.restore(preview).outcome());
        RestoreService.Result r;
        try (Connection reader = DriverManager.getConnection(url(db)); Statement st = reader.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM investment_type")) {
            assertTrue(rs.next()); // statement still active: SHARED lock held, the driver cannot write
            r = svc.restoreOverDamaged(preview);
        }
        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome(), r.message());
        assertTrue(r.message().contains("danificados, continuam como estavam"), r.message());
        assertEquals(damagedSha, BackupService.sha256(db), "the damaged file was never touched");
        assertEquals(damagedSha, BackupService.sha256(r.safetyCopy()), "and its byte copy stays");
    }

    @Test
    void healthyDataWhoseCopyFails_neverTakeTheByteForBytePath() throws Exception {
        Path copy = copyOfA();
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy).preview();
        Snapshot b = snapshot();
        // the verified copy fails for a reason that would not stop a byte copy
        backups.hooks(new BackupService.Hooks() {
            @Override
            public void beforePublish(Path verifiedTemp) throws java.io.IOException {
                throw new java.io.IOException("queda simulada");
            }
        });

        RestoreService.Result r = svc.restoreOverDamaged(preview);

        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome(), r.message());
        assertEquals(b, snapshot(), "healthy data are never replaced without a verified copy");
        assertTrue(names(folder).stream().noneMatch(n -> n.startsWith("breakinv-danificado-")), names(folder).toString());
    }

    @Test
    void refusesADamagedCopy_aBrokenLedger_aWalBeside_theLiveFile_andAVanishedPreparation() throws Exception {
        Path copy = copyOfA();
        RestoreService svc = restore(InstanceGuard.SOLE);

        Path damaged = tempDir.resolve("danificada.db");
        Files.copy(copy, damaged);
        corruptTable(damaged, "portfolio_movement");
        String why = svc.prepare(damaged).refusal();
        assertTrue(why != null && why.contains("danificado"), why);

        Path ledger = tempDir.resolve("livro.db");
        Files.copy(copy, ledger);
        try (Connection c = DriverManager.getConnection(url(ledger)); Statement st = c.createStatement()) {
            st.execute("INSERT INTO portfolio_movement(intent_key, payload_hash, date, kind, amount_cents, created_at) "
                    + "VALUES ('estouro', 'x', '2026-03-20', 'DEPOSIT', 9223372036854775807, '2026-03-20T00:00:00Z')");
        }
        String why2 = svc.prepare(ledger).refusal();
        assertTrue(why2 != null && why2.contains("livro de movimentações"), why2);

        Path wal = tempDir.resolve("wal.db");
        Files.copy(copy, wal);
        Files.writeString(tempDir.resolve("wal.db-wal"), "wal");
        String why3 = svc.prepare(wal).refusal();
        assertTrue(why3 != null && why3.contains("-wal"), why3);

        String why4 = svc.prepare(db).refusal();
        assertTrue(why4 != null && why4.contains("dados em uso"), why4);

        Snapshot b = snapshot();
        RestoreService.Preview preview = svc.prepare(copy).preview();
        Files.delete(preview.staged());
        RestoreService.Result r = svc.restore(preview);
        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome());
        assertTrue(r.message().contains("não existe mais"), r.message());
        assertEquals(b, snapshot());
        assertEquals(List.of(), names(folder).stream().filter(n -> n.endsWith(".partial")).toList());
    }

    @Test
    void readerHoldingTheDatabase_isDetectedBeforeReplacing() throws Exception {
        Path copy = copyOfA();
        Snapshot b = snapshot();
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy).preview();
        try (Connection reader = DriverManager.getConnection(url(db)); Statement st = reader.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM portfolio_movement")) {
            assertTrue(rs.next()); // statement still active: SHARED lock held

            RestoreService.Result r = svc.restore(preview);

            assertEquals(RestoreService.Outcome.REFUSED, r.outcome(), r.message());
        }
        assertEquals(b, snapshot(), "nothing changed");
    }

    /**
     * Driver regression (BRK-29 probe): with a reader on the target, {@code DB.restore} of
     * sqlite-jdbc 3.51.2.0 returns 0 without restoring. Without the exclusivity probe, the
     * verification after the replacement must catch it and keep the current data.
     */
    @Test
    void driverReportsSuccessWithoutRestoring_verificationKeepsTheCurrentData() throws Exception {
        Path copy = copyOfA();
        Snapshot b = snapshot();
        RestoreService svc = restore(InstanceGuard.SOLE);
        svc.hooks(new RestoreService.Hooks() {
            @Override
            public boolean skipExclusivityProbe() {
                return true;
            }
        });
        RestoreService.Preview preview = svc.prepare(copy).preview();
        RestoreService.Result r;
        try (Connection reader = DriverManager.getConnection(url(db)); Statement st = reader.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM portfolio_movement")) {
            assertTrue(rs.next());
            r = svc.restore(preview);
        }
        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome(), r.message());
        assertTrue(r.message().contains("não corresponde"), r.message());
        assertEquals(b, snapshot(), "current data kept");
    }

    @Test
    void driverReturnCodeAlone_isNotEvidence_characterization() throws Exception {
        Path copy = copyOfA();
        DatabaseFingerprint before = liveFingerprint();
        int rc;
        try (Connection reader = DriverManager.getConnection(url(db)); Statement st = reader.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM portfolio_movement");
             Connection target = DriverManager.getConnection(url(db))) {
            assertTrue(rs.next());
            rc = target.unwrap(SQLiteConnection.class).getDatabase().restore("main", copy.toString(), (a, b) -> { });
        }
        assertEquals(before, liveFingerprint(), "nothing was restored");
        assertEquals(0, rc, "sqlite-jdbc 3.51.2.0 reports success here; if this changes, keep the verification anyway");
    }

    @Test
    void failureAfterTheReplacement_goesBackToThePreRestoreCopy() throws Exception {
        Path copy = copyOfA();
        Snapshot b = snapshot();
        RestoreService svc = restore(InstanceGuard.SOLE);
        svc.hooks(new RestoreService.Hooks() {
            @Override
            public void afterReplacement() throws Exception {
                throw new IllegalStateException("falha simulada depois da troca");
            }
        });
        RestoreService.Result r = svc.restore(svc.prepare(copy).preview());
        assertEquals(RestoreService.Outcome.FAILED_KEPT, r.outcome(), r.message());
        assertTrue(r.message().contains("dados anteriores foram mantidos"), r.message());
        assertEquals(b, snapshot(), "B is back and verified");
    }

    @Test
    void olderVersionCopy_isMigratedAfterRestoring() throws Exception {
        Path legacy = tempDir.resolve("antigo.db");
        PreMigrationBackupTest.legacy(legacy, true);
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Prepared prepared = svc.prepare(legacy);
        assertTrue(prepared.ok(), prepared.refusal());
        assertTrue(prepared.preview().olderVersion());

        RestoreService.Result r = svc.restore(prepared.preview());

        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        Connection live = Database.open();
        assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(live));
        try (Statement st = live.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM investment_type ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("Tesouro legado", rs.getString(1));
        }
    }

    @Test
    void cancelledPreview_leavesNoTemp() throws Exception {
        Path copy = copyOfA();
        RestoreService svc = restore(InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy).preview();
        assertTrue(Files.exists(preview.staged()));
        svc.discard(preview);
        assertFalse(Files.exists(preview.staged()));
        assertTrue(BackupManifest.load(folder).entries().stream().noneMatch(BackupManifest.Entry::inProgress));
    }
}

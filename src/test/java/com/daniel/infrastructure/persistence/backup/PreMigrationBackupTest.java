package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

/** Automatic copy before a migration changes an existing database (and only then). */
class PreMigrationBackupTest {

    @TempDir
    Path tempDir;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-22T06:00:00Z"));

    @AfterEach
    void tearDown() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    /** Pre-versioning database (as released before schema_migrations existed), with rows. */
    static void legacy(Path db, boolean withRows) throws Exception {
        try (Connection c = DriverManager.getConnection(url(db)); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE investment_type (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, category TEXT)");
            st.execute("CREATE TABLE app_settings (key TEXT PRIMARY KEY, value TEXT)");
            if (withRows) {
                st.execute("INSERT INTO investment_type (id, name, category) VALUES (7, 'Tesouro legado', 'RENDA_FIXA')");
                st.execute("INSERT INTO investment_type (id, name, category) VALUES (9, 'Ação legada', 'ACOES')");
            }
        }
    }

    BackupService install(Path db) {
        BackupService svc = service(db, BackupService.defaultFolder(db), clock);
        Database.setPreMigrationHook((candidate, file) -> svc.beforeMigration(candidate));
        return svc;
    }

    @Test
    void legacyDatabase_isCopiedAndVerifiedBeforeTheMigrationChangesIt() throws Exception {
        Path db = tempDir.resolve("legado.db");
        legacy(db, true);
        DatabaseFingerprint before;
        try (Connection c = DriverManager.getConnection(url(db))) {
            before = DatabaseFingerprint.of(c);
        }
        install(db);
        Database.configure(url(db));

        Connection live = Database.open();

        Path folder = BackupService.defaultFolder(db);
        List<String> copies = copies(folder);
        assertEquals(1, copies.size(), copies.toString());
        assertTrue(copies.get(0).startsWith("breakinv-pre-migracao-"));
        BackupVerifier.Report copy = BackupVerifier.verify(folder.resolve(copies.get(0)));
        assertTrue(copy.faithfulTo(before), "the copy holds the database exactly as it was before migrating");
        assertEquals(0, copy.schemaVersion());
        assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(live), "then the migration ran");
        try (Statement st = live.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM investment_type")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void newCurrentOrEmptyDatabase_getsNoCopy() throws Exception {
        Path fresh = tempDir.resolve("novo.db");
        install(fresh);
        Database.configure(url(fresh));
        Database.open();
        Database.close();
        Database.configure(url(fresh)); // reopen a current database
        Database.open();
        assertEquals(List.of(), copies(BackupService.defaultFolder(fresh)), "new and current databases");

        Database.close();
        Path emptyLegacy = tempDir.resolve("vazio").resolve("legado-vazio.db");
        Files.createDirectories(emptyLegacy.getParent());
        legacy(emptyLegacy, false);
        install(emptyLegacy);
        Database.configure(url(emptyLegacy));
        Database.open();
        assertEquals(List.of(), copies(BackupService.defaultFolder(emptyLegacy)), "legacy tables without rows");
        assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(Database.open()));
    }

    @Test
    void failedCopy_stopsTheMigration_andLeavesTheFileUntouched() throws Exception {
        Path db = tempDir.resolve("bloqueio.db");
        legacy(db, true);
        DatabaseFingerprint before;
        try (Connection c = DriverManager.getConnection(url(db))) {
            before = DatabaseFingerprint.of(c);
        }
        Path notAFolder = tempDir.resolve("arquivo-no-lugar-da-pasta");
        Files.writeString(notAFolder, "x");
        BackupService svc = service(db, notAFolder, clock);
        Database.setPreMigrationHook((candidate, file) -> svc.beforeMigration(candidate));
        Database.configure(url(db));

        RuntimeException e = assertThrows(RuntimeException.class, Database::open);
        assertTrue(e.getMessage().contains("antes de atualizar os dados"), e.getMessage());

        Database.setPreMigrationHook(null);
        try (Connection c = DriverManager.getConnection(url(db))) {
            assertEquals(before, DatabaseFingerprint.of(c), "no schema_migrations, no new tables: untouched");
        }
    }
}

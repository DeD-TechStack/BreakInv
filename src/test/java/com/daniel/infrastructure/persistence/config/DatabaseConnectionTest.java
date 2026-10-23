package com.daniel.infrastructure.persistence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Effective-connection guarantees of {@link Database}: foreign keys enforced, busy timeout,
 * versioned migration on open, independent connections and the transaction callback.
 * Temp-file databases only.
 */
class DatabaseConnectionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    private String url(String name) {
        return "jdbc:sqlite:" + tempDir.resolve(name).toString().replace("\\", "/");
    }

    @Test
    void open_enablesForeignKeysOnSharedConnection() throws Exception {
        Database.configure(url("fk.db"));
        Connection c = Database.open();
        assertEquals(1, pragmaInt(c, "foreign_keys"));
        assertTrue(pragmaInt(c, "busy_timeout") >= 1000);
    }

    @Test
    void open_rejectsOrphanInsertBecauseFkIsEffective() throws Exception {
        Database.configure(url("fk2.db"));
        Connection c = Database.open();
        try (Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO transactions (date, investment_type_id, type, name, total_cents) "
                            + "VALUES ('2024-01-01', 999, 'BUY', 'x', 1)"));
            assertTrue(ex.getMessage().toUpperCase().contains("FOREIGN KEY"), ex.getMessage());
        }
    }

    @Test
    void open_appliesVersionedMigrationsToCurrentVersion() {
        Database.configure(url("version.db"));
        Connection c = Database.open();
        assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(c));
        assertTrue(Schema.isCurrent(c));
    }

    @Test
    void open_onLegacyDatabase_migratesAndPreservesRows() throws Exception {
        String u = url("legacy.db");
        try (Connection setup = DriverManager.getConnection(u); Statement st = setup.createStatement()) {
            st.execute("CREATE TABLE investment_type (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, category TEXT)");
            st.execute("INSERT INTO investment_type (id, name) VALUES (3, 'Antigo')");
        }
        Database.configure(u);
        Database.setPreMigrationHook((candidate, file) -> { }); // BRK-31: migration logic only; the copy is in PreMigrationBackupTest
        Connection c = Database.open();
        assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(c));
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, position_mode FROM investment_type")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertEquals("Antigo", rs.getString(2));
            assertNull(rs.getString(3));
        }
    }

    @Test
    void newConnection_isIndependentConfiguredAndDoesNotReMigrate() throws Exception {
        Database.configure(url("indep.db"));
        Connection shared = Database.open();
        int before = countRows(shared, "schema_migrations");

        try (Connection other = Database.newConnection()) {
            assertNotSame(shared, other);
            assertEquals(1, pragmaInt(other, "foreign_keys"));
            assertTrue(pragmaInt(other, "busy_timeout") >= 1000);
            assertTrue(other.getAutoCommit());
            assertEquals(before, countRows(other, "schema_migrations"));
            // sees the same file: a row written through the shared connection is visible
            try (Statement st = shared.createStatement()) {
                st.execute("INSERT INTO investment_type (name) VALUES ('via shared')");
            }
            assertEquals(1, countRows(other, "investment_type"));
        }
        assertFalse(shared.isClosed(), "closing the independent connection must not close the shared one");
    }

    @Test
    void newConnection_beforeOpen_stillMigratesOnce() throws Exception {
        Database.configure(url("indep2.db"));
        try (Connection other = Database.newConnection()) {
            assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(other));
        }
    }

    @Test
    void inTransaction_commitsOnSuccessAndReturnsValue() throws Exception {
        Database.configure(url("tx1.db"));
        Database.open();
        long id = Database.inTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO investment_type (name) VALUES ('tx')");
                try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertTrue(id > 0);
        try (Connection check = Database.newConnection()) {
            assertEquals(1, countRows(check, "investment_type"));
        }
    }

    @Test
    void inTransaction_rollsBackEverythingOnException() throws Exception {
        Database.configure(url("tx2.db"));
        Database.open();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO investment_type (name) VALUES ('a')");
                st.execute("INSERT INTO investment_type (name) VALUES ('b')");
            }
            throw new IllegalStateException("boom");
        }));
        assertEquals("boom", ex.getMessage());
        try (Connection check = Database.newConnection()) {
            assertEquals(0, countRows(check, "investment_type"));
        }
    }

    @Test
    void inTransaction_usesItsOwnConnectionNotTheSharedOne() throws Exception {
        Database.configure(url("tx3.db"));
        Connection shared = Database.open();
        Database.inTransaction(conn -> {
            assertNotSame(shared, conn);
            assertFalse(conn.getAutoCommit());
            assertTrue(shared.getAutoCommit(), "shared UI connection must not be put in a transaction");
            return null;
        });
    }

    @Test
    void configure_memoryUrl_openAndNewConnectionShareTheSameDatabase() throws Exception {
        Database.configure("jdbc:sqlite::memory:");
        Connection shared = Database.open();
        try (Statement st = shared.createStatement()) {
            st.execute("INSERT INTO investment_type (name) VALUES ('mem')");
        }
        try (Connection other = Database.newConnection()) {
            assertEquals(1, countRows(other, "investment_type"),
                    ":memory: must map to one shared database so independent connections see the same data");
        }
    }

    // ===== helpers =====

    static int pragmaInt(Connection c, String pragma) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int countRows(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

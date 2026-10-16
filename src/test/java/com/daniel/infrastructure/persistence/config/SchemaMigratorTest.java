package com.daniel.infrastructure.persistence.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Versioned, atomic schema migrations: fresh DB, legacy DB, partially migrated DB,
 * repeated runs, failure rollback and preservation of legacy rows/ids/values.
 * Raw JDBC connections on temp files only — no singleton, no personal database.
 */
class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    private String url(String name) {
        return "jdbc:sqlite:" + tempDir.resolve(name).toString().replace("\\", "/");
    }

    // ===== fresh =====

    @Test
    void migrate_freshDatabase_createsAllTablesAndRecordsCurrentVersion() throws Exception {
        try (Connection c = DriverManager.getConnection(url("fresh.db"))) {
            SchemaMigrator.migrate(c);

            assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(c));
            for (String t : List.of("investment_type", "flows", "cash_snapshots", "investment_snapshots",
                    "transactions", "app_settings", "schema_migrations", "portfolio_movement",
                    "allocation_plan", "allocation_item", "allocation_selection", "simulation_scenario")) {
                assertTrue(tableExists(c, t), "missing table " + t);
            }
            List<String> cols = columnsOf(c, "investment_type");
            assertTrue(cols.contains("position_mode"));
            assertTrue(cols.contains("archived_at"));
            assertTrue(cols.contains("note"));
            assertFalse(Schema.needsMigration(c));
        }
    }

    @Test
    void migrate_recordsOneRowPerVersionWithTimestamp() throws Exception {
        try (Connection c = DriverManager.getConnection(url("versions.db"))) {
            SchemaMigrator.migrate(c);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version, applied_at FROM schema_migrations ORDER BY version")) {
                int expected = 1;
                while (rs.next()) {
                    assertEquals(expected++, rs.getInt(1));
                    assertNotNull(rs.getString(2));
                }
                assertEquals(Schema.CURRENT_VERSION + 1, expected);
            }
        }
    }

    // ===== legacy =====

    @Test
    void migrate_legacyDatabase_addsMissingColumnsAndPreservesRows() throws Exception {
        String u = url("legacy.db");
        try (Connection c = DriverManager.getConnection(u); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE investment_type (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        category TEXT,
                        liquidity TEXT,
                        investment_date TEXT,
                        profitability REAL,
                        invested_value REAL)""");
            st.execute("INSERT INTO investment_type (id, name, category, invested_value) VALUES (7, 'CDB legado', 'RENDA_FIXA', 1234.565)");
            st.execute("INSERT INTO investment_type (id, name) VALUES (9, 'Sem valor')");
            st.execute("CREATE TABLE cash_snapshots (date TEXT PRIMARY KEY, value_cents INTEGER NOT NULL)");
            st.execute("INSERT INTO cash_snapshots VALUES ('2024-01-01', 50000)");
        }
        try (Connection c = DriverManager.getConnection(u)) {
            SchemaMigrator.migrate(c);
            assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(c));
            List<String> cols = columnsOf(c, "investment_type");
            for (String col : List.of("type_of_investment", "index_type", "index_percentage", "ticker",
                    "purchase_price", "quantity", "position_mode", "archived_at", "note")) {
                assertTrue(cols.contains(col), "missing column " + col);
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, invested_value, quantity FROM investment_type ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
                assertEquals("CDB legado", rs.getString(2));
                assertEquals(1234.565, rs.getDouble(3), 1e-9);
                rs.getInt(4);
                assertTrue(rs.wasNull(), "quantity must stay NULL, not become zero");
                assertTrue(rs.next());
                assertEquals(9, rs.getInt(1));
                rs.getDouble(3);
                assertTrue(rs.wasNull(), "invested_value NULL must be preserved as NULL");
                assertFalse(rs.next());
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value_cents FROM cash_snapshots WHERE date='2024-01-01'")) {
                assertTrue(rs.next());
                assertEquals(50000L, rs.getLong(1));
            }
        }
    }

    @Test
    void migrate_partiallyMigratedLegacy_onlyAddsWhatIsMissing() throws Exception {
        String u = url("partial.db");
        try (Connection c = DriverManager.getConnection(u); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE investment_type (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        ticker TEXT,
                        quantity INTEGER)""");
            st.execute("INSERT INTO investment_type (name, ticker, quantity) VALUES ('PETR4', 'PETR4', 12)");
            // transactions already exists, flows does not
            st.execute("""
                    CREATE TABLE transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL,
                        investment_type_id INTEGER NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL,
                        ticker TEXT, quantity INTEGER, unit_price_cents INTEGER, total_cents INTEGER NOT NULL, note TEXT)""");
            st.execute("INSERT INTO transactions (date, investment_type_id, type, name, quantity, total_cents) VALUES ('2024-02-02', 1, 'BUY', 'PETR4', 12, 36000)");
        }
        try (Connection c = DriverManager.getConnection(u)) {
            assertDoesNotThrow(() -> SchemaMigrator.migrate(c));
            List<String> cols = columnsOf(c, "investment_type");
            assertEquals(1, cols.stream().filter("ticker"::equals).count());
            assertTrue(cols.contains("type_of_investment"));
            assertTrue(tableExists(c, "flows"));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT quantity, ticker FROM investment_type")) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1));
                assertEquals("PETR4", rs.getString(2));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(total_cents) FROM transactions")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals(36000L, rs.getLong(2));
            }
        }
    }

    // ===== repeat =====

    @Test
    void migrate_runTwice_isIdempotentAndDoesNotDuplicateVersions() throws Exception {
        try (Connection c = DriverManager.getConnection(url("twice.db"))) {
            SchemaMigrator.migrate(c);
            SchemaMigrator.migrate(c);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                assertTrue(rs.next());
                assertEquals(Schema.CURRENT_VERSION, rs.getInt(1));
            }
            assertEquals(1, columnsOf(c, "investment_type").stream().filter("note"::equals).count());
        }
    }

    // ===== failure / rollback =====

    @Test
    void migrate_failingStep_rollsBackWholeVersionAndRecordsNothing() throws Exception {
        try (Connection c = DriverManager.getConnection(url("fail.db"))) {
            SchemaMigrator.migrate(c); // reach current version first

            SchemaMigrator.Migration bad = new SchemaMigrator.Migration(Schema.CURRENT_VERSION + 1, "bad", conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE should_not_survive (id INTEGER)");
                    st.execute("INSERT INTO should_not_survive VALUES (1)");
                }
                throw new IllegalStateException("simulated interruption");
            });
            SchemaMigrator custom = new SchemaMigrator(List.of(bad));

            SchemaMigrator.MigrationException ex = assertThrows(SchemaMigrator.MigrationException.class,
                    () -> custom.migrateAll(c));
            assertTrue(ex.getMessage().contains("simulated interruption"));
            assertFalse(tableExists(c, "should_not_survive"), "DDL of the failed version must be rolled back");
            assertEquals(Schema.CURRENT_VERSION, Schema.appliedVersion(c));
            assertTrue(c.getAutoCommit(), "connection must be left usable (autocommit restored)");

            // a later successful run of the same version is accepted
            SchemaMigrator.Migration good = new SchemaMigrator.Migration(Schema.CURRENT_VERSION + 1, "good", conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE survives (id INTEGER)");
                }
            });
            new SchemaMigrator(List.of(good)).migrateAll(c);
            assertTrue(tableExists(c, "survives"));
            assertEquals(Schema.CURRENT_VERSION + 1, Schema.appliedVersion(c));
        }
    }

    // ===== orphan audit (fail closed, no data loss) =====

    @Test
    void migrate_legacyWithOrphanHistory_failsClosedWithoutDeletingAnything() throws Exception {
        String u = url("orphans.db");
        try (Connection c = DriverManager.getConnection(u); Statement st = c.createStatement()) {
            for (String sql : Schema.legacyCreateTables().split(";")) {
                if (!sql.isBlank()) st.execute(sql);
            }
            st.execute("INSERT INTO investment_type (id, name) VALUES (1, 'Existe')");
            st.execute("INSERT INTO transactions (date, investment_type_id, type, name, total_cents) VALUES ('2024-01-01', 1, 'BUY', 'ok', 100)");
            st.execute("INSERT INTO transactions (date, investment_type_id, type, name, total_cents) VALUES ('2024-01-02', 42, 'BUY', 'orfao', 200)");
            st.execute("INSERT INTO investment_snapshots (date, investment_type_id, value_cents) VALUES ('2024-01-02', 43, 300)");
        }
        try (Connection c = DriverManager.getConnection(u)) {
            SchemaMigrator.MigrationException ex = assertThrows(SchemaMigrator.MigrationException.class,
                    () -> SchemaMigrator.migrate(c));
            assertTrue(ex.getMessage().contains("transactions"), ex.getMessage());
            assertTrue(ex.getMessage().contains("investment_snapshots"), ex.getMessage());
            assertTrue(Schema.appliedVersion(c) < Schema.CURRENT_VERSION);
            assertFalse(tableExists(c, "portfolio_movement"));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM transactions")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "orphan rows must not be deleted");
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM investment_snapshots")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    // ===== helpers =====

    static boolean tableExists(Connection c, String name) throws Exception {
        try (var ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static List<String> columnsOf(Connection c, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }
}

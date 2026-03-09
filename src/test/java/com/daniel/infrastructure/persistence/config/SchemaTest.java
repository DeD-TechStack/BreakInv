package com.daniel.infrastructure.persistence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lightweight integration tests for Schema DDL.
 *
 * Uses an in-memory SQLite database (:memory:) so no production DB is touched.
 * The concrete repositories are NOT tested here because they call Database.open()
 * statically (hardcoded file path) — that would require production code changes.
 *
 * What IS tested:
 *   1. Schema.createTables() returns a non-empty SQL string.
 *   2. The DDL executes without errors on a fresh in-memory DB.
 *   3. Expected tables exist after DDL execution.
 *   4. Expected columns exist in key tables.
 *   5. Schema.needsMigration() returns false on a fully-migrated DB.
 *   6. Schema.needsMigration() returns true on an old DB without new columns.
 */
class SchemaTest {

    private Connection conn;

    @BeforeEach
    void openInMemoryDb() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @AfterEach
    void closeDb() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ===== Schema.createTables() string checks =====

    @Test
    void createTables_returnsNonEmptyString() {
        String ddl = Schema.createTables();
        assertNotNull(ddl);
        assertFalse(ddl.isBlank());
    }

    @Test
    void createTables_containsInvestmentTypeTable() {
        assertTrue(Schema.createTables().contains("investment_type"));
    }

    @Test
    void createTables_containsFlowsTable() {
        assertTrue(Schema.createTables().contains("flows"));
    }

    @Test
    void createTables_containsCashSnapshotsTable() {
        assertTrue(Schema.createTables().contains("cash_snapshots"));
    }

    @Test
    void createTables_containsInvestmentSnapshotsTable() {
        assertTrue(Schema.createTables().contains("investment_snapshots"));
    }

    @Test
    void createTables_containsTransactionsTable() {
        assertTrue(Schema.createTables().contains("transactions"));
    }

    @Test
    void createTables_containsAppSettingsTable() {
        assertTrue(Schema.createTables().contains("app_settings"));
    }

    @Test
    void createTables_containsTickerColumn() {
        assertTrue(Schema.createTables().contains("ticker"));
    }

    @Test
    void createTables_containsTypeOfInvestmentColumn() {
        assertTrue(Schema.createTables().contains("type_of_investment"));
    }

    // ===== DDL execution on in-memory DB =====

    @Test
    void createTables_executesWithoutError() throws Exception {
        String ddl = Schema.createTables();
        try (Statement stmt = conn.createStatement()) {
            for (String sql : ddl.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed); // must not throw
                }
            }
        }
        // If we reach here, all DDL executed cleanly
    }

    @Test
    void afterDdl_investmentTypeTableExists() throws Exception {
        applyDdl();
        assertTrue(tableExists("investment_type"));
    }

    @Test
    void afterDdl_flowsTableExists() throws Exception {
        applyDdl();
        assertTrue(tableExists("flows"));
    }

    @Test
    void afterDdl_cashSnapshotsTableExists() throws Exception {
        applyDdl();
        assertTrue(tableExists("cash_snapshots"));
    }

    @Test
    void afterDdl_investmentSnapshotsTableExists() throws Exception {
        applyDdl();
        assertTrue(tableExists("investment_snapshots"));
    }

    @Test
    void afterDdl_transactionsTableExists() throws Exception {
        applyDdl();
        assertTrue(tableExists("transactions"));
    }

    @Test
    void afterDdl_appSettingsTableExists() throws Exception {
        applyDdl();
        assertTrue(tableExists("app_settings"));
    }

    @Test
    void afterDdl_investmentType_hasExpectedColumns() throws Exception {
        applyDdl();
        List<String> cols = columnsOf("investment_type");
        assertTrue(cols.contains("id"), "Missing column: id");
        assertTrue(cols.contains("name"), "Missing column: name");
        assertTrue(cols.contains("category"), "Missing column: category");
        assertTrue(cols.contains("liquidity"), "Missing column: liquidity");
        assertTrue(cols.contains("investment_date"), "Missing column: investment_date");
        assertTrue(cols.contains("profitability"), "Missing column: profitability");
        assertTrue(cols.contains("invested_value"), "Missing column: invested_value");
        assertTrue(cols.contains("type_of_investment"), "Missing column: type_of_investment");
        assertTrue(cols.contains("ticker"), "Missing column: ticker");
        assertTrue(cols.contains("purchase_price"), "Missing column: purchase_price");
        assertTrue(cols.contains("quantity"), "Missing column: quantity");
    }

    @Test
    void afterDdl_flows_hasExpectedColumns() throws Exception {
        applyDdl();
        List<String> cols = columnsOf("flows");
        assertTrue(cols.contains("id"));
        assertTrue(cols.contains("date"));
        assertTrue(cols.contains("from_kind"));
        assertTrue(cols.contains("to_kind"));
        assertTrue(cols.contains("amount_cents"));
    }

    @Test
    void afterDdl_transactions_hasExpectedColumns() throws Exception {
        applyDdl();
        List<String> cols = columnsOf("transactions");
        assertTrue(cols.contains("type"));
        assertTrue(cols.contains("ticker"));
        assertTrue(cols.contains("quantity"));
        assertTrue(cols.contains("unit_price_cents"));
        assertTrue(cols.contains("total_cents"));
    }

    // ===== Schema.needsMigration() =====

    @Test
    void needsMigration_afterFullDdl_returnsFalse() throws Exception {
        applyDdl();
        assertFalse(Schema.needsMigration(conn),
                "A freshly created schema should NOT need migration");
    }

    @Test
    void needsMigration_oldSchemaWithoutTypeColumn_returnsTrue() throws Exception {
        // Simulate an old DB without the type_of_investment column
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE investment_type (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        category TEXT,
                        liquidity TEXT
                    )
                    """);
        }
        assertTrue(Schema.needsMigration(conn),
                "Old schema without type_of_investment should need migration");
    }

    @Test
    void needsMigration_tableWithColumn_returnsFalse() throws Exception {
        // Table with type_of_investment present → no migration needed
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE investment_type (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        type_of_investment TEXT
                    )
                    """);
        }
        assertFalse(Schema.needsMigration(conn));
    }

    // ===== Schema.migrationScript() =====

    @Test
    void migrationScript_returnsNonEmptyString() {
        String script = Schema.migrationScript();
        assertNotNull(script);
        assertFalse(script.isBlank());
    }

    @Test
    void migrationScript_containsTypeOfInvestmentAlter() {
        assertTrue(Schema.migrationScript().contains("type_of_investment"));
    }

    // ===== Helpers =====

    private void applyDdl() throws Exception {
        String ddl = Schema.createTables();
        try (Statement stmt = conn.createStatement()) {
            for (String sql : ddl.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
        }
    }

    private boolean tableExists(String tableName) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private List<String> columnsOf(String tableName) throws Exception {
        List<String> cols = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }
}

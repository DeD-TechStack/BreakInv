package com.daniel.infrastructure.persistence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that Database.open() runs the schema migration when an old
 * investment_type table (missing newer columns) is found on startup.
 * Uses temp files so the production DB is never touched.
 */
class DatabaseMigrationTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetDatabase() {
        Database.close();
    }

    @Test
    void open_withOldSchema_migratesAllNewerColumns() throws Exception {
        String jdbcUrl = jdbcUrl("old_schema.db");

        // Build an old-schema file directly — type_of_investment and all newer
        // columns are absent, simulating a database created before the migration.
        try (Connection setup = DriverManager.getConnection(jdbcUrl);
             Statement stmt = setup.createStatement()) {
            stmt.execute("""
                    CREATE TABLE investment_type (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        name             TEXT NOT NULL,
                        category         TEXT,
                        liquidity        TEXT,
                        investment_date  TEXT,
                        profitability    REAL,
                        invested_value   REAL
                    )
                    """);
        }

        // Open through the singleton — migration must fire automatically.
        Database.configure(jdbcUrl);
        Database.open();

        List<String> cols = columnsOf(Database.open(), "investment_type");
        assertTrue(cols.contains("type_of_investment"), "Missing: type_of_investment");
        assertTrue(cols.contains("index_type"),         "Missing: index_type");
        assertTrue(cols.contains("index_percentage"),   "Missing: index_percentage");
        assertTrue(cols.contains("ticker"),             "Missing: ticker");
        assertTrue(cols.contains("purchase_price"),     "Missing: purchase_price");
        assertTrue(cols.contains("quantity"),           "Missing: quantity");
    }

    @Test
    void open_withFreshSchema_doesNotFailMigrationCheck() {
        Database.configure(jdbcUrl("fresh.db"));
        assertDoesNotThrow(Database::open,
                "Database.open() must not throw for a fresh DB that needs no migration");
    }

    private String jdbcUrl(String filename) {
        return "jdbc:sqlite:" + tempDir.resolve(filename).toString().replace("\\", "/");
    }

    private List<String> columnsOf(Connection conn, String tableName) throws Exception {
        List<String> cols = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }
}

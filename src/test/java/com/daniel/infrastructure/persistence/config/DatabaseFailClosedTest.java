package com.daniel.infrastructure.persistence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseFailClosedTest {
    @TempDir Path temp;
    @AfterEach void close() { Database.close(); }

    @Test
    void futureSchemaIsRefusedWithoutCreatingProductTables() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("future.db");
        try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
            st.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,description TEXT,applied_at TEXT NOT NULL)");
            st.execute("INSERT INTO schema_migrations VALUES(99,'future','2026-01-01')");
        }
        Database.configure(url);
        assertThrows(SchemaMigrator.MigrationException.class, Database::open);
        try (var c = DriverManager.getConnection(url); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='investment_type'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void failedMigrationCannotBeBypassedByOpeningAgain() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("orphan.db");
        try (var c = DriverManager.getConnection(url)) {
            SchemaMigrator.execScript(c, Schema.createTables());
            try (var st = c.createStatement()) {
                st.execute("INSERT INTO investment_snapshots(date,investment_type_id,value_cents) VALUES('2026-01-01',99,100)");
            }
        }
        Database.configure(url);
        assertThrows(SchemaMigrator.MigrationException.class, Database::open);
        assertThrows(SchemaMigrator.MigrationException.class, Database::open);
        assertThrows(SchemaMigrator.MigrationException.class, Database::newConnection);
    }
}

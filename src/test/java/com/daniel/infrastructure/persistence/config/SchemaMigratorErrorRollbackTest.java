package com.daniel.infrastructure.persistence.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An {@link Error} thrown mid-step must roll the version back, never commit it via the finally. */
class SchemaMigratorErrorRollbackTest {

    @TempDir
    Path tempDir;

    @Test
    void errorDuringStep_rollsBackPartialVersion_andLeavesConnectionUsable() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("err.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(url)) {
            SchemaMigrator failing = new SchemaMigrator(List.of(new SchemaMigrator.Migration(1, "partial", conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE t_partial (id INTEGER)");
                }
                throw new AssertionError("boom");
            })));
            assertThrows(AssertionError.class, () -> failing.migrateAll(c));

            assertTrue(c.getAutoCommit(), "auto-commit restored");
            assertFalse(tableExists(c, "t_partial"), "partial DDL must be rolled back");
            assertEquals(0, Schema.appliedVersion(c), "no version recorded");

            SchemaMigrator good = new SchemaMigrator(List.of(new SchemaMigrator.Migration(1, "ok", conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE t_ok (id INTEGER)");
                }
            })));
            good.migrateAll(c);
            assertTrue(tableExists(c, "t_ok"));
            assertEquals(1, Schema.appliedVersion(c));
        }
    }

    static boolean tableExists(Connection c, String name) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='" + name + "'")) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }
}

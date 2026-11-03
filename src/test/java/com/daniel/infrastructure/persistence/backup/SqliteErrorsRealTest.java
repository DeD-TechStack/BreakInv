package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.backup.SqliteErrors.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4A:RF-05: the categories proven with exceptions the SQLite really throws, on disposable files in
 * the test's temp folder. Each case prints the observed class, result code and error code (HANDOFF).
 */
class SqliteErrorsRealTest {

    @TempDir
    Path tempDir;

    static String url(Path file) {
        return "jdbc:sqlite:" + file.toString().replace("\\", "/");
    }

    private static SQLException observed(String label, SQLException e) {
        String name = e instanceof SQLiteException se && se.getResultCode() != null ? se.getResultCode().name() : "-";
        System.out.println("RF05-OBSERVED " + label + " class=" + e.getClass().getName() + " resultCode=" + name
                + " errorCode=" + e.getErrorCode() + " message=" + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        return e;
    }

    /** Fills a table on {@code conn} until its page limit; returns the SQLITE_FULL the insert throws. */
    static SQLException fillUntilFull(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS filler(v BLOB)");
            st.execute("INSERT INTO filler(v) VALUES (zeroblob(100))");
            long pages;
            try (ResultSet rs = st.executeQuery("PRAGMA page_count")) {
                rs.next();
                pages = rs.getLong(1);
            }
            st.execute("PRAGMA max_page_count = " + pages);
            for (int i = 0; i < 1000; i++) {
                try {
                    st.execute("INSERT INTO filler(v) VALUES (zeroblob(8192))");
                } catch (SQLException e) {
                    return e;
                }
            }
        }
        throw new AssertionError("no SQLITE_FULL after 1000 inserts");
    }

    @Test
    void space_pageLimitReached_isSpace() throws Exception {
        try (Connection c = DriverManager.getConnection(url(tempDir.resolve("cheio.db")))) {
            SQLException e = observed("ESPACO", fillUntilFull(c));
            assertEquals(Category.SPACE, SqliteErrors.ofChain(e));
        }
    }

    @Test
    void writeDenied_readOnlyFile_isWriteDenied() throws Exception {
        Path file = tempDir.resolve("somente-leitura.db");
        try (Connection c = DriverManager.getConnection(url(file)); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t(v INTEGER)");
            st.execute("INSERT INTO t(v) VALUES (1)");
        }
        assertTrue(file.toFile().setReadOnly(), "file marked read-only");
        try (Connection c = DriverManager.getConnection(url(file)); Statement st = c.createStatement()) {
            SQLException e = observed("PERMISSAO", assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO t(v) VALUES (2)")));
            assertEquals(Category.WRITE_DENIED, SqliteErrors.ofChain(e));
        } finally {
            assertTrue(file.toFile().setWritable(true), "file writable again for the cleanup");
        }
    }

    @Test
    void cantOpen_folderMissing_isCantOpen() {
        Path file = tempDir.resolve("pasta-que-nao-existe").resolve("x.db");
        SQLException e = observed("NAO_ABRE", assertThrows(SQLException.class, () -> {
            try (Connection c = DriverManager.getConnection(url(file)); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE t(v INTEGER)");
            }
        }));
        assertEquals(Category.CANT_OPEN, SqliteErrors.ofChain(e));
    }

    @Test
    void busy_exclusiveTransactionElsewhere_isBusy() throws Exception {
        Path file = tempDir.resolve("ocupado.db");
        try (Connection first = DriverManager.getConnection(url(file)); Statement a = first.createStatement()) {
            a.execute("CREATE TABLE t(v INTEGER)");
            a.execute("BEGIN EXCLUSIVE");
            try (Connection second = DriverManager.getConnection(url(file)); Statement b = second.createStatement()) {
                b.execute("PRAGMA busy_timeout = 0");
                SQLException e = observed("OCUPADO", assertThrows(SQLException.class,
                        () -> b.execute("INSERT INTO t(v) VALUES (1)")));
                assertEquals(Category.BUSY, SqliteErrors.ofChain(e));
            } finally {
                a.execute("ROLLBACK");
            }
        }
    }

    @Test
    void damaged_fileOfRepeatedBytes_isDamaged() throws Exception {
        Path file = tempDir.resolve("danificado.db");
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 0x42);
        Files.write(file, bytes);
        try (Connection c = DriverManager.getConnection(url(file)); Statement st = c.createStatement()) {
            SQLException e = observed("DANIFICADO", assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT count(*) FROM sqlite_master").close()));
            assertEquals(Category.DAMAGED, SqliteErrors.ofChain(e));
        }
    }
}

package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static com.daniel.infrastructure.persistence.backup.SqliteErrorsRealTest.url;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U4A:RF-03 ("current data damaged") and U4A:RF-04 ("in use") of the restore, by the SQLite code. */
class RestoreErrorDecisionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Database.close();
    }

    private Path notADatabase() throws Exception {
        Path file = tempDir.resolve("danificado.db");
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 0x42);
        Files.write(file, bytes);
        return file;
    }

    private Path validDatabase(String name) throws Exception {
        Path file = tempDir.resolve(name);
        try (Connection c = DriverManager.getConnection(url(file)); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t(v INTEGER)");
            st.execute("INSERT INTO t(v) VALUES (1)");
        }
        return file;
    }

    // ---------------------------------------------------------------- RF-03

    /** As in a restore: the app already opened (and migrated) the live file, then its header broke. */
    @Test
    void damaged_notADatabase_real_throughTheAppConnection_isTrue() throws Exception {
        Path file = tempDir.resolve("breakinv.db");
        Database.configure(url(file));
        Database.open();
        BackupTestSupport.corruptHeader(file);
        assertTrue(RestoreService.currentDataDamaged(Database::newConnection));
    }

    @Test
    void damaged_notADatabase_real_onAPlainConnection_isTrue() throws Exception {
        Path file = notADatabase();
        assertTrue(RestoreService.currentDataDamaged(() -> DriverManager.getConnection(url(file))));
    }

    @Test
    void damaged_busyFailure_real_isFalse() throws Exception {
        Path file = validDatabase("ocupado.db");
        try (Connection first = DriverManager.getConnection(url(file)); Statement a = first.createStatement()) {
            a.execute("BEGIN EXCLUSIVE");
            try {
                assertFalse(RestoreService.currentDataDamaged(() -> {
                    Connection c = DriverManager.getConnection(url(file));
                    try (Statement st = c.createStatement()) {
                        st.execute("PRAGMA busy_timeout = 0");
                    }
                    return c;
                }));
            } finally {
                a.execute("ROLLBACK");
            }
        }
    }

    @Test
    void damaged_genericCodeWithMalformed_isTrue_asToday() {
        assertTrue(RestoreService.currentDataDamaged(() -> {
            throw new SQLException("database disk image is malformed", null, 1);
        }));
    }

    @Test
    void damaged_onlyInACause_isTrue() {
        assertTrue(RestoreService.currentDataDamaged(() -> {
            throw new RuntimeException("Erro ao abrir conexão independente: x", new SQLException("y", null, 11));
        }));
    }

    @Test
    void damaged_codeOfAnotherCategory_withCorruptInTheMessage_isFalse() {
        assertFalse(RestoreService.currentDataDamaged(() -> {
            throw new SQLiteException("corrupt file system", SQLiteErrorCode.SQLITE_IOERR_CORRUPTFS);
        }));
        assertFalse(RestoreService.currentDataDamaged(() -> {
            throw new SQLiteException("corrupt", SQLiteErrorCode.SQLITE_CANTOPEN);
        }));
        assertFalse(RestoreService.currentDataDamaged(() -> {
            throw new SQLiteException("corrupt", SQLiteErrorCode.SQLITE_READONLY);
        }));
    }

    @Test
    void damaged_otherFailures_areFalse() {
        assertFalse(RestoreService.currentDataDamaged(() -> {
            throw new SQLiteException("x", SQLiteErrorCode.SQLITE_FULL);
        }));
        assertFalse(RestoreService.currentDataDamaged(() -> {
            throw new RuntimeException("x");
        }));
    }

    @Test
    void damaged_healthyDatabase_isFalse() throws Exception {
        Path file = validDatabase("saudavel.db");
        assertFalse(RestoreService.currentDataDamaged(() -> DriverManager.getConnection(url(file))));
    }

    // ---------------------------------------------------------------- RF-04

    @Test
    void busy_real_isTrue() throws Exception {
        Path file = validDatabase("ocupado-real.db");
        try (Connection first = DriverManager.getConnection(url(file)); Statement a = first.createStatement()) {
            a.execute("BEGIN EXCLUSIVE");
            try (Connection second = DriverManager.getConnection(url(file)); Statement b = second.createStatement()) {
                b.execute("PRAGMA busy_timeout = 0");
                SQLException e = assertThrows(SQLException.class, () -> b.execute("BEGIN EXCLUSIVE"));
                assertTrue(RestoreService.busy(e), e.getMessage());
            } finally {
                a.execute("ROLLBACK");
            }
        }
    }

    @Test
    void busy_lockedConstructed_isTrue() {
        assertTrue(RestoreService.busy(new SQLiteException("x", SQLiteErrorCode.SQLITE_LOCKED)));
        assertTrue(RestoreService.busy(new SQLiteException("x", SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE)));
        assertTrue(RestoreService.busy(new SQLException("x", null, 6)));
    }

    @Test
    void busy_asACause_isTrue() {
        SQLException outer = new SQLException("x", null, 1);
        outer.initCause(new SQLiteException("y", SQLiteErrorCode.SQLITE_BUSY));
        assertTrue(RestoreService.busy(outer));
    }

    @Test
    void busy_databaseIsLockedWithoutCode_isTrue() {
        assertTrue(RestoreService.busy(new SQLException("database is locked")));
    }

    @Test
    void busy_full_isFalse() {
        assertFalse(RestoreService.busy(new SQLiteException("database is locked", SQLiteErrorCode.SQLITE_FULL)));
        assertFalse(RestoreService.busy(new SQLException("x", null, 13)));
        assertFalse(RestoreService.busy(new SQLException("x", null, 1)));
    }
}

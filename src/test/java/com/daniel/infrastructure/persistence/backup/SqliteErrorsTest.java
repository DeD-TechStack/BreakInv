package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.backup.SqliteErrors.Category;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.sql.SQLException;
import java.util.Map;

import static com.daniel.infrastructure.persistence.backup.SqliteErrors.Category.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** U4A:RF-01: one category per exception, code (extended name, then primary) before the message. */
class SqliteErrorsTest {

    private static SQLiteException named(SQLiteErrorCode code) {
        return new SQLiteException("x", code);
    }

    private static SQLException primary(int code) {
        return new SQLException("x", null, code);
    }

    @Test
    void extendedName_decidesTheCategory_forEveryRowOfTheTable() {
        Map<SQLiteErrorCode, Category> expected = new java.util.LinkedHashMap<>();
        expected.put(SQLiteErrorCode.SQLITE_FULL, SPACE);
        expected.put(SQLiteErrorCode.SQLITE_IOERR, IO);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_READ, IO);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_WRITE, IO);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_NOMEM, IO);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_CORRUPTFS, IO);
        expected.put(SQLiteErrorCode.SQLITE_CANTOPEN, CANT_OPEN);
        expected.put(SQLiteErrorCode.SQLITE_CANTOPEN_ISDIR, CANT_OPEN);
        expected.put(SQLiteErrorCode.SQLITE_CANTOPEN_FULLPATH, CANT_OPEN);
        expected.put(SQLiteErrorCode.SQLITE_PERM, WRITE_DENIED);
        expected.put(SQLiteErrorCode.SQLITE_READONLY, WRITE_DENIED);
        expected.put(SQLiteErrorCode.SQLITE_READONLY_DBMOVED, WRITE_DENIED);
        expected.put(SQLiteErrorCode.SQLITE_READONLY_RECOVERY, WRITE_DENIED);
        expected.put(SQLiteErrorCode.SQLITE_BUSY, BUSY);
        expected.put(SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT, BUSY);
        expected.put(SQLiteErrorCode.SQLITE_LOCKED, BUSY);
        expected.put(SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE, BUSY);
        expected.put(SQLiteErrorCode.SQLITE_CORRUPT, DAMAGED);
        expected.put(SQLiteErrorCode.SQLITE_CORRUPT_INDEX, DAMAGED);
        expected.put(SQLiteErrorCode.SQLITE_NOTADB, DAMAGED);
        expected.put(SQLiteErrorCode.SQLITE_ERROR, OTHER);
        expected.put(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, OTHER);
        expected.put(SQLiteErrorCode.SQLITE_NOMEM, OTHER);
        Map<SQLiteErrorCode, Category> actual = new java.util.LinkedHashMap<>();
        expected.keySet().forEach(code -> actual.put(code, SqliteErrors.of(named(code))));
        assertEquals(expected, actual);
    }

    @Test
    void lockedByName_isBusy() {
        assertEquals(BUSY, SqliteErrors.of(named(SQLiteErrorCode.SQLITE_LOCKED)));
        assertEquals(BUSY, SqliteErrors.of(named(SQLiteErrorCode.SQLITE_LOCKED_VTAB)));
    }

    @Test
    void primaryCode_decidesTheCategory_forEveryRowOfTheTable() {
        Map<Integer, Category> expected = new java.util.LinkedHashMap<>();
        expected.put(13, SPACE);
        expected.put(10, IO);
        expected.put(14, CANT_OPEN);
        expected.put(3, WRITE_DENIED);
        expected.put(8, WRITE_DENIED);
        expected.put(5, BUSY);
        expected.put(6, BUSY);
        expected.put(11, DAMAGED);
        expected.put(26, DAMAGED);
        expected.put(1, OTHER);
        expected.put(19, OTHER);
        expected.put(10 | (13 << 8), IO); // SQLITE_IOERR_FSYNC: only the low byte counts
        expected.put(6 | (1 << 8), BUSY); // SQLITE_LOCKED_SHAREDCACHE
        Map<Integer, Category> actual = new java.util.LinkedHashMap<>();
        expected.keySet().forEach(code -> actual.put(code, SqliteErrors.of(primary(code))));
        assertEquals(expected, actual);
    }

    @Test
    void lockedByPrimary_isBusy() {
        assertEquals(BUSY, SqliteErrors.of(primary(6)));
    }

    @Test
    void nameInOther_doesNotBlockThePrimary() {
        SQLiteException e = new SQLiteException("x", SQLiteErrorCode.SQLITE_ERROR) {
            @Override
            public int getErrorCode() {
                return 13;
            }
        };
        assertEquals(SPACE, SqliteErrors.of(e));
    }

    @Test
    void nullResultCode_isNoName_andThePrimaryDecides() {
        SQLiteException e = new SQLiteException("x", SQLiteErrorCode.SQLITE_BUSY) {
            @Override
            public SQLiteErrorCode getResultCode() {
                return null;
            }
        };
        assertEquals(BUSY, SqliteErrors.of(e));
    }

    @Test
    void message_decides_forEveryWordOfTheTable_onADriverExceptionWithoutCode() {
        Map<String, Category> expected = new java.util.LinkedHashMap<>();
        expected.put("[SQLITE_FULL] x", SPACE);
        expected.put("database or disk is full", SPACE);
        expected.put("There is NOT ENOUGH SPACE on the disk", SPACE);
        expected.put("Espaço insuficiente no disco", SPACE);
        expected.put("[SQLITE_IOERR_WRITE] x", IO);
        expected.put("disk I/O error", IO);
        expected.put("[SQLITE_CANTOPEN] x", CANT_OPEN);
        expected.put("Unable to open the database file", CANT_OPEN);
        expected.put("[SQLITE_READONLY] x", WRITE_DENIED);
        expected.put("attempt to write a readonly database", WRITE_DENIED);
        expected.put("[SQLITE_PERM] x", WRITE_DENIED);
        expected.put("access permission denied", WRITE_DENIED);
        expected.put("[SQLITE_BUSY] x", BUSY);
        expected.put("database is locked", BUSY);
        expected.put("database table is locked", BUSY);
        expected.put("database disk image is malformed", DAMAGED);
        expected.put("the file is corrupt", DAMAGED);
        expected.put("file is not a database", DAMAGED);
        expected.put("no such column: x", OTHER);
        Map<String, Category> actual = new java.util.LinkedHashMap<>();
        expected.keySet().forEach(m -> actual.put(m, SqliteErrors.of(new SQLException(m))));
        assertEquals(expected, actual);
    }

    @Test
    void driverOnlyWords_doNotCountOutsideTheDriver_theOthersDo() {
        Map<String, Category> expected = new java.util.LinkedHashMap<>();
        expected.put("disk is full", SPACE);
        expected.put("sqlite_cantopen", CANT_OPEN);
        expected.put("unable to open x", CANT_OPEN);
        expected.put("sqlite_busy", BUSY);
        expected.put("database is locked", BUSY);
        expected.put("disk i/o error", OTHER);
        expected.put("sqlite_ioerr", OTHER);
        expected.put("readonly database", OTHER);
        expected.put("access permission denied", OTHER);
        expected.put("database table is locked", OTHER);
        expected.put("malformed", OTHER);
        expected.put("corrupt", OTHER);
        expected.put("not a database", OTHER);
        Map<String, Category> actual = new java.util.LinkedHashMap<>();
        expected.keySet().forEach(m -> actual.put(m, SqliteErrors.of(new IOException(m))));
        assertEquals(expected, actual);
    }

    @Test
    void theExamplesOfTheSpec() {
        assertEquals(SPACE, SqliteErrors.of(new SQLiteException("database is locked", SQLiteErrorCode.SQLITE_FULL)));
        assertEquals(DAMAGED, SqliteErrors.of(new SQLException("database disk image is malformed", null, 1)));
        assertEquals(SPACE, SqliteErrors.of(new SQLException("x", null, 13 | (99 << 8))));
        assertEquals(SPACE, SqliteErrors.of(new RuntimeException("disk is full")));
        assertEquals(OTHER, SqliteErrors.of(new FileSystemException("C:/corrupt/x", null,
                "The file or directory is corrupted and unreadable")));
        assertEquals(OTHER, SqliteErrors.of(new RuntimeException((String) null)));
    }

    @Test
    void theCodeWins_overAMessageOfAnotherCategory() {
        assertEquals(SPACE, SqliteErrors.of(new SQLiteException("database is locked", SQLiteErrorCode.SQLITE_FULL)));
        assertEquals(IO, SqliteErrors.of(new SQLiteException("database disk image is malformed",
                SQLiteErrorCode.SQLITE_IOERR_CORRUPTFS)));
        assertEquals(BUSY, SqliteErrors.of(new SQLException("disk is full", null, 5)));
    }

    @Test
    void messageMatchingTwoRows_takesTheUpperOne() {
        assertEquals(SPACE, SqliteErrors.of(new SQLException("disk is full; database is locked")));
        assertEquals(IO, SqliteErrors.of(new SQLException("disk i/o error: file is not a database")));
    }

    @Test
    void unknownErrorWithoutNumber_hasNoCode_andTheMessageDecides() {
        assertEquals(BUSY, SqliteErrors.of(new SQLiteException("database is locked", SQLiteErrorCode.UNKNOWN_ERROR)));
        assertEquals(OTHER, SqliteErrors.of(new SQLiteException("x", SQLiteErrorCode.UNKNOWN_ERROR)));
        assertEquals(DAMAGED, SqliteErrors.of(new SQLException("malformed", null, 0)));
        assertEquals(DAMAGED, SqliteErrors.of(new SQLException("malformed", null, -1)));
    }

    @Test
    void nullMessage_isOther_unlessACauseDecides() {
        assertEquals(OTHER, SqliteErrors.of(new SQLException((String) null)));
        assertEquals(OTHER, SqliteErrors.ofChain(new RuntimeException((String) null)));
        assertEquals(DAMAGED, SqliteErrors.ofChain(new RuntimeException((String) null,
                new SQLException("x", null, 26))));
    }

    @Test
    void chain_isReadFromOutsideIn_andTheFirstCategoryOtherThanOtherWins() {
        SQLException inner = new SQLiteException("x", SQLiteErrorCode.SQLITE_BUSY);
        SQLException middle = new SQLException("disk is full", inner);
        RuntimeException outer = new RuntimeException("Erro ao abrir conexão independente: x", middle);
        assertEquals(OTHER, SqliteErrors.of(outer));
        assertEquals(SPACE, SqliteErrors.ofChain(outer));
        assertEquals(BUSY, SqliteErrors.ofChain(new RuntimeException("x", new IOException("y", inner))));
        assertEquals(OTHER, SqliteErrors.ofChain(new RuntimeException("x", new IOException("y"))));
    }

    @Test
    void nextExceptionAndSuppressed_doNotCount() {
        SQLException outer = new SQLException("x", null, 1);
        outer.setNextException(new SQLException("x", null, 5));
        outer.addSuppressed(new SQLException("x", null, 13));
        assertEquals(OTHER, SqliteErrors.ofChain(outer));
    }
}

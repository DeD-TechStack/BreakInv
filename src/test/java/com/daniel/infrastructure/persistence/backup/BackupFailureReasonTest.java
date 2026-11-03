package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** U4A:RF-02 and U4A:RN-01 (a): the reason of a failed copy, from the code first, read along the chain. */
class BackupFailureReasonTest {

    static final Path WHERE = Path.of("C:/Dados/BreakInv/copias");
    static final String SECRET = "VALOR-SECRETO-4471";

    static final String SPACE = "o disco está cheio";
    static final String IO = "erro de leitura ou gravação no disco em " + WHERE;
    static final String CANT_OPEN = "não foi possível criar o arquivo da cópia em " + WHERE
            + " (verifique a permissão de gravação)";
    static final String WRITE_DENIED = "sem permissão para gravar em " + WHERE;
    static final String BUSY = "os dados estavam em uso; tente de novo";
    static final String DAMAGED = "o arquivo está danificado ou não é um banco de dados do BreakInv";

    private static String describe(Throwable e) {
        return BackupService.describe(e, WHERE);
    }

    private static FileSystemException withCause(FileSystemException e, Throwable cause) {
        e.initCause(cause);
        return e;
    }

    @Test
    void item1_copyFailure_showsItsOwnMessage() {
        assertEquals("os dados mudaram durante a cópia; tente de novo",
                describe(new BackupService.CopyFailure("os dados mudaram durante a cópia; tente de novo")));
        assertEquals("a cópia não conferiu com os dados de origem (disk is full)",
                describe(new BackupService.CopyFailure("a cópia não conferiu com os dados de origem (disk is full)")));
    }

    @Test
    void item2_accessDenied_isTheTextOfToday() {
        assertEquals(WRITE_DENIED, describe(new AccessDeniedException("C:/x", null, "disk is full")));
    }

    @Test
    void item3_eachCategoryByExtendedName_givesItsText() {
        Map<SQLiteErrorCode, String> expected = new LinkedHashMap<>();
        expected.put(SQLiteErrorCode.SQLITE_FULL, SPACE);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_WRITE, IO);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_NOMEM, IO);
        expected.put(SQLiteErrorCode.SQLITE_IOERR_CORRUPTFS, IO);
        expected.put(SQLiteErrorCode.SQLITE_CANTOPEN, CANT_OPEN);
        expected.put(SQLiteErrorCode.SQLITE_READONLY, WRITE_DENIED);
        expected.put(SQLiteErrorCode.SQLITE_PERM, WRITE_DENIED);
        expected.put(SQLiteErrorCode.SQLITE_BUSY, BUSY);
        expected.put(SQLiteErrorCode.SQLITE_LOCKED, BUSY);
        expected.put(SQLiteErrorCode.SQLITE_CORRUPT, DAMAGED);
        expected.put(SQLiteErrorCode.SQLITE_NOTADB, DAMAGED);
        Map<SQLiteErrorCode, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(code -> actual.put(code, describe(new SQLiteException("x", code))));
        assertEquals(expected, actual);
    }

    @Test
    void item3_spaceByName_isSpace() {
        assertEquals(SPACE, describe(new SQLiteException("x", SQLiteErrorCode.SQLITE_FULL)));
    }

    @Test
    void item3_ioByName_isIo() {
        assertEquals(IO, describe(new SQLiteException("x", SQLiteErrorCode.SQLITE_IOERR)));
    }

    @Test
    void item3_eachCategoryByPrimaryCode_givesItsText() {
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(13, SPACE);
        expected.put(10, IO);
        expected.put(14, CANT_OPEN);
        expected.put(8, WRITE_DENIED);
        expected.put(3, WRITE_DENIED);
        expected.put(5, BUSY);
        expected.put(6, BUSY);
        expected.put(11, DAMAGED);
        expected.put(26, DAMAGED);
        Map<Integer, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(code -> actual.put(code, describe(new SQLException("x", null, code))));
        assertEquals(expected, actual);
    }

    @Test
    void item3_textsOfToday_byMessage_areUnchanged() {
        assertEquals(SPACE, describe(new RuntimeException("disk is full")));
        assertEquals(SPACE, describe(new IOException("There is not enough space on the disk")));
        assertEquals(SPACE, describe(new SQLException("[SQLITE_FULL] database or disk is full")));
        assertEquals(CANT_OPEN, describe(new IOException("unable to open the file")));
        assertEquals(CANT_OPEN, describe(new SQLException("[SQLITE_CANTOPEN] Unable to open the database file")));
        assertEquals(BUSY, describe(new IOException("database is locked")));
        assertEquals(BUSY, describe(new SQLException("[SQLITE_BUSY] The database file is locked")));
    }

    @Test
    void item3_newTexts_byMessageOfTheDriver() {
        assertEquals(IO, describe(new SQLException("disk I/O error")));
        assertEquals(WRITE_DENIED, describe(new SQLException("attempt to write a readonly database")));
        assertEquals(DAMAGED, describe(new SQLException("database disk image is malformed")));
        assertEquals(DAMAGED, describe(new SQLException("file is not a database", null, 1)));
    }

    @Test
    void item3_theCodeWins_overTheMessage() {
        assertEquals(SPACE, describe(new SQLiteException("database is locked", SQLiteErrorCode.SQLITE_FULL)));
        assertEquals(IO, describe(new SQLiteException("database disk image is malformed",
                SQLiteErrorCode.SQLITE_IOERR_CORRUPTFS)));
    }

    @Test
    void item4_fileSystemReason_isTheTextOfToday() {
        assertEquals("erro ao gravar a cópia: The device is not ready",
                describe(new FileSystemException("C:/x", null, "The device is not ready")));
        assertEquals("erro ao gravar a cópia: The file or directory is corrupted and unreadable",
                describe(new FileSystemException("C:/corrupt/x", null, "The file or directory is corrupted and unreadable")));
    }

    @Test
    void item5_noTestPasses_showsTheFirstLineOfTheOuterMessage() {
        assertEquals("erro ao criar a cópia: primeira linha",
                describe(new RuntimeException("primeira linha\nsegunda linha", new IOException("y"))));
        assertEquals("erro ao criar a cópia: null", describe(new RuntimeException((String) null)));
        assertEquals("erro ao criar a cópia: x", describe(new SQLException("x", null, 1)));
        assertEquals("erro ao criar a cópia: x", describe(new FileSystemException("x")));
    }

    @Test
    void chain_firstExceptionWhereATestPasses_wins() {
        assertEquals(SPACE, describe(new RuntimeException("x", new SQLiteException("y", SQLiteErrorCode.SQLITE_FULL))));
        assertEquals(DAMAGED, describe(new RuntimeException("disk i/o error",
                new SQLException("y", null, 26))));
        assertEquals(BUSY, describe(new RuntimeException("x", new SQLException("disk is full", null, 5))));
        assertEquals(WRITE_DENIED, describe(new RuntimeException("x",
                new AccessDeniedException("C:/x", null, "y"))));
    }

    @Test
    void chain_fileSystemReasonBeforeSpace_givesItem4_asToday() {
        assertEquals("erro ao gravar a cópia: motivo do sistema", describe(withCause(
                new FileSystemException("C:/x", null, "motivo do sistema"),
                new SQLiteException("y", SQLiteErrorCode.SQLITE_FULL))));
    }

    @Test
    void chain_spaceBeforeFileSystemReason_givesSpace() {
        SQLException outer = new SQLiteException("y", SQLiteErrorCode.SQLITE_FULL);
        outer.initCause(new FileSystemException("C:/x", null, "motivo do sistema"));
        assertEquals(SPACE, describe(outer));
    }

    @Test
    void rn01a_item3Reasons_neverCarryTheMessage() {
        List<SQLException> each = List.of(
                new SQLiteException("falhou em " + SECRET, SQLiteErrorCode.SQLITE_FULL),
                new SQLiteException("falhou em " + SECRET, SQLiteErrorCode.SQLITE_IOERR_WRITE),
                new SQLiteException("falhou em " + SECRET, SQLiteErrorCode.SQLITE_CANTOPEN),
                new SQLiteException("falhou em " + SECRET, SQLiteErrorCode.SQLITE_READONLY),
                new SQLiteException("falhou em " + SECRET, SQLiteErrorCode.SQLITE_BUSY),
                new SQLiteException("falhou em " + SECRET, SQLiteErrorCode.SQLITE_CORRUPT));
        List<String> reasons = each.stream().map(BackupFailureReasonTest::describe).toList();
        assertEquals(List.of(SPACE, IO, CANT_OPEN, WRITE_DENIED, BUSY, DAMAGED), reasons);
        reasons.forEach(r -> assertFalse(r.contains(SECRET), r));
    }
}

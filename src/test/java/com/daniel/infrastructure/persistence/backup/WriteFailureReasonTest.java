package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U4B:RF-02 (the reason of a failed write by the category of the chain) and U4B:RF-05 (the same reason, without
 * state, in the three reasons of the restore, through {@link RestoreService#reason}).
 */
class WriteFailureReasonTest {

    @TempDir
    Path tempDir;

    private static final String TODAY = "[SQLITE_X] mensagem de hoje";

    /** One exception of each category, built the way the driver builds them, wrapped as the repositories wrap. */
    private static Map<String, Throwable> byCategory() {
        Map<String, Throwable> cases = new LinkedHashMap<>();
        cases.put("disco cheio", wrapped(new SQLiteException("[SQLITE_FULL] database or disk is full", SQLiteErrorCode.SQLITE_FULL)));
        cases.put("erro de leitura ou gravação no disco", wrapped(new SQLiteException("[SQLITE_IOERR] disk I/O error", SQLiteErrorCode.SQLITE_IOERR)));
        cases.put("arquivo de dados inacessível", wrapped(new SQLiteException("[SQLITE_CANTOPEN] unable to open", SQLiteErrorCode.SQLITE_CANTOPEN)));
        cases.put("sem permissão de gravação no arquivo de dados", wrapped(new SQLiteException("[SQLITE_READONLY] readonly", SQLiteErrorCode.SQLITE_READONLY)));
        cases.put("dados em uso por outra operação", wrapped(new SQLiteException("[SQLITE_BUSY] locked", SQLiteErrorCode.SQLITE_BUSY)));
        cases.put("arquivo de dados danificado", wrapped(new SQLiteException("[SQLITE_CORRUPT] malformed", SQLiteErrorCode.SQLITE_CORRUPT)));
        return cases;
    }

    private static Throwable wrapped(SQLException e) {
        return new RuntimeException("portfolio_movement insert: " + e.getMessage(), e);
    }

    @Test
    void eachCategory_hasItsReason_andTheMessageOfTodayIsNotUsed() {
        byCategory().forEach((reason, failure) -> assertEquals(reason, WriteFailureReason.of(failure, TODAY), reason));
    }

    @Test
    void spaceReason_isDiscoCheio_notTheMessageOfToday() {
        Throwable space = wrapped(new SQLiteException("[SQLITE_FULL] database or disk is full", SQLiteErrorCode.SQLITE_FULL));
        assertEquals("disco cheio", WriteFailureReason.of(space, TODAY));
    }

    @Test
    void realSpaceFailure_isDiscoCheio() throws Exception {
        try (Connection c = DriverManager.getConnection(SqliteErrorsRealTest.url(tempDir.resolve("cheio.db")))) {
            SQLException full = SqliteErrorsRealTest.fillUntilFull(c);
            assertEquals("disco cheio", WriteFailureReason.of(wrapped(full), TODAY));
        }
    }

    @Test
    void otherCategory_isTheMessageOfToday_wordForWord() {
        assertEquals(TODAY, WriteFailureReason.of(new RuntimeException("x"), TODAY));
        assertEquals("", WriteFailureReason.of(new IllegalStateException(), ""));
        assertEquals("null", WriteFailureReason.of(new IllegalStateException(), String.valueOf((Object) null)));
    }

    // ---- U4B:RF-05: the restore uses the same reason

    @Test
    void restoreReason_eachCategory_isTheReasonOfTheTable() {
        byCategory().forEach((reason, failure) -> assertEquals(reason, RestoreService.reason(failure), reason));
    }

    @Test
    void restoreReason_spaceIsDiscoCheio_notTheFirstLine() {
        Throwable space = new SQLiteException("[SQLITE_FULL] database or disk is full\nsegunda linha", SQLiteErrorCode.SQLITE_FULL);
        assertEquals("disco cheio", RestoreService.reason(space));
    }

    @Test
    void restoreReason_other_isTheFirstLineOfTheMessage_asBefore() {
        assertEquals("primeira linha", RestoreService.reason(new SQLException("primeira linha\nsegunda linha")));
        assertEquals("IllegalStateException", RestoreService.reason(new IllegalStateException("")));
        assertEquals("null", RestoreService.reason(new IllegalStateException((String) null)));
    }
}

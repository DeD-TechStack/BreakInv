package com.daniel.presentation.view.util;

import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** U4C:RF-03 (the reason of a failed opening) and the reason of U4C:RF-04 (a failed daily snapshot). */
class FailureReasonTest {

    private static final String UNEXPECTED_OPENING = "erro inesperado ao abrir os dados";

    @Test
    void cantOpenCodeGivesTheInaccessibleFileReason() {
        RuntimeException failure = new RuntimeException("Erro ao conectar ao banco: [SQLITE_CANTOPEN] x",
                new SQLiteException("[SQLITE_CANTOPEN] unable to open", SQLiteErrorCode.SQLITE_CANTOPEN));

        assertEquals("arquivo de dados inacessível", FailureReason.forOpening(failure));
    }

    @Test
    void notADatabaseCodeGivesTheDamagedFileReason() {
        RuntimeException failure = new RuntimeException("Erro ao conectar ao banco: [SQLITE_NOTADB] x",
                new SQLiteException("[SQLITE_NOTADB] file is not a database", SQLiteErrorCode.SQLITE_NOTADB));

        assertEquals("arquivo de dados danificado", FailureReason.forOpening(failure));
    }

    @Test
    void otherFailureGivesItsOwnMessage() {
        assertEquals("sem espaço para a cópia antes da migração",
                FailureReason.forOpening(new IllegalStateException("sem espaço para a cópia antes da migração")));
    }

    @Test
    void completionExceptionIsUnwrappedAndOnlyTheFirstLineIsKept() {
        Throwable failure = new CompletionException(new IllegalStateException("x\nlinha 2"));

        assertEquals("x", FailureReason.forOpening(failure));
    }

    @Test
    void executionExceptionIsUnwrappedToo() {
        Throwable failure = new ExecutionException(new CompletionException(new IllegalStateException("y\r\nz")));

        assertEquals("y", FailureReason.forOpening(failure));
    }

    @Test
    void unwrappingDecidesTheCategoryToo() {
        Throwable failure = new CompletionException(new RuntimeException("Erro ao conectar ao banco: x",
                new SQLiteException("[SQLITE_CANTOPEN] unable to open", SQLiteErrorCode.SQLITE_CANTOPEN)));

        assertEquals("arquivo de dados inacessível", FailureReason.forOpening(failure));
    }

    @Test
    void wrapperWithoutCauseIsTheOutermostFailure() {
        assertEquals("sozinha", FailureReason.forOpening(new CompletionException("sozinha", null)));
    }

    @Test
    void noMessageGivesTheUnexpectedFailureText() {
        assertEquals(UNEXPECTED_OPENING, FailureReason.forOpening(new IllegalStateException()));
    }

    @Test
    void emptyBlankOrLineBreakFirstMessagesGiveTheUnexpectedFailureText() {
        assertEquals(UNEXPECTED_OPENING, FailureReason.forOpening(new IllegalStateException("")));
        assertEquals(UNEXPECTED_OPENING, FailureReason.forOpening(new IllegalStateException("   \t ")));
        assertEquals(UNEXPECTED_OPENING, FailureReason.forOpening(new IllegalStateException("\nsegunda linha")));
        assertEquals(UNEXPECTED_OPENING, FailureReason.forOpening(new IllegalStateException("  \r\nsegunda")));
    }

    @Test
    void firstLineLosesTheSpacesAtItsEnds() {
        assertEquals("com espaços", FailureReason.forOpening(new IllegalStateException("  com espaços  \nresto")));
    }

    // ── the reason of the daily snapshot: same rule, other text for "no message", no final dots ──

    @Test
    void snapshotReasonWithoutMessageIsUnexpectedError() {
        assertEquals("erro inesperado", FailureReason.forSnapshot(new IllegalStateException()));
        assertEquals("erro inesperado", FailureReason.forSnapshot(new IllegalStateException("  ")));
    }

    @Test
    void snapshotReasonLosesTheFinalDots() {
        assertEquals("limite de gravação atingido",
                FailureReason.forSnapshot(new IllegalStateException("limite de gravação atingido.")));
        assertEquals("reticências", FailureReason.forSnapshot(new IllegalStateException("reticências...")));
    }

    @Test
    void snapshotReasonUnwrapsAndUsesTheCategory() {
        Throwable failure = new CompletionException(new RuntimeException("x",
                new SQLiteException("[SQLITE_FULL] a write failed", SQLiteErrorCode.SQLITE_FULL)));

        assertEquals("disco cheio", FailureReason.forSnapshot(failure));
    }
}

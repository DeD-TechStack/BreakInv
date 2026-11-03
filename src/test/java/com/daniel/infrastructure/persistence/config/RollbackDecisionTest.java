package com.daniel.infrastructure.persistence.config;

import com.daniel.infrastructure.persistence.config.RollbackDecision.Outcome;
import com.daniel.infrastructure.persistence.config.RollbackDecision.RollbackResult;
import com.daniel.infrastructure.persistence.config.RollbackDecision.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** U4B:RF-01: the rule that fixes the state of the undoing, alone, with the cases of the spec table. */
class RollbackDecisionTest {

    @TempDir
    Path tempDir;

    // ---- rule 1: the body never started

    @Test
    void rule1_failureBeforeTheBody_isConfirmed_andTheRollbackIsIrrelevant() {
        assertEquals(Outcome.CONFIRMED, RollbackDecision.decide(Stage.BEFORE_BODY, RollbackResult.NOT_RUN));
        assertEquals(Outcome.CONFIRMED, RollbackDecision.decide(Stage.BEFORE_BODY, RollbackResult.FAILED));
    }

    // ---- rule 2: the ROLLBACK ends without error

    @Test
    void rule2_rollbackOk_isConfirmed_inTheBodyAndInTheCommit() {
        assertEquals(Outcome.CONFIRMED, RollbackDecision.decide(Stage.BODY, RollbackResult.OK));
        assertEquals(Outcome.CONFIRMED, RollbackDecision.decide(Stage.COMMIT, RollbackResult.OK));
    }

    // ---- rule 3: "no transaction is active"

    @Test
    void rule3_noTransaction_afterAFailureInTheBody_isConfirmed() {
        assertEquals(Outcome.CONFIRMED, RollbackDecision.decide(Stage.BODY, RollbackResult.NO_TRANSACTION));
    }

    @Test
    void rule3_noTransaction_afterAFailureInTheCommit_isUnconfirmed() {
        assertEquals(Outcome.UNCONFIRMED, RollbackDecision.decide(Stage.COMMIT, RollbackResult.NO_TRANSACTION));
    }

    // ---- rule 4: any other result

    @Test
    void rule4_anyOtherRollbackResult_isUnconfirmed_inTheBodyAndInTheCommit() {
        assertEquals(Outcome.UNCONFIRMED, RollbackDecision.decide(Stage.BODY, RollbackResult.FAILED));
        assertEquals(Outcome.UNCONFIRMED, RollbackDecision.decide(Stage.COMMIT, RollbackResult.FAILED));
    }

    // ---- rule 5: only the closing failed

    @Test
    void rule5_failureOnlyInTheClosing_isSuccessWithWarning() {
        assertEquals(Outcome.SUCCESS_WITH_WARNING, RollbackDecision.decide(Stage.CLOSE, RollbackResult.NOT_RUN));
    }

    /** The number reported for the diagnosis is the rule that {@code decide} applies. */
    @Test
    void ruleOf_numbersTheRuleThatDecides() {
        assertEquals(1, RollbackDecision.ruleOf(Stage.BEFORE_BODY, RollbackResult.NOT_RUN));
        assertEquals(2, RollbackDecision.ruleOf(Stage.BODY, RollbackResult.OK));
        assertEquals(2, RollbackDecision.ruleOf(Stage.COMMIT, RollbackResult.OK));
        assertEquals(3, RollbackDecision.ruleOf(Stage.BODY, RollbackResult.NO_TRANSACTION));
        assertEquals(3, RollbackDecision.ruleOf(Stage.COMMIT, RollbackResult.NO_TRANSACTION));
        assertEquals(4, RollbackDecision.ruleOf(Stage.BODY, RollbackResult.FAILED));
        assertEquals(4, RollbackDecision.ruleOf(Stage.COMMIT, RollbackResult.FAILED));
        assertEquals(5, RollbackDecision.ruleOf(Stage.CLOSE, RollbackResult.NOT_RUN));
    }

    // ---- the reading of the ROLLBACK answer

    @Test
    void resultOf_busyAndIoerr_areFailed() {
        assertEquals(RollbackResult.FAILED,
                RollbackDecision.resultOf(new SQLiteException("[SQLITE_BUSY] The database file is locked", SQLiteErrorCode.SQLITE_BUSY)));
        assertEquals(RollbackResult.FAILED,
                RollbackDecision.resultOf(new SQLiteException("[SQLITE_IOERR] disk I/O error", SQLiteErrorCode.SQLITE_IOERR)));
        assertEquals(RollbackResult.FAILED, RollbackDecision.resultOf(new SQLException((String) null)));
    }

    /** P1: the text the driver 3.51.2.0 gives is read as "no transaction is active". */
    @Test
    void resultOf_theRealMessageOfTheDriver_isNoTransaction() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("sem-transacao.db").toString().replace("\\", "/"));
             Statement st = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> st.execute("ROLLBACK"));
            System.out.println("U4B-RF01 mensagem real do ROLLBACK sem transação: " + e.getMessage());
            assertEquals(RollbackResult.NO_TRANSACTION, RollbackDecision.resultOf(e));
        }
    }
}

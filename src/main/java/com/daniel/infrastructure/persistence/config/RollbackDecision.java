package com.daniel.infrastructure.persistence.config;

import java.sql.SQLException;

/**
 * The rule that fixes the {@link RollbackState} of a failed ledger write (BRK-31 u4b, U4B:RF-01). It is a pure
 * function of where the failure happened and of what the ROLLBACK statement answered, so it is testable alone.
 */
final class RollbackDecision {

    /** Where {@link Database#inTransaction} failed. */
    enum Stage {
        /** Opening the connection or the BEGIN: the body never started. */
        BEFORE_BODY,
        /** The body of the write. */
        BODY,
        /** The commit. */
        COMMIT,
        /** Only the closing of the connection, after a commit without error. */
        CLOSE
    }

    /** What the ROLLBACK statement answered. */
    enum RollbackResult {
        /** The statement was not executed. */
        NOT_RUN,
        /** The statement ended without error. */
        OK,
        /** The statement answered that no transaction is active. */
        NO_TRANSACTION,
        /** Any other result (another error, including SQLITE_BUSY, or an unusable connection). */
        FAILED
    }

    /** What {@link #decide} concludes. */
    enum Outcome {
        CONFIRMED,
        UNCONFIRMED,
        /** Rule 5: the write is done; only the closing failed, which is logged. */
        SUCCESS_WITH_WARNING
    }

    /** The only reading of the message in this rule; SQLite answers the generic SQLITE_ERROR for it. */
    private static final String NO_TRANSACTION_TEXT = "no transaction is active";

    private RollbackDecision() {
    }

    /** The result of a ROLLBACK statement that threw {@code failure}. */
    static RollbackResult resultOf(SQLException failure) {
        String message = failure.getMessage();
        if (message != null && message.contains(NO_TRANSACTION_TEXT)) return RollbackResult.NO_TRANSACTION;
        return RollbackResult.FAILED;
    }

    /** The number (1 to 5) of the rule of U4B:RF-01 that {@link #decide} applies, for the log and the diagnosis. */
    static int ruleOf(Stage stage, RollbackResult rollback) {
        if (stage == Stage.CLOSE) return 5;
        if (stage == Stage.BEFORE_BODY) return 1;
        if (rollback == RollbackResult.OK) return 2;
        if (rollback == RollbackResult.NO_TRANSACTION) return 3;
        return 4;
    }

    /** The first rule that holds decides (U4B:RF-01). */
    static Outcome decide(Stage stage, RollbackResult rollback) {
        if (stage == Stage.CLOSE) return Outcome.SUCCESS_WITH_WARNING;
        if (stage == Stage.BEFORE_BODY) return Outcome.CONFIRMED;
        if (rollback == RollbackResult.OK) return Outcome.CONFIRMED;
        if (rollback == RollbackResult.NO_TRANSACTION && stage == Stage.BODY) return Outcome.CONFIRMED;
        if (rollback == RollbackResult.NO_TRANSACTION && stage == Stage.COMMIT) return Outcome.UNCONFIRMED;
        return Outcome.UNCONFIRMED;
    }
}

package com.daniel.infrastructure.persistence.config;

/**
 * Whether the undoing of a failed ledger write (a {@link Database#inTransaction} call) was confirmed
 * (BRK-31 u4b). Read from the failure with {@link Database#rollbackStateOf(Throwable)}; a failure that is
 * not from such a write has no state.
 */
public enum RollbackState {
    /** Nothing of the write remains: the screen may say "Nada foi gravado.". */
    CONFIRMED,
    /** The undoing could not be confirmed: the screen asks the user to check before repeating. */
    UNCONFIRMED
}

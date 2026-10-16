package com.daniel.core.domain.ledger;

/** Kinds of ledger entries. Values are persisted verbatim in portfolio_movement.kind. */
public enum MovementKind {
    /** Opening marker: absolute position/cost (investment) or absolute cash. Not an external flow. */
    OPENING,
    /** External money in: cash += amount; externalNetFlow += amount. */
    DEPOSIT,
    /** External money out: cash -= amount (cash >= 0); externalNetFlow -= amount. */
    WITHDRAWAL,
    /** Cash -> investment: cash -= amount; quantity/cost up. */
    BUY,
    /** Investment -> cash: quantity/cost down; cash += amount; realized gain on QUANTITY mode. */
    SELL,
    /** Explicit correction with classification and reason. Never auto-labelled as income. */
    ADJUSTMENT,
    /** Inverse of an earlier movement, linked by reversesId. No DELETE ever. */
    REVERSAL,
    /** Re-statement of absolute position/cost or cash with a reason; clears a pending reconciliation. */
    RECONCILIATION
}

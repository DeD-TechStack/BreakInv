package com.daniel.core.domain.ledger;

/** Materialized position derived from the ledger. quantity is 0 for VALUE mode. */
public record Position(long investmentTypeId, PositionMode mode, long quantity, long costCents, boolean reconciliationPending) {

    public static Position empty(long investmentTypeId) {
        return new Position(investmentTypeId, null, 0L, 0L, false);
    }

    public boolean isOpen() {
        return mode != null;
    }

    public Position with(long quantity, long costCents) {
        return new Position(investmentTypeId, mode, quantity, costCents, reconciliationPending);
    }
}

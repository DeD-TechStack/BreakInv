package com.daniel.core.domain.ledger;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Registration row as seen by the ledger. Legacy fields are the raw values of the old
 * investment_type columns (null preserved) and the count of legacy transactions rows; they
 * are inputs for {@code proposeOpening}, never applied silently.
 * {@code legacyNullQuantityCount} counts legacy BUY/SELL rows whose quantity is NULL (the old
 * UI wrote a total sell that way): their quantity is unknowable, so the sums exclude them and
 * the proposal must be flagged instead of inferring history.
 */
public record InvestmentLedgerInfo(
        long id,
        String name,
        PositionMode positionMode,
        Instant archivedAt,
        String note,
        Integer legacyQuantity,
        BigDecimal legacyPurchasePrice,
        BigDecimal legacyInvestedValue,
        int legacyTransactionCount,
        long legacyBuyQuantity,
        long legacySellQuantity,
        int legacyNullQuantityCount
) {
    /** Previous signature preserved for callers unaware of NULL-quantity rows (count 0). */
    public InvestmentLedgerInfo(long id, String name, PositionMode positionMode, Instant archivedAt, String note,
                                Integer legacyQuantity, BigDecimal legacyPurchasePrice, BigDecimal legacyInvestedValue,
                                int legacyTransactionCount, long legacyBuyQuantity, long legacySellQuantity) {
        this(id, name, positionMode, archivedAt, note, legacyQuantity, legacyPurchasePrice, legacyInvestedValue,
                legacyTransactionCount, legacyBuyQuantity, legacySellQuantity, 0);
    }

    public boolean archived() {
        return archivedAt != null;
    }

    public boolean hasLegacyData() {
        return legacyTransactionCount > 0 || legacyQuantity != null || legacyInvestedValue != null;
    }

    /** True when at least one legacy BUY/SELL has no quantity: the transaction balance is incomplete. */
    public boolean legacyQuantityIncomplete() {
        return legacyNullQuantityCount > 0;
    }
}

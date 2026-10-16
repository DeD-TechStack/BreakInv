package com.daniel.core.domain.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Persisted ledger row (append-only). {@code investmentTypeId == null} means the entry acts on
 * cash only. {@code unitPrice} keeps the exact decimal typed by the user; {@code amountCents}
 * is the settled value (HALF_UP once). Ordering is (date, id).
 */
public record Movement(
        long id,
        String intentKey,
        LocalDate date,
        MovementKind kind,
        Long investmentTypeId,
        PositionMode positionMode,
        Long quantity,
        BigDecimal unitPrice,
        long amountCents,
        String classification,
        String reason,
        String note,
        Long reversesId,
        boolean reconciliationFlag,
        String source,
        Instant createdAt
) {
    public boolean isCashOnly() {
        return investmentTypeId == null;
    }
}

package com.daniel.core.domain.entity;

import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.PositionMode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the unified statement: either a ledger movement ({@code legacy == false},
 * {@code movementId != null}) or a legacy {@code transactions} row ({@code legacy == true},
 * {@code legacyTransactionId != null}, {@code kind == null}). Ledger rows appear exactly once;
 * reversed originals stay visible and point to their reversal through {@code reversedById}.
 */
public record StatementEntry(
        LocalDate date,
        MovementKind kind,
        String legacyType,
        Long movementId,
        Long legacyTransactionId,
        Long investmentTypeId,
        String investmentName,
        PositionMode positionMode,
        Long quantity,
        BigDecimal unitPrice,
        long amountCents,
        String classification,
        String reason,
        String note,
        Long reversesId,
        Long reversedById,
        boolean reconciliationFlag,
        boolean legacy
) {
    public boolean reversed() {
        return reversedById != null;
    }

    public boolean cashOnly() {
        return investmentTypeId == null;
    }

    public boolean marker() {
        return kind == MovementKind.OPENING || kind == MovementKind.RECONCILIATION;
    }
}

package com.daniel.core.domain.ledger;

import java.time.LocalDate;

/**
 * Opening marker. For an investment: absolute quantity (QUANTITY mode) and cost. For cash
 * ({@code investmentTypeId == null}): absolute cash balance. {@code reconciliationFlag} marks the
 * values as uncertain (legacy data could not be reconstructed safely); while flagged, new
 * movements on that investment/cash are refused until {@code reconcile} confirms the state.
 */
public record OpeningRequest(
        String intentKey,
        LocalDate date,
        Long investmentTypeId,
        PositionMode positionMode,
        Long quantity,
        long costCents,
        boolean reconciliationFlag,
        String note
) {
    public OpeningRequest {
        if (intentKey == null || intentKey.isBlank()) throw new LedgerException("intentKey", "chave de intenção obrigatória");
        MovementRequest.requireDate(date);
        if (costCents < 0) throw new LedgerException("cost", "custo/caixa de abertura não pode ser negativo");
        if (investmentTypeId != null) {
            if (positionMode == null) throw new LedgerException("positionMode", "modo de posição obrigatório");
            if (positionMode == PositionMode.QUANTITY) {
                if (quantity == null || quantity < 0) throw new LedgerException("quantity", "quantidade de abertura deve ser >= 0");
                if (quantity == 0 && costCents != 0) throw new LedgerException("cost", "posição com quantidade zero não pode ter custo");
            } else if (quantity != null) {
                throw new LedgerException("quantity", "investimento por valor não tem quantidade");
            }
        } else if (positionMode != null || quantity != null) {
            throw new LedgerException("positionMode", "abertura de caixa não tem modo/quantidade");
        }
        note = MovementRequest.blankToNull(note);
    }

    /** Fresh registration: zero position, nothing invented. */
    public static OpeningRequest newInvestment(String key, LocalDate date, long investmentTypeId, PositionMode mode, String note) {
        return new OpeningRequest(key, date, investmentTypeId, mode, mode == PositionMode.QUANTITY ? 0L : null, 0L, false, note);
    }

    public static OpeningRequest existingPosition(String key, LocalDate date, long investmentTypeId, PositionMode mode,
                                                  Long quantity, long costCents, boolean reconciliationFlag, String note) {
        return new OpeningRequest(key, date, investmentTypeId, mode, quantity, costCents, reconciliationFlag, note);
    }

    public static OpeningRequest cash(String key, LocalDate date, long cashCents, boolean reconciliationFlag, String note) {
        return new OpeningRequest(key, date, null, null, null, cashCents, reconciliationFlag, note);
    }

    public String payloadHash() {
        return MovementRequest.hashFields("OPENING", date, investmentTypeId,
                positionMode, quantity, costCents, reconciliationFlag, note);
    }
}

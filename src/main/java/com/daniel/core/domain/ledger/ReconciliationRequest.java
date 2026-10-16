package com.daniel.core.domain.ledger;

import java.time.LocalDate;

/** Explicit re-statement of an investment position or of cash, with a mandatory reason. */
public record ReconciliationRequest(
        String intentKey,
        LocalDate date,
        Long investmentTypeId,
        Long quantity,
        long costCents,
        String reason,
        String note
) {
    public ReconciliationRequest {
        if (intentKey == null || intentKey.isBlank()) throw new LedgerException("intentKey", "chave de intenção obrigatória");
        MovementRequest.requireDate(date);
        if (reason == null || reason.isBlank()) throw new LedgerException("reason", "motivo da reconciliação obrigatório");
        if (costCents < 0) throw new LedgerException("cost", "valor não pode ser negativo");
        if (quantity != null && quantity < 0) throw new LedgerException("quantity", "quantidade não pode ser negativa");
        if (quantity != null && quantity == 0 && costCents != 0) throw new LedgerException("cost", "posição com quantidade zero não pode ter custo");
        reason = reason.trim();
        note = MovementRequest.blankToNull(note);
    }

    public static ReconciliationRequest position(String key, LocalDate date, long investmentTypeId, Long quantity, long costCents, String reason) {
        return new ReconciliationRequest(key, date, investmentTypeId, quantity, costCents, reason, null);
    }

    public static ReconciliationRequest cash(String key, LocalDate date, long cashCents, String reason) {
        return new ReconciliationRequest(key, date, null, null, cashCents, reason, null);
    }

    public String payloadHash() {
        return MovementRequest.hashFields("RECONCILIATION", date, investmentTypeId,
                quantity, costCents, reason, note);
    }
}

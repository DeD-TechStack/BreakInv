package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * State of the movement dialog, independent of JavaFX.
 *
 * <p>Idempotency: one intent key (UUID) represents one intention. The key is created when the
 * form opens and kept across failed attempts ("try again" after a transient error reuses it),
 * but any change to the content renews it, so a different movement is never confused with a
 * retry of the previous one. {@link #submitting()} tells the dialog to disable its inputs
 * while the worker is busy.
 */
public final class MovementForm {

    private final MovementKind kind;
    private final Long investmentTypeId;
    private final PositionMode mode;
    private final Long reversesId;

    private String intentKey = UUID.randomUUID().toString();
    private boolean submitting;

    private LocalDate date;
    private String quantityText = "";
    private String priceText = "";
    private String amountText = "";
    private boolean outflow;
    private AdjustmentClassification classification;
    private String reason = "";
    private String note = "";

    private MovementForm(MovementKind kind, Long investmentTypeId, PositionMode mode, Long reversesId) {
        this.kind = kind;
        this.investmentTypeId = investmentTypeId;
        this.mode = mode;
        this.reversesId = reversesId;
    }

    // ---- factories ----

    public static MovementForm deposit() { return new MovementForm(MovementKind.DEPOSIT, null, null, null); }
    public static MovementForm withdrawal() { return new MovementForm(MovementKind.WITHDRAWAL, null, null, null); }
    public static MovementForm buy(long investmentTypeId, PositionMode mode) { return new MovementForm(MovementKind.BUY, investmentTypeId, mode, null); }
    public static MovementForm sell(long investmentTypeId, PositionMode mode) { return new MovementForm(MovementKind.SELL, investmentTypeId, mode, null); }
    public static MovementForm adjustCash() { return new MovementForm(MovementKind.ADJUSTMENT, null, null, null); }
    public static MovementForm adjustPosition(long investmentTypeId, PositionMode mode) { return new MovementForm(MovementKind.ADJUSTMENT, investmentTypeId, mode, null); }
    public static MovementForm reversal(long reversesId) { return new MovementForm(MovementKind.REVERSAL, null, null, reversesId); }

    // ---- accessors ----

    public MovementKind kind() { return kind; }
    public Long investmentTypeId() { return investmentTypeId; }
    public PositionMode mode() { return mode; }
    public Long reversesId() { return reversesId; }
    public String intentKey() { return intentKey; }
    public boolean submitting() { return submitting; }
    public LocalDate date() { return date; }
    public boolean outflow() { return outflow; }

    public boolean usesQuantity() {
        return (kind == MovementKind.BUY || kind == MovementKind.SELL || kind == MovementKind.ADJUSTMENT)
                && investmentTypeId != null && mode == PositionMode.QUANTITY;
    }

    public boolean usesPrice() {
        return (kind == MovementKind.BUY || kind == MovementKind.SELL) && mode == PositionMode.QUANTITY;
    }

    public boolean usesAmount() {
        return kind == MovementKind.DEPOSIT || kind == MovementKind.WITHDRAWAL || kind == MovementKind.ADJUSTMENT
                || ((kind == MovementKind.BUY || kind == MovementKind.SELL) && mode == PositionMode.VALUE);
    }

    public boolean isAdjustment() { return kind == MovementKind.ADJUSTMENT; }
    public boolean isReversal() { return kind == MovementKind.REVERSAL; }

    // ---- mutators (every content change renews the intention) ----

    public void setDate(LocalDate date) { if (!java.util.Objects.equals(this.date, date)) { this.date = date; renew(); } }
    public void setQuantityText(String s) { if (!nz(s).equals(quantityText)) { quantityText = nz(s); renew(); } }
    public void setPriceText(String s) { if (!nz(s).equals(priceText)) { priceText = nz(s); renew(); } }
    public void setAmountText(String s) { if (!nz(s).equals(amountText)) { amountText = nz(s); renew(); } }
    public void setOutflow(boolean outflow) { if (this.outflow != outflow) { this.outflow = outflow; renew(); } }
    public void setClassification(AdjustmentClassification c) { if (classification != c) { classification = c; renew(); } }
    public void setReason(String s) { if (!nz(s).equals(reason)) { reason = nz(s); renew(); } }
    public void setNote(String s) { if (!nz(s).equals(note)) { note = nz(s); renew(); } }

    public void markSubmitting() { submitting = true; }
    /** The attempt did not settle; the same intention may be retried with the same key. */
    public void markFailed() { submitting = false; }
    /** The attempt settled: the write committed, the dialog may close (its own close is no longer refused). */
    public void markSaved() { submitting = false; }

    private void renew() {
        if (!submitting) intentKey = UUID.randomUUID().toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ---- build ----

    public MovementRequest build() {
        if (date == null) throw new LedgerException("date", "informe a data");
        String n = note.isBlank() ? null : note.trim();
        switch (kind) {
            case DEPOSIT: return MovementRequest.deposit(intentKey, date, positiveAmount(), n);
            case WITHDRAWAL: return MovementRequest.withdrawal(intentKey, date, positiveAmount(), n);
            case BUY:
            case SELL: {
                if (mode == PositionMode.QUANTITY) {
                    long qty = quantity();
                    BigDecimal price = price();
                    return kind == MovementKind.BUY
                            ? MovementRequest.buy(intentKey, date, investmentTypeId, qty, price, n)
                            : MovementRequest.sell(intentKey, date, investmentTypeId, qty, price, n);
                }
                long amount = positiveAmount();
                return kind == MovementKind.BUY
                        ? MovementRequest.buyValue(intentKey, date, investmentTypeId, amount, n)
                        : MovementRequest.sellValue(intentKey, date, investmentTypeId, amount, n);
            }
            case ADJUSTMENT: {
                if (classification == null) throw new LedgerException("classification", "escolha a classificação do ajuste");
                if (reason.isBlank()) throw new LedgerException("reason", "informe o motivo do ajuste");
                long amount = signed(optionalAmount());
                if (investmentTypeId == null) {
                    if (amount == 0) throw new LedgerException("amount", "ajuste de caixa sem valor");
                    return MovementRequest.adjustCash(intentKey, date, amount, classification, reason.trim(), n);
                }
                Long qty = null;
                if (mode == PositionMode.QUANTITY && !quantityText.isBlank()) qty = signed(quantity());
                if (amount == 0 && (qty == null || qty == 0)) throw new LedgerException("amount", "ajuste sem efeito");
                return MovementRequest.adjustPosition(intentKey, date, investmentTypeId, qty, amount, classification, reason.trim(), n);
            }
            case REVERSAL: {
                if (reason.isBlank()) throw new LedgerException("reason", "informe o motivo do estorno");
                return MovementRequest.reversal(intentKey, date, reversesId, reason.trim());
            }
            default: throw new LedgerException("kind", "tipo não suportado: " + kind);
        }
    }

    private long signed(long v) { return outflow ? -Math.abs(v) : Math.abs(v); }

    private long quantity() {
        String s = quantityText.trim();
        if (s.isEmpty()) throw new LedgerException("quantity", "informe a quantidade");
        if (!s.matches("\\d+")) throw new LedgerException("quantity", "quantidade deve ser um inteiro (sem frações)");
        long q;
        try { q = Long.parseLong(s); } catch (NumberFormatException e) { throw new LedgerException("quantity", "quantidade fora do intervalo"); }
        if (q <= 0) throw new LedgerException("quantity", "quantidade deve ser maior que zero");
        return q;
    }

    private BigDecimal price() {
        BigDecimal p = parseDecimal(priceText, "unitPrice", "informe o preço unitário");
        if (p.signum() <= 0) throw new LedgerException("unitPrice", "preço deve ser maior que zero");
        return p;
    }

    private long positiveAmount() {
        long a = optionalAmount();
        if (a <= 0) throw new LedgerException("amount", "valor deve ser maior que zero");
        return a;
    }

    private long optionalAmount() {
        if (amountText.isBlank()) return 0L;
        BigDecimal v = parseDecimal(amountText, "amount", "informe o valor");
        try {
            return v.setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new LedgerException("amount", "valor fora do intervalo suportado");
        }
    }

    /** Accepts "1.234,56", "1234.56", "R$ 20,005"; keeps every typed decimal place. */
    static BigDecimal parseDecimal(String text, String field, String emptyMessage) {
        String s = text == null ? "" : text.replace("R$", "").replace("\u00A0", "").replace(" ", "").trim();
        if (s.isEmpty()) throw new LedgerException(field, emptyMessage);
        if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
        else if (s.contains(",")) s = s.replace(",", ".");
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new LedgerException(field, "Digite só números, como 1.234,56."); // R2-19
        }
    }
}

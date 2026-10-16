package com.daniel.core.domain.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Intention to append one movement. {@code intentKey} is the idempotency key (UUID chosen by the
 * caller per intention); re-submitting the same key with the same payload returns the existing
 * row, a different payload is rejected.
 */
public record MovementRequest(
        String intentKey,
        LocalDate date,
        MovementKind kind,
        Long investmentTypeId,
        Long quantity,
        BigDecimal unitPrice,
        Long amountCents,
        AdjustmentClassification classification,
        String reason,
        String note,
        Long reversesId
) {
    /** Ledger dates are stored as ISO text and ordered lexically, so years must stay in 4 digits. */
    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 9999;

    public MovementRequest {
        if (intentKey == null || intentKey.isBlank()) throw new LedgerException("intentKey", "chave de intenção obrigatória");
        requireDate(date);
        if (kind == null) throw new LedgerException("kind", "tipo obrigatório");
        if (kind == MovementKind.OPENING || kind == MovementKind.RECONCILIATION) {
            throw new LedgerException("kind", "use registerOpening/reconcile para " + kind);
        }
        note = blankToNull(note);
        reason = blankToNull(reason);
    }

    // ---- factories ----

    public static MovementRequest deposit(String key, LocalDate date, long amountCents, String note) {
        return new MovementRequest(key, date, MovementKind.DEPOSIT, null, null, null, amountCents, null, null, note, null);
    }

    public static MovementRequest withdrawal(String key, LocalDate date, long amountCents, String note) {
        return new MovementRequest(key, date, MovementKind.WITHDRAWAL, null, null, null, amountCents, null, null, note, null);
    }

    /** QUANTITY mode: amount = quantity x unitPrice (rounded once). */
    public static MovementRequest buy(String key, LocalDate date, long investmentTypeId, long quantity, BigDecimal unitPrice, String note) {
        return new MovementRequest(key, date, MovementKind.BUY, investmentTypeId, quantity, unitPrice, null, null, null, note, null);
    }

    public static MovementRequest sell(String key, LocalDate date, long investmentTypeId, long quantity, BigDecimal unitPrice, String note) {
        return new MovementRequest(key, date, MovementKind.SELL, investmentTypeId, quantity, unitPrice, null, null, null, note, null);
    }

    /** VALUE mode: cash -> investment, cost += amount. */
    public static MovementRequest buyValue(String key, LocalDate date, long investmentTypeId, long amountCents, String note) {
        return new MovementRequest(key, date, MovementKind.BUY, investmentTypeId, null, null, amountCents, null, null, note, null);
    }

    /** VALUE mode: redeems exactly {@code amountCents} of cost to cash; no gain is inferred. */
    public static MovementRequest sellValue(String key, LocalDate date, long investmentTypeId, long amountCents, String note) {
        return new MovementRequest(key, date, MovementKind.SELL, investmentTypeId, null, null, amountCents, null, null, note, null);
    }

    /** Cash adjustment (signed amount). */
    public static MovementRequest adjustCash(String key, LocalDate date, long signedAmountCents,
                                             AdjustmentClassification classification, String reason, String note) {
        return new MovementRequest(key, date, MovementKind.ADJUSTMENT, null, null, null, signedAmountCents, classification, reason, note, null);
    }

    /** Position adjustment: signed quantity (may be null for VALUE mode) and signed cost delta. */
    public static MovementRequest adjustPosition(String key, LocalDate date, long investmentTypeId, Long signedQuantity,
                                                 long signedCostCents, AdjustmentClassification classification,
                                                 String reason, String note) {
        return new MovementRequest(key, date, MovementKind.ADJUSTMENT, investmentTypeId, signedQuantity, null, signedCostCents, classification, reason, note, null);
    }

    public static MovementRequest reversal(String key, LocalDate date, long reversesId, String reason) {
        return new MovementRequest(key, date, MovementKind.REVERSAL, null, null, null, null, null, reason, null, reversesId);
    }

    // ---- payload identity ----

    /** Canonical SHA-256 of every field except the key; used to detect key reuse with a different payload. */
    public String payloadHash() {
        return hashFields(date, kind, investmentTypeId, quantity,
                unitPrice == null ? null : unitPrice.stripTrailingZeros().toPlainString(),
                amountCents, classification, reason, note, reversesId);
    }

    /** Length prefixes and a distinct null marker prevent ambiguous field concatenation. */
    static String hashFields(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            if (field == null) canonical.append("-1:");
            else {
                String value = field.toString();
                canonical.append(value.length()).append(':').append(value);
            }
        }
        return sha256(canonical.toString());
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Shared by every request type: non-null date inside the lexically sortable ISO range. */
    static void requireDate(LocalDate date) {
        if (date == null) throw new LedgerException("date", "data obrigatória");
        if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
            throw new LedgerException("date", "data fora do intervalo suportado (" + MIN_YEAR + "–" + MAX_YEAR + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MovementRequest r && Objects.equals(intentKey, r.intentKey) && payloadHash().equals(r.payloadHash());
    }

    @Override
    public int hashCode() {
        return Objects.hash(intentKey, payloadHash());
    }
}

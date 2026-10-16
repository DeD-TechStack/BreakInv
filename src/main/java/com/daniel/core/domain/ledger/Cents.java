package com.daniel.core.domain.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Checked decimal -> cents conversions (HALF_UP, scale 2, overflow rejected, null preserved). */
public final class Cents {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Cents() {
    }

    /** Monetary decimal (BRL) to cents. Returns null for null input. */
    public static Long fromDecimal(BigDecimal value, String field) {
        if (value == null) return null;
        try {
            return value.setScale(2, RoundingMode.HALF_UP).multiply(HUNDRED).longValueExact();
        } catch (ArithmeticException e) {
            throw new LedgerException(field, field + ": valor fora do intervalo suportado");
        }
    }

    /** quantity x unit price, rounded ONCE at the end (the price itself keeps its scale). */
    public static long total(long quantity, BigDecimal unitPrice, String field) {
        if (unitPrice == null) throw new LedgerException(field, field + ": preço obrigatório");
        if (unitPrice.signum() <= 0) throw new LedgerException(field, field + ": preço deve ser maior que zero");
        Long cents = fromDecimal(unitPrice.multiply(BigDecimal.valueOf(quantity)), field);
        return cents == null ? 0L : cents;
    }

    public static BigDecimal toDecimal(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /** Proportional share cost * part / whole, HALF_UP. */
    public static long proportion(long cost, long part, long whole) {
        if (whole == 0) return 0L;
        return BigDecimal.valueOf(cost).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact();
    }
}

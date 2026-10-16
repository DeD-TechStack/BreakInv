package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Form state behind MovementDialog: one intent key per intention, recoverable on failure. */
class MovementFormTest {

    static final LocalDate D = LocalDate.of(2026, 1, 10);

    @Test
    void buyQuantity_buildsRequestWithExactDecimalPriceAndIntegerQuantity() {
        MovementForm f = MovementForm.buy(7L, PositionMode.QUANTITY);
        f.setDate(D);
        f.setQuantityText("10");
        f.setPriceText("20,005");
        f.setNote("  ");

        MovementRequest r = f.build();
        assertEquals(MovementKind.BUY, r.kind());
        assertEquals(7L, r.investmentTypeId());
        assertEquals(10L, r.quantity());
        assertEquals(new BigDecimal("20.005"), r.unitPrice(), "preço não arredondado a 2 casas");
        assertNull(r.amountCents());
        assertNull(r.note());
        assertEquals(f.intentKey(), r.intentKey());
    }

    @Test
    void fractionalQuantity_rejectedNamingField() {
        MovementForm f = MovementForm.sell(7L, PositionMode.QUANTITY);
        f.setDate(D);
        f.setQuantityText("1,5");
        f.setPriceText("10");
        LedgerException ex = assertThrows(LedgerException.class, f::build);
        assertEquals("quantity", ex.field());
    }

    @Test
    void valueMode_usesAmountOnly() {
        MovementForm f = MovementForm.sell(3L, PositionMode.VALUE);
        f.setDate(D);
        f.setAmountText("R$ 1.234,56");
        MovementRequest r = f.build();
        assertEquals(123_456L, r.amountCents());
        assertNull(r.quantity());
        assertNull(r.unitPrice());
    }

    @Test
    void deposit_requiresPositiveAmount() {
        MovementForm f = MovementForm.deposit();
        f.setDate(D);
        f.setAmountText("0");
        LedgerException ex = assertThrows(LedgerException.class, f::build);
        assertEquals("amount", ex.field());
    }

    @Test
    void adjustment_requiresClassificationAndReason_signedAmount() {
        MovementForm f = MovementForm.adjustCash();
        f.setDate(D);
        f.setAmountText("5,00");
        f.setOutflow(true);
        assertEquals("classification", assertThrows(LedgerException.class, f::build).field());
        f.setClassification(AdjustmentClassification.EXPENSE);
        assertEquals("reason", assertThrows(LedgerException.class, f::build).field());
        f.setReason("tarifa");
        MovementRequest r = f.build();
        assertEquals(-500L, r.amountCents());
        assertEquals(AdjustmentClassification.EXPENSE, r.classification());
    }

    @Test
    void positionAdjustment_signedQuantityAndCost() {
        MovementForm f = MovementForm.adjustPosition(9L, PositionMode.QUANTITY);
        f.setDate(D);
        f.setQuantityText("2");
        f.setAmountText("10");
        f.setOutflow(true);
        f.setClassification(AdjustmentClassification.CORRECTION);
        f.setReason("erro de digitação");
        MovementRequest r = f.build();
        assertEquals(-2L, r.quantity());
        assertEquals(-1_000L, r.amountCents());
        assertEquals(9L, r.investmentTypeId());
    }

    @Test
    void reversal_requiresReason_andLinksOriginal() {
        MovementForm f = MovementForm.reversal(42L);
        f.setDate(D);
        assertEquals("reason", assertThrows(LedgerException.class, f::build).field());
        f.setReason("duplicado");
        MovementRequest r = f.build();
        assertEquals(MovementKind.REVERSAL, r.kind());
        assertEquals(42L, r.reversesId());
    }

    @Test
    void intentKey_isStableAcrossFailedAttempt_andRenewsWhenContentChanges() {
        MovementForm f = MovementForm.deposit();
        f.setDate(D);
        f.setAmountText("100");
        String k1 = f.intentKey();
        MovementRequest first = f.build();
        f.markFailed();
        assertEquals(k1, f.intentKey(), "retentativa do mesmo conteúdo reutiliza a chave");
        assertEquals(first.payloadHash(), f.build().payloadHash());

        f.setAmountText("200");
        assertNotEquals(k1, f.intentKey(), "conteúdo novo é nova intenção");
    }

    @Test
    void submitting_disablesUntilSettled() {
        MovementForm f = MovementForm.deposit();
        assertFalse(f.submitting());
        f.markSubmitting();
        assertTrue(f.submitting());
        f.markFailed();
        assertFalse(f.submitting());
    }

    @Test
    void missingDate_rejected() {
        MovementForm f = MovementForm.deposit();
        f.setAmountText("1");
        assertEquals("date", assertThrows(LedgerException.class, f::build).field());
    }
}

package com.daniel.presentation.view.components;

import com.daniel.core.util.Money;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.InvestmentTypeDialog.InvestmentTypeData;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The editable currency filter lets "1,2,3", "-" or "R" through; those texts must be caught at
 * the dialog's validation boundary (a field error, not a NumberFormatException from the result
 * converter) and the blur reformatting must leave an unparsable text untouched instead of
 * throwing on the FX thread.
 */
class InvestmentTypeDialogMoneyValidationTest {

    static InvestmentTypeData existing() {
        return new InvestmentTypeData("CDB", "RENDA_FIXA", "ALTA", LocalDate.of(2026, 1, 1), new BigDecimal("12.5"),
                new BigDecimal("1000.00"), "PREFIXADO", null, null, null, null, null);
    }

    @Test
    void validation_namesInvalidMoneyTexts_insteadOfThrowingInTheResultConverter() {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypeDialog d = new InvestmentTypeDialog("Editar", existing());
            for (String bad : List.of("1,2,3", "-", "R")) {
                d.investedValueField().setText(bad);
                List<String> errors = d.validationErrors();
                assertFalse(errors.isEmpty(), "'" + bad + "' must be a validation error");
                assertTrue(errors.stream().anyMatch(e -> e.contains("Valor")), bad + " -> " + errors);
                assertEquals(bad, d.investedValueField().getText(), "the text being edited is preserved");
            }
            d.investedValueField().setText("1.234,56");
            assertTrue(d.validationErrors().isEmpty(), d.validationErrors().toString());
            assertEquals(0, new BigDecimal("1234.56").compareTo(d.buildResult().investedValue()));
        });
    }

    @Test
    void validation_purchasePriceInvalid_isReportedToo() {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypeDialog d = new InvestmentTypeDialog("Editar", existing());
            d.purchasePriceField().setText("-");
            List<String> errors = d.validationErrors();
            assertTrue(errors.stream().anyMatch(e -> e.contains("Preço")), errors.toString());
        });
    }

    @Test
    void blurReformat_keepsUnparsableTextAndFormatsValidOnes() {
        assertEquals("1,2,3", Money.reformatOnBlur("1,2,3"));
        assertEquals("-", Money.reformatOnBlur("-"));
        assertEquals("R", Money.reformatOnBlur("R"));
        assertEquals("   ", Money.reformatOnBlur("   "));
        assertEquals(Money.centsToCurrencyText(1250L), Money.reformatOnBlur("12,5"));
        assertEquals(Money.centsToCurrencyText(123456L), Money.reformatOnBlur("1.234,56"));
    }
}

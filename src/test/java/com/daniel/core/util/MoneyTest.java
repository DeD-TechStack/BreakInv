package com.daniel.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    // ===== textToCentsOrZero =====

    @Test
    void null_returnsZero() {
        assertEquals(0L, Money.textToCentsOrZero(null));
    }

    @Test
    void emptyString_returnsZero() {
        assertEquals(0L, Money.textToCentsOrZero(""));
    }

    @Test
    void blankString_returnsZero() {
        assertEquals(0L, Money.textToCentsOrZero("   "));
    }

    @Test
    void plainInteger_parsedCorrectly() {
        // "1000" → 100000 cents (R$ 1000,00)
        assertEquals(100000L, Money.textToCentsOrZero("1000"));
    }

    @Test
    void brazilianFormat_dotGrouping_commaDecimal() {
        // "1.250,00" → 125000 cents (R$ 1250,00)
        assertEquals(125000L, Money.textToCentsOrZero("1.250,00"));
    }

    @Test
    void brazilianFormat_commaDecimalOnly() {
        // "12,50" → 1250 cents
        assertEquals(1250L, Money.textToCentsOrZero("12,50"));
    }

    @Test
    void withCurrencySymbol_parsed() {
        // "R$ 50,00" → 5000 cents
        assertEquals(5000L, Money.textToCentsOrZero("R$ 50,00"));
    }

    @Test
    void negativeValue_parsed() {
        // "-12,50" → -1250 cents
        assertEquals(-1250L, Money.textToCentsOrZero("-12,50"));
    }

    @Test
    void negativeBrazilianFormat() {
        // "-1.250,00" → -125000 cents
        assertEquals(-125000L, Money.textToCentsOrZero("-1.250,00"));
    }

    @Test
    void decimalWithDotOnly() {
        // "12.50" — only dot, no comma → treated as decimal point
        assertEquals(1250L, Money.textToCentsOrZero("12.50"));
    }

    @Test
    void zeroValue() {
        assertEquals(0L, Money.textToCentsOrZero("0"));
    }

    @Test
    void zeroCommaZero() {
        assertEquals(0L, Money.textToCentsOrZero("0,00"));
    }

    // ===== textToCentsSafe =====

    @Test
    void safe_validInput_delegatesToOrZero() {
        assertEquals(1250L, Money.textToCentsSafe("12,50"));
    }

    // ===== centsToCurrencyText / centsToText =====

    @Test
    void centsToCurrencyText_roundTrip() {
        // Format then parse back — must recover original cents
        long original = 125050L;
        String formatted = Money.centsToCurrencyText(original);
        long recovered = Money.textToCentsOrZero(formatted);
        assertEquals(original, recovered);
    }

    @Test
    void centsToCurrencyText_zero_roundTrip() {
        String formatted = Money.centsToCurrencyText(0L);
        long recovered = Money.textToCentsOrZero(formatted);
        assertEquals(0L, recovered);
    }

    @Test
    void centsToCurrencyText_containsValue() {
        // R$ 12,50 — must contain "12" and "50"
        String result = Money.centsToCurrencyText(1250L);
        assertTrue(result.contains("12"), "Expected '12' in: " + result);
        assertTrue(result.contains("50"), "Expected '50' in: " + result);
    }

    @Test
    void centsToText_sameAsCentsToCurrencyText() {
        long cents = 99900L;
        assertEquals(Money.centsToCurrencyText(cents), Money.centsToText(cents));
    }

    @Test
    void centsToCurrencyText_largeAmount_roundTrip() {
        long original = 1_000_000_00L; // R$ 1.000.000,00
        String formatted = Money.centsToCurrencyText(original);
        long recovered = Money.textToCentsOrZero(formatted);
        assertEquals(original, recovered);
    }

    @Test
    void negativeCents_roundTrip() {
        long original = -125050L;
        String formatted = Money.centsToCurrencyText(original);
        long recovered = Money.textToCentsOrZero(formatted);
        assertEquals(original, recovered);
    }

    // ===== Edge cases — textToCentsOrZero =====

    @Test
    void oneCent_minimumAmount() {
        // "0,01" → 1 cent
        assertEquals(1L, Money.textToCentsOrZero("0,01"));
    }

    @Test
    void largeAmountMultipleThousandGroups() {
        // "1.000.000,50" → multiple dot groups + comma decimal
        assertEquals(100_000_050L, Money.textToCentsOrZero("1.000.000,50"));
    }

    @Test
    void currencySymbolOnly_returnsZero() {
        // "R$" after strip → blank → 0
        assertEquals(0L, Money.textToCentsOrZero("R$"));
    }

    @Test
    void currencySymbolWithNbsp_returnsZero() {
        // "R$\u00A0" after strip + NBSP removal → blank → 0
        assertEquals(0L, Money.textToCentsOrZero("R$\u00A0"));
    }

    @Test
    void leadingAndTrailingSpaces_stripped() {
        // "  12,50  " → 1250 cents
        assertEquals(1250L, Money.textToCentsOrZero("  12,50  "));
    }

    @Test
    void negativeWithCurrencySymbol_parsed() {
        // "R$ -12,50" → should produce -1250 cents
        // Negative sign comes AFTER R$ is stripped
        assertEquals(-1250L, Money.textToCentsOrZero("R$ -12,50"));
    }

    @Test
    void wholeNumberFiveDigits() {
        // "99999" → 9999900 cents
        assertEquals(9_999_900L, Money.textToCentsOrZero("99999"));
    }

    @Test
    void decimalDotFormat_threeDigits() {
        // "100.50" — dot-only (no comma) → treated as decimal
        assertEquals(10050L, Money.textToCentsOrZero("100.50"));
    }

    // ===== textToCentsSafe — error path =====

    @Test
    void safe_invalidInput_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.textToCentsSafe("abc"));
    }

    @Test
    void safe_alphabeticMixed_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.textToCentsSafe("12abc"));
    }

    // ===== centsToCurrencyText — formatting checks =====

    @Test
    void centsToCurrencyText_negativeAmount_containsDigits() {
        String result = Money.centsToCurrencyText(-1250L);
        assertTrue(result.contains("12"), "Expected '12' in: " + result);
        assertTrue(result.contains("50"), "Expected '50' in: " + result);
    }

    @Test
    void centsToCurrencyText_oneCent_roundTrip() {
        long original = 1L;
        String formatted = Money.centsToCurrencyText(original);
        long recovered = Money.textToCentsOrZero(formatted);
        assertEquals(original, recovered);
    }

    @Test
    void centsToCurrencyText_largePrecise_roundTrip() {
        long original = 9_999_999_99L; // R$ 99.999.999,99
        String formatted = Money.centsToCurrencyText(original);
        long recovered = Money.textToCentsOrZero(formatted);
        assertEquals(original, recovered);
    }
}

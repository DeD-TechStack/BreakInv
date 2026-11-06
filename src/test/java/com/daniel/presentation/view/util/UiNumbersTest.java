package com.daniel.presentation.view.util;

import com.daniel.core.util.MoneyFormat;
import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.control.Label;
import javafx.util.StringConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1, V1:RF-01, RF-02, RF-05 items 1 and 5: the one formatter of the screens. Every case runs with the JVM in
 * en-US, the locale that used to leak into the text ("97.7%", "R$ 20.00", "22,500,000").
 */
class UiNumbersTest {

    private static final String NBSP = " ";

    private Locale previous;

    @BeforeEach
    void english() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previous);
    }

    // ── RF-01.1 money ──────────────────────────────────────────────────────

    @Test
    void money_isBrlWithThousandsDotAndCommaDecimals_andTheNoBreakSpaceOfMoneyFormat() {
        assertEquals("R$" + NBSP + "1.234,50", UiNumbers.money(1234.5));
        assertEquals("R$" + NBSP + "1.000,00", UiNumbers.money(1000.0));
        assertEquals("R$" + NBSP + "20,00", UiNumbers.money(20.0));
        assertEquals("R$" + NBSP + "20.000.000,00", UiNumbers.money(20_000_000.0));
        assertEquals("R$" + NBSP + "1.234,56", UiNumbers.moneyExact(new BigDecimal("1234.56")));
        assertEquals("R$" + NBSP + "12,50", UiNumbers.moneyExact(new BigDecimal("12.5")));
    }

    @Test
    void money_writesTheSameTextAsMoneyFormatForTheSameAmount() {
        for (long cents : new long[]{0, 5, 99, 100, 123_456, 100_000, 123_456_789, 2_169_548_750L}) {
            assertEquals(MoneyFormat.brl(cents), UiNumbers.money(cents / 100.0), cents + " cents");
            assertEquals(MoneyFormat.brl(cents), UiNumbers.moneyExact(BigDecimal.valueOf(cents, 2)), cents + " cents");
            assertEquals(MoneyFormat.brl(cents), UiNumbers.moneyCents(cents), cents + " cents");
        }
    }

    @Test
    void money_roundsHalfUpOnTheDecimalAsWritten() {
        assertEquals("R$" + NBSP + "0,13", UiNumbers.money(0.125));
        assertEquals("R$" + NBSP + "1,01", UiNumbers.money(1.005));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.money(0.004));
        assertEquals("R$" + NBSP + "12,35", UiNumbers.moneyExact(new BigDecimal("12.345")));
    }

    @Test
    void money_aNegativeKeepsItsSignAfterTheSymbol_andAShownZeroHasNoSign() {
        assertEquals("R$" + NBSP + "-5,00", UiNumbers.money(-5.0));
        assertEquals("R$" + NBSP + "-1.234,56", UiNumbers.money(-1234.56));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.money(-0.004));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.money(-0.0));
    }

    // ── RF-02 sign ─────────────────────────────────────────────────────────

    @Test
    void signedMoney_hasAPlusOnlyForAGain_andNoSignAtZero() {
        assertEquals("+ R$" + NBSP + "10,00", UiNumbers.signedMoney(10.0));
        assertEquals("- R$" + NBSP + "10,00", UiNumbers.signedMoney(-10.0));
        assertEquals("+ R$" + NBSP + "1.234,50", UiNumbers.signedMoney(1234.5));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.signedMoney(0.0));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.signedMoney(0.004));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.signedMoney(-0.004));
    }

    @Test
    void moneyDelta_putsTheSignAfterTheSymbol_andNoSignAtZero() {
        assertEquals("R$" + NBSP + "+0,45", UiNumbers.moneyDelta(0.45));
        assertEquals("R$" + NBSP + "-0,45", UiNumbers.moneyDelta(-0.45));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.moneyDelta(0.0));
        assertEquals("R$" + NBSP + "0,00", UiNumbers.moneyDelta(-0.004));
    }

    // ── RF-01.2 percent, RF-02 ─────────────────────────────────────────────

    @Test
    void pct_hasTheDecimalsOfThePoint_commaDecimal_thousandsDot_andTheSignGluedPercent() {
        assertEquals("3,22%", UiNumbers.pct(3.22, 2));
        assertEquals("97,7%", UiNumbers.pct(97.7, 1));
        assertEquals("-15,0%", UiNumbers.pct(-15.0, 1));
        assertEquals("1.250,0%", UiNumbers.pct(1250.0, 1));
        assertEquals("1.250,00%", UiNumbers.pct(1250.0, 2));
        assertEquals("8,50%", UiNumbers.pct2(8.5));
        assertEquals("13,50%", UiNumbers.pctExact(new BigDecimal("13.5"), 2));
        assertEquals("13,50% a.a.", UiNumbers.ratePerYear(new BigDecimal("13.5")));
    }

    @Test
    void pct_aShownZeroHasNoSign_whateverTheRawSign() {
        assertEquals("0,00%", UiNumbers.pct(0.004, 2));
        assertEquals("0,00%", UiNumbers.pct(-0.004, 2));
        assertEquals("0,00%", UiNumbers.pct(0.0, 2));
        assertEquals("0,0%", UiNumbers.pct(-0.04, 1));
    }

    @Test
    void signedPct_hasAPlusOnlyForAGain_andRoundsHalfUpOnTheDecimalAsWritten() {
        assertEquals("+0,85%", UiNumbers.signedPct(0.85, 2));
        assertEquals("-0,85%", UiNumbers.signedPct(-0.85, 2));
        assertEquals("0,00%", UiNumbers.signedPct(0.004, 2));
        assertEquals("0,00%", UiNumbers.signedPct(-0.004, 2));
        assertEquals("+0,01%", UiNumbers.signedPct(0.005, 2));
        assertEquals("+0,13%", UiNumbers.signedPct(0.125, 2));
        assertEquals("-0,01%", UiNumbers.signedPct(-0.005, 2));
        assertEquals("0,00%", UiNumbers.signedPct(0.0, 2));
        assertEquals("+1.250,00%", UiNumbers.signedPct2(1250.0));
    }

    // ── RF-01.3 quantities ─────────────────────────────────────────────────

    @Test
    void integer_hasTheThousandsDot() {
        assertEquals("1.500", UiNumbers.integer(1500));
        assertEquals("999", UiNumbers.integer(999));
        assertEquals("1.000.000", UiNumbers.integer(1_000_000));
        assertEquals("0", UiNumbers.integer(0));
        assertEquals("-1.500", UiNumbers.integer(-1500));
    }

    @Test
    void volume_staysAbbreviated_withACommaAndAThousandsDotFromABillion() {
        assertEquals("12,3K", UiNumbers.volume(12_340));
        assertEquals("12,3M", UiNumbers.volume(12_345_678));
        assertEquals("1.234,5M", UiNumbers.volume(1_234_500_000L));
        assertEquals("999", UiNumbers.volume(999));
        assertEquals("1,0K", UiNumbers.volume(1_000));
        assertEquals(UiNumbers.DASH, UiNumbers.volume(0));
    }

    // ── RF-01.5 dates ──────────────────────────────────────────────────────

    @Test
    void isoDate_becomesDayMonthYear_anythingElseStaysAsItIs_nullIsEmpty() {
        assertEquals("05/11/2026", UiNumbers.isoDate("2026-11-05"));
        assertEquals("29/02/2028", UiNumbers.isoDate("2028-02-29"));
        assertEquals("estimativa padrão", UiNumbers.isoDate("estimativa padrão"));
        assertEquals("2026-02-31", UiNumbers.isoDate("2026-02-31"));
        assertEquals("05/11/2026", UiNumbers.isoDate("05/11/2026"));
        assertEquals("", UiNumbers.isoDate(""));
        assertEquals("", UiNumbers.isoDate(null));
    }

    // ── RF-05.5 invalid ────────────────────────────────────────────────────

    @Test
    void invalidFigures_showADash_inEveryFormat() {
        for (Double invalid : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            String which = String.valueOf(invalid);
            assertEquals("—", UiNumbers.money(invalid), which);
            assertEquals("—", UiNumbers.signedMoney(invalid), which);
            assertEquals("—", UiNumbers.moneyDelta(invalid), which);
            assertEquals("—", UiNumbers.pct(invalid, 2), which);
            assertEquals("—", UiNumbers.pct2(invalid), which);
            assertEquals("—", UiNumbers.signedPct(invalid, 2), which);
            assertEquals("—", UiNumbers.signedPct2(invalid), which);
            assertEquals(UiNumbers.MUTED, UiNumbers.tone(invalid, 2), which);
        }
        assertEquals("—", UiNumbers.moneyExact(null));
        assertEquals("—", UiNumbers.pctExact(null, 2));
        assertEquals("—", UiNumbers.ratePerYear(null));
        StringConverter<Number> axis = UiNumbers.axisConverter();
        assertEquals("—", axis.toString(null));
        assertEquals("—", axis.toString(Double.NaN));
        assertEquals("—", axis.toString(Double.POSITIVE_INFINITY));
    }

    // ── RF-03 tone by the shown value ──────────────────────────────────────

    @Test
    void tone_followsTheShownValue_notTheRawOne() {
        assertEquals("pos", UiNumbers.tone(3.22, 2));
        assertEquals("neg", UiNumbers.tone(-3.22, 2));
        assertEquals("muted", UiNumbers.tone(0.0, 2));
        assertEquals("muted", UiNumbers.tone(0.004, 2));
        assertEquals("muted", UiNumbers.tone(-0.004, 2));
        assertEquals("pos", UiNumbers.tone(0.005, 2));
        assertEquals("neg", UiNumbers.tone(-0.005, 2));
        assertEquals("muted", UiNumbers.tone(0.04, 1));
        assertEquals("pos", UiNumbers.tone(0.05, 1));
        assertEquals("pos", UiNumbers.toneOfCents(1));
        assertEquals("neg", UiNumbers.toneOfCents(-1));
        assertEquals("muted", UiNumbers.toneOfCents(0));
    }

    @Test
    void applyTone_leavesExactlyOneToneClass_andNothingElseChanges() {
        FxTestSupport.runAndWait(() -> {
            Label label = new Label("x");
            label.getStyleClass().addAll("metric-lg", "pos");
            UiNumbers.applyTone(label, UiNumbers.NEG);
            assertEquals(List.of("label", "metric-lg", "neg"), label.getStyleClass());
            UiNumbers.applyTone(label, UiNumbers.MUTED);
            assertEquals(List.of("label", "metric-lg", "muted"), label.getStyleClass());
            UiNumbers.applyTone(label, UiNumbers.MUTED);
            assertEquals(List.of("label", "metric-lg", "muted"), label.getStyleClass());
            UiNumbers.applyTone(label, null);
            assertEquals(List.of("label", "metric-lg"), label.getStyleClass());
        });
    }

    // ── RF-01.6 axis ───────────────────────────────────────────────────────

    @Test
    void axisConverter_hasThousandsDot_commaDecimal_upToTwoPlaces_andNoUnit() {
        StringConverter<Number> axis = UiNumbers.axisConverter();
        assertNotNull(axis);
        assertEquals("22.500.000", axis.toString(22_500_000));
        assertEquals("1.250", axis.toString(1250));
        assertEquals("3,5", axis.toString(3.5));
        assertEquals("0,25", axis.toString(0.25));
        assertEquals("0,5", axis.toString(0.5));
        assertEquals("0,75", axis.toString(0.75));
        assertEquals("3", axis.toString(3.0));
        assertEquals("2,25", axis.toString(2.25));
        assertEquals("2,26", axis.toString(2.256));
        assertEquals("0", axis.toString(0));
        assertEquals("0", axis.toString(-0.0));
        assertEquals("-1.250", axis.toString(-1250));
        assertEquals("-3,5", axis.toString(-3.5));
        assertFalse(axis.toString(1250).contains("R$"));
        assertFalse(axis.toString(3.5).contains("%"));
    }

    @Test
    void axisConverter_readsBackWhatItWrites_andRefusesWhatIsNotANumber() {
        StringConverter<Number> axis = UiNumbers.axisConverter();
        assertEquals(22_500_000.0, axis.fromString("22.500.000").doubleValue(), 0);
        assertEquals(3.5, axis.fromString("3,5").doubleValue(), 0);
        assertNull(axis.fromString(""));
        assertNull(axis.fromString(null));
        assertNull(axis.fromString("abc"));
    }

    // ── the default locale is never read ───────────────────────────────────

    @Test
    void everyFormat_isTheSameWhateverTheDefaultLocale() {
        for (Locale locale : new Locale[]{Locale.US, Locale.GERMANY, Locale.forLanguageTag("pt-BR"), Locale.FRANCE}) {
            Locale.setDefault(locale);
            String where = locale.toLanguageTag();
            assertEquals("R$" + NBSP + "1.234,56", UiNumbers.money(1234.56), where);
            assertEquals("1.234,6%", UiNumbers.pct(1234.56, 1), where);
            assertEquals("+3,22%", UiNumbers.signedPct(3.22, 2), where);
            assertEquals("1.500", UiNumbers.integer(1500), where);
            assertEquals("12,3M", UiNumbers.volume(12_345_678), where);
            assertEquals("22.500.000", UiNumbers.axisConverter().toString(22_500_000), where);
            assertTrue(UiNumbers.signedMoney(10.0).startsWith("+ R$"), where);
        }
    }
}

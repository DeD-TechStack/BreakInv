package com.daniel.presentation.view.util;

import com.daniel.core.util.MoneyFormat;
import javafx.scene.Node;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Every number the screens show, in pt-BR whatever the JVM's default locale is: {@code R$ 1.234,56}, {@code 3,22%},
 * {@code 22.500.000}, {@code 12,3K}, {@code 05/11/2026}. One place, used by every page (the copies that Ranking and
 * Análise de ativo kept of {@code money}, {@code signedPct} and {@code volume} are gone).
 *
 * <p><b>Shown value.</b> A figure is rounded to the places its text shows, HALF_UP on the decimal as written
 * ({@code BigDecimal.valueOf(double)}): {@code 0,125} shows {@code 0,13}, {@code -0,005} shows {@code -0,01},
 * {@code 0,004} shows {@code 0,00}. The sign and the {@linkplain #tone tone} follow that shown value, so a figure that
 * reads zero is written without a sign and is neutral, never a green or a red {@code 0,00%}.</p>
 *
 * <p><b>Invalid</b> ({@code null}, NaN, infinite) shows {@value #DASH} and the {@value #MUTED} tone, like an unknown value.</p>
 *
 * <p>Formats are built per call: {@link java.text.NumberFormat} is not thread safe, and nothing here caches the locale
 * (the default of the JVM is never read).</p>
 */
public final class UiNumbers {

    /** What an unknown or invalid figure shows. */
    public static final String DASH = "—";
    /** Tone classes: a gain, a loss, and zero / unknown. */
    public static final String POS = "pos";
    public static final String NEG = "neg";
    public static final String MUTED = "muted";

    private static final List<String> TONES = List.of(POS, NEG, MUTED);
    /** "R$" and a no-break space, as {@link MoneyFormat} writes it. */
    private static final String REAIS = "R$ ";

    private UiNumbers() {}

    /** The one locale of every number on screen. */
    private static Locale ptBr() { return Locale.forLanguageTag("pt-BR"); }

    private static String grouped(BigDecimal value, int minFraction, int maxFraction) {
        NumberFormat format = NumberFormat.getNumberInstance(ptBr());
        format.setGroupingUsed(true);
        format.setRoundingMode(RoundingMode.HALF_UP);
        format.setMinimumFractionDigits(minFraction);
        format.setMaximumFractionDigits(maxFraction);
        return format.format(value);
    }

    /** The value rounded to {@code decimals} places (HALF_UP on the decimal as written); {@code null} when invalid. */
    private static BigDecimal shown(Double value, int decimals) {
        if (value == null || value.isNaN() || value.isInfinite()) return null;
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP);
    }

    // ── Money ──────────────────────────────────────────────────────────────

    /** {@code R$ 1.234,56}; a negative keeps the sign after the symbol ({@code R$ -5,00}); invalid: {@value #DASH}. */
    public static String money(Double reais) {
        BigDecimal value = shown(reais, 2);
        return value == null ? DASH : REAIS + (value.signum() < 0 ? "-" : "") + grouped(value.abs(), 2, 2);
    }

    /** Same as {@link #money(Double)} for an exact amount (a unit price, a cost). */
    public static String moneyExact(BigDecimal reais) {
        if (reais == null) return DASH;
        BigDecimal value = reais.setScale(2, RoundingMode.HALF_UP);
        return REAIS + (value.signum() < 0 ? "-" : "") + grouped(value.abs(), 2, 2);
    }

    /** An amount in cents, as the rest of the app writes money ({@link MoneyFormat#brl}). */
    public static String moneyCents(long cents) {
        return MoneyFormat.brl(cents);
    }

    /** {@code + R$ 0,45} / {@code - R$ 0,45}; a shown zero has no sign ({@code R$ 0,00}); invalid: {@value #DASH}. */
    public static String signedMoney(Double reais) {
        BigDecimal value = shown(reais, 2);
        if (value == null) return DASH;
        String sign = value.signum() > 0 ? "+ " : value.signum() < 0 ? "- " : "";
        return sign + REAIS + grouped(value.abs(), 2, 2);
    }

    /**
     * The sign a screen writes by hand before an amount in cents ({@code "+ "} or {@code "- "}, as the kind of movement
     * says), and nothing when the amount is zero: cents are exactly what {@code R$ 0,00} shows, so zero reads
     * {@code R$ 0,00} whatever the kind. The text of an amount that is not zero is the caller's, unchanged.
     */
    public static String moneySign(String sign, long cents) {
        return cents == 0 ? "" : sign;
    }

    /** A change with the sign after the symbol ({@code R$ +0,45}, {@code R$ -0,45}); a shown zero: {@code R$ 0,00}. */
    public static String moneyDelta(Double reais) {
        BigDecimal value = shown(reais, 2);
        if (value == null) return DASH;
        String sign = value.signum() > 0 ? "+" : value.signum() < 0 ? "-" : "";
        return REAIS + sign + grouped(value.abs(), 2, 2);
    }

    // ── Percent ────────────────────────────────────────────────────────────

    /** {@code 3,22%} with {@code decimals} places, thousands with a dot ({@code 1.250,0%}); invalid: {@value #DASH}. */
    public static String pct(Double percent, int decimals) {
        BigDecimal value = shown(percent, decimals);
        return value == null ? DASH : grouped(value, decimals, decimals) + "%";
    }

    /** {@link #pct(Double, int)} with two places (a function of one argument, for a chart's crosshair). */
    public static String pct2(Double percent) {
        return pct(percent, 2);
    }

    /** Same as {@link #pct(Double, int)} for an exact percentage (a rate stored as a decimal). */
    public static String pctExact(BigDecimal percent, int decimals) {
        if (percent == null) return DASH;
        return grouped(percent.setScale(decimals, RoundingMode.HALF_UP), decimals, decimals) + "%";
    }

    /** {@code +0,85%} / {@code -0,85%}; a shown zero has no sign ({@code 0,00%}); invalid: {@value #DASH}. */
    public static String signedPct(Double percent, int decimals) {
        BigDecimal value = shown(percent, decimals);
        if (value == null) return DASH;
        return (value.signum() > 0 ? "+" : "") + grouped(value, decimals, decimals) + "%";
    }

    /** {@link #signedPct(Double, int)} with two places (a function of one argument, for a chart's crosshair). */
    public static String signedPct2(Double percent) {
        return signedPct(percent, 2);
    }

    /** A yearly rate: {@code 13,50% a.a.}. */
    public static String ratePerYear(BigDecimal percent) {
        String text = pctExact(percent, 2);
        return DASH.equals(text) ? text : text + " a.a.";
    }

    // ── Quantities ─────────────────────────────────────────────────────────

    /** A whole quantity or count with thousands separated by a dot: {@code 1.500}. */
    public static String integer(long value) {
        return grouped(BigDecimal.valueOf(value), 0, 0);
    }

    /** A volume: {@code 12,3K}, {@code 12,3M}, {@code 1.234,5M}; below 1.000 the number itself; none: {@value #DASH}. */
    public static String volume(long volume) {
        if (volume >= 1_000_000) return grouped(BigDecimal.valueOf(volume).movePointLeft(6).setScale(1, RoundingMode.HALF_UP), 1, 1) + "M";
        if (volume >= 1_000) return grouped(BigDecimal.valueOf(volume).movePointLeft(3).setScale(1, RoundingMode.HALF_UP), 1, 1) + "K";
        return volume > 0 ? integer(volume) : DASH;
    }

    // ── Dates ──────────────────────────────────────────────────────────────

    /** An ISO date ({@code 2026-11-05}) as {@code 05/11/2026}; any other text as it is; {@code null} as an empty text. */
    public static String isoDate(String text) {
        if (text == null) return "";
        try {
            return UiDates.BR.format(LocalDate.parse(text));
        } catch (DateTimeParseException notAnIsoDate) {
            return text;
        }
    }

    // ── Tone ───────────────────────────────────────────────────────────────

    /** {@value #POS} when the shown value is above zero, {@value #NEG} below, {@value #MUTED} at zero or invalid. */
    public static String tone(Double value, int decimals) {
        BigDecimal rounded = shown(value, decimals);
        if (rounded == null || rounded.signum() == 0) return MUTED;
        return rounded.signum() > 0 ? POS : NEG;
    }

    /** The tone of an amount in cents. */
    public static String toneOfCents(long cents) {
        return cents == 0 ? MUTED : cents > 0 ? POS : NEG;
    }

    /** Gives {@code node} exactly one of the tone classes ({@code null} leaves it with none). */
    public static void applyTone(Node node, String tone) {
        node.getStyleClass().removeAll(TONES);
        if (tone != null) node.getStyleClass().add(tone);
    }

    // ── Axes ───────────────────────────────────────────────────────────────

    /**
     * The tick labels of a value axis: thousands with a dot, decimals with a comma, up to two places and none trailing
     * ({@code 22.500.000}, {@code 1.250}, {@code 3,5}, {@code 0,25}), without {@code R$} or {@code %} (the axis title
     * names the unit).
     */
    public static StringConverter<Number> axisConverter() {
        return new StringConverter<>() {
            @Override public String toString(Number tick) {
                BigDecimal value = tick == null ? null : shown(tick.doubleValue(), 2);
                return value == null ? DASH : grouped(value, 0, 2);
            }

            @Override public Number fromString(String text) {
                if (text == null || text.isBlank()) return null;
                try {
                    return NumberFormat.getNumberInstance(ptBr()).parse(text.trim());
                } catch (ParseException notANumber) {
                    return null;
                }
            }
        };
    }
}

package com.daniel.core.util;

import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class Money {

    private static final Locale LOCALE_BR = Locale.forLanguageTag("pt-BR");
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(LOCALE_BR);

    private static final char DECIMAL_SEP = new DecimalFormatSymbols(LOCALE_BR).getDecimalSeparator();
    private static final Pattern ALLOWED = Pattern.compile("[0-9\\s\u00a0R\\$\\.,-]*");

    private Money() {}

    public static long textToCentsOrZero(String input) {
        if (input == null) return 0L;
        String s = input.trim();
        if (s.isEmpty()) return 0L;

        s = s.replace("R$", "").trim();
        s = s.replace(" ", "").replace("\u00A0", "");


        if (s.contains(".") && s.contains(",")) {
            s = s.replace(".", "").replace(",", ".");
        } else if (s.contains(",")) {
            s = s.replace(",", ".");
        }

        if (s.isBlank()) return 0L;

        return new BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long textToCentsSafe(String input) {
        try {
            return textToCentsOrZero(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Valor inválido. Use algo como 1234,56");
        }
    }

    public static String centsToCurrencyText(long cents) {
        synchronized (BRL) {
            return BRL.format(BigDecimal.valueOf(cents, 2));
        }
    }

    public static String centsToText(long cents) {
        return centsToCurrencyText(cents);
    }

    static boolean isEditableCurrencyText(String text) {
        return text == null || ALLOWED.matcher(text).matches();
    }

    public static TextFormatter<String> currencyFormatterEditable() {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String n = change.getControlNewText();
            if (!isEditableCurrencyText(n)) return null;
            return change;
        };
        return new TextFormatter<>(filter);
    }

    public static void applyCurrencyFormatOnBlur(TextField field) {
        field.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) {
                String t = field.getText();
                String formatted = reformatOnBlur(t);
                if (!java.util.Objects.equals(t, formatted)) field.setText(formatted);
            }
        });
    }

    /**
     * Text a currency field shows after losing focus. Blank or unparsable texts (the editable
     * filter admits "1,2,3", "-", "R") stay exactly as typed so the user can correct them; the
     * validation boundary of the form reports them, never the blur listener.
     */
    public static String reformatOnBlur(String text) {
        if (text == null || text.trim().isBlank()) return text;
        try {
            return centsToCurrencyText(textToCentsOrZero(text));
        } catch (RuntimeException unparsable) {
            return text;
        }
    }

    public static void applyFormatOnBlur(TextField field) {
        applyCurrencyFormatOnBlur(field);
    }
}

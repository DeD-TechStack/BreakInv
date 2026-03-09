package com.daniel.core.util;

import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class Money {

    private static final Locale LOCALE_BR = new Locale("pt", "BR");
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(LOCALE_BR);

    private static final char DECIMAL_SEP = new DecimalFormatSymbols(LOCALE_BR).getDecimalSeparator();
    private static final Pattern ALLOWED = Pattern.compile("[0-9\\sR\\$\\.,-]*");

    private Money() {}

    public static long textToCentsOrZero(String input) {
        if (input == null) return 0L;
        String s = input.trim();
        if (s.isEmpty()) return 0L;

        s = s.replace("R$", "").trim();
        s = s.replace(" ", "").replace("\u00A0", "");

        boolean neg = s.startsWith("-");
        if (neg) s = s.substring(1);

        if (s.contains(".") && s.contains(",")) {
            s = s.replace(".", "").replace(",", ".");
        } else if (s.contains(",")) {
            s = s.replace(",", ".");
        }

        if (s.isBlank()) return 0L;

        double v = Double.parseDouble(s);
        long cents = Math.round(v * 100.0);
        return neg ? -cents : cents;
    }

    public static long textToCentsSafe(String input) {
        try {
            return textToCentsOrZero(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Valor inválido. Use algo como 1234,56");
        }
    }

    public static String centsToCurrencyText(long cents) {
        return BRL.format(cents / 100.0);
    }

    public static String centsToText(long cents) {
        return centsToCurrencyText(cents);
    }

    public static TextFormatter<String> currencyFormatterEditable() {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String n = change.getControlNewText();
            if (n == null) return change;
            if (!ALLOWED.matcher(n).matches()) return null;
            return change;
        };
        return new TextFormatter<>(filter);
    }

    public static void applyCurrencyFormatOnBlur(TextField field) {
        field.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is) {
                String t = field.getText();
                if (t == null || t.trim().isBlank()) return;
                long cents = textToCentsOrZero(t);
                field.setText(centsToCurrencyText(cents));
            }
        });
    }

    public static void applyFormatOnBlur(TextField field) {
        applyCurrencyFormatOnBlur(field);
    }
}

package com.daniel.core.util;

import java.text.NumberFormat;
import java.util.Locale;

public final class MoneyFormat {

    private MoneyFormat() {}

    private static final NumberFormat BRL =
            NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));

    public static String brl(long cents) {
        return BRL.format(cents / 100.0);
    }

    public static String brlAbs(long cents) {
        return BRL.format(Math.abs(cents) / 100.0);
    }
}

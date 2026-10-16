package com.daniel.presentation.viewmodel;

import java.math.BigDecimal;

/** Shared decimal parsing for dialogs outside this package. */
public final class MovementFormAccess {
    private MovementFormAccess() {}

    public static BigDecimal parse(String text) {
        return MovementForm.parseDecimal(text, "amount", "informe o valor");
    }
}

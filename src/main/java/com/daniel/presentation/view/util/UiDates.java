package com.daniel.presentation.view.util;

import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Date presentation of the pt-BR UI: {@code dd/MM/yyyy} on every DatePicker, whatever the JVM's
 * default locale is (the GUI pass saw {@code 10/16/2026}). Only the text is localised — the value
 * stays a {@link LocalDate}; persistence and the API parsers are untouched.
 */
public final class UiDates {

    public static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    private UiDates() {}

    public static StringConverter<LocalDate> brConverter() {
        return new StringConverter<>() {
            @Override public String toString(LocalDate d) {
                return d == null ? "" : BR.format(d);
            }

            /** dd/MM/yyyy, or ISO typed by hand; anything else is rejected (null), never reinterpreted. */
            @Override public LocalDate fromString(String s) {
                if (s == null || s.isBlank()) return null;
                String t = s.trim();
                try { return LocalDate.parse(t, BR); } catch (DateTimeParseException ignored) { }
                try { return LocalDate.parse(t); } catch (DateTimeParseException ignored) { }
                return null;
            }
        };
    }

    /** Read the editor at the submission boundary, not the last committed calendar value. */
    public static LocalDate commitEditor(DatePicker picker) {
        LocalDate date = brConverter().fromString(picker.getEditor().getText());
        // Do not erase an invalid edit; callers show an error and leave it available to correct.
        if (date != null) picker.setValue(date);
        return date;
    }

    public static void applyBrFormat(DatePicker picker) {
        StringConverter<LocalDate> conv = brConverter();
        picker.setConverter(conv);
        if (picker.getPromptText() == null || picker.getPromptText().isBlank()) picker.setPromptText("dd/mm/aaaa");
        picker.getEditor().setText(conv.toString(picker.getValue()));
    }
}

package com.daniel.presentation.view.pages.storage;

import com.daniel.infrastructure.persistence.backup.StorageLimits;

/**
 * BRK-31 u5 F2, RF-07: the text of a limit field (whole MB, digits only) to bytes and back. Pure; no JavaFX.
 *
 * <p>Only {@code [0-9]+} (after {@code strip()}) is a number: no sign, dot, comma, inner space or unit. A number too big
 * for a {@code long} (in {@code parseLong} or in the multiplication by one MiB) is not refused here: it becomes
 * {@code Long.MAX_VALUE}, so that the range check of {@code StorageLimits.problem()} refuses it with its own text.</p>
 */
public final class LimitInput {

    /** What {@link #parse} returns: {@link Parsed} or {@link Invalid}. */
    public sealed interface Result permits Parsed, Invalid {
    }

    /** A number of MB, as bytes. */
    public record Parsed(long bytes) implements Result {
    }

    /** Empty, or anything that is not digits: the field shows {@code StoragePresenter.notANumber()}. */
    public record Invalid() implements Result {
    }

    private LimitInput() {
    }

    public static Result parse(String text) {
        if (text == null) return new Invalid();
        String digits = text.strip();
        if (!digits.matches("[0-9]+")) return new Invalid();
        try {
            return new Parsed(Math.multiplyExact(Long.parseLong(digits), StorageLimits.MIB));
        } catch (NumberFormatException | ArithmeticException overflow) {
            return new Parsed(Long.MAX_VALUE);
        }
    }

    /**
     * Whole MiB as plain digits, without a thousands separator (the field is read back). A value that is not a multiple
     * of one MiB is rounded down.
     */
    public static String format(long bytes) {
        return Long.toString(bytes / StorageLimits.MIB);
    }
}

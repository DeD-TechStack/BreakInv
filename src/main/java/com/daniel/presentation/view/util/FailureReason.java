package com.daniel.presentation.view.util;

import com.daniel.infrastructure.persistence.backup.WriteFailureReason;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The short reason shown for a failure (BRK-31 u4c): U4C:RF-03 for a failed opening of the data and U4C:RF-04 for a
 * failed daily snapshot. Both unwrap the failure first, use the category of {@link WriteFailureReason} and, when the
 * category says nothing, the first line of the message of the outermost failure.
 */
public final class FailureReason {

    private static final String UNEXPECTED_OPENING = "erro inesperado ao abrir os dados";
    private static final String UNEXPECTED_SNAPSHOT = "erro inesperado";

    private FailureReason() {
    }

    /** U4C:RF-03: the reason of a failed opening of the data. */
    public static String forOpening(Throwable failure) {
        Throwable outer = outermost(failure);
        return WriteFailureReason.of(outer, firstLine(outer, UNEXPECTED_OPENING));
    }

    /** U4C:RF-04: the same rule, with the text "erro inesperado" for no message and without the final dots. */
    public static String forSnapshot(Throwable failure) {
        Throwable outer = outermost(failure);
        String reason = WriteFailureReason.of(outer, firstLine(outer, UNEXPECTED_SNAPSHOT));
        int end = reason.length();
        while (end > 0 && reason.charAt(end - 1) == '.') end--;
        String withoutDots = reason.substring(0, end).strip();
        return withoutDots.isEmpty() ? UNEXPECTED_SNAPSHOT : withoutDots;
    }

    /** The failure inside any chain of {@link CompletionException} or {@link ExecutionException} that has a cause. */
    static Throwable outermost(Throwable failure) {
        Throwable t = failure;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** First line of the message (up to the first CR or LF) without spaces at the ends; {@code fallback} if empty. */
    private static String firstLine(Throwable failure, String fallback) {
        String message = failure.getMessage();
        if (message == null) return fallback;
        int cut = indexOfLineBreak(message);
        String line = (cut < 0 ? message : message.substring(0, cut)).strip();
        return line.isEmpty() ? fallback : line;
    }

    private static int indexOfLineBreak(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') return i;
        }
        return -1;
    }
}

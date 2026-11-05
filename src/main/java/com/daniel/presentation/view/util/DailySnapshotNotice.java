package com.daniel.presentation.view.util;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a failed daily snapshot does, the same for its three callers (BRK-31 u4c, U4C:RF-04): a WARNING in the log of
 * the caller's class and a notice with the reason, shown on the UI executor.
 */
public final class DailySnapshotNotice {

    private DailySnapshotNotice() {
    }

    /** The text of the notice. */
    public static String message(Throwable failure) {
        String reason = FailureReason.forSnapshot(failure);
        return "Não foi possível registrar o valor da carteira de hoje (" + reason + "). "
                + "O BreakInv tenta de novo na próxima alteração da carteira ou na próxima abertura de hoje.";
    }

    /** Logs the failure on {@code log} and shows the notice through {@code warn}, run by {@code ui}. */
    public static void report(Logger log, Throwable failure, Executor ui, Consumer<String> warn) {
        log.log(Level.WARNING, "daily snapshot failed", failure);
        String text = message(failure);
        ui.execute(() -> warn.accept(text));
    }
}

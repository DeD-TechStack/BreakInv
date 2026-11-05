package com.daniel.main;

import com.daniel.presentation.view.OpeningFailurePane;
import com.daniel.presentation.view.util.FailureReason;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the failure window does with "Tentar de novo" and "Fechar" (BRK-31 u4c, U4C:RF-01 and RF-02). The window itself
 * is not here: the flow gets the pane, the way to close the window and the way to end the program.
 *
 * <p>Threads: {@link #retry} and {@link #close} run on the FX thread. The attempt runs on {@code attempts}, which must
 * never interrupt it (an interruption while waiting for the instance lock would open the data during another
 * window's restore); the answer goes back through {@code ui}. A "Fechar" during an attempt closes the window at once;
 * the attempt ends by itself, lets go of what it opened and only then the program ends.
 */
final class OpeningFailureFlow {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    private final DataOpening opening;
    private final OpeningFailurePane pane;
    private final Executor attempts;
    private final Executor ui;
    private final Consumer<DataOpening.Attempt> onOpened;
    private final Runnable closeWindow;
    private final Runnable exit;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** FX thread only. */
    private boolean trying;

    /**
     * @param onOpened    called on the FX thread after the failure window closed, with the attempt that opened
     * @param closeWindow closes the failure window
     * @param exit        ends the program (called once)
     */
    OpeningFailureFlow(DataOpening opening, OpeningFailurePane pane, Executor attempts, Executor ui,
                       Consumer<DataOpening.Attempt> onOpened, Runnable closeWindow, Runnable exit) {
        this.opening = opening;
        this.pane = pane;
        this.attempts = attempts;
        this.ui = ui;
        this.onOpened = onOpened;
        this.closeWindow = closeWindow;
        this.exit = exit;
        pane.setOnRetry(this::retry);
        pane.setOnClose(this::close);
    }

    /** "Tentar de novo": a new attempt, one at a time. */
    void retry() {
        if (closed.get() || trying) return;
        trying = true;
        pane.showTrying();
        attempts.execute(this::runAttempt);
    }

    /** "Fechar", the X and Esc. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeWindow.run();
        if (!trying) exit.run();
        // during an attempt the program ends when the attempt does (see runAttempt and answered)
    }

    /** On the attempt executor. */
    private void runAttempt() {
        DataOpening.Attempt attempt;
        try {
            attempt = opening.attempt();
        } catch (Error error) {
            LOG.log(Level.SEVERE, "data opening failed", error);
            exit.run();
            return;
        }
        if (closed.get()) {
            endAfterClose(attempt);
            return;
        }
        ui.execute(() -> answered(attempt));
    }

    /** On the FX thread. */
    private void answered(DataOpening.Attempt attempt) {
        if (closed.get()) {
            endAfterClose(attempt);
            return;
        }
        trying = false;
        if (attempt.opened()) {
            closeWindow.run();
            onOpened.accept(attempt);
        } else {
            pane.showReason(FailureReason.forOpening(attempt.failure()));
        }
    }

    /** The window was closed during the attempt: what it opened is let go and the program ends. */
    private void endAfterClose(DataOpening.Attempt attempt) {
        attempt.release();
        exit.run();
    }
}

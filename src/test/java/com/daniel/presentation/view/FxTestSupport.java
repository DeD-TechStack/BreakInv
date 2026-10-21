package com.daniel.presentation.view;

import javafx.application.Platform;
import org.opentest4j.TestAbortedException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts the JavaFX toolkit once per JVM (no stage is ever shown) and runs test bodies on the
 * FX thread. Pages and dialogs are built with injected executors, so the worker is a queue the
 * test drains by hand and every callback runs synchronously on the FX thread: no timing, no
 * native input, no window.
 */
public final class FxTestSupport {

    private static volatile boolean started;

    private FxTestSupport() {}

    public static synchronized void ensureStarted() {
        if (started) return;
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            latch.countDown();
        } catch (RuntimeException | Error noToolkit) {
            // e.g. no DISPLAY on a headless CI runner: the FX-dependent tests are skipped, not failed
            throw new TestAbortedException("JavaFX toolkit unavailable: " + noToolkit, noToolkit);
        }
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new TestAbortedException("JavaFX toolkit did not start in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Platform.setImplicitExit(false);
        started = true;
    }

    @FunctionalInterface
    public interface FxBody {
        void run() throws Exception;
    }

    /** Runs the body on the FX thread and rethrows anything it throws (assertions included). */
    public static void runAndWait(FxBody body) {
        ensureStarted();
        if (Platform.isFxApplicationThread()) {
            try {
                com.daniel.presentation.view.util.UiTheme.install();
                body.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                com.daniel.presentation.view.util.UiTheme.install();
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("FX body timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Throwable t = failure.get();
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error er) throw er;
        if (t != null) throw new RuntimeException(t);
    }
}

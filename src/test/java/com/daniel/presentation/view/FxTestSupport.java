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

    /**
     * E-V2 class: a filled or framed message (warning banner, dialog context, stale-curve note)
     * spans its container like the fields and panels around it, so a short text never leaves a box
     * that ends mid-row. Shows every Label matching the selectors with a short text, lays the
     * (already sized) root out and lists each one narrower than its parent's content width.
     */
    public static java.util.List<String> messageBoxesShortOfTheirContainer(javafx.scene.Parent root, String... selectors) {
        for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
        java.util.List<javafx.scene.control.Label> boxes = new java.util.ArrayList<>();
        for (String selector : selectors) {
            for (javafx.scene.Node node : root.lookupAll(selector)) {
                if (node instanceof javafx.scene.control.Label label && !boxes.contains(label)) boxes.add(label);
            }
        }
        if (boxes.isEmpty()) throw new AssertionError("no message box found for " + java.util.Arrays.toString(selectors));
        for (javafx.scene.control.Label box : boxes) {
            box.setText("Curto.");
            if (!box.visibleProperty().isBound()) box.setVisible(true);
            if (!box.managedProperty().isBound()) box.setManaged(true);
        }
        for (int pass = 0; pass < 6; pass++) { root.applyCss(); root.layout(); }
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (javafx.scene.control.Label box : boxes) {
            if (!(box.getParent() instanceof javafx.scene.layout.Region parent)) {
                problems.add(box.getStyleClass() + " has no Region parent");
                continue;
            }
            double content = parent.getWidth() - parent.snappedLeftInset() - parent.snappedRightInset();
            if (box.getWidth() < content - 1) problems.add(box.getStyleClass() + " " + box.getWidth() + " < " + content);
        }
        return problems;
    }

    /**
     * V5 (no clipping): every shown Labeled under {@code root} whose rendered text is not its whole
     * text — an ellipsis or a cut, measured on the skin's Text, not on {@code getText()}. Hidden or
     * unmanaged branches are skipped; ScrollPane content is included.
     */
    public static java.util.List<String> cutTexts(javafx.scene.Parent root) {
        java.util.List<String> cut = new java.util.ArrayList<>();
        collectCut(root, cut);
        return cut;
    }

    private static void collectCut(javafx.scene.Node node, java.util.List<String> cut) {
        if (!node.isVisible()) return;
        if (node instanceof javafx.scene.control.Labeled l && l.getText() != null && !l.getText().isBlank()
                && l.getContentDisplay() != javafx.scene.control.ContentDisplay.GRAPHIC_ONLY) {
            // the skin's own LabeledText is a direct child; a graphic may hold other texts
            for (javafx.scene.Node t : l.getChildrenUnmodifiable()) {
                if (t instanceof javafx.scene.text.Text text && text.getStyleClass().contains("text")
                        && !l.getText().equals(text.getText())) {
                    cut.add("'" + l.getText() + "' -> '" + text.getText() + "' (" + Math.round(l.getWidth()) + " px)");
                }
            }
        }
        if (node instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) collectCut(sp.getContent(), cut);
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) collectCut(child, cut);
        }
    }
}

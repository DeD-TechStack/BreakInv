package com.daniel.presentation.view.util;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The single place the app starts animations from.
 *
 * <ul>
 *   <li><b>Cancellable:</b> at most one animation per node; starting another stops the previous
 *       one and puts the node in its final state first, so the last request always wins.</li>
 *   <li><b>Data first:</b> text, children and layout values are assigned before the animation
 *       starts. Animations only ever touch opacity/translate/scale/prefWidth; a stale callback can
 *       never overwrite newer data.</li>
 *   <li><b>Reduce motion:</b> a persistent user preference ({@link UiPreferences}). When on, every
 *       call jumps straight to the end state. There is no OS detection: JavaFX 21 exposes none.</li>
 * </ul>
 *
 * Durations: feedback 100–160 ms · navigation/panels 180–260 ms · charts 250–450 ms.
 */
public final class Motion {

    public static final int FEEDBACK_MS = 140;
    public static final int NAV_MS = 200;
    public static final int PANEL_MS = 220;
    public static final int CHART_MS = 320;

    private static final Object KEY_ANIMATION = new Object();
    private static final Object KEY_FINAL_STATE = new Object();

    private static volatile boolean reduced;
    private static Runnable unsubscribe;
    /** Nodes with an animation in flight, so turning "reduce motion" on can settle them at once. */
    private static final Set<Node> live = Collections.newSetFromMap(new WeakHashMap<>());

    private Motion() {}

    // ── Configuration ──────────────────────────────────────────────────────

    /**
     * Binds the reduce-motion flag to a preferences object (null detaches, animations run).
     * Turning the preference on while animations are running settles them immediately: the
     * toggle takes effect in the session, not after the next transition.
     */
    public static synchronized void configure(UiPreferences p) {
        if (unsubscribe != null) { unsubscribe.run(); unsubscribe = null; }
        if (p == null) { reduced = false; return; }
        reduced = p.reduceMotion();
        unsubscribe = p.onReduceMotionChanged(v -> {
            reduced = v;
            if (v) settleAll();
        });
    }

    /** Stops every running animation, leaving each node in its final state. */
    public static void settleAll() {
        for (Node n : new ArrayList<>(live)) settle(n);
    }

    public static boolean reduced() {
        return reduced;
    }

    // ── Per-node bookkeeping ───────────────────────────────────────────────

    public static boolean isAnimating(Node node) {
        Animation a = (Animation) node.getProperties().get(KEY_ANIMATION);
        return a != null && a.getStatus() == Animation.Status.RUNNING;
    }

    /** Stops whatever runs on the node and leaves it in the final (settled) state. */
    public static void settle(Node node) {
        Animation a = (Animation) node.getProperties().remove(KEY_ANIMATION);
        Runnable finalState = (Runnable) node.getProperties().remove(KEY_FINAL_STATE);
        live.remove(node);
        if (a != null) {
            a.setOnFinished(null);
            a.stop();
        }
        restoreVisualState(node);
        if (finalState != null) finalState.run();
    }

    /** Alias used when a view is discarded: stop timelines, drop references. */
    public static void dispose(Node node) {
        settle(node);
    }

    private static void restoreVisualState(Node node) {
        node.setOpacity(1.0);
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setScaleX(1.0);
        node.setScaleY(1.0);
    }

    /**
     * Registers and plays. {@code finalState} (may be null) is what settle() must apply beyond the
     * visual reset; {@code onDone} runs only when this very animation finishes naturally.
     */
    private static void run(Node node, Animation animation, Runnable finalState, Runnable onDone) {
        settle(node);
        if (reduced) {
            restoreVisualState(node);
            if (finalState != null) finalState.run();
            if (onDone != null) onDone.run();
            return;
        }
        node.getProperties().put(KEY_ANIMATION, animation);
        if (finalState != null) node.getProperties().put(KEY_FINAL_STATE, finalState);
        live.add(node);
        animation.setOnFinished(e -> {
            if (node.getProperties().get(KEY_ANIMATION) == animation) {
                node.getProperties().remove(KEY_ANIMATION);
                node.getProperties().remove(KEY_FINAL_STATE);
                live.remove(node);
                restoreVisualState(node);
                if (finalState != null) finalState.run();
                if (onDone != null) onDone.run();
            }
        });
        animation.play();
    }

    // ── Page swap (navigation) ─────────────────────────────────────────────

    /**
     * Replaces the host's content with {@code incoming}. Direction &gt; 0 means "down the menu"
     * (new page rises from +12 px), &lt; 0 "up the menu" (comes from −12 px), 0 = fade only.
     * Any earlier swap on the same host is cancelled: the previous page is removed at once and the
     * incoming one is the only child; nothing is left at opacity 0.
     */
    public static void swap(Pane host, Node incoming, int direction) {
        settle(host);
        for (Node old : host.getChildren()) {
            if (old != incoming) settle(old);
        }
        if (host.getChildren().size() != 1 || host.getChildren().get(0) != incoming) {
            host.getChildren().setAll(incoming);
        }
        settle(incoming);
        if (reduced) return;

        double from = direction == 0 ? 0 : Math.signum(direction) * 12;
        incoming.setOpacity(0);
        incoming.setTranslateY(from);
        FadeTransition fade = new FadeTransition(Duration.millis(NAV_MS), incoming);
        fade.setFromValue(0); fade.setToValue(1); fade.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition slide = new TranslateTransition(Duration.millis(NAV_MS), incoming);
        slide.setFromY(from); slide.setToY(0); slide.setInterpolator(Interpolator.EASE_OUT);
        run(incoming, new ParallelTransition(fade, slide), null, null);
        // settle(host) must also cancel the incoming's entrance
        host.getProperties().put(KEY_FINAL_STATE, (Runnable) () -> settle(incoming));
    }

    // ── Enter / leave ──────────────────────────────────────────────────────

    /** Fades and slides a node in from {@code fromY} px below. Node must already be visible/managed. */
    public static void fadeSlideIn(Node node, int ms, double fromY) {
        settle(node);
        if (reduced) return;
        node.setOpacity(0);
        node.setTranslateY(fromY);
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setFromValue(0); fade.setToValue(1); fade.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition slide = new TranslateTransition(Duration.millis(ms), node);
        slide.setFromY(fromY); slide.setToY(0); slide.setInterpolator(Interpolator.EASE_OUT);
        run(node, new ParallelTransition(fade, slide), null, null);
    }

    /** Horizontal variant for side panels (positive fromX = enters from the right). */
    public static void slideInX(Node node, int ms, double fromX) {
        settle(node);
        if (reduced) return;
        node.setOpacity(0);
        node.setTranslateX(fromX);
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setFromValue(0); fade.setToValue(1); fade.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition slide = new TranslateTransition(Duration.millis(ms), node);
        slide.setFromX(fromX); slide.setToX(0); slide.setInterpolator(Interpolator.EASE_OUT);
        run(node, new ParallelTransition(fade, slide), null, null);
    }

    /** Fades a node out, then runs {@code then} (typically hide/unmanage). Reduced: runs at once. */
    public static void fadeOut(Node node, int ms, Runnable then) {
        settle(node);
        if (reduced) { if (then != null) then.run(); return; }
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setFromValue(1); fade.setToValue(0); fade.setInterpolator(Interpolator.EASE_IN);
        run(node, fade, null, then);
    }

    /** Fades a node out and removes it from its parent pane. */
    public static void fadeOutAndRemove(Node node, Pane parent, int ms) {
        fadeOut(node, ms, () -> parent.getChildren().remove(node));
    }

    // ── Values ─────────────────────────────────────────────────────────────

    /**
     * Sets the label text IMMEDIATELY and gives a short opacity pulse so the eye catches the
     * change. The value never waits for the animation, so no stale callback can revert it.
     */
    public static void animateLabelChange(Label label, String newText) {
        if (newText == null) newText = "";
        boolean changed = !newText.equals(label.getText());
        label.setText(newText);
        if (!changed) return;
        settle(label);
        if (reduced) return;
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(label.opacityProperty(), 0.35)),
                new KeyFrame(Duration.millis(FEEDBACK_MS), new KeyValue(label.opacityProperty(), 1.0, Interpolator.EASE_OUT)));
        run(label, pulse, null, null);
    }

    /** Animates a region's prefWidth (sidebar 220↔56). Last target wins; settle jumps to it. */
    public static void animatePrefWidth(Region region, double target, int ms) {
        animatePrefWidth(region, target, ms, null);
    }

    /**
     * Same, with {@code then} applied at the end state (natural finish, settle, or reduced motion)
     * — e.g. pinning min/max once the width has arrived, so the layout can follow prefWidth
     * while the transition runs.
     */
    public static void animatePrefWidth(Region region, double target, int ms, Runnable then) {
        settle(region);
        Runnable finalState = () -> { region.setPrefWidth(target); if (then != null) then.run(); };
        if (reduced) { finalState.run(); return; }
        Timeline t = new Timeline(
                new KeyFrame(Duration.millis(ms), new KeyValue(region.prefWidthProperty(), target, Interpolator.EASE_BOTH)));
        run(region, t, finalState, null);
    }

    // ── Hover ──────────────────────────────────────────────────────────────

    /** Subtle scale on hover for clickable cards only (never for static values). */
    public static void hoverLift(Node node) {
        node.setOnMouseEntered(e -> {
            if (reduced) return;
            ScaleTransition st = new ScaleTransition(Duration.millis(120), node);
            st.setToX(1.018); st.setToY(1.018); st.setInterpolator(Interpolator.EASE_OUT);
            run(node, st, () -> { node.setScaleX(1.018); node.setScaleY(1.018); }, null);
        });
        node.setOnMouseExited(e -> {
            if (reduced) { node.setScaleX(1); node.setScaleY(1); return; }
            ScaleTransition st = new ScaleTransition(Duration.millis(140), node);
            st.setToX(1.0); st.setToY(1.0); st.setInterpolator(Interpolator.EASE_IN);
            run(node, st, null, null);
        });
    }
}

package com.daniel.presentation.view.util;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

/**
 * Lightweight animation utilities — no external dependencies.
 * All timings are intentionally subtle (80–220 ms).
 */
public final class Motion {

    private Motion() {}

    /**
     * Adds a subtle hover-lift effect (2% scale) to the given node.
     * Scale does not affect layout bounds in JavaFX, so neighbours don't shift.
     */
    public static void hoverLift(Node node) {
        node.setOnMouseEntered(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(120), node);
            st.setToX(1.018);
            st.setToY(1.018);
            st.setInterpolator(Interpolator.EASE_OUT);
            st.play();
        });
        node.setOnMouseExited(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(140), node);
            st.setToX(1.0);
            st.setToY(1.0);
            st.setInterpolator(Interpolator.EASE_IN);
            st.play();
        });
    }

    /**
     * Fades and slides a node in from {@code fromY} px below its final position.
     * Call after the node is already visible/managed = true.
     */
    public static void fadeSlideIn(Node node, int ms, double fromY) {
        node.setOpacity(0);
        node.setTranslateY(fromY);

        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition slide = new TranslateTransition(Duration.millis(ms), node);
        slide.setFromY(fromY);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, slide).play();
    }

    /**
     * Fades a node out and removes it from its parent {@code Pane}.
     */
    public static void fadeOutAndRemove(Node node, Pane parent, int ms) {
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setFromValue(node.getOpacity());
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_IN);
        fade.setOnFinished(ev -> parent.getChildren().remove(node));
        fade.play();
    }

    /**
     * Animates a label's text change with a quick cross-fade.
     * Only triggers if the new text is different from the current text.
     */
    public static void animateLabelChange(Label label, String newText) {
        if (newText.equals(label.getText())) return;

        FadeTransition out = new FadeTransition(Duration.millis(80), label);
        out.setFromValue(1.0);
        out.setToValue(0.15);
        out.setInterpolator(Interpolator.EASE_IN);
        out.setOnFinished(ev -> {
            label.setText(newText);
            FadeTransition in = new FadeTransition(Duration.millis(140), label);
            in.setFromValue(0.15);
            in.setToValue(1.0);
            in.setInterpolator(Interpolator.EASE_OUT);
            in.play();
        });
        out.play();
    }
}

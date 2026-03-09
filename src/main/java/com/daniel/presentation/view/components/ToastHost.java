package com.daniel.presentation.view.components;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.util.Duration;

/**
 * Non-blocking toast/snackbar overlay.
 * Call {@link #install(StackPane)} once when building the shell,
 * then use the static show* methods from anywhere.
 */
public final class ToastHost {

    private static VBox container;
    private static Region dimOverlay;
    private static Node blurTarget;
    private static int dimCount = 0;

    private ToastHost() {}

    /** Wire the overlay into the root StackPane of the app shell. */
    public static void install(StackPane root) {
        // Capture first child as the blur/dim target (the main content node)
        blurTarget = root.getChildren().isEmpty() ? null : root.getChildren().get(0);

        container = new VBox(8);
        container.getStyleClass().add("toast-container");
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setPickOnBounds(false);
        container.setMouseTransparent(true);
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
        root.getChildren().add(container);
    }

    /** Applies a dark+blur overlay behind dialogs (reference-counted). */
    public static void showDim() {
        dimCount++;
        if (dimCount > 1) return; // overlay already visible
        if (container == null) return;
        StackPane root = (StackPane) container.getParent();
        if (root == null) return;

        if (blurTarget != null) blurTarget.setEffect(new GaussianBlur(8));

        dimOverlay = new Region();
        dimOverlay.getStyleClass().add("overlay-dim");
        dimOverlay.setOpacity(0);
        int idx = root.getChildren().indexOf(container);
        root.getChildren().add(idx, dimOverlay);
        FadeTransition ft = new FadeTransition(Duration.millis(200), dimOverlay);
        ft.setFromValue(0); ft.setToValue(1); ft.play();
    }

    /** Removes the dark+blur overlay (reference-counted). */
    public static void hideDim() {
        dimCount = Math.max(0, dimCount - 1);
        if (dimCount > 0) return; // still needed by another caller
        if (dimOverlay == null) return;

        if (blurTarget != null) blurTarget.setEffect(null);

        Region overlay = dimOverlay;
        dimOverlay = null;
        FadeTransition ft = new FadeTransition(Duration.millis(180), overlay);
        ft.setFromValue(1); ft.setToValue(0);
        ft.setOnFinished(e -> {
            StackPane root = (StackPane) overlay.getParent();
            if (root != null) root.getChildren().remove(overlay);
        });
        ft.play();
    }

    public static void showSuccess(String message) { show(iconFor(Feather.CHECK,          "icon-accent"),  message, "toast-success"); }
    public static void showError(String message)   { show(iconFor(Feather.X,              "icon-danger"),  message, "toast-error");   }
    public static void showWarn(String message)    { show(iconFor(Feather.ALERT_TRIANGLE, "icon-warn"),    message, "toast-warn");    }
    public static void showInfo(String message)    { show(iconFor(Feather.INFO,           "icon-muted"),   message, "toast-info");    }

    private static FontIcon iconFor(Feather f, String extraClass) {
        FontIcon fi = new FontIcon(f);
        fi.getStyleClass().addAll("toast-icon", extraClass);
        fi.setIconSize(15);
        return fi;
    }

    private static void show(Node iconNode, String message, String variant) {
        if (container == null) return;

        Label msgLabel = new Label(message);
        msgLabel.getStyleClass().add("toast-msg");
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(320);

        HBox toast = new HBox(10, iconNode, msgLabel);
        toast.getStyleClass().addAll("toast", variant);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setOpacity(0);

        container.getChildren().add(0, toast);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), toast);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();

        PauseTransition hold = new PauseTransition(Duration.seconds(3));
        hold.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), toast);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> container.getChildren().remove(toast));
            fadeOut.play();
        });
        hold.play();
    }
}

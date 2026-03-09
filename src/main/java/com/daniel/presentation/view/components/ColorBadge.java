package com.daniel.presentation.view.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * A coloured pill badge (dot + text) that avoids inline setStyle() strings.
 * Uses JavaFX Background/Color API for per-hue colouring.
 */
public final class ColorBadge {

    private ColorBadge() {}

    /**
     * Creates a badge HBox styled with the given hex colour.
     *
     * @param text     Badge label text
     * @param hexColor Hex colour string, e.g. "#22C55E"
     */
    public static HBox create(String text, String hexColor) {
        Color color = Color.web(hexColor);

        HBox badge = new HBox(6);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.getStyleClass().add("badge");

        // Background at 12% alpha — avoids setStyle() string
        Color bgColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0.12);
        badge.setBackground(new Background(
                new BackgroundFill(bgColor, new CornerRadii(6), Insets.EMPTY)));

        Circle dot = new Circle(4);
        dot.setFill(color);

        Label label = new Label(text);
        label.setTextFill(color);

        badge.getChildren().addAll(dot, label);
        return badge;
    }
}

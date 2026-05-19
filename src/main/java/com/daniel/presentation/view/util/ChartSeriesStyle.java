package com.daniel.presentation.view.util;

import javafx.scene.Node;

/**
 * Centralizes inline chart series stroke styles used across pages.
 *
 * JavaFX chart series line nodes are accessed via chart.lookup() after layout.
 * CSS class application is unreliable for stroke-color overrides on series line
 * nodes at runtime, so setStyle() is the only dependable approach. This utility
 * centralizes the color constants so they stay in sync with the design intent.
 */
public final class ChartSeriesStyle {

    private static final String COLOR_ACCENT  = "#22c55e";
    private static final String COLOR_MUTED   = "rgba(255,255,255,0.85)";
    private static final String COLOR_WARNING = "#f59e0b";
    private static final String COLOR_INFO    = "#3b82f6";

    private ChartSeriesStyle() {}

    /** Green accent line (#22c55e). Matches -color-accent design token. */
    public static void accent(Node node, double width) {
        apply(node, COLOR_ACCENT, width, null);
    }

    /** Muted white line for benchmark/secondary series. */
    public static void muted(Node node, double width) {
        apply(node, COLOR_MUTED, width, null);
    }

    /** Amber warning line (#f59e0b). */
    public static void warning(Node node, double width) {
        apply(node, COLOR_WARNING, width, null);
    }

    /** Amber warning dashed line (dash-array 8 4) — for moving average overlay. */
    public static void warningDashed(Node node, double width) {
        apply(node, COLOR_WARNING, width, "8 4");
    }

    /** Blue info line (#3b82f6). */
    public static void info(Node node, double width) {
        apply(node, COLOR_INFO, width, null);
    }

    private static void apply(Node node, String color, double width, String dashArray) {
        if (node == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("-fx-stroke: ").append(color).append("; ");
        sb.append("-fx-stroke-width: ").append(width).append(";");
        if (dashArray != null) {
            sb.append(" -fx-stroke-dash-array: ").append(dashArray).append(";");
        }
        node.setStyle(sb.toString());
    }
}

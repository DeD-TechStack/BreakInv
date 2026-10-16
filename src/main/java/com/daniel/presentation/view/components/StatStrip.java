package com.daniel.presentation.view.components;

import com.daniel.presentation.view.layout.AdaptiveGrid;
import com.daniel.presentation.view.util.Motion;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A row of label/value pairs separated by thin rules, instead of N identical cards. Values are
 * assigned immediately (pulse only).
 *
 * <p>Money is never cut: the page asks for N columns per breakpoint, but when the widest cell
 * does not fit {@code width / N} the strip drops to fewer columns for that width (measured at
 * layout, with the real font), so a value such as {@code R$ 98.765.432,10} stays whole. A
 * qualifier ("a reconhecer", "revisão pendente") is a separate, smaller line under the value
 * and may wrap; it is never glued to the number.
 */
public final class StatStrip extends VBox {

    private final AdaptiveGrid grid = new AdaptiveGrid(0, 8);
    private final Map<String, Label> values = new LinkedHashMap<>();
    private final Map<String, Label> qualifiers = new LinkedHashMap<>();
    private final List<VBox> cells = new ArrayList<>();
    private int requestedColumns;

    public StatStrip(String... labels) {
        getStyleClass().add("stat-strip");
        for (String l : labels) {
            Label caption = new Label(l);
            caption.getStyleClass().add("stat-label");
            Label value = new Label("—");
            value.getStyleClass().addAll("stat-value", "num");
            value.setMinWidth(USE_PREF_SIZE); // never ellipsize: the strip reflows instead
            Label qualifier = new Label();
            qualifier.getStyleClass().add("stat-qualifier");
            qualifier.setWrapText(true);
            qualifier.setVisible(false);
            qualifier.setManaged(false);
            VBox cell = new VBox(3, caption, value, qualifier);
            cell.getStyleClass().add("stat-cell");
            cell.setMaxWidth(Double.MAX_VALUE);
            VBox.setVgrow(cell, Priority.NEVER);
            values.put(l, value);
            qualifiers.put(l, qualifier);
            cells.add(cell);
        }
        grid.setCells(cells);
        requestedColumns = Math.max(1, labels.length);
        grid.setColumns(requestedColumns);
        getChildren().add(grid);
    }

    public List<String> labels() {
        return new ArrayList<>(values.keySet());
    }

    /** Columns the page asked for (per breakpoint); the strip may lay out fewer when they do not fit. */
    public int columns() {
        return requestedColumns;
    }

    /** Columns actually laid out at the current width. */
    public int effectiveColumns() {
        return grid.columns();
    }

    public void setColumns(int n) {
        requestedColumns = Math.max(1, n);
        grid.setColumns(requestedColumns);
        requestLayout();
    }

    public Label valueLabel(String label) {
        return values.get(label);
    }

    public Label qualifierLabel(String label) {
        return qualifiers.get(label);
    }

    public void set(String label, String text) {
        set(label, text, null);
    }

    /** {@code tone}: "pos", "neg", "muted" or null for the default colour. */
    public void set(String label, String text, String tone) {
        set(label, text, tone, null);
    }

    /** Value plus an optional qualifier under it (smaller, may wrap; never part of the number). */
    public void set(String label, String text, String tone, String qualifier) {
        Label v = values.get(label);
        if (v == null) return;
        v.getStyleClass().removeAll("pos", "neg", "muted");
        if (tone != null) v.getStyleClass().add(tone);
        Motion.animateLabelChange(v, text == null ? "—" : text);
        Label q = qualifiers.get(label);
        boolean show = qualifier != null && !qualifier.isBlank();
        q.setText(show ? qualifier : "");
        q.setVisible(show);
        q.setManaged(show);
        requestLayout();
    }

    // ── Reflow by measured width ───────────────────────────────────────────

    /** The strip may shrink down to one column of the widest cell; it never forces the page wider. */
    @Override
    protected double computeMinWidth(double height) {
        return widestCell() + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        double inner = width < 0 ? -1 : width - snappedLeftInset() - snappedRightInset();
        if (inner > 0) reflow(inner);
        return grid.prefHeight(inner) + snappedTopInset() + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double x = snappedLeftInset(), y = snappedTopInset();
        double w = getWidth() - x - snappedRightInset();
        reflow(w);
        grid.resizeRelocate(x, y, w, Math.max(0, getHeight() - y - snappedBottomInset()));
    }

    private double widestCell() {
        double widest = 0;
        for (VBox cell : cells) widest = Math.max(widest, cell.prefWidth(-1));
        return widest;
    }

    /**
     * Largest column count ≤ requested whose equal columns can each hold the widest cell; when
     * columns must be dropped the rows are balanced (4 cells → 2 × 2, not 3 + 1).
     */
    private void reflow(double available) {
        if (available <= 0 || cells.isEmpty()) return;
        double widest = widestCell();
        int c = requestedColumns;
        while (c > 1 && (available - (c - 1) * grid.getHgap()) / c < widest) c--;
        if (c < requestedColumns) {
            int rows = (int) Math.ceil(cells.size() / (double) c);
            c = (int) Math.ceil(cells.size() / (double) rows);
        }
        if (c != grid.columns()) grid.setColumns(c);
    }
}

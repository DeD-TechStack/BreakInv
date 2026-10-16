package com.daniel.presentation.view.layout;

import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

/**
 * A GridPane whose column count is set by the page (per breakpoint), re-flowing its cells.
 * Columns share the width equally; used for the stat strip and any "N equal tiles" row.
 */
public final class AdaptiveGrid extends GridPane {

    private final List<Node> cells = new ArrayList<>();
    private int columns = 1;

    public AdaptiveGrid(double hgap, double vgap) {
        setHgap(hgap);
        setVgap(vgap);
        getStyleClass().add("adaptive-grid");
    }

    public void setCells(List<? extends Node> nodes) {
        cells.clear();
        cells.addAll(nodes);
        relayout();
    }

    public int columns() {
        return columns;
    }

    public void setColumns(int n) {
        int c = Math.max(1, n);
        if (c == columns && !getChildren().isEmpty()) return;
        columns = c;
        relayout();
    }

    private void relayout() {
        getChildren().clear();
        getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setHgrow(Priority.ALWAYS);
            cc.setFillWidth(true);
            cc.setHalignment(HPos.LEFT);
            cc.setPercentWidth(100.0 / columns);
            getColumnConstraints().add(cc);
        }
        for (int i = 0; i < cells.size(); i++) {
            Node n = cells.get(i);
            add(n, i % columns, i / columns);
            GridPane.setHgrow(n, Priority.ALWAYS);
            GridPane.setFillWidth(n, true);
        }
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }
}

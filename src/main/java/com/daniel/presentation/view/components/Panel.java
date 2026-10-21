package com.daniel.presentation.view.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Surface block with a 15 px title, optional subtitle and an optional right-aligned slot
 * (chips, toggles). No shadow, no green border: hierarchy comes from the surface step.
 */
public final class Panel extends VBox {

    private final HBox header = new HBox(10);
    private final VBox stackedHeader = new VBox(8);
    private final VBox titles = new VBox(2);
    private final Region spacer = new Region();
    private boolean stacked;
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final HBox actions = new HBox(8);
    private final VBox body = new VBox(10);
    /**
     * R3-02: beside its actions the titles column keeps at least this width; below it the actions go under
     * the titles (at 632 px of page a 257 px column took the subtitle to four lines and, in a real window,
     * to one line with "…").
     */
    private static final double TITLES_MIN = 360;

    public Panel(String titleText, Node... content) {
        getStyleClass().add("panel-block");
        setSpacing(10);
        title.setText(titleText);
        title.getStyleClass().add("panel-title");
        title.setWrapText(true);
        subtitle.getStyleClass().add("panel-subtitle");
        subtitle.setWrapText(true);
        // R3-02: a wrapped title or subtitle is never squeezed to one line — in a real window the r3 Ranking
        // showed its caveats as "Variação da cotação em relação ao fechamen…" at 632 px of page
        title.setMinHeight(Region.USE_PREF_SIZE);
        subtitle.setMinHeight(Region.USE_PREF_SIZE);
        subtitle.setVisible(false);
        subtitle.setManaged(false);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_RIGHT);
        titles.getChildren().addAll(title, subtitle);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(titles, spacer, actions);
        body.getChildren().addAll(content);
        getChildren().addAll(header, body);
        setMaxWidth(Double.MAX_VALUE);
        widthProperty().addListener((obs, old, width) -> reflowHeader(width.doubleValue()));
    }

    public Panel subtitle(String text) {
        boolean show = text != null && !text.isBlank();
        subtitle.setText(show ? text : "");
        subtitle.setVisible(show);
        subtitle.setManaged(show);
        return this;
    }

    public Panel actions(Node... nodes) {
        actions.getChildren().setAll(nodes);
        requestLayout();
        return this;
    }

    @Override
    protected double computePrefHeight(double width) {
        reflowHeader(width);
        return super.computePrefHeight(width);
    }

    private void reflowHeader(double width) {
        if (width <= 0) return;
        double available = width - snappedLeftInset() - snappedRightInset();
        boolean next = !actions.getChildren().isEmpty()
                && available < actions.prefWidth(-1) + Math.max(TITLES_MIN, title.prefWidth(-1)) + 20;
        if (next == stacked) return;
        header.getChildren().clear();
        stackedHeader.getChildren().clear();
        stacked = next;
        actions.setAlignment(stacked ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        if (stacked) {
            stackedHeader.getChildren().setAll(titles, actions);
            getChildren().set(0, stackedHeader);
        } else {
            header.getChildren().setAll(titles, spacer, actions);
            getChildren().set(0, header);
        }
    }

    public VBox body() {
        return body;
    }

    public void setTitle(String text) {
        title.setText(text);
    }
}

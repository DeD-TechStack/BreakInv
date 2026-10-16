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
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final HBox actions = new HBox(8);
    private final VBox body = new VBox(10);

    public Panel(String titleText, Node... content) {
        getStyleClass().add("panel-block");
        setSpacing(10);
        title.setText(titleText);
        title.getStyleClass().add("panel-title");
        subtitle.getStyleClass().add("panel-subtitle");
        subtitle.setWrapText(true);
        subtitle.setVisible(false);
        subtitle.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox titles = new VBox(2, title, subtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(titles, spacer, actions);
        body.getChildren().addAll(content);
        getChildren().addAll(header, body);
        setMaxWidth(Double.MAX_VALUE);
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
        return this;
    }

    public VBox body() {
        return body;
    }

    public void setTitle(String text) {
        title.setText(text);
    }
}

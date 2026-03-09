package com.daniel.presentation.view;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Reusable card container with optional section title.
 * Replaces the repeated buildCard(title, children...) pattern across pages.
 */
public final class SectionCard extends VBox {

    public SectionCard(String title, Node... children) {
        getStyleClass().add("card");
        setSpacing(10);

        if (title != null && !title.isBlank()) {
            Label lbl = new Label(title);
            lbl.getStyleClass().add("card-title");
            getChildren().add(lbl);
        }

        getChildren().addAll(children);
    }

    public SectionCard(Node... children) {
        this(null, children);
    }
}

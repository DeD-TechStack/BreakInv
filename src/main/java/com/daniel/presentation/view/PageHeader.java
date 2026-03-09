package com.daniel.presentation.view;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Reusable page header: title (h1) + optional subtitle.
 * Use inside any page's root VBox as the first element.
 */
public final class PageHeader extends VBox {

    public PageHeader(String title) {
        this(title, null);
    }

    public PageHeader(String title, String subtitle) {
        getStyleClass().add("page-header");
        setSpacing(4);

        Label h1 = new Label(title);
        h1.getStyleClass().addAll("h1", "page-title");
        getChildren().add(h1);

        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("page-subtitle");
            getChildren().add(sub);
        }
    }
}

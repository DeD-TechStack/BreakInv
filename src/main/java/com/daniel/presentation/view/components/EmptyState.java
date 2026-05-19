package com.daniel.presentation.view.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Factory for the standard BreakInv empty-state placeholder.
 * Wraps the repeated VBox+icon+title+hint pattern used as table placeholders
 * and section fallbacks across pages.
 */
public final class EmptyState {

    private EmptyState() {}

    /** Standard empty state: icon (text/emoji), title, hint. */
    public static VBox of(String icon, String title, String hint) {
        return of(icon, title, hint, false);
    }

    /** Standard empty state with optional hint text wrapping (for longer hint strings). */
    public static VBox of(String icon, String title, String hint, boolean wrapHint) {
        VBox box = new VBox(8);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        Label iconLbl = new Label(icon);
        iconLbl.getStyleClass().add("empty-icon");
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("empty-title");
        Label hintLbl = new Label(hint);
        hintLbl.getStyleClass().add("empty-hint");
        if (wrapHint) hintLbl.setWrapText(true);
        box.getChildren().addAll(iconLbl, titleLbl, hintLbl);
        return box;
    }
}

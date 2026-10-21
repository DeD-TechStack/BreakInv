package com.daniel.presentation.view.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

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
        Label iconLbl = new Label(icon);
        iconLbl.getStyleClass().add("empty-icon");
        return build(iconLbl, title, hint, wrapHint);
    }

    /** Empty state drawn with the app's Feather icon set instead of a platform emoji. */
    public static VBox of(Feather icon, String title, String hint, boolean wrapHint) {
        FontIcon glyph = new FontIcon(icon);
        glyph.getStyleClass().add("empty-glyph"); // not .empty-icon: its font size would replace the icon font
        return build(glyph, title, hint, wrapHint);
    }

    /** Retells a state built here: one area can say different things (R2-08: why a chart has no line). */
    public static void setText(VBox state, String title, String hint) {
        for (Node n : state.getChildren()) {
            if (n instanceof Label l && l.getStyleClass().contains("empty-title")) l.setText(title);
            if (n instanceof Label l && l.getStyleClass().contains("empty-hint")) l.setText(hint);
        }
    }

    private static VBox build(Node iconNode, String title, String hint, boolean wrapHint) {
        VBox box = new VBox(8);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("empty-title");
        // V5 (r1): at the expanded-sidebar minimum "Nenhum lançamento neste período" was cut; a
        // state title wraps like its hint instead of losing words.
        titleLbl.setWrapText(true);
        titleLbl.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Label hintLbl = new Label(hint);
        hintLbl.getStyleClass().add("empty-hint");
        hintLbl.setTextAlignment(javafx.scene.text.TextAlignment.CENTER); // R2-14: wrapped lines centre like the title
        if (wrapHint) hintLbl.setWrapText(true);
        box.getChildren().addAll(iconNode, titleLbl, hintLbl);
        return box;
    }
}

package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Motion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Typographic hero: a small caption, one large value and an optional secondary line (e.g. the
 * gain) plus a context line (date, price origin). The number is the hierarchy, not a card.
 */
public final class HeroValue extends VBox {

    private final Label caption = new Label();
    private final Label value = new Label("—");
    private final Label secondary = new Label();
    private final Label context = new Label();
    private final FlowPane valueRow = new FlowPane();
    private final HBox trailing = new HBox(8);

    public HeroValue(String captionText) {
        getStyleClass().add("hero-value");
        setSpacing(4);
        caption.setText(captionText);
        caption.getStyleClass().add("hero-caption");
        value.getStyleClass().addAll("hero-number", "num");
        secondary.getStyleClass().addAll("hero-secondary", "num");
        secondary.setVisible(false);
        secondary.setManaged(false);
        context.getStyleClass().add("hero-context");
        context.setWrapText(true);
        valueRow.setHgap(14);
        valueRow.setVgap(2);
        valueRow.setAlignment(Pos.BOTTOM_LEFT);
        valueRow.setRowValignment(javafx.geometry.VPos.BOTTOM);
        valueRow.getChildren().addAll(value, secondary, trailing);
        trailing.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(caption, valueRow, context);
    }

    public String caption() {
        return caption.getText();
    }

    public Label valueLabel() {
        return value;
    }

    /** Assigns at once; the pulse only highlights the change. */
    public void setValue(String text) {
        Motion.animateLabelChange(value, text);
    }

    /** Secondary line beside the number (e.g. "+ R$ 50,00"); {@code tone} is "pos", "neg", "muted" or null. */
    public void setSecondary(String text, String tone) {
        boolean show = text != null && !text.isBlank();
        secondary.setVisible(show);
        secondary.setManaged(show);
        secondary.getStyleClass().removeAll("pos", "neg", "muted");
        if (tone != null) secondary.getStyleClass().add(tone);
        Motion.animateLabelChange(secondary, show ? text : "");
    }

    public void setContext(String text) {
        context.setText(text == null ? "" : text);
        boolean show = text != null && !text.isBlank();
        context.setVisible(show);
        context.setManaged(show);
    }

    /** Small nodes placed after the value (chips, an inline action). */
    public void setTrailing(Node... nodes) {
        trailing.getChildren().setAll(nodes);
    }
}

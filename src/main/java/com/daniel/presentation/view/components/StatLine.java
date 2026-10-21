package com.daniel.presentation.view.components;

import com.daniel.presentation.view.layout.LineFlow;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The figures of a {@link StatStrip} read on one line ("Caixa R$ 1.234,56 | Custo … | Ativos 12"),
 * for a page that puts its summary in the title band. It mirrors the strip — text, tone and the
 * qualifier kept apart from the number — so the page keeps one source of values and switches between
 * the line and the strip by breakpoint. Items move to another line whole (LineFlow): never cut.
 */
public final class StatLine extends LineFlow {

    private static final List<String> TONES = List.of("pos", "neg", "muted");
    private final Map<String, Label> values = new LinkedHashMap<>();

    public StatLine(StatStrip source) {
        super(0, 4);
        getStyleClass().add("stat-line");
        List<String> labels = source.labels();
        for (int i = 0; i < labels.size(); i++) {
            String key = labels.get(i);
            Label caption = new Label(key);
            caption.getStyleClass().add("stat-label");
            caption.setMinWidth(USE_PREF_SIZE);
            Label sourceValue = source.valueLabel(key);
            Label value = new Label();
            value.getStyleClass().addAll("stat-value", "num");
            value.setMinWidth(USE_PREF_SIZE);
            value.textProperty().bind(sourceValue.textProperty());
            mirrorTone(sourceValue, value);
            Label sourceQualifier = source.qualifierLabel(key);
            Label qualifier = new Label();
            qualifier.getStyleClass().add("stat-qualifier");
            qualifier.setMinWidth(USE_PREF_SIZE);
            qualifier.textProperty().bind(sourceQualifier.textProperty());
            qualifier.visibleProperty().bind(sourceQualifier.visibleProperty());
            qualifier.managedProperty().bind(sourceQualifier.managedProperty());
            HBox item = new HBox(6, caption, value, qualifier);
            item.setAlignment(Pos.BASELINE_LEFT);
            item.setMinWidth(USE_PREF_SIZE);
            item.getStyleClass().add("stat-line-item");
            if (i == labels.size() - 1) item.getStyleClass().add("last");
            item.setAccessibleRole(AccessibleRole.TEXT);
            item.accessibleTextProperty().bind(Bindings.createStringBinding(
                    () -> key + ": " + value.getText()
                            + (qualifier.isVisible() && !qualifier.getText().isBlank() ? " (" + qualifier.getText() + ")" : ""),
                    value.textProperty(), qualifier.textProperty(), qualifier.visibleProperty()));
            values.put(key, value);
            getChildren().add(item);
        }
    }

    public Label valueLabel(String key) {
        return values.get(key);
    }

    /** The strip sets its tone by style class ("pos", "neg", "muted"); the line follows every change. */
    private static void mirrorTone(Label from, Label to) {
        Runnable copy = () -> {
            to.getStyleClass().removeAll(TONES);
            for (String tone : TONES) if (from.getStyleClass().contains(tone)) to.getStyleClass().add(tone);
        };
        from.getStyleClass().addListener((ListChangeListener<String>) change -> copy.run());
        copy.run();
    }
}

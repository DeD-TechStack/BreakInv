package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Motion;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Factory for the two KPI card variants used across BreakInv pages.
 *
 * hero()     — .hero-card with icon header + hover lift (Dashboard)
 * compact()  — .kpi-card that grows horizontally (Reports)
 * analysis() — .kpi-card in GridPane context (Asset Analysis)
 */
public final class KpiCard {

    private KpiCard() {}

    /** Dashboard hero card: icon + title header, hover lift, grows horizontally. */
    public static VBox hero(Node icon, String title, Label value) {
        VBox box = new VBox(6);
        box.getStyleClass().add("hero-card");

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("kpi-label");
        header.getChildren().addAll(icon, titleLbl);

        value.getStyleClass().addAll("kpi-value", "num");
        box.getChildren().addAll(header, value);

        HBox.setHgrow(box, Priority.ALWAYS);
        Motion.hoverLift(box);
        return box;
    }

    /** Reports/toolbar compact card: title + value, grows horizontally. */
    public static VBox compact(String title, Label value) {
        VBox b = new VBox(6);
        b.getStyleClass().add("kpi-card");
        Label t = new Label(title);
        t.getStyleClass().add("kpi-label");
        value.getStyleClass().addAll("kpi-value", "num");
        b.getChildren().addAll(t, value);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    /** Analysis grid card: uppercase title, explicit padding, no hGrow (GridPane context). */
    public static VBox analysis(String title, Label value) {
        Label lbl = new Label(title.toUpperCase());
        lbl.getStyleClass().add("kpi-label");
        value.getStyleClass().add("kpi-value");
        VBox card = new VBox(4, lbl, value);
        card.getStyleClass().add("kpi-card");
        card.setPadding(new Insets(10, 14, 10, 14));
        return card;
    }
}

package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextual details for the selected item: title, badges, a headline value, primary actions,
 * a label/value list and advanced actions. Placed beside the table when wide, below it when
 * narrow (the page decides). Esc closes it and the page returns focus to the table.
 */
public final class DetailsPane extends VBox {

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final HBox badges = new HBox(6);
    private final Label headline = new Label();
    private final Label headlineHint = new Label();
    private final FlowPane primaryActions = new FlowPane(8, 8);
    private final GridPane fields = new GridPane();
    private final Label note = new Label();
    private final FlowPane advancedActions = new FlowPane(8, 6);
    private final Button closeBtn = new Button(null, Icons.close());
    private final List<String> fieldLabels = new ArrayList<>();
    private Runnable onClose;

    public DetailsPane() {
        getStyleClass().add("details-pane");
        setSpacing(12);
        setVisible(false);
        setManaged(false);

        title.getStyleClass().add("details-name");
        title.setWrapText(true);
        subtitle.getStyleClass().add("details-ticker");
        subtitle.setVisible(false);
        subtitle.setManaged(false);
        closeBtn.getStyleClass().add("icon-btn");
        closeBtn.setTooltip(new Tooltip("Fechar detalhes (Esc)"));
        closeBtn.setAccessibleText("Fechar detalhes");
        closeBtn.setOnAction(e -> requestClose());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox titles = new VBox(2, title, subtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox head = new HBox(8, titles, spacer, closeBtn);
        head.setAlignment(Pos.TOP_LEFT);

        headline.getStyleClass().addAll("details-value", "num");
        headlineHint.getStyleClass().add("details-hint");
        headlineHint.setWrapText(true);
        VBox headlineBox = new VBox(2, headline, headlineHint);

        primaryActions.getStyleClass().add("details-actions");
        fields.getStyleClass().add("details-fields");
        fields.setHgap(12);
        fields.setVgap(6);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setHgrow(Priority.NEVER);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setHalignment(javafx.geometry.HPos.RIGHT);
        fields.getColumnConstraints().addAll(c0, c1);

        note.getStyleClass().add("details-note");
        note.setWrapText(true);
        note.setVisible(false);
        note.setManaged(false);

        Label advTitle = new Label("Avançado");
        advTitle.getStyleClass().add("details-section");
        VBox advanced = new VBox(6, advTitle, advancedActions);
        advanced.getStyleClass().add("details-advanced");

        getChildren().addAll(head, badges, headlineBox, primaryActions, fields, note, advanced);

        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) { requestClose(); e.consume(); }
        });
    }

    public void setOnClose(Runnable r) {
        this.onClose = r;
    }

    public void setTitle(String text, String sub) {
        title.setText(text);
        boolean show = sub != null && !sub.isBlank();
        subtitle.setText(show ? sub : "");
        subtitle.setVisible(show);
        subtitle.setManaged(show);
    }

    public void setBadges(Node... nodes) {
        badges.getChildren().setAll(nodes);
    }

    public void setHeadline(String value, String hint) {
        Motion.animateLabelChange(headline, value == null ? "—" : value);
        headlineHint.setText(hint == null ? "" : hint);
        headlineHint.setVisible(hint != null && !hint.isBlank());
        headlineHint.setManaged(hint != null && !hint.isBlank());
    }

    public void setPrimaryActions(ButtonBase... buttons) {
        primaryActions.getChildren().setAll(buttons);
    }

    /**
     * Width the primary actions take on one row, this pane's insets included: each button at its
     * preferred width plus the row's gaps, snapped as the FlowPane lays them out. 0 when empty.
     */
    public double primaryActionsRowWidth() {
        double sum = 0;
        int managed = 0;
        for (Node n : primaryActions.getChildren()) {
            if (!n.isManaged()) continue;
            sum += primaryActions.snapSizeX(Math.max(n.minWidth(-1), n.prefWidth(-1)));
            managed++;
        }
        if (managed == 0) return 0;
        return snappedLeftInset() + snappedRightInset() + primaryActions.snappedLeftInset() + primaryActions.snappedRightInset()
                + sum + (managed - 1) * primaryActions.snapSpaceX(primaryActions.getHgap());
    }

    public void setAdvancedActions(ButtonBase... buttons) {
        advancedActions.getChildren().setAll(buttons);
        advancedActions.getParent().setVisible(buttons.length > 0);
        advancedActions.getParent().setManaged(buttons.length > 0);
    }

    public void clearFields() {
        fields.getChildren().clear();
        fieldLabels.clear();
    }

    /** Appends a label/value row; {@code valueNode} may be a Label or a badge. */
    public void addField(String label, Node valueNode) {
        int row = fieldLabels.size();
        Label l = new Label(label);
        l.getStyleClass().add("details-field-label");
        if (valueNode instanceof Label vl) vl.getStyleClass().add("details-field-value");
        // A badge keeps its own width, so the column's right alignment places it with the other values.
        else GridPane.setFillWidth(valueNode, false);
        fields.add(l, 0, row);
        fields.add(valueNode, 1, row);
        fieldLabels.add(label);
    }

    public void addField(String label, String value) {
        Label v = new Label(value == null ? "—" : value);
        v.setWrapText(true);
        addField(label, v);
    }

    public void setNote(String text) {
        boolean show = text != null && !text.isBlank();
        note.setText(show ? text : "");
        note.setVisible(show);
        note.setManaged(show);
    }

    public List<String> fieldLabels() {
        return new ArrayList<>(fieldLabels);
    }

    public List<String> actionLabels() {
        List<String> out = new ArrayList<>();
        for (Node n : primaryActions.getChildren()) if (n instanceof ButtonBase b) out.add(b.getText());
        for (Node n : advancedActions.getChildren()) if (n instanceof ButtonBase b) out.add(b.getText());
        return out;
    }

    /** Shows with a slide (horizontal when beside the table, vertical when below). */
    public void open(boolean beside) {
        boolean wasVisible = isVisible();
        setVisible(true);
        setManaged(true);
        if (!wasVisible) {
            if (beside) Motion.slideInX(this, Motion.PANEL_MS, 24);
            else Motion.fadeSlideIn(this, Motion.PANEL_MS, 16);
        }
    }

    /** The USER closes the pane (Esc / X): hides it and tells the page, which may move the focus. */
    public void requestClose() {
        if (!isVisible()) return;
        hide();
        if (onClose != null) onClose.run();
    }

    /**
     * The DATA closed the pane (the item left the list after a filter or a refresh): hides it
     * without the close callback, so the keyboard focus stays wherever the user is working.
     */
    public void hide() {
        if (!isVisible()) return;
        Motion.settle(this);
        setVisible(false);
        setManaged(false);
    }
}

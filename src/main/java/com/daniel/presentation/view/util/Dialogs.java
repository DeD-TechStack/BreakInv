package com.daniel.presentation.view.util;

import com.daniel.presentation.view.components.ToastHost;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;

import java.util.Optional;

public final class Dialogs {
    private Dialogs() {}

    /** Width of the shared dialog frame. */
    static final double FRAME_WIDTH = 440;
    /**
     * Width a wrapped dialog text is measured at. The window takes its height from the dialog's
     * preferred height asked for no width, and a wrapped Label measured without a width reports a
     * single line, so a long message got one line of room and a scrollbar. JavaFX's own dialog
     * text has a fixed preferred width for the same reason. The frame leaves 396 px for text
     * (pane, scroll and body paddings); 4 px of slack keeps the measure valid when rounded.
     */
    static final double TEXT_WIDTH = FRAME_WIDTH - 48;

    private static void applyTheme(Alert a) {
        Label message = new Label(a.getContentText());
        message.setWrapText(true);
        message.setMinWidth(0);
        message.setPrefWidth(TEXT_WIDTH);
        applySimpleFrame(a, message);
    }

    private static void applyTheme(TextInputDialog d) {
        // Keep the original editor and its result converter; only move its containing grid.
        javafx.scene.Node content = d.getDialogPane().getContent();
        d.getDialogPane().setContent(null);
        applySimpleFrame(d, content);
    }

    private static void applySimpleFrame(Dialog<?> d, javafx.scene.Node content) {
        VBox body = new VBox(8);
        body.getStyleClass().add("dialog-confirm-body");
        if (d.getHeaderText() != null && !d.getHeaderText().isBlank()) {
            Label question = new Label(d.getHeaderText());
            question.setWrapText(true);
            question.setPrefWidth(TEXT_WIDTH);
            question.getStyleClass().add("dialog-question");
            body.getChildren().add(question);
        }
        if (content != null) body.getChildren().add(content);
        d.setHeaderText(null);
        d.setGraphic(null);
        d.getDialogPane().getStylesheets().add(Dialogs.class.getResource("/styles/app.css").toExternalForm());
        d.getDialogPane().getStyleClass().add("dark-dialog");
        DialogChrome.frame(d, d.getTitle(), body, () -> true);
        d.getDialogPane().setPrefWidth(FRAME_WIDTH);
        for (ButtonType type : d.getDialogPane().getButtonTypes()) {
            d.getDialogPane().lookupButton(type).getStyleClass().add(type.getButtonData().isCancelButton() ? "ghost-btn" : "primary-btn");
        }
        d.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWING, e -> ToastHost.showDim());
        d.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_HIDDEN, e -> ToastHost.hideDim());
    }

    public static Alert buildAlert(Alert.AlertType type, String title, String header, String message, javafx.stage.Window owner) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        if (owner != null) alert.initOwner(owner);
        applyTheme(alert);
        return alert;
    }

    public static void info(String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title == null ? "Info" : title);
        a.setHeaderText(null);
        a.setContentText(message == null ? "" : message);
        applyTheme(a);
        a.showAndWait();
    }

    public static void info(String message) {
        info("Info", message);
    }

    public static void error(String message) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Erro");
        a.setHeaderText(null);
        a.setContentText(message == null ? "Ocorreu um erro." : message);
        applyTheme(a);
        a.showAndWait();
    }

    public static boolean confirm(String title, String header, String message) {
        return confirm(title, header, message, null);
    }

    /**
     * Confirmation in the shared dialog frame. {@code actionLabel} names what the primary button
     * does ("Excluir", "Arquivar"); null falls back to "Confirmar". Cancel is always offered.
     */
    public static boolean confirm(String title, String header, String message, String actionLabel) {
        Optional<ButtonType> r = buildConfirm(title, header, message, actionLabel).showAndWait();
        return r.isPresent() && r.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    public static boolean confirm(String title, String message) {
        return confirm(title, null, message);
    }

    /** Builds (does not show) the confirmation dialog, so its structure can be inspected. */
    public static Dialog<ButtonType> buildConfirm(String title, String header, String message, String actionLabel) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title == null ? "Confirmar" : title);
        try {
            d.getDialogPane().getStylesheets().add(Dialogs.class.getResource("/styles/app.css").toExternalForm());
        } catch (Exception ignored) {}
        d.getDialogPane().getStyleClass().add("dark-dialog");

        VBox body = new VBox(8);
        body.getStyleClass().add("dialog-confirm-body");
        if (header != null && !header.isBlank()) {
            Label question = new Label(header);
            question.getStyleClass().add("dialog-question");
            question.setWrapText(true);
            question.setPrefWidth(TEXT_WIDTH);
            body.getChildren().add(question);
        }
        Label consequence = new Label(message == null ? "" : message);
        consequence.getStyleClass().add("text-secondary");
        consequence.setWrapText(true);
        consequence.setPrefWidth(TEXT_WIDTH);
        body.getChildren().add(consequence);

        String label = actionLabel == null || actionLabel.isBlank() ? "Confirmar" : actionLabel;
        ButtonType primary = new ButtonType(label, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(primary, cancel);
        DialogChrome.frame(d, d.getTitle(), body, () -> true);
        d.getDialogPane().setPrefWidth(FRAME_WIDTH);

        Button primaryBtn = (Button) d.getDialogPane().lookupButton(primary);
        boolean destructive = label.toLowerCase().startsWith("excluir") || label.toLowerCase().startsWith("apagar");
        primaryBtn.getStyleClass().add(destructive ? "danger-btn" : "primary-btn");
        Button cancelBtn = (Button) d.getDialogPane().lookupButton(cancel);
        cancelBtn.getStyleClass().add("ghost-btn");
        cancelBtn.setCancelButton(true);
        // Enter confirms a reversible action; on a destructive one (Excluir/Apagar) Enter = Cancelar,
        // so a key held from the previous screen never deletes anything. Deleting takes a deliberate click/Space.
        primaryBtn.setDefaultButton(!destructive);
        cancelBtn.setDefaultButton(destructive);

        d.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWN, e -> ToastHost.showDim());
        d.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_HIDDEN, e -> ToastHost.hideDim());
        return d;
    }

    public static Long askAmountCents(String title, String header) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle(title);
        d.setHeaderText(header);
        d.setContentText("Valor (ex: 1234,56):");
        applyTheme(d);

        return d.showAndWait()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Dialogs::parseToCents)
                .orElse(null);
    }

    public static String askText(String title, String label) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(label);
        applyTheme(d);
        return d.showAndWait().map(String::trim).orElse(null);
    }

    private static long parseToCents(String input) {
        String s = input.replace("R$", "").trim();
        if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
        else if (s.contains(",")) s = s.replace(",", ".");
        double v = Double.parseDouble(s);
        return Math.round(v * 100.0);
    }
}

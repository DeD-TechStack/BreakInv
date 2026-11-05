package com.daniel.presentation.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/**
 * The content of the window shown when the data cannot be opened (BRK-31 u4c, U4C:RF-02): the reason, the data file,
 * the folder of the copies and the buttons "Tentar de novo" and "Fechar". It only shows; what the buttons do is set
 * with {@link #setOnRetry} and {@link #setOnClose}.
 */
public final class OpeningFailurePane extends VBox {

    public static final double WIDTH = 520;

    private static final String TITLE = "Não foi possível abrir os dados do BreakInv";
    private static final String TRYING = "Abrindo os dados…";
    private static final String GUIDANCE = "Os dados não foram apagados nem substituídos. Corrija o motivo e tente de novo.";

    private final Label reasonLabel = label("opening-failure-reason");
    private final Button retryButton = new Button("Tentar de novo");
    private final Button closeButton = new Button("Fechar");
    private Runnable onRetry = () -> { };
    private Runnable onClose = () -> { };

    /**
     * @param reason       the reason of the failure (U4C:RF-03)
     * @param dataFile     the data file, or null when there is none ("(não definido)")
     * @param copiesFolder the folder of the copies, or null to leave the line out
     */
    public OpeningFailurePane(String reason, Path dataFile, Path copiesFolder) {
        super(12);
        getStyleClass().add("page-root");
        setPadding(new Insets(24));
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label title = label("opening-failure-title");
        title.setText(TITLE);
        title.getStyleClass().addAll("text-lg", "text-strong");

        Label fileLabel = label("opening-failure-file");
        fileLabel.setText("Arquivo de dados: " + (dataFile == null ? "(não definido)" : dataFile.toAbsolutePath().toString()));

        Label guidance = label("opening-failure-guidance");
        guidance.setText(GUIDANCE);

        getChildren().addAll(title, reasonLabel, fileLabel);
        if (copiesFolder != null) {
            Label copiesLabel = label("opening-failure-copies");
            copiesLabel.setText("Cópias de segurança: " + copiesFolder.toAbsolutePath());
            getChildren().add(copiesLabel);
        }
        getChildren().add(guidance);

        retryButton.setId("opening-failure-retry");
        retryButton.getStyleClass().add("primary-btn");
        retryButton.setDefaultButton(true);
        retryButton.setOnAction(e -> onRetry.run());
        closeButton.setId("opening-failure-close");
        closeButton.getStyleClass().add("ghost-btn");
        closeButton.setCancelButton(true);
        closeButton.setOnAction(e -> onClose.run());
        HBox buttons = new HBox(8, retryButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(buttons);

        showReason(reason);
    }

    public void setOnRetry(Runnable onRetry) {
        this.onRetry = onRetry;
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** After a failure: the reason of this failure, "Tentar de novo" active. */
    public void showReason(String reason) {
        boolean ended = reason.endsWith(".") || reason.endsWith("!") || reason.endsWith("?") || reason.endsWith("…");
        reasonLabel.setText("Motivo: " + reason + (ended ? "" : "."));
        retryButton.setDisable(false);
    }

    /** During an attempt: only the line "Abrindo os dados…", "Tentar de novo" disabled, "Fechar" still active. */
    public void showTrying() {
        reasonLabel.setText(TRYING);
        retryButton.setDisable(true);
    }

    private static Label label(String id) {
        Label label = new Label();
        label.setId(id);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}

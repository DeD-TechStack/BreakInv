package com.daniel.presentation.view;

import com.daniel.presentation.view.util.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;

/**
 * The content of the window shown when the data cannot be opened (BRK-31 u4c, U4C:RF-02): the reason, the data file,
 * the folder of the copies and the buttons "Tentar de novo" and "Fechar". It only shows; what the buttons do is set
 * with {@link #setOnRetry} and {@link #setOnClose}.
 *
 * <p>The look follows the DESIGN.md (BRK-31 u4c-vis). Four direct children, 16 px apart, the last one 24 px below the
 * guidance: the title row (alert icon and title), the data group (reason, file and copies, 8 px apart), the guidance
 * and the button bar. Colors, the surface and the padding come from the {@code opening-failure-*} rules of
 * {@code app.css}; the spacing is set here.
 */
public final class OpeningFailurePane extends VBox {

    public static final double WIDTH = 520;

    private static final String TITLE = "Não foi possível abrir os dados do BreakInv";
    private static final String TRYING = "Abrindo os dados…";
    private static final String GUIDANCE = "Os dados não foram apagados nem substituídos. Corrija o motivo e tente de novo.";

    /** Between the groups of the pane (DESIGN.md "in-section"). */
    private static final double GROUP_GAP = 16;
    /** Between one line of the data group and the next (DESIGN.md "field"). */
    private static final double LINE_GAP = 8;
    /** Extra space above the button bar: {@link #GROUP_GAP} + this = 24 (DESIGN.md "between-sections"). */
    private static final double BEFORE_BUTTONS = 8;

    private final Label reasonLabel = label("opening-failure-reason", "opening-failure-reason");
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
        super(GROUP_GAP);
        getStyleClass().add("opening-failure-pane");
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label title = label("opening-failure-title", "h3");
        title.setText(TITLE);
        FontIcon alert = Icons.alertTriangle();
        alert.getStyleClass().add("opening-failure-icon");
        title.setGraphic(alert);
        title.setGraphicTextGap(8);
        HBox titleRow = new HBox(title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        Label fileLabel = label("opening-failure-file", "opening-failure-detail");
        fileLabel.setText("Arquivo de dados: " + (dataFile == null ? "(não definido)" : dataFile.toAbsolutePath().toString()));

        VBox data = new VBox(LINE_GAP, reasonLabel, fileLabel);
        if (copiesFolder != null) {
            Label copiesLabel = label("opening-failure-copies", "opening-failure-detail");
            copiesLabel.setText("Cópias de segurança: " + copiesFolder.toAbsolutePath());
            data.getChildren().add(copiesLabel);
        }

        Label guidance = label("opening-failure-guidance", "opening-failure-detail");
        guidance.setText(GUIDANCE);

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
        VBox.setMargin(buttons, new Insets(BEFORE_BUTTONS, 0, 0, 0));

        getChildren().addAll(titleRow, data, guidance, buttons);

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

    private static Label label(String id, String styleClass) {
        Label label = new Label();
        label.setId(id);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}

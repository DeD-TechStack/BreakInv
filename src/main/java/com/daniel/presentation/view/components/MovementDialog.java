package com.daniel.presentation.view.components;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.util.DialogChrome;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.MovementForm;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * One dialog for every ledger movement (deposit, withdrawal, buy, sell, adjustment, reversal).
 *
 * <ul>
 *   <li>The intention key is fixed per form content ({@link MovementForm}); the confirm button
 *       and inputs are disabled while the worker runs, so a double click never submits twice.</li>
 *   <li>The write runs on {@link UiExecutor} (never on the FX thread). A domain rejection is
 *       shown inline naming the field; the form stays open and editable (same key on retry with
 *       the same content).</li>
 *   <li>On success the dialog closes and {@code onSaved} receives the persisted row.</li>
 * </ul>
 */
public final class MovementDialog extends Dialog<Movement> {

    private final MovementForm form;
    private final DailyTrackingUseCase daily;
    private final Consumer<Movement> onSaved;
    private final Executor worker;
    private final Executor ui;

    private final DatePicker datePicker = new DatePicker(LocalDate.now());
    private final TextField quantityField = new TextField();
    private final TextField priceField = new TextField();
    private final TextField amountField = new TextField();
    private final ToggleGroup signGroup = new ToggleGroup();
    private final RadioButton inflowBtn = new RadioButton("Entrada (+)");
    private final RadioButton outflowBtn = new RadioButton("Saída (−)");
    private final ComboBox<AdjustmentClassification> classificationCombo = new ComboBox<>();
    private final TextField reasonField = new TextField();
    private final TextField noteField = new TextField();
    private final Label errorLabel = new Label();
    private final Label helperLabel = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Map<String, Control> fieldByName = new LinkedHashMap<>();
    private final ButtonType confirmType;

    public MovementDialog(String title, String subtitle, MovementForm form,
                          DailyTrackingUseCase daily, Consumer<Movement> onSaved) {
        this(title, subtitle, form, daily, onSaved, UiExecutor.get(), Platform::runLater);
    }

    /** Test seam: the executors the write runs on (worker) and answers on (ui). */
    MovementDialog(String title, String subtitle, MovementForm form,
                   DailyTrackingUseCase daily, Consumer<Movement> onSaved, Executor worker, Executor ui) {
        this.form = form;
        this.daily = daily;
        this.onSaved = onSaved;
        this.worker = worker;
        this.ui = ui;

        setTitle(title);
        setHeaderText(null);
        getDialogPane().getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        getDialogPane().getStyleClass().add("dark-dialog");
        getDialogPane().setMinWidth(520);

        confirmType = new ButtonType("Confirmar", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title-label");
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("icon-btn");
        closeBtn.setOnAction(e -> { if (!form.submitting()) close(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(titleLabel, spacer, closeBtn);
        headerRow.getStyleClass().add("dialog-header-row");
        DialogChrome.apply(this, headerRow);

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("text-helper");
            sub.setWrapText(true);
            content.getChildren().add(sub);
        }

        content.getChildren().add(field("Data", datePicker, "date"));
        datePicker.setMaxWidth(Double.MAX_VALUE);
        datePicker.valueProperty().addListener((o, a, v) -> form.setDate(v));
        form.setDate(datePicker.getValue());

        if (form.usesQuantity()) {
            quantityField.setPromptText("Quantidade inteira");
            quantityField.textProperty().addListener((o, a, v) -> form.setQuantityText(v));
            content.getChildren().add(field(form.isAdjustment() ? "Quantidade (opcional)" : "Quantidade", quantityField, "quantity"));
        }
        if (form.usesPrice()) {
            priceField.setPromptText("Preço unitário, ex.: 20,005");
            priceField.textProperty().addListener((o, a, v) -> form.setPriceText(v));
            content.getChildren().add(field("Preço unitário (R$)", priceField, "unitPrice"));
        }
        if (form.usesAmount()) {
            amountField.setPromptText("R$ 0,00");
            amountField.textProperty().addListener((o, a, v) -> form.setAmountText(v));
            String label = switch (form.kind()) {
                case BUY -> "Valor aplicado (R$)";
                case SELL -> "Valor resgatado ao custo (R$) — sem ganho inferido";
                case ADJUSTMENT -> form.investmentTypeId() == null ? "Valor (R$)" : "Variação de custo (R$)";
                default -> "Valor (R$)";
            };
            content.getChildren().add(field(label, amountField, "amount"));
        }
        if (form.isAdjustment()) {
            inflowBtn.setToggleGroup(signGroup);
            outflowBtn.setToggleGroup(signGroup);
            inflowBtn.setSelected(true);
            signGroup.selectedToggleProperty().addListener((o, a, v) -> form.setOutflow(v == outflowBtn));
            HBox signRow = new HBox(14, inflowBtn, outflowBtn);
            content.getChildren().add(field("Sentido", signRow, "sign"));

            classificationCombo.getItems().addAll(AdjustmentClassification.values());
            classificationCombo.setMaxWidth(Double.MAX_VALUE);
            classificationCombo.setPromptText("Classificação");
            classificationCombo.setButtonCell(classificationCell());
            classificationCombo.setCellFactory(lv -> classificationCell());
            classificationCombo.valueProperty().addListener((o, a, v) -> form.setClassification(v));
            content.getChildren().add(field("Classificação", classificationCombo, "classification"));
            Label clsHint = new Label("Só Rendimento/Despesa entram no ganho realizado; Correção e Outro apenas restabelecem saldo.");
            clsHint.getStyleClass().add("text-helper");
            clsHint.setWrapText(true);
            content.getChildren().add(clsHint);
        }
        if (form.isAdjustment() || form.isReversal()) {
            reasonField.setPromptText("Motivo (obrigatório)");
            reasonField.textProperty().addListener((o, a, v) -> form.setReason(v));
            content.getChildren().add(field("Motivo", reasonField, "reason"));
        }
        if (!form.isReversal()) {
            noteField.setPromptText("Observação (opcional)");
            noteField.textProperty().addListener((o, a, v) -> form.setNote(v));
            content.getChildren().add(field("Observação", noteField, "note"));
        }

        errorLabel.getStyleClass().add("warning-banner");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        helperLabel.getStyleClass().add("text-helper");
        helperLabel.setWrapText(true);
        helperLabel.setText("Intenção " + shortKey() + " — um clique, um lançamento.");
        busy.setMaxSize(18, 18);
        busy.setVisible(false);
        busy.setManaged(false);
        HBox footer = new HBox(8, busy, helperLabel);
        content.getChildren().addAll(errorLabel, footer);

        getDialogPane().setContent(new VBox(0, headerRow, content));

        showingProperty().addListener((obs, was, is) -> {
            if (is) ToastHost.showDim(); else ToastHost.hideDim();
        });

        setResultConverter(bt -> null);
        // Alt+F4 / OS close while the write is in flight: refused, the dialog stays until it answers.
        addEventFilter(DialogEvent.DIALOG_CLOSE_REQUEST, ev -> { if (form.submitting()) ev.consume(); });
        Button confirmBtn = (Button) getDialogPane().lookupButton(confirmType);
        confirmBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            submit();
        });
        Platform.runLater(() -> {
            confirmBtn.getStyleClass().add("primary-btn");
            confirmBtn.setMinWidth(120);
            ButtonBar.setButtonUniformSize(confirmBtn, false);
            Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancel != null) cancel.getStyleClass().add("ghost-btn");
            firstInput().requestFocus();
        });
    }

    TextField amountField() {
        return amountField;
    }

    private Control firstInput() {
        if (form.usesQuantity()) return quantityField;
        if (form.usesAmount()) return amountField;
        if (form.isReversal()) return reasonField;
        return datePicker;
    }

    private ListCell<AdjustmentClassification> classificationCell() {
        return new ListCell<>() {
            @Override protected void updateItem(AdjustmentClassification item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : classificationLabel(item));
            }
        };
    }

    public static String classificationLabel(AdjustmentClassification c) {
        return switch (c) {
            case CORRECTION -> "Correção (sem efeito no ganho)";
            case INCOME -> "Rendimento (entra no ganho realizado)";
            case EXPENSE -> "Despesa/tarifa (reduz o ganho realizado)";
            case OTHER -> "Outro (sem efeito no ganho)";
        };
    }

    private VBox field(String label, Node input, String name) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        if (input instanceof Control c) fieldByName.put(name, c);
        VBox box = new VBox(4, l, input);
        if (input instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private String shortKey() {
        String k = form.intentKey();
        return k.length() > 8 ? k.substring(0, 8) : k;
    }

    private void setBusy(boolean on) {
        busy.setVisible(on);
        busy.setManaged(on);
        getDialogPane().lookupButton(confirmType).setDisable(on);
        getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(on);
        for (Control c : fieldByName.values()) c.setDisable(on);
        inflowBtn.setDisable(on);
        outflowBtn.setDisable(on);
    }

    private void showError(LedgerException ex) {
        errorLabel.setText(ex.getMessage());
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        Control c = fieldByName.get(ex.field());
        if (c == null && "cash".equals(ex.field())) c = fieldByName.get("amount");
        if (c != null) c.requestFocus();
        DialogChrome.resizeToContent(this);
    }

    void submit() {
        if (form.submitting()) return;
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        MovementRequest req;
        try {
            req = form.build();
        } catch (LedgerException ex) {
            showError(ex);
            return;
        }
        form.markSubmitting();
        setBusy(true);
        helperLabel.setText("Gravando intenção " + shortKey() + "…");
        CompletableFuture.supplyAsync(() -> daily.movement(req), worker)
                .whenComplete((saved, err) -> ui.execute(() -> {
                    if (err == null) {
                        form.markSaved(); // setResult() closes the dialog itself; the close-request filter must let it through
                        setResult(saved);
                        if (onSaved != null) onSaved.accept(saved);
                        close();
                        return;
                    }
                    form.markFailed();
                    setBusy(false);
                    helperLabel.setText("Intenção " + shortKey() + " — corrija e confirme novamente; o mesmo conteúdo não duplica.");
                    Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
                    if (cause instanceof LedgerException le) {
                        showError(le);
                    } else {
                        errorLabel.setText("Falha ao gravar: " + cause.getMessage());
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                        DialogChrome.resizeToContent(this);
                    }
                }));
    }
}

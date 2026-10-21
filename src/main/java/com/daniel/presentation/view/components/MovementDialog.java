package com.daniel.presentation.view.components;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.util.DialogChrome;
import com.daniel.presentation.view.util.UiDates;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.MovementForm;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
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
    /** R2-19: each field's box, where its own message is shown. */
    private final Map<String, VBox> fieldBoxByName = new LinkedHashMap<>();
    private VBox formBox;
    private javafx.scene.Node footerRow;
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

        VBox content = new VBox(16);
        formBox = content;
        content.getStyleClass().add("movement-form");
        content.setPadding(new Insets(16));
        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().addAll("text-secondary", "movement-context");
            sub.setWrapText(true);
            sub.setMaxWidth(Double.MAX_VALUE); // a filled note spans the form like the fields (E-V2)
            content.getChildren().add(sub);
        }

        VBox financialFields = new VBox(12);
        financialFields.getStyleClass().add("movement-values");
        if (form.usesQuantity() || form.usesPrice() || form.usesAmount()) content.getChildren().add(financialFields);
        datePicker.setMaxWidth(Double.MAX_VALUE);
        UiDates.applyBrFormat(datePicker);
        datePicker.valueProperty().addListener((o, a, v) -> form.setDate(v));
        form.setDate(datePicker.getValue());

        if (form.usesQuantity()) {
            quantityField.setPromptText("Quantidade inteira");
            quantityField.textProperty().addListener((o, a, v) -> form.setQuantityText(v));
            financialFields.getChildren().add(field(form.isAdjustment() ? "Quantidade (opcional)" : "Quantidade", quantityField, "quantity"));
        }
        if (form.usesPrice()) {
            priceField.setPromptText("Preço unitário, ex.: 20,005");
            priceField.textProperty().addListener((o, a, v) -> form.setPriceText(v));
            financialFields.getChildren().add(field("Preço unitário (R$)", priceField, "unitPrice"));
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
            amountField.getStyleClass().add("movement-amount");
            financialFields.getChildren().add(field(label, amountField, "amount"));
        }
        content.getChildren().add(field("Data", datePicker, "date"));
        if (form.isAdjustment()) {
            inflowBtn.setToggleGroup(signGroup);
            outflowBtn.setToggleGroup(signGroup);
            inflowBtn.setSelected(true);
            signGroup.selectedToggleProperty().addListener((o, a, v) -> form.setOutflow(v == outflowBtn));
            HBox signRow = new HBox(14, inflowBtn, outflowBtn);
            financialFields.getChildren().add(field("Sentido", signRow, "sign"));

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
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        helperLabel.getStyleClass().add("text-helper");
        helperLabel.setWrapText(true);
        helperLabel.setText("Um clique, um lançamento: confirmar de novo com o mesmo conteúdo não duplica.");
        busy.setMaxSize(18, 18);
        busy.setVisible(false);
        busy.setManaged(false);
        HBox footer = new HBox(8, busy, helperLabel);
        footer.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        footerRow = footer;
        content.getChildren().addAll(errorLabel, footer);

        // Shared frame: header (title + close, draggable) and a body that scrolls on its own, so the
        // Confirmar/Cancelar bar stays reachable in a short window.
        DialogChrome.frame(this, title, content, () -> !form.submitting());

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
        fieldBoxByName.put(name, box);
        // a refused value stays marked until the field is edited
        if (input instanceof TextInputControl t) t.textProperty().addListener((o, a, v) -> t.getStyleClass().remove("field-error"));
        if (input instanceof ComboBoxBase<?> cb) cb.valueProperty().addListener((o, a, v) -> cb.getStyleClass().remove("field-error"));
        if (input instanceof DatePicker dp) dp.getEditor().textProperty().addListener((o, a, v) -> dp.getStyleClass().remove("field-error"));
        return box;
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
        String name = fieldByName.containsKey(ex.field()) ? ex.field() : "cash".equals(ex.field()) ? "amount" : null;
        showMessage(ex.getMessage(), name);
    }

    /**
     * R2-19: the message is a sentence, right under the field it is about (above the footer when it
     * is about no field), and the field stays marked until it is edited — "número inválido: abc"
     * came out lowercase in a banner ~188 px below Valor, with nothing on the field.
     */
    private void showMessage(String message, String fieldName) {
        errorLabel.setText(sentence(message));
        if (errorLabel.getParent() instanceof javafx.scene.layout.Pane p) p.getChildren().remove(errorLabel);
        VBox box = fieldName == null ? null : fieldBoxByName.get(fieldName);
        if (box != null) box.getChildren().add(errorLabel);
        else formBox.getChildren().add(Math.max(0, formBox.getChildren().indexOf(footerRow)), errorLabel);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        Control c = fieldName == null ? null : fieldByName.get(fieldName);
        if (c != null) {
            if (!c.getStyleClass().contains("field-error")) c.getStyleClass().add("field-error");
            c.requestFocus();
        }
        DialogChrome.resizeToContent(this);
    }

    /** A message as a sentence: capital first letter, final full stop. */
    static String sentence(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text.strip();
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        char last = s.charAt(s.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == ')' ? s : s + ".";
    }

    void submit() {
        if (form.submitting()) return;
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        fieldByName.values().forEach(c -> c.getStyleClass().remove("field-error"));
        MovementRequest req;
        LocalDate date = UiDates.commitEditor(datePicker);
        if (date == null) {
            showMessage("Informe uma data válida (dd/mm/aaaa ou aaaa-mm-dd).", "date");
            return;
        }
        form.setDate(date);
        try {
            req = form.build();
        } catch (LedgerException ex) {
            showError(ex);
            return;
        }
        form.markSubmitting();
        setBusy(true);
        helperLabel.setText("Gravando…");
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
                    helperLabel.setText("Corrija e confirme novamente; o mesmo conteúdo não duplica o lançamento.");
                    Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
                    if (cause instanceof LedgerException le) {
                        showError(le);
                    } else {
                        showMessage("Falha ao gravar: " + cause.getMessage(), null);
                    }
                }));
    }
}

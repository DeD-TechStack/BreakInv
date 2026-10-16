package com.daniel.presentation.view.components;

import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.util.MoneyFormat;
import com.daniel.presentation.view.util.DialogChrome;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.MovementForm;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Explicit onboarding of legacy state into the ledger. Two modes:
 * <ul>
 *   <li><b>Opening</b> — shows the proposal derived from the legacy registration (quantity ×
 *       purchase price, or invested value) and the reason it may be flagged; the user confirms
 *       or edits the figures. Nothing from the legacy {@code transactions} table is replayed.</li>
 *   <li><b>Reconciliation</b> — restates quantity/cost (or cash) with a mandatory reason,
 *       clearing the pending flag.</li>
 * </ul>
 * {@code investmentTypeId == null} means cash. Writes run on the worker; rejections are shown
 * inline and the form stays open.
 */
public final class LedgerRecognitionDialog extends Dialog<Boolean> {

    public enum Mode { OPENING, RECONCILIATION }

    private final DailyTrackingUseCase daily;
    private final Mode mode;
    private final Long investmentTypeId;
    private final PositionMode positionMode;
    private final OpeningRequest proposal;
    private final String intentKey = UUID.randomUUID().toString();

    private final DatePicker datePicker = new DatePicker(LocalDate.now());
    private final TextField quantityField = new TextField();
    private final TextField costField = new TextField();
    private final TextField reasonField = new TextField();
    private final Label errorLabel = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private final ButtonType confirmType = new ButtonType("Confirmar", ButtonBar.ButtonData.OK_DONE);
    private final Executor worker;
    private final Executor ui;
    private final Runnable onDone;
    private boolean submitting;

    /** Builds the dialog; the proposal (for OPENING) is computed on the worker before showing. */
    public static void open(Mode mode, Long investmentTypeId, String displayName, DailyTrackingUseCase daily, Runnable onDone) {
        String key = UUID.randomUUID().toString();
        CompletableFuture.supplyAsync(() -> {
            OpeningRequest proposal = null;
            PositionMode pm = null;
            if (mode == Mode.OPENING) {
                proposal = investmentTypeId == null
                        ? daily.proposeCashOpening(key, LocalDate.now())
                        : daily.proposeOpening(key, LocalDate.now(), investmentTypeId);
                pm = proposal.positionMode();
            } else if (investmentTypeId != null) {
                DailyTrackingUseCase.InvestmentStatus st = daily.investmentStatus(investmentTypeId);
                pm = st.mode();
                proposal = OpeningRequest.existingPosition(key, LocalDate.now(), investmentTypeId, pm,
                        pm == PositionMode.QUANTITY ? st.quantity() : null, st.costCents(), false, null);
            } else {
                proposal = OpeningRequest.cash(key, LocalDate.now(), daily.cashStatus().cashCents(), false, null);
            }
            return new Object[]{proposal, pm};
        }, UiExecutor.get()).whenComplete((arr, err) -> Platform.runLater(() -> {
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                ToastHost.showError("Não foi possível preparar o reconhecimento: " + c.getMessage());
                return;
            }
            OpeningRequest proposal = (OpeningRequest) arr[0];
            PositionMode pm = (PositionMode) arr[1];
            LedgerRecognitionDialog d = new LedgerRecognitionDialog(mode, investmentTypeId, displayName, pm, proposal, daily,
                    UiExecutor.get(), Platform::runLater, onDone);
            // onDone runs from the write's success path (see submit), not from the dialog result:
            // a window-close that slipped through could otherwise discard the result after the commit.
            d.showAndWait();
        }));
    }

    /** Test seam: builds the dialog (never shown) with the given executors and completion callback. */
    static LedgerRecognitionDialog forTest(Mode mode, Long investmentTypeId, String displayName, PositionMode positionMode,
                                           OpeningRequest proposal, DailyTrackingUseCase daily,
                                           Executor worker, Executor ui, Runnable onDone) {
        return new LedgerRecognitionDialog(mode, investmentTypeId, displayName, positionMode, proposal, daily, worker, ui, onDone);
    }

    TextField reasonField() {
        return reasonField;
    }

    private LedgerRecognitionDialog(Mode mode, Long investmentTypeId, String displayName, PositionMode positionMode,
                                    OpeningRequest proposal, DailyTrackingUseCase daily,
                                    Executor worker, Executor ui, Runnable onDone) {
        this.daily = daily;
        this.mode = mode;
        this.investmentTypeId = investmentTypeId;
        this.positionMode = positionMode;
        this.proposal = proposal;
        this.worker = worker;
        this.ui = ui;
        this.onDone = onDone;

        String title = (mode == Mode.OPENING ? "Reconhecer saldo de abertura" : "Reconciliar saldo") + " — " + displayName;
        setTitle(title);
        setHeaderText(null);
        getDialogPane().getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        getDialogPane().getStyleClass().add("dark-dialog");
        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title-label");
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("icon-btn");
        closeBtn.setOnAction(e -> { if (!submitting) close(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(titleLabel, spacer, closeBtn);
        headerRow.getStyleClass().add("dialog-header-row");
        DialogChrome.apply(this, headerRow);

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));

        Label intro = new Label(mode == Mode.OPENING
                ? "O ledger passa a ser a fonte de verdade a partir deste marco. Os lançamentos antigos NÃO são reproduzidos; "
                  + "confira a proposta derivada do cadastro e confirme ou corrija. Abertura não é aporte nem lucro."
                : "Informe o saldo correto. A reconciliação fica registrada com motivo e substitui a posição anterior sem apagar histórico.");
        intro.getStyleClass().add("text-helper");
        intro.setWrapText(true);
        content.getChildren().add(intro);

        if (proposal != null && proposal.reconciliationFlag()) {
            Label flag = new Label("Atenção: " + proposal.note() + ". O saldo ficará marcado como pendente até você reconciliar.");
            flag.getStyleClass().add("warning-banner");
            flag.setWrapText(true);
            content.getChildren().add(flag);
        } else if (proposal != null && proposal.note() != null) {
            Label n = new Label("Origem: " + proposal.note());
            n.getStyleClass().add("text-helper");
            n.setWrapText(true);
            content.getChildren().add(n);
        }

        datePicker.setMaxWidth(Double.MAX_VALUE);
        if (proposal != null) datePicker.setValue(proposal.date());
        content.getChildren().add(field("Data do marco", datePicker));

        if (investmentTypeId != null && positionMode == PositionMode.QUANTITY) {
            quantityField.setText(proposal != null && proposal.quantity() != null ? String.valueOf(proposal.quantity()) : "0");
            content.getChildren().add(field("Quantidade (inteira)", quantityField));
        }
        costField.setText(proposal != null ? MoneyFormat.brl(proposal.costCents()) : "R$ 0,00");
        content.getChildren().add(field(investmentTypeId == null ? "Saldo em caixa (R$)"
                : (positionMode == PositionMode.VALUE ? "Valor aplicado ao custo (R$)" : "Custo total da posição (R$)"), costField));

        if (mode == Mode.RECONCILIATION) {
            reasonField.setPromptText("Motivo (obrigatório), ex.: conferido no extrato da corretora");
            content.getChildren().add(field("Motivo", reasonField));
        }

        errorLabel.getStyleClass().add("warning-banner");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        busy.setMaxSize(18, 18);
        busy.setVisible(false);
        busy.setManaged(false);
        content.getChildren().addAll(errorLabel, busy);

        getDialogPane().setContent(new VBox(0, headerRow, content));
        showingProperty().addListener((obs, was, is) -> { if (is) ToastHost.showDim(); else ToastHost.hideDim(); });

        setResultConverter(bt -> null);
        // Alt+F4 / OS close while the write is in flight: refused, the dialog stays until it answers.
        addEventFilter(DialogEvent.DIALOG_CLOSE_REQUEST, ev -> { if (submitting) ev.consume(); });
        Button confirmBtn = (Button) getDialogPane().lookupButton(confirmType);
        confirmBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> { ev.consume(); submit(); });
        Platform.runLater(() -> {
            confirmBtn.getStyleClass().add("primary-btn");
            confirmBtn.setMinWidth(120);
            ButtonBar.setButtonUniformSize(confirmBtn, false);
            Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancel != null) cancel.getStyleClass().add("ghost-btn");
        });
    }

    private VBox field(String label, Control input) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        input.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, l, input);
    }

    private void setBusy(boolean on) {
        submitting = on;
        busy.setVisible(on);
        busy.setManaged(on);
        getDialogPane().lookupButton(confirmType).setDisable(on);
        getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(on);
        datePicker.setDisable(on);
        quantityField.setDisable(on);
        costField.setDisable(on);
        reasonField.setDisable(on);
    }

    private void fail(String message, Control focus) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        if (focus != null) focus.requestFocus();
        DialogChrome.resizeToContent(this);
    }

    void submit() {
        if (submitting) return;
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        LocalDate date = datePicker.getValue();
        if (date == null) { fail("informe a data", datePicker); return; }
        Long qty = null;
        if (investmentTypeId != null && positionMode == PositionMode.QUANTITY) {
            String q = quantityField.getText() == null ? "" : quantityField.getText().trim();
            if (!q.matches("\\d+")) { fail("quantidade deve ser um inteiro ≥ 0", quantityField); return; }
            qty = Long.parseLong(q);
        }
        long cents;
        try {
            BigDecimal v = com.daniel.presentation.viewmodel.MovementFormAccess.parse(costField.getText());
            cents = v.setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } catch (LedgerException | ArithmeticException e) {
            fail("valor inválido: " + costField.getText(), costField);
            return;
        }
        if (cents < 0) { fail("valor não pode ser negativo", costField); return; }
        String reason = reasonField.getText() == null ? "" : reasonField.getText().trim();
        if (mode == Mode.RECONCILIATION && reason.isBlank()) { fail("informe o motivo da reconciliação", reasonField); return; }

        final Long fqty = qty;
        final long fcents = cents;
        final boolean pendingOpening = proposal != null && proposal.reconciliationFlag();
        final String openingNote = "abertura confirmada pelo usuário"
                + (proposal != null && proposal.note() != null ? "; " + proposal.note() : "");
        setBusy(true);
        CompletableFuture.runAsync(() -> {
            if (mode == Mode.OPENING) {
                OpeningRequest req = investmentTypeId == null
                        ? OpeningRequest.cash(intentKey, date, fcents, pendingOpening, openingNote)
                        : OpeningRequest.existingPosition(intentKey, date, investmentTypeId, positionMode, fqty, fcents,
                                pendingOpening, openingNote);
                daily.registerOpening(req);
            } else {
                ReconciliationRequest req = investmentTypeId == null
                        ? ReconciliationRequest.cash(intentKey, date, fcents, reason)
                        : ReconciliationRequest.position(intentKey, date, investmentTypeId, fqty, fcents, reason);
                daily.reconcile(req);
            }
        }, worker).whenComplete((v, err) -> ui.execute(() -> {
            if (err == null) {
                submitting = false;
                setResult(Boolean.TRUE);
                close();
                if (onDone != null) onDone.run();
                return;
            }
            setBusy(false);
            Throwable c = err.getCause() != null ? err.getCause() : err;
            fail(c.getMessage(), null);
        }));
    }
}

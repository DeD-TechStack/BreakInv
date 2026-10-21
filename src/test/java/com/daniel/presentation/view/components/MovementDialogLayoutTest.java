package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.viewmodel.MovementForm;

import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementDialogLayoutTest {
    @Test
    void adjustmentDirectionStaysWithTheValueItQualifies() {
        FxTestSupport.runAndWait(() -> {
            for (var form : java.util.List.of(MovementForm.adjustCash(), MovementForm.adjustPosition(7,
                    com.daniel.core.domain.ledger.PositionMode.QUANTITY))) {
                var dialog = new MovementDialog("Ajuste", "Corrige o saldo registrado.", form,
                        FxStubs.emptyDaily(), m -> {}, Runnable::run, Runnable::run);
                var pane = dialog.getDialogPane();
                pane.resize(520, 760); pane.applyCss(); pane.layout();
                var values = pane.lookup(".movement-values");
                var date = pane.lookup(".date-picker");
                var sign = pane.lookup(".radio-button");
                assertNotNull(sign);
                boolean inside = false;
                for (var parent = sign.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent == values) inside = true;
                }
                assertTrue(inside, "Entrada/Saída qualifies the amount, not date metadata");
                assertTrue(sign.localToScene(sign.getLayoutBounds()).getMinY()
                        < date.localToScene(date.getLayoutBounds()).getMinY());
            }
        });
    }

    @Test
    void amountIsThePrimaryFieldAndDateStaysWithTheRecordMetadata() {
        FxTestSupport.runAndWait(() -> {
            var dialog = new MovementDialog("Aporte externo", "Aumenta o caixa; não é lucro.",
                    MovementForm.deposit(), FxStubs.emptyDaily(), m -> {}, Runnable::run, Runnable::run);
            var pane = dialog.getDialogPane();

            pane.resize(520, 620); pane.applyCss(); pane.layout();
            var date = (DatePicker) pane.lookup(".date-picker");
            assertNotNull(date);
            var amount = dialog.amountField();
            assertTrue(amount.localToScene(amount.getBoundsInLocal()).getMinY()
                    < date.localToScene(date.getBoundsInLocal()).getMinY(),
                    "financial input leads; date is supporting record metadata");
            assertNotNull(pane.lookup(".movement-context"), "financial meaning remains permanently visible");
            assertTrue(pane.lookupAll(".label").stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).anyMatch(l -> "Aumenta o caixa; não é lucro.".equals(l.getText())));
        });
    }

    /**
     * C04: what (context), how much (amount), when (date and note on one record line): a shorter
     * form that reads in that order and still ends where the fields end.
     */
    @Test
    void dateAndNoteShareOneRecordLineUnderTheAmount() {
        FxTestSupport.runAndWait(() -> {
            var dialog = new MovementDialog("Aporte externo", "Aumenta o caixa; não é lucro.",
                    MovementForm.deposit(), FxStubs.emptyDaily(), m -> {}, Runnable::run, Runnable::run);
            var pane = dialog.getDialogPane();
            pane.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            pane.resize(520, 620);
            for (int pass = 0; pass < 4; pass++) { pane.applyCss(); pane.layout(); }
            var date = (DatePicker) pane.lookup(".date-picker");
            var note = pane.lookupAll(".text-field").stream()
                    .filter(n -> n instanceof javafx.scene.control.TextField t && "Observação (opcional)".equals(t.getPromptText()))
                    .findFirst().orElseThrow();
            var amount = dialog.amountField();
            var a = amount.localToScene(amount.getLayoutBounds());
            var d = date.localToScene(date.getLayoutBounds());
            var n = note.localToScene(note.getLayoutBounds());
            assertEquals(d.getMinY(), n.getMinY(), 1, "date and note share one record line");
            assertTrue(n.getMinX() > d.getMaxX(), "the note follows the date");
            assertTrue(d.getMinY() > a.getMaxY(), "the record line follows the amount");
            assertEquals(a.getMaxX(), n.getMaxX(), 1, "the line ends where the fields end");
            assertTrue(date.getWidth() + 0.5 >= date.prefWidth(-1), "the date keeps its whole text: " + date.getWidth());
        });
    }

    /** E-V2: the context note and the error banner span the form, with the same right edge as the fields. */
    @Test
    void contextAndErrorBannerSpanTheFormWidth() {
        FxTestSupport.runAndWait(() -> {
            var dialog = new MovementDialog("Aporte externo", "Aumenta o caixa; não é lucro.",
                    MovementForm.deposit(), FxStubs.emptyDaily(), m -> {}, Runnable::run, Runnable::run);
            var pane = dialog.getDialogPane();
            pane.resize(520, 620);
            assertEquals(java.util.List.of(),
                    FxTestSupport.messageBoxesShortOfTheirContainer(pane, ".movement-context", ".warning-banner"));
            var context = pane.lookup(".movement-context");
            var amount = dialog.amountField();
            assertEquals(amount.localToScene(amount.getLayoutBounds()).getMaxX(),
                    context.localToScene(context.getLayoutBounds()).getMaxX(), 1.0, "context ends where the fields end");
        });
    }

    /**
     * R2-19: "número inválido: abc" came out lowercase and raw, in a banner under Data and Observação
     * (~188 px from Valor), with no mark on the field. The message is a sentence that says what to
     * type, right under the field it is about; the field is marked until it is edited.
     */
    @Test
    void anInvalidAmountIsExplainedRightUnderTheAmountField() {
        FxTestSupport.runAndWait(() -> {
            var dialog = new MovementDialog("Aporte externo", "Aumenta o caixa; não é lucro.",
                    MovementForm.deposit(), FxStubs.emptyDaily(), m -> {}, Runnable::run, Runnable::run);
            var pane = dialog.getDialogPane();
            pane.resize(520, 620);
            pane.applyCss();
            pane.layout();
            var amount = dialog.amountField();
            amount.setText("abc");
            dialog.submit();
            pane.applyCss();
            pane.layout();
            var error = (javafx.scene.control.Label) pane.lookupAll(".warning-banner").stream()
                    .filter(n -> n.isVisible() && n.isManaged() && n instanceof javafx.scene.control.Label l && !l.getText().isBlank())
                    .findFirst().orElseThrow();
            String text = error.getText();
            assertTrue(Character.isUpperCase(text.charAt(0)), "a sentence: " + text);
            assertFalse(text.contains("número inválido"), text);
            assertTrue(text.contains("1.234,56"), "says what to type: " + text);
            var a = amount.localToScene(amount.getLayoutBounds());
            var e = error.localToScene(error.getLayoutBounds());
            assertTrue(e.getMinY() >= a.getMaxY() - 0.5 && e.getMinY() - a.getMaxY() <= 12,
                    "right under Valor: " + (e.getMinY() - a.getMaxY()) + " px");
            assertTrue(amount.getStyleClass().contains("field-error"), "the field is marked");
            amount.setText("100,00");
            assertFalse(amount.getStyleClass().contains("field-error"), "editing clears the mark");
        });
    }

    /** E-V2 class: the recognition dialog's pending-review warning and error banner span its form too. */
    @Test
    void recognitionDialogBannersSpanTheFormWidth() {
        FxTestSupport.runAndWait(() -> {
            var proposal = com.daniel.core.domain.ledger.OpeningRequest.cash("k", java.time.LocalDate.of(2026, 10, 1),
                    80_000L, true, "saldo manual anterior");
            var dialog = LedgerRecognitionDialog.forTest(LedgerRecognitionDialog.Mode.OPENING, null, "Caixa",
                    null, proposal, FxStubs.emptyDaily(), Runnable::run, Runnable::run, () -> {});
            var pane = dialog.getDialogPane();
            pane.resize(560, 700);
            assertEquals(java.util.List.of(), FxTestSupport.messageBoxesShortOfTheirContainer(pane, ".warning-banner"));
        });
    }
}

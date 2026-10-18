package com.daniel.presentation.view.components;

import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.viewmodel.MovementForm;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Submission must read the editor, including edits not yet committed by the skin. */
class DialogDateValidationTest {
    private static final LocalDate OLD = LocalDate.of(2026, 1, 15);
    private static final LocalDate CORRECTED = LocalDate.of(2024, 2, 29);
    private static final List<String> INVALID = List.of("31/02/2026", "29/02/2025", "31/04/2026", "2026-02-31", "");

    private static DatePicker picker(Dialog<?> dialog) {
        dialog.getDialogPane().applyCss();
        try {
            var field = dialog.getClass().getDeclaredField("datePicker");
            field.setAccessible(true);
            return (DatePicker) field.get(dialog);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void confirm(Dialog<?> dialog) {
        var type = dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
        ((Button) dialog.getDialogPane().lookupButton(type)).fire();
    }

    @Test
    void movementConfirmNeverQueuesInvalidEditorOrOldDateAndCorrectionRecovers() {
        FxTestSupport.runAndWait(() -> {
            for (boolean blur : List.of(false, true)) for (String bad : INVALID) {
                var work = new ArrayDeque<Runnable>();
                var form = MovementForm.deposit();
                var dialog = new MovementDialog("Aporte", null, form, null, null, work::add, Runnable::run);
                dialog.amountField().setText("100,00");
                DatePicker date = picker(dialog);
                date.setValue(OLD);
                date.getEditor().setText(bad);
                // DatePicker's production focus-loss listener calls precisely this method.
                if (blur) date.commitValue();
                confirm(dialog);
                assertTrue(work.isEmpty(), "invalid editor queued a write: " + bad + ", blur=" + blur);
                assertNull(dialog.getResult());
                date.getEditor().setText("29/02/2024");
                confirm(dialog);
                assertEquals(1, work.size(), "correction should reach worker exactly once");
                assertEquals(CORRECTED, form.date());
            }
        });
    }

    @Test
    void recognitionConfirmNeverQueuesInvalidEditorAndCorrectionRecovers() {
        FxTestSupport.runAndWait(() -> {
            for (boolean blur : List.of(false, true)) for (String bad : INVALID) {
                var work = new ArrayDeque<Runnable>();
                var dialog = LedgerRecognitionDialog.forTest(LedgerRecognitionDialog.Mode.OPENING, null,
                        "Caixa", null, OpeningRequest.cash("date-test", OLD, 0, false, null),
                        null, work::add, Runnable::run, null);
                DatePicker date = picker(dialog);
                date.getEditor().setText(bad);
                if (blur) date.commitValue();
                confirm(dialog);
                assertTrue(work.isEmpty(), "invalid editor queued opening: " + bad + ", blur=" + blur);
                assertNull(dialog.getResult());
                date.getEditor().setText("2024-02-29");
                confirm(dialog);
                assertEquals(1, work.size());
                assertEquals(CORRECTED, date.getValue());
            }
        });
    }

    @Test
    void investmentValidationAndResultNeverReuseOldDateAndAcceptCalendarChanges() {
        FxTestSupport.runAndWait(() -> {
            for (boolean blur : List.of(false, true)) for (String bad : INVALID) {
                var dialog = new InvestmentTypeDialog("Editar", InvestmentTypeDialogMoneyValidationTest.existing());
                DatePicker date = picker(dialog);
                date.getEditor().setText(bad);
                if (blur) date.commitValue();
                assertTrue(dialog.validationErrors().stream().anyMatch(e -> e.contains("Data")), bad);
                assertThrows(IllegalArgumentException.class, dialog::buildResult);
                date.getEditor().setText("29/02/2024");
                assertTrue(dialog.validationErrors().isEmpty());
                assertEquals(CORRECTED, dialog.buildResult().investmentDate());
                // Calendar selection changes value, rather than editing text.
                date.setValue(OLD);
                assertTrue(dialog.validationErrors().isEmpty());
                assertEquals(OLD, dialog.buildResult().investmentDate());
            }
        });
    }
}

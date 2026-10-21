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
}

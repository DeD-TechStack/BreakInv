package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.event.Event;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimpleDialogFrameTest {
    @Test
    void alertsAndTextInputsUseTheSharedScrollableBoundedFrame() {
        FxTestSupport.runAndWait(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Erro"); alert.setHeaderText("Corrija os erros:");
            alert.setContentText("Uma mensagem longa. ".repeat(200));
            TextInputDialog input = new TextInputDialog("valor preservado");
            input.setTitle("Editar"); input.setHeaderText("Cabeçalho"); input.setContentText("Nome:");
            for (Dialog<?> dialog : java.util.List.of(alert, input)) {
                var apply = Dialogs.class.getDeclaredMethod("applyTheme", dialog.getClass());
                apply.setAccessible(true); apply.invoke(null, dialog);
                assertNotNull(dialog.getDialogPane().getContent(), "shared content frame is required");
                assertNotNull(dialog.getDialogPane().getContent().lookup(".dialog-body-scroll"));
                assertNotNull(dialog.getDialogPane().getContent().lookup(".dialog-header-row"));
                Event.fireEvent(dialog, new DialogEvent(dialog, DialogEvent.DIALOG_SHOWING));
                assertTrue(dialog.getDialogPane().getMaxWidth() > 0);
                assertTrue(dialog.getDialogPane().getMaxWidth() < Double.MAX_VALUE);
                assertTrue(dialog.getDialogPane().getMaxHeight() > 0);
                assertTrue(dialog.getDialogPane().getMaxHeight() < Double.MAX_VALUE);
                Event.fireEvent(dialog, new DialogEvent(dialog, DialogEvent.DIALOG_HIDDEN));
            }
            assertEquals("valor preservado", input.getEditor().getText());
            ScrollPane scroll = (ScrollPane) input.getDialogPane().getContent().lookup(".dialog-body-scroll");
            assertTrue(scroll.getContent().lookupAll(".text-field").contains(input.getEditor()));
        });
    }
}

package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The confirmation dialog shares the app frame (title + close, scrollable body) and names the
 * action instead of "OK": the primary button says what will happen, Cancel is always present,
 * and the consequence text is on the dialog itself.
 */
class DialogsConfirmTest {

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        return out;
    }

    @Test
    void confirmDialog_usesTheSharedFrame_namesTheAction_andKeepsCancel() {
        FxTestSupport.runAndWait(() -> {
            Dialog<ButtonType> d = Dialogs.buildConfirm("Excluir plano", "Excluir o plano \"Conservador\"?",
                    "A análise volta para a estratégia padrão. Nenhum investimento é alterado.", "Excluir");
            Node content = d.getDialogPane().getContent();
            assertNotNull(content);
            Label title = (Label) content.lookup(".dialog-title-label");
            assertEquals("Excluir plano", title.getText());
            assertNotNull(content.lookup(".dialog-close-btn"), "close button in the header");
            assertNotNull(content.lookup(".dialog-body-scroll"), "body inside the shared scroll");
            List<String> texts = flatten(content).stream().filter(Label.class::isInstance).map(n -> ((Label) n).getText()).toList();
            assertTrue(texts.contains("Excluir o plano \"Conservador\"?"), texts.toString());
            assertTrue(texts.stream().anyMatch(t -> t != null && t.contains("Nenhum investimento é alterado")), texts.toString());

            List<ButtonType> types = d.getDialogPane().getButtonTypes();
            assertEquals(2, types.size());
            ButtonType primary = types.stream().filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
            assertEquals("Excluir", primary.getText(), "the primary button names the action, never OK");
            ButtonType cancel = types.stream().filter(t -> t.getButtonData().isCancelButton()).findFirst().orElseThrow();
            assertEquals("Cancelar", cancel.getText());
            Button primaryBtn = (Button) d.getDialogPane().lookupButton(primary);
            assertTrue(primaryBtn.getStyleClass().contains("danger-btn"), primaryBtn.getStyleClass().toString());
            Button cancelBtn = (Button) d.getDialogPane().lookupButton(cancel);
            assertTrue(cancelBtn.isCancelButton(), "Esc cancels");
            // Destructive: Enter must not delete. Cancel is the default button; Excluir needs a deliberate activation.
            assertFalse(primaryBtn.isDefaultButton(), "Enter must not run a destructive action");
            assertTrue(cancelBtn.isDefaultButton(), "Enter = Cancelar on a destructive confirmation");
        });
    }

    @Test
    void nonDestructiveConfirmation_keepsEnterOnThePrimaryAction() {
        FxTestSupport.runAndWait(() -> {
            Dialog<ButtonType> d = Dialogs.buildConfirm("Arquivar", "Arquivar o ativo?", "Pode ser desarquivado depois.", "Arquivar");
            ButtonType primary = d.getDialogPane().getButtonTypes().stream()
                    .filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
            Button primaryBtn = (Button) d.getDialogPane().lookupButton(primary);
            assertTrue(primaryBtn.isDefaultButton(), "Enter confirms a reversible action");
            assertTrue(primaryBtn.getStyleClass().contains("primary-btn"));
        });
    }

    @Test
    void confirmDialog_defaultLabelIsConfirmar_andHeaderIsOptional() {
        FxTestSupport.runAndWait(() -> {
            Dialog<ButtonType> d = Dialogs.buildConfirm("Arquivar", null, "Arquivar o ativo?", null);
            ButtonType primary = d.getDialogPane().getButtonTypes().stream()
                    .filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
            assertEquals("Confirmar", primary.getText());
            List<String> texts = flatten(d.getDialogPane().getContent()).stream().filter(Label.class::isInstance)
                    .map(n -> ((Label) n).getText()).filter(t -> t != null && !t.isBlank()).toList();
            assertTrue(texts.contains("Arquivar o ativo?"), texts.toString());
            assertEquals(2, texts.size(), "title + message only, no empty header label: " + texts);
        });
    }
}

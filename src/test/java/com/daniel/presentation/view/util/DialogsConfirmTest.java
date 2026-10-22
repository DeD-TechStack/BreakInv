package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.geometry.Orientation;
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

    /**
     * The size the window gets: JavaFX sizes it from the root's preferred size, asked for no width
     * unless the root says its height depends on its width.
     */
    static double[] windowSize(DialogPane pane) {
        pane.applyCss();
        double w = pane.prefWidth(-1);
        double h = pane.prefHeight(pane.getContentBias() == Orientation.HORIZONTAL ? w : -1);
        return new double[]{w, Math.min(h, pane.maxHeight(w))};
    }

    /**
     * A wrapped Label measured without a width reports a single line, and the window is sized from
     * that measure: a three-line message got a 47 px body with a scrollbar (BRK-30 capture). Every
     * text dialog shows its whole message at the size the window gets, without scrolling.
     */
    @Test
    void longMessages_areWholeAtTheSizeTheWindowGets_withoutScrolling() {
        String noChange = "Nada mudou desde a última cópia (22/10/2026 05:23), que continua válida. "
                + "Quer salvar uma cópia em outro local, como outro disco ou um pen drive?";
        String restore = "Arquivo: breakinv-diaria-20261022-052344-a2a919.db\nCriada em: 22/10/2026 05:23\n"
                + "Conteúdo: 1 investimento(s), 6 movimentação(ões), 0 cenário(s) e 0 plano(s).\n\n"
                + "Os dados atuais serão substituídos. Antes, o BreakInv guarda uma cópia deles na pasta das cópias. "
                + "Feche outras janelas do BreakInv antes de continuar.";
        String failure = "Não foi possível restaurar: a cópia escolhida não passou na conferência (a tabela "
                + "portfolio_movement está danificada). Nada foi alterado; os dados atuais continuam como estavam.";
        FxTestSupport.runAndWait(() -> {
            List<Dialog<?>> dialogs = List.of(
                    Dialogs.buildConfirm("Cópia de segurança", null, noChange, "Salvar em outro local…"),
                    Dialogs.buildConfirm("Restaurar cópia", "Restaurar a cópia de 22/10/2026 05:23 sobre os dados atuais?",
                            restore, "Restaurar"),
                    Dialogs.buildAlert(Alert.AlertType.ERROR, "Erro",
                            "Não foi possível restaurar a cópia escolhida sobre os dados atuais deste computador:", failure, null));
            for (Dialog<?> d : dialogs) {
                DialogPane pane = d.getDialogPane();
                double[] size = windowSize(pane);
                pane.resize(size[0], size[1]);
                pane.layout();
                ScrollPane body = (ScrollPane) pane.getContent().lookup(".dialog-body-scroll");
                double needed = body.getContent().getLayoutBounds().getHeight();
                double shown = body.getViewportBounds().getHeight();
                List<String> texts = flatten(body.getContent()).stream()
                        .filter(n -> n instanceof Label l && l.isWrapText())
                        .map(n -> Math.round(((Label) n).getWidth()) + "x" + Math.round(((Label) n).getHeight())).toList();
                assertTrue(needed <= shown + 0.5, d.getTitle() + ": the message needs " + needed + " px and the window, "
                        + Math.round(size[0]) + "x" + Math.round(size[1]) + ", shows " + shown + " px of it; texts " + texts);
            }
        });
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

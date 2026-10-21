package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.PageHeader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeaderActionsLayoutTest {
    @Test
    void everyActionAndSubtitleFitsAfterWideNarrowIntermediateWide() {
        FxTestSupport.runAndWait(() -> {
            String subtitle = "Posições, custo e caixa calculados a partir do histórico de movimentações";
            Button[] actions = {new Button("Retirada"), new Button("Aporte"), new Button("Mais ações"), new Button("Novo ativo")};
            HeaderActions header = new HeaderActions(new PageHeader("Carteira", subtitle), actions);
            VBox root = new VBox(header);
            var scene = new Scene(root, 1060, 600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (double width : new double[]{1060, 320, 544, 740, 1060}) {
                root.resize(width, 600);
                for (int pass = 0; pass < 4; pass++) { root.applyCss(); root.layout(); }
                for (Button button : actions) {
                    var bounds = button.localToScene(button.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1,
                            button.getText() + " outside " + width + ": " + bounds);
                    assertEquals(button.getText(), ((Text) button.lookup(".text")).getText());
                }
                Label sub = (Label) header.lookup(".page-subtitle");
                assertEquals(subtitle, ((Text) sub.lookup(".text")).getText(), "subtitle cannot be ellipsized");
                assertTrue(sub.localToScene(sub.getBoundsInLocal()).getMaxX() <= width + 1);
            }
        });
    }
}

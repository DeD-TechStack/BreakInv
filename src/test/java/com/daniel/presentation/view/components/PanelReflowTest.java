package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PanelReflowTest {
    @Test
    void longActionChipStacksBeforeItCanCrushTheSubtitle() {
        FxTestSupport.runAndWait(() -> {
            Label chip = new Label("Cotações obtidas agora · atraso ~15–30 min");
            chip.setMinWidth(Region.USE_PREF_SIZE);
            Panel panel = new Panel("Variação do dia").subtitle("Variação em relação ao fechamento anterior. Não é a rentabilidade da posição.").actions(chip);
            Scene scene = new Scene(panel, 1000, 240);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (double width : new double[]{1000, 332, 700, 1000}) {
                panel.resize(width, 300);
                for (int pass = 0; pass < 5; pass++) { panel.applyCss(); panel.layout(); }
                Label subtitle = (Label)panel.lookup(".panel-subtitle");
                assertTrue(subtitle.getWidth() >= 220, "subtitle squeezed into a vertical strip: " + subtitle.getWidth());
                assertTrue(chip.getWidth() >= chip.prefWidth(-1) - 1);
                if(width == 332) assertTrue(chip.localToScene(chip.getBoundsInLocal()).getMinY()
                        >= subtitle.localToScene(subtitle.getBoundsInLocal()).getMaxY());
            }
        });
    }

    /**
     * R3-02: beside a badge or a bar of period buttons, the subtitle got one line and ended in "…" at
     * 632 px of page — the caveat it carries was lost. The subtitle (and the title) wrap whole at
     * every width, in the side-by-side header and in the stacked one.
     */
    @Test
    void subtitleBesideActionsWrapsWholeAtEveryWidth() {
        FxTestSupport.runAndWait(() -> {
            Label chip = new Label("Cotações obtidas agora · atraso ~15–30 min");
            chip.setMinWidth(Region.USE_PREF_SIZE);
            javafx.scene.layout.FlowPane periods = new javafx.scene.layout.FlowPane(6, 6);
            for (String p : new String[]{"1 Dia", "1 Semana", "1 Mês", "3 Meses", "1 Ano", "5 Anos", "10 Anos", "Máx"})
                periods.getChildren().add(new javafx.scene.control.ToggleButton(p));
            Panel day = new Panel("Variação do dia").subtitle("Variação da cotação em relação ao fechamento anterior "
                    + "(BRAPI, atraso de 15–30 min). É a variação do preço, não é a rentabilidade da sua posição.").actions(chip);
            Panel period = new Panel("Variação do preço no período").subtitle("Preço de fechamento em relação ao início do "
                    + "período (BRAPI). Não considera aportes, dividendos nem o seu preço de compra.").actions(periods);
            javafx.scene.layout.VBox page = new javafx.scene.layout.VBox(16, day, period);
            com.daniel.presentation.view.util.UiTheme.applyFont(page); // the app's face: wrapping depends on its metrics
            Scene scene = new Scene(page, 1000, 600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            java.util.List<String> problems = new java.util.ArrayList<>();
            for (double width : new double[]{1000, 800, 700, 660, 632, 600, 568, 520, 380, 1000}) {
                page.resize(width, 900);
                for (int pass = 0; pass < 5; pass++) { page.applyCss(); page.layout(); }
                for (String cut : FxTestSupport.cutTexts(page)) problems.add(width + ": " + cut);
            }
            assertEquals(java.util.List.of(), problems);
        });
    }
}

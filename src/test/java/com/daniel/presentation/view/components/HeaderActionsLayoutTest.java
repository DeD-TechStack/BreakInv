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

    static Scene styled(VBox root, double width) {
        var scene = new Scene(root, width, 600);
        scene.getStylesheets().add(HeaderActionsLayoutTest.class.getResource("/styles/app.css").toExternalForm());
        return scene;
    }

    static void settle(VBox root, double width) {
        root.resize(width, 600);
        for (int pass = 0; pass < 6; pass++) { root.applyCss(); root.layout(); }
    }

    /**
     * LB1 residual: the Dashboard data-origin chips change text after the async load, with no
     * resize. The actions must be re-aligned then too: on the title row and flush right while they
     * fit, never left at a margin computed for the old text.
     */
    @Test
    void actionsRealignWhenAChipChangesTextWithoutAResize() {
        FxTestSupport.runAndWait(() -> {
            Label connection = new Label("BRAPI sem token");
            Label freshness = new Label("Sem cotações");
            for (Label chip : java.util.List.of(connection, freshness)) chip.getStyleClass().addAll("brapi-chip", "brapi-chip-neutral");
            HeaderActions header = new HeaderActions(new PageHeader("Dashboard", "Resumo do patrimônio"), connection, freshness);
            VBox root = new VBox(header);
            styled(root, 1060);
            String[][] texts = {
                    {"BRAPI sem token", "Sem cotações"},
                    {"BRAPI conectado · Plano gratuito", "Cotações de 20/10/2026 às 14:02 · atualizadas"},
                    {"BRAPI", "OK"},
            };
            for (String[] t : texts) {
                connection.setText(t[0]);
                freshness.setText(t[1]);
                settle(root, 1060);
                var title = header.lookup(".page-title");
                var titleBounds = title.localToScene(title.getLayoutBounds());
                for (Label chip : java.util.List.of(connection, freshness)) {
                    var b = chip.localToScene(chip.getLayoutBounds());
                    assertTrue(b.getMinY() < titleBounds.getMaxY(), "'" + chip.getText() + "' left the title row: " + b);
                }
                double right = header.localToScene(header.getLayoutBounds()).getMaxX() - header.snappedRightInset();
                assertEquals(right, freshness.localToScene(freshness.getLayoutBounds()).getMaxX(), 1.0,
                        "actions stay flush right after '" + t[1] + "'");
            }
        });
    }

    /**
     * R2-10: at 150 % (600×480) the actions went under the title — allowed — and there "Novo ativo"
     * went alone to a second line: the actions pane was given exactly its preferred width (r2 dump:
     * x 72, right edge 461.33 = 72 + 389.33), but with a fractional render scale (widths in thirds of
     * a pixel) that sum fell a hair short of what FlowPane's own run accumulates. Wherever the row is
     * as wide as the actions, they share one line — on the title row or under it — at any scale.
     */
    @Test
    void actionsNeverSplitWhenTheirOwnLineFitsAtFractionalScales() {
        FxTestSupport.runAndWait(() -> {
            java.util.List<String> problems = new java.util.ArrayList<>();
            for (double scale : new double[]{1.0, 1.25, 1.5, 1.75}) {
                Button[] actions = {new Button("Retirada"), new Button("Aporte"), new Button("⋯"), new Button("Novo ativo")};
                actions[3].getStyleClass().add("primary-btn");
                for (int i = 0; i < 3; i++) actions[i].getStyleClass().add("ghost-btn");
                HeaderActions header = new HeaderActions(new PageHeader("Carteira",
                        "Posições, custo e caixa calculados a partir do histórico de movimentações"), actions);
                VBox root = new VBox(header);
                Scene scene = styled(root, 1060);
                javafx.stage.Stage stage = new javafx.stage.Stage(); // never shown: only its render scale is used
                stage.setScene(scene);
                stage.setRenderScaleX(scale);
                stage.setRenderScaleY(scale);
                settle(root, 1060);
                if (scale == 1.5 && java.util.Arrays.stream(actions).allMatch(b -> b.getWidth() == Math.rint(b.getWidth())))
                    problems.add("render scale 1.5 not applied to snapping: the check would prove nothing");
                double own = header.snappedLeftInset() + header.actions().prefWidth(-1) + header.snappedRightInset();
                for (double width = Math.ceil(own); width <= own + 160; width += 1.0 / 6) {
                    settle(root, width);
                    var first = actions[0].localToScene(actions[0].getLayoutBounds());
                    for (Button b : actions) {
                        var bb = b.localToScene(b.getLayoutBounds());
                        if (bb.getMinY() >= first.getMaxY() - 0.5) // below the first action: another line
                            problems.add("scale " + scale + " width " + String.format("%.3f", width) + ": '" + b.getText()
                                    + "' went to another line (y " + bb.getMinY() + " under " + first.getMaxY() + ")");
                    }
                }
            }
            assertEquals(java.util.List.of(), problems.stream().limit(6).toList(), problems.size() + " splits");
        });
    }

    /**
     * R2-10, the r2 JAR's own numbers: at 150 % the Carteira actions measured 112, 100.67, 36.67 and
     * 116 px (thirds of a pixel), the pane got exactly their sum, 389.33, and FlowPane's run — which
     * adds width and gap child by child — came out a rounding error above it: "Novo ativo" wrapped.
     */
    @Test
    void theR2WidthsAt150PercentStayOnOneLine() {
        FxTestSupport.runAndWait(() -> {
            java.util.List<javafx.scene.layout.Region> items = new java.util.ArrayList<>();
            for (double w : new double[]{112, 100.5, 36.5, 116}) {
                var r = new javafx.scene.layout.Region();
                r.setMinSize(w, 39);
                r.setPrefSize(w, 39);
                r.setMaxSize(w, 39);
                items.add(r);
            }
            HeaderActions header = new HeaderActions(new PageHeader("Carteira",
                    "Posições, custo e caixa calculados a partir do histórico de movimentações"),
                    items.toArray(new javafx.scene.Node[0]));
            VBox root = new VBox(header);
            Scene scene = styled(root, 600);
            javafx.stage.Stage stage = new javafx.stage.Stage(); // never shown: only its render scale is used
            stage.setScene(scene);
            stage.setRenderScaleX(1.5);
            stage.setRenderScaleY(1.5);
            java.util.List<String> problems = new java.util.ArrayList<>();
            for (double width = 380; width <= 560; width += 1.0 / 3) {
                settle(root, width);
                double y = items.get(0).getLayoutY();
                for (var r : items)
                    if (Math.abs(r.getLayoutY() - y) > 0.5 // only where the row holds the actions' own line
                            && header.getWidth() - header.snappedLeftInset() - header.snappedRightInset() >= header.actions().prefWidth(-1))
                        problems.add(String.format("width %.3f: item %d on another line (pane %.10f)", width,
                                items.indexOf(r), header.actions().getWidth()));
            }
            assertEquals(java.util.List.of(), problems.stream().limit(6).toList(), problems.size() + " splits");
        });
    }

    /** LB1 residual: the actions' preferred width uses each child's bounded size (a minimum above pref), as FlowPane lays it out. */
    @Test
    void actionWithAMinimumAboveItsPreferredWidthKeepsTheRow() {
        FxTestSupport.runAndWait(() -> {
            Button period = new Button("Mês");
            period.setStyle("-fx-min-width: 160px;");
            Button add = new Button("Novo ativo");
            HeaderActions header = new HeaderActions(new PageHeader("Carteira", "Posições"), period, add);
            VBox root = new VBox(header);
            styled(root, 1060);
            settle(root, 1060);
            var a = period.localToScene(period.getLayoutBounds());
            var b = add.localToScene(add.getLayoutBounds());
            assertEquals(a.getMinY(), b.getMinY(), 0.5, "both actions share one row: " + a + " / " + b);
            assertTrue(a.getMaxX() <= b.getMinX(), "in order");
            double right = header.localToScene(header.getLayoutBounds()).getMaxX() - header.snappedRightInset();
            assertEquals(right, b.getMaxX(), 1.0, "flush right");
        });
    }
}

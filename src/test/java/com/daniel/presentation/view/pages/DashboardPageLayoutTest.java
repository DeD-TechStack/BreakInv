package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dashboard composition (direction A): hero wealth value, real-value stat strip, the main chart
 * high on the page with its period controls, then composition + activity as support and the
 * ranking without compression. Containers reflow by breakpoint; nothing is only "smaller text".
 */
class DashboardPageLayoutTest {

    @TempDir
    Path tempDir;

    DashboardPage page;
    Parent view;

    @BeforeEach
    void setUp() {
        // onShow() reads app settings (BRAPI token, auto-update) through the Database singleton; in
        // the full suite another test may have left it pointing at a deleted @TempDir, so this test
        // owns its own empty database instead of depending on the order of execution.
        Database.configure("jdbc:sqlite:" + tempDir.resolve("dashboard.db").toString().replace("\\", "/"));
        Database.open();
        // Layout fixtures are offline. An unawaited rate callback can otherwise reopen the
        // next test's Database singleton after teardown and recreate its temporary directory.
        new com.daniel.infrastructure.persistence.repository.AppSettingsRepository()
                .set("brapi_auto_update", "false");
        FxTestSupport.runAndWait(() -> {
            page = new DashboardPage(FxStubs.emptyDaily());
            view = page.view();
        });
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        return out;
    }

    <T> T first(Class<T> type) {
        return flatten(view).stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    Pane pageRoot() {
        return (Pane) ((ScrollPane) view).getContent();
    }

    int indexOfAncestorIn(Pane root, Node target) {
        for (int i = 0; i < root.getChildren().size(); i++) {
            if (flatten(root.getChildren().get(i)).contains(target)) return i;
        }
        return -1;
    }

    @Test
    void heroIsThePatrimonyValue_withGainAndCashAsSupportingValues() {
        FxTestSupport.runAndWait(() -> {
            HeroValue hero = first(HeroValue.class);
            assertNotNull(hero, "hero value present");
            assertEquals("Patrimônio", hero.caption());
            StatStrip strip = first(StatStrip.class);
            assertNotNull(strip, "stat strip present");
            List<String> labels = strip.labels();
            assertTrue(labels.contains("Caixa"), labels.toString());
            assertTrue(labels.stream().anyMatch(l -> l.startsWith("Ganho")), labels.toString());
            assertTrue(labels.contains("Posições"), labels.toString());
        });
    }

    @Test
    void mainChartComesBeforeCompositionAndActivity_andKeepsThePeriodControlsWithIt() {
        FxTestSupport.runAndWait(() -> {
            Pane root = pageRoot();
            LineChart<?, ?> main = first(LineChart.class);
            PieChart pie = first(PieChart.class);
            assertNotNull(main);
            assertNotNull(pie);
            int chartIdx = indexOfAncestorIn(root, main);
            int pieIdx = indexOfAncestorIn(root, pie);
            assertTrue(chartIdx >= 0 && pieIdx >= 0);
            assertTrue(chartIdx < pieIdx, "main chart block (" + chartIdx + ") above composition (" + pieIdx + ")");
            Node periodToggle = flatten(root.getChildren().get(chartIdx)).stream()
                    .filter(n -> n.getStyleClass().contains("filter-toggle")).findFirst().orElse(null);
            assertNotNull(periodToggle, "period filter lives inside the main chart block");
        });
    }

    /** The dashboard refresh runs on the UiExecutor and answers through the FX queue; wait for the hero to settle. */
    void awaitRefresh() throws Exception {
        HeroValue hero = first(HeroValue.class);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            com.daniel.presentation.view.util.UiExecutor.get().submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
            FxTestSupport.runAndWait(() -> {});
            final boolean[] done = {false};
            FxTestSupport.runAndWait(() -> done[0] = !hero.valueLabel().getText().equals("—")
                    || first(StatStrip.class).qualifierLabel("Caixa").isVisible());
            if (done[0]) return;
            Thread.sleep(50);
        }
    }

    @Test
    void emptyPortfolio_showsZeroWealthHonestly_notADash_andQualifiesTheManualCash() throws Exception {
        FxTestSupport.runAndWait(() -> page.onShow());
        awaitRefresh();
        FxTestSupport.runAndWait(() -> {
            HeroValue hero = first(HeroValue.class);
            StatStrip strip = first(StatStrip.class);
            String zero = com.daniel.core.util.MoneyFormat.brl(0);
            assertEquals(zero, hero.valueLabel().getText(), "zero is a value; '—' would mean unknown while the strip says R$ 0,00");
            assertEquals(zero, strip.valueLabel("Caixa").getText());
            Label q = strip.qualifierLabel("Caixa");
            assertTrue(q.isVisible() && q.isManaged(), "cash without a movement history is qualified, like Carteira does");
            assertFalse(q.getText().isBlank());
            assertTrue(strip.valueLabel("Ganho realizado").getText().equals("—"), "unknown gain stays unknown");
            Label gq = strip.qualifierLabel("Ganho realizado");
            assertTrue(gq.isVisible() && !gq.getText().isBlank(), "and says why");
        });
    }

    @Test
    void tokenBanner_doesNotPromiseRealReturn() {
        FxTestSupport.runAndWait(() -> {
            List<String> texts = flatten(view).stream().filter(Label.class::isInstance)
                    .map(n -> ((Label) n).getText()).filter(t -> t != null).toList();
            assertTrue(texts.stream().noneMatch(t -> t.toLowerCase().contains("rentabilidade real")),
                    "the chart method is value variation; the banner must not promise 'rentabilidade real'");
            assertTrue(texts.stream().anyMatch(t -> t.contains("Token Brapi não configurado")), "the warning itself is kept");
        });
    }

    @Test
    void methodDisclaimerIsPreserved_andNoTextPresentsVariationAsReturn() {
        FxTestSupport.runAndWait(() -> {
            List<String> texts = flatten(view).stream().filter(Label.class::isInstance)
                    .map(n -> ((Label) n).getText()).filter(t -> t != null).toList();
            assertTrue(texts.stream().anyMatch(t -> t.contains("NÃO é rentabilidade")), "TWR disclaimer kept");
            assertTrue(texts.stream().noneMatch(t -> t.toLowerCase().contains("ledger")), "no implementation vocabulary");
        });
    }

    @Test
    void statStripReflows_4columnsWide_2x2Narrow() {
        FxTestSupport.runAndWait(() -> {
            StatStrip strip = first(StatStrip.class);
            page.onBreakpoint(Breakpoint.WIDE);
            assertEquals(4, strip.columns());
            page.onBreakpoint(Breakpoint.NARROW);
            assertEquals(2, strip.columns());
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertEquals(4, strip.columns());
        });
    }

    @Test
    void supportPanelsSitSideBySideWide_andStackNarrow() {
        FxTestSupport.runAndWait(() -> {
            List<AdaptivePair> pairs = flatten(view).stream().filter(AdaptivePair.class::isInstance)
                    .map(AdaptivePair.class::cast).toList();
            assertFalse(pairs.isEmpty(), "support content uses adaptive pairs");
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(pairs.stream().allMatch(AdaptivePair::isSideBySide));
            page.onBreakpoint(Breakpoint.NARROW);
            assertTrue(pairs.stream().noneMatch(AdaptivePair::isSideBySide));
        });
    }

    @Test
    void benchmarkLabelsRemainCompleteWithProductionStylesAtWideContentWidth() {
        FxTestSupport.runAndWait(() -> {
            Scene scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((Region) view).resize(1060, 800);
            view.applyCss();
            view.layout();
            List<ToggleButton> toggles = view.lookupAll(".bench-toggle").stream()
                    .map(ToggleButton.class::cast).toList();
            assertEquals(4, toggles.size());
            for (ToggleButton button : toggles) {
                assertTrue(button.getWidth() + 0.5 >= button.prefWidth(-1),
                        button.getText() + ": actual=" + button.getWidth() + ", preferred=" + button.prefWidth(-1));
                assertTrue(button.getBoundsInParent().getMaxX() <=
                        button.getParent().getLayoutBounds().getWidth() + 0.5,
                        button.getText() + " must stay inside its toolbar");
            }
        });
    }

    @Test
    void rankingHasItsOwnBlock_notSqueezedBesideTwoCharts() {
        FxTestSupport.runAndWait(() -> {
            Node ranking = flatten(view).stream().filter(n -> n.getStyleClass().contains("rank-panel")).findFirst().orElse(null);
            assertNotNull(ranking, "ranking panel exists");
            Node parent = ranking.getParent();
            while (parent != null && !(parent instanceof AdaptivePair) && parent != pageRoot()) parent = parent.getParent();
            if (parent instanceof AdaptivePair ap) {
                assertTrue(ap.first() == ranking || ap.second() == ranking, "ranking shares its row with at most one neighbour");
            }
        });
    }
}

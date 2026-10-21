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

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.MovementKind;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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

    /** E-V2 class: the token banner spans the content like the panels around it, even with a short message. */
    @Test
    void warningBannerSpansItsContainerEvenWithAShortMessage() {
        FxTestSupport.runAndWait(() -> {
            var scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((Region) view).resize(1060, 800);
            assertEquals(List.of(), FxTestSupport.messageBoxesShortOfTheirContainer(view, ".warning-banner"));
        });
    }

    @Test
    void minimumContentWidthContainsSummaryAndWarningWithoutHorizontalClipping() {
        FxTestSupport.runAndWait(() -> {
            var scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ScrollPane scroll = (ScrollPane) view;
            for (double width : new double[]{1060, 380, 544, 740, 1060}) {
                var bp = Breakpoint.of(width);
                page.onBreakpoint(bp);
                view.getStyleClass().removeAll(Breakpoint.allStyleClasses());
                view.getStyleClass().add(bp.styleClass());
                scroll.resize(width, 480);
                for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
                assertTrue(pageRoot().getWidth() <= scroll.getViewportBounds().getWidth() + 1,
                        "root exceeds viewport at " + width + ": " + pageRoot().getWidth()
                                + pageRoot().getChildren().stream().map(n -> n.getStyleClass() + " min=" + n.minWidth(-1)).toList());
            }
        });
    }

    @Test
    void wideChartKeepsAllPeriodsInOneRowWithoutCompetingWithTheTitle() {
        FxTestSupport.runAndWait(() -> {
            var scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (double width : new double[]{1060, 380, 740, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((javafx.scene.layout.Region) view).resize(width, 800);
                for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
                var toggles = flatten(view).stream().filter(n -> n instanceof javafx.scene.control.ToggleButton
                        && n.getStyleClass().contains("filter-toggle")).distinct().toList();
                assertEquals(8, toggles.size(), "all periods and custom interval remain available");
                if (width == 1060) {
                    double y = toggles.getFirst().localToScene(toggles.getFirst().getLayoutBounds()).getCenterY();
                    for (Node toggle : toggles) assertEquals(y,
                            toggle.localToScene(toggle.getLayoutBounds()).getCenterY(), 1);
                }
            }
        });
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
            double benchmarkY = toggles.getFirst().localToScene(toggles.getFirst().getBoundsInLocal()).getMinY();
            for (ToggleButton button : toggles) {
                assertEquals(benchmarkY, button.localToScene(button.getBoundsInLocal()).getMinY(), 1,
                        "benchmark controls form one group above the plot, not a cramped metrics column");
                assertTrue(button.getWidth() + 0.5 >= button.prefWidth(-1),
                        button.getText() + ": actual=" + button.getWidth() + ", preferred=" + button.prefWidth(-1));
                assertTrue(button.getBoundsInParent().getMaxX() <=
                        button.getParent().getLayoutBounds().getWidth() + 0.5,
                        button.getText() + " must stay inside its toolbar");
            }
        });
    }

    @Test
    void patrimonyAndItsBreakdownShareTheWideSummary_andStackWithoutLosingValues() {
        FxTestSupport.runAndWait(() -> {
            Scene scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (int width : new int[]{1060, 544, 740, 1060}) {
                page.onBreakpoint(width >= 1000 ? Breakpoint.WIDE : width < 600 ? Breakpoint.NARROW : Breakpoint.MEDIUM);
                ((Region) view).resize(width, 800);
                view.applyCss(); view.layout();
                HeroValue hero = first(HeroValue.class);
                StatStrip strip = first(StatStrip.class);
                var h = hero.localToScene(hero.getBoundsInLocal());
                var s = strip.localToScene(strip.getBoundsInLocal());
                if (width >= 1000) {
                    assertEquals(h.getMinY(), s.getMinY(), 1, "wide: wealth and breakdown align");
                    assertTrue(s.getMinX() > h.getMinX(), "breakdown beside wealth, not another full-width card");
                } else assertTrue(s.getMinY() >= h.getMaxY(), "narrow: breakdown follows wealth");
                assertEquals(4, strip.labels().size());
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

    // ── V5 / V9: what the candidate-retomada-r1 captures reproved ─────────────

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        java.lang.reflect.Field f = DashboardPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    Object call(String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = DashboardPage.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(page, args);
    }

    static StatementEntry movement(long id, MovementKind kind, String who, long cents) {
        return new StatementEntry(LocalDate.of(2026, 10, 15), kind, null, id, null, who == null ? null : 7L, who,
                null, null, null, cents, null, null, null, null, null, false, false);
    }

    void layOut(double width) {
        page.onBreakpoint(Breakpoint.of(width));
        ((Region) view).resize(width, 1600);
        for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
    }

    /**
     * r1 at 960×720 (740 px of page, activity beside the composition): the purchase row was cut to
     * "Compra · Ativo fictício ...", "- R$ 200,..." and "15/...". Sign, amount and date keep their
     * width at every content width; the description is the one that wraps.
     */
    @Test
    void recentActivityKeepsEveryAmountAndDateWholeBesideTheComposition() throws Exception {
        FxTestSupport.runAndWait(() -> {
            var scene = new Scene(view, 1060, 1600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            call("updateRecentActivity", new Class<?>[]{List.class}, List.of(
                    movement(1, MovementKind.DEPOSIT, null, 100_000),
                    movement(2, MovementKind.BUY, "Ativo fictício E2", 20_000),
                    movement(3, MovementKind.SELL, "Ativo fictício E2", 10_000),
                    movement(4, MovementKind.WITHDRAWAL, null, 10_000)));
            Parent list = field("recentActivityList");
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 720, 700, 680, 660, 640, 544, 380, 1060}) {
                layOut(width);
                FxTestSupport.cutTexts(list).forEach(c -> problems.add(width + ": " + c));
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * r1 populated at 600×480: "R$ 20.000.000,..." and the quantity/average-price line were cut in
     * the position list. Ticker and amounts keep their width; metrics move to another line before
     * anything is cut, down to the expanded-sidebar minimum, and nothing leaves its row.
     */
    @Test
    void positionRowsKeepTickerAndEveryAmountWholeAtEveryWidth() throws Exception {
        FxTestSupport.runAndWait(() -> {
            InvestmentType stock = new InvestmentType(7, "Ativo sintético 01 — infraestrutura, energia e desenvolvimento",
                    "ACOES", "ALTA", LocalDate.of(2026, 7, 1), null, new BigDecimal("20000000.00"),
                    "ACAO", null, null, "FICT1", new BigDecimal("20.00"), 1_000_000, null);
            InvestmentType fixed = new InvestmentType(8, "Tesouro Prefixado 2031 com juros semestrais",
                    "RENDA_FIXA", "ALTA", LocalDate.of(2026, 7, 1), new BigDecimal("12.50"), new BigDecimal("12345678.90"),
                    "PREFIXADO", null, null, null, null, null, null);
            Region stockRow = (Region) call("buildGroupedStockRow",
                    new Class<?>[]{String.class, List.class, long.class, long.class},
                    "FICT1", List.of(stock), 2_000_000_000L, 3_234_567_890L);
            Region fixedRow = (Region) call("buildInvestmentRow",
                    new Class<?>[]{InvestmentType.class, long.class, long.class},
                    fixed, 1_234_567_890L, 3_234_567_890L);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, stockRow, fixedRow);
            var scene = new Scene(box, 700, 700);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{700, 452, 380, 300, 700}) {
                box.resize(width, 700);
                for (int i = 0; i < 6; i++) { box.applyCss(); box.layout(); }
                FxTestSupport.cutTexts(box).forEach(c -> problems.add(width + ": " + c));
                for (Region row : List.of(stockRow, fixedRow)) {
                    double right = row.localToScene(row.getLayoutBounds()).getMaxX();
                    for (Node n : flatten(row)) {
                        if (n instanceof Label l && l.isVisible() && l.getText() != null && !l.getText().isBlank()
                                && l.localToScene(l.getLayoutBounds()).getMaxX() > right + 0.5)
                            problems.add(width + ": '" + l.getText() + "' beyond its row");
                    }
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * r1 populated: the "Valor por ativo" axis cut each name to 15 characters, so the 75 synthetic
     * assets ("Ativo sintético 01 — …", "… 02 — …") all became "Ativo sintét..." and the
     * CategoryAxis merged them into a single category. Each asset keeps its own category: its
     * ticker when it has one, its name otherwise, never two assets under one label.
     */
    @Test
    void valuePerAssetChartGivesEveryAssetItsOwnCategory() throws Exception {
        FxTestSupport.runAndWait(() -> {
            List<InvestmentType> assets = new ArrayList<>();
            Map<Long, Long> values = new HashMap<>();
            for (int i = 1; i <= 3; i++) {
                assets.add(new InvestmentType(i, "Ativo sintético 0" + i + " — infraestrutura, energia e desenvolvimento",
                        "ACOES", "ALTA", LocalDate.of(2026, 7, 1), null, new BigDecimal("1000.00"),
                        "ACAO", null, null, "FICT" + i, new BigDecimal("10.00"), 100, null));
                values.put((long) i, 100_000L * i);
            }
            assets.add(new InvestmentType(4, "Tesouro Prefixado 2029", "RENDA_FIXA", "ALTA", LocalDate.of(2026, 7, 1),
                    new BigDecimal("12.0"), new BigDecimal("500.00"), "PREFIXADO", null, null, null, null, null, null));
            assets.add(new InvestmentType(5, "Tesouro Prefixado 2031", "RENDA_FIXA", "ALTA", LocalDate.of(2026, 7, 1),
                    new BigDecimal("12.0"), new BigDecimal("400.00"), "PREFIXADO", null, null, null, null, null, null));
            values.put(4L, 50_000L);
            values.put(5L, 40_000L);
            call("updateWaterfallChart", new Class<?>[]{List.class, Map.class}, assets, values);
            javafx.scene.chart.XYChart<String, Number> chart = field("waterfallChart");
            List<String> categories = chart.getData().get(0).getData().stream().map(d -> d.getXValue()).toList();
            assertEquals(5, categories.size(), "one bar per asset with value: " + categories);
            assertEquals(5, new HashSet<>(categories).size(), "no two assets share a category: " + categories);
            assertTrue(categories.containsAll(List.of("FICT1", "FICT2", "FICT3")), "a ticker names its asset: " + categories);
            assertTrue(categories.containsAll(List.of("Tesouro Prefixado 2029", "Tesouro Prefixado 2031")),
                    "a short name stays whole: " + categories);
        });
    }

    // ── V9 / R2: what the candidate-retomada-r2 review reproved ──────────────

    static InvestmentType fictStock(int id, String ticker, String invested) {
        return new InvestmentType(id, "Ativo fictício " + ticker, "ACOES", "ALTA", LocalDate.of(2026, 7, 1), null,
                new BigDecimal(invested), "ACAO", null, null, ticker, new BigDecimal("10.00"), 100, null);
    }

    static InvestmentType fixedIncome(int id) {
        return new InvestmentType(id, "Tesouro Prefixado 2029", "RENDA_FIXA", "ALTA", LocalDate.of(2026, 7, 1),
                new BigDecimal("12.0"), new BigDecimal("500.00"), "PREFIXADO", null, null, null, null, null, null);
    }

    static javafx.scene.paint.Color colorOf(javafx.scene.paint.Paint p) {
        if (p instanceof javafx.scene.paint.Color c) return c;
        if (p instanceof javafx.scene.paint.LinearGradient g) return g.getStops().get(0).getColor();
        if (p instanceof javafx.scene.paint.RadialGradient g) return g.getStops().get(0).getColor();
        return null;
    }

    static boolean near(javafx.scene.paint.Color a, javafx.scene.paint.Color b) {
        return a != null && b != null && Math.abs(a.getRed() - b.getRed()) < .006 && Math.abs(a.getGreen() - b.getGreen()) < .006
                && Math.abs(a.getBlue() - b.getBlue()) < .006 && Math.abs(a.getOpacity() - b.getOpacity()) < .006;
    }

    static double contrast(javafx.scene.paint.Color a, javafx.scene.paint.Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    static double luminance(javafx.scene.paint.Color c) {
        return .2126 * lin(c.getRed()) + .7152 * lin(c.getGreen()) + .0722 * lin(c.getBlue());
    }

    static double lin(double c) { return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4); }

    void styled(double width) {
        var scene = new Scene(view, width, 1600);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        layOut(width);
    }

    /** The visible empty states inside the panel that holds {@code node}. */
    static List<Node> emptyStatesBeside(Node node) {
        Node panel = node;
        while (panel != null && !panel.getStyleClass().contains("panel-block")) panel = panel.getParent();
        assertNotNull(panel, "chart inside a panel");
        return flatten(panel).stream().filter(n -> n.getStyleClass().contains("empty-state") && n.isVisible() && n.isManaged()).toList();
    }

    /**
     * R2-01 (V9, blocker): the Evolução legend showed Carteira amber and CDI green while the lines were
     * green (Carteira) and white (CDI) — the reading inverted. Each legend symbol wears its line's colour.
     */
    @Test
    void evolutionLegendUsesTheColoursOfItsLines() throws Exception {
        FxTestSupport.runAndWait(() -> {
            styled(1060);
            java.util.TreeMap<LocalDate, Long> snaps = new java.util.TreeMap<>();
            snaps.put(LocalDate.of(2026, 7, 1), 100_000L);
            snaps.put(LocalDate.of(2026, 8, 1), 101_000L);
            snaps.put(LocalDate.of(2026, 9, 1), 102_500L);
            call("updateComparisonChart", new Class<?>[]{List.class, Map.class, LocalDate.class, LocalDate.class, LocalDate.class,
                            java.util.TreeMap.class},
                    List.of(fictStock(1, "FICT1", "1000.00")), Map.of(1L, 102_500L), LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), snaps);
            layOut(1060);
        });
        FxTestSupport.runAndWait(() -> layOut(1060)); // series styles are applied on the next pulse
        FxTestSupport.runAndWait(() -> {
            LineChart<?, ?> chart = field("comparisonChart");
            assertEquals(2, chart.getData().size(), "Carteira and the benchmark");
            for (int i = 0; i < 2; i++) {
                final String series = "series" + i;
                var line = (javafx.scene.shape.Path) chart.lookup("." + series + ".chart-series-line");
                Region symbol = (Region) chart.lookupAll(".chart-legend-item-symbol").stream()
                        .filter(n -> n.getStyleClass().contains(series)).findFirst().orElseThrow();
                var stroke = colorOf(line.getStroke());
                var legend = colorOf(symbol.getBackground().getFills().get(0).getFill());
                assertTrue(near(stroke, legend), series + ": legend " + legend + " but line " + stroke);
            }
        });
    }

    /**
     * R2-22: the populated base's 75 positions became 75 cards and a 10 694 px page. A category shows
     * its first five positions and offers the others on demand, and back; a category of five or fewer
     * has nothing to expand.
     */
    @Test
    void aCategoryShowsItsFirstFivePositionsAndTheRestOnDemand() throws Exception {
        FxTestSupport.runAndWait(() -> {
            styled(1060);
            List<InvestmentType> assets = new ArrayList<>();
            Map<Long, Long> values = new HashMap<>();
            for (int i = 1; i <= 12; i++) {
                assets.add(fictStock(i, "FICT" + i, "1000.00"));
                values.put((long) i, 100_000L + i);
            }
            assets.add(fixedIncome(13));
            values.put(13L, 50_000L);
            call("updateInvestmentsByCategory", new Class<?>[]{List.class, Map.class, long.class}, assets, values, 1_250_078L);
            layOut(1060);
            javafx.scene.layout.VBox container = field("investmentsByCategoryContainer");
            List<Node> rows = flatten(container).stream().filter(n -> n.getStyleClass().contains("inv-row-card")).toList();
            assertEquals(13, rows.size(), "every position is still built");
            assertEquals(6, rows.stream().filter(n -> n.isVisible() && n.isManaged()).count(), "five stocks and the one fixed income");
            List<javafx.scene.control.Button> toggles = flatten(container).stream()
                    .filter(n -> n instanceof javafx.scene.control.Button).map(n -> (javafx.scene.control.Button) n).toList();
            assertEquals(1, toggles.size(), "only the category with more than five positions offers the rest");
            var more = toggles.get(0);
            assertEquals("Mostrar todas as 12 posições", more.getText());
            more.fire();
            assertEquals(13, rows.stream().filter(n -> n.isVisible() && n.isManaged()).count());
            assertEquals("Mostrar só as 5 primeiras", more.getText());
            more.fire();
            assertEquals(6, rows.stream().filter(n -> n.isVisible() && n.isManaged()).count());
        });
    }

    /** R2-02 (V9): the axis said "Rentabilidade %" right above the note that the line is NOT a return. */
    @Test
    void evolutionAxisNamesTheVariationItDraws() throws Exception {
        FxTestSupport.runAndWait(() -> {
            javafx.scene.chart.NumberAxis y = field("compYAxis");
            assertEquals("Variação (%)", y.getLabel());
        });
    }

    /**
     * R2-11: "Valor por ativo" was Modena orange on the first wide capture and green afterwards — the
     * series is recreated on every refresh and only `.default-color0.chart-bar` was styled.
     */
    @Test
    void valuePerAssetBarsKeepTheAccentWhateverTheRefreshCount() throws Exception {
        FxTestSupport.runAndWait(() -> {
            styled(1060);
            List<InvestmentType> assets = List.of(fictStock(1, "FICT1", "1000.00"), fictStock(2, "FICT2", "500.00"));
            Map<Long, Long> values = Map.of(1L, 100_000L, 2L, 50_000L);
            javafx.scene.chart.XYChart<String, Number> chart = field("waterfallChart");
            var accentStrong = javafx.scene.paint.Color.web("#31B77A");
            List<String> problems = new ArrayList<>();
            for (int refresh = 1; refresh <= 3; refresh++) {
                call("updateWaterfallChart", new Class<?>[]{List.class, Map.class}, assets, values);
                layOut(1060);
                for (var d : chart.getData().get(0).getData()) {
                    var fill = ((Region) d.getNode()).getBackground().getFills().get(0).getFill();
                    // derive(-fx-bar-fill, 0%) rounds #31B77A to #31B778: 3/255 of tolerance
                    boolean accent = fill instanceof javafx.scene.paint.LinearGradient g
                            ? g.getStops().stream().anyMatch(s -> Math.abs(s.getColor().getRed() - accentStrong.getRed()) < .013
                                    && Math.abs(s.getColor().getGreen() - accentStrong.getGreen()) < .013
                                    && Math.abs(s.getColor().getBlue() - accentStrong.getBlue()) < .013)
                            : near(colorOf(fill), accentStrong);
                    if (!accent) problems.add("refresh " + refresh + " " + d.getXValue() + ": " + fill);
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * R2-21: the bar chart carried an inner title "Composição do Patrimônio" competing with its panel
     * ("Valor por ativo") and the composition card; the pie legend dot was white beside a green slice.
     */
    @Test
    void chartsCarryOnlyTheirPanelTitle_andThePieLegendWearsItsSliceColour() throws Exception {
        FxTestSupport.runAndWait(() -> {
            styled(1060);
            javafx.scene.chart.XYChart<?, ?> bars = field("waterfallChart");
            assertNull(bars.getTitle(), "the panel already says 'Valor por ativo'");
            call("updatePieChart", new Class<?>[]{List.class, Map.class},
                    List.of(fictStock(1, "FICT1", "1000.00"), fixedIncome(2)), Map.of(1L, 100_000L, 2L, 50_000L));
            layOut(1060);
        });
        FxTestSupport.runAndWait(() -> layOut(1060));
        FxTestSupport.runAndWait(() -> {
            PieChart pie = field("pieChart");
            assertEquals(2, pie.getData().size());
            for (PieChart.Data slice : pie.getData()) {
                Label item = (Label) pie.lookupAll(".chart-legend-item").stream()
                        .filter(n -> n instanceof Label l && slice.getName().equals(l.getText())).findFirst().orElseThrow();
                var symbol = colorOf(((Region) item.getGraphic()).getBackground().getFills().get(0).getFill());
                var wedge = colorOf(((Region) slice.getNode()).getBackground().getFills().get(0).getFill());
                assertTrue(near(wedge, symbol), slice.getName() + ": legend " + symbol + " but slice " + wedge);
            }
        });
    }

    /** R2-12: at 740 px of page the composition shares its row with the activity and the pie shrank to ~45 px. */
    @Test
    void compositionPieStaysReadableAtEveryWidth() throws Exception {
        FxTestSupport.runAndWait(() -> {
            styled(1060);
            call("updatePieChart", new Class<?>[]{List.class, Map.class},
                    List.of(fictStock(1, "FICT1", "1000.00"), fixedIncome(2)), Map.of(1L, 100_000L, 2L, 50_000L));
        });
        FxTestSupport.runAndWait(() -> {
            PieChart pie = field("pieChart");
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 700, 544, 380, 740}) {
                layOut(width);
                var arc = (javafx.scene.shape.Arc) ((Region) pie.getData().get(0).getNode()).getShape();
                if (arc == null || arc.getRadiusX() < 70) problems.add(width + ": radius " + (arc == null ? "none" : arc.getRadiusX()));
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * R2-08 (V9): the empty dashboard drew bare 0–110 grids for Evolução and Valor por ativo, ~300 px of
     * blank composition and rank lists without a word. Each shows its own state instead.
     */
    @Test
    void emptyPortfolioShowsAStateInsteadOfBlankCharts() throws Exception {
        FxTestSupport.runAndWait(() -> page.onShow());
        awaitRefresh();
        FxTestSupport.runAndWait(() -> {
            Node evolution = field("compWrapper");
            Label why = field("noComparisonHint");
            assertFalse(evolution.isVisible() && evolution.isManaged(), "no bare evolution grid");
            assertTrue(why.isVisible() && why.isManaged() && !why.getText().isBlank(), "the evolution says why it is empty");
            Node bars = field("waterfallChart"), pie = field("pieChart");
            assertFalse(bars.isVisible() && bars.isManaged(), "no bare value grid");
            assertEquals(1, emptyStatesBeside(bars).size(), "Valor por ativo states it is empty");
            assertFalse(pie.isVisible() && pie.isManaged(), "no blank composition");
            assertEquals(1, emptyStatesBeside(pie).size(), "Composição states it is empty");
            for (String name : List.of("rankPanelAltas", "rankPanelBaixas")) {
                javafx.scene.layout.VBox list = field(name);
                assertTrue(list.getChildren().stream().anyMatch(n -> n instanceof Label l && !l.getStyleClass().contains("rank-title")
                        && l.isVisible() && l.getText() != null && !l.getText().isBlank()), name + " has a placeholder");
            }
        });
    }

    static boolean shown(Node n) {
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return n.isManaged();
    }

    static List<String> loadingNotices(Node root) {
        return flatten(root).stream().filter(n -> n instanceof javafx.scene.control.Labeled l && shown(l)
                && l.getText() != null && l.getText().startsWith("Carregando")).map(n -> ((javafx.scene.control.Labeled) n).getText()).toList();
    }

    /**
     * R3-03 (R2-08 while loading): until the first result arrives (populated: ~5 s), Evolução and "Valor
     * por ativo" drew the default 0–110 grid with neither data nor notice, "Variação do dia" stood blank
     * and Composição was empty. Before the first result no chart is drawn and every panel says it is
     * loading; the first (empty) result replaces every notice with the panel's own state.
     */
    @Test
    void beforeTheFirstResultEveryPanelSaysItIsLoading_thenShowsItsOwnState() throws Exception {
        FxTestSupport.runAndWait(() -> {
            List<String> problems = new ArrayList<>();
            Node evolution = field("compWrapper"), bars = field("waterfallChart"), pie = field("pieChart");
            if (shown(evolution)) problems.add("Evolução draws its grid before any data");
            if (shown(bars)) problems.add("Valor por ativo draws its grid before any data");
            if (shown(pie)) problems.add("Composição draws its chart before any data");
            if (loadingNotices(field("chartPanel")).isEmpty()) problems.add("Evolução does not say it is loading");
            if (emptyStatesBeside(bars).stream().noneMatch(s -> !loadingNotices(s).isEmpty()))
                problems.add("Valor por ativo does not say it is loading");
            if (emptyStatesBeside(pie).stream().noneMatch(s -> !loadingNotices(s).isEmpty()))
                problems.add("Composição does not say it is loading");
            for (String name : List.of("rankPanelAltas", "rankPanelBaixas"))
                if (loadingNotices(field(name)).isEmpty()) problems.add(name + " stands blank while loading");
            assertEquals(List.of(), problems);
        });
        FxTestSupport.runAndWait(() -> page.onShow());
        awaitRefresh();
        FxTestSupport.runAndWait(() -> assertEquals(List.of(), loadingNotices(view), "the result replaced every loading notice"));
    }

    /** E4 (V7): the "—" of an unavailable return read 3.96:1 — `.rent-dimmed` faded the text to 45 %. */
    @Test
    void unavailableReturnKeepsTextContrast() {
        FxTestSupport.runAndWait(() -> {
            Label dash = new Label("—");
            dash.getStyleClass().addAll("text-sm", "rent-dimmed");
            javafx.scene.layout.StackPane card = new javafx.scene.layout.StackPane(dash);
            card.getStyleClass().add("inv-row-card");
            var scene = new Scene(card, 200, 80);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            card.applyCss();
            var bg = colorOf(card.getBackground().getFills().get(card.getBackground().getFills().size() - 1).getFill());
            var ink = (javafx.scene.paint.Color) dash.getTextFill();
            double o = dash.getOpacity() * ink.getOpacity();
            var seen = new javafx.scene.paint.Color(ink.getRed() * o + bg.getRed() * (1 - o), ink.getGreen() * o + bg.getGreen() * (1 - o),
                    ink.getBlue() * o + bg.getBlue() * (1 - o), 1);
            assertTrue(contrast(seen, bg) >= 4.5, "dash " + seen + " on " + bg + " = " + contrast(seen, bg));
        });
    }

    /** r1: the title grew from 20 px (wide/medium) to 24 px narrow — `.bp-narrow .page-title` beat the `.h1` alias. */
    @Test
    void pageTitleKeepsItsSizeWhenTheWindowNarrows() {
        FxTestSupport.runAndWait(() -> {
            var scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            layOut(1060);
            Label title = (Label) view.lookup(".page-title");
            double wide = title.getFont().getSize();
            layOut(380);
            double narrow = title.getFont().getSize();
            layOut(740);
            double medium = title.getFont().getSize();
            assertEquals(wide, narrow, 0.01, "narrow title");
            assertEquals(wide, medium, 0.01, "medium title");
        });
    }
}

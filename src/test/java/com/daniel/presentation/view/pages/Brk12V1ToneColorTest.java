package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.daniel.presentation.view.pages.Brk12V1Support.call;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static com.daniel.presentation.view.pages.Brk12V1Support.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1, correction 1 (finding 2): the class of a tone node is not the colour it is drawn in. A later, equally
 * specific rule ({@code .table-cell}, {@code .hero-chip-value}, {@code .info-box-value}…) can win over {@code .pos},
 * {@code .neg} and {@code .muted}. Every text of tone of the screens (T1 to T10) is put in a scene with the production
 * stylesheet, laid out, and its RENDERED text fill is compared with the token of its tone: {@code -color-success}
 * #74D7A6, {@code -color-danger} #FF9595, {@code -color-text-2} #A8B6C6. The Ranking cell (T7) is in Brk12V1RankingTest.
 */
class Brk12V1ToneColorTest {

    private static final Map<String, Color> INK = Map.of(
            "pos", Color.web("#74D7A6"), "neg", Color.web("#FF9595"), "muted", Color.web("#A8B6C6"));
    private static final LocalDate TODAY = Brk12V1DashboardTest.TODAY;

    private final Brk12V1DashboardTest dash = new Brk12V1DashboardTest();
    private final Brk12V1AnalysisTest analysis = new Brk12V1AnalysisTest();
    private Locale previous;

    @BeforeEach
    void english() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previous);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The node is in a scene and its drawn text colour is the token of {@code tone}. */
    private static void assertInk(String tone, Labeled node, String what) {
        assertNotNull(node, what + ": the node exists");
        assertNotNull(node.getScene(), what + ": the node is in a scene with the production stylesheet");
        assertTrue(node.getTextFill() instanceof Color, what + ": text fill " + node.getTextFill());
        Color expected = INK.get(tone);
        Color seen = (Color) node.getTextFill();
        String where = what + " ('" + node.getText() + "', tone " + tone + ", classes " + node.getStyleClass() + ", drawn " + seen + ")";
        assertEquals(expected.getRed(), seen.getRed(), 0.01, "red of " + where);
        assertEquals(expected.getGreen(), seen.getGreen(), 0.01, "green of " + where);
        assertEquals(expected.getBlue(), seen.getBlue(), 0.01, "blue of " + where);
    }

    private DashboardPage styledDashboard(Brk12V1DashboardTest.Quotes quotes) throws Exception {
        DashboardPage page = dash.page(quotes);
        Brk12V1Support.styled(page.view(), 1280, 800);
        page.onBreakpoint(Breakpoint.WIDE);
        ((XYChart<?, ?>) field(page, "waterfallChart")).setAnimated(false);
        return page;
    }

    private static void settle(DashboardPage page) {
        Brk12V1Support.settle(page.view(), 1280, 800);
    }

    private static List<Label> labelsOf(Node root, String styleClass) {
        List<Label> out = new ArrayList<>();
        for (Node n : Brk12V1DashboardTest.flatten(root)) {
            if (n instanceof Label l && l.getStyleClass().contains(styleClass)) out.add(l);
        }
        return out;
    }

    // ── T1, T2, T3: the two variation metrics ────────────────────────────────

    private record Variation(String name, long first, long last, String invested, String moneyTone, String pctTone) {}

    @Test
    void variationMetrics_areDrawnInTheTokenOfTheirTone() throws Exception {
        List<Variation> cases = List.of(
                new Variation("gain", 100_000, 102_500, "1000.00", "pos", "pos"),
                new Variation("loss", 100_000, 97_500, "1000.00", "neg", "neg"),
                new Variation("zero", 100_000, 100_000, "1000.00", "muted", "muted"),
                new Variation("0,004 % up", 1_000_000, 1_000_040, "10000.00", "pos", "muted"),
                new Variation("0,004 % down", 1_000_000, 999_960, "10000.00", "neg", "muted"));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = styledDashboard(new Brk12V1DashboardTest.Quotes(false));
            Label money = field(page, "metricRendimentoLabel");
            Label percent = field(page, "metricRentabilidadeLabel");
            Class<?>[] types = {List.class, Map.class, LocalDate.class, LocalDate.class, LocalDate.class, TreeMap.class};
            settle(page);
            assertInk("muted", money, "T3 before any data: Variação (R$)");
            assertInk("muted", percent, "T3 before any data: Variação %");
            for (Variation c : cases) {
                call(page, "updateComparisonChart", types,
                        List.of(Brk12V1DashboardTest.stock(1, "FICT1", c.invested(), "10.00", 100)), Map.of(1L, c.last()),
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1),
                        Brk12V1DashboardTest.snapshots(c.first(), c.last()));
                settle(page);
                assertInk(c.moneyTone(), money, "T1 " + c.name() + ": Variação (R$)");
                assertInk(c.pctTone(), percent, "T2 " + c.name() + ": Variação %");
            }
            call(page, "updateComparisonChart", types, List.<InvestmentType>of(), Map.of(), LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), new TreeMap<LocalDate, Long>());
            settle(page);
            assertInk("muted", money, "T3 no investment: Variação (R$)");
            assertInk("muted", percent, "T3 no investment: Variação %");
        });
    }

    // ── T4: the day's rises and falls ────────────────────────────────────────

    @Test
    void risesAndFalls_areDrawnInTheTokenOfTheirTone() throws Exception {
        Brk12V1DashboardTest.Quotes quotes = new Brk12V1DashboardTest.Quotes(false);
        quotes.batch.put("FICT1", Brk12V1DashboardTest.quote("FICT1", 28.12, -0.34, -1.20));
        quotes.batch.put("FICT2", Brk12V1DashboardTest.quote("FICT2", 150.0, 3.45, 2.35));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = styledDashboard(quotes);
            List<InvestmentType> investments = List.of(
                    Brk12V1DashboardTest.stock(1, "FICT1", "1000.00", "10.00", 100),
                    Brk12V1DashboardTest.stock(2, "FICT2", "1000.00", "10.00", 100));
            call(page, "updateRankPanel", new Class<?>[]{List.class, Map.class}, investments, Map.of(1L, 100_000L, 2L, 100_000L));
            dash.drain();
            settle(page);
            VBox rises = field(page, "rankPanelAltas");
            VBox falls = field(page, "rankPanelBaixas");
            List<Label> up = labelsOf(rises, "rent-value");
            List<Label> down = labelsOf(falls, "rent-value");
            assertEquals(1, up.size(), "one rise listed");
            assertEquals(1, down.size(), "one fall listed");
            assertInk("pos", up.get(0), "T4 a rise");
            assertInk("neg", down.get(0), "T4 a fall");
            assertInk("pos", labelsOf(rises, "rank-title").get(0), "the title of the rises");
            assertInk("neg", labelsOf(falls, "rank-title").get(0), "the title of the falls");
        });
    }

    // ── T5: the return of each position ──────────────────────────────────────

    /** The whole row goes into a styled scene (its own ancestors stay), then the return label's drawn colour is read. */
    private static void assertRentInk(Node row, String tone, String what) {
        VBox host = new VBox(row);
        Brk12V1Support.styled(host, 900, 200);
        Brk12V1Support.settle(host, 900, 200);
        assertInk(tone, Brk12V1DashboardTest.infoValue(row, "Rentabilidade"), what);
    }

    @Test
    void positionReturns_areDrawnInTheTokenOfTheirTone() throws Exception {
        record Case(String name, String price, String tone) {}
        List<Case> stocks = List.of(new Case("gain", "12.00", "pos"), new Case("loss", "8.00", "neg"),
                new Case("zero", "10.00", "muted"), new Case("0,004 %", "10.0004", "muted"));
        record Fixed(String name, long current, String tone) {}
        List<Fixed> fixed = List.of(new Fixed("gain", 102_500, "pos"), new Fixed("loss", 97_500, "neg"),
                new Fixed("zero", 100_000, "muted"), new Fixed("0,004 %", 100_004, "muted"));
        FxTestSupport.runAndWait(() -> {
            for (Case c : stocks) {
                Brk12V1DashboardTest.Quotes quotes = new Brk12V1DashboardTest.Quotes(true);
                quotes.single.put("FICT1", Brk12V1DashboardTest.quote("FICT1", Double.parseDouble(c.price()), 0, 0));
                DashboardPage page = dash.page(quotes);
                Node row = (Node) call(page, "buildGroupedStockRow", new Class<?>[]{String.class, List.class, long.class, long.class},
                        "FICT1", List.of(Brk12V1DashboardTest.stock(1, "FICT1", "1000.00", "10.00", 100)), 100_000L, 1_000_000L);
                dash.drain();
                assertRentInk(row, c.tone(), "T5 grouped stock, " + c.name());
            }
            DashboardPage page = dash.page(new Brk12V1DashboardTest.Quotes(false));
            for (Fixed c : fixed) {
                Node row = (Node) call(page, "buildInvestmentRow", new Class<?>[]{InvestmentType.class, long.class, long.class},
                        Brk12V1DashboardTest.fixedIncome(3, "1000.00"), c.current(), 1_000_000L);
                assertRentInk(row, c.tone(), "T5 fixed income, " + c.name());
            }
        });
    }

    // ── T6: the hero, the comparison with the CDI, the strip ─────────────────

    @Test
    void hero_isDrawnInTheTokenOfItsTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = styledDashboard(new Brk12V1DashboardTest.Quotes(false));
            List<InvestmentType> investments = List.of(Brk12V1DashboardTest.stock(1, "FICT1", "1000.00", "10.00", 100));
            HeroValue hero = field(page, "hero");
            for (Object[] c : new Object[][]{{-11_250L, "neg"}, {11_250L, "pos"}, {0L, "muted"}}) {
                Brk12V1DashboardTest.apply(page, Brk12V1DashboardTest.dashData(investments, Map.of(1L, 100_000L), 100_000L,
                        (Long) c[0], 100_000L, Brk12V1DashboardTest.snapshots(100_000, 100_000)));
                settle(page);
                assertInk((String) c[1], (Label) hero.lookup(".hero-secondary"), "T6 hero, profit " + c[0]);
            }
        });
    }

    @Test
    void cdiComparison_isDrawnInTheTokenOfItsTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = styledDashboard(new Brk12V1DashboardTest.Quotes(false));
            set(page, "rateCdi", 0.0);
            Label cdi = field(page, "cdiComparisonLabel");
            Object[][] cases = {{1_032_200L, "pos", "above the CDI"}, {989_500L, "neg", "below the CDI"},
                    {1_000_000L, "muted", "a tie with the CDI"}, {1_000_040L, "muted", "a gap of +0,004 that shows zero"},
                    {999_960L, "muted", "a gap of -0,004 that shows zero"}};
            for (Object[] c : cases) {
                call(page, "updateCDIComparison", new Class<?>[]{LocalDate.class, List.class, long.class}, TODAY,
                        List.of(Brk12V1DashboardTest.stock(1, "FICT1", "10000.00", "10.00", 100)), (Long) c[0]);
                settle(page);
                assertInk((String) c[1], cdi, "T6 " + c[2]);
            }
        });
    }

    @Test
    void gainStrip_isDrawnInTheTokenOfItsTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = styledDashboard(new Brk12V1DashboardTest.Quotes(false));
            StatStrip strip = field(page, "strip");
            Class<?>[] types = {long.class, long.class, PortfolioMetrics.class, com.daniel.core.service.DailyTrackingUseCase.CashStatus.class};
            for (long[] gains : new long[][]{{0, 0}, {1_000, -1_000}}) {
                call(page, "applyStrip", types, 1_000_000L, 1_000_000L,
                        new PortfolioMetrics(0, 0, 0, gains[0], gains[1], 0, 0, List.of()), null);
                settle(page);
                assertInk(gains[0] == 0 ? "muted" : "pos", strip.valueLabel("Ganho realizado"), "T6 strip, realized " + gains[0]);
                assertInk(gains[0] == 0 ? "muted" : "neg", strip.valueLabel("Ganho não realizado"), "T6 strip, unrealized " + gains[1]);
            }
        });
    }

    // ── T9, T10: Análise de ativo ────────────────────────────────────────────

    private AssetAnalysisPage styledAnalysis(StockData quote) {
        AssetAnalysisPage page = analysis.page(quote);
        Brk12V1Support.styled(page.view(), 1280, 800);
        page.onBreakpoint(Breakpoint.WIDE);
        return page;
    }

    @Test
    void dayChange_isDrawnInTheTokenOfItsTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Object[][] cases = {{0.48, 1.23, "pos"}, {-0.48, -1.23, "neg"}, {0.0, 0.0, "muted"},
                    {0.001, 0.004, "muted"}, {-0.001, -0.004, "muted"}, {0.01, 0.004, "muted"}};
            for (Object[] c : cases) {
                StockData quote = Brk12V1AnalysisTest.quote((Double) c[0], (Double) c[1], 0.12);
                AssetAnalysisPage page = styledAnalysis(quote);
                page.renderQuote(quote);
                Brk12V1Support.settle(page.view(), 1280, 800);
                HeroValue hero = field(page, "hero");
                assertInk((String) c[2], (Label) hero.lookup(".hero-secondary"), "T9 day change " + c[0] + " / " + c[1] + " %");
            }
        });
    }

    @Test
    void yearChange_isDrawnInTheTokenOfItsTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Object[][] cases = {{0.12, "pos"}, {-0.05, "neg"}, {0.0, "muted"}, {0.00004, "muted"}, {-0.00004, "muted"}};
            for (Object[] c : cases) {
                StockData quote = Brk12V1AnalysisTest.quote(0.48, 1.23, (Double) c[0]);
                AssetAnalysisPage page = styledAnalysis(quote);
                page.renderQuote(quote);
                Brk12V1Support.settle(page.view(), 1280, 800);
                StatStrip year = field(page, "yearStrip");
                assertInk((String) c[1], year.valueLabel("Var. 52 sem."), "T10 year change " + c[0]);
            }
        });
    }
}

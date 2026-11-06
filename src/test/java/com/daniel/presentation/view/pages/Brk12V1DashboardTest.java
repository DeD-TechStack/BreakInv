package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.util.MoneyFormat;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.daniel.presentation.view.pages.Brk12V1Support.NBSP;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertAxisTable;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertPtBr;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertTone;
import static com.daniel.presentation.view.pages.Brk12V1Support.call;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static com.daniel.presentation.view.pages.Brk12V1Support.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1 on the Dashboard, with the JVM in en-US: every number in pt-BR (V1:RF-01), the zero without a sign and
 * neutral, a loss that reads "de perda" and a tie with the CDI that reads "igual ao CDI" (V1:RF-02 to RF-04). The page
 * gets its quotes from a fixed source and works on queues the test drains by hand (the seam of the first step).
 */
class Brk12V1DashboardTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final Deque<Runnable> worker = new ArrayDeque<>();
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

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Fixed quotes: the batch of the day's changes and the single quote behind each position's return. */
    static final class Quotes implements DashboardQuoteSource {
        final boolean token;
        final Map<String, StockData> batch = new LinkedHashMap<>();
        final Map<String, StockData> single = new HashMap<>();

        Quotes(boolean token) {
            this.token = token;
        }

        @Override public boolean hasToken() { return token; }
        @Override public long lastSuccessfulFetchMs() { return 0; }
        @Override public Optional<Double> fetchIbovespaReturn() { return Optional.empty(); }
        @Override public Map<String, StockData> fetchMultipleStocks(String tickers) { return batch; }
        @Override public StockData fetchStockData(String ticker) throws IOException { return single.get(ticker); }
    }

    static StockData quote(String ticker, double price, double change, double percent) {
        return new StockData(ticker, null, ticker + " S.A.", price, change, percent, price - change, price + 1, price - 1,
                1000L, 0, 0, 0, "BRL", 0, 0, null);
    }

    static InvestmentType stock(int id, String ticker, String invested, String price, int quantity) {
        return new InvestmentType(id, "Ativo fictício " + ticker, "ACOES", "ALTA", LocalDate.of(2026, 1, 10), null,
                new BigDecimal(invested), "ACAO", null, null, ticker, new BigDecimal(price), quantity, null);
    }

    static InvestmentType fixedIncome(int id, String invested) {
        return new InvestmentType(id, "Tesouro Prefixado 2031", "RENDA_FIXA", "ALTA", LocalDate.of(2026, 1, 10),
                new BigDecimal("12.50"), new BigDecimal(invested), "PREFIXADO", null, null, null, null, null, null);
    }

    DashboardPage page(Quotes quotes) {
        return new DashboardPage(FxStubs.emptyDaily(), quotes, worker::add, Runnable::run);
    }

    /** The value of the first snapshot, of the middle one and of the last (the evolution runs from the first to the last). */
    static TreeMap<LocalDate, Long> snapshots(long first, long last) {
        TreeMap<LocalDate, Long> snaps = new TreeMap<>();
        snaps.put(LocalDate.of(2026, 7, 1), first);
        snaps.put(LocalDate.of(2026, 8, 1), (first + last) / 2);
        snaps.put(LocalDate.of(2026, 9, 1), last);
        return snaps;
    }

    static Object dashData(List<InvestmentType> investments, Map<Long, Long> values, long patrimony, long profit,
                           long investmentValue, TreeMap<LocalDate, Long> snaps) throws Exception {
        Class<?> type = Class.forName("com.daniel.presentation.view.pages.DashboardPage$DashData");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(investments, values, patrimony, profit, investmentValue, snaps,
                LocalDate.of(2026, 5, 1), TODAY, List.of(), null, null);
    }

    static void apply(DashboardPage page, Object data) throws Exception {
        Method apply = DashboardPage.class.getDeclaredMethod("applyDashData", data.getClass(), LocalDate.class);
        apply.setAccessible(true);
        apply.invoke(page, data, TODAY);
    }

    /** Positions of R$ 1.000.000 and more, a quantity of 1.500, a fixed income, a gain of R$ 120.000,00. */
    static Object populated() throws Exception {
        List<InvestmentType> investments = List.of(
                stock(1, "FICT1", "30000.00", "20.00", 1500),
                stock(2, "FICT2", "1500000.00", "150.00", 10_000),
                fixedIncome(3, "500000.00"));
        Map<Long, Long> values = new HashMap<>();
        values.put(1L, 310_000_000L);
        values.put(2L, 150_000_000L);
        values.put(3L, 52_000_000L);
        return dashData(investments, values, 512_000_000L, 12_000_000L, 512_000_000L, snapshots(500_000_000L, 512_000_000L));
    }

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        return out;
    }

    /** The value label of the info box captioned {@code caption} in a position row. */
    static Label infoValue(Node row, String caption) {
        for (Node n : flatten(row)) {
            if (n instanceof VBox box && box.getStyleClass().contains("info-box") && box.getChildren().size() == 2
                    && box.getChildren().get(1) instanceof Label c && caption.equals(c.getText())
                    && box.getChildren().get(0) instanceof Label v) return v;
        }
        throw new AssertionError("no info box '" + caption + "' in the row");
    }

    // ── V1:RF-01 / RF-05.2: nothing en-US, nothing without thousands ─────────

    @Test
    void noTextOfTheDashboardIsEnUsOrWithoutThousands() throws Exception {
        Quotes quotes = new Quotes(true);
        quotes.batch.put("FICT1", quote("FICT1", 20.5, 0.35, 1.74));
        quotes.batch.put("FICT2", quote("FICT2", 148.0, -2.0, -1.33));
        quotes.single.put("FICT1", quote("FICT1", 20.64, 0.1, 0.5));
        quotes.single.put("FICT2", quote("FICT2", 147.0, -3.0, -2.0));
        DashboardPage[] holder = new DashboardPage[1];
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(quotes);
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            ((XYChart<?, ?>) field(page, "waterfallChart")).setAnimated(false);
            apply(page, populated());
            drain();
        });
        // the page's own runLater calls (tooltips, legend colours, axis density) land before the scan
        FxTestSupport.runAndWait(() -> {
            Parent view = holder[0].view();
            Brk12V1Support.settle(view, 1280, 800);
            assertPtBr(view, "Dashboard");
        });
    }

    @Test
    void theTwoValueAxesFollowTheTableOfRf01_6() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            assertAxisTable(field(page, "compYAxis"), "Dashboard compYAxis (Variação %)");
            XYChart<?, ?> bars = field(page, "waterfallChart");
            assertAxisTable((NumberAxis) bars.getYAxis(), "Dashboard yAxis (Valor R$)");
        });
    }

    // ── V1:RF-01.3 class E: quantities ───────────────────────────────────────

    @Test
    void aGroupedPositionWritesItsQuantityAndAveragePriceInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            InvestmentType held = stock(1, "FICT1", "30000.00", "20.00", 1500);
            Node row = (Node) call(page, "buildGroupedStockRow", new Class<?>[]{String.class, List.class, long.class, long.class},
                    "FICT1", List.of(held), 3_000_000L, 12_345_678L);
            List<String> meta = flatten(row).stream().filter(n -> n instanceof Label l && l.getText() != null && l.getText().startsWith("Qtd:"))
                    .map(n -> ((Label) n).getText()).toList();
            assertEquals(List.of("Qtd: 1.500  ·  PM: R$" + NBSP + "20,00"), meta);
            assertEquals("24,3%", infoValue(row, "Alocação").getText(), "3.000.000 of 12.345.678 in pt-BR with one place");
        });
    }

    @Test
    void anUngroupedPositionWritesItsAveragePriceRateAndShareInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            Node stockRow = (Node) call(page, "buildInvestmentRow", new Class<?>[]{InvestmentType.class, long.class, long.class},
                    stock(1, "FICT1", "30000.00", "20.00", 1500), 3_000_000L, 12_345_678L);
            assertEquals("R$" + NBSP + "20,00", infoValue(stockRow, "PM").getText());
            assertEquals("24,3%", infoValue(stockRow, "Alocação").getText());
            Node fixedRow = (Node) call(page, "buildInvestmentRow", new Class<?>[]{InvestmentType.class, long.class, long.class},
                    fixedIncome(3, "500000.00"), 52_000_000L, 512_000_000L);
            assertEquals("12,50% a.a.", infoValue(fixedRow, "Taxa").getText());
            assertEquals("10,2%", infoValue(fixedRow, "Alocação").getText());
        });
    }

    // ── V1:RF-03 T1, T2: the two variation metrics follow their own shown value ──

    private record Variation(String name, long first, long last, String invested, long cents, String pct, String moneyTone, String pctTone) {}

    @Test
    void variationMetrics_toneAndTextFollowEachShownValue() throws Exception {
        List<Variation> cases = List.of(
                new Variation("gain", 100_000, 102_500, "1000.00", 2_500, "2,50%", "pos", "pos"),
                new Variation("loss", 100_000, 97_500, "1000.00", -2_500, "-2,50%", "neg", "neg"),
                new Variation("zero", 100_000, 100_000, "1000.00", 0, "0,00%", "muted", "muted"),
                // 0,004 % shows 0,00 % (neutral) while R$ 0,40 is still a gain
                new Variation("0,004 % up", 1_000_000, 1_000_040, "10000.00", 40, "0,00%", "pos", "muted"),
                new Variation("0,004 % down", 1_000_000, 999_960, "10000.00", -40, "0,00%", "neg", "muted"));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            Label money = field(page, "metricRendimentoLabel");
            Label percent = field(page, "metricRentabilidadeLabel");
            for (Variation c : cases) {
                call(page, "updateComparisonChart",
                        new Class<?>[]{List.class, Map.class, LocalDate.class, LocalDate.class, LocalDate.class, TreeMap.class},
                        List.of(stock(1, "FICT1", c.invested(), "10.00", 100)), Map.of(1L, c.last()), LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), snapshots(c.first(), c.last()));
                assertEquals(MoneyFormat.brl(c.cents()), money.getText(), c.name() + ": Variação (R$)");
                assertTone(money, c.moneyTone(), c.name() + ": tone of Variação (R$)");
                assertEquals(c.pct(), percent.getText(), c.name() + ": Variação % do valor");
                assertTone(percent, c.pctTone(), c.name() + ": tone of Variação % do valor");
            }
        });
    }

    // ── V1:RF-03 T3: no data → a dash, neutral, nothing old left ─────────────

    @Test
    void variationMetrics_withoutDataShowADashInTheNeutralTone_neverAnOldValue() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            Label money = field(page, "metricRendimentoLabel");
            Label percent = field(page, "metricRentabilidadeLabel");
            assertEquals("—", money.getText());
            assertEquals("—", percent.getText());
            assertTone(money, "muted", "before any data");
            assertTone(percent, "muted", "before any data");

            Class<?>[] types = {List.class, Map.class, LocalDate.class, LocalDate.class, LocalDate.class, TreeMap.class};
            // a result first, so that an old value could be left behind
            call(page, "updateComparisonChart", types, List.of(stock(1, "FICT1", "1000.00", "10.00", 100)), Map.of(1L, 102_500L),
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), snapshots(100_000, 102_500));
            assertEquals("2,50%", percent.getText());

            // no investment at all (showComparisonEmpty)
            call(page, "updateComparisonChart", types, List.<InvestmentType>of(), Map.of(), LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), new TreeMap<LocalDate, Long>());
            assertEquals("—", money.getText(), "no investment: Variação (R$)");
            assertEquals("—", percent.getText(), "no investment: Variação %");
            assertTone(money, "muted", "no investment");
            assertTone(percent, "muted", "no investment");

            // a result again, then investments with no cost (the early return)
            call(page, "updateComparisonChart", types, List.of(stock(1, "FICT1", "1000.00", "10.00", 100)), Map.of(1L, 97_500L),
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), snapshots(100_000, 97_500));
            assertEquals("-2,50%", percent.getText());
            call(page, "updateComparisonChart", types, List.of(stock(1, "FICT1", "0.00", "10.00", 100)), Map.of(1L, 0L),
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), new TreeMap<LocalDate, Long>());
            assertEquals("—", money.getText(), "no cost: Variação (R$)");
            assertEquals("—", percent.getText(), "no cost: Variação %");
            assertTone(money, "muted", "no cost");
            assertTone(percent, "muted", "no cost");
        });
    }

    // ── V1:RF-03 T4: the day's rises and falls ───────────────────────────────

    private static List<String> rowTexts(VBox list) {
        List<String> rows = new ArrayList<>();
        for (Node child : list.getChildren()) {
            if (!child.getStyleClass().contains("rank-row")) continue;
            List<String> labels = new ArrayList<>();
            for (Node n : flatten(child)) if (n instanceof Label l && l.getText() != null) labels.add(l.getText());
            rows.add(String.join("|", labels));
        }
        return rows;
    }

    private static Label changeLabel(VBox list, String ticker) {
        for (Node child : list.getChildren()) {
            if (!child.getStyleClass().contains("rank-row")) continue;
            boolean mine = flatten(child).stream().anyMatch(n -> n instanceof Label l && ticker.equals(l.getText()));
            if (mine) return (Label) child.lookup(".rent-value");
        }
        throw new AssertionError("no row of " + ticker);
    }

    @Test
    void risesAndFalls_listTheShownSign_andNeverTheShownZero() throws Exception {
        Quotes quotes = new Quotes(false);
        quotes.batch.put("FICT1", quote("FICT1", 28.12, -0.34, -1.20));
        quotes.batch.put("FICT2", quote("FICT2", 150.0, 3.45, 2.35));
        quotes.batch.put("FICT3", quote("FICT3", 50.0, 0.002, 0.004));
        quotes.batch.put("FICT4", quote("FICT4", 50.0, 0.0, 0.0));
        quotes.batch.put("FICT5", quote("FICT5", 50.0, -0.002, -0.004));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(quotes);
            List<InvestmentType> investments = new ArrayList<>();
            Map<Long, Long> values = new HashMap<>();
            for (int i = 1; i <= 5; i++) {
                investments.add(stock(i, "FICT" + i, "1000.00", "10.00", 100));
                values.put((long) i, 100_000L);
            }
            call(page, "updateRankPanel", new Class<?>[]{List.class, Map.class}, investments, values);
            drain();
            VBox rises = field(page, "rankPanelAltas");
            VBox falls = field(page, "rankPanelBaixas");
            List<String> up = rowTexts(rises);
            List<String> down = rowTexts(falls);
            assertEquals(1, up.size(), "only the rise that shows above zero is listed: " + up);
            assertTrue(up.get(0).startsWith("FICT2|"), up.toString());
            assertEquals("+2,35%", changeLabel(rises, "FICT2").getText());
            assertTone(changeLabel(rises, "FICT2"), "pos", "a rise");
            assertEquals(1, down.size(), "only the fall that shows below zero is listed: " + down);
            assertTrue(down.get(0).startsWith("FICT1|"), down.toString());
            assertEquals("-1,20%", changeLabel(falls, "FICT1").getText());
            assertTone(changeLabel(falls, "FICT1"), "neg", "a fall");
            for (String hidden : List.of("FICT3", "FICT4", "FICT5")) {
                assertFalse(up.stream().anyMatch(r -> r.contains(hidden)), hidden + " shows 0,00% and is no rise: " + up);
                assertFalse(down.stream().anyMatch(r -> r.contains(hidden)), hidden + " shows 0,00% and is no fall: " + down);
            }
        });
    }

    @Test
    void whenEveryMoveShowsZero_bothListsSayThereIsNoRiseAndNoFall() throws Exception {
        Quotes quotes = new Quotes(false);
        quotes.batch.put("FICT3", quote("FICT3", 50.0, 0.002, 0.004));
        quotes.batch.put("FICT4", quote("FICT4", 50.0, 0.0, 0.0));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(quotes);
            List<InvestmentType> investments = List.of(stock(3, "FICT3", "1000.00", "10.00", 100), stock(4, "FICT4", "1000.00", "10.00", 100));
            call(page, "updateRankPanel", new Class<?>[]{List.class, Map.class}, investments, Map.of(3L, 100_000L, 4L, 100_000L));
            drain();
            VBox rises = field(page, "rankPanelAltas");
            VBox falls = field(page, "rankPanelBaixas");
            assertTrue(rowTexts(rises).isEmpty(), rowTexts(rises).toString());
            assertTrue(rowTexts(falls).isEmpty(), rowTexts(falls).toString());
            assertTrue(flatten(rises).stream().anyMatch(n -> n instanceof Label l && "Sem altas hoje".equals(l.getText())));
            assertTrue(flatten(falls).stream().anyMatch(n -> n instanceof Label l && "Sem baixas hoje".equals(l.getText())));
        });
    }

    // ── V1:RF-03 T5: the return of each position ─────────────────────────────

    private record Return(String name, String price, String text, String tone) {}

    @Test
    void groupedPositionReturn_toneAndTextFollowTheShownValue() throws Exception {
        List<Return> cases = List.of(
                new Return("gain", "12.00", "+20,00%", "pos"),
                new Return("loss", "8.00", "-20,00%", "neg"),
                new Return("zero", "10.00", "0,00%", "muted"),
                new Return("0,004 %", "10.0004", "0,00%", "muted"));
        FxTestSupport.runAndWait(() -> {
            for (Return c : cases) {
                Quotes quotes = new Quotes(true);
                quotes.single.put("FICT1", quote("FICT1", Double.parseDouble(c.price()), 0, 0));
                DashboardPage page = page(quotes);
                Node row = (Node) call(page, "buildGroupedStockRow", new Class<?>[]{String.class, List.class, long.class, long.class},
                        "FICT1", List.of(stock(1, "FICT1", "1000.00", "10.00", 100)), 100_000L, 1_000_000L);
                drain();
                Label rent = infoValue(row, "Rentabilidade");
                assertEquals(c.text(), rent.getText(), c.name());
                assertTone(rent, c.tone(), c.name());
                assertTrue(rent.getStyleClass().contains("rent-value"), c.name() + ": keeps the look of a return: " + rent.getStyleClass());
            }
        });
    }

    @Test
    void unavailableReturn_staysDimmedWithoutAnyTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            Node row = (Node) call(page, "buildGroupedStockRow", new Class<?>[]{String.class, List.class, long.class, long.class},
                    "FICT1", List.of(stock(1, "FICT1", "1000.00", "10.00", 100)), 100_000L, 1_000_000L);
            Label rent = infoValue(row, "Rentabilidade");
            assertEquals("—", rent.getText());
            assertTrue(rent.getStyleClass().contains("rent-dimmed"), rent.getStyleClass().toString());
            assertFalse(rent.getStyleClass().contains("pos") || rent.getStyleClass().contains("neg"), rent.getStyleClass().toString());
        });
    }

    @Test
    void fixedIncomeReturn_toneAndTextFollowTheShownValue() throws Exception {
        record Case(String name, long current, String text, String tone) {}
        List<Case> cases = List.of(
                new Case("gain", 102_500, "+2,50%", "pos"),
                new Case("loss", 97_500, "-2,50%", "neg"),
                new Case("zero", 100_000, "0,00%", "muted"),
                new Case("0,004 %", 100_004, "0,00%", "muted"));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            for (Case c : cases) {
                Node row = (Node) call(page, "buildInvestmentRow", new Class<?>[]{InvestmentType.class, long.class, long.class},
                        fixedIncome(3, "1000.00"), c.current(), 1_000_000L);
                Label rent = infoValue(row, "Rentabilidade");
                assertEquals(c.text(), rent.getText(), c.name());
                assertTone(rent, c.tone(), c.name());
            }
        });
    }

    @Test
    void ungroupedStockReturn_withTokenFollowsTheShownValue() throws Exception {
        record Case(String name, long current, String text, String tone) {}
        List<Case> cases = List.of(
                new Case("gain", 12_000, "+20,00%", "pos"),
                new Case("loss", 8_000, "-20,00%", "neg"),
                new Case("zero", 10_000, "0,00%", "muted"));
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(true));
            for (Case c : cases) {
                // 100 shares at R$ 10,00 average; the position is worth `current` cents
                Node row = (Node) call(page, "buildInvestmentRow", new Class<?>[]{InvestmentType.class, long.class, long.class},
                        stock(1, "FICT1", "1000.00", "10.00", 100), c.current() * 10, 1_000_000L);
                Label rent = infoValue(row, "Rentabilidade");
                assertEquals(c.text(), rent.getText(), c.name());
                assertTone(rent, c.tone(), c.name());
            }
        });
    }

    // ── V1:RF-03 T6 and V1:RF-04: the hero and the comparison with the CDI ───

    private Label heroSecondary(DashboardPage page) throws Exception {
        HeroValue hero = field(page, "hero");
        return (Label) hero.lookup(".hero-secondary");
    }

    private Label heroAfter(long profit) throws Exception {
        DashboardPage page = page(new Quotes(false));
        List<InvestmentType> investments = List.of(stock(1, "FICT1", "1000.00", "10.00", 100));
        apply(page, dashData(investments, Map.of(1L, 100_000L), 100_000L, profit, 100_000L, snapshots(100_000, 100_000)));
        return heroSecondary(page);
    }

    @Test
    void hero_aLossReadsDePerda_inTheLossTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label secondary = heroAfter(-11_250);
            assertEquals("- " + MoneyFormat.brl(11_250) + " de perda", secondary.getText());
            assertTone(secondary, "neg", "hero, loss");
        });
    }

    @Test
    void hero_aGainReadsDeGanho_inTheGainTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label secondary = heroAfter(11_250);
            assertEquals("+ " + MoneyFormat.brl(11_250) + " de ganho", secondary.getText());
            assertTone(secondary, "pos", "hero, gain");
        });
    }

    @Test
    void hero_noProfitReadsSemGanhoRegistrado_neutral() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label secondary = heroAfter(0);
            assertEquals("sem ganho registrado", secondary.getText());
            assertTone(secondary, "muted", "hero, no profit");
        });
    }

    /** The comparison with the CDI of a portfolio that cost R$ 10.000,00 and is worth {@code current} cents, with the CDI flat. */
    private Label cdiAfter(long current) throws Exception {
        DashboardPage page = page(new Quotes(false));
        set(page, "rateCdi", 0.0);
        call(page, "updateCDIComparison", new Class<?>[]{LocalDate.class, List.class, long.class},
                TODAY, List.of(stock(1, "FICT1", "10000.00", "10.00", 100)), current);
        return field(page, "cdiComparisonLabel");
    }

    @Test
    void cdi_aboveReadsAcima_inTheGainTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label cdi = cdiAfter(1_032_200);
            assertEquals("↑ 3,22% acima", cdi.getText());
            assertTone(cdi, "pos", "above the CDI");
        });
    }

    @Test
    void cdi_belowReadsAbaixo_inTheLossTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label cdi = cdiAfter(989_500);
            assertEquals("↓ 1,05% abaixo", cdi.getText());
            assertTone(cdi, "neg", "below the CDI");
        });
    }

    @Test
    void cdi_aTieReadsIgualAoCdi_neutral() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label cdi = cdiAfter(1_000_000);
            assertEquals("igual ao CDI", cdi.getText());
            assertTone(cdi, "muted", "a tie with the CDI");
        });
    }

    @Test
    void cdi_aGapThatShowsZeroIsATie_neverAcimaOrAbaixo() throws Exception {
        FxTestSupport.runAndWait(() -> {
            for (long current : new long[]{1_000_040, 999_960}) {
                Label cdi = cdiAfter(current);
                assertEquals("igual ao CDI", cdi.getText(), "a gap of ±0,004 shows 0,00");
                assertTone(cdi, "muted", "a gap that shows zero");
            }
        });
    }

    // ── the strip's gains already follow the rule: kept (mutation c) ─────────

    @Test
    void gainStrip_zeroHasNoSign_aGainHasAPlus_aLossAMinus() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(new Quotes(false));
            StatStrip strip = field(page, "strip");
            Class<?>[] types = {long.class, long.class, PortfolioMetrics.class, com.daniel.core.service.DailyTrackingUseCase.CashStatus.class};
            for (long[] gains : new long[][]{{0, 0}, {1_000, -1_000}}) {
                PortfolioMetrics metrics = new PortfolioMetrics(0, 0, 0, gains[0], gains[1], 0, 0, List.of());
                call(page, "applyStrip", types, 1_000_000L, 1_000_000L, metrics, null);
                if (gains[0] == 0) {
                    assertEquals(MoneyFormat.brl(0), strip.valueLabel("Ganho realizado").getText());
                    assertEquals(MoneyFormat.brl(0), strip.valueLabel("Ganho não realizado").getText());
                    assertTone(strip.valueLabel("Ganho realizado"), "muted", "realized zero");
                    assertTone(strip.valueLabel("Ganho não realizado"), "muted", "unrealized zero");
                } else {
                    assertEquals("+ " + MoneyFormat.brl(1_000), strip.valueLabel("Ganho realizado").getText());
                    assertEquals("- " + MoneyFormat.brl(1_000), strip.valueLabel("Ganho não realizado").getText());
                    assertTone(strip.valueLabel("Ganho realizado"), "pos", "realized gain");
                    assertTone(strip.valueLabel("Ganho não realizado"), "neg", "unrealized loss");
                }
            }
        });
    }

    // ── DESIGN rule 6: the thousands dot lengthens texts; the areas this unit changes keep every one whole ──

    @Test
    void theChangedAreasKeepEveryTextWhole() throws Exception {
        Quotes quotes = new Quotes(false);
        quotes.batch.put("FICT1", quote("FICT1", 28.12, -0.34, -1.20));
        quotes.batch.put("FICT2", quote("FICT2", 150.00, 3.45, 2.35));
        quotes.batch.put("FICT3", quote("FICT3", 50.00, 0.002, 0.004));
        DashboardPage[] holder = new DashboardPage[1];
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = page(quotes);
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            set(page, "rateCdi", 0.0);
            ((XYChart<?, ?>) field(page, "waterfallChart")).setAnimated(false);
            List<InvestmentType> investments = List.of(
                    stock(1, "FICT1", "2812500.00", "10.00", 1_000_000),
                    stock(2, "FICT2", "1500000.00", "10.00", 10_000),
                    stock(3, "FICT3", "50000.00", "10.00", 1_000));
            Map<Long, Long> values = Map.of(1L, 281_238_750L, 2L, 150_000_000L, 3L, 5_000_000L);
            apply(page, dashData(investments, values, 436_238_750L, -11_250L, 436_238_750L, snapshots(436_238_750L, 436_238_750L)));
            drain();
        });
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = holder[0];
            Brk12V1Support.settle(page.view(), 1280, 800);
            for (String name : List.of("hero", "waterfallChart", "rankPair")) {
                Parent area = field(page, name);
                Brk12V1Support.assertNotCut(area, "Dashboard " + name);
            }
            Label metric = field(page, "metricRendimentoLabel");
            Brk12V1Support.assertNotCut((Parent) metric.getParent().getParent(), "Dashboard variation metrics");
        });
    }

    // ── CSS: a neutral metric is drawn in the secondary colour (the later .metric-lg must not win) ──

    @Test
    void aMutedMetricIsDrawnInTheSecondaryTextColour() {
        FxTestSupport.runAndWait(() -> {
            Label metric = new Label("—");
            metric.getStyleClass().addAll("metric-lg", "muted");
            StackPane host = new StackPane(metric);
            Brk12V1Support.styled(host, 300, 100);
            host.applyCss();
            Color seen = (Color) metric.getTextFill();
            Color secondary = Color.web("#A8B6C6"); // -color-text-2
            assertEquals(secondary.getRed(), seen.getRed(), 0.01, "red of " + seen);
            assertEquals(secondary.getGreen(), seen.getGreen(), 0.01, "green of " + seen);
            assertEquals(secondary.getBlue(), seen.getBlue(), 0.01, "blue of " + seen);
        });
    }
}

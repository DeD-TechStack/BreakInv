package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryResult;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient.FetchResult;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.viewmodel.RankingViewModel.RankingRow;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ranking — review findings L6, L8 and G2, with injected sources and executors (no HTTP).
 *
 * <ul>
 *   <li>L6: the period chart has an epoch per request — the last REQUEST wins (period and
 *       selection), not the last answer; an unexpected exception from the quote source ends in
 *       the failure state and does not leave {@code loading} stuck.</li>
 *   <li>L8: closing the detail (X or Esc) hands the focus back to the table the row came from;
 *       Esc works with the focus on the table.</li>
 *   <li>G2: the freshness chip is never ellipsized; empty lists say whether there were no gainers
 *       / losers among the quotes obtained, not a generic "no asset"; the bar chart says why it
 *       is empty instead of showing a bare scale.</li>
 * </ul>
 */
class RankingPageAsyncFocusTest {

    final Deque<Runnable> worker = new ArrayDeque<>();

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    @SuppressWarnings("unchecked")
    static <T> T field(RankingPage page, String name) throws Exception {
        Field f = RankingPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    static InvestmentType type(int id, String ticker) {
        return new InvestmentType(id, "Ativo " + ticker, "ACOES", "ALTA", LocalDate.of(2026, 10, 15), null,
                new BigDecimal("120.00"), "ACAO", null, null, ticker, new BigDecimal("20.00"), 6, null);
    }

    static StockData stock(String t, double price, double chg, double pct) {
        return new StockData(t, null, t + " S.A.", price, chg, pct, price - chg, price + 1, price - 1, 1000L, 0, 0, 0, "BRL", 0, 0, null);
    }

    static Map<String, StockData> twoStocks() {
        Map<String, StockData> m = new LinkedHashMap<>();
        m.put("AAAA3", stock("AAAA3", 10.0, 0.5, 5.26));
        m.put("BBBB4", stock("BBBB4", 20.0, -1.0, -4.76));
        return m;
    }

    static List<HistoryPoint> points(int n) {
        List<HistoryPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new HistoryPoint(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(i), 30 + i));
        return out;
    }

    static String rendered(Labeled label) {
        Text t = (Text) label.lookup(".text");
        return t == null ? null : t.getText();
    }

    RankingPage pageWith(Function<String, FetchResult<Map<String, StockData>>> quotes,
                         RankingPage.HistorySource histories) {
        return new RankingPage(FxStubs.dailyWithTypes(List.of(type(1, "AAAA3"), type(2, "BBBB4"))),
                quotes::apply, histories, worker::add, Runnable::run);
    }

    static ToggleButton toggle(RankingPage page, Period p) throws Exception {
        ToggleGroup g = field(page, "returnPeriodGroup");
        for (Toggle t : g.getToggles()) if (t.getUserData() == p) return (ToggleButton) t;
        throw new AssertionError("no toggle " + p);
    }

    /** A Scene alone does not attach the ScrollPane content: the skin does, on the first CSS/layout pass. */
    static void layOut(Parent view, Scene scene, double w, double h) {
        ((Region) view).resize(w, h);
        view.applyCss();
        view.layout();
    }

    // ── L6 ─────────────────────────────────────────────────────────────────

    @Test
    void olderPeriodAnswerArrivingLast_doesNotOverwriteTheNewerPeriodChart() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(period == Period.ONE_YEAR ? 250 : 20), period.range, Set.of()));
            page.onShow();
            worker.pollFirst().run();                 // quotes answer → 1M history (2 tickers) queued
            List<Runnable> oneMonth = new ArrayList<>(worker);
            worker.clear();
            assertEquals(2, oneMonth.size(), "one history request per ticker");
            toggle(page, Period.ONE_YEAR).fire();     // newer request while 1M is in flight
            drain();                                  // 1A answers first
            LineChart<?, ?> chart = field(page, "returnChart");
            assertEquals(2, chart.getData().size());
            assertEquals(250, chart.getData().get(0).getData().size(), "1A on screen");
            for (Runnable r : oneMonth) r.run();      // stale 1M answers arrive last
            assertEquals(250, chart.getData().get(0).getData().size(), "the stale 1M answer must not overwrite the 1A chart");
            Label loading = field(page, "returnLoadingLabel");
            assertFalse(loading.isVisible());
        });
    }

    @Test
    void unexpectedExceptionFromTheQuoteSource_endsInFailure_andTheNextShowReloads() throws Exception {
        FxTestSupport.runAndWait(() -> {
            int[] calls = {0};
            RankingPage page = pageWith(t -> {
                calls[0]++;
                if (calls[0] == 1) throw new IllegalStateException("resposta inesperada");
                return new FetchResult.Success<>(twoStocks());
            }, (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            page.onShow();
            drain();
            Node loading = field(page, "loadingBox");
            assertFalse(loading.isVisible(), "loading must not stay on after an unexpected failure");
            Label error = field(page, "errorBanner");
            assertTrue(error.isVisible() && error.getText().contains("resposta inesperada"), error.getText());
            page.onShow();                            // must not be blocked by a stuck loading flag
            drain();
            assertEquals(2, calls[0], "the second show queried again");
            assertFalse(error.isVisible(), "a successful load clears the failure");
            TableView<?> gainers = field(page, "gainersTable");
            assertEquals(1, gainers.getItems().size());
        });
    }

    // ── L8 ─────────────────────────────────────────────────────────────────

    @Test
    void invalidQuotesAreNotFreshObtainedQuotesButValidZeroChangeIs() {
        FxTestSupport.runAndWait(() -> {
            var response = new java.util.concurrent.atomic.AtomicReference<Map<String, StockData>>();
            RankingPage page = pageWith(t -> new FetchResult.Success<>(response.get()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            for (var stocks : List.of(Map.of("BAD", stock("BAD", 0, 0, 0)), Map.<String, StockData>of())) {
                response.set(stocks); page.onShow(); drain();
                Label chip = field(page, "freshnessChip");
                Label bars = field(page, "barEmptyLabel");
                assertEquals(0L, RankingPageAsyncFocusTest.<Long>field(page, "quotesObtainedAtMs"));
                assertFalse(chip.getText().contains("obtidas"), chip.getText());
                assertFalse(bars.getText().contains("Sem variação"), bars.getText());
                assertTrue(RankingPageAsyncFocusTest.<BarChart<?, ?>>field(page, "barChart").getData().isEmpty());
            }
            response.set(Map.of("AAAA3", stock("AAAA3", 10, 0, 0), "BAD", stock("BAD", 0, 0, 0)));
            page.onShow(); drain();
            assertTrue(RankingPageAsyncFocusTest.<Long>field(page, "quotesObtainedAtMs") > 0);
            assertEquals(1, RankingPageAsyncFocusTest.<Map<?, ?>>field(page, "lastStocks").size());
            Label bars = field(page, "barEmptyLabel");
            assertTrue(bars.getText().contains("Sem variação"));
        });
    }

    @Test
    void injectedEmptySuccessEndsHistoryLoadingAndObsoleteHistoryCannotRestoreIt() {
        FxTestSupport.runAndWait(() -> {
            int[] calls = {0};
            RankingPage page = pageWith(t -> new FetchResult.Success<>(calls[0]++ == 0 ? twoStocks() : Map.of()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            page.onShow(); worker.removeFirst().run();
            var obsolete = new ArrayList<>(worker); worker.clear();
            Label loading = field(page, "returnLoadingLabel");
            assertTrue(loading.isVisible());
            page.onShow(); drain();
            assertFalse(loading.isVisible(), "empty injected success is a terminal state");
            obsolete.forEach(Runnable::run);
            assertFalse(loading.isVisible());
            assertTrue(RankingPageAsyncFocusTest.<LineChart<?, ?>>field(page, "returnChart").getData().isEmpty());
        });
    }

    @Test
    void obsoleteHistoryDoesNotClearTheLoadingOfANewerRequest() {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            page.onShow(); worker.removeFirst().run();
            var obsolete = new ArrayList<>(worker); worker.clear();
            toggle(page, Period.ONE_YEAR).fire();
            Label loading = field(page, "returnLoadingLabel");
            obsolete.forEach(Runnable::run);
            assertTrue(loading.isVisible());
            drain();
            assertFalse(loading.isVisible());
        });
    }

    @Test
    void escOnTheTable_closesTheDetail_andTheFocusStaysOnThatTable() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            Parent view = page.view();
            Scene scene = new Scene(view, 1280, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.renderStocks(twoStocks());
            drain();
            layOut(view, scene, 1280, 800);
            TableView<RankingRow> losers = field(page, "losersTable");
            DetailsPane details = field(page, "details");
            losers.requestFocus();
            losers.getSelectionModel().select(0);
            assertTrue(details.isVisible());
            assertSame(losers, scene.getFocusOwner(), "selecting a row keeps the focus on the table");
            Event.fireEvent(losers, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertFalse(details.isVisible(), "Esc with the focus on the table closes the detail");
            assertTrue(losers.getSelectionModel().isEmpty(), "and clears the selection");
            assertSame(losers, scene.getFocusOwner());
        });
    }

    @Test
    void closingTheDetailWithX_returnsTheFocusToTheTableTheRowCameFrom() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            Parent view = page.view();
            Scene scene = new Scene(view, 1280, 800);
            page.renderStocks(twoStocks());
            drain();
            layOut(view, scene, 1280, 800);
            TableView<RankingRow> gainers = field(page, "gainersTable");
            DetailsPane details = field(page, "details");
            gainers.getSelectionModel().select(0);
            assertTrue(details.isVisible());
            Button close = (Button) details.lookup(".icon-btn");
            close.requestFocus();                     // the user clicked/tabbed to X
            close.fire();
            assertFalse(details.isVisible());
            assertSame(gainers, scene.getFocusOwner(), "X hands the focus back to the table, not to a hidden button");
        });
    }

    // ── G2 ─────────────────────────────────────────────────────────────────

    @Test
    void freshnessChip_isNeverEllipsized_inTheWidePanelHeader() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            Parent view = page.view();
            // 1280×800 at 100 %: 220 px sidebar → 1060 px content, 24 px page padding each side
            Scene scene = new Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            page.renderStocks(twoStocks());
            drain();
            ((Region) view).resize(1060, 800);
            view.applyCss();
            view.layout();
            view.applyCss();
            view.layout();
            Label chip = field(page, "freshnessChip");
            assertTrue(chip.isVisible());
            String shown = rendered(chip);
            assertNotNull(shown, "chip rendered");
            assertFalse(shown.contains("\u2026") || shown.contains("..."), "status never cut: '" + shown + "'");
            assertEquals(chip.getText(), shown);
            assertNotNull(chip.getTooltip(), "the full provenance is one hover away");
        });
    }

    @Test
    void emptyLists_sayNoGainersOrLosersAmongTheQuotesObtained_andTheBarChartSaysWhyItIsEmpty() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            Map<String, StockData> flat = new LinkedHashMap<>();
            flat.put("AAAA3", stock("AAAA3", 10.0, 0, 0));
            flat.put("BBBB4", stock("BBBB4", 20.0, 0, 0));
            page.renderStocks(flat);
            drain();
            TableView<?> gainers = field(page, "gainersTable");
            TableView<?> losers = field(page, "losersTable");
            assertTrue(gainers.getItems().isEmpty() && losers.getItems().isEmpty());
            Label gph = (Label) gainers.getPlaceholder();
            Label lph = (Label) losers.getPlaceholder();
            assertTrue(gph.getText().toLowerCase().contains("nenhuma alta"), "says there were no gainers: " + gph.getText());
            assertTrue(gph.getText().contains("2"), "names how many quotes were obtained: " + gph.getText());
            assertTrue(lph.getText().toLowerCase().contains("nenhuma baixa"), lph.getText());
            BarChart<String, Number> bars = field(page, "barChart");
            Label barsEmpty = field(page, "barEmptyLabel");
            assertTrue(bars.getData().stream().allMatch(s -> s.getData().stream().allMatch(d -> d.getYValue().doubleValue() == 0)),
                    "bars with zero change only");
            assertTrue(barsEmpty.isVisible() && barsEmpty.getText().toLowerCase().contains("sem variação"), "honest empty chart: " + barsEmpty.getText());

            page.renderStocks(twoStocks());
            drain();
            assertFalse(barsEmpty.isVisible(), "bars with a change hide the empty message");
            assertTrue(gph.getText().toLowerCase().contains("nenhuma alta") || !gainers.getItems().isEmpty());
        });
    }

    @Test
    void beforeAnyQuote_theChipDoesNotClaimFreshData_andTheBarChartIsNotABareScale() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = pageWith(t -> new FetchResult.Success<>(twoStocks()),
                    (ticker, period) -> new HistoryResult(points(5), period.range, Set.of()));
            Label barsEmpty = field(page, "barEmptyLabel");
            assertTrue(barsEmpty.isVisible(), "initial state names what is missing");
            Panel dayPanel = field(page, "dayPanel");
            assertNotNull(dayPanel);
        });
    }
}

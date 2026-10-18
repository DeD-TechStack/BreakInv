package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryResult;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient.FetchResult;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Análise de ativo — review findings L2 and L5, reproduced with injected sources and executors
 * (no HTTP, no timing): responses are delivered by hand, in any order.
 *
 * <ul>
 *   <li>L2: two period changes for the same ticker — the SLOWER (older) answer must not overwrite
 *       the chart nor re-apply its permitted ranges over the newer selection.</li>
 *   <li>L5: an unexpected RuntimeException from the quote source must end in the failure state,
 *       never leave the page loading with "Buscar" disabled.</li>
 * </ul>
 */
class AssetAnalysisPageAsyncTest {

    final Deque<Runnable> worker = new ArrayDeque<>();

    @SuppressWarnings("unchecked")
    static <T> T field(AssetAnalysisPage page, String name) throws Exception {
        Field f = AssetAnalysisPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    static StockData petr() {
        return new StockData("PETR4", null, "Petróleo Brasileiro S.A.", 38.52, -0.48, -1.23, 39.00, 39.20, 38.10,
                12_345_678L, 42.10, 30.05, 36.80, "BRL", 8.5, 0.12, null);
    }

    static List<HistoryPoint> points(int n) {
        List<HistoryPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new HistoryPoint(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(i), 30 + i));
        return out;
    }

    static ToggleButton toggle(AssetAnalysisPage page, Period p) throws Exception {
        ToggleGroup g = field(page, "periodGroup");
        for (Toggle t : g.getToggles()) if (t.getUserData() == p) return (ToggleButton) t;
        throw new AssertionError("no toggle " + p);
    }

    @Test
    void olderPeriodResponse_arrivingAfterTheNewerOne_isDiscarded_includingItsPermittedRanges() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = new AssetAnalysisPage(
                    ticker -> new FetchResult.Success<>(petr()),
                    (ticker, period) -> switch (period) {
                        // the 1-month answer is the slow one and claims only "1mo" is permitted
                        case ONE_MONTH -> new HistoryResult(points(20), "1mo", Set.of("1mo"));
                        case ONE_YEAR -> new HistoryResult(points(250), "1y", Set.of());
                        default -> new HistoryResult(points(5), period.range, Set.of());
                    },
                    worker::add, Runnable::run);
            ComboBox<?> combo = field(page, "tickerCombo");
            ((ComboBox<Object>) combo).getEditor().setText("PETR4");
            Button search = field(page, "searchBtn");
            search.fire();
            assertEquals(1, worker.size(), "quote request queued");
            worker.pollFirst().run();                       // quote answers → chart 1M + MAX probe queued
            assertEquals(2, worker.size(), "1M history and the MAX probe queued");
            Runnable oneMonth = worker.pollFirst();
            Runnable probe = worker.pollFirst();

            ToggleButton oneYear = toggle(page, Period.ONE_YEAR);
            oneYear.fire();                                 // user switches the period while 1M is still in flight
            assertEquals(1, worker.size(), "1A history queued");
            worker.pollFirst().run();                       // the NEWER request answers first
            AreaChart<?, ?> chart = field(page, "areaChart");
            assertEquals(1, chart.getData().size());
            assertEquals(250, chart.getData().get(0).getData().size(), "1A on screen");

            oneMonth.run();                                 // the OLDER request answers last
            assertEquals(250, chart.getData().get(0).getData().size(), "the stale 1M answer must not overwrite the 1A chart");
            assertTrue(oneYear.isSelected(), "the user's period selection survives the stale answer");
            assertTrue(oneYear.isVisible(), "stale permitted ranges are not applied over the newer request");
            Label loading = field(page, "chartLoadingLabel");
            assertFalse(loading.isVisible());
            probe.run();                                    // probe with no restriction: nothing changes
            assertTrue(oneYear.isSelected());
        });
    }

    @Test
    void unexpectedExceptionFromTheQuoteSource_endsInTheFailureState_andReenablesSearch() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = new AssetAnalysisPage(
                    ticker -> { throw new IllegalStateException("resposta inesperada (campo ausente)"); },
                    (ticker, period) -> new HistoryResult(points(3), period.range, Set.of()),
                    worker::add, Runnable::run);
            ComboBox<?> combo = field(page, "tickerCombo");
            ((ComboBox<Object>) combo).getEditor().setText("PETR4");
            Button search = field(page, "searchBtn");
            search.fire();
            assertTrue(search.isDisabled(), "locked while the query runs");
            worker.pollFirst().run();
            Node loading = field(page, "loadingBox");
            assertFalse(loading.isVisible(), "loading must not stay on after an unexpected failure");
            Label error = field(page, "errorBanner");
            assertTrue(error.isVisible() && error.isManaged(), "the failure is explicit on the page");
            assertTrue(error.getText().contains("resposta inesperada"), error.getText());
            assertFalse(search.isDisabled(), "Buscar is usable again");
        });
    }

    @Test
    void unexpectedExceptionFromTheHistorySource_isShownOnTheChart_notLeftLoading() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = new AssetAnalysisPage(
                    ticker -> new FetchResult.Success<>(petr()),
                    (ticker, period) -> { throw new IllegalStateException("histórico quebrado"); },
                    worker::add, Runnable::run);
            ComboBox<?> combo = field(page, "tickerCombo");
            ((ComboBox<Object>) combo).getEditor().setText("PETR4");
            ((Button) field(page, "searchBtn")).fire();
            worker.pollFirst().run();
            Runnable chart = worker.pollFirst();
            chart.run();
            Label loading = field(page, "chartLoadingLabel");
            assertFalse(loading.isVisible());
            Label empty = field(page, "chartEmptyLabel");
            assertTrue(empty.isVisible() && empty.getText().contains("histórico quebrado"), empty.getText());
            worker.pollFirst().run(); // the probe failing is non-critical and must not throw
        });
    }
}

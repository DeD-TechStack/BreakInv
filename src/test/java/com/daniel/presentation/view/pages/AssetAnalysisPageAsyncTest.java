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

    /**
     * R2-16: "Histórico de preço" had no dates on its time axis and no unit on its value axis — only
     * the hover told when and how much. The axis names the dates; the hover still gives the exact point.
     */
    @Test
    void priceHistoryShowsDatesOnItsTimeAxisAndTheCurrencyOnItsValueAxis() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = new AssetAnalysisPage(
                    ticker -> new FetchResult.Success<>(petr()),
                    (ticker, period) -> new HistoryResult(points(20), period.range, Set.of()),
                    worker::add, Runnable::run);
            ComboBox<?> combo = field(page, "tickerCombo");
            ((ComboBox<Object>) combo).getEditor().setText("PETR4");
            ((Button) field(page, "searchBtn")).fire();
            worker.pollFirst().run();
            worker.pollFirst().run();
            javafx.scene.chart.NumberAxis x = field(page, "xAxis");
            javafx.scene.chart.NumberAxis y = field(page, "yAxis");
            assertTrue(x.isTickLabelsVisible() && x.isTickMarkVisible(), "dates are read on the time axis");
            long jan2 = LocalDateTime.of(2026, 1, 2, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC);
            String label = x.getTickLabelFormatter().toString(jan2);
            assertTrue(label != null && label.contains("02"), "a tick reads as a date: '" + label + "'");
            assertTrue(y.getLabel() != null && y.getLabel().contains("R$"), "the value axis names its unit: " + y.getLabel());
        });
    }

    static javafx.scene.paint.Color firstColor(javafx.scene.paint.Paint paint) {
        if (paint instanceof javafx.scene.paint.Color c) return c;
        if (paint instanceof javafx.scene.paint.LinearGradient g) return g.getStops().get(0).getColor();
        throw new AssertionError("unexpected paint " + paint);
    }

    /**
     * R3-08: under the green line the area came out brown-red — the stylesheet aimed at
     * `.chart-series-area`, which no node carries, so the theme's first chart colour filled it — and the
     * value axis read 34.704 / 33.856 / … (a sixth of the padded range). The fill wears the line's hue;
     * the ticks are round steps (1, 2, 2.5 or 5 × 10ⁿ) on round bounds that hold every price.
     */
    @Test
    @SuppressWarnings("unchecked")
    void priceAreaWearsTheLineHueAndTheValueAxisStepsRoundly() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = new AssetAnalysisPage(
                    ticker -> new FetchResult.Success<>(petr()),
                    (ticker, period) -> new HistoryResult(points(20), period.range, Set.of()),
                    worker::add, Runnable::run);
            var scene = new javafx.scene.Scene(page.view(), 1060, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ComboBox<?> combo = field(page, "tickerCombo");
            ((ComboBox<Object>) combo).getEditor().setText("PETR4");
            ((Button) field(page, "searchBtn")).fire();
            worker.pollFirst().run();
            worker.pollFirst().run();
            for (int pass = 0; pass < 3; pass++) { page.view().applyCss(); page.view().layout(); }
            var fill = (javafx.scene.shape.Path) page.view().lookup(".chart-series-area-fill");
            var line = (javafx.scene.shape.Path) page.view().lookup(".chart-series-area-line");
            assertNotNull(fill);
            assertNotNull(line);
            double hueGap = Math.abs(firstColor(fill.getFill()).getHue() - firstColor(line.getStroke()).getHue());
            assertTrue(Math.min(hueGap, 360 - hueGap) < 15, "area " + fill.getFill() + " under a line " + line.getStroke());
            AreaChart<Number, Number> chart = field(page, "areaChart");
            double min = chart.getData().get(0).getData().stream().mapToDouble(d -> d.getYValue().doubleValue()).min().orElseThrow();
            double max = chart.getData().get(0).getData().stream().mapToDouble(d -> d.getYValue().doubleValue()).max().orElseThrow();
            javafx.scene.chart.NumberAxis y = field(page, "yAxis");
            assertRoundAxis(y, min, max);
        });
    }

    static void assertRoundAxis(javafx.scene.chart.NumberAxis y, double min, double max) {
        double unit = y.getTickUnit();
        double mantissa = unit / Math.pow(10, Math.floor(Math.log10(unit)));
        assertTrue(java.util.stream.DoubleStream.of(1, 2, 2.5, 5).anyMatch(m -> Math.abs(m - mantissa) < 1e-9), "round step: " + unit);
        for (double bound : new double[]{y.getLowerBound(), y.getUpperBound()}) {
            double steps = bound / unit;
            assertTrue(Math.abs(steps - Math.rint(steps)) < 1e-6, "bound " + bound + " on the step " + unit);
        }
        assertTrue(y.getLowerBound() <= min && y.getUpperBound() >= max, "every price inside " + y.getLowerBound() + ".." + y.getUpperBound());
        double ticks = (y.getUpperBound() - y.getLowerBound()) / unit;
        assertTrue(ticks >= 3 && ticks <= 9, ticks + " intervals");
    }
}

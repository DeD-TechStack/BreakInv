package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Análise de ativo (direction A): the search is the first thing on the page, every query state
 * (idle, loading, error, no result, result) has its own honest presentation, the price is the
 * hero with its provenance, secondary indicators are grouped by importance, and the period
 * controls live with the chart. No network is touched: states are driven directly.
 */
class AssetAnalysisPageLayoutTest {

    AssetAnalysisPage page;
    Parent view;

    @BeforeEach
    void setUp() {
        FxTestSupport.runAndWait(() -> {
            page = new AssetAnalysisPage();
            view = page.view();
        });
    }

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        return out;
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = AssetAnalysisPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    Pane pageRoot() {
        return (Pane) ((ScrollPane) view).getContent();
    }

    int indexOfAncestorIn(Node target) {
        Pane root = pageRoot();
        for (int i = 0; i < root.getChildren().size(); i++) {
            if (flatten(root.getChildren().get(i)).contains(target)) return i;
        }
        return -1;
    }

    static StockData petr() {
        return new StockData("PETR4", null, "Petróleo Brasileiro S.A.", 38.52, -0.48, -1.23, 39.00, 39.20, 38.10,
                12_345_678L, 42.10, 30.05, 36.80, "BRL", 8.5, 0.12, null);
    }

    @Test
    void narrowSearchPreservesTheActualActionText() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 380, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.NARROW);
            ((javafx.scene.layout.Region)view).resize(380, 720);
            for (int pass=0; pass<6; pass++) {view.applyCss();view.layout();}
            Button search = field("searchBtn");
            assertEquals("Buscar", ((javafx.scene.text.Text)search.lookup(".text")).getText());
            assertTrue(search.getWidth()+1 >= search.prefWidth(-1));
        });
    }

    static boolean paints(javafx.scene.paint.Paint paint) {
        return paint != null && !(paint instanceof javafx.scene.paint.Color c && c.getOpacity() == 0);
    }

    /** A-V7: the editable ticker combo is ONE field: its editor draws no second frame or fill inside it. */
    @Test
    void editableTickerComboDrawsASingleFrameAlignedWithTheSearchButton() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 1060, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 720);
            for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
            ComboBox<?> combo = field("tickerCombo");
            Button search = field("searchBtn");
            TextField editor = combo.getEditor();
            assertTrue(Math.abs(combo.getHeight() - search.getHeight()) <= 2,
                    "one field as tall as its button: combo " + combo.getHeight() + " vs button " + search.getHeight());
            boolean innerBorder = editor.getBorder() != null && editor.getBorder().getStrokes().stream()
                    .anyMatch(s -> paints(s.getTopStroke()) && s.getWidths().getTop() > 0);
            assertFalse(innerBorder, "the editor draws a second border inside the combo");
            boolean innerFill = editor.getBackground() != null && editor.getBackground().getFills().stream()
                    .anyMatch(f -> paints(f.getFill()));
            assertFalse(innerFill, "the editor paints a second field inside the combo");
        });
    }

    /** A-V10: at medium width the summary stacks, so the day strip has the full row: one line of four, not 2×2. */
    @Test
    void mediumWidthDayStripUsesTheWholeRow() {
        FxTestSupport.runAndWait(() -> {
            page.renderQuote(petr());
            var scene = new javafx.scene.Scene(view, 760, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.MEDIUM);
            ((javafx.scene.layout.Region) view).resize(760, 720);
            for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
            StatStrip day = field("dayStrip");
            assertEquals(4, day.columns(), "medium asks for four columns");
            assertEquals(4, day.effectiveColumns(), "and four fit at 760 px");
        });
    }

    /** E-V2 class: the search error banner spans the search panel like the field row, even with a short message. */
    @Test
    void warningBannerSpansItsContainerEvenWithAShortMessage() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 1060, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 720);
            assertEquals(java.util.List.of(), FxTestSupport.messageBoxesShortOfTheirContainer(view, ".warning-banner"));
        });
    }

    @Test
    void searchStatesAreIntegratedAndPriceSharesTheDaySummaryBeforeTheChart() {
        FxTestSupport.runAndWait(() -> {
            Node search = pageRoot().lookup(".asset-search-workspace");
            assertNotNull(search, "search and its feedback belong to one workspace");
            assertTrue(flatten(search).contains(field("tickerCombo")));
            assertTrue(flatten(search).contains(field("errorBanner")));
            assertTrue(flatten(search).contains(field("emptyState")));
            page.renderQuote(petr());
            Node summary = pageRoot().lookup(".asset-summary");
            assertNotNull(summary);
            assertTrue(flatten(summary).contains(field("hero")));
            assertTrue(flatten(summary).contains(field("dayStrip")));
            Pane result = field("resultBox");
            assertTrue(result.getChildren().indexOf(field("chartPanel")) < result.getChildren().indexOf(field("yearStrip")),
                    "history is the workspace; year indicators are secondary");
        });
    }

    @Test
    void searchIsAboveEverything_andHasAnAccessibleName() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ComboBox<?> ticker = field("tickerCombo");
            Button search = field("searchBtn");
            assertNotNull(ticker.getAccessibleText());
            assertNotNull(search.getTooltip());
            HeroValue hero = field("hero");
            assertTrue(indexOfAncestorIn(ticker) < indexOfAncestorIn(hero), "search before the result");
            assertTrue(indexOfAncestorIn(search) == indexOfAncestorIn(ticker), "button beside the field");
        });
    }

    @Test
    void states_idleLoadingErrorAndNoResult_areExclusiveAndHonest() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Node empty = field("emptyState");
            Node loading = field("loadingBox");
            Label error = field("errorBanner");
            Node result = field("resultBox");
            assertTrue(empty.isVisible() && !loading.isVisible() && !error.isVisible() && !result.isVisible(), "idle: only the empty state");

            page.showLoading("PETR4");
            assertTrue(loading.isVisible() && loading.isManaged(), "loading shown");
            assertFalse(empty.isVisible());
            assertTrue(((Button) field("searchBtn")).isDisabled(), "no second query while one runs");

            page.showFailure("Sem conexão. Verifique sua internet.");
            assertTrue(error.isVisible() && error.isManaged(), "error stays on the page, not only in a toast");
            assertEquals("Sem conexão. Verifique sua internet.", error.getText());
            assertFalse(loading.isVisible());
            assertFalse(result.isVisible(), "no stale result under an error");
            assertFalse(((Button) field("searchBtn")).isDisabled());

            page.showNoResult("XPTO9");
            assertFalse(error.isVisible());
            Label noResult = field("noResultLabel");
            assertTrue(noResult.isVisible());
            assertTrue(noResult.getText().contains("XPTO9"));
        });
    }

    @Test
    void result_priceIsTheHero_withChangeAndProvenance_secondaryIndicatorsGrouped() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.renderQuote(petr());
            HeroValue hero = field("hero");
            Node result = field("resultBox");
            assertTrue(result.isVisible() && result.isManaged());
            assertFalse(((Node) field("emptyState")).isVisible());
            assertEquals("R$ 38,52", hero.valueLabel().getText());
            Label secondary = (Label) hero.lookup(".hero-secondary");
            assertTrue(secondary.getText().contains("-1,23%"), secondary.getText());
            assertTrue(secondary.getText().contains("0,48"), secondary.getText());
            assertTrue(secondary.getStyleClass().contains("neg"));
            Label context = (Label) hero.lookup(".hero-context");
            assertTrue(context.getText().contains("Petróleo Brasileiro"), context.getText());
            assertTrue(context.getText().contains("PETR4"), context.getText());
            assertTrue(context.getText().toLowerCase().contains("brapi"), "provenance named: " + context.getText());

            StatStrip day = field("dayStrip");
            assertEquals(List.of("Abertura", "Máx. do dia", "Mín. do dia", "Volume"), day.labels());
            assertEquals("R$ 39,00", day.valueLabel("Abertura").getText());
            assertEquals("12,3M", day.valueLabel("Volume").getText());
            StatStrip year = field("yearStrip");
            assertTrue(year.labels().contains("Máx. 52 sem."), year.labels().toString());
            assertTrue(year.labels().contains("Dividend yield"), year.labels().toString());
            assertEquals("+12,00%", year.valueLabel("Var. 52 sem.").getText());
            assertEquals("8,50%", year.valueLabel("Dividend yield").getText());
        });
    }

    @Test
    void periodControlsLiveWithTheChart_inTheHeaderWhenWide_underTheTitleWhenNarrow() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Panel chart = field("chartPanel");
            Node periodBar = field("periodBar");
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(flatten(chart).contains(periodBar), "period toggles inside the chart block");
            assertFalse(chart.body().getChildren().contains(periodBar), "wide: in the panel header");
            page.onBreakpoint(Breakpoint.NARROW);
            assertSame(periodBar, chart.body().getChildren().get(0), "narrow: first row of the body");
            StatStrip day = field("dayStrip");
            assertEquals(2, day.columns());
            page.onBreakpoint(Breakpoint.WIDE);
            assertEquals(2, day.columns(), "day metrics form a 2×2 strip beside the quote");
        });
    }

    @Test
    void noImplementationVocabulary() {
        FxTestSupport.runAndWait(() -> {
            page.renderQuote(petr());
            List<String> bad = flatten(view).stream().filter(Labeled.class::isInstance).map(n -> ((Labeled) n).getText())
                    .filter(t -> t != null).map(String::toLowerCase)
                    .filter(s -> s.contains("ledger") || s.contains("replay") || s.contains("schema")).toList();
            assertTrue(bad.isEmpty(), bad.toString());
        });
    }
}

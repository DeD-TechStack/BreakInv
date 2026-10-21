package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ranking (direction A): the metric and the period are named on screen, the two lists are
 * comparable rows (asset, price, day change) that stack when narrow instead of squeezing, a row
 * opens a contextual detail, and the empty/error states say what is missing. No network.
 */
class RankingPageLayoutTest {

    RankingPage page;
    Parent view;

    @BeforeEach
    void setUp() {
        FxTestSupport.runAndWait(() -> {
            page = new RankingPage(FxStubs.emptyDaily());
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
        Field f = RankingPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    static StockData stock(String t, double price, double chg, double pct) {
        return new StockData(t, null, t + " S.A.", price, chg, pct, price - chg, price + 1, price - 1, 1000L, 0, 0, 0, "BRL", 0, 0, null);
    }

    static Map<String, StockData> stocks() {
        Map<String, StockData> m = new LinkedHashMap<>();
        m.put("AAAA3", stock("AAAA3", 10.0, 0.5, 5.26));
        m.put("BBBB4", stock("BBBB4", 20.0, -1.0, -4.76));
        return m;
    }

    /** E-V2 class: the error banner spans the page like the panels around it, even with a short message. */
    @Test
    void warningBannerSpansItsContainerEvenWithAShortMessage() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 800);
            assertEquals(java.util.List.of(), FxTestSupport.messageBoxesShortOfTheirContainer(view, ".warning-banner"));
        });
    }

    @Test
    void intermediateTablesFitWithoutAHorizontalScrollbar() {
        FxTestSupport.runAndWait(() -> {
            page.renderStocks(stocks());
            var scene = new javafx.scene.Scene(view, 640, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for(int width : new int[]{1060,640,730,380,1060}) {
                ((javafx.scene.layout.Region)view).resize(width,720);
                page.onBreakpoint(Breakpoint.of(width));
                for(int i=0;i<6;i++){view.applyCss();view.layout();}
                for(String name : List.of("gainersTable", "losersTable")) {
                    TableView<?> table = field(name);
                    for(Node node : table.lookupAll(".scroll-bar")) {
                        ScrollBar bar = (ScrollBar)node;
                        if(bar.getOrientation()==javafx.geometry.Orientation.HORIZONTAL)
                            assertFalse(bar.isVisible(), "horizontal bar consumes the single row at page width="+width);
                    }
                }
            }
        });
    }

    static Map<String, StockData> stocks(int perList) {
        Map<String, StockData> m = new LinkedHashMap<>();
        for (int i = 0; i < perList; i++) {
            m.put("UP" + i + "3", stock("UP" + i + "3", 1234.56 + i, 10.0 + i, 12.34 + i));
            m.put("DN" + i + "4", stock("DN" + i + "4", 4321.09 + i, -10.0 - i, -11.22 - i));
        }
        return m;
    }

    static TableRow<?> renderedRow(TableView<?> table, int index) {
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && row.isVisible() && !row.isEmpty() && row.getIndex() == index) return row;
        }
        return null;
    }

    /**
     * A-V5: at every content width the two lists sit side by side only when their three columns
     * fit; otherwise they stack. A height-locked list must never need a horizontal scrollbar (it
     * covers the last row), overflow its panel or cut a column title; the last rendered row stays
     * inside the table for 1, 2, 5 and 7 quotes, through wide → intermediate → narrow → wide.
     */
    @Test
    void intermediateWidthsKeepEveryColumnTitleAndTheLastRowInsideEachList() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 960, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            List<String> problems = new ArrayList<>();
            for (int n : new int[]{1, 2, 5, 7}) {
                page.renderStocks(stocks(n));
                for (int width : new int[]{1060, 959, 900, 800, 760, 730, 700, 680, 660, 640, 639, 520, 380, 730, 1060}) {
                    ((javafx.scene.layout.Region) view).resize(width, 720);
                    page.onBreakpoint(Breakpoint.of(width));
                    for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
                    Panel day = field("dayPanel");
                    javafx.geometry.Bounds dayBounds = day.localToScene(day.getLayoutBounds());
                    for (String name : List.of("gainersTable", "losersTable")) {
                        TableView<?> table = field(name);
                        String where = name + " n=" + n + " content=" + width;
                        assertNotNull(table.lookup(".virtual-flow"), where + ": table skin was not built");
                        javafx.geometry.Bounds tb = table.localToScene(table.getLayoutBounds());
                        if (tb.getMaxX() > dayBounds.getMaxX() - day.snappedRightInset() + 0.5)
                            problems.add(where + " overflows its panel (" + Math.round(tb.getMaxX()) + " > " + Math.round(dayBounds.getMaxX()) + ")");
                        double vbar = 0;
                        for (Node node : table.lookupAll(".scroll-bar")) {
                            ScrollBar bar = (ScrollBar) node;
                            if (!bar.isVisible()) continue;
                            if (bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL)
                                problems.add(where + " shows a horizontal scrollbar");
                            else vbar = bar.getWidth();
                        }
                        double columns = table.getVisibleLeafColumns().stream().mapToDouble(TableColumn::getWidth).sum();
                        double inner = table.getWidth() - table.snappedLeftInset() - table.snappedRightInset() - vbar;
                        String widths = table.getVisibleLeafColumns().stream()
                                .map(c -> Math.round(c.getWidth()) + "/" + Math.round(c.getMinWidth())).toList() + " table=" + Math.round(table.getWidth());
                        if (columns > inner + 0.5) problems.add(where + " columns " + Math.round(columns) + " > list " + Math.round(inner) + " " + widths);
                        for (Node node : table.lookupAll(".column-header .label")) {
                            Label title = (Label) node;
                            var shown = (javafx.scene.text.Text) title.lookup(".text");
                            String text = shown == null ? null : shown.getText();
                            if (!title.getText().equals(text)) problems.add(where + " title '" + text + "' needs " + Math.round(title.prefWidth(-1)) + " in " + Math.round(title.getWidth()) + " " + widths);
                        }
                        int lastIndex = Math.min(5, table.getItems().size()) - 1;
                        TableRow<?> last = renderedRow(table, lastIndex);
                        if (last == null) { problems.add(where + " row " + lastIndex + " not rendered"); continue; }
                        javafx.geometry.Bounds rb = last.localToScene(last.getLayoutBounds());
                        Node viewport = table.lookup(".clipped-container");
                        javafx.geometry.Bounds vb = viewport.localToScene(viewport.getLayoutBounds());
                        if (rb.getMaxY() > vb.getMaxY() + 0.5)
                            problems.add(where + " last row cut (" + Math.round(rb.getMaxY()) + " > viewport " + Math.round(vb.getMaxY()) + ")");
                    }
                }
            }
            assertTrue(problems.isEmpty(), problems.size() + " problems:\n" + String.join("\n", problems));
        });
    }

    @SuppressWarnings("unchecked")
    static <T> T fieldOf(RankingPage p, String name) throws Exception {
        Field f = RankingPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(p);
    }

    static double bottomOf(Node n) {
        return n.localToScene(n.getLayoutBounds()).getMaxY();
    }

    static String nodeName(Node n) {
        return n instanceof Labeled l ? n.getClass().getSimpleName() + " '" + l.getText() + "'" : n.getClass().getSimpleName();
    }

    /**
     * R4-01 measurements on one laid-out day panel: both lists end inside their pair, every node after the
     * pair (detail, hint, chart) starts below the lists, and none of them covers the last row of a list.
     */
    static List<String> listsOverlaps(RankingPage p, String where, boolean settled) throws Exception {
        List<String> out = new ArrayList<>();
        Panel day = fieldOf(p, "dayPanel");
        AdaptivePair pair = fieldOf(p, "listsPair");
        String mode = pair.isSideBySide() ? "side by side" : "stacked";
        double pairBottom = bottomOf(pair);
        double listsBottom = Math.max(bottomOf(pair.first()), bottomOf(pair.second()));
        if (listsBottom > pairBottom + 0.5)
            out.add(where + " (" + mode + "): the lists end at " + Math.round(listsBottom) + ", below their pair at " + Math.round(pairBottom));
        List<Node> body = day.body().getChildren();
        List<Node> after = new ArrayList<>();
        for (int i = body.indexOf(pair) + 1; i < body.size(); i++) {
            Node next = body.get(i);
            if (next.isVisible() && next.isManaged()) after.add(next);
        }
        for (Node next : after) {
            double top = next.localToScene(next.getLayoutBounds()).getMinY();
            if (top < listsBottom - 0.5)
                out.add(where + " (" + mode + "): " + nodeName(next) + " starts at " + Math.round(top) + ", over the lists ending at " + Math.round(listsBottom));
        }
        for (String name : List.of("gainersTable", "losersTable")) {
            TableView<?> table = fieldOf(p, name);
            int lastIndex = Math.min(5, table.getItems().size()) - 1;
            if (lastIndex < 0) continue;
            TableRow<?> last = renderedRow(table, lastIndex);
            if (last == null) {
                if (settled) out.add(where + " " + name + ": last row not rendered");
                continue;
            }
            javafx.geometry.Bounds rb = last.localToScene(last.getLayoutBounds());
            for (Node next : after) {
                if (next.localToScene(next.getLayoutBounds()).intersects(rb))
                    out.add(where + " " + name + ": the last row is covered by " + nodeName(next));
            }
            if (settled) { // ticker, quote and change of the last row whole: no "…" brought back
                int texts = 0;
                for (Node n : flatten(last)) {
                    if (!(n instanceof Labeled l) || !n.isVisible() || l.getText() == null || l.getText().isBlank()) continue;
                    for (Node c : l.getChildrenUnmodifiable()) {
                        if (!(c instanceof javafx.scene.text.Text shown)) continue;
                        texts++;
                        if (!l.getText().equals(shown.getText()))
                            out.add(where + " " + name + ": last row shows '" + shown.getText() + "' instead of '" + l.getText() + "'");
                    }
                }
                if (texts < 3) out.add(where + " " + name + ": only " + texts + " texts found in the last row");
            }
        }
        return out;
    }

    /** One layout pass, as one pulse of a real window: the pass that changes the width must already be right. */
    static void onePass(Parent v) {
        v.applyCss();
        v.layout();
    }

    /**
     * R4-01: when the lists stack at an intermediate width, the day panel reserves the stacked height in the
     * same pass that stacks them — the hint and the chart start below "Maiores baixas" and its last row keeps
     * its quote and change. The r4 panel kept the side-by-side height of that pass (286 px for 588 px of
     * stacked lists at 640 px) and drew the chart over the second list; a headless CSS pass healed it one
     * pulse later, the real window never did. So every check runs right after the first pass at a width
     * and again once settled. Covers the first layout at each width and wide → narrow → medium → wide on one
     * page, with 2 and 5 quotes per list, in the app's face.
     */
    @Test
    void stackedListsKeepTheHintAndTheChartBelowThem_firstLayoutAndAfterResizing() {
        FxTestSupport.runAndWait(() -> {
            List<String> problems = new ArrayList<>();
            int[] intermediate = {600, 632, 640, 660, 680, 700, 720, 722, 740, 760, 800};
            for (int n : new int[]{2, 5}) {
                for (int width : intermediate) {
                    RankingPage fresh = new RankingPage(FxStubs.emptyDaily());
                    Parent freshView = fresh.view();
                    com.daniel.presentation.view.util.UiTheme.applyFont(freshView); // the app's face: wrapping depends on its metrics
                    var freshScene = new javafx.scene.Scene(freshView, width, 720);
                    freshScene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
                    fresh.renderStocks(stocks(n));
                    fresh.onBreakpoint(Breakpoint.of(width));
                    ((javafx.scene.layout.Region) freshView).resize(width, 720);
                    String where = "first layout n=" + n + " content=" + width;
                    onePass(freshView);
                    problems.addAll(listsOverlaps(fresh, where + " (first pass)", false));
                    for (int i = 0; i < 5; i++) onePass(freshView);
                    problems.addAll(listsOverlaps(fresh, where + " (settled)", true));
                }
            }
            com.daniel.presentation.view.util.UiTheme.applyFont(view);
            var scene = new javafx.scene.Scene(view, 1060, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (int n : new int[]{2, 5}) {
                page.renderStocks(stocks(n));
                for (int medium : intermediate) {
                    for (int width : new int[]{1060, medium, 1060, 520, medium, 1060}) {
                        ((javafx.scene.layout.Region) view).resize(width, 720);
                        page.onBreakpoint(Breakpoint.of(width));
                        String where = "resized n=" + n + " to " + width + " (sequence via " + medium + ")";
                        onePass(view);
                        problems.addAll(listsOverlaps(page, where + " (first pass)", false));
                        for (int i = 0; i < 5; i++) onePass(view);
                        problems.addAll(listsOverlaps(page, where + " (settled)", true));
                    }
                }
            }
            assertTrue(problems.isEmpty(), problems.size() + " problems:\n" + String.join("\n", problems));
        });
    }

    /**
     * A-V5, found in the packaged shell at 960×720 (~692 px of page): with one flat quote both lists
     * are empty and the losers message was cut ("…cotação obt…"). An empty-list message wraps inside
     * its list at every width, at the body size rather than the theme's larger placeholder font.
     */
    @Test
    void emptyListMessageIsWholeInsideItsListAtEveryWidth() {
        FxTestSupport.runAndWait(() -> {
            Map<String, StockData> one = new LinkedHashMap<>();
            one.put("FICT1", stock("FICT1", 25.0, 0.0, 0.0));
            page.renderStocks(one);
            var scene = new javafx.scene.Scene(view, 960, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            List<String> problems = new ArrayList<>();
            for (int width : new int[]{1060, 692, 640, 730, 380, 1060}) {
                ((javafx.scene.layout.Region) view).resize(width, 720);
                page.onBreakpoint(Breakpoint.of(width));
                for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
                for (String name : List.of("gainersTable", "losersTable")) {
                    TableView<?> table = field(name);
                    assertTrue(table.getItems().isEmpty(), name + " should be empty with one flat quote");
                    Label message = (Label) table.getPlaceholder();
                    var shown = (javafx.scene.text.Text) message.lookup(".text");
                    if (shown == null || !message.getText().equals(shown.getText()))
                        problems.add(name + " at " + width + ": '" + (shown == null ? null : shown.getText()) + "'");
                    var mb = message.localToScene(message.getLayoutBounds());
                    var tb = table.localToScene(table.getLayoutBounds());
                    if (mb.getMinX() < tb.getMinX() - 0.5 || mb.getMaxX() > tb.getMaxX() + 0.5 || mb.getMaxY() > tb.getMaxY() + 0.5)
                        problems.add(name + " at " + width + ": message outside its list " + mb + " / " + tb);
                    if (message.getFont().getSize() > 14.01) problems.add(name + " message font " + message.getFont().getSize());
                }
            }
            assertTrue(problems.isEmpty(), String.join("\n", problems));
        });
    }

    @Test
    void shortListsUseTheirRowsAndSelectionDetailStaysBeforeTheChart() {
        FxTestSupport.runAndWait(() -> {
            page.renderStocks(stocks());
            TableView<?> gainers = field("gainersTable");
            assertTrue(gainers.getPrefHeight() <= 100, "one quote must not reserve five rows of empty table");
            Panel day = field("dayPanel");
            Node detail = field("details");
            Node chart = field("barChart");
            assertTrue(day.body().getChildren().contains(detail), "selection detail belongs to the day workspace");
            assertTrue(day.body().getChildren().indexOf(detail) < day.body().getChildren().indexOf(chart));
            gainers.getSelectionModel().select(0);
            assertTrue(detail.isVisible());
        });
    }

    @Test
    void metricAndPeriodAreExplicit_andRowsAreComparable() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Panel dayPanel = field("dayPanel");
            Label subtitle = (Label) dayPanel.lookup(".panel-subtitle");
            assertTrue(subtitle.getText().toLowerCase().contains("cotação"), subtitle.getText());
            assertTrue(subtitle.getText().toLowerCase().contains("não é a rentabilidade"), "day change is not the position return: " + subtitle.getText());
            TableView<?> gainers = field("gainersTable");
            List<String> cols = gainers.getColumns().stream().map(TableColumn::getText).toList();
            assertEquals(List.of("Ativo", "Cotação", "Variação do dia"), cols);
            assertTrue(gainers.getFixedCellSize() >= 40 && gainers.getFixedCellSize() <= 44);
            Panel returnPanel = field("returnPanel");
            Label rs = (Label) returnPanel.lookup(".panel-subtitle");
            assertTrue(rs.getText().toLowerCase().contains("preço"), rs.getText());
            assertTrue(rs.getText().toLowerCase().contains("aporte"), "says it excludes contributions: " + rs.getText());
            Node periodBar = field("returnPeriodBar");
            assertTrue(flatten(returnPanel).contains(periodBar), "period lives with its chart");
        });
    }

    @Test
    void listsSideBySideWhenWide_stackedWhenNarrow_detailsFollowTheBreakpoint() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AdaptivePair lists = field("listsPair");
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(lists.isSideBySide());
            page.onBreakpoint(Breakpoint.NARROW);
            assertFalse(lists.isSideBySide(), "two tables never squeezed side by side in a narrow window");
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertTrue(lists.isSideBySide(), "three narrow columns still fit side by side at medium width");
        });
    }

    @Test
    void selectingARow_opensTheAssetDetail_withTheRealQuoteFigures() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.renderStocks(stocks());
            TableView<?> gainers = field("gainersTable");
            TableView<?> losers = field("losersTable");
            assertEquals(1, gainers.getItems().size());
            assertEquals(1, losers.getItems().size());
            DetailsPane details = field("details");
            assertFalse(details.isVisible());
            losers.getSelectionModel().select(0);
            assertTrue(details.isVisible(), "a row opens its detail");
            List<String> labels = details.fieldLabels();
            assertTrue(labels.contains("Abertura"), labels.toString());
            assertTrue(labels.contains("Máx. do dia"), labels.toString());
            assertTrue(labels.contains("Volume"), labels.toString());
            Label headline = (Label) details.lookup(".details-value");
            assertEquals("R$ 20,00", headline.getText());
            details.requestClose();
            assertFalse(details.isVisible());
        });
    }

    @Test
    void emptyAndErrorStates_areHonest_andTheErrorKeepsThePreviousData() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Node empty = field("emptyState");
            Node content = field("contentArea");
            assertTrue(empty.isVisible() && !content.isVisible());
            page.renderStocks(stocks());
            assertTrue(content.isVisible() && !empty.isVisible());
            Label error = field("errorBanner");
            page.showFailure("Sem conexão. Cotações podem estar desatualizadas.");
            assertTrue(error.isVisible() && error.isManaged());
            assertTrue(content.isVisible(), "the previous ranking stays readable under the warning");
            page.renderStocks(stocks());
            assertFalse(error.isVisible(), "a successful load clears the warning");
        });
    }

    /**
     * R2-07: a quote failure with no previous ranking showed "Nenhum ativo com ticker / Cadastre
     * ativos…" under the failure banner — the portfolio HAS tickers; only the lookup failed.
     */
    @Test
    void aFailureWithoutPreviousQuotesDoesNotClaimThereAreNoTickers() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Node empty = field("emptyState");
            Node content = field("contentArea");
            Label error = field("errorBanner");
            page.showFailure("Falha inesperada ao consultar as cotações: timeout. Tente novamente.");
            assertTrue(error.isVisible() && error.isManaged(), "the failure is said");
            assertFalse(empty.isVisible() && empty.isManaged(), "and not mistaken for a portfolio without tickers");
            assertFalse(content.isVisible(), "no ranking to show");
        });
    }

    /**
     * R2-06: each bar carried its ticker rotated inside it — cut to "T1" on a zero-height bar and to
     * "⋮" on thin bars at 380 px. The axis names every bar in full (the tooltip too).
     */
    @Test
    void barsCarryNoTickerLabelOfTheirOwn() throws Exception {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 1060, 1400);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            Map<String, StockData> m = new LinkedHashMap<>(stocks());
            m.put("FICT1", stock("FICT1", 10.0, 0.0, 0.0)); // a zero-height bar
            page.renderStocks(m);
            view.applyCss();
            view.layout();
        });
        FxTestSupport.runAndWait(() -> { // bar nodes styled on the next pulse
            view.applyCss();
            view.layout();
            javafx.scene.chart.BarChart<String, Number> chart = field("barChart");
            List<String> labels = new ArrayList<>();
            for (var series : chart.getData())
                for (var d : series.getData())
                    if (d.getNode() instanceof Parent p)
                        for (Node n : flatten(p)) if (n instanceof Labeled l) labels.add(d.getXValue() + ": " + l.getText());
            assertEquals(List.of(), labels, "the axis is where tickers are read");
            assertEquals(3, chart.getData().stream().mapToInt(s -> s.getData().size()).sum(), "one bar per ticker");
        });
    }

    /**
     * R3-04: one series per ticker gave every category one slot per series — each bar sat off its
     * ticker (~18 px at 722 px of page) and with 8 quotes became a hairline. Every bar is centred on
     * its ticker and keeps the width of its category minus the gap.
     */
    @Test
    void everyDayBarSitsOnItsTickerAndKeepsItsWidth() throws Exception {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 640, 1400);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.renderStocks(stocks(4)); // 8 tickers on the chart
            ((javafx.scene.layout.Region) view).resize(640, 1400);
            page.onBreakpoint(Breakpoint.of(640));
            for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
        });
        FxTestSupport.runAndWait(() -> {
            view.applyCss();
            view.layout();
            javafx.scene.chart.BarChart<String, Number> chart = field("barChart");
            var axis = (javafx.scene.chart.CategoryAxis) chart.getXAxis();
            List<String> problems = new ArrayList<>();
            int bars = 0;
            for (var series : chart.getData())
                for (var d : series.getData()) {
                    bars++;
                    var b = d.getNode().localToScene(d.getNode().getBoundsInLocal());
                    double tick = axis.localToScene(axis.getDisplayPosition(d.getXValue()), 0).getX();
                    double centre = (b.getMinX() + b.getMaxX()) / 2;
                    if (Math.abs(centre - tick) > 1)
                        problems.add(d.getXValue() + ": bar centre " + Math.round(centre) + " vs its tick " + Math.round(tick));
                    if (b.getWidth() < axis.getCategorySpacing() * 0.5)
                        problems.add(d.getXValue() + ": bar " + Math.round(b.getWidth()) + " px in a "
                                + Math.round(axis.getCategorySpacing()) + " px category");
                }
            assertEquals(8, bars, "one bar per ticker");
            assertEquals(List.of(), problems);
        });
    }

    /**
     * R3-02 (r3 expanded states, 632 px of page): the three panels' subtitles — with the caveats
     * "não é a rentabilidade da sua posição" and "nem o seu preço de compra" — and the moving-average
     * line legend ended in "…" beside the badge and the period buttons. Every text on the page is
     * whole from the wide page to the narrow one, with 2 and 5 quotes per list.
     */
    @Test
    void everyExplanatoryTextIsWholeAtEveryWidth() {
        FxTestSupport.runAndWait(() -> {
            com.daniel.presentation.view.util.UiTheme.applyFont(view); // the app's face: wrapping depends on its metrics
            var scene = new javafx.scene.Scene(view, 1060, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            List<String> problems = new ArrayList<>();
            for (int n : new int[]{2, 5}) {
                page.renderStocks(stocks(n));
                for (int width : new int[]{1060, 960, 800, 740, 730, 700, 660, 640, 632, 600, 560, 520, 380, 1060}) {
                    ((javafx.scene.layout.Region) view).resize(width, 900);
                    page.onBreakpoint(Breakpoint.of(width));
                    for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
                    for (String cut : FxTestSupport.cutTexts(view)) problems.add("n=" + n + " width=" + width + ": " + cut);
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * R3-02, as the r3 probe met it: a page that opens already filled, at its width, laid out once and
     * snapshotted (no earlier wider pass to settle from) — the subtitles and the line legend came out
     * in "…" at 632 px of page. Whole after the first layout, at the widths around the header's turn.
     */
    @Test
    void aFreshFilledPageIsWholeAfterItsFirstLayout() {
        FxTestSupport.runAndWait(() -> {
            List<String> problems = new ArrayList<>();
            for (int width : new int[]{640, 650, 660, 680, 700, 730, 760, 800}) {
                RankingPage fresh = new RankingPage(FxStubs.emptyDaily());
                fresh.renderStocks(stocks(5));
                fresh.onBreakpoint(Breakpoint.of(width));
                Parent root = fresh.view();
                com.daniel.presentation.view.util.UiTheme.applyFont(root);
                var scene = new javafx.scene.Scene(root, width, 720);
                scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
                ((javafx.scene.layout.Region) root).resize(width, 720);
                root.snapshot(null, null); // one CSS + layout pass of the scene, as the probe's snapshot did
                Node content = ((ScrollPane) root).getContent();
                for (String cut : FxTestSupport.cutTexts((Parent) content)) problems.add("window " + width + ": " + cut);
                // the probe's "…" came from a vertical squeeze (one line given to a wrapped text):
                // a wrapped explanation can never be made shorter than its own lines
                for (Node n : ((Parent) content).lookupAll(".label")) {
                    boolean shown = true;
                    for (Node p = n; p != null && shown; p = p.getParent()) shown = p.isVisible();
                    if (!shown || !(n instanceof Label l) || !l.isWrapText() || l.getText() == null || l.getText().isBlank() || l.getWidth() <= 0) continue;
                    double w = l.getWidth();
                    if (l.minHeight(w) + 0.5 < l.prefHeight(w))
                        problems.add("window " + width + ": '" + l.getText().substring(0, Math.min(40, l.getText().length()))
                                + "…' can be squeezed to " + Math.round(l.minHeight(w)) + " of " + Math.round(l.prefHeight(w)) + " px");
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /** R3-09: the titles of the columns whose values sit on the other side of the column. */
    static List<String> headersOffTheirValues(TableView<?> table) {
        List<String> problems = new ArrayList<>();
        for (Node h : table.lookupAll(".column-header")) {
            if (!(h instanceof javafx.scene.control.skin.TableColumnHeader header) || header.getTableColumn() == null
                    || !header.getTableColumn().isVisible()) continue;
            var column = header.getTableColumn();
            Label title = (Label) header.lookup(".label");
            if (title == null || title.getText() == null || title.getText().isBlank()) continue;
            javafx.geometry.HPos values = null;
            for (Node n : table.lookupAll(".table-cell"))
                if (n instanceof TableCell<?, ?> cell && cell.getTableColumn() == column && !cell.isEmpty() && cell.isVisible()) {
                    values = cell.getAlignment().getHpos();
                    break;
                }
            if (values != null && values != title.getAlignment().getHpos())
                problems.add("'" + title.getText() + "': title " + title.getAlignment().getHpos() + " over values " + values);
        }
        return problems;
    }

    /**
     * R3-09 (O10): "Cotação" and "Variação do dia" headed the lists from the left, over values aligned to
     * the right. Every column title sits on the side of its values.
     */
    @Test
    void numericColumnTitlesSitOverTheirNumbers() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 1060, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.renderStocks(stocks(2));
            ((javafx.scene.layout.Region) view).resize(1060, 900);
            page.onBreakpoint(Breakpoint.of(1060));
            for (int i = 0; i < 4; i++) { view.applyCss(); view.layout(); }
            List<String> problems = new ArrayList<>();
            for (String name : List.of("gainersTable", "losersTable")) {
                TableView<?> table = field(name);
                for (String p : headersOffTheirValues(table)) problems.add(name + " " + p);
            }
            assertEquals(List.of(), problems);
        });
    }

    /** R2-16 class: the Ranking's time charts name their dates and units like the Análise's. */
    @Test
    void timeChartsShowDatesAndTheirUnits() throws Exception {
        FxTestSupport.runAndWait(() -> {
            for (String[] axes : new String[][]{{"retXAxis", "retYAxis", "%"}, {"maXAxis", "maYAxis", "R$"}}) {
                javafx.scene.chart.NumberAxis x = field(axes[0]);
                javafx.scene.chart.NumberAxis y = field(axes[1]);
                assertTrue(x.isTickLabelsVisible() && x.isTickMarkVisible(), axes[0] + " shows its dates");
                assertTrue(y.getLabel() != null && y.getLabel().contains(axes[2]), axes[1] + " names its unit: " + y.getLabel());
            }
        });
    }

    /** R2-09: "Sem cruzamento recente detectado." stood there before any calculation. */
    @Test
    void movingAverageSaysNothingBeforeACalculation() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label status = field("maCrossLabel");
            assertFalse(status.isVisible() && status.isManaged() && status.getText() != null && !status.getText().isBlank(),
                    "no result before a calculation: '" + status.getText() + "'");
            status.setText("Informe o ticker do ativo.");
            assertTrue(status.isVisible() && status.isManaged(), "a validation or a result is shown");
        });
    }

    @Test
    void movingAverageInputs_wrapInsteadOfOneFixedRow() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Node inputBar = field("maInputBar");
            assertTrue(inputBar instanceof javafx.scene.layout.FlowPane, "inputs wrap in a narrow window: " + inputBar.getClass());
            Button calc = field("maCalcBtn");
            assertNotNull(calc.getTooltip());
        });
    }
}

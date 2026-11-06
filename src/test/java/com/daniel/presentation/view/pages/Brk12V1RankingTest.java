package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.viewmodel.RankingViewModel.RankingRow;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.daniel.presentation.view.pages.Brk12V1Support.NBSP;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertAxisTable;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertPtBr;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertTone;
import static com.daniel.presentation.view.pages.Brk12V1Support.detailFields;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1 on the Ranking, with the JVM in en-US: money and percentages in pt-BR, the change cell and the bar of
 * each ticker in the tone of their SHOWN value (a move of 0,004 % reads 0,00 % and is neutral), the bars painted by
 * a class (no hex in the style) in the colour of the token.
 */
class Brk12V1RankingTest {

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

    // ── fixtures ─────────────────────────────────────────────────────────────

    static StockData stock(String ticker, double price, double change, double percent) {
        return new StockData(ticker, null, ticker + " S.A.", price, change, percent, price - change, price + 1, price - 1,
                1000L, 0, 0, 0, "BRL", 0, 0, null);
    }

    /** A rise, a fall, a move of 0,004 % (shows zero) and a flat quote; prices of R$ 1.000 or more. */
    static Map<String, StockData> stocks() {
        Map<String, StockData> m = new LinkedHashMap<>();
        m.put("UPPR3", stock("UPPR3", 1234.56, 61.0, 5.26));
        m.put("DOWN4", stock("DOWN4", 4321.09, -216.0, -4.76));
        m.put("ZERO3", stock("ZERO3", 20.00, 0.0008, 0.004));
        m.put("FLAT3", stock("FLAT3", 20.00, 0.0, 0.0));
        return m;
    }

    /** The sources never reach the network: quotes fail without a token, histories are offline. */
    static RankingPage page() {
        return new RankingPage(FxStubs.emptyDaily(),
                joined -> new BrapiClient.FetchResult.Failure<Map<String, StockData>>(BrapiClient.FailureReason.NO_TOKEN, ""),
                (ticker, period) -> { throw new IllegalStateException("offline"); },
                task -> {}, Runnable::run);
    }

    // ── V1:RF-01 / RF-05.2 ───────────────────────────────────────────────────

    @Test
    void noTextOfTheRankingIsEnUsOrWithoutThousands() throws Exception {
        RankingPage[] holder = new RankingPage[1];
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.renderStocks(stocks());
            Brk12V1Support.settle(page.view(), 1280, 800);
            TableView<RankingRow> gainers = field(page, "gainersTable");
            gainers.getSelectionModel().select(0); // opens the detail: price, change in R$, open, high, low, volume
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        // the bars get their tooltips on the next pulse
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            assertPtBr(holder[0].view(), "Ranking");
        });
    }

    @Test
    void theThreeValueAxesFollowTheTableOfRf01_6() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            assertAxisTable(field(page, "barYAxis"), "Ranking barYAxis (Variação do dia %)");
            assertAxisTable(field(page, "retYAxis"), "Ranking retYAxis (Variação do preço %)");
            assertAxisTable(field(page, "maYAxis"), "Ranking maYAxis (Preço R$)");
        });
    }

    @Test
    void theDetailWritesPriceChangeAndVolumeInPtBr_withoutASignAtZero() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            page.renderStocks(stocks());
            TableView<RankingRow> gainers = field(page, "gainersTable");
            TableView<RankingRow> losers = field(page, "losersTable");
            DetailsPane details = field(page, "details");

            gainers.getSelectionModel().select(0); // UPPR3
            assertEquals("R$" + NBSP + "1.234,56", ((Label) details.lookup(".details-value")).getText());
            Map<String, String> up = detailFields(details);
            assertEquals("+5,26%", up.get("Variação do dia"));
            assertEquals("+ R$" + NBSP + "61,00", up.get("Variação em R$"));
            assertEquals("R$" + NBSP + "1.173,56", up.get("Abertura"));
            assertEquals("R$" + NBSP + "1.235,56", up.get("Máx. do dia"));
            assertEquals("R$" + NBSP + "1.233,56", up.get("Mín. do dia"));
            assertEquals("1,0K", up.get("Volume"));

            gainers.getSelectionModel().clearSelection();
            losers.getSelectionModel().select(0); // DOWN4
            Map<String, String> down = detailFields(details);
            assertEquals("-4,76%", down.get("Variação do dia"));
            assertEquals("- R$" + NBSP + "216,00", down.get("Variação em R$"));

            losers.getSelectionModel().clearSelection();
            // ZERO3 (+0,004 % and +R$ 0,0008 show zero) is in no day list any more; its detail is still written without a sign
            gainers.setItems(FXCollections.observableArrayList(new RankingRow("ZERO3", "ZERO3 S.A.", 20.00, 0.0008, 0.004)));
            gainers.getSelectionModel().select(0);
            Map<String, String> zero = detailFields(details);
            assertEquals("0,00%", zero.get("Variação do dia"));
            assertEquals("R$" + NBSP + "0,00", zero.get("Variação em R$"));
        });
    }

    // ── V1:RF-03 T7: the change cell ─────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void changeCell_toneAndTextFollowTheShownValue() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            TableView<RankingRow> table = field(page, "gainersTable");
            table.setItems(FXCollections.observableArrayList(
                    new RankingRow("UPPR3", "UPPR3", 10, 0.5, 5.26),
                    new RankingRow("DOWN4", "DOWN4", 10, -0.5, -4.76),
                    new RankingRow("ZERO", "ZERO", 10, 0, 0.0),
                    new RankingRow("TINY3", "TINY3", 10, 0.001, 0.004),
                    new RankingRow("TINY4", "TINY4", 10, -0.001, -0.004),
                    new RankingRow("HALF3", "HALF3", 10, 0.001, 0.005)));
            TableColumn<RankingRow, Number> change = (TableColumn<RankingRow, Number>) table.getColumns().get(2);
            TableCell<RankingRow, Number> cell = (TableCell<RankingRow, Number>) change.getCellFactory().call(change);
            cell.updateTableView(table);
            cell.updateTableColumn(change);
            String[][] expected = {
                    {"+5,26%", "pos"}, {"-4,76%", "neg"}, {"0,00%", "muted"}, {"0,00%", "muted"}, {"0,00%", "muted"}, {"+0,01%", "pos"}};
            for (int i = 0; i < expected.length; i++) {
                cell.updateIndex(i);
                String name = table.getItems().get(i).ticker();
                assertEquals(expected[i][0], cell.getText(), name);
                assertTone(cell, expected[i][1], name);
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void priceCell_writesMoneyInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            TableView<RankingRow> table = field(page, "gainersTable");
            table.setItems(FXCollections.observableArrayList(new RankingRow("UPPR3", "UPPR3", 1234.56, 61.0, 5.26)));
            TableColumn<RankingRow, Number> price = (TableColumn<RankingRow, Number>) table.getColumns().get(1);
            TableCell<RankingRow, Number> cell = (TableCell<RankingRow, Number>) price.getCellFactory().call(price);
            cell.updateTableView(table);
            cell.updateTableColumn(price);
            cell.updateIndex(0);
            assertEquals("R$" + NBSP + "1.234,56", cell.getText());
        });
    }

    // ── V1:RF-03 T8: the bars ────────────────────────────────────────────────

    /** The first background fill of the node, which has to be a plain colour (the theme's own bar is a gradient). */
    private static Color fillOf(Node node) {
        var background = ((Region) node).getBackground();
        assertNotNull(background, "the bar has a background");
        var fill = background.getFills().get(0).getFill();
        assertTrue(fill instanceof Color, "the bar is filled with a plain colour, not " + fill);
        return (Color) fill;
    }

    private static void assertColor(Color expected, Color seen, String what) {
        assertEquals(expected.getRed(), seen.getRed(), 0.01, what + " (red of " + seen + ")");
        assertEquals(expected.getGreen(), seen.getGreen(), 0.01, what + " (green of " + seen + ")");
        assertEquals(expected.getBlue(), seen.getBlue(), 0.01, what + " (blue of " + seen + ")");
    }

    @Test
    void bars_areColouredByTheClassOfTheirShownTone_withNoHexInTheStyle() throws Exception {
        RankingPage[] holder = new RankingPage[1];
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            ((XYChart<?, ?>) field(page, "barChart")).setAnimated(false);
            page.renderStocks(stocks());
            Brk12V1Support.settle(page.view(), 1280, 800); // the bars exist now; the page styles them on the next pulse
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            XYChart<String, Number> chart = field(holder[0], "barChart");
            Map<String, String> tone = Map.of("UPPR3", "pos", "DOWN4", "neg", "ZERO3", "muted", "FLAT3", "muted");
            Map<String, Color> token = Map.of("pos", Color.web("#74D7A6"), "neg", Color.web("#FF9595"), "muted", Color.web("#A8B6C6"));
            assertEquals(4, chart.getData().get(0).getData().size(), "one bar per quote");
            for (XYChart.Data<String, Number> bar : chart.getData().get(0).getData()) {
                String ticker = bar.getXValue();
                String expected = tone.get(ticker);
                Node node = bar.getNode();
                assertNotNull(node, ticker + ": the bar node exists");
                for (String t : List.of("pos", "neg", "muted")) {
                    assertEquals(t.equals(expected), node.getStyleClass().contains("var-bar-" + t),
                            ticker + ": class var-bar-" + t + " in " + node.getStyleClass());
                }
                assertFalse(node.getStyle().contains("#"), ticker + ": no hex in the style: '" + node.getStyle() + "'");
                assertFalse(node.getStyle().contains("-fx-background-color"), ticker + ": no inline colour: '" + node.getStyle() + "'");
                assertNull(node.getOnMouseEntered(), ticker + ": the hover is the stylesheet's, not a handler that swaps colours");
                assertNull(node.getOnMouseExited(), ticker + ": the hover is the stylesheet's, not a handler that swaps colours");
                assertColor(token.get(expected), fillOf(node), ticker + ": the rendered fill is the colour of the token");
            }
        });
    }

    @Test
    void barTooltips_writeMoneyAndSignsInPtBr() throws Exception {
        RankingPage[] holder = new RankingPage[1];
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            ((XYChart<?, ?>) field(page, "barChart")).setAnimated(false);
            page.renderStocks(stocks());
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            XYChart<String, Number> chart = field(holder[0], "barChart");
            Map<String, String> tips = new LinkedHashMap<>();
            for (XYChart.Data<String, Number> bar : chart.getData().get(0).getData()) {
                assertTrue(bar.getNode().getProperties().get("javafx.scene.control.Tooltip") instanceof Tooltip, bar.getXValue());
                tips.put(bar.getXValue(), ((Tooltip) bar.getNode().getProperties().get("javafx.scene.control.Tooltip")).getText());
            }
            assertEquals("UPPR3\n+5,26% hoje\nCotação:  R$" + NBSP + "1.234,56\nVariação: R$" + NBSP + "+61,00", tips.get("UPPR3"));
            assertEquals("DOWN4\n-4,76% hoje\nCotação:  R$" + NBSP + "4.321,09\nVariação: R$" + NBSP + "-216,00", tips.get("DOWN4"));
            assertEquals("ZERO3\n0,00% hoje\nCotação:  R$" + NBSP + "20,00\nVariação: R$" + NBSP + "0,00", tips.get("ZERO3"));
        });
    }

    // ── DESIGN rule 6: the thousands dot lengthens the quote, the column minimum comes from that content ──

    /** The width the cell font needs for {@code text} plus the cell's own horizontal padding. */
    private static double need(javafx.scene.control.Labeled cell, String text) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text(text);
        probe.setFont(cell.getFont());
        return Math.ceil(probe.getLayoutBounds().getWidth() + cell.snappedLeftInset() + cell.snappedRightInset());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePriceColumnMinimumHoldsAFourDigitQuoteWithItsThousandsDot() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.renderStocks(stocks());
            Brk12V1Support.settle(page.view(), 1280, 800);
            TableView<RankingRow> table = field(page, "gainersTable");
            TableCell<RankingRow, ?> sample = null;
            for (Node n : table.lookupAll(".table-cell")) {
                if (n instanceof TableCell<?, ?> c && !c.isEmpty() && c.getText() != null && c.getText().startsWith("R$")) {
                    sample = (TableCell<RankingRow, ?>) c;
                    break;
                }
            }
            assertNotNull(sample, "a rendered price cell");
            double widest = need(sample, "R$" + NBSP + "8.888,88"); // digits are the same width, so 8 is the widest
            assertTrue(RankingPage.LIST_PRICE_MIN >= widest,
                    "the price column minimum (" + RankingPage.LIST_PRICE_MIN + " px) must hold 'R$ 8.888,88' (" + widest
                            + " px with the cell padding) in the font of the cells: " + sample.getFont());
        });
    }

    @Test
    void theDayListsKeepEveryTextWhole() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.renderStocks(stocks());
            Brk12V1Support.settle(page.view(), 1280, 800);
            javafx.scene.Parent lists = field(page, "listsPair");
            Brk12V1Support.assertNotCut(lists, "Ranking day lists");
        });
    }

    // ── the production stylesheet paints the classes of the bars ─────────────

    private record BarClass(String cls, String hex) {}

    @Test
    void theBarClassesPaintTheBackgroundWithTheTokens_andHoverDarkensByOpacity() throws Exception {
        FxTestSupport.runAndWait(() -> {
            PseudoClass hover = PseudoClass.getPseudoClass("hover");
            for (BarClass c : List.of(new BarClass("var-bar-pos", "#74D7A6"), new BarClass("var-bar-neg", "#FF9595"),
                    new BarClass("var-bar-muted", "#A8B6C6"))) {
                javafx.scene.layout.StackPane bar = new javafx.scene.layout.StackPane();
                bar.getStyleClass().addAll("chart-bar", c.cls());
                bar.setPrefSize(40, 80);
                javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(bar);
                Brk12V1Support.styled(host, 200, 200);
                host.applyCss();
                host.layout();
                assertColor(Color.web(c.hex()), fillOf(bar), c.cls() + " background");
                assertEquals(1.0, bar.getOpacity(), 0.0001, c.cls() + " at rest");
                bar.pseudoClassStateChanged(hover, true);
                host.applyCss();
                assertEquals(0.85, bar.getOpacity(), 0.0001, c.cls() + " under the pointer");
                assertColor(Color.web(c.hex()), fillOf(bar), c.cls() + " keeps its colour under the pointer");
            }
        });
    }

    // ── correction 1, finding 1: the day lists take the SHOWN value, not the raw one ──

    /** Six quotes around the edge of what two places show: +-0,004 read zero, +-0,005 read +-0,01. */
    static Map<String, StockData> edgeStocks() {
        Map<String, StockData> m = new LinkedHashMap<>();
        m.put("UP004", stock("UP004", 10.00, 0.0004, 0.004));
        m.put("DN004", stock("DN004", 10.00, -0.0004, -0.004));
        m.put("UP005", stock("UP005", 10.00, 0.0005, 0.005));
        m.put("DN005", stock("DN005", 10.00, -0.0005, -0.005));
        m.put("UP235", stock("UP235", 10.00, 0.235, 2.35));
        m.put("DN120", stock("DN120", 10.00, -0.12, -1.20));
        return m;
    }

    private static List<String> tickers(List<RankingRow> rows) {
        return rows.stream().map(RankingRow::ticker).toList();
    }

    @Test
    void theViewModelListsAShownRiseAsARise_andAShownFallAsAFall_neverAShownZero() {
        List<RankingRow> gainers = com.daniel.presentation.viewmodel.RankingViewModel.topGainers(edgeStocks());
        List<RankingRow> losers = com.daniel.presentation.viewmodel.RankingViewModel.topLosers(edgeStocks());
        assertEquals(List.of("UP235", "UP005"), tickers(gainers), "Maiores altas: +2,35 and +0,005 (reads +0,01), best first");
        assertEquals(List.of("DN120", "DN005"), tickers(losers), "Maiores baixas: -1,20 and -0,005 (reads -0,01), worst first");
    }

    @Test
    void theTwoDayListsOfThePageShowOnlyWhatReadsAboveOrBelowZero() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            page.renderStocks(edgeStocks());
            TableView<RankingRow> gainers = field(page, "gainersTable");
            TableView<RankingRow> losers = field(page, "losersTable");
            assertEquals(List.of("UP235", "UP005"), tickers(gainers.getItems()), "Maiores altas");
            assertEquals(List.of("DN120", "DN005"), tickers(losers.getItems()), "Maiores baixas");
            for (String zero : List.of("UP004", "DN004")) {
                assertFalse(tickers(gainers.getItems()).contains(zero), zero + " reads 0,00% and is no rise");
                assertFalse(tickers(losers.getItems()).contains(zero), zero + " reads 0,00% and is no fall");
            }
        });
    }

    @Test
    void whenEveryMoveReadsZero_bothListsShowTheEmptyTextOfToday() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            Map<String, StockData> m = new LinkedHashMap<>();
            m.put("UP004", stock("UP004", 10.00, 0.0004, 0.004));
            m.put("DN004", stock("DN004", 10.00, -0.0004, -0.004));
            page.renderStocks(m);
            TableView<RankingRow> gainers = field(page, "gainersTable");
            TableView<RankingRow> losers = field(page, "losersTable");
            assertTrue(gainers.getItems().isEmpty(), "Maiores altas: " + tickers(gainers.getItems()));
            assertTrue(losers.getItems().isEmpty(), "Maiores baixas: " + tickers(losers.getItems()));
            assertEquals("Nenhuma alta entre as 2 cotações obtidas", ((Label) gainers.getPlaceholder()).getText());
            assertEquals("Nenhuma baixa entre as 2 cotações obtidas", ((Label) losers.getPlaceholder()).getText());
        });
    }

    // ── correction 1, finding 2: the colour the cell is DRAWN in, not only its class ──

    private static final Color SUCCESS = Color.web("#74D7A6");
    private static final Color DANGER = Color.web("#FF9595");
    private static final Color TEXT_2 = Color.web("#A8B6C6");
    private static final Color TEXT_1 = Color.web("#EAF0F6");

    /** The rendered cell of {@code table} whose text is {@code text}, in the third column (the change). */
    private static TableCell<?, ?> changeCellShowing(TableView<RankingRow> table, String text) {
        for (Node n : table.lookupAll(".table-cell")) {
            if (n instanceof TableCell<?, ?> c && !c.isEmpty() && text.equals(c.getText())) return c;
        }
        throw new AssertionError("no rendered change cell shows '" + text + "'");
    }

    private static void assertInk(Color expected, javafx.scene.control.Labeled node, String what) {
        assertTrue(node.getTextFill() instanceof Color, what + ": text fill " + node.getTextFill());
        assertColor(expected, (Color) node.getTextFill(), what);
    }

    @Test
    void theChangeCellIsDrawnInTheColourOfItsTone() throws Exception {
        RankingPage[] holder = new RankingPage[1];
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.renderStocks(stocks());
            TableView<RankingRow> gainers = field(page, "gainersTable");
            gainers.setItems(FXCollections.observableArrayList(
                    new RankingRow("UPPR3", "UPPR3", 10, 0.5, 5.26),
                    new RankingRow("DOWN4", "DOWN4", 10, -0.5, -4.76),
                    new RankingRow("ZERO3", "ZERO3", 10, 0.0008, 0.004)));
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            TableView<RankingRow> gainers = field(holder[0], "gainersTable");
            assertInk(SUCCESS, changeCellShowing(gainers, "+5,26%"), "a gain (> 0)");
            assertInk(DANGER, changeCellShowing(gainers, "-4,76%"), "a loss (< 0)");
            assertInk(TEXT_2, changeCellShowing(gainers, "0,00%"), "a shown zero");
        });
    }

    @Test
    void aSelectedRowKeepsThePrimaryTextColour() throws Exception {
        RankingPage[] holder = new RankingPage[1];
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.renderStocks(stocks());
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            TableView<RankingRow> gainers = field(holder[0], "gainersTable");
            TableView<RankingRow> losers = field(holder[0], "losersTable");
            assertInk(SUCCESS, changeCellShowing(gainers, "+5,26%"), "before the selection");
            gainers.getSelectionModel().select(0);
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            TableView<RankingRow> gainers = field(holder[0], "gainersTable");
            TableView<RankingRow> losers = field(holder[0], "losersTable");
            assertInk(TEXT_1, changeCellShowing(gainers, "+5,26%"), "the selected row");
            assertInk(DANGER, changeCellShowing(losers, "-4,76%"), "a row that is not selected keeps its tone");
        });
    }

    @Test
    void theDayListsSitInsideThePanelTheToneRulesNameAsRankingList() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = page();
            for (String name : List.of("gainersTable", "losersTable")) {
                TableView<RankingRow> table = field(page, name);
                Node up = table.getParent();
                while (up != null && !up.getStyleClass().contains("ranking-list")) up = up.getParent();
                assertNotNull(up, name + " is inside a '.ranking-list' panel");
            }
        });
    }
}

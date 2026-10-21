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

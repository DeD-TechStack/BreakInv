package com.daniel.presentation.view.pages;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReportsPageDetailsTest {
    @Test
    @SuppressWarnings("unchecked")
    void reusedActionCellOffersDetailsForEveryRowWithoutStartingReversal() {
        FxTestSupport.runAndWait(() -> {
            var page = new ReportsPage(ReportsPageStateTest.legacyDaily(Map.of(), null), Runnable::run, Runnable::run);
            page.showEntries(ReportsPageLayoutTest.sample(), 0, 95_000);
            TableView<ReportsPage.ExtractRow> table = ReportsPageStateTest.field(page, "table");
            var col = (TableColumn<ReportsPage.ExtractRow, Void>) table.getColumns().get(5);
            var cell = col.getCellFactory().call(col);
            cell.updateTableView(table); cell.updateTableColumn(col);
            for (int i = 0; i < table.getItems().size(); i++) {
                cell.updateIndex(i);
                var row = table.getItems().get(i);
                assertInstanceOf(Button.class, cell.getGraphic(), "every row, including legacy and markers");
                var button = (Button) cell.getGraphic();
                assertEquals("Detalhes", button.getText());
                assertTrue(button.isFocusTraversable());
                assertNotNull(button.getAccessibleText());
                button.fire();
                DetailsPane details = ReportsPageStateTest.field(page, "details");
                assertTrue(details.isVisible());
                assertEquals(row.description(), ((Label) details.lookup(".details-note")).getText());
                assertEquals(row.reversible(), details.actionLabels().contains("Estornar"));
                assertTrue(details.fieldLabels().contains("Data"));
                assertTrue(details.fieldLabels().contains("Identificação"));
            }
            cell.updateIndex(-1);
            assertNull(cell.getGraphic(), "reused empty cell retains no action");
            ToggleGroup filters = ReportsPageStateTest.field(page, "filterGroup");
            ReportsPageLayoutTest.select(filters, "Registros antigos");
            cell.updateIndex(0);
            ((Button) cell.getGraphic()).fire();
            DetailsPane details = ReportsPageStateTest.field(page, "details");
            assertTrue(((Label) details.lookup(".details-note")).getText().contains("cadastro anterior"));
            ReportsPageLayoutTest.select(filters, "Caixa");
            assertFalse(details.isVisible(), "filtered-out detail must not remain actionable");
        });
    }

    @Test
    void returningToPageKeepsChosenMonthRatherThanResettingToToday() {
        FxTestSupport.runAndWait(() -> {
            var page = new ReportsPage(ReportsPageStateTest.legacyDaily(Map.of(), null), Runnable::run, Runnable::run);
            Button previous = ReportsPageStateTest.field(page, "btnPrevMonth");
            previous.fire();
            YearMonth expected = ReportsPageStateTest.field(page, "currentMonth");
            page.onShow();
            assertEquals(expected, ReportsPageStateTest.<YearMonth>field(page, "currentMonth"));
        });
    }
}

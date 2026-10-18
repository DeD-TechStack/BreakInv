package com.daniel.presentation.view.pages;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReportsPageViewportTest {
    @Test
    void sameTableKeepsPriorityCellsInsideViewportThroughResizeAndSidebarWidths() {
        FxTestSupport.runAndWait(() -> {
            var page = new ReportsPage(ReportsPageStateTest.legacyDaily(Map.of(), null), Runnable::run, Runnable::run);
            var view = (Region) page.view();
            var scene = new Scene(view, 1060, 720);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.showEntries(ReportsPageStateTest.wideSample(), 0, 95_000);
            TableView<?> table = ReportsPageStateTest.field(page, "table");
            // Actual shell content widths, not the name of its breakpoint. Same controls throughout.
            for (int width : new int[]{1060, 584, 420, 740, 380, 544, 904, 1060}) {
                page.onBreakpoint(width < 600 ? Breakpoint.NARROW : Breakpoint.MEDIUM);
                view.resize(width, 600);
                for (int pass = 0; pass < 3; pass++) { view.applyCss(); view.layout(); }
                double sum = table.getVisibleLeafColumns().stream().mapToDouble(TableColumn::getWidth).sum();
                assertTrue(sum <= table.getWidth() - 2 + 1, "content=" + width + ", columns=" + sum + ", table=" + table.getWidth());
                for (String label : List.of("Data", "Valor", "")) {
                    var col = table.getColumns().stream().filter(c -> label.equals(c.getText())).findFirst().orElseThrow();
                    assertTrue(col.isVisible(), label);
                }
                for (var node : table.lookupAll(".table-cell")) {
                    if (!(node instanceof TableCell<?,?> cell) || cell.isEmpty() || !cell.isVisible()) continue;
                    var bounds = cell.localToScene(cell.getBoundsInLocal());
                    var viewport = table.localToScene(table.getBoundsInLocal());
                    assertTrue(bounds.getMaxX() <= viewport.getMaxX() + 1, "offscreen cell " + cell.getText() + ", width=" + width);
                    if (cell.getGraphic() instanceof Button button) {
                        var action = button.localToScene(button.getBoundsInLocal());
                        assertTrue(action.getMaxX() <= viewport.getMaxX() + 1, "offscreen Details at " + width);
                        assertTrue(button.getWidth() >= 24 && button.getHeight() >= 24);
                        assertTrue(button.getAccessibleText().startsWith("Detalhes de "));
                    }
                    if (List.of("Data", "Valor").contains(cell.getTableColumn().getText())) {
                        var text = (javafx.scene.text.Text) cell.lookup(".text");
                        assertNotNull(text);
                        assertEquals(cell.getText(), text.getText(), "priority text truncated at " + width);
                    }
                }
            }
        });
    }
}

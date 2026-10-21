package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiThemeControlsTest {
    @Test
    void analyticHeadersUseReadableTextWithoutVerticalGridBorders() {
        FxTestSupport.runAndWait(() -> {
            var table = new javafx.scene.control.TableView<String>();
            table.getStyleClass().addAll("table-analytic", "table-dense");
            var column = new javafx.scene.control.TableColumn<String, String>("Ativo");
            column.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()));
            table.getColumns().add(column); table.getItems().add("FICT1");
            var root = new VBox(table); var scene = new Scene(root, 400, 250);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            Region header = (Region) table.lookupAll(".column-header").stream()
                    .filter(n -> n.lookup(".label") != null).findFirst().orElseThrow();
            assertNotNull(header);
            assertTrue(header.getBorder() == null || header.getBorder().getStrokes().stream()
                    .allMatch(s -> s.getWidths().getRight() == 0 && s.getWidths().getLeft() == 0),
                    "no vertical header grid inherited from the old skin");
            var label = (javafx.scene.control.Label) header.lookup(".label");
            assertTrue(label.getFont().getSize() >= 12);
        });
    }

    @Test
    void uncheckedCheckboxDoesNotDisplayAFaintCheckmarkFromTheLegacyBackground() {
        FxTestSupport.runAndWait(() -> {
            var check = new CheckBox("Mostrar arquivados");
            var root = new VBox(check);
            var scene = new Scene(root, 400, 200);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            Region box = (Region) check.lookup(".box");
            Region mark = (Region) check.lookup(".mark");
            assertEquals(box.getBackground().getFills().getLast().getFill(),
                    mark.getBackground().getFills().getLast().getFill(),
                    "unchecked mark must merge with its background, not look checked");
            check.setSelected(true); root.applyCss(); root.layout();
            assertNotEquals(box.getBackground().getFills().getLast().getFill(),
                    mark.getBackground().getFills().getLast().getFill());
        });
    }
}

package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Carteira pilot: dense table (40–44 px rows) with one actions menu per row, every field still
 * reachable (in the table when wide, in the details pane when narrow), details beside the table
 * when wide and below it otherwise, and no implementation vocabulary on screen.
 */
class InvestmentTypesPageLayoutTest {

    InvestmentTypesPage page;
    Parent view;

    @BeforeEach
    void setUp() {
        FxTestSupport.runAndWait(() -> {
            page = new InvestmentTypesPage(FxStubs.emptyDaily());
            view = page.view();
        });
    }

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        // C04: an unskinned SplitPane (no Scene yet) exposes its panes only as items
        if (n instanceof SplitPane split && split.getSkin() == null) for (Node item : split.getItems()) out.addAll(flatten(item));
        return out;
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = InvestmentTypesPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    static InvestmentType sample() {
        return new InvestmentType(7, "Ativo fictício E2", "ACOES", "ALTA", LocalDate.of(2026, 10, 15),
                null, new BigDecimal("120.00"), "ACAO", null, null, "FICT1", new BigDecimal("20.00"), 6, null);
    }

    @Test
    void searchIconAndClearActionHaveReservedSpaceAfterCss() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 584, 600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.NARROW);
            var region = (javafx.scene.layout.Region) view;
            region.resize(584, 600);
            TextField search = field("searchField");
            // C04 (AtlantaFX CustomTextField, already in the product): glyph and clear action live inside the field's frame
            assertTrue(search instanceof atlantafx.base.controls.CustomTextField, "search field is " + search.getClass());
            var custom = (atlantafx.base.controls.CustomTextField) search;
            Node icon = custom.getLeft();
            assertTrue(icon instanceof org.kordamp.ikonli.javafx.FontIcon, "left node is the search glyph: " + icon);
            assertTrue(custom.getRight() instanceof Button, "right node is the clear action: " + custom.getRight());
            Button clear = (Button) custom.getRight();
            assertTrue(clear.isFocusTraversable(), "clearing is reachable from the keyboard, not only by mouse");
            assertEquals("Limpar busca", clear.getAccessibleText());
            for (String input : List.of("", "FICT1", "")) {
                search.setText(input);
                for (int pass = 0; pass < 3; pass++) { region.applyCss(); region.layout(); }
                var iconBounds = search.sceneToLocal(icon.localToScene(icon.getBoundsInLocal()));
                javafx.scene.text.Text text = (javafx.scene.text.Text) search.lookup(".text");
                var textBounds = search.sceneToLocal(text.localToScene(text.getBoundsInLocal()));
                assertTrue(textBounds.getMinX() >= iconBounds.getMaxX() + 4,
                        "search glyph overlaps input/placeholder: text=" + textBounds + ", icon=" + iconBounds);
                assertEquals(!input.isEmpty(), clear.isVisible(), "the clear action appears only when there is text");
                if (!input.isEmpty()) {
                    var clearBounds = search.sceneToLocal(clear.localToScene(clear.getBoundsInLocal()));
                    assertTrue(clearBounds.getMinX() >= textBounds.getMaxX() && clearBounds.getMaxX() <= search.getWidth() + 0.5,
                            "clear action inside the field, after the text: clear=" + clearBounds + ", text=" + textBounds);
                    clear.fire();
                    assertEquals("", search.getText());
                }
            }
        });
    }

    @Test
    void positionStatusRemainsReadableAtNarrowContentWidths() {
        FxTestSupport.runAndWait(() -> {
            java.util.Map<Long, com.daniel.core.service.DailyTrackingUseCase.InvestmentStatus> statuses = field("statusById");
            statuses.put(7L, new com.daniel.core.service.DailyTrackingUseCase.InvestmentStatus(7, sample().name(), true,
                    com.daniel.core.domain.ledger.PositionMode.QUANTITY, 6, 12000, false, false, null,
                    false, 0, null, null));
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            var scene = new javafx.scene.Scene(view, 584, 600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            TableView<InvestmentType> table = field("table");
            var region = (javafx.scene.layout.Region) view;
            for (int width : new int[]{584, 420, 380, 584}) {
                page.onBreakpoint(Breakpoint.NARROW);
                region.resize(width, 600);
                for (int pass = 0; pass < 4; pass++) { region.applyCss(); region.layout(); }
                int checked = 0;
                for (var n : table.lookupAll(".badge")) {
                    if (!n.isVisible() || n.getScene() == null) continue;
                    var label = (Label) n.lookup(".label");
                    if (label == null || label.getText().equals("FICT1")) continue;
                    assertTrue(label.getWidth() >= label.prefWidth(-1) - 1,
                            "status truncated at " + width + ": " + label.getText() + ", actual=" + label.getWidth()
                                    + ", required=" + label.prefWidth(-1));
                    var bounds = n.localToScene(n.getBoundsInLocal());
                    var viewport = table.localToScene(table.getBoundsInLocal());
                    assertTrue(bounds.getMaxX() <= viewport.getMaxX() - 1, "status beyond viewport");
                    checked++;
                }
                assertTrue(checked > 0, "visible status required at " + width);
                for (Node node : table.lookupAll(".table-cell")) {
                    TableCell<?, ?> cell = (TableCell<?, ?>) node;
                    if (!cell.isEmpty() && "Ativo".equals(cell.getTableColumn().getText())) {
                        assertTrue(cell.getAccessibleText().contains("Por quantidade"),
                                "accessible identity must retain the complete position status");
                        Label status = (Label) cell.lookup(".row-status .label");
                        assertEquals("Por qtd.", status.getText(), "short label still identifies a mode, not a quantity value");
                    }
                }
            }
            page.showDetails(sample());
            DetailsPane details = field("details");
            assertTrue(details.fieldLabels().contains("Situação"));
        });
    }

    @Test
    void wideVisibleDatesAndLiquidityAreNotTruncatedByCompetingColumns() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            TableView<InvestmentType> table = field("table");
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 800);
            for (int pass = 0; pass < 4; pass++) { view.applyCss(); view.layout(); }
            TableColumn<?, ?> date = field("dateColRef");
            assertTrue(date.isVisible(), "wide view keeps complete registration date");
            var quantityHeader = table.lookupAll(".column-header").stream()
                    .filter(n -> n instanceof javafx.scene.control.skin.TableColumnHeader h
                            && h.getTableColumn() != null && "Qtd.".equals(h.getTableColumn().getText()))
                    .findFirst().orElseThrow();
            assertEquals("Qtd.", ((javafx.scene.text.Text) quantityHeader.lookup(".label .text")).getText(),
                    "quantity header must not render as ellipsis");
            for (Node n : table.lookupAll(".table-cell")) {
                TableCell<?, ?> cell = (TableCell<?, ?>) n;
                if (cell.isEmpty()) continue;
                if (cell.getTableColumn() == date) {
                    javafx.scene.text.Text text = (javafx.scene.text.Text) cell.lookup(".text");
                    assertEquals("15/10/2026", text.getText(), "actual rendered date, not only cell value");
                }
            }
            for (Node badge : table.lookupAll(".badge")) {
                Label label = (Label) badge.lookup(".label");
                if (label != null && label.getText().startsWith("Alta"))
                    assertTrue(label.getWidth() >= label.prefWidth(-1) - 1, "liquidity badge must fit when shown");
            }
        });
    }

    @Test
    void pageContentDoesNotForceHorizontalOverflowWithExpandedSidebarAtMinimumWindow() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ScrollPane scroll = (ScrollPane) view;
            for (double width : new double[]{1060, 380, 544, 740, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                scroll.resize(width, 480);
                for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
                assertTrue(scroll.getContent().getLayoutBounds().getWidth() <= scroll.getViewportBounds().getWidth() + 1,
                        "content exceeds actual viewport at " + width + ": " + scroll.getContent().getLayoutBounds()
                                + ((javafx.scene.layout.VBox) scroll.getContent()).getChildren().stream()
                                .map(n -> n.getStyleClass() + " min=" + n.minWidth(-1)).toList());
                for (String fieldName : new String[]{"addBtn", "showArchived", "searchField"}) {
                    Node control = field(fieldName);
                    assertTrue(control.localToScene(control.getBoundsInLocal()).getMaxX() <= width,
                            fieldName + " overflows at " + width);
                }
                TableView<?> table = field("table");
                for (Node action : table.lookupAll(".row-more-btn")) {
                    assertTrue(action.localToScene(action.getBoundsInLocal()).getMaxX()
                                    <= table.localToScene(table.getBoundsInLocal()).getMaxX() - 1,
                            "row action exceeds the table viewport at " + width + ": action="
                                    + action.localToScene(action.getBoundsInLocal()) + ", table="
                                    + table.localToScene(table.getBoundsInLocal()) + ", columns="
                                    + table.getVisibleLeafColumns().stream().map(c -> c.getText() + ":" + c.getWidth()).toList());
                }
            }
        });
    }

    @Test
    void positionsWorkspaceKeepsFiltersAndVisibleCountWithTheTable() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            var workspace = flatten(view).stream().filter(n -> n.getStyleClass().contains("positions-workspace"))
                    .findFirst().orElse(null);
            assertNotNull(workspace, "filters and table belong to one workspace, not unrelated cards");
            assertTrue(flatten(workspace).contains(field("searchField")));
            assertTrue(flatten(workspace).contains(field("table")));
            Label count = (Label) flatten(workspace).stream().filter(n -> n.getStyleClass().contains("position-count"))
                    .findFirst().orElseThrow();
            // R2-15: the noun agrees with the total, as the Extrato's "1 de 1 lançamento"
            assertEquals("1 de 1 ativo", count.getText());
            ((TextField) field("searchField")).setText("inexistente");
            assertEquals("0 de 1 ativo", count.getText());
            ((TextField) field("searchField")).clear();
            assertEquals("1 de 1 ativo", count.getText());
            InvestmentType second = new InvestmentType(2, "Tesouro Prefixado 2029", "RENDA_FIXA", "ALTA",
                    java.time.LocalDate.of(2026, 7, 1), new java.math.BigDecimal("12.0"), new java.math.BigDecimal("500.00"),
                    "PREFIXADO", null, null, null, null, null, null);
            items.add(second);
            assertEquals("2 de 2 ativos", count.getText());
        });
    }

    @Test
    void tableHeightFollowsVisibleVolumeWithoutExpandingAllSeventyFiveRows() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            TableView<InvestmentType> table = field("table");
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((javafx.scene.layout.Region) view).resize(1060, 800);
            double tenRows = 0;
            for (int count : new int[]{1, 10, 75, 1}) {
                items.setAll(java.util.Collections.nCopies(count, sample()));
                view.applyCss(); view.layout();
                double height = table.prefHeight(-1);
                if (count == 1) assertTrue(height <= 120, "one row must not reserve a 440px empty box: " + height);
                if (count == 10) { tenRows = height; assertTrue(height >= 400 && height <= 500); }
                if (count == 75) {
                    assertEquals(tenRows, height, .5, "bounded viewport retains virtualized table");
                    assertTrue(table.lookupAll(".table-row-cell").size() < 75);
                }
            }
            TextField search = field("searchField");
            search.setText("inexistente");
            assertTrue(table.getItems().isEmpty());
            assertTrue(table.prefHeight(-1) >= 160, "empty state has room for guidance");
            search.clear();
            assertEquals(1, table.getItems().size());
            assertTrue(table.prefHeight(-1) <= 120);
        });
    }

    @Test
    void populatedReferenceWidthKeepsTickerAndCategoryTextUnabridged() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            TableView<?> table = field("table");
            InvestmentType item = new InvestmentType(7, "Nome sintético de ativo muito longo para pressionar a identidade",
                    "FUNDOS", "ALTA", LocalDate.of(2026, 10, 15), null, new BigDecimal("120.00"),
                    "ACAO", null, null, "FICT11", new BigDecimal("20.00"), 6, null);
            items.setAll(java.util.Collections.nCopies(75, item));
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((javafx.scene.layout.Region) view).resize(1060, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            for (int pass = 0; pass < 8; pass++) { view.applyCss(); view.layout(); }
            var labels = table.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                    .filter(l -> l.isVisible() && ("FICT11".equals(l.getText()) || "Fundos de Investimento".equals(l.getText()))).toList();
            assertTrue(labels.size() >= 2);
            for (Label label : labels) {
                var rendered = label.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                        .map(javafx.scene.text.Text.class::cast).map(javafx.scene.text.Text::getText).toList();
                assertTrue(rendered.contains(label.getText()), "critical identity text truncated: " + label.getText() + " -> " + rendered);
                assertTrue(label.getWidth() + 1 >= label.prefWidth(-1), "label lacks measured space: " + label.getText());
            }
        });
    }

    /**
     * r1 populated (75 assets): back at 1280×800 after 600 and 960 the table came back with other
     * columns than on the first wide pass (Ativo 242→220, Custo 131→110, Data 112→133, Qtd 76→101):
     * the constrained policy redistributed the widths left by the narrow passes. The same width
     * gives the same columns, whatever came before (P5 / J1 return to wide).
     */
    @Test
    void returningToWideRestoresTheFirstWideColumns() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            TableView<InvestmentType> table = field("table");
            items.setAll(java.util.Collections.nCopies(75, sample()));
            var scene = new javafx.scene.Scene(view, 1060, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            List<String> first = null;
            List<String> problems = new java.util.ArrayList<>();
            for (double width : new double[]{1060, 544, 740, 1060, 380, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((javafx.scene.layout.Region) view).resize(width, 900);
                for (int pass = 0; pass < 8; pass++) { view.applyCss(); view.layout(); }
                List<String> cols = table.getColumns().stream().filter(TableColumn::isVisible)
                        .map(c -> c.getText() + "=" + Math.round(c.getWidth())).toList();
                if (first == null) first = cols;
                else if (width == 1060 && !cols.equals(first)) problems.add("back to wide: " + cols + " vs first " + first);
            }
            assertEquals(List.of(), problems);
        });
    }

    @Test
    void populatedWideTableReservesSpaceForItsVerticalScrollbar() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            TableView<InvestmentType> table = field("table");
            var scene = new javafx.scene.Scene(view, 1060, 1000);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 1000);
            for (int count : new int[]{1, 10, 75, 1}) {
                items.setAll(java.util.Collections.nCopies(count, sample()));
                for (int pass = 0; pass < 8; pass++) { view.applyCss(); view.layout(); }
                for (Node n : table.lookupAll(".scroll-bar")) {
                    var bar = (javafx.scene.control.ScrollBar) n;
                    if (bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL)
                        assertFalse(bar.isVisible(), "priority columns overflow at " + count + " rows / " + table.getWidth());
                    if (count == 75 && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                        assertTrue(bar.isVisible(), "75 rows require a virtualized vertical viewport");
                        double right = bar.localToScene(bar.getLayoutBounds()).getMinX();
                        for (Node action : table.lookupAll(".row-more-btn"))
                            assertTrue(action.localToScene(action.getLayoutBounds()).getMaxX() <= right + 1,
                                    "row action is covered by vertical scrollbar");
                    }
                }
            }
        });
    }

    @Test
    void wideHeaderKeepsThePrimaryActionOnTheSameRowAsOtherActions() {
        FxTestSupport.runAndWait(() -> {
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((javafx.scene.layout.Region) view).resize(1060, 800);
            for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
            var buttons = flatten(view).stream().filter(n -> n instanceof javafx.scene.control.Button)
                    .map(n -> (javafx.scene.control.Button) n).toList();
            var primary = buttons.stream().filter(b -> "Novo ativo".equals(b.getText())).findFirst().orElseThrow();
            var deposit = buttons.stream().filter(b -> "Aporte".equals(b.getText())).findFirst().orElseThrow();
            assertEquals(deposit.localToScene(deposit.getLayoutBounds()).getCenterY(),
                    primary.localToScene(primary.getLayoutBounds()).getCenterY(), 1,
                    "ample width must not force the primary action onto another line: "
                            + primary.getParent().getLayoutBounds() + " "
                            + primary.getParent().getChildrenUnmodifiable().stream()
                                    .map(n -> n.getLayoutBounds() + " pref=" + n.prefWidth(-1) + " min=" + n.minWidth(-1)).toList());
        });
    }

    @Test
    void emptyPlaceholderDoesNotCoverTheHeaderOrFollowingContent() {
        FxTestSupport.runAndWait(() -> {
            TableView<InvestmentType> table = field("table");
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (double width : new double[]{1060, 544, 380, 740, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((javafx.scene.layout.Region) view).resize(width, 800);
                for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
                Node placeholder = table.getPlaceholder();
                Node header = table.lookup(".column-header-background");
                assertNotNull(header);
                var emptyBounds = placeholder.localToScene(placeholder.getLayoutBounds());
                var headerBounds = header.localToScene(header.getLayoutBounds());
                var tableBounds = table.localToScene(table.getLayoutBounds());
                assertTrue(emptyBounds.getMinY() >= headerBounds.getMaxY() - 1,
                        "empty state overlaps header at " + width + ": " + emptyBounds + " / " + headerBounds);
                assertTrue(emptyBounds.getMaxY() <= tableBounds.getMaxY() + 1,
                        "empty state spills below table at " + width + ": " + emptyBounds + " / " + tableBounds);
            }
        });
    }

    /** E1 residual: the no-result placeholder is contained like the empty one and its hint is never cut. */
    @Test
    void noSearchResultPlaceholderStaysInsideTheTableWithItsTextWhole() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            TableView<InvestmentType> table = field("table");
            TextField search = field("searchField");
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            search.setText("zzz sem resultado");
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 544, 380, 740, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((javafx.scene.layout.Region) view).resize(width, 800);
                for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
                Node placeholder = table.getPlaceholder();
                List<Label> texts = placeholder.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast).toList();
                if (texts.stream().noneMatch(l -> l.getText().startsWith("Nenhum resultado"))) problems.add(width + ": not the no-result state");
                var headerBounds = table.lookup(".column-header-background").localToScene(table.lookup(".column-header-background").getLayoutBounds());
                var emptyBounds = placeholder.localToScene(placeholder.getLayoutBounds());
                var tableBounds = table.localToScene(table.getLayoutBounds());
                if (emptyBounds.getMinY() < headerBounds.getMaxY() - 1) problems.add(width + ": covers the header");
                if (emptyBounds.getMaxY() > tableBounds.getMaxY() + 1) problems.add(width + ": spills below the table");
                for (Label label : texts) {
                    var shown = (javafx.scene.text.Text) label.lookup(".text");
                    if (shown != null && !label.getText().equals(shown.getText())) problems.add(width + ": cut '" + shown.getText() + "'");
                }
            }
            assertTrue(problems.isEmpty(), problems.toString());
        });
    }

    /** H residual: the selection hint appears only when a row can be selected and none is. */
    @Test
    void selectionHintOnlyWhenThereIsARowToSelect() {
        FxTestSupport.runAndWait(() -> {
            Label hint = flatten(view).stream().filter(Label.class::isInstance).map(Label.class::cast)
                    .filter(l -> l.getText() != null && l.getText().startsWith("Selecione um ativo")).findFirst().orElseThrow();
            assertFalse(hint.isVisible() || hint.isManaged(), "0 assets: nothing to select");
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            assertTrue(hint.isVisible() && hint.isManaged(), "a row and no selection: say how to open it");
            TableView<InvestmentType> table = field("table");
            table.getSelectionModel().select(0);
            assertFalse(hint.isVisible() || hint.isManaged(), "the detail is open: the hint is redundant");
            table.getSelectionModel().clearSelection();
            assertTrue(hint.isVisible());
            ((TextField) field("searchField")).setText("zzz sem resultado");
            assertFalse(hint.isVisible() || hint.isManaged(), "no visible row: nothing to select");
        });
    }

    /** H-E4: the ticker badge is drawn with the embedded Plex face, not a locally installed Courier New. */
    @Test
    void tickerBadgeUsesTheEmbeddedFace() {
        FxTestSupport.runAndWait(() -> {
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(sample());
            TableView<?> table = field("table");
            var scene = new javafx.scene.Scene(view, 1060, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((javafx.scene.layout.Region) view).resize(1060, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
            Label ticker = table.lookupAll(".ticker-badge").stream().filter(Label.class::isInstance).map(Label.class::cast)
                    .filter(l -> "FICT1".equals(l.getText())).findFirst().orElseThrow();
            assertTrue(ticker.getFont().getFamily().startsWith("IBM Plex Sans"), "ticker face: " + ticker.getFont());
        });
    }

    /** E-V4: the selected asset's leading action (Comprar) is a contained accent action, not a neutral button. */
    @Test
    void detailsLeadWithBuyAsTheContainedAccentAction() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.showDetails(sample());
            DetailsPane details = field("details");
            List<Button> buttons = flatten(details).stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
            Button buy = buttons.stream().filter(b -> "Comprar".equals(b.getText())).findFirst().orElseThrow();
            Button sell = buttons.stream().filter(b -> "Vender".equals(b.getText())).findFirst().orElseThrow();
            assertTrue(buy.getStyleClass().contains("secondary-btn"), "buy is the detail's accent action: " + buy.getStyleClass());
            assertFalse(buy.getStyleClass().contains("primary-btn"), "the solid primary stays with the page action (Novo ativo)");
            assertTrue(sell.getStyleClass().contains("sell-btn"));
            assertTrue(buttons.indexOf(buy) < buttons.indexOf(sell), "buy leads the actions");
        });
    }

    /** E-V2 class: the portfolio warning banner spans the page like the workspace under it, even with a short message. */
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
    void tableRowsAreDense_andEachRowHasASingleActionsMenu() throws Exception {
        FxTestSupport.runAndWait(() -> {
            TableView<InvestmentType> table = field("table");
            assertTrue(table.getFixedCellSize() >= 40 && table.getFixedCellSize() <= 44, "row height " + table.getFixedCellSize());
            long actionCols = table.getColumns().stream().filter(c -> c.getStyleClass().contains("actions-col")).count();
            assertEquals(1, actionCols, "exactly one actions column");
            TableColumn<InvestmentType, ?> actions = table.getColumns().stream()
                    .filter(c -> c.getStyleClass().contains("actions-col")).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            TableCell<InvestmentType, Object> cell = ((TableColumn<InvestmentType, Object>) actions).getCellFactory().call((TableColumn<InvestmentType, Object>) actions);
            // the cell graphic is created on first update; it must be one MenuButton, not four buttons
            table.setItems(FXCollections.observableArrayList(sample()));
            cell.updateTableView(table);
            cell.updateTableColumn((TableColumn<InvestmentType, Object>) actions);
            cell.updateIndex(0);
            Node graphic = cell.getGraphic();
            assertTrue(graphic instanceof MenuButton, "one ⋯ menu per row, got " + graphic);
            assertNotNull(((MenuButton) graphic).getTooltip());
            assertNotNull(((MenuButton) graphic).getAccessibleText());
        });
    }

    @Test
    void secondaryColumnsHideWhenNarrow_butTheDetailsPaneStillShowsThem() throws Exception {
        FxTestSupport.runAndWait(() -> {
            TableColumn<?, ?> cat = field("catColRef");
            TableColumn<?, ?> liq = field("liqColRef");
            TableColumn<?, ?> date = field("dateColRef");
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(cat.isVisible() && liq.isVisible() && date.isVisible());
            page.onBreakpoint(Breakpoint.NARROW);
            assertFalse(liq.isVisible());
            assertFalse(date.isVisible());

            DetailsPane details = field("details");
            page.showDetails(sample());
            List<String> labels = details.fieldLabels();
            assertTrue(labels.contains("Categoria"), labels.toString());
            assertTrue(labels.contains("Liquidez"), labels.toString());
            assertTrue(labels.contains("Data"), labels.toString());
            assertTrue(labels.contains("Situação"), labels.toString());
        });
    }

    /** C04: beside the table in a native SplitPane when wide, below it otherwise — same node, no re-creation. */
    @Test
    void detailsBesideTheTableWhenWide_belowItOtherwise() throws Exception {
        FxTestSupport.runAndWait(() -> {
            BorderPane body = field("body");
            DetailsPane details = field("details");
            SplitPane split = field("split");
            page.showDetails(sample());
            page.onBreakpoint(Breakpoint.WIDE);
            assertSame(split, body.getCenter(), "wide: table and details share one split");
            assertTrue(inSplit(split, details), "wide: details are the split's second pane");
            assertNull(body.getRight());
            assertNull(body.getBottom());
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertSame(details, body.getBottom(), "medium: details below");
            assertFalse(inSplit(split, details));
            page.onBreakpoint(Breakpoint.NARROW);
            assertSame(details, body.getBottom());
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(inSplit(split, details), "back to wide: beside again");
        });
    }

    @Test
    void detailsPaneOffersEveryAction_andEscapeClosesIt() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DetailsPane details = field("details");
            page.showDetails(sample());
            assertTrue(details.isVisible());
            List<String> actions = details.actionLabels();
            for (String expected : List.of("Editar", "Comprar", "Vender", "Arquivar", "Excluir")) {
                assertTrue(actions.contains(expected), expected + " in " + actions);
            }
            assertTrue(actions.stream().anyMatch(a -> a.startsWith("Reconhecer") || a.startsWith("Reconciliar")), actions.toString());
            details.requestClose();
            assertFalse(details.isVisible(), "Esc/close hides the pane");
        });
    }

    @Test
    void cashAndCreateActionsAreInTheHeader_andFiltersAreOneToolbar() throws Exception {
        FxTestSupport.runAndWait(() -> {
            List<String> buttons = flatten(view).stream().filter(n -> n instanceof ButtonBase && !(n instanceof MenuButton))
                    .map(n -> ((ButtonBase) n).getText()).filter(t -> t != null).toList();
            assertTrue(buttons.stream().anyMatch(t -> t.startsWith("Novo")), buttons.toString());
            assertTrue(buttons.contains("Aporte"), buttons.toString());
            assertTrue(buttons.contains("Retirada"), buttons.toString());
            MenuButton cashMore = field("cashMoreBtn");
            List<String> items = cashMore.getItems().stream().map(MenuItem::getText).filter(t -> t != null).toList();
            assertTrue(items.stream().anyMatch(t -> t.startsWith("Ajuste de caixa")), items.toString());
            assertNotNull(field("searchField"));
            assertNotNull(field("categoryFilter"));
            assertNotNull(field("showArchived"));
        });
    }

    @Test
    void noImplementationVocabularyOnScreen() {
        FxTestSupport.runAndWait(() -> {
            page.showDetails(sample());
            List<String> texts = new ArrayList<>();
            for (Node n : flatten(view)) {
                if (n instanceof Labeled l && l.getText() != null) texts.add(l.getText());
                if (n instanceof Control c && c.getTooltip() != null) texts.add(c.getTooltip().getText());
            }
            for (TableColumn<?, ?> c : ((TableView<?>) flatten(view).stream().filter(TableView.class::isInstance).findFirst().orElseThrow()).getColumns()) {
                texts.add(c.getText());
            }
            List<String> bad = texts.stream().filter(t -> {
                String s = t.toLowerCase();
                return s.contains("ledger") || s.contains("replay") || s.contains("schema");
            }).toList();
            assertTrue(bad.isEmpty(), bad.toString());
        });
    }

    // ── C04 pilot: a list/detail workspace that uses the monitor ──────────────

    static boolean inSplit(SplitPane split, Node node) {
        return split.getItems().stream().anyMatch(item -> flatten(item).contains(node));
    }

    static com.daniel.presentation.view.util.UiPreferences memoryPrefs() {
        return new com.daniel.presentation.view.util.UiPreferences(new com.daniel.presentation.view.window.MemoryPreferences());
    }

    /** The page over its own preferences, so a remembered width never leaks between tests. */
    void usePrefs(com.daniel.presentation.view.util.UiPreferences prefs) {
        page = new InvestmentTypesPage(FxStubs.emptyDaily(), prefs);
        view = page.view();
    }

    static List<InvestmentType> numbered(int count) {
        List<InvestmentType> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(new InvestmentType(100 + i, "Ativo sintético " + i, "ACOES", "ALTA", LocalDate.of(2026, 10, 15),
                    null, new BigDecimal("120.00"), "ACAO", null, null, "FIC" + i, new BigDecimal("20.00"), 6, null));
        }
        return out;
    }

    void layOut(double width, double height) {
        page.onBreakpoint(Breakpoint.of(width));
        ((javafx.scene.layout.Region) view).resize(width, height);
        for (int pass = 0; pass < 8; pass++) { view.applyCss(); view.layout(); }
    }

    javafx.scene.Scene styledScene(double width, double height) {
        var scene = new javafx.scene.Scene(view, width, height);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        return scene;
    }

    double detailWidth(SplitPane split) {
        return split.getItems().get(split.getItems().size() - 1).getLayoutBounds().getWidth();
    }

    /**
     * C04 (Portfolio Performance pattern, native SplitPane): the detail beside the table has a width the
     * user adjusts on the divider; the limits come from what each side shows, and moving the divider keeps
     * the selection and the detail open.
     */
    @Test
    void wideDetailSitsInAnAdjustableSplitBoundedByWhatEachSideShows() {
        FxTestSupport.runAndWait(() -> {
            usePrefs(memoryPrefs());
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(75));
            styledScene(1060, 800);
            TableView<InvestmentType> table = field("table");
            DetailsPane details = field("details");
            SplitPane split = field("split");
            layOut(1060, 800);
            table.getSelectionModel().select(3);
            layOut(1060, 800);
            assertEquals(2, split.getItems().size(), "table and detail are the split's two panes");
            assertTrue(inSplit(split, table) && inSplit(split, details));
            assertFalse(SplitPane.isResizableWithParent(split.getItems().get(1)), "a wider window widens the table, not the detail");
            double start = detailWidth(split);
            split.setDividerPositions(split.getDividerPositions()[0] - 0.12);
            layOut(1060, 800);
            assertTrue(detailWidth(split) > start + 60, "the divider widens the detail: " + start + " -> " + detailWidth(split));
            split.setDividerPositions(0.99);
            layOut(1060, 800);
            assertTrue(detailWidth(split) + 1 >= details.minWidth(-1) && details.getWidth() + 1 >= details.prefWidth(-1) * 0.5
                            && detailWidth(split) >= 240,
                    "the detail keeps the room its fields need: " + detailWidth(split) + " min=" + details.minWidth(-1));
            split.setDividerPositions(0.02);
            layOut(1060, 800);
            for (Node n : table.lookupAll(".scroll-bar")) {
                if (((ScrollBar) n).getOrientation() == javafx.geometry.Orientation.HORIZONTAL)
                    assertFalse(n.isVisible(), "the table keeps its priority columns whole at " + table.getWidth());
            }
            for (Node action : table.lookupAll(".row-more-btn"))
                assertTrue(action.localToScene(action.getLayoutBounds()).getMaxX() <= table.localToScene(table.getLayoutBounds()).getMaxX(),
                        "row action stays inside the narrowed table");
            assertSame(items.get(3), table.getSelectionModel().getSelectedItem(), "adjusting the divider keeps the selection");
            assertTrue(details.isVisible(), "and keeps the detail open");
        });
    }

    /** C04: the width chosen on the divider is a UI preference (synthetic home in tests), restored on the next open. */
    @Test
    void theDetailWidthChosenOnTheDividerIsRememberedForTheNextOpen() {
        var prefs = memoryPrefs();
        double[] chosen = new double[1];
        FxTestSupport.runAndWait(() -> {
            usePrefs(prefs);
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(10));
            styledScene(1060, 800);
            TableView<InvestmentType> table = field("table");
            SplitPane split = field("split");
            layOut(1060, 800);
            table.getSelectionModel().select(0);
            layOut(1060, 800);
            split.setDividerPositions(split.getDividerPositions()[0] - 0.1);
            layOut(1060, 800);
            split.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_RELEASED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, false, null));
            chosen[0] = detailWidth(split);
            assertEquals(chosen[0], prefs.carteiraDetailWidth(), 1.0, "released divider stores the detail width");
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(1);
            layOut(1060, 800);
            assertEquals(chosen[0], detailWidth(split), 1.5, "reopening keeps the chosen width");
        });
        FxTestSupport.runAndWait(() -> {
            usePrefs(prefs); // a new page over the same preferences = the next start of the app
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(10));
            styledScene(1180, 800);
            TableView<InvestmentType> table = field("table");
            SplitPane split = field("split");
            layOut(1180, 800);
            table.getSelectionModel().select(2);
            layOut(1180, 800);
            assertEquals(chosen[0], detailWidth(split), 1.5, "the next start restores the width, whatever the window width");
        });
    }

    /** C04: the FX 21 divider only answers the mouse, so the keyboard gets its own way to resize the detail. */
    @Test
    void theDetailWidthCanAlsoBeAdjustedFromTheKeyboard() {
        var prefs = memoryPrefs();
        FxTestSupport.runAndWait(() -> {
            usePrefs(prefs);
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(10));
            styledScene(1060, 800);
            TableView<InvestmentType> table = field("table");
            DetailsPane details = field("details");
            SplitPane split = field("split");
            layOut(1060, 800);
            table.getSelectionModel().select(0);
            layOut(1060, 800);
            double start = detailWidth(split);
            javafx.event.Event.fireEvent(details, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.LEFT, true, true, false, false));
            layOut(1060, 800);
            double wider = detailWidth(split);
            assertTrue(wider >= start + 20, "Ctrl+Shift+← widens the detail: " + start + " -> " + wider);
            assertEquals(wider, prefs.carteiraDetailWidth(), 1.0, "the keyboard choice is remembered too");
            javafx.event.Event.fireEvent(details, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.RIGHT, true, true, false, false));
            layOut(1060, 800);
            assertEquals(start, detailWidth(split), 1.5, "Ctrl+Shift+→ narrows it back");
            assertSame(items.get(0), table.getSelectionModel().getSelectedItem());
        });
    }

    /**
     * C04 (one scroll per region): wide, the positions table takes the height the window gives the
     * workspace instead of a fixed ten-row box inside a scrolling page; the detail scrolls on its own.
     */
    @Test
    void wideTableTakesTheWindowHeightAndThePageDoesNotScrollAroundIt() {
        FxTestSupport.runAndWait(() -> {
            usePrefs(memoryPrefs());
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(75));
            styledScene(1060, 900);
            TableView<InvestmentType> table = field("table");
            DetailsPane details = field("details");
            ScrollPane scroll = (ScrollPane) view;
            layOut(1060, 900);
            table.getSelectionModel().select(0);
            layOut(1060, 900);
            assertTrue(scroll.getContent().getLayoutBounds().getHeight() <= scroll.getViewportBounds().getHeight() + 1,
                    "the page itself does not scroll at 1060×900: content " + scroll.getContent().getLayoutBounds().getHeight()
                            + " in " + scroll.getViewportBounds().getHeight());
            double rows = (table.getHeight() - 44) / InvestmentTypesPage.ROW_HEIGHT;
            assertTrue(rows > 11, "the table shows more than the old ten-row box: " + rows + " rows in " + table.getHeight());
            assertTrue(table.localToScene(table.getLayoutBounds()).getMaxY() >= 900 - 90,
                    "the table reaches down the window: bottom " + table.localToScene(table.getLayoutBounds()).getMaxY());
            ScrollPane detailScroll = null;
            for (Node p = details.getParent(); p != null; p = p.getParent()) if (p instanceof ScrollPane sp) { detailScroll = sp; break; }
            assertNotNull(detailScroll, "the detail scrolls inside its own pane");
            assertNotSame(scroll, detailScroll);
            assertTrue(table.lookupAll(".table-row-cell").size() < 75, "still virtualized");
        });
    }

    /** C04: crossing breakpoints keeps what the user was doing — selection, filter, open detail, chosen width. */
    @Test
    void crossingBreakpointsKeepsTheSelectionTheFilterAndTheChosenWidth() {
        var prefs = memoryPrefs();
        FxTestSupport.runAndWait(() -> {
            usePrefs(prefs);
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(30));
            styledScene(1060, 800);
            TableView<InvestmentType> table = field("table");
            DetailsPane details = field("details");
            SplitPane split = field("split");
            TextField search = field("searchField");
            BorderPane body = field("body");
            layOut(1060, 800);
            search.setText("sintético 2");
            table.getSelectionModel().select(0);
            layOut(1060, 800);
            InvestmentType picked = table.getSelectionModel().getSelectedItem();
            split.setDividerPositions(split.getDividerPositions()[0] - 0.08);
            layOut(1060, 800);
            split.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_RELEASED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, false, null));
            double chosen = detailWidth(split);
            for (double width : new double[]{740, 544, 740, 1060}) {
                layOut(width, 800);
                assertSame(picked, table.getSelectionModel().getSelectedItem(), "selection kept at " + width);
                assertEquals("sintético 2", search.getText(), "filter kept at " + width);
                assertTrue(details.isVisible(), "detail still open at " + width);
                if (width < 960) assertSame(details, body.getBottom(), "below the table at " + width);
            }
            assertTrue(inSplit(split, details), "beside the table again when wide");
            assertEquals(chosen, detailWidth(split), 1.5, "with the width the user chose");
        });
    }

    /** Waits (bounded) for the page's own first load, so later FX turns are not overwritten by it. */
    void awaitLoadedItems(int count) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            int[] size = new int[1];
            FxTestSupport.runAndWait(() -> {
                javafx.collections.ObservableList<InvestmentType> items = field("allItems");
                size[0] = items.size();
            });
            if (size[0] == count) return;
            Thread.sleep(20);
        }
        fail("the page's first load never delivered " + count + " registrations");
    }

    /** C04: narrow, opening a detail brings it into view instead of leaving it below the fold. */
    @Test
    void narrowDetailIsBroughtIntoViewWhenOpened() throws Exception {
        List<InvestmentType> registrations = numbered(10);
        FxTestSupport.runAndWait(() -> {
            page = new InvestmentTypesPage(FxStubs.dailyWithTypes(registrations), memoryPrefs());
            view = page.view();
            styledScene(584, 600);
        });
        awaitLoadedItems(registrations.size());
        FxTestSupport.runAndWait(() -> {
            layOut(584, 600);
            TableView<InvestmentType> table = field("table");
            table.getSelectionModel().select(0);
            layOut(584, 600);
        });
        FxTestSupport.runAndWait(() -> {}); // the reveal is posted once the detail has opened
        FxTestSupport.runAndWait(() -> {
            for (int pass = 0; pass < 4; pass++) { view.applyCss(); view.layout(); }
            DetailsPane details = field("details");
            ScrollPane scroll = (ScrollPane) view;
            var top = scroll.sceneToLocal(details.localToScene(details.getLayoutBounds())).getMinY();
            assertTrue(top >= -1 && top < scroll.getViewportBounds().getHeight() - 40,
                    "the opened detail starts inside the viewport: " + top + " of " + scroll.getViewportBounds().getHeight());
        });
    }

    // ── C04 round 2: the Carteira's frame, divider, detail width, Ativo column and summary ──────

    static boolean visiblePaint(javafx.scene.paint.Paint paint) {
        return paint instanceof javafx.scene.paint.Color c && c.getOpacity() > 0.01;
    }

    static boolean hasVisibleBorder(javafx.scene.layout.Region region) {
        var border = region.getBorder();
        if (border == null) return false;
        for (var s : border.getStrokes()) {
            var w = s.getWidths();
            if ((visiblePaint(s.getTopStroke()) && w.getTop() > 0) || (visiblePaint(s.getRightStroke()) && w.getRight() > 0)
                    || (visiblePaint(s.getBottomStroke()) && w.getBottom() > 0) || (visiblePaint(s.getLeftStroke()) && w.getLeft() > 0))
                return true;
        }
        return false;
    }

    static boolean hasVisibleFill(javafx.scene.layout.Region region) {
        var background = region.getBackground();
        return background != null && background.getFills().stream().anyMatch(f -> visiblePaint(f.getFill()));
    }

    /** Wide, 75 rows, the first one selected: the state of the p1 capture at 1280×800 (page 1060×800). */
    void wideWithDetailOpen(int rows) throws Exception {
        usePrefs(memoryPrefs());
        javafx.collections.ObservableList<InvestmentType> items = field("allItems");
        items.setAll(numbered(rows));
        styledScene(1060, 800);
        com.daniel.presentation.view.util.UiTheme.applyFont(view);
        layOut(1060, 800);
        TableView<InvestmentType> table = field("table");
        table.getSelectionModel().select(0);
        layOut(1060, 800);
    }

    /** C04 r2 (a): beside the table the detail's frame is its scroll region, so the frame stays whole and the scrollbar runs inside it. */
    @Test
    void wideDetailFrameBelongsToItsScrollRegionNotToTheScrolledCard() {
        FxTestSupport.runAndWait(() -> {
            wideWithDetailOpen(75);
            DetailsPane details = field("details");
            ScrollPane detailsScroll = field("detailsScroll");
            assertTrue(hasVisibleBorder(detailsScroll) && hasVisibleFill(detailsScroll), "the frame is drawn by the scroll region");
            assertFalse(hasVisibleBorder(details), "the scrolled content carries no second frame for the viewport to cut");
            var frame = detailsScroll.localToScene(detailsScroll.getLayoutBounds());
            assertTrue(frame.getMaxY() <= 800 + 0.5, "the whole frame is inside the window: " + frame.getMaxY());
            boolean sawBar = false;
            for (Node n : detailsScroll.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL && bar.isVisible()) {
                    sawBar = true;
                    var b = bar.localToScene(bar.getLayoutBounds());
                    assertTrue(b.getMinX() >= frame.getMinX() && b.getMaxX() <= frame.getMaxX() - 1 && b.getMinY() >= frame.getMinY() + 1
                                    && b.getMaxY() <= frame.getMaxY() - 1, "the detail's scrollbar runs inside its frame: " + b + " in " + frame);
                }
            }
            assertTrue(sawBar, "the 75-row fixture's detail is taller than its pane, so it scrolls");
            layOut(740, 800);
            assertTrue(hasVisibleBorder(details), "below the table the detail keeps its own card");
        });
    }

    /** C04 r2 (b): the divider reads as a full-height line inside a wider grip zone, not as a second scrollbar beside the table's. */
    @Test
    void dividerIsAFullHeightLineInItsOwnGripZoneApartFromTheTableScrollbar() {
        FxTestSupport.runAndWait(() -> {
            wideWithDetailOpen(75);
            SplitPane split = field("split");
            TableView<InvestmentType> table = field("table");
            var divider = (javafx.scene.layout.Region) split.lookup(".split-pane-divider");
            assertNotNull(divider, "the split shows its divider");
            assertTrue(divider.getWidth() >= 9, "the grip zone is wider than the line: " + divider.getWidth());
            var lines = divider.getBackground() == null ? List.<javafx.scene.layout.BackgroundFill>of()
                    : divider.getBackground().getFills().stream().filter(f -> visiblePaint(f.getFill())
                            && divider.getWidth() - f.getInsets().getLeft() - f.getInsets().getRight() <= 2.01
                            && f.getInsets().getTop() <= 0 && f.getInsets().getBottom() <= 0).toList();
            assertFalse(lines.isEmpty(), "a visible full-height line of 1-2 px inside the zone: " + divider.getBackground());
            if (divider.lookup(".horizontal-grabber") instanceof javafx.scene.layout.Region grabber)
                assertTrue(!grabber.isVisible() || grabber.getOpacity() == 0 || !hasVisibleFill(grabber)
                        || grabber.getWidth() < 0.5 || grabber.getHeight() < 0.5, "no short grabber pill that reads like a scrollbar thumb");
            double lineMinX = divider.localToScene(divider.getLayoutBounds()).getMinX() + lines.getFirst().getInsets().getLeft();
            for (Node n : table.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL && bar.isVisible())
                    assertTrue(bar.localToScene(bar.getLayoutBounds()).getMaxX() + 3 <= lineMinX,
                            "room between the table's scrollbar and the line: " + bar.localToScene(bar.getLayoutBounds()).getMaxX() + " / " + lineMinX);
            }
        });
    }

    /** C04 r2 (c): with no width chosen yet, the detail opens wide enough for Comprar, Vender and Editar on one row. */
    @Test
    void detailOpensWideEnoughForItsThreePrimaryActionsOnOneRow() {
        FxTestSupport.runAndWait(() -> {
            wideWithDetailOpen(10);
            DetailsPane details = field("details");
            var actions = (javafx.scene.layout.FlowPane) details.lookup(".details-actions");
            List<Node> buttons = actions.getChildren().stream().filter(Node::isManaged).toList();
            assertEquals(List.of("Comprar", "Vender", "Editar"), buttons.stream().map(b -> ((Labeled) b).getText()).toList());
            double row = buttons.getFirst().getLayoutY();
            for (Node b : buttons) {
                var button = (javafx.scene.layout.Region) b;
                assertEquals(row, b.getLayoutY(), 0.5, ((Labeled) b).getText() + " shares the first action's row");
                assertTrue(button.getWidth() + 0.5 >= button.prefWidth(-1), ((Labeled) b).getText() + " keeps its width: " + button.getWidth());
            }
        });
    }

    /** C04 r2 (d): the Ativo column takes the flexible width; value and state columns keep the width their content needs. */
    @Test
    void assetColumnTakesTheFlexibleWidthWhileValueColumnsKeepTheirOwn() {
        FxTestSupport.runAndWait(() -> {
            wideWithDetailOpen(10);
            TableView<InvestmentType> table = field("table");
            TableColumn<InvestmentType, ?> name = table.getColumns().getFirst();
            TableColumn<InvestmentType, ?> cost = table.getColumns().stream().filter(c -> "Custo".equals(c.getText())).findFirst().orElseThrow();
            assertTrue(cost.isVisible() && cost.getWidth() <= 160.5, "with the detail open the cost column stays at its own width: " + cost.getWidth());
            usePrefs(memoryPrefs());
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(numbered(10));
            styledScene(1500, 900);
            com.daniel.presentation.view.util.UiTheme.applyFont(view);
            layOut(1500, 900);
            TableView<InvestmentType> wide = field("table");
            TableColumn<InvestmentType, ?> wideName = wide.getColumns().getFirst();
            assertTrue(wideName.getWidth() >= wideName.getPrefWidth() + 150,
                    "a wide table gives its spare width to Ativo: " + wideName.getWidth() + " for pref " + wideName.getPrefWidth());
            for (TableColumn<InvestmentType, ?> c : wide.getVisibleLeafColumns()) {
                if (c == wideName || !c.isResizable()) continue;
                assertTrue(c.getWidth() <= c.getPrefWidth() + 35.5, c.getText() + " grows little: " + c.getWidth() + " for pref " + c.getPrefWidth());
            }
            assertNotNull(name);
        });
    }

    /** C04 r2 (d): where the column cuts a long name, the full name stays reachable (tooltip, accessible text, detail title). */
    @Test
    void aLongAssetNameStaysReachableWhereTheColumnCutsIt() {
        FxTestSupport.runAndWait(() -> {
            usePrefs(memoryPrefs());
            String full = "Ativo sintético com nome longo — infraestrutura, energia e desenvolvimento de longo prazo no exterior";
            javafx.collections.ObservableList<InvestmentType> items = field("allItems");
            items.setAll(new InvestmentType(901, full, "ACOES", "ALTA", LocalDate.of(2026, 10, 15), null, new BigDecimal("120.00"),
                    "ACAO", null, null, "FICT9", new BigDecimal("20.00"), 6, null));
            styledScene(1060, 800);
            com.daniel.presentation.view.util.UiTheme.applyFont(view);
            layOut(1060, 800);
            TableView<InvestmentType> table = field("table");
            Label cellName = (Label) table.lookupAll(".cell-primary").stream().filter(n -> full.equals(((Label) n).getText())).findFirst().orElseThrow();
            var shown = (javafx.scene.text.Text) cellName.lookup(".text");
            assertNotEquals(full, shown.getText(), "the fixture is long enough to be cut in the column");
            assertEquals(full, cellName.getTooltip().getText(), "the tooltip holds the whole name");
            Node cell = cellName;
            while (cell != null && !(cell instanceof TableCell<?, ?>)) cell = cell.getParent();
            assertNotNull(cell);
            assertTrue(cell.getAccessibleText().contains(full), "the accessible text holds the whole name");
            table.getSelectionModel().select(0);
            layOut(1060, 800);
            DetailsPane details = field("details");
            Label title = (Label) details.lookup(".details-name");
            assertEquals(full, title.getText());
            assertEquals(full, ((javafx.scene.text.Text) title.lookup(".text")).getText(), "the detail title wraps the whole name");
        });
    }

    /** C04 r2 (e): medium/wide, the summary reads on one line under the title instead of a band of its own; narrow keeps the band. */
    @Test
    void wideSummaryJoinsTheTitleBandAndTheTableGetsRowsBack() {
        FxTestSupport.runAndWait(() -> {
            wideWithDetailOpen(75);
            com.daniel.presentation.view.components.StatStrip strip = field("strip");
            Node line = view.lookup(".stat-line");
            assertNotNull(line, "the title band has a summary line");
            assertTrue(line.isVisible() && line.isManaged(), "shown on the wide page");
            assertFalse(strip.isManaged(), "the separate band leaves the flow");
            Node band = view.lookup(".header-actions");
            assertTrue(line.localToScene(line.getLayoutBounds()).getMaxY() <= band.localToScene(band.getLayoutBounds()).getMaxY() + 0.5,
                    "the summary sits inside the title band");
            TableView<InvestmentType> table = field("table");
            double rows = (table.getHeight() - InvestmentTypesPage.TABLE_HEADER) / InvestmentTypesPage.ROW_HEIGHT;
            assertTrue(rows >= 10.8, "the table gets rows back at 1060×800 (p1: about 9.6): " + rows);
            strip.set(InvestmentTypesPage.STAT_CASH, "R$ 0,00", "muted", "a reconhecer");
            layOut(1060, 800);
            Node cash = line.lookupAll(".stat-line-item").stream()
                    .filter(i -> i.lookup(".stat-label") instanceof Label l && InvestmentTypesPage.STAT_CASH.equals(l.getText()))
                    .findFirst().orElseThrow();
            Label value = (Label) cash.lookup(".stat-value");
            Label qualifier = (Label) cash.lookup(".stat-qualifier");
            assertEquals("R$ 0,00", value.getText(), "the line reads the strip's value");
            assertTrue(value.getStyleClass().contains("muted"), "with its tone");
            assertTrue(qualifier.isVisible() && "a reconhecer".equals(qualifier.getText()), "and its qualifier, apart from the number");
            assertTrue(cash.getAccessibleText() != null && cash.getAccessibleText().contains("a reconhecer"), "read together by assistive technology");
            layOut(584, 600);
            assertTrue(strip.isManaged() && strip.isVisible(), "narrow keeps the stat band");
            assertFalse(line.isManaged(), "and drops the line");
            layOut(1060, 800);
            assertTrue(line.isManaged() && !strip.isManaged(), "wide again, the line comes back");
        });
    }

    /** C04 r2 (a): in the detail's field list every value keeps its own width at the right edge, the status badge included. */
    @Test
    void detailValuesShareOneRightEdgeIncludingTheStatusBadge() {
        FxTestSupport.runAndWait(() -> {
            wideWithDetailOpen(10);
            DetailsPane details = field("details");
            var grid = (javafx.scene.layout.GridPane) details.lookup(".details-fields");
            List<Double> rights = new ArrayList<>();
            for (Node n : grid.getChildren()) {
                Integer column = javafx.scene.layout.GridPane.getColumnIndex(n);
                if (column == null || column != 1 || !n.isVisible()) continue;
                assertTrue(n.getLayoutBounds().getWidth() <= n.prefWidth(-1) + 1,
                        "a value is not stretched across its cell: " + n + " " + n.getLayoutBounds().getWidth() + " > " + n.prefWidth(-1));
                rights.add(n.localToScene(n.getLayoutBounds()).getMaxX());
            }
            assertTrue(rights.size() >= 6, "the fixture lists its fields: " + rights);
            for (double right : rights) assertEquals(rights.getFirst(), right, 1.5, "the values share one right edge: " + rights);
        });
    }
}

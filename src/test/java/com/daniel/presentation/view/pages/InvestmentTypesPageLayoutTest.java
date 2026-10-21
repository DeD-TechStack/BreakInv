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
            for (String input : List.of("", "FICT1", "")) {
                search.setText(input);
                for (int pass = 0; pass < 3; pass++) { region.applyCss(); region.layout(); }
                var icon = search.getParent().getChildrenUnmodifiable().stream()
                        .filter(org.kordamp.ikonli.javafx.FontIcon.class::isInstance).findFirst().orElseThrow();
                var iconBounds = search.sceneToLocal(icon.localToScene(icon.getBoundsInLocal()));
                assertTrue(search.getInsets().getLeft() >= iconBounds.getMaxX() + 4,
                        "search glyph overlaps input/placeholder: inset=" + search.getInsets() + ", icon=" + iconBounds);
                var clear = search.getParent().getChildrenUnmodifiable().stream()
                        .filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
                if (!input.isEmpty()) {
                    assertTrue(clear.isVisible());
                    var clearBounds = search.sceneToLocal(clear.localToScene(clear.getBoundsInLocal()));
                    assertTrue(search.getWidth() - search.getInsets().getRight() <= clearBounds.getMinX() - 2);
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

    @Test
    void detailsBesideTheTableWhenWide_belowItOtherwise() throws Exception {
        FxTestSupport.runAndWait(() -> {
            BorderPane body = field("body");
            DetailsPane details = field("details");
            page.showDetails(sample());
            page.onBreakpoint(Breakpoint.WIDE);
            assertSame(details, body.getRight(), "wide: details on the right");
            assertNull(body.getBottom());
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertSame(details, body.getBottom(), "medium: details below");
            assertNull(body.getRight());
            page.onBreakpoint(Breakpoint.NARROW);
            assertSame(details, body.getBottom());
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
}

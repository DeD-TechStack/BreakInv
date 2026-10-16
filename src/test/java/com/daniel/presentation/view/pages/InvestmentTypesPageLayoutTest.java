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

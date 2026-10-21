package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeaderActions;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extrato (direction A): month navigation in the header, the month's figures as a value strip,
 * flow filters that narrow the same list, one row per movement with a plain-language description,
 * reversals and reversed originals clearly linked, and "Estornar" only where it applies. No
 * implementation vocabulary (ledger/replay/schema) on screen.
 */
class ReportsPageLayoutTest {

    ReportsPage page;
    Parent view;

    @BeforeEach
    void setUp() {
        FxTestSupport.runAndWait(() -> {
            page = new ReportsPage(FxStubs.emptyDaily());
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
        Field f = ReportsPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    Pane pageRoot() {
        return (Pane) ((ScrollPane) view).getContent();
    }

    static StatementEntry ledger(long id, MovementKind kind, String who, long cents, Long reversesId, Long reversedById) {
        return new StatementEntry(LocalDate.of(2026, 10, 5), kind, null, id, null, who == null ? null : 7L, who,
                null, null, null, cents, null, kind == MovementKind.REVERSAL ? "lançado em duplicidade" : null, null,
                reversesId, reversedById, false, false);
    }

    static StatementEntry legacy(long id, String type, long cents) {
        return new StatementEntry(LocalDate.of(2026, 10, 2), null, type, null, id, 7L, "CDB antigo",
                null, 2L, new BigDecimal("50.00"), cents, null, null, null, null, null, false, true);
    }

    static List<StatementEntry> sample() {
        return List.of(
                ledger(1, MovementKind.DEPOSIT, null, 100_000, null, null),
                ledger(2, MovementKind.BUY, "Tesouro Selic", 40_000, null, 3L),
                ledger(3, MovementKind.REVERSAL, "Tesouro Selic", 40_000, 2L, null),
                ledger(4, MovementKind.OPENING, "CDB", 25_000, null, null),
                legacy(9, "BUY", 10_000));
    }

    @Test
    void filteredListReportsItsScopeAndDoesNotReserveEmptyRows() {
        FxTestSupport.runAndWait(() -> {
            page.showEntries(sample(), 0, 0);
            Label count = (Label) pageRoot().lookup(".statement-count");
            assertNotNull(count);
            assertEquals("5 de 5 lançamentos", count.getText());
            select(field("filterGroup"), "Caixa");
            assertEquals("1 de 5 lançamentos", count.getText());
            TableView<?> table = field("table");
            assertTrue(table.getPrefHeight() <= 100, "one movement should not reserve a full empty table");
            Node details = field("details");
            assertSame(table.getParent(), details.getParent(), "detail stays within the statement workspace");
            page.showLoading();
            assertEquals("Carregando…", count.getText());
            page.showFailure("Falha sintética");
            assertEquals("Indisponível", count.getText());
        });
    }

    @Test
    void monthNavigationLivesInTheHeader_withNamedButtons() throws Exception {
        FxTestSupport.runAndWait(() -> {
            HeaderActions header = (HeaderActions) pageRoot().getChildren().get(0);
            Button prev = field("btnPrevMonth");
            Button next = field("btnNextMonth");
            Button current = field("btnCurrentMonth");
            Label month = field("monthLabel");
            for (Button b : List.of(prev, next, current)) {
                assertTrue(flatten(header).contains(b), "month navigation in the header actions");
                assertNotNull(b.getTooltip(), b.getText() + " tooltip");
            }
            assertNotNull(prev.getAccessibleText());
            assertNotNull(next.getAccessibleText());
            assertTrue(flatten(header).contains(month), "the month is the context of the whole page");
        });
    }

    @Test
    void monthFiguresAreAValueStrip_thatReflows_andTheGainIsQualified() throws Exception {
        FxTestSupport.runAndWait(() -> {
            StatStrip strip = field("strip");
            assertEquals(List.of("Aportes no mês", "Compras no mês", "Ganho", "Patrimônio"), strip.labels());
            page.onBreakpoint(Breakpoint.NARROW);
            assertEquals(2, strip.columns());
            page.onBreakpoint(Breakpoint.WIDE);
            assertEquals(4, strip.columns());
            page.showEntries(sample(), 0, 0);
            Label gq = strip.qualifierLabel("Ganho");
            assertTrue(gq.isVisible() && gq.getText().toLowerCase().contains("realizado"), "the gain says what it includes: " + gq.getText());
        });
    }

    @Test
    void rowsSpeakTheUsersLanguage_andReversalsAreLinkedBothWays() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.showEntries(sample(), 0, 0);
            TableView<ReportsPage.ExtractRow> table = field("table");
            List<String> cols = table.getColumns().stream().map(TableColumn::getText).toList();
            assertEquals(List.of("Data", "Nº", "Tipo", "Descrição", "Valor", ""), cols);
            assertEquals(5, table.getItems().size());
            for (ReportsPage.ExtractRow r : table.getItems()) {
                String text = (r.type() + " " + r.description()).toLowerCase();
                assertFalse(text.contains("ledger") || text.contains("replay") || text.contains("schema"), text);
                assertFalse(r.description().startsWith("#"), "the number has its own column: " + r.description());
            }
            ReportsPage.ExtractRow buy = table.getItems().stream().filter(r -> r.number().equals("2")).findFirst().orElseThrow();
            assertTrue(buy.description().toLowerCase().contains("estornado"), buy.description());
            assertTrue(buy.description().contains("nº 3"), "points to the reversal: " + buy.description());
            assertFalse(buy.reversible(), "a reversed movement cannot be reversed again");
            ReportsPage.ExtractRow rev = table.getItems().stream().filter(r -> r.number().equals("3")).findFirst().orElseThrow();
            assertTrue(rev.description().contains("nº 2"), "points back to the original: " + rev.description());
            assertTrue(rev.description().toLowerCase().contains("duplicidade"), "reason shown: " + rev.description());
            assertFalse(rev.reversible());
            ReportsPage.ExtractRow opening = table.getItems().stream().filter(r -> r.number().equals("4")).findFirst().orElseThrow();
            assertEquals("Saldo inicial", opening.type());
            assertFalse(opening.reversible());
            ReportsPage.ExtractRow deposit = table.getItems().stream().filter(r -> r.number().equals("1")).findFirst().orElseThrow();
            assertTrue(deposit.reversible());
            assertTrue(deposit.value().startsWith("+"));
            ReportsPage.ExtractRow old = table.getItems().stream().filter(r -> r.legacy()).findFirst().orElseThrow();
            assertTrue(old.type().toLowerCase().contains("registro antigo"), old.type());
            assertTrue(old.description().toLowerCase().contains("não entra no caixa"), "the risk of the old record is said plainly: " + old.description());
            Label legend = field("legendLabel");
            assertFalse(legend.getText().toLowerCase().contains("ledger"), legend.getText());
        });
    }

    @Test
    void flowFiltersNarrowTheSameList_andWrap() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.showEntries(sample(), 0, 0);
            Node bar = field("filterBar");
            assertTrue(bar instanceof FlowPane, "filters wrap: " + bar.getClass());
            ToggleGroup group = field("filterGroup");
            List<String> labels = group.getToggles().stream().map(t -> ((ToggleButton) t).getText()).toList();
            assertEquals(List.of("Tudo", "Caixa", "Compras e vendas", "Ajustes e estornos", "Registros antigos"), labels);
            TableView<?> table = field("table");
            select(group, "Caixa");
            assertEquals(1, table.getItems().size());
            select(group, "Compras e vendas");
            assertEquals(1, table.getItems().size());
            select(group, "Ajustes e estornos");
            assertEquals(2, table.getItems().size(), "reversal + opening");
            select(group, "Registros antigos");
            assertEquals(1, table.getItems().size());
            select(group, "Tudo");
            assertEquals(5, table.getItems().size());
            // a filter cannot be deselected into "nothing"
            group.getSelectedToggle().setSelected(false);
            assertNotNull(group.getSelectedToggle());
            assertEquals(5, table.getItems().size());
        });
    }

    static void select(ToggleGroup group, String label) {
        for (Toggle t : group.getToggles()) if (((ToggleButton) t).getText().equals(label)) { ((ToggleButton) t).fire(); return; }
        fail("no toggle " + label);
    }
}

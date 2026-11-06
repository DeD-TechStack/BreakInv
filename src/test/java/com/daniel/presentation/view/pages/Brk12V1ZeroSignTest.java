package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.util.MoneyFormat;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.daniel.presentation.view.pages.Brk12V1Support.assertTone;
import static com.daniel.presentation.view.pages.Brk12V1Support.call;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1, correction 2 (finding R1, V1:RF-02): a money amount that SHOWS zero is written without a sign
 * ({@code R$ 0,00}) at every point that builds the sign by hand: the Extrato of Relatórios ({@code ReportsPage.toRow}:
 * old record, deposit, withdrawal, purchase, sale, adjustment) and the recent activity of the Dashboard. A cost-free
 * adjustment of 1.500 units (accepted by the domain) used to read {@code + R$ 0,00}. An amount that is not zero keeps
 * today's text, sign and space ({@code + R$ 1,00}, {@code - R$ 1,00}); the tone of the zero is neutral.
 */
class Brk12V1ZeroSignTest {

    private static final Color TEXT_2 = Color.web("#A8B6C6");
    private static final Color SUCCESS = Color.web("#74D7A6");
    private static final Color DANGER = Color.web("#FF9595");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

    private final Brk12V1DashboardTest dash = new Brk12V1DashboardTest();
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

    /** A ledger movement of {@code cents} (an adjustment is a CORRECTION); the quantity is the caller's. */
    private static StatementEntry movement(long id, MovementKind kind, Long quantity, long cents) {
        return new StatementEntry(DAY, kind, null, id, null, 7L, "Ativo " + id, PositionMode.QUANTITY, quantity, null, cents,
                kind == MovementKind.ADJUSTMENT ? "CORRECTION" : null, null, null, null, null, false, false);
    }

    /** A row of the old {@code transactions} table. */
    private static StatementEntry legacy(long id, String type, long cents) {
        return new StatementEntry(DAY, null, type, null, id, 7L, "CDB antigo " + id, null, 1500L, new BigDecimal("12.5"), cents,
                null, null, null, null, null, false, true);
    }

    private static final List<MovementKind> SIGNED_KINDS = List.of(MovementKind.DEPOSIT, MovementKind.WITHDRAWAL,
            MovementKind.BUY, MovementKind.SELL, MovementKind.ADJUSTMENT);

    /** The kinds that write a "+" (money into the cash) when the amount is not zero. */
    private static boolean entersCash(StatementEntry e) {
        if (e.legacy()) return "SELL".equals(e.legacyType());
        return e.kind() == MovementKind.DEPOSIT || e.kind() == MovementKind.SELL;
    }

    /** Zero for every kind and for the old record, in both directions. */
    private static Map<String, StatementEntry> zeroEntries() {
        Map<String, StatementEntry> m = new LinkedHashMap<>();
        long id = 1;
        for (MovementKind kind : SIGNED_KINDS) m.put(kind + " 0", movement(id++, kind, kind == MovementKind.ADJUSTMENT ? 1500L : null, 0));
        m.put("ADJUSTMENT 0, +1500 un.", movement(id++, MovementKind.ADJUSTMENT, 1500L, 0));
        m.put("ADJUSTMENT 0, -1500 un.", movement(id++, MovementKind.ADJUSTMENT, -1500L, 0));
        m.put("legacy BUY 0", legacy(id++, "BUY", 0));
        m.put("legacy SELL 0", legacy(id, "SELL", 0));
        return m;
    }

    /** Controls: an amount of R$ 1,00 (and R$ 12.345,67) with the sign of today. */
    private static Map<StatementEntry, String> nonZeroEntries() {
        Map<StatementEntry, String> m = new LinkedHashMap<>();
        long id = 100;
        for (long cents : new long[]{100, 1_234_567}) {
            for (MovementKind kind : List.of(MovementKind.DEPOSIT, MovementKind.WITHDRAWAL, MovementKind.BUY, MovementKind.SELL)) {
                StatementEntry e = movement(id++, kind, null, cents);
                m.put(e, (entersCash(e) ? "+ " : "- ") + MoneyFormat.brl(cents));
            }
            m.put(movement(id++, MovementKind.ADJUSTMENT, 1L, cents), "+ " + MoneyFormat.brl(cents));
            m.put(movement(id++, MovementKind.ADJUSTMENT, -1L, -cents), "- " + MoneyFormat.brl(cents));
            StatementEntry oldBuy = legacy(id++, "BUY", cents);
            m.put(oldBuy, "- " + MoneyFormat.brl(cents));
            StatementEntry oldSell = legacy(id++, "SELL", cents);
            m.put(oldSell, "+ " + MoneyFormat.brl(cents));
        }
        return m;
    }

    // ── Extrato: the text ────────────────────────────────────────────────────

    private static ReportsPage reports() {
        return new ReportsPage(ReportsPageStateTest.legacyDaily(Map.of(), null), Runnable::run, Runnable::run);
    }

    private static ReportsPage.ExtractRow rowOf(ReportsPage page, StatementEntry e) throws Exception {
        return (ReportsPage.ExtractRow) call(page, "toRow", new Class<?>[]{StatementEntry.class}, e);
    }

    @Test
    void extrato_aCostFreeAdjustmentOfUnitsShowsNoSign() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            for (long quantity : new long[]{1500, -1500}) {
                assertEquals(MoneyFormat.brl(0), rowOf(page, movement(1, MovementKind.ADJUSTMENT, quantity, 0)).value(),
                        "adjustment of " + quantity + " units at no cost");
            }
        });
    }

    @Test
    void extrato_everyKindWithAZeroValueShowsNoSign() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, StatementEntry> c : zeroEntries().entrySet()) {
                String value = rowOf(page, c.getValue()).value();
                if (!MoneyFormat.brl(0).equals(value)) wrong.add(c.getKey() + " -> '" + value + "'");
            }
            assertEquals(List.of(), wrong, "each of these should read '" + MoneyFormat.brl(0) + "'");
        });
    }

    @Test
    void extrato_anAmountThatIsNotZeroKeepsTheTextOfToday() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<StatementEntry, String> c : nonZeroEntries().entrySet()) {
                String value = rowOf(page, c.getKey()).value();
                if (!c.getValue().equals(value)) wrong.add((c.getKey().legacy() ? "legacy " + c.getKey().legacyType() : c.getKey().kind())
                        + " " + c.getKey().amountCents() + ": expected '" + c.getValue() + "' but '" + value + "'");
            }
            assertEquals(List.of(), wrong);
        });
    }

    // ── Extrato: the tone, as drawn ──────────────────────────────────────────

    /** The cells of the "Valor" column of {@code table}, by their text. */
    private static Map<String, TableCell<?, ?>> valueCells(TableView<ReportsPage.ExtractRow> table) {
        Map<String, TableCell<?, ?>> cells = new LinkedHashMap<>();
        for (Node n : table.lookupAll(".table-cell")) {
            if (n instanceof TableCell<?, ?> c && !c.isEmpty() && c.getTableColumn() != null
                    && "Valor".equals(c.getTableColumn().getText()) && c.getText() != null) {
                cells.put(c.getText(), c);
            }
        }
        return cells;
    }

    @Test
    void extrato_theCellOfAZeroValueHasNoGainOrLossTone() throws Exception {
        ReportsPage[] holder = new ReportsPage[1];
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = reports();
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            page.showEntries(List.of(
                    movement(1, MovementKind.ADJUSTMENT, 1500L, 0),
                    movement(2, MovementKind.ADJUSTMENT, 1L, 100),
                    movement(3, MovementKind.ADJUSTMENT, -1L, -100)), 0, 95_000L);
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            TableView<ReportsPage.ExtractRow> table = field(holder[0], "table");
            Map<String, TableCell<?, ?>> cells = valueCells(table);
            String zero = MoneyFormat.brl(0);
            TableCell<?, ?> zeroCell = cells.get(zero);
            assertNotNull(zeroCell, "a rendered value cell reads '" + zero + "'; the cells are " + cells.keySet());
            assertFalse(zeroCell.getStyleClass().contains("pos") || zeroCell.getStyleClass().contains("neg"),
                    "the zero has no gain or loss tone: " + zeroCell.getStyleClass());
            assertTrue(zeroCell.getTextFill() instanceof Color, "text fill " + zeroCell.getTextFill());
            assertFalse(SUCCESS.equals(zeroCell.getTextFill()) || DANGER.equals(zeroCell.getTextFill()),
                    "the zero is not drawn in the colour of a gain or a loss: " + zeroCell.getTextFill());
            // controls: an amount that is not zero keeps the tone of today
            TableCell<?, ?> gain = cells.get("+ " + MoneyFormat.brl(100));
            TableCell<?, ?> loss = cells.get("- " + MoneyFormat.brl(100));
            assertNotNull(gain, "the gain cell; the cells are " + cells.keySet());
            assertNotNull(loss, "the loss cell; the cells are " + cells.keySet());
            assertTone(gain, "pos", "a gain keeps its tone");
            assertTone(loss, "neg", "a loss keeps its tone");
        });
    }

    // ── Dashboard: the recent activity ───────────────────────────────────────

    private record Shown(String icon, Label value) {}

    /** The first row of the recent activity after it was built from {@code entry} alone. */
    private Shown recent(DashboardPage page, StatementEntry entry) throws Exception {
        call(page, "updateRecentActivity", new Class<?>[]{List.class}, List.of(entry));
        Parent list = field(page, "recentActivityList");
        Node row = list.getChildrenUnmodifiable().get(0);
        List<Node> parts = ((Parent) row).getChildrenUnmodifiable();
        return new Shown(((Label) parts.get(0)).getText(), (Label) parts.get(3));
    }

    @Test
    void dashboard_aCostFreeAdjustmentOfUnitsShowsNoSign() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = dash.page(new Brk12V1DashboardTest.Quotes(false));
            for (long quantity : new long[]{1500, -1500}) {
                assertEquals(MoneyFormat.brl(0), recent(page, movement(1, MovementKind.ADJUSTMENT, quantity, 0)).value().getText(),
                        "adjustment of " + quantity + " units at no cost");
            }
        });
    }

    @Test
    void dashboard_everyKindWithAZeroValueShowsNoSignAndTheNeutralTone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = dash.page(new Brk12V1DashboardTest.Quotes(false));
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, StatementEntry> c : zeroEntries().entrySet()) {
                Shown shown = recent(page, c.getValue());
                if (!MoneyFormat.brl(0).equals(shown.value().getText())) wrong.add(c.getKey() + " -> '" + shown.value().getText() + "'");
                List<String> tones = new ArrayList<>();
                for (String tone : List.of("pos", "neg", "muted")) if (shown.value().getStyleClass().contains(tone)) tones.add(tone);
                if (!List.of("muted").equals(tones)) wrong.add(c.getKey() + " tone " + tones);
            }
            assertEquals(List.of(), wrong, "each of these should read '" + MoneyFormat.brl(0) + "' in the muted tone");
        });
    }

    @Test
    void dashboard_theIconOfTheRowDoesNotChange() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = dash.page(new Brk12V1DashboardTest.Quotes(false));
            assertEquals("▲", recent(page, movement(1, MovementKind.DEPOSIT, null, 0)).icon());
            assertEquals("▼", recent(page, movement(2, MovementKind.WITHDRAWAL, null, 0)).icon());
            assertEquals("▼", recent(page, movement(3, MovementKind.BUY, null, 0)).icon());
            assertEquals("▲", recent(page, movement(4, MovementKind.ADJUSTMENT, 1500L, 0)).icon());
            assertEquals("▲", recent(page, movement(5, MovementKind.ADJUSTMENT, 1L, 100)).icon());
            assertEquals("▼", recent(page, movement(6, MovementKind.ADJUSTMENT, -1L, -100)).icon());
        });
    }

    @Test
    void dashboard_anAmountThatIsNotZeroKeepsTheTextAndTheToneOfToday() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = dash.page(new Brk12V1DashboardTest.Quotes(false));
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<StatementEntry, String> c : nonZeroEntries().entrySet()) {
                Label value = recent(page, c.getKey()).value();
                String name = (c.getKey().legacy() ? "legacy " + c.getKey().legacyType() : c.getKey().kind()) + " " + c.getKey().amountCents();
                if (!c.getValue().equals(value.getText())) wrong.add(name + ": expected '" + c.getValue() + "' but '" + value.getText() + "'");
                String tone = c.getValue().startsWith("+") ? "pos" : "neg";
                if (!value.getStyleClass().contains(tone)) wrong.add(name + ": expected tone " + tone + " in " + value.getStyleClass());
            }
            assertEquals(List.of(), wrong);
        });
    }

    @Test
    void dashboard_theZeroIsDrawnInTheSecondaryTextColour() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = dash.page(new Brk12V1DashboardTest.Quotes(false));
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            for (Map.Entry<String, StatementEntry> c : zeroEntries().entrySet()) {
                Label value = recent(page, c.getValue()).value();
                Brk12V1Support.settle(page.view(), 1280, 800);
                assertNotNull(value.getScene(), c.getKey() + ": in a scene with the production stylesheet");
                assertTrue(value.getTextFill() instanceof Color, c.getKey() + ": text fill " + value.getTextFill());
                Color seen = (Color) value.getTextFill();
                String where = c.getKey() + " ('" + value.getText() + "', classes " + value.getStyleClass() + ", drawn " + seen + ")";
                assertEquals(TEXT_2.getRed(), seen.getRed(), 0.01, "red of " + where);
                assertEquals(TEXT_2.getGreen(), seen.getGreen(), 0.01, "green of " + where);
                assertEquals(TEXT_2.getBlue(), seen.getBlue(), 0.01, "blue of " + where);
            }
        });
    }
}

package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extrato — review findings L1, L7 and G1.
 *
 * <ul>
 *   <li>L1: a past month WITH movements but WITHOUT a snapshot has an unknown end-of-month wealth;
 *       it must show "—" with a qualifier, never R$ 0,00 (zero is a known value).</li>
 *   <li>L7: when the month changes, the table and the strip must never show the previous month's
 *       rows under the new month's label — loading clears them, and a failed load says so on the
 *       page for THAT month.</li>
 *   <li>G1: in a narrow window the table keeps Data / Tipo / Valor / action, hides the secondary
 *       columns (kept reachable as a tooltip), fits its viewport without a horizontal scrollbar
 *       and never ellipsizes a value; the opening/review qualifier is separated from the number.</li>
 * </ul>
 * The worker is an injected queue so every async step is driven by hand; no database, no window.
 */
class ReportsPageStateTest {

    final Deque<Runnable> worker = new ArrayDeque<>();

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    @SuppressWarnings("unchecked")
    static <T> T field(ReportsPage page, String name) throws Exception {
        Field f = ReportsPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    static Transaction tx(long id, LocalDate date, long cents) {
        return new Transaction(id, date, 7, Transaction.BUY, "CDB antigo", null, 2, 5_000L, cents, null);
    }

    /** No ledger: the statement is the legacy transactions of the month; a month may be told to fail. */
    static DailyTrackingUseCase legacyDaily(Map<YearMonth, List<Transaction>> byMonth, YearMonth failing) {
        IInvestmentTypeRepository types = new IInvestmentTypeRepository() {
            @Override public List<InvestmentType> listAll() { return List.of(); }
            @Override public void save(String name) {}
            @Override public void rename(int id, String newName) {}
            @Override public void delete(int id) {}
        };
        IFlowRepository flows = new IFlowRepository() {
            @Override public List<Flow> listForDate(LocalDate date) { return List.of(); }
            @Override public void delete(long id) {}
            @Override public long create(Flow flow) { return 0; }
        };
        ISnapshotRepository snapshots = new ISnapshotRepository() {
            @Override public long getCash(LocalDate date) { return 0; }
            @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) { return Map.of(); }
            @Override public Map<String, Long> seriesForInvestiments(long id) { return Map.of(); }
        };
        ITransactionRepository txs = new ITransactionRepository() {
            @Override public long insert(Transaction transaction) { return 0; }
            @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) {
                YearMonth m = YearMonth.from(start);
                if (m.equals(failing)) throw new IllegalStateException("banco indisponível");
                return byMonth.getOrDefault(m, List.of());
            }
        };
        return new DailyTrackingUseCase(flows, types, snapshots, txs, ticker -> null);
    }

    static StatementEntry ledger(long id, MovementKind kind, String who, long cents) {
        return new StatementEntry(LocalDate.of(2026, 10, 5), kind, null, id, null, who == null ? null : 7L, who,
                null, null, null, cents, null, null, null, null, null, false, false);
    }

    static String rendered(Labeled label) {
        Text t = (Text) label.lookup(".text");
        return t == null ? null : t.getText();
    }

    // ── L1 ─────────────────────────────────────────────────────────────────

    @Test
    void pastMonthWithMovementsButNoSnapshot_showsUnknownWealth_notZero() throws Exception {
        YearMonth prev = YearMonth.now().minusMonths(1);
        DailyTrackingUseCase daily = legacyDaily(Map.of(prev, List.of(tx(9, prev.atDay(3), 10_000))), null);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            Button prevBtn = field(page, "btnPrevMonth");
            prevBtn.fire();
            drain();
            TableView<?> table = field(page, "table");
            assertEquals(1, table.getItems().size(), "the old record of the previous month is listed");
            StatStrip strip = field(page, "strip");
            Label wealth = strip.valueLabel(ReportsPage.STAT_WEALTH);
            assertEquals("\u2014", wealth.getText(), "unknown wealth is a dash, not R$ 0,00");
            Label q = strip.qualifierLabel(ReportsPage.STAT_WEALTH);
            assertTrue(q.isVisible() && q.getText().toLowerCase().contains("sem registro"), "says why it is unknown: " + q.getText());
        });
    }

    @Test
    void knownZeroWealth_isShownAsZero_unknownIsADash() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DailyTrackingUseCase daily = legacyDaily(Map.of(), null);
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            StatStrip strip = field(page, "strip");
            List<StatementEntry> rows = List.of(ledger(1, MovementKind.DEPOSIT, null, 100_000));
            page.showEntries(rows, 0, 0L);
            assertEquals(daily.brl(0), strip.valueLabel(ReportsPage.STAT_WEALTH).getText(), "zero is a known value");
            page.showEntries(rows, 0, null);
            assertEquals("\u2014", strip.valueLabel(ReportsPage.STAT_WEALTH).getText());
        });
    }

    // ── L7 ─────────────────────────────────────────────────────────────────

    @Test
    void changingMonth_neverShowsOldRowsUnderTheNewLabel_andAFailureIsSaidForThatMonth() throws Exception {
        YearMonth now = YearMonth.now();
        YearMonth prev = now.minusMonths(1);
        DailyTrackingUseCase daily = legacyDaily(Map.of(now, List.of(tx(1, now.atDay(1), 20_000))), prev);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            page.onShow();
            drain();
            TableView<?> table = field(page, "table");
            assertEquals(1, table.getItems().size(), "current month loaded");
            StatStrip strip = field(page, "strip");
            assertEquals(daily.brl(20_000), strip.valueLabel(ReportsPage.STAT_BUYS).getText());

            Button prevBtn = field(page, "btnPrevMonth");
            prevBtn.fire(); // request in flight: nothing of the previous month may remain on screen
            Label month = field(page, "monthLabel");
            assertTrue(month.getText().toLowerCase().contains(prev.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("pt-BR")).toLowerCase()));
            assertTrue(table.getItems().isEmpty(), "rows of the other month are gone while this one loads");
            Node loading = field(page, "loadingLabel");
            assertTrue(loading.isVisible() && loading.isManaged(), "loading state is explicit");
            assertNotEquals(daily.brl(20_000), strip.valueLabel(ReportsPage.STAT_BUYS).getText(), "sums of the other month are not shown under the new label");

            drain(); // the load fails
            assertFalse(loading.isVisible());
            Label error = field(page, "errorBanner");
            assertTrue(error.isVisible() && error.isManaged(), "the failure is on the page, not only a toast");
            assertTrue(error.getText().toLowerCase().contains("banco indisponível"), error.getText());
            assertTrue(table.getItems().isEmpty(), "no stale rows under an error");
            assertEquals("\u2014", strip.valueLabel(ReportsPage.STAT_WEALTH).getText());
            assertTrue(strip.qualifierLabel(ReportsPage.STAT_WEALTH).getText().toLowerCase().contains("não foi possível"),
                    strip.qualifierLabel(ReportsPage.STAT_WEALTH).getText());

            Button nextBtn = field(page, "btnNextMonth");
            nextBtn.fire();
            drain();
            assertFalse(error.isVisible(), "a successful load clears the failure");
            assertEquals(1, table.getItems().size());
        });
    }

    // ── G1 ─────────────────────────────────────────────────────────────────

    static List<StatementEntry> wideSample() {
        return List.of(
                ledger(1, MovementKind.DEPOSIT, null, 123_456_789),
                ledger(2, MovementKind.BUY, "Tesouro Selic 2029 longo nome", 40_000),
                ledger(4, MovementKind.OPENING, "CDB", 25_000));
    }

    @Test
    void narrow_keepsDateTypeValueAndAction_hidesSecondaryColumns_noHorizontalOverflow_noEllipsizedValue() throws Exception {
        DailyTrackingUseCase daily = legacyDaily(Map.of(), null);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            Parent view = page.view();
            // 640×600 window at 100 %: 56 px rail → 584 px of content (NARROW)
            Scene scene = new Scene(view, 584, 560);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.NARROW);
            page.showEntries(wideSample(), 0, 95_000L);
            ((Region) view).resize(584, 560);
            view.applyCss();
            view.layout();
            view.applyCss();
            view.layout();

            TableView<ReportsPage.ExtractRow> table = field(page, "table");
            List<String> visible = table.getColumns().stream().filter(TableColumn::isVisible).map(TableColumn::getText).toList();
            assertEquals(List.of("Data", "Tipo", "Valor", ""), visible, "priority columns only when narrow");
            double mins = table.getColumns().stream().filter(TableColumn::isVisible).mapToDouble(TableColumn::getMinWidth).sum();
            assertTrue(table.getWidth() > 0, "table laid out");
            assertTrue(mins <= table.getWidth(), "minimum widths fit the viewport: " + mins + " > " + table.getWidth());
            for (Node sb : table.lookupAll(".scroll-bar")) {
                if (sb instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL) {
                    assertFalse(bar.isVisible(), "no horizontal scrollbar in the statement");
                }
            }

            List<Labeled> cells = new ArrayList<>();
            for (Node n : table.lookupAll(".table-cell")) if (n instanceof Labeled l && l.getText() != null) cells.add(l);
            assertFalse(cells.isEmpty(), "cells are created by the skin in a Scene");
            Labeled big = cells.stream().filter(c -> c.getText().contains("1.234.567,89")).findFirst().orElseThrow();
            String shown = rendered(big);
            assertNotNull(shown);
            assertFalse(shown.contains("\u2026") || shown.contains("..."), "the value is never cut: '" + shown + "'");
            assertEquals(big.getText(), shown);
            Labeled type = cells.stream().filter(c -> "Compra".equals(c.getText())).findFirst().orElseThrow();
            assertNotNull(type.getTooltip(), "secondary details reachable from the type cell when narrow");
            assertTrue(type.getTooltip().getText().contains("Tesouro Selic 2029"), type.getTooltip().getText());
            assertTrue(type.getTooltip().getText().contains("2"), "the number is part of the detail: " + type.getTooltip().getText());

            ReportsPage.ExtractRow opening = table.getItems().stream().filter(r -> r.number().equals("4")).findFirst().orElseThrow();
            assertEquals(daily.brl(25_000), opening.value(), "the number stands alone; the qualifier is not glued to it");
            assertTrue(opening.description().toLowerCase().contains("não é aporte"), "qualifier lives in the description: " + opening.description());

            page.onBreakpoint(Breakpoint.WIDE);
            ((Region) view).resize(1060, 720);
            view.applyCss();
            view.layout();
            view.applyCss();
            view.layout();
            assertEquals(6, table.getColumns().stream().filter(TableColumn::isVisible).count(), "wide: every column back");
        });
    }
}

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
        return legacyDaily(byMonth, failing, 0L);
    }

    /** As above, with {@code cash} as the cash balance on any date — the wealth when there is no position. */
    static DailyTrackingUseCase legacyDaily(Map<YearMonth, List<Transaction>> byMonth, YearMonth failing, long cash) {
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
            @Override public long getCashOnOrBefore(LocalDate date) { return cash; }
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

    // ── R2-18 / R2-17 (candidate-retomada-r2 review) ───────────────────────

    /**
     * R2-18: a month without movements said "Patrimônio — / sem lançamentos no mês" while the
     * Diversificação showed R$ 21.695.487,50. Gain and wealth are values AT a date, not sums of the
     * month's movements: the current month shows today's, a past month its end-of-month record (or why
     * there is none), and only a month still to come has neither.
     */
    @Test
    void aMonthWithoutMovementsStillShowsGainAndWealthAtItsDate() throws Exception {
        DailyTrackingUseCase daily = legacyDaily(Map.of(), null, 2_169_548_750L);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            page.onShow();
            drain();
            StatStrip strip = field(page, "strip");
            TableView<?> table = field(page, "table");
            assertTrue(table.getItems().isEmpty(), "no movement this month");
            assertEquals(daily.brl(2_169_548_750L), strip.valueLabel(ReportsPage.STAT_WEALTH).getText(), "today's wealth");
            assertEquals("hoje", strip.qualifierLabel(ReportsPage.STAT_WEALTH).getText());
            assertEquals(daily.brl(0), strip.valueLabel(ReportsPage.STAT_GAIN).getText(), "the gain to date is known");

            ((Button) field(page, "btnPrevMonth")).fire();
            drain();
            assertEquals("\u2014", strip.valueLabel(ReportsPage.STAT_WEALTH).getText(), "no end-of-month record");
            assertTrue(strip.qualifierLabel(ReportsPage.STAT_WEALTH).getText().contains("sem registro"),
                    strip.qualifierLabel(ReportsPage.STAT_WEALTH).getText());

            ((Button) field(page, "btnCurrentMonth")).fire();
            drain();
            ((Button) field(page, "btnNextMonth")).fire();
            drain();
            for (String stat : List.of(ReportsPage.STAT_GAIN, ReportsPage.STAT_WEALTH)) {
                assertEquals("\u2014", strip.valueLabel(stat).getText(), stat + ": a month still to come has no value yet");
                assertTrue(strip.qualifierLabel(stat).getText().contains("futuro"), strip.qualifierLabel(stat).getText());
            }
        });
    }

    /**
     * R2-17: at 536 px (a 600×480 window beside the rail) the statement showed Data, Valor and a
     * "Detalhes" text button and dropped Tipo — the kind of a movement was only in its details. The
     * short date and the icon-only action come before losing the column.
     */
    @Test
    void at536pxTheTypeKeepsItsColumn_theDateAndTheActionGoShortFirst() throws Exception {
        DailyTrackingUseCase daily = legacyDaily(Map.of(), null);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            Parent view = page.view();
            Scene scene = new Scene(view, 536, 440);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.NARROW);
            page.showEntries(wideSample(), 0, 95_000L);
            ((Region) view).resize(536, 440);
            for (int pass = 0; pass < 3; pass++) { view.applyCss(); view.layout(); }
            TableView<ReportsPage.ExtractRow> table = field(page, "table");
            List<String> visible = table.getColumns().stream().filter(TableColumn::isVisible).map(TableColumn::getText).toList();
            assertEquals(List.of("Data", "Tipo", "Valor", ""), visible, "the kind keeps its column");
            for (Node sb : table.lookupAll(".scroll-bar"))
                if (sb instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL)
                    assertFalse(bar.isVisible(), "no horizontal scrollbar in the statement");
            List<Labeled> cells = new ArrayList<>();
            for (Node n : table.lookupAll(".table-cell")) if (n instanceof Labeled l && l.getText() != null) cells.add(l);
            Labeled big = cells.stream().filter(c -> c.getText().contains("1.234.567,89")).findFirst().orElseThrow();
            assertEquals(big.getText(), rendered(big), "the value is never cut");
            Labeled type = cells.stream().filter(c -> "Compra".equals(c.getText())).findFirst().orElseThrow();
            assertEquals("Compra", rendered(type), "the kind is read whole");
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

    // ── A-V8 ───────────────────────────────────────────────────────────────

    static String placeholderText(TableView<?> table) {
        List<String> texts = new ArrayList<>();
        Node placeholder = table.getPlaceholder();
        if (placeholder instanceof Labeled l) texts.add(l.getText());
        if (placeholder instanceof Parent p) for (Node n : p.lookupAll(".label")) if (n instanceof Labeled l) texts.add(l.getText());
        return String.join(" | ", texts);
    }

    static ToggleButton filter(ReportsPage page, String text) throws Exception {
        javafx.scene.layout.FlowPane bar = field(page, "filterBar");
        return bar.getChildren().stream().map(n -> (ToggleButton) n).filter(t -> t.getText().equals(text)).findFirst().orElseThrow();
    }

    /**
     * A-V8: the table's own message agrees with the page state. A month being read or that
     * failed to load is never "no movement in this period"; an empty filter names the filter;
     * the counter says "lançamento" for one.
     */
    @Test
    void placeholderAndCountFollowTheState_loadingFailureEmptyMonthAndEmptyFilter() throws Exception {
        YearMonth now = YearMonth.now();
        YearMonth prev = now.minusMonths(1);
        DailyTrackingUseCase daily = legacyDaily(Map.of(now, List.of(tx(1, now.atDay(1), 20_000))), prev);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            TableView<?> table = field(page, "table");
            Label count = field(page, "entryCount");

            page.onShow(); // request in flight
            String loading = placeholderText(table).toLowerCase();
            assertTrue(loading.contains("carregando"), "loading says so: " + loading);
            assertFalse(loading.contains("nenhum lançamento"), "a month being read is not an empty month: " + loading);

            drain(); // one old record this month
            assertEquals("1 de 1 lançamento", count.getText());
            filter(page, "Caixa").fire(); // an old record is not a cash movement
            assertEquals("0 de 1 lançamento", count.getText());
            String filtered = placeholderText(table).toLowerCase();
            assertTrue(filtered.contains("filtro"), "an empty filter names the filter: " + filtered);
            assertFalse(filtered.contains("neste período"), "the month has a movement: " + filtered);
            filter(page, "Tudo").fire();

            ((Button) field(page, "btnPrevMonth")).fire();
            drain(); // this month fails
            String failed = placeholderText(table).toLowerCase();
            assertFalse(failed.contains("nenhum lançamento"), "a month that was not read is not empty: " + failed);
            assertTrue(failed.contains("indisponíve"), failed);

            ((Button) field(page, "btnPrevMonth")).fire();
            drain(); // a month read without movements
            String empty = placeholderText(table).toLowerCase();
            assertTrue(empty.contains("nenhum lançamento neste período"), empty);
            assertEquals("0 de 0 lançamentos", count.getText());
        });
    }

    /**
     * r1 at 600×480 (544 px of page): the empty month's hint was cut to "…registrados neste mês ..."
     * because the empty table kept a fixed 240 px. Every state message is whole at every width,
     * including the expanded-sidebar minimum, and returning to wide keeps it whole.
     */
    @Test
    void emptyMonthMessageIsWholeAtEveryWidth() throws Exception {
        DailyTrackingUseCase daily = legacyDaily(Map.of(), null);
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(daily, worker::add, Runnable::run);
            Parent view = page.view();
            Scene scene = new Scene(view, 1060, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onShow();
            drain(); // a month read without movements
            TableView<?> table = field(page, "table");
            assertTrue(placeholderText(table).toLowerCase().contains("nenhum lançamento neste período"), placeholderText(table));
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 584, 544, 380, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((Region) view).resize(width, 900);
                for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
                FxTestSupport.cutTexts(table).forEach(c -> problems.add(width + ": " + c));
            }
            assertEquals(List.of(), problems);
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

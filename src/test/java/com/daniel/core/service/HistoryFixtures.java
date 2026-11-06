package com.daniel.core.service;

import com.daniel.core.domain.entity.DailyEntry;
import com.daniel.core.domain.entity.DailySummary;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.infrastructure.persistence.backup.DatabaseFingerprint;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * BRK-32 S1: synthetic financial histories H1 to H7, each in its OWN test database (a file in a folder the
 * test owns). Everything is written through the product's path: movements through
 * {@link DailyTrackingUseCase#movement} with the date of each day, and the snapshot of each day through
 * {@link DailyTrackingUseCase#takeSnapshotIfNeeded} with the use case's clock held on that day (S1:RF-00).
 * The price is fixed (A 20.00, B 50.00, LEG1 32.00). The only row written by SQL is the legacy cash snapshot
 * of H5, which has no product path.
 *
 * <p>Days are {@code d(1)} = {@link #DAY0} onwards. The helper also reads every reader of the history
 * ({@link #readAll}) as canonical text, so two states can be compared exactly.
 */
public final class HistoryFixtures {

    public static final LocalDate DAY0 = LocalDate.of(2026, 3, 2);
    public static final List<String> NAMES = List.of("H1", "H2", "H3", "H4", "H5", "H6", "H7");

    private static final IStockPriceProvider PRICES = ticker -> switch (ticker) {
        case "FICTA1" -> 20.0;
        case "FICTB1" -> 50.0;
        case "LEG1" -> 32.0;
        default -> null;
    };

    private HistoryFixtures() {
    }

    /** Day number n (1-based) of a history. */
    public static LocalDate d(int n) {
        return DAY0.plusDays(n - 1L);
    }

    /** A built history: its database file, its registrations and its period. */
    public static final class Fixture {
        public final String name;
        public final Path dbFile;
        public final String url;
        /** Label ("A", "B", "C") to registration id, in a fixed order. */
        public final Map<String, Long> ids = new LinkedHashMap<>();
        public LocalDate first;
        public LocalDate last;

        Fixture(String name, Path dbFile) {
            this.name = name;
            this.dbFile = dbFile;
            this.url = urlOf(dbFile);
        }

        public long id(String label) {
            return ids.get(label);
        }

        /** Points the product's database at this fixture. */
        public void open() {
            Database.configure(url);
            Database.open();
        }

        public void close() {
            Database.close();
        }

        /** The intervals every range reader is read on: whole period, last week, last day. */
        public Map<String, LocalDate[]> ranges() {
            Map<String, LocalDate[]> r = new LinkedHashMap<>();
            r.put("full", new LocalDate[]{first, last});
            r.put("week", new LocalDate[]{last.minusDays(6), last});
            r.put("day", new LocalDate[]{last, last});
            return r;
        }
    }

    public static String urlOf(Path dbFile) {
        return "jdbc:sqlite:" + dbFile.toString().replace("\\", "/");
    }

    /** A use case on the database that is currently configured, wired as in production (ledger-backed). */
    public static DailyTrackingUseCase useCase() {
        return new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(), new SnapshotRepository(),
                new TransactionRepository(), PRICES, new DatabaseLedgerAccess());
    }

    // =====================================================================
    // Building
    // =====================================================================

    /** Builds one history in {@code folder}/{@code name}.db and leaves the database closed. */
    public static Fixture build(String name, Path folder) {
        Fixture f = new Fixture(name, folder.resolve(name + ".db"));
        f.open();
        try {
            Rec r = new Rec(name);
            switch (name) {
                case "H1" -> h1(r, f);
                case "H2" -> h2(r, f);
                case "H3" -> h3(r, f);
                case "H4" -> h4(r, f);
                case "H5" -> h5(r, f);
                case "H6" -> h6(r, f);
                case "H7" -> h7(r, f);
                default -> throw new IllegalArgumentException(name);
            }
        } finally {
            f.close();
        }
        return f;
    }

    /** Writes through the product, with the clock held on the day being recorded. */
    private static final class Rec {
        final String p;
        final AtomicReference<LocalDate> today = new AtomicReference<>(DAY0);
        final DailyTrackingUseCase uc;

        Rec(String prefix) {
            this.p = prefix.toLowerCase();
            this.uc = new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(),
                    new SnapshotRepository(), new TransactionRepository(), PRICES, new DatabaseLedgerAccess(), today::get);
        }

        void snap(LocalDate day) {
            today.set(day);
            uc.takeSnapshotIfNeeded(day);
        }

        int create(String label, String name, String ticker, LocalDate day) {
            return uc.createInvestment(new DailyTrackingUseCase.NewInvestment(name, "ACOES", "ALTA", day, null, null,
                            "ACAO", null, null, ticker, null, null, null),
                    DailyTrackingUseCase.OpeningSpec.zero(p + "-open-" + label, day, PositionMode.QUANTITY));
        }

        void deposit(LocalDate day, long cents) {
            uc.movement(MovementRequest.deposit(p + "-dep-" + day, day, cents, null));
        }

        Movement buy(String key, LocalDate day, long id, long qty, String price) {
            return uc.movement(MovementRequest.buy(p + "-" + key, day, id, qty, new BigDecimal(price), null));
        }

        Movement sell(String key, LocalDate day, long id, long qty, String price) {
            return uc.movement(MovementRequest.sell(p + "-" + key, day, id, qty, new BigDecimal(price), null));
        }
    }

    /**
     * Day 1: deposit 10 000.00 (1 000 000 cents), A and B opened at zero, A bought 10 x 20.00 (20 000) and B
     * bought 10 x 50.00 (50 000). Cash after: 1 000 000 - 20 000 - 50 000 = 930 000 cents.
     */
    private static void openAB(Rec r, Fixture f) {
        r.deposit(d(1), 1_000_000L);
        long a = r.create("a", "ATIVO A", "FICTA1", d(1));
        long b = r.create("b", "ATIVO B", "FICTB1", d(1));
        f.ids.put("A", a);
        f.ids.put("B", b);
        r.buy("buy-a", d(1), a, 10, "20.00");
        r.buy("buy-b", d(1), b, 10, "50.00");
    }

    /** H1, closing: days 1 and 2 open; day 3 A is sold whole (10 x 22.00 = 22 000, cash 952 000); days 3 to 5 snapshot. */
    private static void h1(Rec r, Fixture f) {
        openAB(r, f);
        r.snap(d(1));
        r.snap(d(2));
        r.sell("sell-a", d(3), f.id("A"), 10, "22.00");
        r.snap(d(3));
        r.snap(d(4));
        r.snap(d(5));
        f.first = d(1);
        f.last = d(5);
    }

    /** H2, reopening: H1, then day 6 A is bought again, 5 x 20.00 = 10 000 (cash 942 000). */
    private static void h2(Rec r, Fixture f) {
        h1(r, f);
        r.buy("rebuy-a", d(6), f.id("A"), 5, "20.00");
        r.snap(d(6));
        f.last = d(6);
    }

    /** H3, archiving: A closed on day 3, archived on day 4, unarchived and reopened on day 6. */
    private static void h3(Rec r, Fixture f) {
        openAB(r, f);
        r.snap(d(1));
        r.snap(d(2));
        r.sell("sell-a", d(3), f.id("A"), 10, "22.00");
        r.snap(d(3));
        r.uc.archiveType(f.id("A"));
        r.snap(d(4));
        r.snap(d(5));
        r.uc.unarchiveType(f.id("A"));
        r.buy("rebuy-a", d(6), f.id("A"), 5, "20.00");
        r.snap(d(6));
        f.first = d(1);
        f.last = d(6);
    }

    /**
     * H4, cash only: day 1 A 10 x 20.00 and B 4 x 50.00 bought (cash 1 000 000 - 20 000 - 20 000 = 960 000); day 2
     * both sold whole (A 10 x 22.00 = 22 000, B 4 x 55.00 = 22 000; cash 1 004 000); days 2 to 4 snapshot.
     */
    private static void h4(Rec r, Fixture f) {
        r.deposit(d(1), 1_000_000L);
        long a = r.create("a", "ATIVO A", "FICTA1", d(1));
        long b = r.create("b", "ATIVO B", "FICTB1", d(1));
        f.ids.put("A", a);
        f.ids.put("B", b);
        r.buy("buy-a", d(1), a, 10, "20.00");
        r.buy("buy-b", d(1), b, 4, "50.00");
        r.snap(d(1));
        r.sell("sell-a", d(2), a, 10, "22.00");
        r.sell("sell-b", d(2), b, 4, "55.00");
        r.snap(d(2));
        r.snap(d(3));
        r.snap(d(4));
        f.first = d(1);
        f.last = d(4);
    }

    /**
     * H5, legacy: two registrations without a ledger (B = LEG1 with 10 x 30.00, C = invested value 0.00) and a legacy
     * cash snapshot (500.00). Days 1 and 2 are legacy snapshots; day 3 the cash and B are recognised
     * (registerOpening + reconcile: B becomes 8 units); a native A is bought on day 3 and sold on day 4.
     *
     * <p>The legacy cash snapshot is the one row written by SQL: the same INSERT as {@code legacyCash} in
     * PortfolioServiceRulesTest.java:451-455 (a legacy cash snapshot has no product path).
     */
    private static void h5(Rec r, Fixture f) {
        legacyCash(d(1).toString(), 50_000L);
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        long b = repo.createFull("LEGADO ACAO", "ACOES", "ALTA", d(1), null, new BigDecimal("300"),
                "ACAO", null, null, "LEG1", new BigDecimal("30"), 10);
        long c = repo.createFull("LEGADO SEM VALOR", "OUTROS", "BAIXA", d(1), null, new BigDecimal("0"),
                null, null, null, null, null, null);
        f.ids.put("B", b);
        f.ids.put("C", c);
        r.snap(d(1));
        r.snap(d(2));
        DailyTrackingUseCase uc = r.uc;
        uc.registerOpening(uc.proposeCashOpening("h5-cash-open", d(3)));
        uc.reconcile(ReconciliationRequest.cash("h5-cash-rec", d(3), 50_000L, "conferido"));
        uc.registerOpening(uc.proposeOpening("h5-open-b", d(3), b));
        uc.reconcile(ReconciliationRequest.position("h5-rec-b", d(3), b, 8L, 24_000L, "conferido"));
        long a = r.create("a", "ATIVO A", "FICTA1", d(3));
        f.ids.put("A", a);
        r.buy("buy-a", d(3), a, 10, "20.00");
        r.snap(d(3));
        r.sell("sell-a", d(4), a, 10, "22.00");
        r.snap(d(4));
        r.snap(d(5));
        f.first = d(1);
        f.last = d(5);
    }

    /**
     * H6, reversal and retroactive: H1 until day 3 (A sold whole); day 4 the sale is reversed (A is back to 10); day 5 a
     * purchase dated day 2 is entered (A 12). Old snapshots are never rebuilt.
     */
    private static void h6(Rec r, Fixture f) {
        openAB(r, f);
        r.snap(d(1));
        r.snap(d(2));
        Movement sale = r.sell("sell-a", d(3), f.id("A"), 10, "22.00");
        r.snap(d(3));
        r.uc.movement(MovementRequest.reversal("h6-rev-sale", d(4), sale.id(), "lançada por engano"));
        r.snap(d(4));
        r.buy("retro-a", d(2), f.id("A"), 2, "20.00");
        r.snap(d(5));
        f.first = d(1);
        f.last = d(5);
    }

    /**
     * H7, gaps and ranges: the app was opened on 23 days out of 367, around the borders of the panel ranges
     * (30, 90, 180 and 365 days). A is sold on offset 5 and bought again on offset 200.
     */
    private static void h7(Rec r, Fixture f) {
        openAB(r, f);
        int[] opened = {0, 1, 4, 5, 6, 29, 30, 31, 60, 61, 89, 90, 91, 119, 120, 179, 180, 181, 200, 250, 364, 365, 366};
        for (int off : opened) {
            LocalDate day = DAY0.plusDays(off);
            if (off == 5) r.sell("sell-a", day, f.id("A"), 10, "22.00");
            if (off == 200) r.buy("rebuy-a", day, f.id("A"), 5, "20.00");
            r.snap(day);
        }
        f.first = DAY0;
        f.last = DAY0.plusDays(366);
    }

    private static void legacyCash(String date, long cents) {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO cash_snapshots (date, value_cents) VALUES ('" + date + "', " + cents + ")");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // =====================================================================
    // Reading: canonical text of every reader
    // =====================================================================

    /**
     * Every reader of the history on the database that is currently open, as canonical text, in a fixed order.
     * Keys have no spaces; they are the names printed in the {@code BRK32S1} lines.
     */
    public static Map<String, String> readAll(Fixture f) throws Exception {
        DailyTrackingUseCase uc = useCase();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("getInvestmentSnapshotSeries", ranges(f, uc::getInvestmentSnapshotSeries));
        out.put("getPortfolioSnapshotSeries", ranges(f, uc::getPortfolioSnapshotSeries));
        for (Map.Entry<String, Long> e : f.ids.entrySet()) {
            out.put("seriesForInvestment(" + e.getKey() + ")", uc.seriesForInvestment(e.getValue().intValue()).toString());
        }
        StringBuilder summary = new StringBuilder();
        StringBuilder entries = new StringBuilder();
        for (LocalDate day = f.first; !day.isAfter(f.last); day = day.plusDays(1)) {
            summary.append(summaryText(uc.summaryFor(day))).append('\n');
            entries.append(entryText(uc.loadEntry(day))).append('\n');
        }
        out.put("summaryFor", summary.toString());
        out.put("loadEntry", entries.toString());
        out.put("livro", ledgerText(uc, f));
        try (Connection c = Database.newConnection()) {
            out.put("fingerprint", DatabaseFingerprint.of(c).toString());
        }
        return out;
    }

    private static String ranges(Fixture f, BiFunction<LocalDate, LocalDate, TreeMap<LocalDate, Long>> reader) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LocalDate[]> e : f.ranges().entrySet()) {
            sb.append(e.getKey()).append('=').append(reader.apply(e.getValue()[0], e.getValue()[1])).append('\n');
        }
        return sb.toString();
    }

    public static String summaryText(DailySummary s) {
        return s.date() + "|total=" + s.totalTodayCents() + "|profit=" + s.totalProfitTodayCents()
                + "|cash=" + s.cashTodayCents() + "|cashDelta=" + s.cashDeltaCents()
                + "|inv=" + new TreeMap<>(s.investmentTodayCents())
                + "|invProfit=" + new TreeMap<>(s.investmentProfitTodayCents());
    }

    public static String entryText(DailyEntry e) {
        TreeMap<Integer, Long> values = new TreeMap<>();
        e.investmentValuesCents().forEach((t, cents) -> values.put(t.id(), cents));
        return e.date() + "|cash=" + e.cashCents() + "|inv=" + values;
    }

    /** The ledger state: position, cash, cost, realised and unrealised gain, flows and the statement of every month. */
    private static String ledgerText(DailyTrackingUseCase uc, Fixture f) {
        StringBuilder sb = new StringBuilder();
        PortfolioState state = uc.portfolio().state();
        sb.append("state=").append(new TreeMap<>(state.positions())).append("|cash=").append(state.cashCents())
                .append("|realized=").append(state.realizedGainCents())
                .append("|externalNetFlow=").append(state.externalNetFlowCents())
                .append("|openingCapital=").append(state.openingCapitalCents()).append('\n');
        PortfolioMetrics m = uc.metrics(f.last);
        sb.append("metrics=").append(m).append('\n');
        sb.append("cashStatus=").append(uc.cashStatus()).append('\n');
        for (Map.Entry<String, Long> e : f.ids.entrySet()) {
            sb.append("status(").append(e.getKey()).append(")=").append(uc.investmentStatus(e.getValue())).append('\n');
        }
        for (YearMonth month = YearMonth.from(f.first); !month.isAfter(YearMonth.from(f.last)); month = month.plusMonths(1)) {
            sb.append("statement(").append(month).append(")=").append(uc.statement(month)).append('\n');
        }
        return sb.toString();
    }

    // =====================================================================
    // Test-database helpers
    // =====================================================================

    /** A byte copy of the (closed) database file of a fixture, for a test that changes the copy. */
    public static Fixture copyOf(Fixture f, Path folder, String name) throws Exception {
        Path copy = folder.resolve(name + ".db");
        Files.copy(f.dbFile, copy);
        Fixture c = new Fixture(f.name, copy);
        c.ids.putAll(f.ids);
        c.first = f.first;
        c.last = f.last;
        return c;
    }

    /**
     * Deletes only the "zero of the ended" from a CLOSED database: snapshot rows with value zero of registrations that
     * have a ledger (they have movements). Legacy zeros (registration without ledger) stay. Returns the rows deleted.
     */
    public static int deleteEndedZeros(Path dbFile) throws Exception {
        try (Connection c = DriverManager.getConnection(urlOf(dbFile)); Statement st = c.createStatement()) {
            return st.executeUpdate("DELETE FROM investment_snapshots WHERE value_cents = 0 AND investment_type_id IN "
                    + "(SELECT DISTINCT investment_type_id FROM portfolio_movement WHERE investment_type_id IS NOT NULL)");
        }
    }

    /** Number of snapshot rows with value zero of registrations with a ledger (what {@link #deleteEndedZeros} removes). */
    public static long countEndedZeros(Path dbFile) throws Exception {
        try (Connection c = DriverManager.getConnection(urlOf(dbFile)); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM investment_snapshots WHERE value_cents = 0 AND "
                     + "investment_type_id IN (SELECT DISTINCT investment_type_id FROM portfolio_movement "
                     + "WHERE investment_type_id IS NOT NULL)")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

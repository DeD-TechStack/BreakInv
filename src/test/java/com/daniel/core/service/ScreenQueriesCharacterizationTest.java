package com.daniel.core.service;

import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-36, unit 1: what the screens read (Painel, Carteira, Diversificação, Extrato), pinned before
 * one portfolio read is shared per refresh.
 *
 * <p>Fixed evaluation date {@link #D} and deterministic prices per ticker (one without a quote, one
 * whose provider fails). Stages K1..K5 are built through the product API on a temp-file SQLite:
 * legacy data, pending recognition, reconciliation, a full ledger (retroactive buy and a refused
 * retroactive sell, reversals of a deposit, a buy, a sell followed by a sell and a negative
 * adjustment, a reversal in another month) and archiving.
 *
 * <p>Two oracles per stage: key values computed by hand from the stage's own movements (written
 * below as constants, never taken from the code), and the canonical text of every query, captured
 * from the code at d8702a3 (candidate-c05-brk30-r5). A refactoring must keep both; neither is
 * adjusted to fit an implementation. The text also lands in {@code target/brk36-caracterizacao/}.
 */
class ScreenQueriesCharacterizationTest {

    static final LocalDate D = LocalDate.of(2026, 10, 22);
    static final YearMonth AUG = YearMonth.of(2026, 8);
    static final YearMonth SEP = YearMonth.of(2026, 9);
    static final YearMonth OCT = YearMonth.of(2026, 10);
    static final YearMonth NOV = YearMonth.of(2026, 11);
    static final LocalDate SERIES_FROM = LocalDate.of(2026, 7, 1);

    /** Deterministic quotes: LEG1 32.00, AAA1 25.00, BBB2 11.50; SEMQ3 has none; ERRO4 fails. */
    static final IStockPriceProvider PRICES = ticker -> switch (ticker) {
        case "LEG1" -> 32.0;
        case "AAA1" -> 25.0;
        case "BBB2" -> 11.5;
        case "ERRO4" -> throw new IllegalStateException("provedor fora do ar (teste)");
        default -> null;
    };

    /** The queries the four screens make in one refresh (same names as the use case). */
    interface Queries {
        List<InvestmentType> listTypes(boolean includeArchived);

        DailyTrackingUseCase.InvestmentStatus investmentStatus(long id);

        DailyTrackingUseCase.CashStatus cashStatus();

        Map<Long, Long> getAllCurrentValues(LocalDate date);

        long getTotalInvestmentValue(LocalDate date);

        long getTotalPatrimony(LocalDate date);

        long getTotalProfit(LocalDate date);

        PortfolioMetrics metrics(LocalDate date);

        List<StatementEntry> statement(YearMonth month);
    }

    /** The use case API as it is called today (each call reads on its own). */
    static Queries api(DailyTrackingUseCase uc) {
        return new Queries() {
            public List<InvestmentType> listTypes(boolean a) { return uc.listTypes(a); }
            public DailyTrackingUseCase.InvestmentStatus investmentStatus(long id) { return uc.investmentStatus(id); }
            public DailyTrackingUseCase.CashStatus cashStatus() { return uc.cashStatus(); }
            public Map<Long, Long> getAllCurrentValues(LocalDate d) { return uc.getAllCurrentValues(d); }
            public long getTotalInvestmentValue(LocalDate d) { return uc.getTotalInvestmentValue(d); }
            public long getTotalPatrimony(LocalDate d) { return uc.getTotalPatrimony(d); }
            public long getTotalProfit(LocalDate d) { return uc.getTotalProfit(d); }
            public PortfolioMetrics metrics(LocalDate d) { return uc.metrics(d); }
            public List<StatementEntry> statement(YearMonth m) { return uc.statement(m); }
        };
    }

    /** The same queries through one {@code PortfolioRead}: one read and at most one replay for all of them. */
    static Queries viaRead(DailyTrackingUseCase.PortfolioRead r) {
        return new Queries() {
            public List<InvestmentType> listTypes(boolean a) { return r.listTypes(a); }
            public DailyTrackingUseCase.InvestmentStatus investmentStatus(long id) { return r.investmentStatus(id); }
            public DailyTrackingUseCase.CashStatus cashStatus() { return r.cashStatus(); }
            public Map<Long, Long> getAllCurrentValues(LocalDate d) { return r.getAllCurrentValues(d); }
            public long getTotalInvestmentValue(LocalDate d) { return r.getTotalInvestmentValue(d); }
            public long getTotalPatrimony(LocalDate d) { return r.getTotalPatrimony(d); }
            public long getTotalProfit(LocalDate d) { return r.getTotalProfit(d); }
            public PortfolioMetrics metrics(LocalDate d) { return r.metrics(d); }
            public List<StatementEntry> statement(YearMonth m) { return r.statement(m); }
        };
    }

    @TempDir
    Path tempDir;
    DailyTrackingUseCase uc;
    SnapshotRepository snapshots;

    @AfterEach
    void tearDown() {
        Database.close();
    }

    DailyTrackingUseCase open(String name) {
        Database.configure("jdbc:sqlite:" + tempDir.resolve(name).toString().replace("\\", "/"));
        Database.open();
        snapshots = new SnapshotRepository();
        uc = new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(), snapshots,
                new TransactionRepository(), PRICES, new DatabaseLedgerAccess());
        return uc;
    }

    // =====================================================================
    // Directed stages K1..K5
    // =====================================================================

    /** Ids of the stage registrations, in creation order. */
    long l1, l2, l3, l4, l5, a, b, c, v1, v2;

    @Test
    void directedStages_K1toK5() throws Exception {
        open("dirigida.db");
        // hand values are checked stage by stage; the canonical texts are compared at the end
        Map<String, String> texts = new java.util.LinkedHashMap<>();
        Map<String, String> readTexts = new java.util.LinkedHashMap<>();
        buildK1();
        checkK1(api(uc));
        texts.put("K1", render(api(uc)));
        readTexts.put("K1", checkedViaRead(this::checkK1));

        buildK2();
        checkK2(api(uc));
        texts.put("K2", render(api(uc)));
        readTexts.put("K2", checkedViaRead(this::checkK2));

        buildK3();
        checkK3(api(uc));
        texts.put("K3", render(api(uc)));
        readTexts.put("K3", checkedViaRead(this::checkK3));

        buildK4();
        checkK4(api(uc));
        texts.put("K4", render(api(uc)));
        readTexts.put("K4", checkedViaRead(this::checkK4));

        buildK5();
        checkK5(api(uc));
        texts.put("K5", render(api(uc)));
        readTexts.put("K5", checkedViaRead(this::checkK5));

        List<String> expected = Arrays.asList(K1_TEXT, K2_TEXT, K3_TEXT, K4_TEXT, K5_TEXT);
        for (Map.Entry<String, String> e : texts.entrySet()) writeActual(e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : readTexts.entrySet()) writeActual(e.getKey() + "-leitura", e.getValue());
        int i = 0;
        for (Map.Entry<String, String> e : texts.entrySet()) assertCanonical(e.getKey(), expected.get(i++), e.getValue());
        i = 0;
        for (Map.Entry<String, String> e : readTexts.entrySet()) {
            assertCanonical(e.getKey() + "-leitura", expected.get(i++), e.getValue());
        }
    }

    /**
     * From unit 3 on: the same hand checks and the same text through ONE {@code read()} shared by every query
     * of the stage, as one page refresh does.
     */
    String checkedViaRead(java.util.function.Consumer<Queries> check) {
        Queries r = viaRead(uc.read());
        check.accept(r);
        return render(r);
    }

    /**
     * K1, legacy only: cash in cash_snapshots (500.00 on 15/07, 550.00 on 25/10, after D) and five
     * registrations without any ledger row; one legacy BUY of 7 and one legacy row without quantity.
     */
    void buildK1() {
        snapshots.upsertCash(LocalDate.of(2026, 7, 15), 50_000L);
        snapshots.upsertCash(LocalDate.of(2026, 10, 25), 55_000L);
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        LocalDate yearAgo = LocalDate.of(2025, 10, 22);
        l1 = repo.createFull("LEGADO ACAO", "ACOES", "ALTA", yearAgo, null, new BigDecimal("300"),
                "ACAO", null, null, "LEG1", new BigDecimal("30"), 10);
        l2 = repo.createFull("LEGADO RF", "RENDA_FIXA", "MEDIA", yearAgo, new BigDecimal("12"), new BigDecimal("1000"),
                "CDB", null, null, null, null, null);
        l3 = repo.createFull("LEGADO SEM COTACAO", "ACOES", "ALTA", yearAgo, null, new BigDecimal("40"),
                "ACAO", null, null, "SEMQ3", new BigDecimal("8"), 5);
        l4 = repo.createFull("LEGADO COTACAO COM ERRO", "ACOES", "ALTA", yearAgo, null, new BigDecimal("100"),
                "ACAO", null, null, "ERRO4", new BigDecimal("50"), 2);
        l5 = repo.createFull("LEGADO ARQUIVADO", "OUTROS", "BAIXA", yearAgo, null, new BigDecimal("70"),
                null, null, null, null, null, null);
        TransactionRepository txs = new TransactionRepository();
        txs.insert(new Transaction(0, LocalDate.of(2026, 9, 10), (int) l1, "BUY", "LEGADO ACAO", "LEG1", 7, 3_000L, 21_000L, null));
        txs.insert(new Transaction(0, LocalDate.of(2026, 10, 5), (int) l1, "BUY", "LEGADO ACAO", "LEG1", null, null, 9_000L,
                "quantidade em branco"));
        uc.portfolio().archive(l5);
        snapshots.upsertInvestment(LocalDate.of(2026, 9, 30), l1, 30_000L, null);
        snapshots.upsertInvestment(LocalDate.of(2026, 9, 30), l5, 7_000L, null);
        snapshots.upsertInvestment(LocalDate.of(2026, 10, 15), l1, 31_000L, null);
    }

    /**
     * Hand values at D. L1: 10 × 32.00 = 320.00 (registered 300.00, gain 20.00). L2: 1 000.00 at
     * 12% a.a. for exactly 12 months = 1 120.00 (gain 120.00). L3: no quote, 5 × 8.00 = 40.00. L4:
     * provider fails, 2 × 50.00 = 100.00. L5 archived: out of the lists and totals. Wealth uses the
     * legacy cash on or before D (500.00); the cash status shows the latest snapshot (550.00).
     */
    void checkK1(Queries q) {
        // lists come ordered by name (InvestmentTypeRepository: ORDER BY name)
        assertEquals(List.of(l1, l4, l2, l3), ids(q.listTypes(false)));
        assertEquals(List.of(l1, l5, l4, l2, l3), ids(q.listTypes(true)));
        assertEquals(new DailyTrackingUseCase.CashStatus(55_000L, false, false, true), q.cashStatus());
        assertEquals(Map.of(l1, 32_000L, l2, 112_000L, l3, 4_000L, l4, 10_000L), q.getAllCurrentValues(D));
        assertEquals(158_000L, q.getTotalInvestmentValue(D));
        assertEquals(208_000L, q.getTotalPatrimony(D));
        assertEquals(14_000L, q.getTotalProfit(D));
        assertMetrics(q.metrics(D), 0, 0, 0, 0, 0, 0, 0, List.of());
        DailyTrackingUseCase.InvestmentStatus s1 = q.investmentStatus(l1);
        assertFalse(s1.opened());
        assertTrue(s1.hasLegacyData());
        assertEquals(2, s1.legacyTransactionCount());
        assertEquals(1, q.statement(SEP).size());
        assertEquals(1, q.statement(OCT).size());
        assertTrue(q.statement(OCT).get(0).legacy());
        assertEquals(List.of(), q.statement(NOV));
    }

    /** K2: cash and L1 recognised from the legacy data, both flagged for review. */
    void buildK2() {
        uc.registerOpening(uc.proposeCashOpening("k2-caixa", LocalDate.of(2026, 8, 1)));
        uc.registerOpening(uc.proposeOpening("k2-leg1", LocalDate.of(2026, 8, 1), l1));
    }

    /**
     * Cash opens at the latest legacy snapshot, 550.00, pending. L1 opens at 10 × 30.00 = 300.00,
     * pending (registered 10 ≠ legacy balance 7, and one legacy row has no quantity). L1 is now valued
     * by the ledger: 320.00, unrealized 20.00. Wealth = 1 580.00 + 550.00.
     */
    void checkK2(Queries q) {
        assertEquals(new DailyTrackingUseCase.CashStatus(55_000L, true, true, false), q.cashStatus());
        DailyTrackingUseCase.InvestmentStatus s1 = q.investmentStatus(l1);
        assertTrue(s1.opened());
        assertTrue(s1.reconciliationPending());
        assertEquals(10L, s1.quantity());
        assertEquals(30_000L, s1.costCents());
        assertEquals(Map.of(l1, 32_000L, l2, 112_000L, l3, 4_000L, l4, 10_000L), q.getAllCurrentValues(D));
        assertEquals(158_000L, q.getTotalInvestmentValue(D));
        assertEquals(213_000L, q.getTotalPatrimony(D));
        assertEquals(14_000L, q.getTotalProfit(D));
        assertMetrics(q.metrics(D), 55_000, 30_000, 32_000, 0, 2_000, 87_000, 0, List.of());
        assertEquals(Arrays.asList(null, l1), uc.pendingReconciliations());
    }

    /** K3: both reviewed with the same figures. */
    void buildK3() {
        uc.reconcile(ReconciliationRequest.cash("k3-caixa", LocalDate.of(2026, 8, 2), 55_000L, "conferido"));
        uc.reconcile(ReconciliationRequest.position("k3-leg1", LocalDate.of(2026, 8, 2), l1, 10L, 30_000L, "conferido"));
    }

    void checkK3(Queries q) {
        assertEquals(new DailyTrackingUseCase.CashStatus(55_000L, true, false, false), q.cashStatus());
        assertFalse(q.investmentStatus(l1).reconciliationPending());
        assertEquals(213_000L, q.getTotalPatrimony(D));
        assertEquals(14_000L, q.getTotalProfit(D));
        assertMetrics(q.metrics(D), 55_000, 30_000, 32_000, 0, 2_000, 87_000, 0, List.of());
        assertEquals(List.of(), uc.pendingReconciliations());
    }

    Movement mv(MovementRequest req) {
        return uc.movement(req);
    }

    static LocalDate day(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    /** K4: a full ledger. Insertion order below; the replay orders by (date, id). */
    void buildK4() throws Exception {
        LocalDate sep1 = day(9, 1);
        a = uc.createInvestment(stock("ACAO AAA", "AAA1"), DailyTrackingUseCase.OpeningSpec.zero("k4-a", sep1, PositionMode.QUANTITY));
        b = uc.createInvestment(stock("ACAO BBB", "BBB2"), DailyTrackingUseCase.OpeningSpec.zero("k4-b", sep1, PositionMode.QUANTITY));
        c = uc.createInvestment(stock("POSICAO SEM TICKER", null), DailyTrackingUseCase.OpeningSpec.zero("k4-c", sep1, PositionMode.QUANTITY));
        v1 = uc.createInvestment(byValue("CDB POR VALOR", new BigDecimal("10")),
                DailyTrackingUseCase.OpeningSpec.zero("k4-v1", sep1, PositionMode.VALUE));
        v2 = uc.createInvestment(byValue("PREVIDENCIA POR VALOR", null),
                DailyTrackingUseCase.OpeningSpec.zero("k4-v2", sep1, PositionMode.VALUE));

        mv(MovementRequest.deposit("k4-dep", day(9, 2), 200_000L, "aporte"));
        mv(MovementRequest.buy("k4-a1", day(9, 10), a, 10, new BigDecimal("20.00"), null));
        mv(MovementRequest.buy("k4-a2", day(9, 15), a, 10, new BigDecimal("26.00"), null));
        mv(MovementRequest.sell("k4-a3", day(9, 20), a, 5, new BigDecimal("30.00"), null));
        // retroactive: inserted after the 20/09 sale, dated 12/09
        mv(MovementRequest.buy("k4-b1", day(9, 12), b, 4, new BigDecimal("11.00"), null));
        int rows = count("portfolio_movement");
        LedgerException refused = assertThrows(LedgerException.class,
                () -> mv(MovementRequest.sell("k4-b0", day(9, 11), b, 1, new BigDecimal("11.00"), "antes da compra")));
        assertEquals("quantity", refused.field());
        assertEquals(rows, count("portfolio_movement"), "a refused retroactive sale changes nothing");
        mv(MovementRequest.sell("k4-b2", day(9, 25), b, 4, new BigDecimal("12.50"), "liquidação"));
        mv(MovementRequest.buy("k4-c1", day(9, 26), c, 3, new BigDecimal("15.00"), null));
        mv(MovementRequest.buyValue("k4-v1a", day(9, 27), v1, 100_000L, null));
        mv(MovementRequest.buyValue("k4-v2a", day(9, 28), v2, 30_000L, null));
        Movement sepDeposit = mv(MovementRequest.deposit("k4-dep2", day(9, 30), 3_000L, null));
        mv(MovementRequest.reversal("k4-dep2-est", day(10, 1), sepDeposit.id(), "aporte em duplicidade"));
        mv(MovementRequest.sellValue("k4-v1b", day(10, 3), v1, 20_000L, "resgate parcial"));
        mv(MovementRequest.adjustCash("k4-inc", day(10, 4), 1_500L, AdjustmentClassification.INCOME, "juros do caixa", null));
        mv(MovementRequest.adjustCash("k4-exp", day(10, 5), -700L, AdjustmentClassification.EXPENSE, "tarifa", null));
        Movement negative = mv(MovementRequest.adjustPosition("k4-v2adj", day(10, 6), v2, null, -1_000L,
                AdjustmentClassification.EXPENSE, "taxa de administração", null));
        Movement octDeposit = mv(MovementRequest.deposit("k4-dep3", day(10, 7), 5_000L, null));
        mv(MovementRequest.reversal("k4-dep3-est", day(10, 8), octDeposit.id(), "aporte não realizado"));
        Movement sell = mv(MovementRequest.sell("k4-a4", day(10, 9), a, 5, new BigDecimal("28.00"), null));
        mv(MovementRequest.sell("k4-a5", day(10, 10), a, 2, new BigDecimal("29.00"), null));
        mv(MovementRequest.reversal("k4-a4-est", day(10, 11), sell.id(), "venda lançada por engano"));
        Movement buyC = mv(MovementRequest.buy("k4-c2", day(10, 12), c, 2, new BigDecimal("16.00"), null));
        mv(MovementRequest.reversal("k4-c2-est", day(10, 13), buyC.id(), "compra duplicada"));
        mv(MovementRequest.reversal("k4-v2adj-est", day(10, 14), negative.id(), "taxa devolvida"));
    }

    /**
     * Hand values at D.
     * <ul>
     *   <li>Cash: 550.00 + 2 000.00 − 200.00 − 44.00 − 260.00 + 150.00 + 50.00 − 45.00 − 1 000.00 − 300.00
     *       (+30.00 −30.00) + 200.00 + 15.00 − 7.00 (+50.00 −50.00) + 140.00 + 58.00 − 140.00 (−32.00 +32.00)
     *       = 1 167.00.</li>
     *   <li>A: buys 10 × 20.00 and 10 × 26.00 (20 for 460.00); sells 5 (cost out 460 × 5/20 = 115.00,
     *       gain 35.00), 5 on 09/10 (cost out 345 × 5/15 = 115.00, reversed later) and 2 (cost out
     *       230 × 2/10 = 46.00, gain 12.00, the same as without the reversed sale). Ends 13 for 299.00;
     *       13 × 25.00 = 325.00.</li>
     *   <li>B: 4 × 11.00, all sold for 50.00 (gain 6.00): closed, valued at cost (0).</li>
     *   <li>C: 3 × 15.00, no ticker: at cost 45.00 (the reversed buy of 2 leaves nothing).</li>
     *   <li>V1: 1 000.00 − 200.00 = 800.00 at 10% a.a. for 12 months = 880.00.</li>
     *   <li>V2: 300.00, no rate: at cost (the −10.00 fee was reversed).</li>
     *   <li>Realized: 35.00 + 6.00 + 15.00 − 7.00 + 12.00 = 61.00. Unrealized: L1 20.00 + A 26.00 + V1 80.00
     *       = 126.00. External flow: 2 000.00 (the two reversed deposits net to zero).</li>
     *   <li>Legacy still unrecognised: L2 1 120.00 (gain 120.00), L3 40.00, L4 100.00.</li>
     * </ul>
     */
    void checkK4(Queries q) {
        assertEquals(List.of(a, b, v1, l1, l4, l2, l3, c, v2), ids(q.listTypes(false)));
        assertEquals(new DailyTrackingUseCase.CashStatus(116_700L, true, false, false), q.cashStatus());
        assertPosition(q.investmentStatus(a), 13, 29_900);
        assertPosition(q.investmentStatus(b), 0, 0);
        assertTrue(q.investmentStatus(b).closed());
        assertPosition(q.investmentStatus(c), 3, 4_500);
        assertPosition(q.investmentStatus(v1), 0, 80_000);
        assertPosition(q.investmentStatus(v2), 0, 30_000);
        assertEquals(Map.of(l1, 32_000L, l2, 112_000L, l3, 4_000L, l4, 10_000L, a, 32_500L, b, 0L, c, 4_500L,
                v1, 88_000L, v2, 30_000L), q.getAllCurrentValues(D));
        assertMetrics(q.metrics(D), 116_700, 174_400, 187_000, 6_100, 12_600, 303_700, 200_000, List.of(b, c, v2));
        assertEquals(313_000L, q.getTotalInvestmentValue(D));
        assertEquals(429_700L, q.getTotalPatrimony(D));
        assertEquals(30_700L, q.getTotalProfit(D));
        // reversal in another month: the September deposit points to the October reversal
        StatementEntry sepDeposit = q.statement(SEP).stream()
                .filter(e -> !e.legacy() && e.amountCents() == 3_000L).findFirst().orElseThrow();
        assertNotNull(sepDeposit.reversedById(), "linked to the reversal of 01/10");
        // ledger and legacy rows on the same date (10/09): ledger first
        List<StatementEntry> tenth = q.statement(SEP).stream().filter(e -> e.date().equals(day(9, 10))).toList();
        assertEquals(List.of(false, true), tenth.stream().map(StatementEntry::legacy).toList());
    }

    /** K5: V2 fully redeemed and archived, B archived then unarchived. */
    void buildK5() {
        mv(MovementRequest.sellValue("k5-v2", day(10, 15), v2, 30_000L, "resgate total"));
        uc.archiveType(v2);
        uc.archiveType(b);
        uc.unarchiveType(b);
    }

    /** Cash + 300.00 = 1 467.00; V2 leaves the active lists but stays in the metrics at 0; wealth unchanged. */
    void checkK5(Queries q) {
        assertEquals(List.of(a, b, v1, l1, l4, l2, l3, c), ids(q.listTypes(false)));
        assertEquals(List.of(a, b, v1, l1, l5, l4, l2, l3, c, v2), ids(q.listTypes(true)));
        assertTrue(q.investmentStatus(v2).archived());
        assertFalse(q.investmentStatus(b).archived());
        assertEquals(new DailyTrackingUseCase.CashStatus(146_700L, true, false, false), q.cashStatus());
        assertEquals(Map.of(l1, 32_000L, l2, 112_000L, l3, 4_000L, l4, 10_000L, a, 32_500L, b, 0L, c, 4_500L,
                v1, 88_000L), q.getAllCurrentValues(D));
        assertMetrics(q.metrics(D), 146_700, 144_400, 157_000, 6_100, 12_600, 303_700, 200_000, List.of(b, c, v2));
        assertEquals(283_000L, q.getTotalInvestmentValue(D));
        assertEquals(429_700L, q.getTotalPatrimony(D));
        assertEquals(30_700L, q.getTotalProfit(D));
    }

    // =====================================================================
    // Without a ledger, and with a ledger that no longer replays
    // =====================================================================

    /**
     * No-ledger wiring (unit-test stubs): legacy values only. 3 × 25.00 = 75.00 (registered 60.00);
     * 1 000.00 at 12% for 12 months = 1 120.00; cash on or before D 70.00. Cash and investment status
     * need the ledger and keep failing the same way.
     */
    @Test
    void withoutLedger_legacyBranches() throws Exception {
        LocalDate yearAgo = LocalDate.of(2025, 10, 22);
        List<InvestmentType> regs = List.of(
                new InvestmentType(1, "SEM LIVRO ACAO", "ACOES", "ALTA", yearAgo, null, new BigDecimal("60"),
                        "ACAO", null, null, "AAA1", new BigDecimal("20"), 3, null),
                new InvestmentType(2, "SEM LIVRO RF", "RENDA_FIXA", "MEDIA", yearAgo, new BigDecimal("12"),
                        new BigDecimal("1000"), "CDB", null, null, null, null, null, null));
        DailyTrackingUseCase legacy = withoutLedger(regs, 7_000L);
        Queries q = api(legacy);
        assertEquals(Map.of(1L, 7_500L, 2L, 112_000L), q.getAllCurrentValues(D));
        assertEquals(119_500L, q.getTotalInvestmentValue(D));
        assertEquals(126_500L, q.getTotalPatrimony(D));
        assertEquals(13_500L, q.getTotalProfit(D));
        assertNull(q.metrics(D));
        assertThrows(IllegalStateException.class, q::cashStatus);
        assertCanonical("SEM-LIVRO", WITHOUT_LEDGER_TEXT, render(q, List.of(1L, 2L), null));

        Queries r = viaRead(legacy.read());
        assertEquals(Map.of(1L, 7_500L, 2L, 112_000L), r.getAllCurrentValues(D));
        assertEquals(126_500L, r.getTotalPatrimony(D));
        assertEquals(13_500L, r.getTotalProfit(D));
        assertNull(r.metrics(D));
        assertCanonical("SEM-LIVRO-leitura", WITHOUT_LEDGER_TEXT, render(r, List.of(1L, 2L), null));
    }

    /**
     * A ledger that no longer replays (a deposit lowered by SQL below a later withdrawal): every query
     * that needs the state fails with the replay's message; the statement still lists the rows.
     */
    @Test
    void invalidLedger_failsTheSameWay_statementStillLists() throws Exception {
        open("invalido.db");
        long x = uc.createInvestment(stock("ATIVO X", "AAA1"),
                DailyTrackingUseCase.OpeningSpec.zero("inv-x", day(10, 1), PositionMode.QUANTITY));
        mv(MovementRequest.deposit("inv-d", day(10, 1), 1_000L, null));
        mv(MovementRequest.withdrawal("inv-w", day(10, 2), 500L, null));
        try (Connection conn = Database.newConnection(); Statement st = conn.createStatement()) {
            assertEquals(1, st.executeUpdate("UPDATE portfolio_movement SET amount_cents = 100 WHERE intent_key = 'inv-d'"));
        }
        Queries q = api(uc);
        assertThrows(LedgerException.class, () -> q.listTypes(false));
        assertThrows(LedgerException.class, q::cashStatus);
        assertThrows(LedgerException.class, () -> q.getTotalPatrimony(D));
        assertEquals(3, q.statement(OCT).size(), "x opening, deposit, withdrawal; the 1900 cash marker is elsewhere");
        assertCanonical("LIVRO-INVALIDO", INVALID_LEDGER_TEXT, render(q, List.of(x), uc));

        DailyTrackingUseCase.PortfolioRead read = uc.read();
        assertThrows(LedgerException.class, read::cashStatus);
        assertEquals(3, read.statement(OCT).size());
        assertCanonical("LIVRO-INVALIDO-leitura", INVALID_LEDGER_TEXT, render(viaRead(read), List.of(x), uc));
    }

    // =====================================================================
    // Canonical text
    // =====================================================================

    String render(Queries q) {
        List<Long> ids = new InvestmentTypeRepository().listAll().stream().map(t -> (long) t.id()).sorted().toList();
        return render(q, ids, uc);
    }

    /** Every screen query, one item per line; a failure is written as its class and message. */
    static String render(Queries q, List<Long> ids, DailyTrackingUseCase daily) {
        StringBuilder out = new StringBuilder();
        section(out, "listTypes(false)", () -> types(q.listTypes(false)));
        section(out, "listTypes(true)", () -> types(q.listTypes(true)));
        for (long id : ids) section(out, "investmentStatus(" + id + ")", () -> List.of(status(q.investmentStatus(id))));
        section(out, "cashStatus", () -> List.of(String.valueOf(q.cashStatus())));
        section(out, "getAllCurrentValues", () -> new TreeMap<>(q.getAllCurrentValues(D)).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList());
        section(out, "getTotalInvestmentValue", () -> List.of(String.valueOf(q.getTotalInvestmentValue(D))));
        section(out, "getTotalPatrimony", () -> List.of(String.valueOf(q.getTotalPatrimony(D))));
        section(out, "getTotalProfit", () -> List.of(String.valueOf(q.getTotalProfit(D))));
        section(out, "metrics", () -> List.of(metrics(q.metrics(D))));
        for (YearMonth m : List.of(AUG, SEP, OCT, NOV)) {
            section(out, "statement(" + m + ")", () -> q.statement(m).stream().map(ScreenQueriesCharacterizationTest::entry).toList());
        }
        if (daily != null && daily.hasLedger()) {
            section(out, "pendingReconciliations", () -> List.of(String.valueOf(daily.pendingReconciliations())));
            section(out, "investmentSeries", () -> series(daily.getInvestmentSnapshotSeries(SERIES_FROM, D)));
            section(out, "portfolioSeries", () -> series(daily.getPortfolioSnapshotSeries(SERIES_FROM, D)));
        }
        return out.toString();
    }

    static void section(StringBuilder out, String name, Callable<List<String>> body) {
        out.append("## ").append(name).append('\n');
        try {
            for (String line : body.call()) out.append(line).append('\n');
        } catch (Exception e) {
            out.append("FALHA ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
    }

    static List<String> types(List<InvestmentType> list) {
        return list.stream().map(t -> t.id() + "|" + t.name() + "|" + t.category() + "|" + t.liquidity() + "|"
                + t.investmentDate() + "|prof=" + plain(t.profitability()) + "|inv=" + plain(t.investedValue()) + "|"
                + t.typeOfInvestment() + "|" + t.ticker() + "|pp=" + plain(t.purchasePrice()) + "|q=" + t.quantity()
                + "|cur=" + plain(t.currentPrice())).toList();
    }

    static String status(DailyTrackingUseCase.InvestmentStatus s) {
        return s.id() + "|" + s.name() + "|opened=" + s.opened() + "|" + s.mode() + "|q=" + s.quantity() + "|cost="
                + s.costCents() + "|pending=" + s.reconciliationPending() + "|archived=" + s.archived() + "|closed="
                + s.closed() + "|note=" + s.note() + "|legacy=" + s.hasLegacyData() + "|legacyTx="
                + s.legacyTransactionCount() + "|legacyQ=" + s.legacyQuantity() + "|legacyInv="
                + plain(s.legacyInvestedValue());
    }

    static String metrics(PortfolioMetrics m) {
        if (m == null) return "null";
        return "cash=" + m.cashCents() + "|cost=" + m.costCents() + "|value=" + m.positionValueCents() + "|realized="
                + m.realizedGainCents() + "|unrealized=" + m.unrealizedGainCents() + "|wealth=" + m.wealthCents()
                + "|external=" + m.externalNetFlowCents() + "|atCost=" + m.valuedAtCost();
    }

    static String entry(StatementEntry e) {
        return e.date() + "|" + e.kind() + "|" + e.legacyType() + "|mv=" + e.movementId() + "|tx=" + e.legacyTransactionId()
                + "|inv=" + e.investmentTypeId() + "|" + e.investmentName() + "|" + e.positionMode() + "|q=" + e.quantity()
                + "|price=" + plain(e.unitPrice()) + "|amount=" + e.amountCents() + "|" + e.classification() + "|reason="
                + e.reason() + "|note=" + e.note() + "|reverses=" + e.reversesId() + "|reversedBy=" + e.reversedById()
                + "|flag=" + e.reconciliationFlag() + "|legacy=" + e.legacy();
    }

    static List<String> series(TreeMap<LocalDate, Long> s) {
        return s.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
    }

    static String plain(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    /**
     * Compares with the expected text line by line; the actual text is always written to
     * {@code target/brk36-caracterizacao/<name>.txt} (the build copy, never the worktree).
     */
    static void assertCanonical(String name, String expected, String actual) throws IOException {
        writeActual(name, actual);
        assertNotNull(expected, name + ": expected text not recorded yet (see target/brk36-caracterizacao)");
        String[] want = expected.split("\n", -1);
        String[] got = actual.split("\n", -1);
        for (int i = 0; i < Math.min(want.length, got.length); i++) {
            assertEquals(want[i], got[i], name + ", line " + (i + 1));
        }
        assertEquals(want.length, got.length, name + ": number of lines");
    }

    static void writeActual(String name, String actual) throws IOException {
        Path dir = Path.of("target", "brk36-caracterizacao");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".txt"), actual, StandardCharsets.UTF_8);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    static List<Long> ids(List<InvestmentType> types) {
        return types.stream().map(t -> (long) t.id()).toList();
    }

    static void assertPosition(DailyTrackingUseCase.InvestmentStatus s, long quantity, long cost) {
        assertTrue(s.opened(), s.name());
        assertEquals(quantity, s.quantity(), s.name() + " quantity");
        assertEquals(cost, s.costCents(), s.name() + " cost");
    }

    static void assertMetrics(PortfolioMetrics m, long cash, long cost, long value, long realized, long unrealized,
                              long wealth, long external, List<Long> atCost) {
        assertEquals(List.of(cash, cost, value, realized, unrealized, wealth, external),
                List.of(m.cashCents(), m.costCents(), m.positionValueCents(), m.realizedGainCents(),
                        m.unrealizedGainCents(), m.wealthCents(), m.externalNetFlowCents()),
                "cash, cost, value, realized, unrealized, wealth, external");
        assertEquals(atCost, m.valuedAtCost());
    }

    static DailyTrackingUseCase.NewInvestment stock(String name, String ticker) {
        return new DailyTrackingUseCase.NewInvestment(name, "ACOES", "ALTA", day(9, 1), null, null, "ACAO",
                null, null, ticker, null, null, null);
    }

    static DailyTrackingUseCase.NewInvestment byValue(String name, BigDecimal rate) {
        return new DailyTrackingUseCase.NewInvestment(name, "RENDA_FIXA", "MEDIA", LocalDate.of(2025, 10, 22), rate,
                null, "CDB", null, null, null, null, null, null);
    }

    static int count(String table) throws Exception {
        try (Connection conn = Database.newConnection(); Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static DailyTrackingUseCase withoutLedger(List<InvestmentType> registrations, long cashOnOrBefore) {
        IInvestmentTypeRepository types = new IInvestmentTypeRepository() {
            @Override public List<InvestmentType> listAll() { return registrations; }
            @Override public void save(String name) {}
            @Override public void rename(int id, String newName) {}
            @Override public void delete(int id) {}
        };
        IFlowRepository flows = new IFlowRepository() {
            @Override public List<Flow> listForDate(LocalDate date) { return List.of(); }
            @Override public void delete(long id) {}
            @Override public long create(Flow flow) { return 0; }
        };
        ISnapshotRepository snaps = new ISnapshotRepository() {
            @Override public long getCash(LocalDate date) { return 0; }
            @Override public long getCashOnOrBefore(LocalDate date) { return cashOnOrBefore; }
            @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) { return Map.of(); }
            @Override public Map<String, Long> seriesForInvestiments(long id) { return Map.of(); }
        };
        ITransactionRepository txs = new ITransactionRepository() {
            @Override public long insert(Transaction t) { return 0; }
            @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) {
                return start.equals(OCT.atDay(1))
                        ? List.of(new Transaction(9, day(10, 5), 1, "BUY", "SEM LIVRO ACAO", "AAA1", 3, 2_000L, 6_000L, null))
                        : List.of();
            }
        };
        return new DailyTrackingUseCase(flows, types, snaps, txs, PRICES);
    }

    // =====================================================================
    // Expected canonical texts (captured at d8702a3; see the class comment)
    // =====================================================================

    static final String K1_TEXT = """
            ## listTypes(false)
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.0|ACAO|LEG1|pp=30.0|q=10|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            ## listTypes(true)
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.0|ACAO|LEG1|pp=30.0|q=10|cur=-
            5|LEGADO ARQUIVADO|OUTROS|BAIXA|2025-10-22|prof=-|inv=70.0|null|null|pp=-|q=null|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            ## investmentStatus(1)
            1|LEGADO ACAO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=2|legacyQ=10|legacyInv=300.0
            ## investmentStatus(2)
            2|LEGADO RF|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=1000.0
            ## investmentStatus(3)
            3|LEGADO SEM COTACAO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=5|legacyInv=40.0
            ## investmentStatus(4)
            4|LEGADO COTACAO COM ERRO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=2|legacyInv=100.0
            ## investmentStatus(5)
            5|LEGADO ARQUIVADO|opened=false|null|q=0|cost=0|pending=false|archived=true|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=70.0
            ## cashStatus
            CashStatus[cashCents=55000, opened=false, reconciliationPending=false, legacySnapshotsPresent=true]
            ## getAllCurrentValues
            1=32000
            2=112000
            3=4000
            4=10000
            ## getTotalInvestmentValue
            158000
            ## getTotalPatrimony
            208000
            ## getTotalProfit
            14000
            ## metrics
            cash=0|cost=0|value=0|realized=0|unrealized=0|wealth=0|external=0|atCost=[]
            ## statement(2026-08)
            ## statement(2026-09)
            2026-09-10|null|BUY|mv=null|tx=1|inv=1|LEGADO ACAO|null|q=7|price=30.00|amount=21000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-10)
            2026-10-05|null|BUY|mv=null|tx=2|inv=1|LEGADO ACAO|null|q=null|price=-|amount=9000|null|reason=null|note=quantidade em branco|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-11)
            ## pendingReconciliations
            []
            ## investmentSeries
            2026-09-30=37000
            2026-10-15=31000
            ## portfolioSeries
            2026-09-30=87000
            2026-10-15=81000
            """;
    static final String K2_TEXT = """
            ## listTypes(false)
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            ## listTypes(true)
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            5|LEGADO ARQUIVADO|OUTROS|BAIXA|2025-10-22|prof=-|inv=70.0|null|null|pp=-|q=null|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            ## investmentStatus(1)
            1|LEGADO ACAO|opened=true|QUANTITY|q=10|cost=30000|pending=true|archived=false|closed=false|note=null|legacy=true|legacyTx=2|legacyQ=10|legacyInv=300.0
            ## investmentStatus(2)
            2|LEGADO RF|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=1000.0
            ## investmentStatus(3)
            3|LEGADO SEM COTACAO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=5|legacyInv=40.0
            ## investmentStatus(4)
            4|LEGADO COTACAO COM ERRO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=2|legacyInv=100.0
            ## investmentStatus(5)
            5|LEGADO ARQUIVADO|opened=false|null|q=0|cost=0|pending=false|archived=true|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=70.0
            ## cashStatus
            CashStatus[cashCents=55000, opened=true, reconciliationPending=true, legacySnapshotsPresent=false]
            ## getAllCurrentValues
            1=32000
            2=112000
            3=4000
            4=10000
            ## getTotalInvestmentValue
            158000
            ## getTotalPatrimony
            213000
            ## getTotalProfit
            14000
            ## metrics
            cash=55000|cost=30000|value=32000|realized=0|unrealized=2000|wealth=87000|external=0|atCost=[]
            ## statement(2026-08)
            2026-08-01|OPENING|null|mv=1|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=null|note=caixa a partir do último snapshot manual|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-01|OPENING|null|mv=2|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=null|note=transações legadas com quantidade em branco (1): saldo histórico incompleto, confirmar quantidade cadastrada 10|reverses=null|reversedBy=null|flag=true|legacy=false
            ## statement(2026-09)
            2026-09-10|null|BUY|mv=null|tx=1|inv=1|LEGADO ACAO|null|q=7|price=30.00|amount=21000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-10)
            2026-10-05|null|BUY|mv=null|tx=2|inv=1|LEGADO ACAO|null|q=null|price=-|amount=9000|null|reason=null|note=quantidade em branco|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-11)
            ## pendingReconciliations
            [null, 1]
            ## investmentSeries
            2026-09-30=37000
            2026-10-15=31000
            ## portfolioSeries
            2026-09-30=87000
            2026-10-15=81000
            """;
    static final String K3_TEXT = """
            ## listTypes(false)
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            ## listTypes(true)
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            5|LEGADO ARQUIVADO|OUTROS|BAIXA|2025-10-22|prof=-|inv=70.0|null|null|pp=-|q=null|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            ## investmentStatus(1)
            1|LEGADO ACAO|opened=true|QUANTITY|q=10|cost=30000|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=2|legacyQ=10|legacyInv=300.0
            ## investmentStatus(2)
            2|LEGADO RF|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=1000.0
            ## investmentStatus(3)
            3|LEGADO SEM COTACAO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=5|legacyInv=40.0
            ## investmentStatus(4)
            4|LEGADO COTACAO COM ERRO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=2|legacyInv=100.0
            ## investmentStatus(5)
            5|LEGADO ARQUIVADO|opened=false|null|q=0|cost=0|pending=false|archived=true|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=70.0
            ## cashStatus
            CashStatus[cashCents=55000, opened=true, reconciliationPending=false, legacySnapshotsPresent=false]
            ## getAllCurrentValues
            1=32000
            2=112000
            3=4000
            4=10000
            ## getTotalInvestmentValue
            158000
            ## getTotalPatrimony
            213000
            ## getTotalProfit
            14000
            ## metrics
            cash=55000|cost=30000|value=32000|realized=0|unrealized=2000|wealth=87000|external=0|atCost=[]
            ## statement(2026-08)
            2026-08-01|OPENING|null|mv=1|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=null|note=caixa a partir do último snapshot manual|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-01|OPENING|null|mv=2|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=null|note=transações legadas com quantidade em branco (1): saldo histórico incompleto, confirmar quantidade cadastrada 10|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-02|RECONCILIATION|null|mv=3|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=conferido|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-08-02|RECONCILIATION|null|mv=4|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=conferido|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            ## statement(2026-09)
            2026-09-10|null|BUY|mv=null|tx=1|inv=1|LEGADO ACAO|null|q=7|price=30.00|amount=21000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-10)
            2026-10-05|null|BUY|mv=null|tx=2|inv=1|LEGADO ACAO|null|q=null|price=-|amount=9000|null|reason=null|note=quantidade em branco|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-11)
            ## pendingReconciliations
            []
            ## investmentSeries
            2026-09-30=37000
            2026-10-15=31000
            ## portfolioSeries
            2026-09-30=87000
            2026-10-15=81000
            """;
    static final String K4_TEXT = """
            ## listTypes(false)
            6|ACAO AAA|ACOES|ALTA|2026-09-01|prof=-|inv=299.00|ACAO|AAA1|pp=23.0000|q=13|cur=-
            7|ACAO BBB|ACOES|ALTA|2026-09-01|prof=-|inv=0.00|ACAO|BBB2|pp=-|q=0|cur=-
            9|CDB POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=10.0|inv=800.00|CDB|null|pp=-|q=null|cur=-
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            8|POSICAO SEM TICKER|ACOES|ALTA|2026-09-01|prof=-|inv=45.00|ACAO|null|pp=15.0000|q=3|cur=-
            10|PREVIDENCIA POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=-|inv=300.00|CDB|null|pp=-|q=null|cur=-
            ## listTypes(true)
            6|ACAO AAA|ACOES|ALTA|2026-09-01|prof=-|inv=299.00|ACAO|AAA1|pp=23.0000|q=13|cur=-
            7|ACAO BBB|ACOES|ALTA|2026-09-01|prof=-|inv=0.00|ACAO|BBB2|pp=-|q=0|cur=-
            9|CDB POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=10.0|inv=800.00|CDB|null|pp=-|q=null|cur=-
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            5|LEGADO ARQUIVADO|OUTROS|BAIXA|2025-10-22|prof=-|inv=70.0|null|null|pp=-|q=null|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            8|POSICAO SEM TICKER|ACOES|ALTA|2026-09-01|prof=-|inv=45.00|ACAO|null|pp=15.0000|q=3|cur=-
            10|PREVIDENCIA POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=-|inv=300.00|CDB|null|pp=-|q=null|cur=-
            ## investmentStatus(1)
            1|LEGADO ACAO|opened=true|QUANTITY|q=10|cost=30000|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=2|legacyQ=10|legacyInv=300.0
            ## investmentStatus(2)
            2|LEGADO RF|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=1000.0
            ## investmentStatus(3)
            3|LEGADO SEM COTACAO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=5|legacyInv=40.0
            ## investmentStatus(4)
            4|LEGADO COTACAO COM ERRO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=2|legacyInv=100.0
            ## investmentStatus(5)
            5|LEGADO ARQUIVADO|opened=false|null|q=0|cost=0|pending=false|archived=true|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=70.0
            ## investmentStatus(6)
            6|ACAO AAA|opened=true|QUANTITY|q=13|cost=29900|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(7)
            7|ACAO BBB|opened=true|QUANTITY|q=0|cost=0|pending=false|archived=false|closed=true|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(8)
            8|POSICAO SEM TICKER|opened=true|QUANTITY|q=3|cost=4500|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(9)
            9|CDB POR VALOR|opened=true|VALUE|q=0|cost=80000|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(10)
            10|PREVIDENCIA POR VALOR|opened=true|VALUE|q=0|cost=30000|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## cashStatus
            CashStatus[cashCents=116700, opened=true, reconciliationPending=false, legacySnapshotsPresent=false]
            ## getAllCurrentValues
            1=32000
            2=112000
            3=4000
            4=10000
            6=32500
            7=0
            8=4500
            9=88000
            10=30000
            ## getTotalInvestmentValue
            313000
            ## getTotalPatrimony
            429700
            ## getTotalProfit
            30700
            ## metrics
            cash=116700|cost=174400|value=187000|realized=6100|unrealized=12600|wealth=303700|external=200000|atCost=[7, 8, 10]
            ## statement(2026-08)
            2026-08-01|OPENING|null|mv=1|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=null|note=caixa a partir do último snapshot manual|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-01|OPENING|null|mv=2|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=null|note=transações legadas com quantidade em branco (1): saldo histórico incompleto, confirmar quantidade cadastrada 10|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-02|RECONCILIATION|null|mv=3|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=conferido|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-08-02|RECONCILIATION|null|mv=4|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=conferido|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            ## statement(2026-09)
            2026-09-01|OPENING|null|mv=5|tx=null|inv=6|ACAO AAA|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=6|tx=null|inv=7|ACAO BBB|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=7|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=8|tx=null|inv=9|CDB POR VALOR|VALUE|q=null|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=9|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-02|DEPOSIT|null|mv=10|tx=null|inv=null|null|null|q=null|price=-|amount=200000|null|reason=null|note=aporte|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-10|BUY|null|mv=11|tx=null|inv=6|ACAO AAA|QUANTITY|q=10|price=20.00|amount=20000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-10|null|BUY|mv=null|tx=1|inv=1|LEGADO ACAO|null|q=7|price=30.00|amount=21000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=true
            2026-09-12|BUY|null|mv=14|tx=null|inv=7|ACAO BBB|QUANTITY|q=4|price=11.00|amount=4400|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-15|BUY|null|mv=12|tx=null|inv=6|ACAO AAA|QUANTITY|q=10|price=26.00|amount=26000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-20|SELL|null|mv=13|tx=null|inv=6|ACAO AAA|QUANTITY|q=5|price=30.00|amount=15000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-25|SELL|null|mv=15|tx=null|inv=7|ACAO BBB|QUANTITY|q=4|price=12.50|amount=5000|null|reason=null|note=liquidação|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-26|BUY|null|mv=16|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=3|price=15.00|amount=4500|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-27|BUY|null|mv=17|tx=null|inv=9|CDB POR VALOR|VALUE|q=null|price=-|amount=100000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-28|BUY|null|mv=18|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=30000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-30|DEPOSIT|null|mv=19|tx=null|inv=null|null|null|q=null|price=-|amount=3000|null|reason=null|note=null|reverses=null|reversedBy=20|flag=false|legacy=false
            ## statement(2026-10)
            2026-10-01|REVERSAL|null|mv=20|tx=null|inv=null|null|null|q=null|price=-|amount=3000|null|reason=aporte em duplicidade|note=null|reverses=19|reversedBy=null|flag=false|legacy=false
            2026-10-03|SELL|null|mv=21|tx=null|inv=9|CDB POR VALOR|VALUE|q=null|price=-|amount=20000|null|reason=null|note=resgate parcial|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-04|ADJUSTMENT|null|mv=22|tx=null|inv=null|null|null|q=null|price=-|amount=1500|INCOME|reason=juros do caixa|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-05|ADJUSTMENT|null|mv=23|tx=null|inv=null|null|null|q=null|price=-|amount=-700|EXPENSE|reason=tarifa|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-05|null|BUY|mv=null|tx=2|inv=1|LEGADO ACAO|null|q=null|price=-|amount=9000|null|reason=null|note=quantidade em branco|reverses=null|reversedBy=null|flag=false|legacy=true
            2026-10-06|ADJUSTMENT|null|mv=24|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=-1000|EXPENSE|reason=taxa de administração|note=null|reverses=null|reversedBy=32|flag=false|legacy=false
            2026-10-07|DEPOSIT|null|mv=25|tx=null|inv=null|null|null|q=null|price=-|amount=5000|null|reason=null|note=null|reverses=null|reversedBy=26|flag=false|legacy=false
            2026-10-08|REVERSAL|null|mv=26|tx=null|inv=null|null|null|q=null|price=-|amount=5000|null|reason=aporte não realizado|note=null|reverses=25|reversedBy=null|flag=false|legacy=false
            2026-10-09|SELL|null|mv=27|tx=null|inv=6|ACAO AAA|QUANTITY|q=5|price=28.00|amount=14000|null|reason=null|note=null|reverses=null|reversedBy=29|flag=false|legacy=false
            2026-10-10|SELL|null|mv=28|tx=null|inv=6|ACAO AAA|QUANTITY|q=2|price=29.00|amount=5800|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-11|REVERSAL|null|mv=29|tx=null|inv=6|ACAO AAA|QUANTITY|q=null|price=-|amount=14000|null|reason=venda lançada por engano|note=null|reverses=27|reversedBy=null|flag=false|legacy=false
            2026-10-12|BUY|null|mv=30|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=2|price=16.00|amount=3200|null|reason=null|note=null|reverses=null|reversedBy=31|flag=false|legacy=false
            2026-10-13|REVERSAL|null|mv=31|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=null|price=-|amount=3200|null|reason=compra duplicada|note=null|reverses=30|reversedBy=null|flag=false|legacy=false
            2026-10-14|REVERSAL|null|mv=32|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=-1000|null|reason=taxa devolvida|note=null|reverses=24|reversedBy=null|flag=false|legacy=false
            ## statement(2026-11)
            ## pendingReconciliations
            []
            ## investmentSeries
            2026-09-30=37000
            2026-10-15=31000
            ## portfolioSeries
            2026-09-30=87000
            2026-10-15=81000
            """;
    static final String K5_TEXT = """
            ## listTypes(false)
            6|ACAO AAA|ACOES|ALTA|2026-09-01|prof=-|inv=299.00|ACAO|AAA1|pp=23.0000|q=13|cur=-
            7|ACAO BBB|ACOES|ALTA|2026-09-01|prof=-|inv=0.00|ACAO|BBB2|pp=-|q=0|cur=-
            9|CDB POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=10.0|inv=800.00|CDB|null|pp=-|q=null|cur=-
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            8|POSICAO SEM TICKER|ACOES|ALTA|2026-09-01|prof=-|inv=45.00|ACAO|null|pp=15.0000|q=3|cur=-
            ## listTypes(true)
            6|ACAO AAA|ACOES|ALTA|2026-09-01|prof=-|inv=299.00|ACAO|AAA1|pp=23.0000|q=13|cur=-
            7|ACAO BBB|ACOES|ALTA|2026-09-01|prof=-|inv=0.00|ACAO|BBB2|pp=-|q=0|cur=-
            9|CDB POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=10.0|inv=800.00|CDB|null|pp=-|q=null|cur=-
            1|LEGADO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=300.00|ACAO|LEG1|pp=30.0000|q=10|cur=-
            5|LEGADO ARQUIVADO|OUTROS|BAIXA|2025-10-22|prof=-|inv=70.0|null|null|pp=-|q=null|cur=-
            4|LEGADO COTACAO COM ERRO|ACOES|ALTA|2025-10-22|prof=-|inv=100.0|ACAO|ERRO4|pp=50.0|q=2|cur=-
            2|LEGADO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12.0|inv=1000.0|CDB|null|pp=-|q=null|cur=-
            3|LEGADO SEM COTACAO|ACOES|ALTA|2025-10-22|prof=-|inv=40.0|ACAO|SEMQ3|pp=8.0|q=5|cur=-
            8|POSICAO SEM TICKER|ACOES|ALTA|2026-09-01|prof=-|inv=45.00|ACAO|null|pp=15.0000|q=3|cur=-
            10|PREVIDENCIA POR VALOR|RENDA_FIXA|MEDIA|2025-10-22|prof=-|inv=0.00|CDB|null|pp=-|q=null|cur=-
            ## investmentStatus(1)
            1|LEGADO ACAO|opened=true|QUANTITY|q=10|cost=30000|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=2|legacyQ=10|legacyInv=300.0
            ## investmentStatus(2)
            2|LEGADO RF|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=1000.0
            ## investmentStatus(3)
            3|LEGADO SEM COTACAO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=5|legacyInv=40.0
            ## investmentStatus(4)
            4|LEGADO COTACAO COM ERRO|opened=false|null|q=0|cost=0|pending=false|archived=false|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=2|legacyInv=100.0
            ## investmentStatus(5)
            5|LEGADO ARQUIVADO|opened=false|null|q=0|cost=0|pending=false|archived=true|closed=false|note=null|legacy=true|legacyTx=0|legacyQ=null|legacyInv=70.0
            ## investmentStatus(6)
            6|ACAO AAA|opened=true|QUANTITY|q=13|cost=29900|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(7)
            7|ACAO BBB|opened=true|QUANTITY|q=0|cost=0|pending=false|archived=false|closed=true|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(8)
            8|POSICAO SEM TICKER|opened=true|QUANTITY|q=3|cost=4500|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(9)
            9|CDB POR VALOR|opened=true|VALUE|q=0|cost=80000|pending=false|archived=false|closed=false|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## investmentStatus(10)
            10|PREVIDENCIA POR VALOR|opened=true|VALUE|q=0|cost=0|pending=false|archived=true|closed=true|note=null|legacy=false|legacyTx=0|legacyQ=null|legacyInv=-
            ## cashStatus
            CashStatus[cashCents=146700, opened=true, reconciliationPending=false, legacySnapshotsPresent=false]
            ## getAllCurrentValues
            1=32000
            2=112000
            3=4000
            4=10000
            6=32500
            7=0
            8=4500
            9=88000
            ## getTotalInvestmentValue
            283000
            ## getTotalPatrimony
            429700
            ## getTotalProfit
            30700
            ## metrics
            cash=146700|cost=144400|value=157000|realized=6100|unrealized=12600|wealth=303700|external=200000|atCost=[7, 8, 10]
            ## statement(2026-08)
            2026-08-01|OPENING|null|mv=1|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=null|note=caixa a partir do último snapshot manual|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-01|OPENING|null|mv=2|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=null|note=transações legadas com quantidade em branco (1): saldo histórico incompleto, confirmar quantidade cadastrada 10|reverses=null|reversedBy=null|flag=true|legacy=false
            2026-08-02|RECONCILIATION|null|mv=3|tx=null|inv=null|null|null|q=null|price=-|amount=55000|null|reason=conferido|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-08-02|RECONCILIATION|null|mv=4|tx=null|inv=1|LEGADO ACAO|QUANTITY|q=10|price=-|amount=30000|null|reason=conferido|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            ## statement(2026-09)
            2026-09-01|OPENING|null|mv=5|tx=null|inv=6|ACAO AAA|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=6|tx=null|inv=7|ACAO BBB|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=7|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=8|tx=null|inv=9|CDB POR VALOR|VALUE|q=null|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-01|OPENING|null|mv=9|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-02|DEPOSIT|null|mv=10|tx=null|inv=null|null|null|q=null|price=-|amount=200000|null|reason=null|note=aporte|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-10|BUY|null|mv=11|tx=null|inv=6|ACAO AAA|QUANTITY|q=10|price=20.00|amount=20000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-10|null|BUY|mv=null|tx=1|inv=1|LEGADO ACAO|null|q=7|price=30.00|amount=21000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=true
            2026-09-12|BUY|null|mv=14|tx=null|inv=7|ACAO BBB|QUANTITY|q=4|price=11.00|amount=4400|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-15|BUY|null|mv=12|tx=null|inv=6|ACAO AAA|QUANTITY|q=10|price=26.00|amount=26000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-20|SELL|null|mv=13|tx=null|inv=6|ACAO AAA|QUANTITY|q=5|price=30.00|amount=15000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-25|SELL|null|mv=15|tx=null|inv=7|ACAO BBB|QUANTITY|q=4|price=12.50|amount=5000|null|reason=null|note=liquidação|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-26|BUY|null|mv=16|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=3|price=15.00|amount=4500|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-27|BUY|null|mv=17|tx=null|inv=9|CDB POR VALOR|VALUE|q=null|price=-|amount=100000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-28|BUY|null|mv=18|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=30000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-09-30|DEPOSIT|null|mv=19|tx=null|inv=null|null|null|q=null|price=-|amount=3000|null|reason=null|note=null|reverses=null|reversedBy=20|flag=false|legacy=false
            ## statement(2026-10)
            2026-10-01|REVERSAL|null|mv=20|tx=null|inv=null|null|null|q=null|price=-|amount=3000|null|reason=aporte em duplicidade|note=null|reverses=19|reversedBy=null|flag=false|legacy=false
            2026-10-03|SELL|null|mv=21|tx=null|inv=9|CDB POR VALOR|VALUE|q=null|price=-|amount=20000|null|reason=null|note=resgate parcial|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-04|ADJUSTMENT|null|mv=22|tx=null|inv=null|null|null|q=null|price=-|amount=1500|INCOME|reason=juros do caixa|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-05|ADJUSTMENT|null|mv=23|tx=null|inv=null|null|null|q=null|price=-|amount=-700|EXPENSE|reason=tarifa|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-05|null|BUY|mv=null|tx=2|inv=1|LEGADO ACAO|null|q=null|price=-|amount=9000|null|reason=null|note=quantidade em branco|reverses=null|reversedBy=null|flag=false|legacy=true
            2026-10-06|ADJUSTMENT|null|mv=24|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=-1000|EXPENSE|reason=taxa de administração|note=null|reverses=null|reversedBy=32|flag=false|legacy=false
            2026-10-07|DEPOSIT|null|mv=25|tx=null|inv=null|null|null|q=null|price=-|amount=5000|null|reason=null|note=null|reverses=null|reversedBy=26|flag=false|legacy=false
            2026-10-08|REVERSAL|null|mv=26|tx=null|inv=null|null|null|q=null|price=-|amount=5000|null|reason=aporte não realizado|note=null|reverses=25|reversedBy=null|flag=false|legacy=false
            2026-10-09|SELL|null|mv=27|tx=null|inv=6|ACAO AAA|QUANTITY|q=5|price=28.00|amount=14000|null|reason=null|note=null|reverses=null|reversedBy=29|flag=false|legacy=false
            2026-10-10|SELL|null|mv=28|tx=null|inv=6|ACAO AAA|QUANTITY|q=2|price=29.00|amount=5800|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-11|REVERSAL|null|mv=29|tx=null|inv=6|ACAO AAA|QUANTITY|q=null|price=-|amount=14000|null|reason=venda lançada por engano|note=null|reverses=27|reversedBy=null|flag=false|legacy=false
            2026-10-12|BUY|null|mv=30|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=2|price=16.00|amount=3200|null|reason=null|note=null|reverses=null|reversedBy=31|flag=false|legacy=false
            2026-10-13|REVERSAL|null|mv=31|tx=null|inv=8|POSICAO SEM TICKER|QUANTITY|q=null|price=-|amount=3200|null|reason=compra duplicada|note=null|reverses=30|reversedBy=null|flag=false|legacy=false
            2026-10-14|REVERSAL|null|mv=32|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=-1000|null|reason=taxa devolvida|note=null|reverses=24|reversedBy=null|flag=false|legacy=false
            2026-10-15|SELL|null|mv=33|tx=null|inv=10|PREVIDENCIA POR VALOR|VALUE|q=null|price=-|amount=30000|null|reason=null|note=resgate total|reverses=null|reversedBy=null|flag=false|legacy=false
            ## statement(2026-11)
            ## pendingReconciliations
            []
            ## investmentSeries
            2026-09-30=37000
            2026-10-15=31000
            ## portfolioSeries
            2026-09-30=87000
            2026-10-15=81000
            """;
    static final String WITHOUT_LEDGER_TEXT = """
            ## listTypes(false)
            1|SEM LIVRO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=60|ACAO|AAA1|pp=20|q=3|cur=-
            2|SEM LIVRO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12|inv=1000|CDB|null|pp=-|q=null|cur=-
            ## listTypes(true)
            1|SEM LIVRO ACAO|ACOES|ALTA|2025-10-22|prof=-|inv=60|ACAO|AAA1|pp=20|q=3|cur=-
            2|SEM LIVRO RF|RENDA_FIXA|MEDIA|2025-10-22|prof=12|inv=1000|CDB|null|pp=-|q=null|cur=-
            ## investmentStatus(1)
            FALHA IllegalStateException: ledger não configurado
            ## investmentStatus(2)
            FALHA IllegalStateException: ledger não configurado
            ## cashStatus
            FALHA IllegalStateException: ledger não configurado
            ## getAllCurrentValues
            1=7500
            2=112000
            ## getTotalInvestmentValue
            119500
            ## getTotalPatrimony
            126500
            ## getTotalProfit
            13500
            ## metrics
            null
            ## statement(2026-08)
            ## statement(2026-09)
            ## statement(2026-10)
            2026-10-05|null|BUY|mv=null|tx=9|inv=1|SEM LIVRO ACAO|null|q=3|price=20.00|amount=6000|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=true
            ## statement(2026-11)
            """;
    static final String INVALID_LEDGER_TEXT = """
            ## listTypes(false)
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## listTypes(true)
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## investmentStatus(1)
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## cashStatus
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## getAllCurrentValues
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## getTotalInvestmentValue
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## getTotalPatrimony
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## getTotalProfit
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## metrics
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## statement(2026-08)
            ## statement(2026-09)
            ## statement(2026-10)
            2026-10-01|OPENING|null|mv=1|tx=null|inv=1|ATIVO X|QUANTITY|q=0|price=-|amount=0|null|reason=null|note=cadastro|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-01|DEPOSIT|null|mv=3|tx=null|inv=null|null|null|q=null|price=-|amount=100|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            2026-10-02|WITHDRAWAL|null|mv=4|tx=null|inv=null|null|null|q=null|price=-|amount=500|null|reason=null|note=null|reverses=null|reversedBy=null|flag=false|legacy=false
            ## statement(2026-11)
            ## pendingReconciliations
            FALHA LedgerException: caixa ficaria negativo (-400 centavos) [intenção inv-w, 2026-10-02]
            ## investmentSeries
            ## portfolioSeries
            """;
}

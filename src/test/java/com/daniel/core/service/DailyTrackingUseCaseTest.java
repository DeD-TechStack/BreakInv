package com.daniel.core.service;

import com.daniel.core.domain.entity.*;
import com.daniel.core.domain.entity.Enums.FlowKind;
import com.daniel.core.domain.repository.*;
import com.daniel.core.domain.repository.IStockPriceProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DailyTrackingUseCase using lightweight in-memory stubs.
 * No JavaFX, no SQLite, no HTTP calls are involved.
 *
 * Skipped paths:
 *   - getCurrentValue() for investments with ticker → calls BrapiClient (HTTP)
 *   - saveEntry / takeSnapshotIfNeeded → use instanceof checks against concrete repo classes
 */
class DailyTrackingUseCaseTest {

    // ===== Minimal in-memory stubs =====

    static class StubTypeRepo implements IInvestmentTypeRepository {
        final List<InvestmentType> all = new ArrayList<>();
        int nextId = 10;
        String lastCreatedName;
        int lastUpdatedId = -1;

        void add(InvestmentType inv) { all.add(inv); }

        @Override public List<InvestmentType> listAll() { return Collections.unmodifiableList(all); }
        @Override public void save(String name) {}
        @Override public void rename(int id, String newName) {}
        @Override public void delete(int id) {}

        @Override
        public int createFull(String name, String category, String liquidity,
                              java.time.LocalDate investmentDate, java.math.BigDecimal profitability,
                              java.math.BigDecimal investedValue, String typeOfInvestment,
                              String indexType, java.math.BigDecimal indexPercentage,
                              String ticker, java.math.BigDecimal purchasePrice, Integer quantity) {
            lastCreatedName = name;
            return nextId++;
        }

        @Override
        public void updateFull(int id, String name, String category, String liquidity,
                               java.time.LocalDate investmentDate, java.math.BigDecimal profitability,
                               java.math.BigDecimal investedValue, String typeOfInvestment,
                               String indexType, java.math.BigDecimal indexPercentage,
                               String ticker, java.math.BigDecimal purchasePrice, Integer quantity) {
            lastUpdatedId = id;
        }
    }

    static class StubFlowRepo implements IFlowRepository {
        final Map<LocalDate, List<Flow>> byDate = new HashMap<>();

        void add(LocalDate date, Flow flow) {
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(flow);
        }

        @Override public List<Flow> listForDate(LocalDate date) {
            return byDate.getOrDefault(date, List.of());
        }
        @Override public void delete(long id) {}
        @Override public long create(Flow flow) { return 0; }
    }

    static class StubSnapshotRepo implements ISnapshotRepository {
        final Map<LocalDate, Long> cash = new HashMap<>();
        final Map<LocalDate, Map<Long, Long>> investments = new HashMap<>();

        void putCash(LocalDate date, long cents) { cash.put(date, cents); }
        void putInvestment(LocalDate date, long typeId, long cents) {
            investments.computeIfAbsent(date, k -> new HashMap<>()).put(typeId, cents);
        }

        final Map<LocalDate, Long> upsertedCash = new HashMap<>();
        final Map<LocalDate, Map<Long, Long>> upsertedInvestments = new HashMap<>();

        final Map<Long, Map<String, Long>> seriesData = new HashMap<>();

        void putSeries(long typeId, Map<String, Long> series) { seriesData.put(typeId, series); }

        @Override public long getCash(LocalDate date) { return cash.getOrDefault(date, 0L); }
        @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) {
            return investments.getOrDefault(date, Map.of());
        }
        @Override public Map<String, Long> seriesForInvestiments(long investimentsTypeId) {
            return seriesData.getOrDefault(investimentsTypeId, Map.of());
        }

        @Override public void upsertCash(LocalDate date, long cashCents) {
            upsertedCash.put(date, cashCents);
        }
        @Override public void upsertInvestment(LocalDate date, long investmentTypeId,
                                               long valueCents, String note) {
            upsertedInvestments.computeIfAbsent(date, k -> new HashMap<>())
                               .put(investmentTypeId, valueCents);
        }
    }

    static class StubPriceProvider implements IStockPriceProvider {
        private final Map<String, Double> prices = new HashMap<>();
        private final java.util.Set<String> throwing = new java.util.HashSet<>();

        void put(String ticker, double price) { prices.put(ticker, price); }
        void throwFor(String ticker) { throwing.add(ticker); }

        @Override
        public Double fetchPrice(String ticker) {
            if (throwing.contains(ticker)) throw new RuntimeException("simulated network error");
            return prices.get(ticker);
        }
    }

    static class StubTxRepo implements ITransactionRepository {
        final List<Transaction> all = new ArrayList<>();

        @Override public long insert(Transaction tx) { all.add(tx); return all.size(); }
        @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) {
            return all.stream()
                    .filter(t -> !t.date().isBefore(start) && !t.date().isAfter(end))
                    .toList();
        }
    }

    // ===== Test fixtures =====

    private StubTypeRepo typeRepo;
    private StubFlowRepo flowRepo;
    private StubSnapshotRepo snapRepo;
    private StubTxRepo txRepo;
    private StubPriceProvider priceProvider;
    private DailyTrackingUseCase uc;

    @BeforeEach
    void setUp() {
        typeRepo      = new StubTypeRepo();
        flowRepo      = new StubFlowRepo();
        snapRepo      = new StubSnapshotRepo();
        txRepo        = new StubTxRepo();
        priceProvider = new StubPriceProvider();
        uc = new DailyTrackingUseCase(flowRepo, typeRepo, snapRepo, txRepo, priceProvider);
    }

    // ===== Formatting helpers =====

    @Test
    void brl_formatsPositiveCents() {
        String result = uc.brl(125000L); // R$ 1.250,00
        assertTrue(result.contains("1") && result.contains("250"),
                "Expected R$ 1.250,00 style in: " + result);
    }

    @Test
    void brl_zeroFormatsToZero() {
        String result = uc.brl(0L);
        assertTrue(result.contains("0"), "Expected '0' in: " + result);
    }

    @Test
    void brlAbs_negativeInputBecomesPositive() {
        String neg = uc.brl(-1250L);
        String abs = uc.brlAbs(-1250L);
        assertFalse(abs.contains("-"), "brlAbs must not contain '-', got: " + abs);
        // absolute value should equal positive formatted value
        assertEquals(uc.brlAbs(1250L), abs);
    }

    // ===== getAveragePrice =====

    @Test
    void getAveragePrice_singlePosition_equalsPrice() {
        typeRepo.add(new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        ));
        assertEquals(30.0, uc.getAveragePrice("PETR4"), 0.001);
    }

    @Test
    void getAveragePrice_twoPositions_weightedAverage() {
        // 100 * 30 = 3000, 50 * 40 = 2000 → total 5000 / 150 = 33.33
        typeRepo.add(new InvestmentType(
                1, "PETR4 A", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        ));
        typeRepo.add(new InvestmentType(
                2, "PETR4 B", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(2000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(40.0), 50, null
        ));
        assertEquals(100.0 / 3.0, uc.getAveragePrice("PETR4"), 0.01);
    }

    @Test
    void getAveragePrice_unknownTicker_returnsZero() {
        assertEquals(0.0, uc.getAveragePrice("XPTO3"), 0.001);
    }

    @Test
    void getAveragePrice_tickerMismatch_returnsZero() {
        typeRepo.add(new InvestmentType(
                1, "VALE3", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(2000),
                "ACAO", null, null, "VALE3", BigDecimal.valueOf(70.0), 50, null
        ));
        assertEquals(0.0, uc.getAveragePrice("PETR4"), 0.001);
    }

    // ===== getTotalQuantity =====

    @Test
    void getTotalQuantity_singlePosition() {
        typeRepo.add(new InvestmentType(
                1, "VALE3", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(2000),
                "ACAO", null, null, "VALE3", BigDecimal.valueOf(70.0), 50, null
        ));
        assertEquals(50, uc.getTotalQuantity("VALE3"));
    }

    @Test
    void getTotalQuantity_multiplePositions_summed() {
        typeRepo.add(new InvestmentType(
                1, "PETR4 A", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        ));
        typeRepo.add(new InvestmentType(
                2, "PETR4 B", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(1500),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 50, null
        ));
        assertEquals(150, uc.getTotalQuantity("PETR4"));
    }

    @Test
    void getTotalQuantity_unknownTicker_returnsZero() {
        assertEquals(0, uc.getTotalQuantity("XPTO3"));
    }

    // ===== groupByTicker =====

    @Test
    void groupByTicker_groupsCorrectly() {
        typeRepo.add(new InvestmentType(
                1, "PETR4 A", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        ));
        typeRepo.add(new InvestmentType(
                2, "PETR4 B", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(1500),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 50, null
        ));
        typeRepo.add(new InvestmentType(
                3, "VALE3", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3500),
                "ACAO", null, null, "VALE3", BigDecimal.valueOf(70.0), 50, null
        ));

        Map<String, List<InvestmentType>> grouped = uc.groupByTicker();

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("PETR4").size());
        assertEquals(1, grouped.get("VALE3").size());
    }

    @Test
    void groupByTicker_nullTicker_excluded() {
        typeRepo.add(new InvestmentType(1, "Tesouro")); // no ticker
        Map<String, List<InvestmentType>> grouped = uc.groupByTicker();
        assertTrue(grouped.isEmpty());
    }

    // ===== listTypes =====

    @Test
    void listTypes_delegatesToRepository() {
        typeRepo.add(new InvestmentType(1, "A"));
        typeRepo.add(new InvestmentType(2, "B"));
        assertEquals(2, uc.listTypes().size());
    }

    // ===== flowsFor =====

    @Test
    void flowsFor_returnsFlowsForDate() {
        LocalDate date = LocalDate.of(2024, 3, 7);
        Flow flow = new Flow(1L, date, FlowKind.CASH, null, FlowKind.INVESTMENT, 1L, 5000L, null);
        flowRepo.add(date, flow);

        List<Flow> result = uc.flowsFor(date);
        assertEquals(1, result.size());
        assertEquals(5000L, result.get(0).amountCents());
    }

    @Test
    void flowsFor_noFlows_returnsEmpty() {
        assertTrue(uc.flowsFor(LocalDate.now()).isEmpty());
    }

    // ===== summaryFor =====

    @Test
    void summaryFor_totalTodayCents_isCashPlusInvestments() {
        InvestmentType tesouro = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        typeRepo.add(tesouro);

        LocalDate today = LocalDate.of(2024, 3, 7);
        snapRepo.putCash(today, 10000L);
        snapRepo.putInvestment(today, 1L, 50000L);

        DailySummary summary = uc.summaryFor(today);

        assertEquals(60000L, summary.totalTodayCents()); // 10000 + 50000
    }

    @Test
    void summaryFor_cashDelta_dayOverDay() {
        InvestmentType tesouro = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        typeRepo.add(tesouro);

        LocalDate today    = LocalDate.of(2024, 3, 7);
        LocalDate yesterday = today.minusDays(1);

        snapRepo.putCash(today, 12000L);
        snapRepo.putCash(yesterday, 10000L);

        DailySummary summary = uc.summaryFor(today);

        assertEquals(2000L, summary.cashDeltaCents());
    }

    @Test
    void summaryFor_investmentProfit_withoutFlows() {
        InvestmentType tesouro = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        typeRepo.add(tesouro);

        LocalDate today    = LocalDate.of(2024, 3, 7);
        LocalDate yesterday = today.minusDays(1);

        snapRepo.putInvestment(today, 1L, 51000L);
        snapRepo.putInvestment(yesterday, 1L, 50000L);

        DailySummary summary = uc.summaryFor(today);

        // profit = today - yesterday - flowsIn + flowsOut = 1000
        assertEquals(1000L, summary.investmentProfitTodayCents().get(1L));
    }

    @Test
    void summaryFor_investmentProfit_flowInReducesProfit() {
        InvestmentType tesouro = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        typeRepo.add(tesouro);

        LocalDate today    = LocalDate.of(2024, 3, 7);
        LocalDate yesterday = today.minusDays(1);

        snapRepo.putInvestment(today, 1L, 55000L);
        snapRepo.putInvestment(yesterday, 1L, 50000L);

        // 3000 cents deposited into this investment (inflow)
        Flow inflow = new Flow(1L, today, FlowKind.CASH, null, FlowKind.INVESTMENT, 1L, 3000L, null);
        flowRepo.add(today, inflow);

        DailySummary summary = uc.summaryFor(today);

        // profit = 55000 - 50000 - 3000 = 2000
        assertEquals(2000L, summary.investmentProfitTodayCents().get(1L));
    }

    @Test
    void summaryFor_noData_allZero() {
        LocalDate today = LocalDate.of(2024, 3, 7);
        DailySummary summary = uc.summaryFor(today);

        assertEquals(0L, summary.totalTodayCents());
        assertEquals(0L, summary.cashTodayCents());
        assertEquals(0L, summary.cashDeltaCents());
        assertEquals(0L, summary.totalProfitTodayCents());
    }

    // ===== recordBuy / recordSell / listTransactions =====

    @Test
    void recordBuy_insertsTransaction() {
        LocalDate date = LocalDate.of(2024, 3, 7);
        uc.recordBuy(1, "PETR4", "PETR4", 100, 3000L, 300000L, date);

        List<Transaction> txs = uc.listTransactions(YearMonth.of(2024, 3));
        assertEquals(1, txs.size());
        assertEquals(Transaction.BUY, txs.get(0).type());
        assertEquals(100, txs.get(0).quantity());
        assertEquals(300000L, txs.get(0).totalCents());
    }

    @Test
    void recordSell_insertsTransaction() {
        LocalDate date = LocalDate.of(2024, 3, 7);
        uc.recordSell(1, "PETR4", "PETR4", 50, 3500L, 175000L, date, "Realizando lucro");

        List<Transaction> txs = uc.listTransactions(YearMonth.of(2024, 3));
        assertEquals(1, txs.size());
        assertEquals(Transaction.SELL, txs.get(0).type());
        assertEquals("Realizando lucro", txs.get(0).note());
    }

    @Test
    void listTransactions_filtersToMonth() {
        uc.recordBuy(1, "PETR4", "PETR4", 10, 3000L, 30000L, LocalDate.of(2024, 2, 15));
        uc.recordBuy(1, "PETR4", "PETR4", 20, 3000L, 60000L, LocalDate.of(2024, 3, 7));

        List<Transaction> march = uc.listTransactions(YearMonth.of(2024, 3));
        assertEquals(1, march.size());

        List<Transaction> feb = uc.listTransactions(YearMonth.of(2024, 2));
        assertEquals(1, feb.size());
    }

    // ===== hasAnyDataPublic =====

    @Test
    void hasAnyDataPublic_withCash_returnsTrue() {
        LocalDate today = LocalDate.of(2024, 3, 7);
        snapRepo.putCash(today, 100L);
        assertTrue(uc.hasAnyDataPublic(today));
    }

    @Test
    void hasAnyDataPublic_noData_returnsFalse() {
        assertFalse(uc.hasAnyDataPublic(LocalDate.of(2024, 3, 7)));
    }

    @Test
    void hasAnyDataPublic_withInvestmentValue_returnsTrue() {
        InvestmentType inv = new InvestmentType(1, "Tesouro");
        typeRepo.add(inv);

        LocalDate today = LocalDate.of(2024, 3, 7);
        snapRepo.putInvestment(today, 1L, 5000L);

        assertTrue(uc.hasAnyDataPublic(today));
    }

    // ===== summaryFor — outflow increases profit =====

    @Test
    void summaryFor_outflowFromInvestment_increasesProfit() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        typeRepo.add(inv);

        LocalDate today    = LocalDate.of(2024, 3, 7);
        LocalDate yesterday = today.minusDays(1);

        // Yesterday 50000, withdrew 3000, today 48000
        // profitCents = 48000 - 50000 + 3000 = 1000
        snapRepo.putInvestment(today, 1L, 48000L);
        snapRepo.putInvestment(yesterday, 1L, 50000L);

        Flow outflow = new Flow(1L, today,
                FlowKind.INVESTMENT, 1L,   // from this investment
                FlowKind.CASH, null,
                3000L, null);
        flowRepo.add(today, outflow);

        DailySummary summary = uc.summaryFor(today);

        assertEquals(1000L, summary.investmentProfitTodayCents().get(1L));
    }

    @Test
    void summaryFor_twoInvestments_totalIncludesBoth() {
        InvestmentType t1 = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        InvestmentType t2 = new InvestmentType(
                2, "LCI", "RENDA_FIXA", "MEDIA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.10), BigDecimal.valueOf(500)
        );
        typeRepo.add(t1);
        typeRepo.add(t2);

        LocalDate today = LocalDate.of(2024, 3, 7);
        snapRepo.putCash(today, 5000L);
        snapRepo.putInvestment(today, 1L, 30000L);
        snapRepo.putInvestment(today, 2L, 20000L);

        DailySummary summary = uc.summaryFor(today);

        assertEquals(55000L, summary.totalTodayCents()); // 5000 + 30000 + 20000
        assertEquals(2, summary.investmentTodayCents().size());
    }

    @Test
    void summaryFor_firstEntry_noYesterdayData_profitEqualsValue() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        typeRepo.add(inv);

        LocalDate today = LocalDate.of(2024, 3, 7);
        snapRepo.putInvestment(today, 1L, 40000L);
        // No yesterday data → yesterdayCents = 0

        DailySummary summary = uc.summaryFor(today);

        // profit = 40000 - 0 = 40000
        assertEquals(40000L, summary.investmentProfitTodayCents().get(1L));
    }

    // ===== groupByTicker — blank ticker excluded =====

    @Test
    void groupByTicker_blankTicker_excluded() {
        typeRepo.add(new InvestmentType(
                1, "Renda Fixa", "RENDA_FIXA", "ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(1000),
                "PREFIXADO", null, null, "   ", null, null, null // blank ticker
        ));
        assertTrue(uc.groupByTicker().isEmpty());
    }

    // ===== getAveragePrice — null purchasePrice skipped =====

    @Test
    void getAveragePrice_nullPurchasePrice_skipped() {
        typeRepo.add(new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", null, 100, null // purchasePrice null
        ));
        // null purchasePrice → skipped → totalQuantity=0 → returns 0.0
        assertEquals(0.0, uc.getAveragePrice("PETR4"), 0.001);
    }

    // ===== getTotalQuantity — null quantity skipped =====

    @Test
    void getTotalQuantity_nullQuantity_skipped() {
        typeRepo.add(new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), null, null // quantity null
        ));
        assertEquals(0, uc.getTotalQuantity("PETR4"));
    }

    // ===== getCurrentValue — non-ticker paths (no BrapiClient) =====

    @Test
    void getCurrentValue_noTicker_investedValueOnly_returnsInvestedValue() {
        // No ticker, no date → falls to investedValue path
        InvestmentType inv = new InvestmentType(
                1, "Poupança", "RENDA_FIXA", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(5000.0)
        );
        long result = uc.getCurrentValue(inv, LocalDate.now());
        assertEquals(500000L, result); // 5000 * 100
    }

    @Test
    void getCurrentValue_noTicker_withDateAndRate_appliesCompoundInterest() {
        // R$1000 at 10% annual, 12 months → should be > 100000 cents
        LocalDate investmentDate = LocalDate.of(2023, 1, 1);
        LocalDate today          = LocalDate.of(2024, 1, 1);

        InvestmentType inv = new InvestmentType(
                1, "CDB", "RENDA_FIXA", "MEDIA",
                investmentDate, BigDecimal.valueOf(10.0), BigDecimal.valueOf(1000.0)
        );
        long result = uc.getCurrentValue(inv, today);
        assertTrue(result > 100000L, "Expected compound growth > R$1000, got: " + result);
    }

    @Test
    void getCurrentValue_noValues_returnsZero() {
        // Neither ticker, profitability, investedValue → 0
        InvestmentType inv = new InvestmentType(1, "Empty");
        long result = uc.getCurrentValue(inv, LocalDate.now());
        assertEquals(0L, result);
    }

    // ===== getProfitability =====

    @Test
    void getProfitability_nullInvestedValue_returnsZero() {
        InvestmentType inv = new InvestmentType(1, "Test");
        assertEquals(0.0, uc.getProfitability(inv, LocalDate.now()), 0.001);
    }

    @Test
    void getProfitability_zeroInvestedValue_returnsZero() {
        InvestmentType inv = new InvestmentType(
                1, "Test", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(0)
        );
        assertEquals(0.0, uc.getProfitability(inv, LocalDate.now()), 0.001);
    }

    @Test
    void getProfitability_noDateNoRate_breakEven() {
        // getCurrentValue with no date returns investedValue*100 → profitability = 0%
        InvestmentType inv = new InvestmentType(
                1, "CDB", "RENDA_FIXA", "ALTA",
                null, BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000.0)
        );
        // getCurrentValue returns 100000, investedCents = 100000 → 0%
        assertEquals(0.0, uc.getProfitability(inv, LocalDate.now()), 0.001);
    }

    // ===== saveEntry =====

    @Test
    void saveEntry_null_doesNothing() {
        uc.saveEntry(null);
        assertTrue(snapRepo.upsertedCash.isEmpty());
        assertTrue(snapRepo.upsertedInvestments.isEmpty());
    }

    @Test
    void saveEntry_withPositiveCash_upsertsCash() {
        LocalDate date = LocalDate.of(2024, 6, 1);
        DailyEntry entry = new DailyEntry(date, 50000L, Map.of());
        uc.saveEntry(entry);
        assertEquals(50000L, snapRepo.upsertedCash.get(date));
    }

    @Test
    void saveEntry_withNegativeCash_skipsCashUpsert() {
        LocalDate date = LocalDate.of(2024, 6, 1);
        DailyEntry entry = new DailyEntry(date, -1L, Map.of());
        uc.saveEntry(entry);
        assertFalse(snapRepo.upsertedCash.containsKey(date));
    }

    @Test
    void saveEntry_withInvestmentValues_upsertsEachInvestment() {
        InvestmentType inv1 = new InvestmentType(1, "A");
        InvestmentType inv2 = new InvestmentType(2, "B");
        LocalDate date = LocalDate.of(2024, 6, 1);
        DailyEntry entry = new DailyEntry(date, -1L,
                Map.of(inv1, 10000L, inv2, 20000L));
        uc.saveEntry(entry);

        Map<Long, Long> saved = snapRepo.upsertedInvestments.get(date);
        assertNotNull(saved);
        assertEquals(10000L, saved.get(1L));
        assertEquals(20000L, saved.get(2L));
    }

    @Test
    void saveEntry_negativeInvestmentValue_isSkipped() {
        InvestmentType inv = new InvestmentType(1, "A");
        LocalDate date = LocalDate.of(2024, 6, 1);
        DailyEntry entry = new DailyEntry(date, -1L, Map.of(inv, -5000L));
        uc.saveEntry(entry);
        assertFalse(snapRepo.upsertedInvestments.containsKey(date));
    }

    // ===== createTypeFull / updateTypeFull delegate to repo =====

    @Test
    void createTypeFull_delegatesToRepo_returnsId() {
        int id = uc.createTypeFull(
                "Meu CDB", "RENDA_FIXA", "MEDIA",
                LocalDate.of(2024, 1, 1), BigDecimal.valueOf(12.0),
                BigDecimal.valueOf(1000.0), "PREFIXADO",
                null, null, null, null, null
        );
        assertEquals("Meu CDB", typeRepo.lastCreatedName);
        assertEquals(10, id); // StubTypeRepo starts at nextId=10
    }

    @Test
    void updateTypeFull_delegatesToRepo() {
        uc.updateTypeFull(
                5, "Renamed", "RENDA_FIXA", "ALTA",
                LocalDate.of(2024, 1, 1), BigDecimal.valueOf(10.0),
                BigDecimal.valueOf(500.0), "PREFIXADO",
                null, null, null, null, null
        );
        assertEquals(5, typeRepo.lastUpdatedId);
    }

    // ===== getCurrentValue — ticker path uses IStockPriceProvider =====

    @Test
    void getCurrentValue_withTickerAndProvider_returnsProviderValue() {
        // 100 shares × R$35.50 = R$3550.00 = 355000 cents
        priceProvider.put("PETR4", 35.50);
        InvestmentType inv = new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        );
        long result = uc.getCurrentValue(inv, LocalDate.now());
        assertEquals(355000L, result);
    }

    @Test
    void getCurrentValue_providerReturnsNull_fallsBackToPurchasePrice() {
        // provider returns null → fallback: 100 × R$30.00 = 300000 cents
        // priceProvider has no entry for PETR4 → returns null
        InvestmentType inv = new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        );
        long result = uc.getCurrentValue(inv, LocalDate.now());
        assertEquals(300000L, result); // purchasePrice fallback
    }

    // ===== loadEntry =====

    @Test
    void loadEntry_emptyState_returnsZeroCashAndEmptyInvestments() {
        LocalDate date = LocalDate.of(2024, 5, 1);
        DailyEntry entry = uc.loadEntry(date);
        assertEquals(date, entry.date());
        assertEquals(0L, entry.cashCents());
        assertTrue(entry.investmentValuesCents().isEmpty());
    }

    @Test
    void loadEntry_withCash_returnsCash() {
        LocalDate date = LocalDate.of(2024, 5, 1);
        snapRepo.putCash(date, 75000L);
        DailyEntry entry = uc.loadEntry(date);
        assertEquals(75000L, entry.cashCents());
    }

    @Test
    void loadEntry_withTypesButNoSnapshot_mapsAllToZero() {
        typeRepo.add(new InvestmentType(1, "CDB"));
        typeRepo.add(new InvestmentType(2, "LCI"));
        LocalDate date = LocalDate.of(2024, 5, 1);

        DailyEntry entry = uc.loadEntry(date);

        assertEquals(2, entry.investmentValuesCents().size());
        entry.investmentValuesCents().values().forEach(v -> assertEquals(0L, v));
    }

    @Test
    void loadEntry_withSnapshot_mapsCorrectCents() {
        InvestmentType inv = new InvestmentType(1, "CDB");
        typeRepo.add(inv);
        LocalDate date = LocalDate.of(2024, 5, 1);
        snapRepo.putInvestment(date, 1L, 80000L);

        DailyEntry entry = uc.loadEntry(date);

        assertEquals(80000L, entry.investmentValuesCents().get(inv));
    }

    @Test
    void loadEntry_typeNotInSnapshot_returnsZeroForMissingType() {
        InvestmentType inv1 = new InvestmentType(1, "CDB");
        InvestmentType inv2 = new InvestmentType(2, "LCI");
        typeRepo.add(inv1);
        typeRepo.add(inv2);
        LocalDate date = LocalDate.of(2024, 5, 1);
        snapRepo.putInvestment(date, 1L, 50000L); // only inv1 has data

        DailyEntry entry = uc.loadEntry(date);

        assertEquals(50000L, entry.investmentValuesCents().get(inv1));
        assertEquals(0L,     entry.investmentValuesCents().get(inv2));
    }

    // ===== takeSnapshotIfNeeded =====

    @Test
    void takeSnapshotIfNeeded_snapshotAlreadyExists_doesNotUpsert() {
        LocalDate date = LocalDate.of(2024, 5, 1);
        // pre-existing snapshot data → early return
        snapRepo.putInvestment(date, 1L, 40000L);

        uc.takeSnapshotIfNeeded(date);

        // no additional upserts should have happened
        assertTrue(snapRepo.upsertedInvestments.isEmpty());
    }

    @Test
    void takeSnapshotIfNeeded_noTypes_doesNotUpsert() {
        LocalDate date = LocalDate.of(2024, 5, 1);
        // typeRepo is empty → early return after checking existing snapshot (also empty)
        uc.takeSnapshotIfNeeded(date);
        assertTrue(snapRepo.upsertedInvestments.isEmpty());
    }

    @Test
    void takeSnapshotIfNeeded_typeWithInvestedValue_upsertsCalculatedValue() {
        // investedValue=1000 with no date/profitability → getCurrentValue = 1000*100 = 100000
        InvestmentType inv = new InvestmentType(
                3, "Poupança", "RENDA_FIXA", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(1000.0)
        );
        typeRepo.add(inv);
        LocalDate date = LocalDate.of(2024, 5, 1);

        uc.takeSnapshotIfNeeded(date);

        Map<Long, Long> upserted = snapRepo.upsertedInvestments.get(date);
        assertNotNull(upserted);
        assertEquals(100000L, upserted.get(3L));
    }

    @Test
    void takeSnapshotIfNeeded_typeWithZeroValue_notUpserted() {
        // type with no investedValue/profitability/ticker → getCurrentValue = 0 → not upserted
        InvestmentType inv = new InvestmentType(4, "Empty");
        typeRepo.add(inv);
        LocalDate date = LocalDate.of(2024, 5, 1);

        uc.takeSnapshotIfNeeded(date);

        // upsertedInvestments should not have an entry for this date
        assertFalse(snapRepo.upsertedInvestments.containsKey(date));
    }

    @Test
    void takeSnapshotIfNeeded_withTickerType_usesProviderPrice() {
        // 50 shares × R$40.00 = R$2000.00 = 200000 cents
        priceProvider.put("VALE3", 40.0);
        InvestmentType inv = new InvestmentType(
                5, "VALE3", "ACOES", "MUITO_ALTA",
                LocalDate.now(), null, BigDecimal.valueOf(1500),
                "ACAO", null, null, "VALE3", BigDecimal.valueOf(30.0), 50, null
        );
        typeRepo.add(inv);
        LocalDate date = LocalDate.of(2024, 5, 1);

        uc.takeSnapshotIfNeeded(date);

        Map<Long, Long> upserted = snapRepo.upsertedInvestments.get(date);
        assertNotNull(upserted);
        assertEquals(200000L, upserted.get(5L));
    }

    // ===== seriesForInvestment =====

    @Test
    void seriesForInvestment_noData_returnsEmptyList() {
        // stub returns empty map by default
        List<DailyTrackingUseCase.SeriesPoint> points = uc.seriesForInvestment(99);
        assertNotNull(points);
        assertTrue(points.isEmpty());
    }

    @Test
    void seriesForInvestment_singlePoint_parsedCorrectly() {
        snapRepo.putSeries(1L, Map.of("2024-03-07", 50000L));

        List<DailyTrackingUseCase.SeriesPoint> points = uc.seriesForInvestment(1);

        assertEquals(1, points.size());
        assertEquals(LocalDate.of(2024, 3, 7), points.get(0).date());
        assertEquals(50000L, points.get(0).valueCents());
    }

    @Test
    void seriesForInvestment_multiplePoints_allParsed() {
        snapRepo.putSeries(2L, Map.of(
                "2024-01-01", 10000L,
                "2024-02-01", 11000L,
                "2024-03-01", 12000L
        ));

        List<DailyTrackingUseCase.SeriesPoint> points = uc.seriesForInvestment(2);

        assertEquals(3, points.size());
        long total = points.stream().mapToLong(DailyTrackingUseCase.SeriesPoint::valueCents).sum();
        assertEquals(33000L, total);
    }

    // ===== getCurrentValue — ticker-path branch coverage =====

    @Test
    void getCurrentValue_blankTicker_routesToNonTickerPath() {
        // Guard: !ticker.isBlank() fails → falls to investedValue path
        InvestmentType inv = new InvestmentType(
                1, "X", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(500.0),
                "ACAO", null, null, "   ", BigDecimal.valueOf(30.0), 100, null
        );
        assertEquals(50000L, uc.getCurrentValue(inv, LocalDate.now()));
    }

    @Test
    void getCurrentValue_nullQuantity_routesToNonTickerPath() {
        // Guard: quantity != null fails → falls to investedValue path
        InvestmentType inv = new InvestmentType(
                1, "X", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(500.0),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), null, null
        );
        assertEquals(50000L, uc.getCurrentValue(inv, LocalDate.now()));
    }

    @Test
    void getCurrentValue_nullPurchasePrice_routesToNonTickerPath() {
        // Guard: purchasePrice != null fails → falls to investedValue path
        InvestmentType inv = new InvestmentType(
                1, "X", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(500.0),
                "ACAO", null, null, "PETR4", null, 100, null
        );
        assertEquals(50000L, uc.getCurrentValue(inv, LocalDate.now()));
    }

    @Test
    void getCurrentValue_providerThrows_fallsBackToPurchasePrice() {
        // Exception in fetchPrice → catch block → fallback: 50 × R$25.00 = 125000 cents
        priceProvider.throwFor("ITUB4");
        InvestmentType inv = new InvestmentType(
                1, "ITUB4", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(1000),
                "ACAO", null, null, "ITUB4", BigDecimal.valueOf(25.0), 50, null
        );
        assertEquals(125000L, uc.getCurrentValue(inv, LocalDate.now()));
    }

    @Test
    void getCurrentValue_providerReturnsZero_returnsZero() {
        // price=0.0 → (long)(0.0 * qty * 100) = 0L; no fallback — zero is a valid provider return
        priceProvider.put("MGLU3", 0.0);
        InvestmentType inv = new InvestmentType(
                1, "MGLU3", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(2000),
                "ACAO", null, null, "MGLU3", BigDecimal.valueOf(10.0), 200, null
        );
        assertEquals(0L, uc.getCurrentValue(inv, LocalDate.now()));
    }

    @Test
    void getCurrentValue_multipleInvestments_differentTickers_resolvedIndependently() {
        priceProvider.put("PETR4", 35.0);  // 100 × 35.00 × 100 = 350000
        priceProvider.put("VALE3", 45.0);  // 50  × 45.00 × 100 = 225000

        InvestmentType petr = new InvestmentType(
                1, "PETR4", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(3000),
                "ACAO", null, null, "PETR4", BigDecimal.valueOf(30.0), 100, null
        );
        InvestmentType vale = new InvestmentType(
                2, "VALE3", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(1500),
                "ACAO", null, null, "VALE3", BigDecimal.valueOf(40.0), 50, null
        );

        assertEquals(350000L, uc.getCurrentValue(petr, LocalDate.now()));
        assertEquals(225000L, uc.getCurrentValue(vale, LocalDate.now()));
    }

    @Test
    void getCurrentValue_priceMathTruncatesToLong() {
        // 33.33 × 3 × 100 = 9999.0 → (long) truncates to 9999, not rounded to 10000
        priceProvider.put("TEST", 33.33);
        InvestmentType inv = new InvestmentType(
                1, "TEST", "ACOES", "MUITO_ALTA",
                null, null, BigDecimal.valueOf(1000),
                "ACAO", null, null, "TEST", BigDecimal.valueOf(30.0), 3, null
        );
        assertEquals(9999L, uc.getCurrentValue(inv, LocalDate.now()));
    }
}

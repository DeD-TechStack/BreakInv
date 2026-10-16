package com.daniel.core.service;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.repository.IStockPriceProvider;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real integration: {@link DailyTrackingUseCase} wired to the JDBC repositories AND the ledger
 * ({@link DatabaseLedgerAccess}) on a temp-file SQLite. Same numeric scenario as
 * {@code PortfolioServiceScenarioTest}, but driven through the use case the UI calls.
 * Prices are a deterministic stub — no network.
 */
class DailyTrackingLedgerIntegrationTest {

    static final LocalDate TODAY = LocalDate.now();
    static final BigDecimal P20 = new BigDecimal("20");
    static final BigDecimal P25 = new BigDecimal("25");

    @TempDir
    Path tempDir;
    String url;
    Map<String, Double> prices;
    DailyTrackingUseCase uc;
    SnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        url = "jdbc:sqlite:" + tempDir.resolve("integration.db").toString().replace("\\", "/");
        Database.configure(url);
        Database.open();
        prices = new HashMap<>();
        uc = build();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    DailyTrackingUseCase build() {
        IStockPriceProvider provider = ticker -> prices.get(ticker);
        snapshots = new SnapshotRepository();
        return new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(), snapshots,
                new TransactionRepository(), provider, new DatabaseLedgerAccess());
    }

    // ===== registration + opening =====

    @Test
    void automaticZeroCashMarkerDoesNotPreventSafeEarlierDeposits() {
        uc.movement(MovementRequest.deposit("today-deposit", TODAY, 1_000L, null));
        uc.movement(MovementRequest.deposit("earlier-deposit", TODAY.minusYears(1), 2_000L, null));
        assertEquals(3_000L, uc.portfolio().state().cashCents());
        assertEquals(3_000L, uc.portfolio().state().externalNetFlowCents());
    }

    @Test
    void rejectedFirstMovementDoesNotPersistAutomaticCashOpening() throws Exception {
        assertThrows(LedgerException.class, () -> uc.movement(
                MovementRequest.withdrawal("first-invalid", TODAY, 100L, "sem caixa")));
        assertEquals(0, count("portfolio_movement"), "abertura automática pertence à mesma transação");
        assertFalse(uc.portfolio().state().cashOpened());
        uc.movement(MovementRequest.deposit("first-valid", TODAY.minusDays(1), 1_000L, null));
        assertEquals(1_000L, uc.portfolio().state().cashCents());
        assertEquals(2, count("portfolio_movement"));
    }

    @Test
    void createInvestment_newRegistration_opensZeroPositionWithoutFakeBuy() throws Exception {
        int id = uc.createInvestment(stock("ATIVO FICTICIO", "FICT1"),
                DailyTrackingUseCase.OpeningSpec.zero("open-1", TODAY, PositionMode.QUANTITY));

        DailyTrackingUseCase.InvestmentStatus st = uc.investmentStatus(id);
        assertTrue(st.opened());
        assertEquals(PositionMode.QUANTITY, st.mode());
        assertEquals(0L, st.quantity());
        assertEquals(0L, st.costCents());
        assertFalse(st.reconciliationPending());
        assertEquals(0, count("transactions"), "cadastro não finge compra legada");
        assertEquals(1, count("portfolio_movement"), "exatamente um marco de abertura");
        assertFalse(uc.cashStatus().blocked(), "base nova: caixa não bloqueado");
        assertEquals(0L, uc.portfolio().state().externalNetFlowCents(), "abertura não é aporte");
    }

    @Test
    void createInvestment_importedBalance_opensWithDeclaredQuantityAndCost() {
        int id = uc.createInvestment(stock("IMPORTADO", "IMP1"),
                DailyTrackingUseCase.OpeningSpec.imported("open-imp", TODAY, PositionMode.QUANTITY, 10L, 20_000L, "saldo trazido da corretora"));

        DailyTrackingUseCase.InvestmentStatus st = uc.investmentStatus(id);
        assertEquals(10L, st.quantity());
        assertEquals(20_000L, st.costCents());
        assertEquals(0L, uc.portfolio().state().externalNetFlowCents(), "saldo importado não vira aporte nem lucro");
        assertEquals(0L, uc.portfolio().state().realizedGainCents());
    }

    @Test
    void createInvestment_whenOpeningFails_leavesNoHalfRegistration() throws Exception {
        // reuse an intent key with a different payload: registerOpening must reject
        uc.createInvestment(stock("PRIMEIRO", "A1"), DailyTrackingUseCase.OpeningSpec.zero("dup-key", TODAY, PositionMode.QUANTITY));
        int typesBefore = count("investment_type");

        LedgerException ex = assertThrows(LedgerException.class, () -> uc.createInvestment(stock("SEGUNDO", "A2"),
                DailyTrackingUseCase.OpeningSpec.imported("dup-key", TODAY, PositionMode.QUANTITY, 1L, 100L, null)));
        assertEquals("intentKey", ex.field());

        assertEquals(typesBefore, count("investment_type"), "cadastro revertido junto com a abertura");
        assertEquals(1, count("portfolio_movement"));
    }

    @Test
    void updateTypeFull_changesMetadataOnly_ledgerPositionUntouched() {
        int id = uc.createInvestment(stock("META", "MET1"),
                DailyTrackingUseCase.OpeningSpec.imported("o", TODAY, PositionMode.QUANTITY, 5L, 5_000L, null));

        uc.updateTypeFull(id, "META RENOMEADO", "ACOES", "ALTA", TODAY, null, new BigDecimal("999"), "ACAO",
                null, null, "MET1", new BigDecimal("77"), 777);
        uc.attachNote(id, "observação persistida");

        DailyTrackingUseCase.InvestmentStatus st = uc.investmentStatus(id);
        assertEquals(5L, st.quantity());
        assertEquals(5_000L, st.costCents());
        assertEquals("observação persistida", st.note());
        InvestmentType view = uc.listTypes().stream().filter(t -> t.id() == id).findFirst().orElseThrow();
        assertEquals("META RENOMEADO", view.name());
        assertEquals(5, view.quantity(), "posição exibida vem do ledger, não do campo editado");
        assertEquals(new BigDecimal("50.00"), view.investedValue());
    }

    // ===== mandatory numeric scenario through the use case =====

    @Test
    void scenario_depositBuySellWithdraw_metricsSnapshotsAndRejection() throws Exception {
        int id = uc.createInvestment(stock("ATIVO FICTICIO", "FICT1"),
                DailyTrackingUseCase.OpeningSpec.zero("open-1", TODAY, PositionMode.QUANTITY));

        // 1. deposit 1000
        uc.movement(MovementRequest.deposit("k1", TODAY, 100_000L, "aporte"));
        assertEquals(100_000L, uc.cashStatus().cashCents());
        assertEquals(100_000L, uc.getTotalPatrimony(TODAY));
        assertEquals(0L, uc.getTotalProfit(TODAY));

        // 2. buy 10 x 20 (price 20)
        prices.put("FICT1", 20.0);
        uc.movement(MovementRequest.buy("k2", TODAY, id, 10, P20, null));
        assertEquals(80_000L, uc.cashStatus().cashCents());
        assertEquals(100_000L, uc.getTotalPatrimony(TODAY));
        assertEquals(0L, uc.getTotalProfit(TODAY));
        InvestmentType view = uc.listTypes().get(0);
        assertEquals(10, view.quantity());
        assertEquals(new BigDecimal("200.00"), view.investedValue());

        // 3. price 25, sell 4 x 25
        prices.put("FICT1", 25.0);
        uc.movement(MovementRequest.sell("k3", TODAY, id, 4, P25, "realização"));
        assertEquals(90_000L, uc.cashStatus().cashCents());
        assertEquals(15_000L, uc.getTotalInvestmentValue(TODAY));
        assertEquals(105_000L, uc.getTotalPatrimony(TODAY));
        assertEquals(5_000L, uc.getTotalProfit(TODAY), "realizado 20 + não realizado 30");

        // 4. withdraw 100
        uc.movement(MovementRequest.withdrawal("k4", TODAY, 10_000L, null));
        assertEquals(95_000L, uc.getTotalPatrimony(TODAY));
        assertEquals(5_000L, uc.getTotalProfit(TODAY), "retirada não é prejuízo");
        assertEquals(90_000L, uc.portfolio().state().externalNetFlowCents());

        // snapshot today reflects derived state (cash + position), no past rebuilt
        uc.takeSnapshotIfNeeded(TODAY);
        assertEquals(15_000L, snapshots.getAllInvestmentsForDate(TODAY).get((long) id));
        assertEquals(80_000L, snapshots.getCash(TODAY));
        TreeMap<LocalDate, Long> series = uc.getPortfolioSnapshotSeries(TODAY, TODAY);
        assertEquals(95_000L, series.get(TODAY));

        // 5. sell 7 rejected, nothing changes
        int rows = count("portfolio_movement");
        LedgerException ex = assertThrows(LedgerException.class,
                () -> uc.movement(MovementRequest.sell("k5", TODAY, id, 7, P25, null)));
        assertEquals("quantity", ex.field());
        assertEquals(rows, count("portfolio_movement"));
        assertEquals(95_000L, uc.getTotalPatrimony(TODAY));

        // 6. reopen
        Database.close();
        Database.configure(url);
        DailyTrackingUseCase reopened = build();
        prices.put("FICT1", 25.0);
        assertEquals(95_000L, reopened.getTotalPatrimony(TODAY));
        assertEquals(5_000L, reopened.getTotalProfit(TODAY));
        assertEquals(6, reopened.listTypes().get(0).quantity());
    }

    @Test
    void legacyMutators_doNotBypassLedgerInProduction() {
        int id = uc.createInvestment(stock("X", "X1"), DailyTrackingUseCase.OpeningSpec.zero("o", TODAY, PositionMode.QUANTITY));
        assertThrows(UnsupportedOperationException.class, () -> uc.recordBuy(id, "X", "X1", 1, 100L, 100L, TODAY));
        assertThrows(UnsupportedOperationException.class, () -> uc.recordSell(id, "X", "X1", 1, 100L, 100L, TODAY, null));
        assertThrows(UnsupportedOperationException.class, () -> uc.saveEntry(new com.daniel.core.domain.entity.DailyEntry(TODAY, 1L, Map.of())));
        assertThrows(UnsupportedOperationException.class, () -> uc.createFlow(null));
        assertThrows(UnsupportedOperationException.class, () -> uc.deleteFlow(1L));
    }

    // ===== snapshots: explicit zero, archive, series include archived =====

    @Test
    void closedPosition_snapshotsExplicitZero_archiveKeepsHistoryAndSeries() throws Exception {
        int id = uc.createInvestment(stock("ZERA", "ZER1"), DailyTrackingUseCase.OpeningSpec.zero("o", TODAY, PositionMode.QUANTITY));
        uc.movement(MovementRequest.deposit("d", TODAY, 10_000L, null));
        uc.movement(MovementRequest.buy("b", TODAY, id, 2, new BigDecimal("10"), null));
        prices.put("ZER1", 10.0);
        uc.takeSnapshotIfNeeded(TODAY);
        assertEquals(2_000L, snapshots.getAllInvestmentsForDate(TODAY).get((long) id));

        assertThrows(LedgerException.class, () -> uc.archiveType(id), "posição viva não pode ser arquivada");

        uc.movement(MovementRequest.sell("s", TODAY, id, 2, new BigDecimal("10"), null));
        uc.takeSnapshotIfNeeded(TODAY);
        assertEquals(0L, snapshots.getAllInvestmentsForDate(TODAY).get((long) id), "zero explícito, não ausência");

        uc.archiveType(id);
        assertTrue(uc.listTypes().isEmpty(), "arquivado sai da lista ativa");
        assertEquals(1, uc.listTypes(true).size(), "mas continua acessível");
        assertEquals(0L, uc.getInvestmentSnapshotSeries(TODAY, TODAY).get(TODAY), "histórico agregado inclui arquivados");
        assertEquals(10_000L, uc.getTotalPatrimony(TODAY), "caixa preservado");

        assertThrows(LedgerException.class, () -> uc.deleteType(id), "com histórico não apaga fisicamente");
        uc.unarchiveType(id);
        assertEquals(1, uc.listTypes().size());
    }

    @Test
    void deleteType_withoutAnyHistory_removesRegistration() throws Exception {
        int id = new InvestmentTypeRepository().createFull("SEM HISTORICO", "OUTROS", "ALTA", null, null, null,
                null, null, null, null, null, null);
        uc.deleteType(id);
        assertEquals(0, count("investment_type"));
    }

    // ===== legacy recognition =====

    @Test
    void legacyRegistration_isShownAsUnrecognized_untilExplicitOpening() throws Exception {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int id = repo.createFull("LEGADO", "ACOES", "ALTA", TODAY.minusYears(1), null, new BigDecimal("300"),
                "ACAO", null, null, "LEG1", new BigDecimal("30"), 10);
        new TransactionRepository().insert(new com.daniel.core.domain.entity.Transaction(0, TODAY.minusMonths(6), id,
                "BUY", "LEGADO", "LEG1", 7, 3000L, 21000L, null));

        DailyTrackingUseCase.InvestmentStatus st = uc.investmentStatus(id);
        assertFalse(st.opened());
        assertTrue(st.hasLegacyData());
        InvestmentType view = uc.listTypes().get(0);
        assertEquals(10, view.quantity(), "legado não reconhecido mostra cadastro original, não some");

        var proposal = uc.proposeOpening("leg-open", TODAY, id);
        assertTrue(proposal.reconciliationFlag(), "cadastro 10 difere das transações legadas 7");
        uc.registerOpening(proposal);
        assertTrue(uc.investmentStatus(id).reconciliationPending());
        assertThrows(LedgerException.class, () -> uc.movement(MovementRequest.buy("x", TODAY, id, 1, P20, null)));

        uc.reconcile(com.daniel.core.domain.ledger.ReconciliationRequest.position("rec", TODAY, id, 10L, 30_000L, "conferido no extrato da corretora"));
        assertFalse(uc.investmentStatus(id).reconciliationPending());
        assertEquals(1, count("transactions"), "transações legadas não são replayadas nem apagadas");
        assertEquals(10, uc.listTypes().get(0).quantity());
    }

    @Test
    void legacyCash_requiresExplicitCashOpening() {
        snapshots.upsertCash(TODAY.minusDays(3), 50_000L);
        DailyTrackingUseCase.CashStatus cs = uc.cashStatus();
        assertFalse(cs.opened());
        assertTrue(cs.legacySnapshotsPresent());
        assertEquals(50_000L, cs.cashCents(), "mostra o caixa legado, com aviso");
        assertThrows(LedgerException.class, () -> uc.movement(MovementRequest.deposit("d", TODAY, 1L, null)));

        uc.registerOpening(uc.proposeCashOpening("cash-open", TODAY));
        assertTrue(uc.cashStatus().reconciliationPending());
        uc.reconcile(com.daniel.core.domain.ledger.ReconciliationRequest.cash("cash-rec", TODAY, 50_000L, "saldo conferido"));
        uc.movement(MovementRequest.deposit("d2", TODAY, 1_000L, null));
        assertEquals(51_000L, uc.cashStatus().cashCents());
    }

    // ===== statement: ledger once + legacy identified, reversals linked =====

    @Test
    void statement_listsLedgerOnceAndLegacyIdentified_withReversalLinks() {
        int id = uc.createInvestment(stock("EXT", "EXT1"), DailyTrackingUseCase.OpeningSpec.zero("o", TODAY, PositionMode.QUANTITY));
        new TransactionRepository().insert(new com.daniel.core.domain.entity.Transaction(0, TODAY, id,
                "BUY", "EXT", "EXT1", 1, 100L, 100L, "legado"));
        uc.movement(MovementRequest.deposit("d", TODAY, 10_000L, null));
        var buy = uc.movement(MovementRequest.buy("b", TODAY, id, 1, new BigDecimal("50"), null));
        uc.movement(MovementRequest.adjustCash("adj", TODAY, -500L, AdjustmentClassification.EXPENSE, "tarifa", null));
        uc.movement(MovementRequest.reversal("rev", TODAY, buy.id(), "lançamento duplicado"));

        List<StatementEntry> rows = uc.statement(YearMonth.from(TODAY));
        assertEquals(6, rows.size(), "opening inv + deposit + buy + adjustment + reversal + 1 legado");
        assertTrue(uc.statement(YearMonth.of(MovementRequest.MIN_YEAR, 1)).stream()
                        .anyMatch(r -> r.kind() == MovementKind.OPENING && r.cashOnly() && r.amountCents() == 0),
                "marco técnico zero fica no início do intervalo suportado, não no mês corrente");
        assertEquals(1, rows.stream().filter(StatementEntry::legacy).count());
        StatementEntry buyRow = rows.stream().filter(r -> r.movementId() != null && r.movementId() == buy.id()).findFirst().orElseThrow();
        assertNotNull(buyRow.reversedById(), "original aponta para o estorno");
        StatementEntry rev = rows.stream().filter(r -> r.kind() == MovementKind.REVERSAL).findFirst().orElseThrow();
        assertEquals(buy.id(), rev.reversesId());
        assertEquals("EXT", buyRow.investmentName());
        assertTrue(rows.stream().anyMatch(r -> r.kind() == MovementKind.OPENING));
        assertEquals(9_500L, uc.cashStatus().cashCents(), "deposit - tarifa; compra estornada");
    }

    // ===== helpers =====

    static DailyTrackingUseCase.NewInvestment stock(String name, String ticker) {
        return new DailyTrackingUseCase.NewInvestment(name, "ACOES", "ALTA", TODAY, null, null, "ACAO",
                null, null, ticker, null, null, null);
    }

    static int count(String table) throws Exception {
        try (Connection c = Database.newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

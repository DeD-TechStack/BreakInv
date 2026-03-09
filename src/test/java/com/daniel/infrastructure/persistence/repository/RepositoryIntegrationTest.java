package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.entity.Enums.FlowKind;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.infrastructure.persistence.config.Database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the four concrete JDBC repositories.
 *
 * Each test gets a fresh SQLite database (temp file) via Database.configure().
 * No production DB file is touched. Temp files are cleaned up by JUnit @TempDir.
 *
 * Note: in-memory SQLite is not used because the repositories close the
 * singleton connection after each statement (try-with-resources), and each
 * new :memory: connection is a fresh empty DB. A temp file persists across
 * reconnects within the same test.
 */
class RepositoryIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test.db").toString().replace("\\", "/");
        Database.configure("jdbc:sqlite:" + dbPath);
        Database.open(); // creates schema
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    // ===== FlowRepository =====

    @Test
    void flow_listForDate_emptyDb_returnsEmpty() {
        FlowRepository repo = new FlowRepository();
        List<Flow> flows = repo.listForDate(LocalDate.of(2024, 1, 1));
        assertNotNull(flows);
        assertTrue(flows.isEmpty());
    }

    @Test
    void flow_create_andListForDate_roundTrip() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);

        Flow f = new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 5000L, "deposit");
        long id = repo.create(f);
        assertTrue(id > 0);

        List<Flow> result = repo.listForDate(date);
        assertEquals(1, result.size());
        Flow loaded = result.get(0);
        assertEquals(date, loaded.date());
        assertEquals(FlowKind.CASH, loaded.fromKind());
        assertEquals(FlowKind.INVESTMENT, loaded.toKind());
        assertEquals(5000L, loaded.amountCents());
        assertEquals("deposit", loaded.note());
    }

    @Test
    void flow_listForDate_differentDate_returnsEmpty() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        Flow f = new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 1000L, null);
        repo.create(f);

        List<Flow> other = repo.listForDate(LocalDate.of(2024, 3, 8));
        assertTrue(other.isEmpty());
    }

    @Test
    void flow_delete_removesEntry() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        Flow f = new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 3000L, null);
        long id = repo.create(f);

        repo.delete(id);

        assertTrue(repo.listForDate(date).isEmpty());
    }

    @Test
    void flow_create_withNullNote_succeeds() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 6, 15);
        Flow f = new Flow(0, date, FlowKind.INVESTMENT, null, FlowKind.CASH, null, 2000L, null);
        long id = repo.create(f);
        assertTrue(id > 0);
        List<Flow> result = repo.listForDate(date);
        assertEquals(1, result.size());
        assertNull(result.get(0).note());
    }

    // ===== SnapshotRepository =====

    @Test
    void snapshot_getCash_noData_returnsZero() {
        SnapshotRepository repo = new SnapshotRepository();
        assertEquals(0L, repo.getCash(LocalDate.of(2024, 1, 1)));
    }

    @Test
    void snapshot_upsertCash_andGetCash_roundTrip() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        repo.upsertCash(date, 100000L);
        assertEquals(100000L, repo.getCash(date));
    }

    @Test
    void snapshot_upsertCash_overwrite_updatesValue() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        repo.upsertCash(date, 100000L);
        repo.upsertCash(date, 200000L);
        assertEquals(200000L, repo.getCash(date));
    }

    @Test
    void snapshot_upsertInvestment_andGetAll_roundTrip() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        repo.upsertInvestment(date, 1L, 50000L, null);
        repo.upsertInvestment(date, 2L, 75000L, "note");

        Map<Long, Long> all = repo.getAllInvestmentsForDate(date);
        assertEquals(2, all.size());
        assertEquals(50000L, all.get(1L));
        assertEquals(75000L, all.get(2L));
    }

    @Test
    void snapshot_upsertInvestment_overwrite_updatesValue() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        repo.upsertInvestment(date, 1L, 50000L, null);
        repo.upsertInvestment(date, 1L, 60000L, null);

        Map<Long, Long> all = repo.getAllInvestmentsForDate(date);
        assertEquals(60000L, all.get(1L));
    }

    @Test
    void snapshot_getAllInvestments_emptyDate_returnsEmpty() {
        SnapshotRepository repo = new SnapshotRepository();
        Map<Long, Long> all = repo.getAllInvestmentsForDate(LocalDate.of(2099, 1, 1));
        assertTrue(all.isEmpty());
    }

    @Test
    void snapshot_seriesForInvestment_multipleEntries_orderedByDate() {
        SnapshotRepository repo = new SnapshotRepository();
        repo.upsertInvestment(LocalDate.of(2024, 1, 1), 5L, 10000L, null);
        repo.upsertInvestment(LocalDate.of(2024, 2, 1), 5L, 11000L, null);
        repo.upsertInvestment(LocalDate.of(2024, 3, 1), 5L, 12000L, null);

        Map<String, Long> series = repo.seriesForInvestment(5L);
        assertEquals(3, series.size());
        List<String> keys = List.copyOf(series.keySet());
        assertTrue(keys.get(0).compareTo(keys.get(1)) < 0, "Dates should be in ascending order");
    }

    @Test
    void snapshot_seriesForInvestment_noData_returnsEmpty() {
        SnapshotRepository repo = new SnapshotRepository();
        assertTrue(repo.seriesForInvestment(99L).isEmpty());
    }

    // ===== InvestmentTypeRepository =====

    @Test
    void invType_listAll_emptyDb_returnsEmpty() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        assertTrue(repo.listAll().isEmpty());
    }

    @Test
    void invType_createFull_andListAll_roundTrip() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int id = repo.createFull(
                "Tesouro Selic", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 6, 1), BigDecimal.valueOf(12.0),
                BigDecimal.valueOf(1000.0), "POSFIXADO",
                "SELIC", BigDecimal.valueOf(100.0),
                null, null, null
        );
        assertTrue(id > 0);

        List<InvestmentType> all = repo.listAll();
        assertEquals(1, all.size());
        InvestmentType loaded = all.get(0);
        assertEquals("Tesouro Selic", loaded.name());
        assertEquals("RENDA_FIXA", loaded.category());
        assertEquals("ALTA", loaded.liquidity());
    }

    @Test
    void invType_rename_updatesName() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int id = repo.createFull("Old Name", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(500.0), "PREFIXADO",
                null, null, null, null, null);

        repo.rename(id, "New Name");

        InvestmentType loaded = repo.listAll().get(0);
        assertEquals("New Name", loaded.name());
    }

    @Test
    void invType_delete_removesEntry() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int id = repo.createFull("To Delete", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(100.0), "PREFIXADO",
                null, null, null, null, null);

        repo.delete(id);

        assertTrue(repo.listAll().isEmpty());
    }

    @Test
    void invType_updateFull_changesFields() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int id = repo.createFull("CDB", "RENDA_FIXA", "MEDIA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(10.0),
                BigDecimal.valueOf(1000.0), "PREFIXADO",
                null, null, null, null, null);

        repo.updateFull(id, "CDB Atualizado", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 6, 1), BigDecimal.valueOf(12.0),
                BigDecimal.valueOf(2000.0), "POSFIXADO",
                "SELIC", BigDecimal.valueOf(100.0),
                null, null, null);

        InvestmentType loaded = repo.listAll().get(0);
        assertEquals("CDB Atualizado", loaded.name());
        assertEquals("ALTA", loaded.liquidity());
        assertEquals(BigDecimal.valueOf(2000.0), loaded.investedValue());
    }

    @Test
    void invType_createFull_withTicker_storesTicker() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        repo.createFull("PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.of(2023, 1, 1), null,
                BigDecimal.valueOf(3000.0), "ACAO",
                null, null, "PETR4", BigDecimal.valueOf(30.0), 100);

        InvestmentType loaded = repo.listAll().get(0);
        assertEquals("PETR4", loaded.ticker());
        assertEquals(100, loaded.quantity());
    }

    // ===== TransactionRepository =====

    @Test
    void tx_insert_andListBetween_roundTrip() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        Transaction t = new Transaction(0, date, 1, Transaction.BUY,
                "PETR4", "PETR4", 10, 3000L, 30000L, "compra inicial");

        long id = repo.insert(t);
        assertTrue(id > 0);

        List<Transaction> result = repo.listBetween(date, date);
        assertEquals(1, result.size());
        Transaction loaded = result.get(0);
        assertEquals(Transaction.BUY, loaded.type());
        assertEquals("PETR4", loaded.ticker());
        assertEquals(10, loaded.quantity());
        assertEquals(3000L, loaded.unitPriceCents());
        assertEquals(30000L, loaded.totalCents());
        assertEquals("compra inicial", loaded.note());
    }

    @Test
    void tx_listBetween_outsideRange_returnsEmpty() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 3, 7);
        Transaction t = new Transaction(0, date, 1, Transaction.BUY,
                "VALE3", "VALE3", 5, 6000L, 30000L, null);
        repo.insert(t);

        List<Transaction> before = repo.listBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 6));
        assertTrue(before.isEmpty());

        List<Transaction> after = repo.listBetween(LocalDate.of(2024, 3, 8), LocalDate.of(2024, 12, 31));
        assertTrue(after.isEmpty());
    }

    @Test
    void tx_insert_returnsGeneratedId() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 6, 1);
        Transaction t1 = new Transaction(0, date, 1, Transaction.BUY, "A", "A", 1, 100L, 100L, null);
        Transaction t2 = new Transaction(0, date, 1, Transaction.SELL, "A", "A", 1, 110L, 110L, null);

        long id1 = repo.insert(t1);
        long id2 = repo.insert(t2);

        assertTrue(id1 > 0);
        assertTrue(id2 > id1);
    }

    @Test
    void tx_listBetween_multipleDates_onlyReturnsInRange() {
        TransactionRepository repo = new TransactionRepository();
        repo.insert(new Transaction(0, LocalDate.of(2024, 2, 1), 1, Transaction.BUY, "X", "X", 1, 100L, 100L, null));
        repo.insert(new Transaction(0, LocalDate.of(2024, 3, 7), 1, Transaction.BUY, "X", "X", 2, 100L, 200L, null));
        repo.insert(new Transaction(0, LocalDate.of(2024, 4, 1), 1, Transaction.BUY, "X", "X", 3, 100L, 300L, null));

        List<Transaction> march = repo.listBetween(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31));
        assertEquals(1, march.size());
        assertEquals(2, march.get(0).quantity());
    }

    @Test
    void tx_insert_sellType_roundTrip() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 5, 10);
        Transaction t = new Transaction(0, date, 2, Transaction.SELL,
                "BBAS3", "BBAS3", 20, 5000L, 100000L, "venda parcial");
        repo.insert(t);

        List<Transaction> result = repo.listBetween(date, date);
        assertEquals(1, result.size());
        assertEquals(Transaction.SELL, result.get(0).type());
    }

    // ===== FlowRepository — additional scenarios =====

    @Test
    void flow_create_multipleFlows_sameDate_orderedByIdAsc() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 4, 1);
        long id1 = repo.create(new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 1000L, "first"));
        long id2 = repo.create(new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 2000L, "second"));

        List<Flow> result = repo.listForDate(date);
        assertEquals(2, result.size());
        assertEquals(id1, result.get(0).id());
        assertEquals(id2, result.get(1).id());
        assertEquals("first",  result.get(0).note());
        assertEquals("second", result.get(1).note());
    }

    @Test
    void flow_create_investmentToInvestment_withTypeIds_roundTrip() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 4, 1);
        // SQLite does not enforce FK by default, so arbitrary type IDs are fine
        Flow f = new Flow(0, date, FlowKind.INVESTMENT, 1L, FlowKind.INVESTMENT, 2L, 7500L, "rebalance");
        long id = repo.create(f);
        assertTrue(id > 0);

        Flow loaded = repo.listForDate(date).get(0);
        assertEquals(FlowKind.INVESTMENT, loaded.fromKind());
        assertEquals(1L, loaded.fromInvestmentTypeId());
        assertEquals(FlowKind.INVESTMENT, loaded.toKind());
        assertEquals(2L, loaded.toInvestmentTypeId());
        assertEquals(7500L, loaded.amountCents());
    }

    @Test
    void flow_delete_leavesOtherFlowsIntact() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 4, 1);
        long idA = repo.create(new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 100L, "A"));
        long idB = repo.create(new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 200L, "B"));

        repo.delete(idA);

        List<Flow> remaining = repo.listForDate(date);
        assertEquals(1, remaining.size());
        assertEquals(idB, remaining.get(0).id());
        assertEquals("B", remaining.get(0).note());
    }

    @Test
    void flow_create_returnsAscendingIds() {
        FlowRepository repo = new FlowRepository();
        LocalDate date = LocalDate.of(2024, 4, 1);
        long id1 = repo.create(new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 500L, null));
        long id2 = repo.create(new Flow(0, date, FlowKind.CASH, null, FlowKind.INVESTMENT, null, 600L, null));
        assertTrue(id2 > id1);
    }

    // ===== SnapshotRepository — additional scenarios =====

    @Test
    void snapshot_multipleDates_cashIsolatedPerDate() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate d1 = LocalDate.of(2024, 1, 1);
        LocalDate d2 = LocalDate.of(2024, 2, 1);
        repo.upsertCash(d1, 10000L);
        repo.upsertCash(d2, 20000L);

        assertEquals(10000L, repo.getCash(d1));
        assertEquals(20000L, repo.getCash(d2));
    }

    @Test
    void snapshot_upsertCash_zeroValue_persistsWithoutError() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate date = LocalDate.of(2024, 5, 1);
        // Should not throw; getCash returns 0 (same as "not found" by design)
        assertDoesNotThrow(() -> repo.upsertCash(date, 0L));
        assertEquals(0L, repo.getCash(date));
    }

    @Test
    void snapshot_upsertInvestment_dateIsolation() {
        SnapshotRepository repo = new SnapshotRepository();
        LocalDate d1 = LocalDate.of(2024, 1, 1);
        LocalDate d2 = LocalDate.of(2024, 2, 1);
        repo.upsertInvestment(d1, 10L, 50000L, null);

        // d2 has no data — should not see d1's investment
        assertTrue(repo.getAllInvestmentsForDate(d2).isEmpty());
    }

    @Test
    void snapshot_getAllInvestimentsForDate_viaInterfaceMethod() {
        // Verifies the @Override method with the intentional "Investiments" typo
        // correctly delegates to the concrete implementation.
        ISnapshotRepository iface = new SnapshotRepository();
        LocalDate date = LocalDate.of(2024, 6, 1);
        ((SnapshotRepository) iface).upsertInvestment(date, 7L, 33000L, null);

        Map<Long, Long> result = iface.getAllInvestimentsForDate(date);
        assertEquals(1, result.size());
        assertEquals(33000L, result.get(7L));
    }

    @Test
    void snapshot_seriesForInvestment_isolatedByInvestmentId() {
        SnapshotRepository repo = new SnapshotRepository();
        repo.upsertInvestment(LocalDate.of(2024, 1, 1), 1L, 10000L, null);
        repo.upsertInvestment(LocalDate.of(2024, 2, 1), 2L, 20000L, null);

        // Series for id=1 should not contain id=2's data
        Map<String, Long> seriesId1 = repo.seriesForInvestment(1L);
        assertEquals(1, seriesId1.size());
        assertEquals(10000L, seriesId1.get("2024-01-01"));

        // Series for id=2 should not contain id=1's data
        Map<String, Long> seriesId2 = repo.seriesForInvestment(2L);
        assertEquals(1, seriesId2.size());
        assertEquals(20000L, seriesId2.get("2024-02-01"));
    }

    // ===== InvestmentTypeRepository — additional scenarios =====

    @Test
    void invType_listAll_orderedByName() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        repo.createFull("Zeta Fund", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(100.0), "PREFIXADO", null, null, null, null, null);
        repo.createFull("Alpha CDB", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(200.0), "PREFIXADO", null, null, null, null, null);

        List<InvestmentType> all = repo.listAll();
        assertEquals(2, all.size());
        assertEquals("Alpha CDB",  all.get(0).name());
        assertEquals("Zeta Fund", all.get(1).name());
    }

    @Test
    void invType_createFull_nullDate_roundTrip() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        repo.createFull("CDB Sem Data", "RENDA_FIXA", "MEDIA",
                null, BigDecimal.valueOf(10.0), BigDecimal.valueOf(500.0),
                "PREFIXADO", null, null, null, null, null);

        InvestmentType loaded = repo.listAll().get(0);
        assertNull(loaded.investmentDate(), "investmentDate should round-trip as null");
    }

    @Test
    void invType_delete_leavesOtherTypesIntact() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int idA = repo.createFull("TypeA", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(100.0), "PREFIXADO", null, null, null, null, null);
        repo.createFull("TypeB", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(200.0), "PREFIXADO", null, null, null, null, null);

        repo.delete(idA);

        List<InvestmentType> remaining = repo.listAll();
        assertEquals(1, remaining.size());
        assertEquals("TypeB", remaining.get(0).name());
    }

    @Test
    void invType_createFull_multipleIds_ascending() {
        InvestmentTypeRepository repo = new InvestmentTypeRepository();
        int id1 = repo.createFull("First", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(100.0), "PREFIXADO", null, null, null, null, null);
        int id2 = repo.createFull("Second", "RENDA_FIXA", "ALTA",
                null, null, BigDecimal.valueOf(200.0), "PREFIXADO", null, null, null, null, null);
        assertTrue(id2 > id1);
    }

    // ===== TransactionRepository — additional scenarios =====

    @Test
    void tx_insert_nullQuantity_roundTrip() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 7, 1);
        Transaction t = new Transaction(0, date, 1, Transaction.BUY,
                "CDB", null, null, null, 50000L, null);
        repo.insert(t);

        Transaction loaded = repo.listBetween(date, date).get(0);
        assertNull(loaded.quantity(), "null quantity should round-trip as null");
    }

    @Test
    void tx_insert_nullUnitPrice_roundTrip() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 7, 2);
        Transaction t = new Transaction(0, date, 1, Transaction.BUY,
                "LCI", null, 10, null, 30000L, null);
        repo.insert(t);

        Transaction loaded = repo.listBetween(date, date).get(0);
        assertNull(loaded.unitPriceCents(), "null unitPriceCents should round-trip as null");
    }

    @Test
    void tx_listBetween_inclusiveBoundaries() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate start = LocalDate.of(2024, 3, 1);
        LocalDate end   = LocalDate.of(2024, 3, 31);
        repo.insert(new Transaction(0, start, 1, Transaction.BUY, "X", "X", 1, 100L, 100L, null));
        repo.insert(new Transaction(0, end,   1, Transaction.BUY, "X", "X", 2, 100L, 200L, null));

        List<Transaction> result = repo.listBetween(start, end);
        assertEquals(2, result.size());
    }

    @Test
    void tx_listBetween_sameDate_orderedDescById() {
        TransactionRepository repo = new TransactionRepository();
        LocalDate date = LocalDate.of(2024, 8, 1);
        long id1 = repo.insert(new Transaction(0, date, 1, Transaction.BUY,  "A", "A", 1, 100L, 100L, null));
        long id2 = repo.insert(new Transaction(0, date, 1, Transaction.SELL, "A", "A", 1, 110L, 110L, null));

        List<Transaction> result = repo.listBetween(date, date);
        // ORDER BY date DESC, id DESC → higher id first
        assertEquals(id2, result.get(0).id());
        assertEquals(id1, result.get(1).id());
    }

    @Test
    void tx_listBetween_largeRange_returnsAll() {
        TransactionRepository repo = new TransactionRepository();
        repo.insert(new Transaction(0, LocalDate.of(2020, 1, 1), 1, Transaction.BUY, "X", "X", 1, 10L, 10L, null));
        repo.insert(new Transaction(0, LocalDate.of(2022, 6, 15), 1, Transaction.BUY, "X", "X", 2, 10L, 20L, null));
        repo.insert(new Transaction(0, LocalDate.of(2024, 12, 31), 1, Transaction.BUY, "X", "X", 3, 10L, 30L, null));

        List<Transaction> all = repo.listBetween(LocalDate.of(2000, 1, 1), LocalDate.of(2099, 12, 31));
        assertEquals(3, all.size());
    }
}

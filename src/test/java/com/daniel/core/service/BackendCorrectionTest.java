package com.daniel.core.service;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.Position;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.PriceSource;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the independent backend review (findings L1–L5) plus the transaction
 * boundary of the fixed-connection store. Each test reproduces the reported defect exactly.
 */
class BackendCorrectionTest {

    static final LocalDate D1 = LocalDate.of(2026, 3, 1);
    static final LocalDate D2 = LocalDate.of(2026, 3, 2);
    static final LocalDate D3 = LocalDate.of(2026, 3, 3);
    static final BigDecimal P10 = new BigDecimal("10");
    static final BigDecimal P20 = new BigDecimal("20");
    static final BigDecimal P30 = new BigDecimal("30");

    @TempDir
    Path tempDir;
    PortfolioService svc;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("correction.db").toString().replace("\\", "/"));
        Database.open();
        svc = PortfolioService.onDatabase();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    // =====================================================================
    // L1 — legacy SELL/BUY with quantity NULL must flag the proposal
    // =====================================================================

    @Test
    void L1_legacyTotalSellWithNullQuantity_proposalIsFlagged_notInferred() {
        long a = investment("LEG", 10, P20, null);
        legacyTx(a, "BUY", 10, 20_000L);
        legacyTx(a, "SELL", null, 2_500L); // UI "deixe em branco para venda total"

        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertTrue(p.reconciliationFlag(), "sell with NULL quantity makes the legacy balance unknowable");
        assertEquals(10L, p.quantity(), "registration value preserved, history not inferred");
        assertEquals(20_000L, p.costCents());
        assertTrue(p.note().toLowerCase().contains("quantidade"), p.note());

        svc.registerOpening(p);
        assertEquals(java.util.List.of(a), svc.pendingReconciliations());
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        assertEquals("reconciliation", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s", D2, a, 1, P20, null))).field());
    }

    @Test
    void L1_legacyBuyWithNullQuantity_alsoFlagged() {
        long a = investment("LEG2", 5, P20, null);
        legacyTx(a, "BUY", null, 10_000L);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertTrue(p.reconciliationFlag());
        assertEquals(5L, p.quantity());
    }

    @Test
    void L1_priceWithoutQuantity_isFlagged_neverSilentZero() {
        long a = investment("SoPreco", null, P20, null);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(0L, p.costCents(), "nothing invented");
        assertTrue(p.reconciliationFlag(), "price without quantity: cost not derivable, must be confirmed");
    }

    // =====================================================================
    // L2 — reversal of BUY after a SELL is not reconstructible; qty 0 ⇒ cost 0
    // =====================================================================

    @Test
    void L2_reversalOfBuyFollowedBySell_rejected_stateUntouched() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        Movement buyA = svc.executeMovement(MovementRequest.buy("bA", D1, a, 10, P20, null));
        svc.executeMovement(MovementRequest.buy("bB", D1, a, 10, P30, null));
        svc.executeMovement(MovementRequest.sell("s", D2, a, 10, P30, null));

        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D3, buyA.id(), "compra errada")));
        assertEquals("reversesId", ex.field());

        PortfolioState s = svc.state();
        assertEquals(10L, s.position(a).quantity());
        assertEquals(25_000L, s.position(a).costCents());
        assertEquals(5_000L, s.realizedGainCents());
        assertEquals(80_000L, s.cashCents());
        assertEquals(5, svc.history().size(), "nothing written");
        PortfolioMetrics m = svc.metrics(PriceSource.none());
        assertEquals(105_000L, m.wealthCents(), "no phantom cost: 80.000 cash + 25.000 cost");
    }

    @Test
    void L2_reversalOfBuyFollowedOnlyByBuys_isExactAndAllowed() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        Movement buyA = svc.executeMovement(MovementRequest.buy("bA", D1, a, 10, P20, null));
        svc.executeMovement(MovementRequest.buy("bB", D1, a, 10, P30, null));
        svc.executeMovement(MovementRequest.reversal("r", D2, buyA.id(), "duplicada"));
        assertEquals(10L, svc.position(a).quantity());
        assertEquals(30_000L, svc.position(a).costCents());
        assertEquals(70_000L, svc.state().cashCents());
    }

    @Test
    void L2_reversalOfSellFollowedByAnotherSell_isAllowed_averageUnchanged() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D1, a, 10, P20, null));
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 2, P30, null));
        svc.executeMovement(MovementRequest.sell("s2", D2, a, 2, P30, null));
        svc.executeMovement(MovementRequest.reversal("r", D3, s1.id(), "venda errada"));
        assertEquals(8L, svc.position(a).quantity());
        assertEquals(16_000L, svc.position(a).costCents());
        assertEquals(2_000L, svc.state().realizedGainCents(), "only s2's gain remains");
    }

    @Test
    void L2_reversalOfCostOnlyAdjustmentFollowedBySell_rejected() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D1, a, 10, P20, null));
        Movement adj = svc.executeMovement(MovementRequest.adjustPosition("adj", D1, a, 0L, 1_000L,
                AdjustmentClassification.CORRECTION, "custo de corretagem", null));
        svc.executeMovement(MovementRequest.sell("s", D2, a, 5, P30, null));
        assertEquals("reversesId", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D3, adj.id(), "x"))).field());
        assertEquals(10_500L, svc.position(a).costCents(), "half of 21.000 remains; untouched");
    }

    @Test
    void L2_openingOrReconciliation_zeroQuantityWithCost_rejectedAtRequest() {
        long a = openedQuantity("A", D1);
        assertEquals("cost", assertThrows(LedgerException.class, () -> OpeningRequest.existingPosition(
                "o", D1, 99L, PositionMode.QUANTITY, 0L, 500L, false, null)).field());
        assertEquals("cost", assertThrows(LedgerException.class, () -> ReconciliationRequest.position(
                "rc", D2, a, 0L, 500L, "motivo")).field());
    }

    @Test
    void L2_adjustmentLeavingZeroQuantityWithCost_rejected_zeroCostAccepted() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D1, a, 5, P10, null));
        LedgerException ex = assertThrows(LedgerException.class, () -> svc.executeMovement(
                MovementRequest.adjustPosition("bad", D2, a, -5L, 0L, AdjustmentClassification.CORRECTION, "zera só quantidade", null)));
        assertEquals("cost", ex.field());
        assertEquals(5L, svc.position(a).quantity());

        svc.executeMovement(MovementRequest.adjustPosition("ok", D2, a, -5L, -5_000L, AdjustmentClassification.CORRECTION, "baixa total", null));
        assertEquals(0L, svc.position(a).quantity());
        assertEquals(0L, svc.position(a).costCents());
    }

    // =====================================================================
    // L3 — RECONCILIATION dated before the OPENING must not bypass the opening barrier
    // =====================================================================

    @Test
    void L3_cashReconciliationBeforeCashOpening_rejected_depositStillBlocked() {
        svc.registerOpening(OpeningRequest.cash("oc", D3, 100_000L, false, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.reconcile(ReconciliationRequest.cash("rc", D1, 0L, "x")));
        assertEquals("date", ex.field());
        assertEquals("date", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.deposit("d", D2, 50_000L, null))).field());
        PortfolioState s = svc.state();
        assertEquals(100_000L, s.cashCents());
        assertEquals(0L, s.externalNetFlowCents());
        assertEquals(1, svc.history().size());
    }

    @Test
    void L3_positionReconciliationBeforeOpening_rejected_buyStillBlocked() {
        long a = investment("A", null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("o", D3, a, PositionMode.QUANTITY, null));
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.reconcile(ReconciliationRequest.position("rc", D1, a, 0L, 0L, "x")));
        assertEquals("date", ex.field());
        assertEquals("date", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b", D2, a, 10, P20, null))).field());
        assertEquals(0L, svc.state().openingCapitalCents());
        assertEquals(2, svc.history().size(), "opening + deposit only");
    }

    @Test
    void L3_cashReconciliationWithoutAnyCashOpening_rejected() {
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.reconcile(ReconciliationRequest.cash("rc", D1, 500L, "x")));
        assertEquals("opening", ex.field());
        assertFalse(svc.state().cashOpened());
    }

    // =====================================================================
    // L4 — INCOME must be positive, EXPENSE negative
    // =====================================================================

    @Test
    void L4_incomeMustBePositive_expenseMustBeNegative() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        assertEquals("amount", assertThrows(LedgerException.class, () -> svc.executeMovement(
                MovementRequest.adjustCash("e+", D1, 500L, AdjustmentClassification.EXPENSE, "tarifa", null))).field());
        assertEquals("amount", assertThrows(LedgerException.class, () -> svc.executeMovement(
                MovementRequest.adjustCash("i-", D1, -500L, AdjustmentClassification.INCOME, "juros", null))).field());
        assertEquals("amount", assertThrows(LedgerException.class, () -> svc.executeMovement(
                MovementRequest.adjustPosition("pe+", D1, a, 0L, 100L, AdjustmentClassification.EXPENSE, "taxa", null))).field());

        svc.executeMovement(MovementRequest.adjustCash("e", D1, -500L, AdjustmentClassification.EXPENSE, "tarifa", null));
        svc.executeMovement(MovementRequest.adjustCash("i", D1, 300L, AdjustmentClassification.INCOME, "juros", null));
        PortfolioState s = svc.state();
        assertEquals(9_800L, s.cashCents());
        assertEquals(-200L, s.realizedGainCents());
    }

    // =====================================================================
    // L5 — reversal of a negative-quantity adjustment must be storable
    // =====================================================================

    @Test
    void L5_reversalOfNegativeQuantityAdjustment_restoresExactPosition() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D1, a, 5, P10, null));
        Movement adj = svc.executeMovement(MovementRequest.adjustPosition("adj", D2, a, -2L, -100L,
                AdjustmentClassification.CORRECTION, "erro", null));
        assertEquals(3L, svc.position(a).quantity());
        assertEquals(4_900L, svc.position(a).costCents());

        Movement rev = svc.executeMovement(MovementRequest.reversal("rev", D3, adj.id(), "ajuste indevido"));
        assertEquals(MovementKind.REVERSAL, rev.kind());
        assertEquals(adj.id(), rev.reversesId());
        assertNull(rev.quantity(), "reversal row carries no quantity; replay inverts the original's effect");
        assertNull(rev.unitPrice());
        assertEquals(5L, svc.position(a).quantity());
        assertEquals(5_000L, svc.position(a).costCents());
        assertEquals(0L, svc.state().realizedGainCents());
    }

    @Test
    void L5_reversalTwice_rejectedWithLedgerFieldNotSqlError() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        Movement buy = svc.executeMovement(MovementRequest.buy("b", D1, a, 1, P10, null));
        svc.executeMovement(MovementRequest.reversal("r1", D2, buy.id(), "x"));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r2", D2, buy.id(), "de novo")));
        assertEquals("reversesId", ex.field());
    }

    // =====================================================================
    // Fixed-connection store: caller-owned transaction and Throwable safety
    // =====================================================================

    @Test
    void store_joinsCallerOwnedTransaction_withoutCommittingIt() throws Exception {
        try (Connection c = Database.newConnection()) {
            c.setAutoCommit(false);
            long id = insertInvestment(c, "Atomico");
            PortfolioService onConn = PortfolioService.onConnection(c);
            onConn.registerOpening(OpeningRequest.newInvestment("o", D1, id, PositionMode.QUANTITY, null));
            assertFalse(c.getAutoCommit(), "store must not change the caller's transaction mode");
            c.rollback();
        }
        assertEquals(0, count("investment_type"), "caller rollback must undo registration");
        assertEquals(0, count("portfolio_movement"), "…and the opening written through the store");
    }

    @Test
    void store_rejectedWorkRollsBackOnlyItsOwnSavepoint_callerTransactionSurvives() throws Exception {
        try (Connection c = Database.newConnection()) {
            c.setAutoCommit(false);
            insertInvestment(c, "Atomico");
            PortfolioService onConn = PortfolioService.onConnection(c);
            onConn.executeMovement(MovementRequest.deposit("d", D1, 100L, null));
            LedgerException ex = assertThrows(LedgerException.class,
                    () -> onConn.executeMovement(MovementRequest.withdrawal("w", D1, 200L, null)));
            assertEquals("cash", ex.field());
            assertFalse(c.getAutoCommit(), "caller still owns an open transaction");
            c.commit();
        }
        assertEquals(1, count("investment_type"));
        assertEquals(1, count("portfolio_movement"), "deposit kept; rejected withdrawal absent");
    }

    @Test
    void store_errorThrownByWork_rollsBackInsteadOfCommittingInFinally() throws Exception {
        try (Connection c = Database.newConnection()) {
            DatabaseLedgerStore store = new DatabaseLedgerStore(c);
            Movement row = new Movement(0, "k", D1, MovementKind.DEPOSIT, null, null, null, null, 100L,
                    null, null, null, null, false, "USER", Instant.now());
            assertThrows(AssertionError.class, () -> store.transact(repo -> {
                repo.insert(row, "hash");
                throw new AssertionError("boom");
            }));
            assertTrue(c.getAutoCommit(), "auto-commit restored");
        }
        assertEquals(0, count("portfolio_movement"), "Error must roll back, not commit via setAutoCommit(true)");
    }

    // =====================================================================
    // Arithmetic / range hygiene
    // =====================================================================

    @Test
    void totalCostCents_overflow_rejectedChecked() {
        PortfolioState s = new PortfolioState(0L, true, false, Map.of(
                1L, new Position(1L, PositionMode.VALUE, 0L, Long.MAX_VALUE, false),
                2L, new Position(2L, PositionMode.VALUE, 0L, 1L, false)), 0L, 0L, 0L);
        assertThrows(LedgerException.class, s::totalCostCents);
    }

    @Test
    void requests_rejectDatesOutsideLexicallySortableRange() {
        LocalDate far = LocalDate.of(10_000, 1, 1);
        assertEquals("date", assertThrows(LedgerException.class,
                () -> MovementRequest.deposit("d", far, 1L, null)).field());
        assertEquals("date", assertThrows(LedgerException.class,
                () -> OpeningRequest.cash("o", far, 0L, false, null)).field());
        assertEquals("date", assertThrows(LedgerException.class,
                () -> ReconciliationRequest.cash("r", far, 0L, "x")).field());
    }

    // =====================================================================
    // helpers
    // =====================================================================

    long openedQuantity(String name, LocalDate date) {
        long id = investment(name, null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("open-" + id, date, id, PositionMode.QUANTITY, null));
        return id;
    }

    static long investment(String name, Integer qty, BigDecimal price, BigDecimal invested) {
        try (Connection c = Database.newConnection();
             var ps = c.prepareStatement("INSERT INTO investment_type (name, quantity, purchase_price, invested_value) VALUES (?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            if (qty == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setInt(2, qty);
            if (price == null) ps.setNull(3, java.sql.Types.REAL); else ps.setBigDecimal(3, price);
            if (invested == null) ps.setNull(4, java.sql.Types.REAL); else ps.setBigDecimal(4, invested);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static long insertInvestment(Connection c, String name) throws SQLException {
        try (var ps = c.prepareStatement("INSERT INTO investment_type (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    static void legacyTx(long inv, String type, Integer qty, long totalCents) {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO transactions (date, investment_type_id, type, name, quantity, total_cents) VALUES ('2025-01-01', "
                    + inv + ", '" + type + "', 'legacy', " + (qty == null ? "NULL" : qty.toString()) + ", " + totalCents + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static int count(String table) {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}

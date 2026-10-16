package com.daniel.core.service;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.domain.ledger.PriceSource;
import com.daniel.core.domain.ledger.ReconciliationRequest;
import com.daniel.infrastructure.persistence.config.Database;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ledger rules beyond the numeric scenario: VALUE-mode investments, adjustments, reversals,
 * retroactive entries, legacy recognition/reconciliation, archiving and the delete guard.
 */
class PortfolioServiceRulesTest {

    static final LocalDate D1 = LocalDate.of(2026, 2, 1);
    static final LocalDate D2 = LocalDate.of(2026, 2, 2);
    static final LocalDate D3 = LocalDate.of(2026, 2, 3);
    static final BigDecimal P10 = new BigDecimal("10");

    @TempDir
    Path tempDir;
    PortfolioService svc;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("rules.db").toString().replace("\\", "/"));
        Database.open();
        svc = PortfolioService.onDatabase();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    // ===== VALUE mode =====

    @Test
    void valueMode_buyAddsCost_sellRedeemsSpecifiedCost_noGainInferred() {
        long cdb = investment("CDB", null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("o", D1, cdb, PositionMode.VALUE, null));
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buyValue("b", D1, cdb, 60_000L, null));
        svc.executeMovement(MovementRequest.sellValue("s", D2, cdb, 25_000L, "resgate parcial"));

        PortfolioState s = svc.state();
        assertEquals(65_000L, s.cashCents());
        assertEquals(35_000L, s.position(cdb).costCents());
        assertEquals(0L, s.position(cdb).quantity());
        assertEquals(0L, s.realizedGainCents(), "VALUE mode never infers realized gain");

        PortfolioMetrics m = svc.metrics(Map.of(cdb, new BigDecimal("360.00")));
        assertEquals(36_000L, m.positionValueCents(), "VALUE mode price source is total value in BRL");
        assertEquals(1_000L, m.unrealizedGainCents());
    }

    @Test
    void valueMode_sellAboveCost_rejected() {
        long cdb = investment("CDB", null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("o", D1, cdb, PositionMode.VALUE, null));
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        svc.executeMovement(MovementRequest.buyValue("b", D1, cdb, 30_000L, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sellValue("s", D2, cdb, 30_001L, null)));
        assertEquals("amount", ex.field());
        assertEquals(30_000L, svc.position(cdb).costCents());
    }

    @Test
    void valueMode_quantityOrPrice_rejected() {
        long cdb = investment("CDB", null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("o", D1, cdb, PositionMode.VALUE, null));
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b", D1, cdb, 3, P10, null)));
        assertEquals("quantity", ex.field());
    }

    // ===== validation =====

    @Test
    void quantityMode_zeroOrNegativeQuantity_andNonPositivePrice_rejected() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        assertEquals("quantity", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b0", D1, a, 0, P10, null))).field());
        assertEquals("quantity", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b1", D1, a, -2, P10, null))).field());
        assertEquals("unitPrice", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b2", D1, a, 2, BigDecimal.ZERO, null))).field());
        assertEquals("amount", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.deposit("d0", D1, 0L, null))).field());
        assertEquals("cash", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.withdrawal("w", D1, 50_001L, null))).field());
        assertEquals(1, svc.history().stream().filter(m -> m.kind() != MovementKind.OPENING).count());
    }

    @Test
    void movement_beforeOpening_rejected() {
        long a = investment("A", null, null, null);
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b", D1, a, 1, P10, null)));
        assertEquals("opening", ex.field());
    }

    @Test
    void sell_totalOverflow_rejectedChecked() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 1_000L, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b", D1, a, 1, new BigDecimal("99999999999999999999"), null)));
        assertEquals("unitPrice", ex.field());
    }

    // ===== adjustment =====

    @Test
    void adjustment_requiresClassificationAndReason_incomeCountsAsRealized_correctionDoesNot() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));

        assertEquals("reason", assertThrows(LedgerException.class, () -> svc.executeMovement(
                MovementRequest.adjustCash("x", D1, 500L, AdjustmentClassification.INCOME, "  ", null))).field());
        assertEquals("classification", assertThrows(LedgerException.class, () -> svc.executeMovement(
                MovementRequest.adjustCash("y", D1, 500L, null, "juros", null))).field());

        svc.executeMovement(MovementRequest.adjustCash("inc", D1, 500L, AdjustmentClassification.INCOME, "juros do caixa", null));
        svc.executeMovement(MovementRequest.adjustCash("fix", D1, -200L, AdjustmentClassification.CORRECTION, "erro de digitação", null));
        svc.executeMovement(MovementRequest.adjustPosition("pos", D1, a, 2L, 1_000L, AdjustmentClassification.CORRECTION, "bonificação registrada", null));

        PortfolioState s = svc.state();
        assertEquals(10_300L, s.cashCents());
        assertEquals(500L, s.realizedGainCents());
        assertEquals(10_000L, s.externalNetFlowCents(), "adjustments are not external flows");
        assertEquals(2L, s.position(a).quantity());
        assertEquals(1_000L, s.position(a).costCents());
    }

    // ===== reversal =====

    @Test
    void reversal_invertsOriginal_keepsBothRows_linked_noDelete() throws Exception {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        Movement buy = svc.executeMovement(MovementRequest.buy("b", D1, a, 3, P10, null));
        Movement rev = svc.executeMovement(MovementRequest.reversal("r", D2, buy.id(), "compra lançada em duplicidade"));

        assertEquals(MovementKind.REVERSAL, rev.kind());
        assertEquals(buy.id(), rev.reversesId());
        assertEquals("compra lançada em duplicidade", rev.reason());
        PortfolioState s = svc.state();
        assertEquals(50_000L, s.cashCents());
        assertEquals(0L, s.position(a).quantity());
        assertEquals(0L, s.position(a).costCents());
        assertEquals(3, svc.history(a).size(), "opening + buy + reversal all kept");
        assertTrue(svc.findByIntentKey("b").isPresent());
    }

    @Test
    void reversal_twiceOrWithoutReason_rejected() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        Movement buy = svc.executeMovement(MovementRequest.buy("b", D1, a, 3, P10, null));
        assertEquals("reason", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r0", D2, buy.id(), null))).field());
        svc.executeMovement(MovementRequest.reversal("r1", D2, buy.id(), "motivo"));
        assertThrows(RuntimeException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r2", D2, buy.id(), "de novo")));
        assertEquals(50_000L, svc.state().cashCents());
    }

    @Test
    void reversal_thatWouldBreakLaterState_rejected() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        Movement buy = svc.executeMovement(MovementRequest.buy("b", D1, a, 3, P10, null));
        svc.executeMovement(MovementRequest.sell("s", D2, a, 3, P10, null));
        // reversing a BUY after a SELL is not reconstructible (the sell's released cost used it):
        // rejected up-front with field reversesId, pointing to reconciliation; nothing written
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D3, buy.id(), "teste")));
        assertEquals("reversesId", ex.field());
        assertTrue(ex.getMessage().contains("reconcilia"), ex.getMessage());
        assertEquals(4, svc.history().size());
    }

    // ===== retroactive =====

    @Test
    void retroactive_isReplayedInDateOrder_notAsToday() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D3, a, 5, P10, null));
        // retroactive sell on D2 — before the buy exists → whole sequence invalid
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s", D2, a, 1, P10, null)));
        assertEquals("quantity", ex.field());
        assertTrue(svc.findByIntentKey("s").isEmpty(), "nothing written");

        // retroactive buy on D2 is valid and ordered before D3
        svc.executeMovement(MovementRequest.buy("b2", D2, a, 1, P10, null));
        List<Movement> h = svc.history(a);
        assertEquals(List.of(D1, D2, D3), h.stream().map(Movement::date).toList());
        assertEquals(6L, svc.position(a).quantity());
    }

    @Test
    void retroactive_beforeOpeningMarker_rejectedExplicitly() {
        long a = investment("A", null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("o", D2, a, PositionMode.QUANTITY, null));
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("b", D1, a, 1, P10, null)));
        assertEquals("date", ex.field());
        assertTrue(ex.getMessage().contains("abertura"));
    }

    // ===== legacy recognition =====

    @Test
    void proposeOpening_fromConsistentLegacy_quantityTimesPriceHalfUp_notFlagged() {
        long a = investment("PETR4", 10, new BigDecimal("30.005"), new BigDecimal("300.05"));
        legacyTx(a, "BUY", 10, 30_005L);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(PositionMode.QUANTITY, p.positionMode());
        assertEquals(10L, p.quantity());
        assertEquals(30_005L, p.costCents(), "10 × 30.005 = 300.05 → HALF_UP once");
        assertFalse(p.reconciliationFlag());
        assertTrue(svc.history().isEmpty(), "proposal writes nothing");
    }

    @Test
    void proposeOpening_fromAmbiguousLegacy_isFlagged_andBlocksMovementsUntilReconciled() {
        long a = investment("VALE3", 8, new BigDecimal("60"), null);
        legacyTx(a, "BUY", 5, 30_000L); // 5 ≠ 8 → ambiguous
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertTrue(p.reconciliationFlag());
        assertEquals(8L, p.quantity());
        assertEquals(48_000L, p.costCents());

        svc.registerOpening(p);
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        assertEquals(List.of(a), svc.pendingReconciliations());
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s", D2, a, 1, P10, null)));
        assertEquals("reconciliation", ex.field());

        svc.reconcile(ReconciliationRequest.position("rc", D2, a, 8L, 48_000L, "conferido na corretora"));
        assertTrue(svc.pendingReconciliations().isEmpty());
        svc.executeMovement(MovementRequest.sell("s2", D2, a, 1, P10, null));
        assertEquals(7L, svc.position(a).quantity());
        assertEquals(42_000L, svc.position(a).costCents());
    }

    @Test
    void proposeOpening_valueOnlyLegacy_usesValueMode_nullsStayAbsent() {
        long a = investment("Tesouro", null, null, new BigDecimal("1500.555"));
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(PositionMode.VALUE, p.positionMode());
        assertNull(p.quantity());
        assertEquals(150_056L, p.costCents(), "HALF_UP, not truncated");
        assertFalse(p.reconciliationFlag());

        long b = investment("Vazio", null, null, null);
        OpeningRequest q = svc.proposeOpening("o2", D1, b);
        assertEquals(0L, q.costCents());
        assertFalse(q.reconciliationFlag());
    }

    @Test
    void legacyTransactions_areNeverReplayedIntoTheLedger() {
        long a = investment("X", 10, P10, null);
        legacyTx(a, "BUY", 10, 10_000L);
        legacyTx(a, "SELL", 4, 5_000L);
        svc.registerOpening(svc.proposeOpening("o", D1, a));
        assertEquals(1, svc.history().size());
        assertEquals(MovementKind.OPENING, svc.history().get(0).kind());
        assertEquals(10L, svc.position(a).quantity());
        assertEquals(0L, svc.state().realizedGainCents());
    }

    @Test
    void legacyCash_withoutOpening_blocksCashMovements_untilCashOpeningRegistered() throws Exception {
        legacyCash("2025-12-31", 12_345L);
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.deposit("d", D1, 100L, null)));
        assertEquals("opening", ex.field());

        OpeningRequest cash = svc.proposeCashOpening("oc", D1);
        assertEquals(12_345L, cash.costCents());
        assertTrue(cash.reconciliationFlag());
        svc.registerOpening(cash);
        assertEquals("reconciliation", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.deposit("d2", D1, 100L, null))).field());

        svc.reconcile(ReconciliationRequest.cash("rc", D1, 12_345L, "saldo conferido"));
        svc.executeMovement(MovementRequest.deposit("d3", D1, 100L, null));
        PortfolioState s = svc.state();
        assertEquals(12_445L, s.cashCents());
        assertEquals(100L, s.externalNetFlowCents(), "opening cash is not an external flow");
        assertEquals(12_345L, s.openingCapitalCents());
    }

    @Test
    void registerOpening_twiceForSameInvestment_rejected() {
        long a = openedQuantity("A");
        LedgerException ex = assertThrows(LedgerException.class, () -> svc.registerOpening(
                OpeningRequest.existingPosition("o2", D1, a, PositionMode.QUANTITY, 5L, 100L, false, null)));
        assertEquals("opening", ex.field());
    }

    // ===== archive / delete guard =====

    @Test
    void archive_keepsHistoryAndPosition_blocksNewMovements_unarchiveRestores() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D1, a, 3, P10, null));

        svc.archive(a);
        assertTrue(svc.investmentInfo(a).orElseThrow().archived());
        assertEquals(3L, svc.position(a).quantity());
        assertEquals(2, svc.history(a).size());
        assertEquals(1, svc.listInvestments(true).size());
        assertEquals(0, svc.listInvestments(false).size());
        assertEquals("archived", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s", D2, a, 1, P10, null))).field());

        svc.unarchive(a);
        svc.executeMovement(MovementRequest.sell("s", D2, a, 1, P10, null));
        assertEquals(2L, svc.position(a).quantity());
    }

    @Test
    void deleteRegistration_withHistory_rejectedByServiceAndByDatabaseTrigger() throws Exception {
        long a = openedQuantity("A");
        assertFalse(svc.canDeletePhysically(a));
        assertEquals("history", assertThrows(LedgerException.class, () -> svc.deleteRegistration(a)).field());

        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute("DELETE FROM investment_type WHERE id = " + a));
            assertTrue(ex.getMessage().contains("arquive"), ex.getMessage());
        }
        assertTrue(svc.investmentInfo(a).isPresent());
    }

    @Test
    void deleteRegistration_withoutAnyHistory_allowed() {
        long a = investment("Rascunho", null, null, null);
        assertTrue(svc.canDeletePhysically(a));
        svc.deleteRegistration(a);
        assertTrue(svc.investmentInfo(a).isEmpty());
    }

    @Test
    void deleteGuard_alsoProtectsLegacyTransactionsAndSnapshots() throws Exception {
        long a = investment("Legado", null, null, null);
        legacyTx(a, "BUY", 1, 100L);
        assertFalse(svc.canDeletePhysically(a));
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("DELETE FROM investment_type WHERE id = " + a));
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM transactions WHERE investment_type_id = " + a)) {
                rs.next();
                assertEquals(1, rs.getInt(1), "legacy history untouched (no cascade)");
            }
        }
    }

    @Test
    void attachNote_persistsOnRegistration() {
        long a = investment("A", null, null, null);
        svc.attachNote(a, "  conta corretora X ");
        assertEquals("conta corretora X", svc.investmentInfo(a).orElseThrow().note());
        svc.attachNote(a, "   ");
        assertNull(svc.investmentInfo(a).orElseThrow().note());
    }

    // ===== metrics without prices =====

    @Test
    void metrics_withoutPrice_valuesAtCostAndFlagsIt() {
        long a = openedQuantity("A");
        svc.executeMovement(MovementRequest.deposit("d", D1, 50_000L, null));
        svc.executeMovement(MovementRequest.buy("b", D1, a, 3, P10, null));
        PortfolioMetrics m = svc.metrics(PriceSource.none());
        assertEquals(3_000L, m.positionValueCents());
        assertEquals(0L, m.unrealizedGainCents());
        assertEquals(List.of(a), m.valuedAtCost());
    }

    // ===== helpers =====

    long openedQuantity(String name) {
        long id = investment(name, null, null, null);
        svc.registerOpening(OpeningRequest.newInvestment("open-" + id, D1, id, PositionMode.QUANTITY, null));
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

    static void legacyTx(long inv, String type, int qty, long totalCents) {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO transactions (date, investment_type_id, type, name, quantity, total_cents) VALUES ('2025-01-01', "
                    + inv + ", '" + type + "', 'legacy', " + qty + ", " + totalCents + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static void legacyCash(String date, long cents) throws SQLException {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO cash_snapshots (date, value_cents) VALUES ('" + date + "', " + cents + ")");
        }
    }
}

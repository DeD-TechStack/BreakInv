package com.daniel.core.service;

import com.daniel.core.domain.ledger.AdjustmentClassification;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.PositionMode;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the integrated independent review (findings LE1, LE2 and LE8). Each
 * test reproduces the reported sequence exactly; the reconstructibility rule now lives in the
 * ordered replay, so it is re-validated for every insertion (retroactive rows included).
 */
class IntegratedReviewLedgerTest {

    static final LocalDate D1 = LocalDate.of(2026, 4, 1);
    static final LocalDate D2 = LocalDate.of(2026, 4, 2);
    static final LocalDate D3 = LocalDate.of(2026, 4, 3);
    static final LocalDate D4 = LocalDate.of(2026, 4, 4);
    static final LocalDate D5 = LocalDate.of(2026, 4, 5);
    static final BigDecimal P20 = new BigDecimal("20");
    static final BigDecimal P30 = new BigDecimal("30");
    static final BigDecimal P40 = new BigDecimal("40");

    @TempDir
    Path tempDir;
    PortfolioService svc;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("integrated.db").toString().replace("\\", "/"));
        Database.open();
        svc = PortfolioService.onDatabase();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    // =====================================================================
    // LE1 — retroactive SELL dated between a BUY and its (already accepted) reversal
    // =====================================================================

    @Test
    void LE1_retroactiveSellBetweenBuyAndItsReversal_rejected_nothingWritten() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        Movement buyA = svc.executeMovement(MovementRequest.buy("bA", D1, a, 10, P20, null));
        svc.executeMovement(MovementRequest.buy("bB", D1, a, 10, P30, null));
        svc.executeMovement(MovementRequest.reversal("rA", D3, buyA.id(), "compra errada")); // no sell yet: accepted
        assertEquals(10L, svc.position(a).quantity());
        assertEquals(30_000L, svc.position(a).costCents());

        // SELL dated D2: in (date, id) order it lands between bA and its reversal
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s", D2, a, 5, P30, null)));
        assertEquals("reversesId", ex.field());
        assertTrue(ex.getMessage().contains("reconcilia"), ex.getMessage());

        assertTrue(svc.findByIntentKey("s").isEmpty(), "rejected row must not be written");
        assertEquals(5, svc.history().size(), "opening + deposit + bA + bB + reversal only");
        PortfolioState s = svc.state();
        assertEquals(10L, s.position(a).quantity(), "no phantom qty 5");
        assertEquals(30_000L, s.position(a).costCents(), "no phantom cost 175,00");
        assertEquals(0L, s.realizedGainCents(), "no phantom realized gain 25,00");
        assertEquals(70_000L, s.cashCents());
    }

    @Test
    void LE1_retroactiveReconciliationBetweenBuyAndItsReversal_rejected_nothingWritten() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        Movement buy = svc.executeMovement(MovementRequest.buy("b", D1, a, 10, P20, null));
        svc.executeMovement(MovementRequest.reversal("r", D3, buy.id(), "compra errada"));

        // a RECONCILIATION dated D2 re-states the position (12 / 240,00) between the BUY and its
        // reversal: the later inversion of the BUY would subtract it from an absolute figure
        // (phantom 2 / 40,00 today)
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.reconcile(ReconciliationRequest.position("rc", D2, a, 12L, 24_000L, "conferido")));
        assertEquals("reversesId", ex.field());
        assertTrue(svc.findByIntentKey("rc").isEmpty());
        assertEquals(4, svc.history().size());
        assertEquals(0L, svc.position(a).quantity());
        assertEquals(0L, svc.position(a).costCents());
    }

    @Test
    void LE1_reversalAcrossLaterReconciliation_rejected_positionAndCash() {
        long a = openedQuantity("A", D1);
        svc.registerOpening(OpeningRequest.cash("oc", D1, 0L, false, null));
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        Movement buy = svc.executeMovement(MovementRequest.buy("b", D1, a, 10, P20, null));
        svc.reconcile(ReconciliationRequest.position("rc", D2, a, 12L, 24_000L, "conferido na corretora"));
        // the reconciliation absorbed the BUY in an absolute figure: inverting the BUY afterwards
        // would subtract it a second time (phantom 2 / 40,00 today)
        assertEquals("reversesId", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D3, buy.id(), "x"))).field());
        assertEquals(12L, svc.position(a).quantity(), "reconciled figure untouched");
        assertEquals(24_000L, svc.position(a).costCents());
        assertTrue(svc.findByIntentKey("r").isEmpty());

        Movement dep = svc.executeMovement(MovementRequest.deposit("d2", D3, 1_000L, null));
        svc.reconcile(ReconciliationRequest.cash("rcc", D4, 50_000L, "saldo conferido"));
        assertEquals("reversesId", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r2", D5, dep.id(), "x"))).field());
        assertEquals(50_000L, svc.state().cashCents(), "reconciled cash untouched, not 49.000");
        assertTrue(svc.findByIntentKey("r2").isEmpty());
    }

    // =====================================================================
    // LE2 — reversal of a SELL after the average changed and another SELL used it
    // =====================================================================

    @Test
    void LE2_reversalOfSell_afterAverageChangeAndLaterSell_rejected_stateUntouched() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b1", D1, a, 10, P20, null));
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 5, P30, null));
        svc.executeMovement(MovementRequest.buy("b2", D3, a, 5, P40, null));
        svc.executeMovement(MovementRequest.sell("s2", D4, a, 5, P40, null));
        PortfolioState before = svc.state();
        assertEquals(5L, before.position(a).quantity());
        assertEquals(15_000L, before.position(a).costCents());
        assertEquals(10_000L, before.realizedGainCents());

        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D5, s1.id(), "venda errada")));
        assertEquals("reversesId", ex.field());
        assertTrue(ex.getMessage().contains("reconcilia"), ex.getMessage());

        assertTrue(svc.findByIntentKey("r").isEmpty());
        assertEquals(6, svc.history().size());
        PortfolioState after = svc.state();
        assertEquals(5L, after.position(a).quantity());
        assertEquals(15_000L, after.position(a).costCents(), "not the phantom 250,00 of the review");
        assertEquals(10_000L, after.realizedGainCents(), "not the phantom 50,00");
        assertEquals(95_000L, after.cashCents());
    }

    @Test
    void LE2_reversalOfSell_averageChangedByAdjustmentThenSell_rejected() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b1", D1, a, 10, P20, null));
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 5, P30, null));
        svc.executeMovement(MovementRequest.adjustPosition("adj", D3, a, 0L, 1_000L,
                AdjustmentClassification.CORRECTION, "custo de corretagem", null));
        svc.executeMovement(MovementRequest.sell("s2", D4, a, 2, P40, null));
        assertEquals("reversesId", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D5, s1.id(), "x"))).field());
        assertEquals(6, svc.history().size());
    }

    @Test
    void LE2_retroactiveSellAfterAverageChange_betweenSellAndItsReversal_rejected() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b1", D1, a, 10, P20, null));
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 5, P30, null));
        svc.executeMovement(MovementRequest.buy("b2", D3, a, 5, P40, null));
        svc.executeMovement(MovementRequest.reversal("r", D5, s1.id(), "venda errada")); // exact so far
        assertEquals(15L, svc.position(a).quantity());
        assertEquals(40_000L, svc.position(a).costCents());
        assertEquals(0L, svc.state().realizedGainCents());

        // retroactive SELL dated D4 would have used the changed average before the reversal
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s2", D4, a, 5, P40, null)));
        assertEquals("reversesId", ex.field());
        assertTrue(svc.findByIntentKey("s2").isEmpty());
        assertEquals(15L, svc.position(a).quantity());
        assertEquals(40_000L, svc.position(a).costCents());
    }

    @Test
    void LE2_reversalOfSell_followedByBuyOnly_isExactAndAllowed() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b1", D1, a, 10, P20, null));
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 5, P30, null));
        svc.executeMovement(MovementRequest.buy("b2", D3, a, 5, P40, null));
        svc.executeMovement(MovementRequest.reversal("r", D4, s1.id(), "venda errada"));
        assertEquals(15L, svc.position(a).quantity(), "same as history without s1");
        assertEquals(40_000L, svc.position(a).costCents());
        assertEquals(0L, svc.state().realizedGainCents());
    }

    @Test
    void LE2_reversalOfSell_followedBySellThenBuy_isExactAndAllowed() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("b1", D1, a, 10, P20, null));
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 5, P30, null));
        svc.executeMovement(MovementRequest.sell("s2", D3, a, 2, P40, null)); // same average as without s1
        svc.executeMovement(MovementRequest.buy("b2", D4, a, 5, P40, null));
        svc.executeMovement(MovementRequest.reversal("r", D5, s1.id(), "venda errada"));
        assertEquals(13L, svc.position(a).quantity());
        assertEquals(36_000L, svc.position(a).costCents(), "10x20 - 2x20 + 5x40");
        assertEquals(4_000L, svc.state().realizedGainCents(), "only s2: 2 x (40 - 20)");
    }

    // =====================================================================
    // LE8 — legacy registration with purchase_price <= 0 must still produce a proposal
    // =====================================================================

    @Test
    void LE8_legacyPriceZero_proposalFlaggedWithZeroCost_noException() {
        long a = investment("Zerado", 10, BigDecimal.ZERO, null);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(PositionMode.QUANTITY, p.positionMode());
        assertEquals(10L, p.quantity(), "registered quantity preserved");
        assertEquals(0L, p.costCents(), "cost is a placeholder, nothing invented");
        assertTrue(p.reconciliationFlag(), "price <= 0: cost not derivable, must be confirmed");
        assertTrue(p.note().toLowerCase().contains("preço"), p.note());
        assertTrue(svc.history().isEmpty(), "proposal writes nothing");

        svc.registerOpening(p);
        assertEquals(List.of(a), svc.pendingReconciliations());
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        assertEquals("reconciliation", assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s", D2, a, 1, P20, null))).field());

        svc.reconcile(ReconciliationRequest.position("rc", D2, a, 10L, 15_000L, "custo conferido na corretora"));
        svc.executeMovement(MovementRequest.sell("s2", D2, a, 1, P20, null));
        assertEquals(9L, svc.position(a).quantity());
        assertEquals(13_500L, svc.position(a).costCents());
    }

    @Test
    void LE8_legacyPriceNegative_sameConservativeTreatment() {
        long a = investment("Negativo", 3, new BigDecimal("-1.50"), null);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(3L, p.quantity());
        assertEquals(0L, p.costCents());
        assertTrue(p.reconciliationFlag());
        svc.registerOpening(p);
        assertEquals(3L, svc.position(a).quantity());
        assertTrue(svc.position(a).reconciliationPending());
    }

    @Test
    void LE8_legacyQuantityZeroPriceZero_zeroCostKeepsInvariant_stillFlagged() {
        long a = investment("Vazio0", 0, BigDecimal.ZERO, null);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(0L, p.quantity());
        assertEquals(0L, p.costCents(), "qty 0 requires cost 0");
        assertTrue(p.reconciliationFlag(), "an invalid price is still an uncertainty marker");
        svc.registerOpening(p);
        assertFalse(svc.history().isEmpty());
    }

    @Test
    void LE8_legacyPricePositive_stillDerivesCostUnflagged() {
        long a = investment("Ok", 10, P20, null);
        OpeningRequest p = svc.proposeOpening("o", D1, a);
        assertEquals(20_000L, p.costCents());
        assertFalse(p.reconciliationFlag());
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
}

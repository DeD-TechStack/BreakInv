package com.daniel.core.service;

import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.Position;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.infrastructure.persistence.config.Database;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mandatory numeric scenario (fictitious asset, no fees/taxes/dividends, controlled price):
 * deposit 1000 → buy 10 × 20 → sell 4 × 25 → withdraw 100 → sell 7 rejected → reopen.
 * Temp-file database; prices are explicit — no network.
 */
class PortfolioServiceScenarioTest {

    static final LocalDate D = LocalDate.of(2026, 1, 10);
    static final BigDecimal P20 = new BigDecimal("20");
    static final BigDecimal P25 = new BigDecimal("25");

    @TempDir
    Path tempDir;
    String url;
    PortfolioService svc;
    long inv;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:sqlite:" + tempDir.resolve("scenario.db").toString().replace("\\", "/");
        Database.configure(url);
        Database.open();
        inv = createInvestment("ATIVO FICTICIO");
        svc = PortfolioService.onDatabase();
        svc.registerOpening(OpeningRequest.newInvestment("open-1", D, inv, PositionMode.QUANTITY, "cadastro"));
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    @Test
    void step1_deposit_cash1000_noPosition_noGain() {
        svc.executeMovement(MovementRequest.deposit("k1", D, 100_000L, "aporte"));

        PortfolioState s = svc.state();
        assertEquals(100_000L, s.cashCents());
        assertEquals(0L, s.position(inv).quantity());
        assertEquals(0L, s.position(inv).costCents());
        assertEquals(0L, s.realizedGainCents());
        assertEquals(100_000L, s.externalNetFlowCents());
    }

    @Test
    void step2_buy10at20_cash800_qty10_cost200_wealth1000() {
        svc.executeMovement(MovementRequest.deposit("k1", D, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("k2", D, inv, 10, P20, null));

        PortfolioState s = svc.state();
        assertEquals(80_000L, s.cashCents());
        assertEquals(10L, s.position(inv).quantity());
        assertEquals(20_000L, s.position(inv).costCents());

        PortfolioMetrics m = svc.metrics(Map.of(inv, P20));
        assertEquals(100_000L, m.wealthCents());
        assertEquals(0L, m.unrealizedGainCents());
    }

    @Test
    void step3_sell4at25_cash900_qty6_cost120_realized20_unrealized30_wealth1050() {
        runUntilSell();

        PortfolioState s = svc.state();
        assertEquals(90_000L, s.cashCents());
        assertEquals(6L, s.position(inv).quantity());
        assertEquals(12_000L, s.position(inv).costCents());
        assertEquals(2_000L, s.realizedGainCents());

        PortfolioMetrics m = svc.metrics(Map.of(inv, P25));
        assertEquals(15_000L, m.positionValueCents());
        assertEquals(3_000L, m.unrealizedGainCents());
        assertEquals(105_000L, m.wealthCents());
    }

    @Test
    void step4_withdraw100_cash800_wealth950_gain50_external900() {
        runUntilSell();
        svc.executeMovement(MovementRequest.withdrawal("k4", D, 10_000L, null));

        PortfolioMetrics m = svc.metrics(Map.of(inv, P25));
        assertEquals(80_000L, m.cashCents());
        assertEquals(12_000L, m.costCents());
        assertEquals(6L, svc.position(inv).quantity());
        assertEquals(2_000L, m.realizedGainCents());
        assertEquals(3_000L, m.unrealizedGainCents());
        assertEquals(95_000L, m.wealthCents());
        assertEquals(90_000L, m.externalNetFlowCents());
    }

    @Test
    void step5_sell7_rejected_databaseAndIndicatorsUnchanged() throws Exception {
        runUntilSell();
        svc.executeMovement(MovementRequest.withdrawal("k4", D, 10_000L, null));
        int rowsBefore = countMovements();
        PortfolioMetrics before = svc.metrics(Map.of(inv, P25));

        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("k5", D, inv, 7, P25, null)));
        assertEquals("quantity", ex.field());

        assertEquals(rowsBefore, countMovements());
        assertEquals(before, svc.metrics(Map.of(inv, P25)));
        assertTrue(svc.findByIntentKey("k5").isEmpty());
    }

    @Test
    void step6_closeAndReopen_sameValuesLinksAndHistory() {
        runUntilSell();
        svc.executeMovement(MovementRequest.withdrawal("k4", D, 10_000L, null));
        List<Movement> historyBefore = svc.history();

        Database.close();
        Database.configure(url);
        PortfolioService reopened = PortfolioService.onDatabase();

        PortfolioMetrics m = reopened.metrics(Map.of(inv, P25));
        assertEquals(80_000L, m.cashCents());
        assertEquals(12_000L, m.costCents());
        assertEquals(2_000L, m.realizedGainCents());
        assertEquals(3_000L, m.unrealizedGainCents());
        assertEquals(95_000L, m.wealthCents());
        assertEquals(90_000L, m.externalNetFlowCents());
        assertEquals(6L, reopened.position(inv).quantity());
        assertEquals(historyBefore, reopened.history());
        assertEquals(5, historyBefore.size(), "opening + 4 movements");
        assertEquals(MovementKind.OPENING, historyBefore.get(0).kind());
        assertEquals(inv, historyBefore.get(1 + 1).investmentTypeId());
    }

    @Test
    void buy_withoutEnoughCash_rejectedWithoutWriting() throws Exception {
        svc.executeMovement(MovementRequest.deposit("k1", D, 10_000L, null));
        int rows = countMovements();
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.buy("k2", D, inv, 10, P20, null)));
        assertEquals("cash", ex.field());
        assertEquals(rows, countMovements());
        assertEquals(10_000L, svc.state().cashCents());
    }

    @Test
    void unitPrice_isNotRoundedBeforeTotal() {
        svc.executeMovement(MovementRequest.deposit("k1", D, 100_000L, null));
        // 3 × 1.005 = 3.015 → 302 cents HALF_UP; rounding the price first (1.01 or 1.00) would give 303/300
        Movement m = svc.executeMovement(MovementRequest.buy("k2", D, inv, 3, new BigDecimal("1.005"), null));
        assertEquals(302L, m.amountCents());
        assertEquals(new BigDecimal("1.005"), m.unitPrice());
        assertEquals(302L, svc.position(inv).costCents());
    }

    // ===== idempotency =====

    @Test
    void sameIntentKey_samePayload_returnsExistingWithoutDuplicate() throws Exception {
        Movement first = svc.executeMovement(MovementRequest.deposit("dup", D, 100_000L, "x"));
        Movement again = svc.executeMovement(MovementRequest.deposit("dup", D, 100_000L, "x"));
        assertEquals(first.id(), again.id());
        assertEquals(2, countMovements());
        assertEquals(100_000L, svc.state().cashCents());
    }

    @Test
    void sameIntentKey_differentPayload_rejected() throws Exception {
        svc.executeMovement(MovementRequest.deposit("dup", D, 100_000L, "x"));
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.deposit("dup", D, 200_000L, "x")));
        assertEquals("intentKey", ex.field());
        assertEquals(2, countMovements());
        assertEquals(100_000L, svc.state().cashCents());
    }

    // ===== helpers =====

    void runUntilSell() {
        svc.executeMovement(MovementRequest.deposit("k1", D, 100_000L, null));
        svc.executeMovement(MovementRequest.buy("k2", D, inv, 10, P20, null));
        svc.executeMovement(MovementRequest.sell("k3", D, inv, 4, P25, null));
    }

    static long createInvestment(String name) throws Exception {
        Connection c = Database.open();
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO investment_type (name, category, ticker) VALUES ('" + name + "', 'ACOES', 'FICT1')");
            try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static int countMovements() throws Exception {
        try (Connection c = Database.newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM portfolio_movement")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static Position positionOf(PortfolioState s, long id) {
        return s.position(id);
    }
}

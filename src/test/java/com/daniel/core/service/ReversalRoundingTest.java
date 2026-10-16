package com.daniel.core.service;

import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioState;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reversal of a SELL followed by another SELL is exact only when the released costs,
 * rounded HALF_UP to whole cents, reproduce the history without the original. With an
 * indivisible average (3 units, R$ 10,00) the second sale releases 334 instead of 333 cents
 * and the inverse of the original leaves the position one cent off the counterfactual.
 */
class ReversalRoundingTest {

    static final LocalDate D1 = LocalDate.of(2026, 5, 1);
    static final LocalDate D2 = LocalDate.of(2026, 5, 2);
    static final LocalDate D3 = LocalDate.of(2026, 5, 3);
    static final LocalDate D4 = LocalDate.of(2026, 5, 4);
    static final BigDecimal P3 = new BigDecimal("3");
    static final BigDecimal P4 = new BigDecimal("4");
    static final BigDecimal P5 = new BigDecimal("5");

    @TempDir
    Path tempDir;
    PortfolioService svc;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("rounding.db").toString().replace("\\", "/"));
        Database.open();
        svc = PortfolioService.onDatabase();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    /** Opening + deposit 100,00 + buy 1 x 4,00 + buy 2 x 3,00: qty 3, cost 10,00 (not divisible by 3). */
    private long indivisiblePosition() {
        long a = openedQuantity("A", D1);
        svc.executeMovement(MovementRequest.deposit("d", D1, 10_000L, null));
        svc.executeMovement(MovementRequest.buy("b1", D1, a, 1, P4, null));
        svc.executeMovement(MovementRequest.buy("b2", D1, a, 2, P3, null));
        assertEquals(3L, svc.position(a).quantity());
        assertEquals(1_000L, svc.position(a).costCents());
        return a;
    }

    @Test
    void reversalOfSell_followedByInexactSell_rejected_nothingWritten() {
        long a = indivisiblePosition();
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 1, P5, null)); // releases 333
        svc.executeMovement(MovementRequest.sell("s2", D3, a, 1, P5, null));              // 667/2 -> 334
        PortfolioState before = svc.state();
        assertEquals(1L, before.position(a).quantity());
        assertEquals(333L, before.position(a).costCents());
        assertEquals(333L, before.realizedGainCents(), "(500-333) + (500-334)");
        assertEquals(10_000L, before.cashCents());

        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.reversal("r", D4, s1.id(), "venda errada")));
        assertEquals("reversesId", ex.field());
        assertTrue(ex.getMessage().contains("reconcilia"), ex.getMessage());

        assertTrue(svc.findByIntentKey("r").isEmpty(), "rejected reversal must not be written");
        assertEquals(6, svc.history().size(), "opening + deposit + b1 + b2 + s1 + s2 only");
        PortfolioState after = svc.state();
        assertEquals(1L, after.position(a).quantity());
        assertEquals(333L, after.position(a).costCents(), "not the phantom 2 / 666 of the inverse");
        assertEquals(333L, after.realizedGainCents(), "history without s1 would realize 167, not 166");
        assertEquals(10_000L, after.cashCents());
    }

    @Test
    void retroactiveSell_thatChangesRoundingOfAnAcceptedReversal_rejected_nothingWritten() {
        long a = indivisiblePosition();
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 1, P5, null));
        svc.executeMovement(MovementRequest.reversal("r", D4, s1.id(), "venda errada")); // nothing between: exact
        assertEquals(3L, svc.position(a).quantity());
        assertEquals(1_000L, svc.position(a).costCents());
        assertEquals(0L, svc.state().realizedGainCents());

        // dated D3, it lands between s1 and its reversal and would release 334 instead of 333
        LedgerException ex = assertThrows(LedgerException.class,
                () -> svc.executeMovement(MovementRequest.sell("s2", D3, a, 1, P5, null)));
        assertEquals("reversesId", ex.field());

        assertTrue(svc.findByIntentKey("s2").isEmpty());
        assertEquals(6, svc.history().size());
        assertEquals(3L, svc.position(a).quantity());
        assertEquals(1_000L, svc.position(a).costCents());
        assertEquals(0L, svc.state().realizedGainCents());
        assertEquals(9_000L, svc.state().cashCents(), "10.000 - 400 - 600 + 500 (s1) - 500 (reversal)");
    }

    @Test
    void reversalOfSell_followedByFullLiquidation_isStillExactAndAllowed() {
        long a = indivisiblePosition();
        Movement s1 = svc.executeMovement(MovementRequest.sell("s1", D2, a, 1, P5, null)); // 333
        svc.executeMovement(MovementRequest.sell("s2", D3, a, 2, P5, null));              // all that is left: 667
        svc.executeMovement(MovementRequest.reversal("r", D4, s1.id(), "venda errada"));
        // without s1, s2 would release round(1000 * 2 / 3) = 667 as well: same position, same gain
        assertEquals(1L, svc.position(a).quantity());
        assertEquals(333L, svc.position(a).costCents());
        assertEquals(333L, svc.state().realizedGainCents(), "only s2: 1000 - 667");
        assertEquals(10_000L, svc.state().cashCents());
        assertEquals(7, svc.history().size());
    }

    long openedQuantity(String name, LocalDate date) {
        long id = investment(name);
        svc.registerOpening(OpeningRequest.newInvestment("open-" + id, date, id, PositionMode.QUANTITY, null));
        return id;
    }

    static long investment(String name) {
        try (Connection c = Database.newConnection();
             var ps = c.prepareStatement("INSERT INTO investment_type (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
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

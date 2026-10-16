package com.daniel.core.service;

import com.daniel.core.domain.ledger.*;
import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PortfolioBoundaryTest {
    @TempDir Path temp;
    PortfolioService service;
    static final LocalDate DAY = LocalDate.of(2026, 2, 2);

    @BeforeEach void prepare() {
        Database.configure("jdbc:sqlite:" + temp.resolve("boundary.db"));
        Database.open();
        service = PortfolioService.onDatabase();
    }
    @AfterEach void close() { Database.close(); }

    @Test void valuationOverflowIsRejectedInsteadOfDisplayingNegativeWealth() throws Exception {
        try (var st = Database.open().createStatement()) {
            st.execute("INSERT INTO investment_type(id,name,category,liquidity) VALUES(1,'Synthetic','Renda Fixa','DAILY')");
        }
        service.registerOpening(OpeningRequest.cash("cash", DAY, Long.MAX_VALUE, false, null));
        service.registerOpening(OpeningRequest.newInvestment("position", DAY, 1L, PositionMode.VALUE, null));
        assertThrows(LedgerException.class, () -> service.metrics(java.util.Map.of(1L, new java.math.BigDecimal("0.01"))));
    }

    @Test void aggregateOpeningOverflowRollsBackInsteadOfWrapping() throws Exception {
        try (var st = Database.open().createStatement()) {
            st.execute("INSERT INTO investment_type(id,name,category,liquidity) VALUES(1,'Synthetic','Renda Fixa','DAILY')");
        }
        service.registerOpening(OpeningRequest.cash("cash", DAY, Long.MAX_VALUE, false, null));
        assertThrows(LedgerException.class, () -> service.registerOpening(
                OpeningRequest.existingPosition("position", DAY, 1L, PositionMode.VALUE, null, 1L, false, null)));
        assertEquals(1, service.history().size());
        assertEquals(Long.MAX_VALUE, service.state().cashCents());
    }

    @Test void cashMovementBeforeOpeningCannotDisappearIntoOpeningBalance() {
        service.registerOpening(OpeningRequest.cash("open", DAY, 500L, false, null));
        assertThrows(LedgerException.class, () -> service.executeMovement(
                MovementRequest.deposit("retro", DAY.minusDays(1), 100L, null)));
        assertEquals(500L, service.state().cashCents());
        assertEquals(0L, service.state().externalNetFlowCents());
        assertEquals(1, service.history().size());
    }
}

package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.LedgerStore;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.LedgerAccess;
import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * {@link LedgerAccess} on the configured {@link Database}. Movements go through
 * {@link PortfolioService#onDatabase()} (one independent connection per unit of work). The
 * registration + opening pair runs inside ONE {@link Database#inTransaction} unit: the ledger
 * service used there is bound to a {@link ParticipatingStore}, which never commits or rolls
 * back by itself, so a rejected opening makes the outer transaction roll the new
 * {@code investment_type} row back as well. {@link #read} gives the ledger and the registrations one
 * consistent, read-only view per refresh (BRK-36).
 */
public final class DatabaseLedgerAccess implements LedgerAccess {

    private final PortfolioService portfolio = PortfolioService.onDatabase();

    @Override
    public PortfolioService portfolio() {
        return portfolio;
    }

    @Override
    public <T> T transact(Function<PortfolioService, T> work) {
        return Database.inTransaction(conn -> work.apply(new PortfolioService(new ParticipatingStore(conn))));
    }

    @Override
    public int createInvestment(DailyTrackingUseCase.NewInvestment inv, LongFunction<OpeningRequest> opening) {
        return Database.inTransaction(conn -> {
            InvestmentTypeRepository repo = new InvestmentTypeRepository(conn);
            int id = repo.createFull(inv.name(), inv.category(), inv.liquidity(), inv.investmentDate(),
                    inv.profitability(), inv.investedValue(), inv.typeOfInvestment(), inv.indexType(),
                    inv.indexPercentage(), inv.ticker(), inv.purchasePrice(), inv.quantity());
            if (id <= 0) throw new IllegalStateException("cadastro sem id gerado");
            if (inv.note() != null && !inv.note().isBlank()) {
                new JdbcPortfolioRepository(conn).setNote(id, inv.note());
            }
            new PortfolioService(new ParticipatingStore(conn)).registerOpening(opening.apply(id));
            return id;
        });
    }

    /**
     * One independent connection, {@code PRAGMA query_only = ON}, then a plain {@code BEGIN} (DEFERRED: the first
     * SELECT takes the SHARED lock and keeps it until the ROLLBACK, so no commit lands between the queries, while
     * other writers can still take RESERVED). Not {@link Database#inTransaction} nor {@code setAutoCommit(false)}:
     * both open {@code BEGIN IMMEDIATE}, a write lock a read does not need. The transaction is rolled back in the
     * {@code finally} without hiding an exception from {@code work}, and the connection always closes.
     */
    @Override
    public <T> T read(BiFunction<PortfolioRepository, IInvestmentTypeRepository, T> work) {
        try (Connection conn = Database.newConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA query_only = ON");
                st.execute("BEGIN");
            }
            Throwable failure = null;
            try {
                return work.apply(new JdbcPortfolioRepository(conn), new InvestmentTypeRepository(conn));
            } catch (RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                try (Statement st = conn.createStatement()) {
                    st.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    // closing the connection right after also ends the transaction; never hide the original failure
                    if (failure != null) failure.addSuppressed(rollback);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("ledger read: " + e.getMessage(), e);
        }
    }

    /**
     * Store that joins a transaction owned by the caller: both {@code transact} and {@code read}
     * simply run on the given connection. Commit/rollback belong to whoever opened it.
     */
    static final class ParticipatingStore implements LedgerStore {
        private final Connection conn;

        ParticipatingStore(Connection conn) {
            this.conn = conn;
        }

        @Override
        public <T> T transact(Function<PortfolioRepository, T> work) {
            return work.apply(new JdbcPortfolioRepository(conn));
        }

        @Override
        public <T> T read(Function<PortfolioRepository, T> work) {
            return work.apply(new JdbcPortfolioRepository(conn));
        }
    }
}

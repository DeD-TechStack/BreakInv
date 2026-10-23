package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.LedgerStore;
import com.daniel.core.domain.ledger.PortfolioRepository;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Function;

/**
 * {@link LedgerStore} on SQLite.
 * <ul>
 *   <li>Default constructor: each {@code transact} runs on a fresh independent connection via
 *       {@link Database#inTransaction} (BEGIN IMMEDIATE, commit/rollback, closed afterwards);
 *       {@code read} uses a fresh auto-commit connection. The shared UI connection is never used.</li>
 *   <li>Connection constructor: everything runs on that single caller-owned connection. If the
 *       connection is in auto-commit mode, {@code transact} opens, commits or rolls back its own
 *       transaction. If the caller already owns a transaction ({@code autoCommit == false}),
 *       the unit joins it through a SAVEPOINT: a rejection rolls back only to that savepoint
 *       and the caller keeps deciding commit/rollback of the whole. Any {@link Throwable}
 *       (including {@link Error} and a failed commit) rolls back before auto-commit is restored,
 *       so nothing is ever committed implicitly by {@code setAutoCommit(true)}.</li>
 * </ul>
 */
public final class DatabaseLedgerStore implements LedgerStore {

    private final Connection fixed;

    public DatabaseLedgerStore() {
        this.fixed = null;
    }

    public DatabaseLedgerStore(Connection connection) {
        this.fixed = connection;
    }

    @Override
    public <T> T transact(Function<PortfolioRepository, T> work) {
        if (fixed == null) {
            return Database.inTransaction(conn -> work.apply(new JdbcPortfolioRepository(conn)));
        }
        Database.checkWrite();
        try {
            if (!fixed.getAutoCommit()) {
                return joinCallerTransaction(work);
            }
            return ownTransaction(work);
        } catch (SQLException e) {
            throw new RuntimeException("ledger transaction: " + e.getMessage(), e);
        }
    }

    /** Caller owns the transaction: partial rollback via savepoint, never commit. */
    private <T> T joinCallerTransaction(Function<PortfolioRepository, T> work) throws SQLException {
        Savepoint sp = fixed.setSavepoint();
        boolean done = false;
        try {
            T result = work.apply(new JdbcPortfolioRepository(fixed));
            done = true;
            return result;
        } finally {
            if (done) {
                fixed.releaseSavepoint(sp);
            } else {
                fixed.rollback(sp);
            }
        }
    }

    /** No caller transaction: open one, commit on success, roll back on any Throwable. */
    private <T> T ownTransaction(Function<PortfolioRepository, T> work) throws SQLException {
        fixed.setAutoCommit(false);
        boolean committed = false;
        try {
            T result = work.apply(new JdbcPortfolioRepository(fixed));
            fixed.commit();
            committed = true;
            return result;
        } finally {
            if (!committed) {
                try {
                    fixed.rollback();
                } catch (SQLException rb) {
                    // rollback failure must not hide the original exception; connection is suspect
                }
            }
            fixed.setAutoCommit(true);
        }
    }

    @Override
    public <T> T read(Function<PortfolioRepository, T> work) {
        if (fixed != null) return work.apply(new JdbcPortfolioRepository(fixed));
        try (Connection conn = Database.newConnection()) {
            return work.apply(new JdbcPortfolioRepository(conn));
        } catch (SQLException e) {
            throw new RuntimeException("ledger read: " + e.getMessage(), e);
        }
    }
}

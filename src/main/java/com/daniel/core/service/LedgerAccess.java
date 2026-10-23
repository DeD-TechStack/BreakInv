package com.daniel.core.service;

import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PortfolioRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;

import java.util.function.BiFunction;
import java.util.function.LongFunction;
import java.util.function.Function;

/**
 * Seam between {@link DailyTrackingUseCase} and the ledger. Production binds it to SQLite
 * ({@code DatabaseLedgerAccess}); unit tests with in-memory stubs simply pass {@code null} and
 * keep the legacy behaviour.
 */
public interface LedgerAccess {

    /** Ledger use cases (movements, openings, metrics). */
    PortfolioService portfolio();

    /** Runs related ledger operations together; failures must roll back the entire unit. */
    <T> T transact(Function<PortfolioService, T> work);

    /**
     * Inserts the registration row and its opening marker in ONE transaction. The opening is
     * built from the generated id; if it is rejected, the registration must not survive.
     *
     * @return generated investment id
     */
    int createInvestment(DailyTrackingUseCase.NewInvestment investment, LongFunction<OpeningRequest> opening);

    /**
     * Reads the ledger and the registrations together: both repositories run on ONE connection, inside ONE
     * read transaction, and nothing can be written through them. Every query in {@code work} sees the same
     * committed state. The transaction ends (and the connection closes) before this returns, on success or
     * failure, so {@code work} must materialise what it needs; replays and network calls belong outside.
     */
    <T> T read(BiFunction<PortfolioRepository, IInvestmentTypeRepository, T> work);
}

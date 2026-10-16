package com.daniel.core.domain.ledger;

import java.util.function.Function;

/** Unit-of-work boundary: {@code transact} is atomic (commit/rollback), {@code read} is not. */
public interface LedgerStore {
    <T> T transact(Function<PortfolioRepository, T> work);

    <T> T read(Function<PortfolioRepository, T> work);
}

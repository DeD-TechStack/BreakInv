package com.daniel.core.domain.ledger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Supplies the valuation input per investment: unit price for QUANTITY positions, total current
 * value for VALUE positions. Empty = unavailable (position is valued at cost and flagged).
 */
@FunctionalInterface
public interface PriceSource {
    Optional<BigDecimal> valueFor(long investmentTypeId);

    static PriceSource of(Map<Long, BigDecimal> explicit) {
        return id -> Optional.ofNullable(explicit.get(id));
    }

    static PriceSource none() {
        return id -> Optional.empty();
    }
}

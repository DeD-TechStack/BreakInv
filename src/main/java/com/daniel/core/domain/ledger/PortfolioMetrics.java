package com.daniel.core.domain.ledger;

import java.util.List;

/**
 * Valuation on top of {@link PortfolioState} with caller-supplied prices (no market access here).
 * {@code valuedAtCost} lists positions for which no price/value was supplied; they were valued
 * at cost and must be presented as "sem cotação", not as market value.
 */
public record PortfolioMetrics(
        long cashCents,
        long costCents,
        long positionValueCents,
        long realizedGainCents,
        long unrealizedGainCents,
        long wealthCents,
        long externalNetFlowCents,
        List<Long> valuedAtCost
) {
    public long economicGainCents() {
        return realizedGainCents + unrealizedGainCents;
    }
}

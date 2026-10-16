package com.daniel.core.domain.ledger;

import java.util.Collections;
import java.util.Map;

/**
 * Result of replaying the ledger in (date, id) order.
 * <ul>
 *   <li>{@code externalNetFlowCents}: deposits - withdrawals (opening markers excluded).</li>
 *   <li>{@code openingCapitalCents}: cash + cost declared by OPENING/RECONCILIATION markers.</li>
 *   <li>{@code realizedGainCents}: SELL proceeds - average cost released (QUANTITY mode) plus
 *       INCOME/EXPENSE adjustments. VALUE-mode sells never contribute.</li>
 * </ul>
 */
public record PortfolioState(
        long cashCents,
        boolean cashOpened,
        boolean cashReconciliationPending,
        Map<Long, Position> positions,
        long realizedGainCents,
        long externalNetFlowCents,
        long openingCapitalCents
) {
    public PortfolioState {
        positions = Collections.unmodifiableMap(positions);
    }

    public Position position(long investmentTypeId) {
        return positions.getOrDefault(investmentTypeId, Position.empty(investmentTypeId));
    }

    /** Checked sum; overflow is rejected like in {@code metrics}, never wrapped. */
    public long totalCostCents() {
        long total = 0L;
        try {
            for (Position p : positions.values()) total = Math.addExact(total, p.costCents());
        } catch (ArithmeticException overflow) {
            throw new LedgerException("cost", "custo agregado excede o limite suportado");
        }
        return total;
    }
}

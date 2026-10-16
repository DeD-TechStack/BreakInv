package com.daniel.core.domain.ledger;

/**
 * How an investment's position is tracked.
 * <ul>
 *   <li>{@link #QUANTITY}: integer units (> 0 per movement) with decimal unit price; average cost.</li>
 *   <li>{@link #VALUE}: no quantity; buys add cost, sells redeem the specified cost amount.
 *       The ledger never infers a gain for VALUE positions; gains appear only through
 *       explicit valuation in metrics.</li>
 * </ul>
 */
public enum PositionMode {
    QUANTITY,
    VALUE
}

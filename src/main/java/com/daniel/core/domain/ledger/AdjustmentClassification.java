package com.daniel.core.domain.ledger;

/**
 * Mandatory classification of an ADJUSTMENT. INCOME (amount &gt; 0) and EXPENSE (amount &lt; 0)
 * count as realized gain with their sign; CORRECTION and OTHER never do.
 */
public enum AdjustmentClassification {
    /** Fixes a data error; affects neither external flow nor realized gain. */
    CORRECTION,
    /** Income credited (e.g. interest paid to cash or to cost); amount must be positive; counts as realized gain. */
    INCOME,
    /** Cost/fee debited; amount must be negative; counts as negative realized gain. */
    EXPENSE,
    /** Anything else; treated like CORRECTION for gain purposes. */
    OTHER
}

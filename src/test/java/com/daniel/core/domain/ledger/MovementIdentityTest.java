package com.daniel.core.domain.ledger;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class MovementIdentityTest {
    @Test
    void fieldDelimitersCannotMakeDifferentIntentPayloadsEqual() {
        var first = MovementRequest.adjustCash("same", LocalDate.of(2026, 1, 1), 100,
                AdjustmentClassification.OTHER, "reason|part", "note");
        var second = MovementRequest.adjustCash("same", LocalDate.of(2026, 1, 1), 100,
                AdjustmentClassification.OTHER, "reason", "part|note");
        assertNotEquals(first.payloadHash(), second.payloadHash());
        assertNotEquals(first, second);
    }
}

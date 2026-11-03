package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.infrastructure.persistence.repository.LedgerWriteOracle.Financial;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controls of the comparison of U4B:RF-04 ({@link LedgerWriteOracle#beforePlusOperation}), with values built here
 * and no database: the right operation is accepted, and each wrong one is rejected. Without them the proof of the
 * injections could pass on a row with the right key and anything else in it.
 */
class LedgerWriteOracleTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    /** An old row, every column of the table, as read from the database. */
    private static Map<String, Object> oldRow(long id, String key, long cents) {
        Map<String, Object> row = LedgerWriteOracle.deposit(key, "hash-" + key, LocalDate.of(2026, 10, 1), cents);
        row.put("id", id);
        row.put("created_at", "2026-10-01T10:00:00Z");
        return row;
    }

    /** The row of the operation as it is read after it, with its own id and created_at. */
    private static Map<String, Object> written(long id, Map<String, Object> expected) {
        Map<String, Object> row = new LinkedHashMap<>(expected);
        row.put("id", id);
        row.put("created_at", "2026-11-03T12:00:00Z");
        return row;
    }

    private static PortfolioState state(long cents) {
        return new PortfolioState(cents, true, false, Map.of(), 0L, cents, 0L);
    }

    private static final Map<String, Object> TYPE = Map.of("id", 1L, "name", "ATIVO A");

    private final Map<String, Object> expectedRow = LedgerWriteOracle.deposit("preso-1", "hash-preso-1", DAY, 700L);
    private final List<Map<String, Object>> expectedNew = List.of(expectedRow);
    private final Financial before = new Financial(state(100_000L),
            List.of(oldRow(1, "base-1", 100_000L)), List.of(TYPE));
    private final PortfolioState expectedState = LedgerWriteOracle.withDeposits(before.state(), 700L);

    private Financial after(Map<String, Object> newRow, PortfolioState state) {
        List<Map<String, Object>> movements = new ArrayList<>(before.movements());
        if (newRow != null) movements.add(newRow);
        return new Financial(state, movements, before.types());
    }

    private boolean accepts(Financial now) {
        return LedgerWriteOracle.beforePlusOperation(before, now, expectedNew, expectedState);
    }

    @Test
    void positive_theRightOperation_isAccepted() {
        assertTrue(accepts(after(written(2, expectedRow), expectedState)));
    }

    @Test
    void positive_idAndCreatedAtOfTheNewRowAreNotCompared() {
        Map<String, Object> other = written(99, expectedRow);
        other.put("created_at", "2030-01-01T00:00:00Z");
        assertTrue(accepts(after(other, expectedState)));
    }

    @Test
    void negative_rightKeyWrongAmount_isRejected() {
        Map<String, Object> row = written(2, expectedRow);
        row.put("amount_cents", 1L);
        assertFalse(accepts(after(row, expectedState)));
    }

    @Test
    void negative_rightKeyWrongDate_isRejected() {
        Map<String, Object> row = written(2, expectedRow);
        row.put("date", "2020-01-01");
        assertFalse(accepts(after(row, expectedState)));
    }

    @Test
    void negative_rightKeyWrongKind_isRejected() {
        Map<String, Object> row = written(2, expectedRow);
        row.put("kind", "WITHDRAWAL");
        assertFalse(accepts(after(row, expectedState)));
    }

    @Test
    void negative_rightKeyWrongPayloadHash_isRejected() {
        Map<String, Object> row = written(2, expectedRow);
        row.put("payload_hash", "outro");
        assertFalse(accepts(after(row, expectedState)));
    }

    @Test
    void negative_rightRowButStateWithoutTheDeposit_isRejected() {
        assertFalse(accepts(after(written(2, expectedRow), before.state())));
    }

    @Test
    void negative_rightRowButCashOnlyInTheState_isRejected() {
        PortfolioState cashOnly = new PortfolioState(expectedState.cashCents(), true, false, Map.of(), 0L,
                before.state().externalNetFlowCents(), 0L);
        assertFalse(accepts(after(written(2, expectedRow), cashOnly)));
    }

    @Test
    void negative_anExtraRow_isRejected() {
        Financial now = after(written(2, expectedRow), expectedState);
        now.movements().add(written(3, LedgerWriteOracle.deposit("preso-2", "hash-preso-2", DAY, 700L)));
        assertFalse(accepts(now));
    }

    @Test
    void negative_aMissingRow_isRejected() {
        assertFalse(accepts(after(null, expectedState)));
    }

    @Test
    void negative_theSameKeyTwice_isRejected() {
        List<Map<String, Object>> twice = List.of(expectedRow, expectedRow);
        Financial now = after(written(2, expectedRow), expectedState);
        now.movements().add(written(3, expectedRow));
        assertFalse(LedgerWriteOracle.beforePlusOperation(before, now, twice, expectedState));
    }

    @Test
    void negative_anOldRowChanged_isRejected() {
        Financial now = after(written(2, expectedRow), expectedState);
        Map<String, Object> changed = new LinkedHashMap<>(now.movements().get(0));
        changed.put("amount_cents", 5L);
        now.movements().set(0, changed);
        assertFalse(accepts(now));
    }

    @Test
    void negative_anInvestmentChanged_isRejected() {
        Financial now = new Financial(expectedState, after(written(2, expectedRow), expectedState).movements(),
                List.of(Map.of("id", 1L, "name", "OUTRO")));
        assertFalse(accepts(now));
    }
}

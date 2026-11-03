package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The comparison of U4B:RF-04 ("Comparação"): the financial state read from the database and the judgement of
 * "equal to before" and "before plus the whole operation". The operation is described by the test beforehand
 * ({@link #deposit}, {@link #withDeposits}), never read from the path that failed.
 */
final class LedgerWriteOracle {

    private LedgerWriteOracle() {
    }

    /** The three parts of the financial state: the whole {@link PortfolioState} and every column of both tables. */
    record Financial(PortfolioState state, List<Map<String, Object>> movements, List<Map<String, Object>> types) {
    }

    /** Columns of the new rows that are not compared. */
    static final Set<String> NOT_COMPARED = Set.of("id", "created_at");

    /** Read through a new connection: the state of the ledger and every column of both tables, by id. */
    static Financial read() throws SQLException {
        PortfolioState state = PortfolioService.onDatabase().state();
        try (Connection c = Database.newConnection()) {
            return new Financial(state, rows(c, "portfolio_movement"), rows(c, "investment_type"));
        }
    }

    private static List<Map<String, Object>> rows(Connection c, String table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    // the driver gives Integer for a small INTEGER and Long for a big one: one type for all
                    row.put(meta.getColumnName(i), value instanceof Integer n ? Long.valueOf(n) : value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** The new row a deposit leaves in {@code portfolio_movement}, column by column, except id and created_at. */
    static Map<String, Object> deposit(String key, String payloadHash, LocalDate date, long cents) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("intent_key", key);
        row.put("payload_hash", payloadHash);
        row.put("date", date.toString());
        row.put("kind", "DEPOSIT");
        row.put("investment_type_id", null);
        row.put("position_mode", null);
        row.put("quantity", null);
        row.put("unit_price", null);
        row.put("amount_cents", cents);
        row.put("classification", null);
        row.put("reason", null);
        row.put("note", null);
        row.put("reverses_id", null);
        row.put("reconciliation_flag", 0L);
        row.put("source", "USER");
        return row;
    }

    /** The state after deposits that total {@code cents}: cash and external net flow grow by it, the rest is the same. */
    static PortfolioState withDeposits(PortfolioState s, long cents) {
        return new PortfolioState(s.cashCents() + cents, s.cashOpened(), s.cashReconciliationPending(), s.positions(),
                s.realizedGainCents(), s.externalNetFlowCents() + cents, s.openingCapitalCents());
    }

    /** Equal to before: every column of the old rows, no new row, the same state. */
    static boolean equalToBefore(Financial before, Financial now) {
        return before.state().equals(now.state()) && before.movements().equals(now.movements())
                && before.types().equals(now.types());
    }

    /**
     * Before plus the whole operation: the old rows equal in every column, the investments equal, the new rows
     * exactly {@code expectedNew}, and the state exactly {@code expectedState}.
     */
    static boolean beforePlusOperation(Financial before, Financial now, List<Map<String, Object>> expectedNew,
                                       PortfolioState expectedState) {
        int old = before.movements().size();
        if (now.movements().size() < old) return false;
        if (!before.movements().equals(now.movements().subList(0, old))) return false;
        if (!before.types().equals(now.types())) return false;
        if (!expectedState.equals(now.state())) return false;
        List<Map<String, Object>> added = now.movements().subList(old, now.movements().size());
        if (added.size() != expectedNew.size()) return false;
        Map<Object, Map<String, Object>> expectedByKey = new HashMap<>();
        for (Map<String, Object> e : expectedNew) {
            if (expectedByKey.put(e.get("intent_key"), e) != null) return false;
        }
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> row : added) {
            Map<String, Object> actual = new LinkedHashMap<>(row);
            actual.keySet().removeAll(NOT_COMPARED);
            Object key = actual.get("intent_key");
            if (!seen.add(key)) return false;
            if (!actual.equals(expectedByKey.get(key))) return false;
        }
        return true;
    }

    static long count(Financial now, String key) {
        return now.movements().stream().filter(r -> key.equals(r.get("intent_key"))).count();
    }
}

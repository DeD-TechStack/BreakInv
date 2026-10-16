package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.InvestmentLedgerInfo;
import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.domain.ledger.PortfolioRepository;
import com.daniel.core.domain.ledger.PositionMode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation bound to ONE connection supplied by the caller (never Database.open()
 * internally), so the caller controls the transaction boundary. Append-only: no UPDATE/DELETE
 * on portfolio_movement exists in this class.
 */
public final class JdbcPortfolioRepository implements PortfolioRepository {

    private static final String COLS = "id, intent_key, date, kind, investment_type_id, position_mode, quantity, unit_price, "
            + "amount_cents, classification, reason, note, reverses_id, reconciliation_flag, source, created_at";

    private final Connection conn;

    public JdbcPortfolioRepository(Connection conn) {
        this.conn = conn;
    }

    @Override
    public Optional<Movement> findByIntentKey(String intentKey) {
        return queryOne("SELECT " + COLS + " FROM portfolio_movement WHERE intent_key = ?", ps -> ps.setString(1, intentKey));
    }

    @Override
    public Optional<String> payloadHashOf(String intentKey) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT payload_hash FROM portfolio_movement WHERE intent_key = ?")) {
            ps.setString(1, intentKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap("payloadHashOf", e);
        }
    }

    @Override
    public Optional<Movement> findById(long id) {
        return queryOne("SELECT " + COLS + " FROM portfolio_movement WHERE id = ?", ps -> ps.setLong(1, id));
    }

    @Override
    public List<Movement> listAll() {
        List<Movement> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + COLS + " FROM portfolio_movement ORDER BY date, id")) {
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (SQLException e) {
            throw wrap("listAll", e);
        }
    }

    @Override
    public Movement insert(Movement m, String payloadHash) {
        String sql = "INSERT INTO portfolio_movement (intent_key, payload_hash, date, kind, investment_type_id, position_mode, "
                + "quantity, unit_price, amount_cents, classification, reason, note, reverses_id, reconciliation_flag, source, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, m.intentKey());
            ps.setString(2, payloadHash);
            ps.setString(3, m.date().toString());
            ps.setString(4, m.kind().name());
            setLong(ps, 5, m.investmentTypeId());
            ps.setString(6, m.positionMode() == null ? null : m.positionMode().name());
            setLong(ps, 7, m.quantity());
            ps.setString(8, m.unitPrice() == null ? null : m.unitPrice().toPlainString());
            ps.setLong(9, m.amountCents());
            ps.setString(10, m.classification());
            ps.setString(11, m.reason());
            ps.setString(12, m.note());
            setLong(ps, 13, m.reversesId());
            ps.setInt(14, m.reconciliationFlag() ? 1 : 0);
            ps.setString(15, m.source() == null ? "USER" : m.source());
            ps.setString(16, (m.createdAt() == null ? Instant.now() : m.createdAt()).toString());
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("no generated id");
                id = keys.getLong(1);
            }
            return findById(id).orElseThrow();
        } catch (SQLException e) {
            throw wrap("insert", e);
        }
    }

    // ---- investment registration ----

    @Override
    public Optional<InvestmentLedgerInfo> investmentInfo(long investmentTypeId) {
        List<InvestmentLedgerInfo> list = queryInvestments("WHERE i.id = ?", ps -> ps.setLong(1, investmentTypeId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<InvestmentLedgerInfo> listInvestments(boolean includeArchived) {
        return queryInvestments(includeArchived ? "" : "WHERE i.archived_at IS NULL", ps -> { });
    }

    private List<InvestmentLedgerInfo> queryInvestments(String where, Binder binder) {
        String sql = "SELECT i.id, i.name, i.position_mode, i.archived_at, i.note, i.quantity, i.purchase_price, i.invested_value, "
                + "(SELECT COUNT(*) FROM transactions t WHERE t.investment_type_id = i.id) AS tx_count, "
                + "(SELECT COALESCE(SUM(quantity),0) FROM transactions t WHERE t.investment_type_id = i.id AND t.type = 'BUY') AS buy_qty, "
                + "(SELECT COALESCE(SUM(quantity),0) FROM transactions t WHERE t.investment_type_id = i.id AND t.type = 'SELL') AS sell_qty, "
                + "(SELECT COUNT(*) FROM transactions t WHERE t.investment_type_id = i.id AND t.type IN ('BUY','SELL') AND t.quantity IS NULL) AS null_qty_count "
                + "FROM investment_type i " + where + " ORDER BY i.name";
        List<InvestmentLedgerInfo> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String mode = rs.getString("position_mode");
                    String archived = rs.getString("archived_at");
                    int rawQty = rs.getInt("quantity");
                    Integer qty = rs.wasNull() ? null : rawQty;
                    out.add(new InvestmentLedgerInfo(rs.getLong("id"), rs.getString("name"),
                            mode == null ? null : PositionMode.valueOf(mode),
                            archived == null ? null : Instant.parse(archived), rs.getString("note"), qty,
                            decimal(rs, "purchase_price"), decimal(rs, "invested_value"), rs.getInt("tx_count"),
                            rs.getLong("buy_qty"), rs.getLong("sell_qty"), rs.getInt("null_qty_count")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw wrap("investmentInfo", e);
        }
    }

    @Override
    public void setPositionMode(long investmentTypeId, PositionMode mode) {
        update("UPDATE investment_type SET position_mode = ? WHERE id = ?", ps -> {
            ps.setString(1, mode == null ? null : mode.name());
            ps.setLong(2, investmentTypeId);
        });
    }

    @Override
    public void setArchivedAt(long investmentTypeId, Instant archivedAt) {
        update("UPDATE investment_type SET archived_at = ? WHERE id = ?", ps -> {
            ps.setString(1, archivedAt == null ? null : archivedAt.toString());
            ps.setLong(2, investmentTypeId);
        });
    }

    @Override
    public void setNote(long investmentTypeId, String note) {
        update("UPDATE investment_type SET note = ? WHERE id = ?", ps -> {
            ps.setString(1, (note == null || note.isBlank()) ? null : note.trim());
            ps.setLong(2, investmentTypeId);
        });
    }

    @Override
    public boolean hasHistory(long investmentTypeId) {
        String sql = "SELECT EXISTS(SELECT 1 FROM portfolio_movement WHERE investment_type_id = ?) "
                + "OR EXISTS(SELECT 1 FROM transactions WHERE investment_type_id = ?) "
                + "OR EXISTS(SELECT 1 FROM investment_snapshots WHERE investment_type_id = ?) "
                + "OR EXISTS(SELECT 1 FROM flows WHERE from_investment_type_id = ? OR to_investment_type_id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) ps.setLong(i, investmentTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw wrap("hasHistory", e);
        }
    }

    @Override
    public void deleteRegistration(long investmentTypeId) {
        update("DELETE FROM investment_type WHERE id = ?", ps -> ps.setLong(1, investmentTypeId));
    }

    @Override
    public Optional<Long> latestLegacyCashCents() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value_cents FROM cash_snapshots ORDER BY date DESC LIMIT 1")) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        } catch (SQLException e) {
            throw wrap("latestLegacyCashCents", e);
        }
    }

    // ---- helpers ----

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Movement> queryOne(String sql, Binder binder) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap("query", e);
        }
    }

    private void update(String sql, Binder binder) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap("update", e);
        }
    }

    private static Movement map(ResultSet rs) throws SQLException {
        String mode = rs.getString("position_mode");
        String price = rs.getString("unit_price");
        return new Movement(
                rs.getLong("id"),
                rs.getString("intent_key"),
                LocalDate.parse(rs.getString("date")),
                MovementKind.valueOf(rs.getString("kind")),
                nullableLong(rs, "investment_type_id"),
                mode == null ? null : PositionMode.valueOf(mode),
                nullableLong(rs, "quantity"),
                price == null ? null : new BigDecimal(price),
                rs.getLong("amount_cents"),
                rs.getString("classification"),
                rs.getString("reason"),
                rs.getString("note"),
                nullableLong(rs, "reverses_id"),
                rs.getInt("reconciliation_flag") == 1,
                rs.getString("source"),
                Instant.parse(rs.getString("created_at")));
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static BigDecimal decimal(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        if (s == null) return null;
        return new BigDecimal(s);
    }

    private static void setLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setLong(idx, v);
    }

    private static RuntimeException wrap(String op, SQLException e) {
        return new RuntimeException("portfolio_movement " + op + ": " + e.getMessage(), e);
    }
}

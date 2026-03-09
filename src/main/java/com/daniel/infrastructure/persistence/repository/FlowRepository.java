package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.Enums.FlowKind;
import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class FlowRepository implements IFlowRepository {

    public List<Flow> listForDate(LocalDate date) {
        String sql = """
            SELECT id, date,
                   from_kind, from_investment_type_id,
                   to_kind, to_investment_type_id,
                   amount_cents, note
            FROM flows
            WHERE date = ?
            ORDER BY id ASC
            """;

        List<Flow> out = new ArrayList<>();
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    LocalDate d = LocalDate.parse(rs.getString("date"));

                    FlowKind fromKind = FlowKind.valueOf(rs.getString("from_kind"));
                    Long fromInvId = toNullableLong(rs.getObject("from_investment_type_id"));

                    FlowKind toKind = FlowKind.valueOf(rs.getString("to_kind"));
                    Long toInvId = toNullableLong(rs.getObject("to_investment_type_id"));

                    long amountCents = rs.getLong("amount_cents");
                    String note = rs.getString("note");

                    out.add(new Flow(id, d, fromKind, fromInvId, toKind, toInvId, amountCents, note));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list flows", e);
        }
    }

    @Override
    public void save(Flow flow) {
    }

    public long create(Flow f) {
        String sql = """
            INSERT INTO flows(date, from_kind, from_investment_type_id, to_kind, to_investment_type_id, amount_cents, note)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, f.date().toString());
            ps.setString(2, f.fromKind().name());
            if (f.fromInvestmentTypeId() == null) ps.setNull(3, java.sql.Types.INTEGER);
            else ps.setLong(3, f.fromInvestmentTypeId());

            ps.setString(4, f.toKind().name());
            if (f.toInvestmentTypeId() == null) ps.setNull(5, java.sql.Types.INTEGER);
            else ps.setLong(5, f.toInvestmentTypeId());

            ps.setLong(6, f.amountCents());
            ps.setString(7, f.note());

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create flow", e);
        }
    }

    public void delete(long id) {
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM flows WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete flow", e);
        }
    }

    private static Long toNullableLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        return Long.parseLong(obj.toString());
    }
}
package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class TransactionRepository implements ITransactionRepository {

    @Override
    public long insert(Transaction t) {
        String sql = """
            INSERT INTO transactions
            (date, investment_type_id, type, name, ticker, quantity, unit_price_cents, total_cents, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = Database.open().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, t.date().toString());
            ps.setInt(2, t.investmentTypeId());
            ps.setString(3, t.type());
            ps.setString(4, t.name());
            ps.setString(5, t.ticker());
            if (t.quantity() != null) {
                ps.setInt(6, t.quantity());
            } else {
                ps.setNull(6, Types.INTEGER);
            }
            if (t.unitPriceCents() != null) {
                ps.setLong(7, t.unitPriceCents());
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.setLong(8, t.totalCents());
            ps.setString(9, t.note());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inserir transação: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Transaction> listBetween(LocalDate start, LocalDate end) {
        String sql = """
            SELECT id, date, investment_type_id, type, name, ticker,
                   quantity, unit_price_cents, total_cents, note
            FROM transactions
            WHERE date >= ? AND date <= ?
            ORDER BY date DESC, id DESC
            """;

        List<Transaction> list = new ArrayList<>();
        try (PreparedStatement ps = Database.open().prepareStatement(sql)) {
            ps.setString(1, start.toString());
            ps.setString(2, end.toString());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar transações: " + e.getMessage(), e);
        }
    }

    private Transaction mapRow(ResultSet rs) throws SQLException {
        int rawQty = rs.getInt("quantity");
        Integer quantity = rs.wasNull() ? null : rawQty;

        long rawUnit = rs.getLong("unit_price_cents");
        Long unitPriceCents = rs.wasNull() ? null : rawUnit;

        return new Transaction(
                rs.getLong("id"),
                LocalDate.parse(rs.getString("date")),
                rs.getInt("investment_type_id"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("ticker"),
                quantity,
                unitPriceCents,
                rs.getLong("total_cents"),
                rs.getString("note")
        );
    }
}

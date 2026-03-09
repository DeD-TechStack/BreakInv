package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.infrastructure.persistence.config.Database;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class InvestmentTypeRepository implements IInvestmentTypeRepository {

    @Override
    public List<InvestmentType> listAll() {
        String sql = "SELECT * FROM investment_type ORDER BY name";

        List<InvestmentType> list = new ArrayList<>();
        try (Connection conn = Database.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar tipos: " + e.getMessage(), e);
        }
    }

    @Override
    public void save(String name) {
        String sql = "INSERT INTO investment_type (name) VALUES (?)";

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Tipo já existe ou erro ao criar: " + e.getMessage(), e);
        }
    }

    public int createFull(String name, String category, String liquidity,
                          LocalDate investmentDate, BigDecimal profitability,
                          BigDecimal investedValue, String typeOfInvestment,
                          String indexType, BigDecimal indexPercentage,
                          String ticker, BigDecimal purchasePrice, Integer quantity) {
        String sql = """
            INSERT INTO investment_type
            (name, category, liquidity, investment_date, profitability, invested_value,
             type_of_investment, index_type, index_percentage, ticker, purchase_price, quantity)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, category);
            ps.setString(3, liquidity);
            ps.setString(4, investmentDate != null ? investmentDate.toString() : null);
            ps.setBigDecimal(5, profitability);
            ps.setBigDecimal(6, investedValue);
            ps.setString(7, typeOfInvestment);
            ps.setString(8, indexType);
            ps.setBigDecimal(9, indexPercentage);
            ps.setString(10, ticker);
            ps.setBigDecimal(11, purchasePrice);
            if (quantity != null) {
                ps.setInt(12, quantity);
            } else {
                ps.setNull(12, Types.INTEGER);
            }
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao criar tipo: " + e.getMessage(), e);
        }
    }

    public void updateFull(int id, String name, String category, String liquidity,
                           LocalDate investmentDate, BigDecimal profitability,
                           BigDecimal investedValue, String typeOfInvestment,
                           String indexType, BigDecimal indexPercentage,
                           String ticker, BigDecimal purchasePrice, Integer quantity) {
        String sql = """
            UPDATE investment_type
            SET name = ?, category = ?, liquidity = ?,
                investment_date = ?, profitability = ?, invested_value = ?,
                type_of_investment = ?, index_type = ?, index_percentage = ?,
                ticker = ?, purchase_price = ?, quantity = ?
            WHERE id = ?
            """;

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, category);
            ps.setString(3, liquidity);
            ps.setString(4, investmentDate != null ? investmentDate.toString() : null);
            ps.setBigDecimal(5, profitability);
            ps.setBigDecimal(6, investedValue);
            ps.setString(7, typeOfInvestment);
            ps.setString(8, indexType);
            ps.setBigDecimal(9, indexPercentage);
            ps.setString(10, ticker);
            ps.setBigDecimal(11, purchasePrice);
            if (quantity != null) {
                ps.setInt(12, quantity);
            } else {
                ps.setNull(12, Types.INTEGER);
            }
            ps.setInt(13, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao atualizar: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(int id, String newName) {
        String sql = "UPDATE investment_type SET name = ? WHERE id = ?";

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao renomear: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(long id) {
        String sql = "DELETE FROM investment_type WHERE id = ?";

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao deletar: " + e.getMessage(), e);
        }
    }

    private InvestmentType mapRow(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String name = rs.getString("name");
        String category = rs.getString("category");
        String liquidity = rs.getString("liquidity");

        String dateStr = rs.getString("investment_date");
        LocalDate investmentDate = dateStr != null && !dateStr.isBlank() ?
                LocalDate.parse(dateStr) : null;

        BigDecimal profitability = rs.getBigDecimal("profitability");
        BigDecimal investedValue = rs.getBigDecimal("invested_value");

        String typeOfInvestment = rs.getString("type_of_investment");
        String indexType = rs.getString("index_type");
        BigDecimal indexPercentage = rs.getBigDecimal("index_percentage");
        String ticker = rs.getString("ticker");
        BigDecimal purchasePrice = rs.getBigDecimal("purchase_price");
        int rawQty = rs.getInt("quantity");
        Integer quantity = rs.wasNull() ? null : rawQty;

        return new InvestmentType(id, name, category, liquidity,
                investmentDate, profitability, investedValue,
                typeOfInvestment, indexType, indexPercentage,
                ticker, purchasePrice, quantity, null);
    }
}
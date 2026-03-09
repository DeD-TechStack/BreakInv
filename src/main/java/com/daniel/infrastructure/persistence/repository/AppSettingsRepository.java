package com.daniel.infrastructure.persistence.repository;

import com.daniel.infrastructure.persistence.config.Database;

import java.sql.*;
import java.util.Optional;

public final class AppSettingsRepository {

    public Optional<String> get(String key) {
        String sql = "SELECT value FROM app_settings WHERE key = ?";
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("value"));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public void set(String key, String value) {
        String sql = "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar configuração: " + e.getMessage(), e);
        }
    }

    public void delete(String key) {
        String sql = "DELETE FROM app_settings WHERE key = ?";
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao deletar configuração: " + e.getMessage(), e);
        }
    }
}

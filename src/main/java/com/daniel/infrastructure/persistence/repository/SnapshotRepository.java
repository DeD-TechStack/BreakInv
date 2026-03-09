package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public final class SnapshotRepository implements ISnapshotRepository {

    public long getCash(LocalDate date) {
        try {
            return querySingleLong(
                    "SELECT value_cents FROM cash_snapshots WHERE date = ?",
                    date
            );
        } catch (RuntimeException ex) {
            if (!looksLikeMissingColumn(ex)) throw ex;

            return querySingleLong(
                    "SELECT amount_cents FROM cash_snapshots WHERE date = ?",
                    date
            );
        }
    }

    @Override
    public void setCash(LocalDate date) {
    }

    @Override
    public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) {
        return getAllInvestmentsForDate(date);
    }

    @Override
    public void setInvestimentValue(LocalDate date, long typeId, long cents) {
        // Implementar
    }

    @Override
    public Map<String, Long> seriesForInvestiments(long investimentsTypeId) {
        return seriesForInvestment(investimentsTypeId);
    }

    public void upsertCash(LocalDate date, long cashCents) {
        try {
            execUpdate("""
                INSERT INTO cash_snapshots(date, value_cents)
                VALUES(?, ?)
                ON CONFLICT(date) DO UPDATE SET value_cents = excluded.value_cents
            """, date, cashCents);
        } catch (RuntimeException ex) {
            if (!looksLikeMissingColumn(ex)) throw ex;

            execUpdate("""
                INSERT INTO cash_snapshots(date, amount_cents)
                VALUES(?, ?)
                ON CONFLICT(date) DO UPDATE SET amount_cents = excluded.amount_cents
            """, date, cashCents);
        }
    }

    public Map<Long, Long> getAllInvestmentsForDate(LocalDate date) {
        try {
            return queryInvestmentMap("""
                SELECT investment_type_id, value_cents
                FROM investment_snapshots
                WHERE date = ?
            """, date);
        } catch (RuntimeException ex) {
            if (!looksLikeMissingColumn(ex)) throw ex;

            return queryInvestmentMap("""
                SELECT investment_type_id, amount_cents
                FROM investment_snapshots
                WHERE date = ?
            """, date);
        }
    }

    public void upsertInvestment(LocalDate date, long investmentTypeId, long valueCents, String note) {
        String normalizedNote = (note == null || note.isBlank()) ? null : note.trim();

        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO investment_snapshots(date, investment_type_id, value_cents, note)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(date, investment_type_id)
                DO UPDATE SET value_cents = excluded.value_cents, note = excluded.note
            """)) {
            ps.setString(1, date.toString());
            ps.setLong(2, investmentTypeId);
            ps.setLong(3, valueCents);
            ps.setString(4, normalizedNote);
            ps.executeUpdate();
        } catch (SQLException e) {
            RuntimeException wrapped = new RuntimeException("Failed to upsert investment snapshot", e);
            if (!looksLikeMissingColumn(wrapped)) throw wrapped;

            try (Connection conn = Database.open();
                 PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO investment_snapshots(date, investment_type_id, amount_cents, note)
                    VALUES(?, ?, ?, ?)
                    ON CONFLICT(date, investment_type_id)
                    DO UPDATE SET amount_cents = excluded.amount_cents, note = excluded.note
                """)) {
                ps.setString(1, date.toString());
                ps.setLong(2, investmentTypeId);
                ps.setLong(3, valueCents);
                ps.setString(4, normalizedNote);
                ps.executeUpdate();
            } catch (SQLException e2) {
                e.addSuppressed(e2);
                throw new RuntimeException("Failed to upsert investment snapshot (legacy)", e);
            }
        }
    }

    public Map<String, Long> seriesForInvestment(long investmentTypeId) {
        try {
            return querySeries("""
                SELECT date, value_cents
                FROM investment_snapshots
                WHERE investment_type_id = ?
                ORDER BY date ASC
            """, investmentTypeId);
        } catch (RuntimeException ex) {
            if (!looksLikeMissingColumn(ex)) throw ex;

            return querySeries("""
                SELECT date, amount_cents
                FROM investment_snapshots
                WHERE investment_type_id = ?
                ORDER BY date ASC
            """, investmentTypeId);
        }
    }

    // ---------------- helpers ----------------

    private long querySingleLong(String sql, LocalDate date) {
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read cash snapshot", e);
        }
    }

    private void execUpdate(String sql, LocalDate date, long cents) {
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.setLong(2, cents);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert cash snapshot", e);
        }
    }

    private Map<Long, Long> queryInvestmentMap(String sql, LocalDate date) {
        Map<Long, Long> out = new LinkedHashMap<>();
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getLong(2));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list investment snapshots", e);
        }
    }

    private Map<String, Long> querySeries(String sql, long investmentTypeId) {
        Map<String, Long> out = new TreeMap<>();
        try (Connection conn = Database.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, investmentTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load investment series", e);
        }
    }

    private static boolean looksLikeMissingColumn(RuntimeException ex) {
        Throwable t = ex;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("no such column")) return true;
            t = t.getCause();
        }
        return false;
    }
}
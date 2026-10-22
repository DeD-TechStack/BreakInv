package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.config.Schema;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Checks a database file on a NEW read-only connection: SQLite header, BreakInv tables,
 * {@code integrity_check}, schema version, {@code foreign_key_check}, ledger replay and the content
 * fingerprint. Never writes to the file.
 */
public final class BackupVerifier {

    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private BackupVerifier() {
    }

    /**
     * @param sqliteFile        header says SQLite 3
     * @param breakinvTables    has the {@code investment_type} table
     * @param integrityOk       {@code integrity_check} returned exactly "ok"
     * @param integrityDetail   first messages when not ok (structure only, no row values)
     * @param fingerprint       content identity (null when the file could not be read)
     * @param foreignKeyIssues  rows reported by {@code foreign_key_check}
     * @param ledgerProblem     replay failure message, or null when the ledger replays (or is absent)
     */
    public record Report(boolean sqliteFile, boolean breakinvTables, boolean integrityOk, String integrityDetail,
                         DatabaseFingerprint fingerprint, long foreignKeyIssues, String ledgerProblem) {

        public int schemaVersion() {
            return fingerprint == null ? 0 : fingerprint.schemaVersion();
        }

        public boolean supportedVersion() {
            return schemaVersion() <= Schema.CURRENT_VERSION;
        }

        /** Faithful copy of {@code source}: readable, intact and with the same content. */
        public boolean faithfulTo(DatabaseFingerprint source) {
            return sqliteFile && integrityOk && fingerprint != null && fingerprint.equals(source);
        }

        /** Problems that the copy shares with its source (same content); reported, not hidden. */
        public List<String> healthWarnings() {
            List<String> w = new ArrayList<>();
            if (foreignKeyIssues > 0) {
                w.add(foreignKeyIssues + " referência(s) entre tabelas sem o registro correspondente");
            }
            if (ledgerProblem != null) w.add("o livro de movimentações não pôde ser conferido: " + ledgerProblem);
            return w;
        }

        /** Why this file cannot replace the current data, or empty when it can. */
        public Optional<String> restoreRefusal() {
            if (!sqliteFile) return Optional.of("o arquivo não é um banco de dados do BreakInv");
            if (!integrityOk) return Optional.of("o arquivo está danificado (a verificação de integridade falhou)");
            if (!breakinvTables) return Optional.of("o arquivo não é um banco de dados do BreakInv");
            if (!supportedVersion()) {
                return Optional.of("o arquivo foi criado por uma versão mais nova do BreakInv (dados na versão "
                        + schemaVersion() + "; esta versão lê até a " + Schema.CURRENT_VERSION + ")");
            }
            if (foreignKeyIssues > 0) {
                return Optional.of("o arquivo tem referências inconsistentes entre tabelas");
            }
            if (ledgerProblem != null) {
                return Optional.of("o livro de movimentações do arquivo não pôde ser conferido (" + ledgerProblem + ")");
            }
            return Optional.empty();
        }
    }

    /** Verifies {@code file} on a fresh read-only connection. */
    public static Report verify(Path file) {
        if (!hasSqliteHeader(file)) return new Report(false, false, false, "cabeçalho ausente", null, 0, null);
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties())) {
            return verify(conn);
        } catch (SQLException e) {
            return new Report(true, false, false, e.getMessage(), null, 0, null);
        }
    }

    /** Verifies the database behind {@code conn} (must not be inside a transaction). */
    public static Report verify(Connection conn) {
        String integrity;
        boolean integrityOk;
        try {
            List<String> rows = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA integrity_check(20)")) {
                while (rs.next()) rows.add(rs.getString(1));
            }
            integrityOk = rows.size() == 1 && "ok".equalsIgnoreCase(rows.get(0));
            integrity = integrityOk ? "ok" : String.join("; ", rows);
        } catch (SQLException e) {
            return new Report(true, false, false, e.getMessage(), null, 0, null);
        }
        try {
            List<String> tables = DatabaseFingerprint.userTables(conn);
            boolean breakinv = tables.contains("investment_type");
            DatabaseFingerprint fp = DatabaseFingerprint.of(conn);
            long fk = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                while (rs.next()) fk++;
            }
            String ledger = null;
            if (tables.contains("portfolio_movement")) {
                try {
                    PortfolioService.onConnection(conn).state();
                } catch (RuntimeException replay) {
                    ledger = replay.getMessage() == null ? replay.getClass().getSimpleName() : replay.getMessage();
                }
            }
            return new Report(true, breakinv, integrityOk, integrity, fp, fk, ledger);
        } catch (SQLException e) {
            return new Report(true, false, integrityOk, integrity + " / " + e.getMessage(), null, 0, null);
        }
    }

    static boolean hasSqliteHeader(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < 100) return false;
            try (InputStream in = Files.newInputStream(file)) {
                byte[] head = in.readNBytes(MAGIC.length);
                return Arrays.equals(head, MAGIC);
            }
        } catch (IOException e) {
            return false;
        }
    }
}

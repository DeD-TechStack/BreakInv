package com.daniel.infrastructure.persistence.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Applies versioned schema migrations.
 *
 * <p>Each pending version runs inside its own SQLite transaction together with the insert into
 * {@code schema_migrations}; any failure rolls the whole version back and stops. SQLite DDL is
 * transactional, so a half-applied version never survives. Versions already recorded are
 * skipped, so repeated runs are no-ops. Column additions are conditional on
 * {@code PRAGMA table_info}, so partially migrated legacy tables are handled.
 *
 * <p>The migrator never deletes or rewrites user rows. Audits that would require destructive
 * repair (orphan history) throw {@link MigrationException} instead.
 */
public final class SchemaMigrator {

    private static final Logger LOG = Logger.getLogger(SchemaMigrator.class.getName());

    /** A single schema step. */
    @FunctionalInterface
    public interface Step {
        void apply(Connection conn) throws Exception;
    }

    public record Migration(int version, String description, Step step) {
        public Migration {
            if (version <= 0) throw new IllegalArgumentException("version must be positive");
            if (step == null) throw new IllegalArgumentException("step required");
        }
    }

    public static final class MigrationException extends RuntimeException {
        private final int version;

        public MigrationException(int version, String message, Throwable cause) {
            super("Schema migration v" + version + " failed: " + message, cause);
            this.version = version;
        }

        public int version() {
            return version;
        }
    }

    private final List<Migration> migrations;

    public SchemaMigrator(List<Migration> migrations) {
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort((a, b) -> Integer.compare(a.version(), b.version()));
        this.migrations = List.copyOf(sorted);
    }

    /** Applies the project catalogue ({@link Schema#migrations()}) to {@code conn}. */
    public static void migrate(Connection conn) {
        new SchemaMigrator(Schema.migrations()).migrateAll(conn);
    }

    /** Applies every version above the recorded one, in order, one transaction each. */
    public void migrateAll(Connection conn) {
        ensureVersionTable(conn);
        int applied = Schema.appliedVersion(conn);
        int supported = migrations.stream().mapToInt(Migration::version).max().orElse(0);
        if (applied > supported) {
            throw new MigrationException(applied,
                    "database belongs to a newer application; supported version is " + supported, null);
        }
        for (Migration m : migrations) {
            if (m.version() <= applied) continue;
            applyOne(conn, m);
            applied = m.version();
        }
    }

    private void applyOne(Connection conn, Migration m) {
        boolean previousAutoCommit;
        try {
            previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new MigrationException(m.version(), "cannot begin transaction: " + e.getMessage(), e);
        }
        boolean committed = false;
        try {
            LOG.info(() -> "Applying schema migration v" + m.version() + " (" + m.description() + ")");
            m.step().apply(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_migrations(version, description, applied_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, m.version());
                ps.setString(2, m.description());
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            conn.commit();
            committed = true;
        } catch (Exception e) {
            if (e instanceof MigrationException me) throw me;
            throw new MigrationException(m.version(), e.getMessage(), e);
        } finally {
            // Any Throwable (Error included, or a failed commit) must roll the version back
            // BEFORE auto-commit is restored: setAutoCommit(true) would otherwise commit it.
            if (!committed) {
                try {
                    conn.rollback();
                } catch (SQLException rb) {
                    LOG.warning(() -> "rollback of migration v" + m.version() + " failed: " + rb.getMessage());
                }
            }
            try {
                conn.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // connection may be unusable; caller will notice on next use
            }
        }
    }

    private static void ensureVersionTable(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        description TEXT,
                        applied_at TEXT NOT NULL
                    )""");
        } catch (SQLException e) {
            throw new MigrationException(0, "cannot create schema_migrations: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Step helpers (package-private, used by the catalogue in Schema)
    // ------------------------------------------------------------------

    static void execScript(Connection conn, String script) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String statement : splitStatements(script)) {
                st.execute(statement);
            }
        }
    }

    /**
     * Splits a script on ';' except inside a CREATE TRIGGER ... BEGIN ... END; block.
     */
    static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inTrigger = false;
        for (String rawLine : script.split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("--") || line.isEmpty()) continue;
            String upper = line.toUpperCase();
            if (upper.startsWith("CREATE TRIGGER")) inTrigger = true;
            current.append(rawLine).append('\n');
            if (inTrigger) {
                if (upper.equals("END;") || upper.endsWith(" END;")) {
                    inTrigger = false;
                    out.add(stripTrailingSemicolon(current.toString()));
                    current.setLength(0);
                }
            } else if (line.endsWith(";")) {
                out.add(stripTrailingSemicolon(current.toString()));
                current.setLength(0);
            }
        }
        String rest = current.toString().strip();
        if (!rest.isEmpty()) out.add(stripTrailingSemicolon(rest));
        return out;
    }

    private static String stripTrailingSemicolon(String s) {
        String t = s.strip();
        return t.endsWith(";") ? t.substring(0, t.length() - 1) : t;
    }

    static void ensureInvestmentTypeTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS investment_type (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL
                    )""");
        }
    }

    /** ALTER TABLE ADD COLUMN for each entry whose name is not yet present. */
    static void addMissingColumns(Connection conn, String table, Map<String, String> columns) throws SQLException {
        List<String> existing = columnNames(conn, table);
        try (Statement st = conn.createStatement()) {
            for (Map.Entry<String, String> col : columns.entrySet()) {
                if (existing.contains(col.getKey())) continue;
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + col.getKey() + " " + col.getValue());
            }
        }
    }

    static List<String> columnNames(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    /**
     * Fails closed when any history row references a missing investment_type. Nothing is
     * deleted; the message lists every offending table and count so the user can reconcile.
     */
    static void auditOrphans(Connection conn, Map<String, String> queries) throws SQLException {
        List<String> problems = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            for (Map.Entry<String, String> q : queries.entrySet()) {
                try (ResultSet rs = st.executeQuery(q.getValue())) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        problems.add(q.getKey() + "=" + rs.getLong(1));
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new MigrationException(3, "orphan history rows referencing missing investment_type ("
                    + String.join(", ", problems) + "); nothing was changed — reconcile or restore the "
                    + "investment_type rows before migrating", null);
        }
    }
}

package com.daniel.infrastructure.persistence.backup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Content identity of a SQLite database: schema version, row count per table and a SHA-256 over
 * the schema text and every row of every non-internal table.
 *
 * <p>Rows are read through SQLite's own {@code quote()} rendering and ordered by those columns, so
 * the digest does not depend on rowids (which VACUUM may renumber) or on page layout. Two databases
 * with the same fingerprint hold the same rows; the fingerprint carries no financial values and
 * cannot be reversed into them.
 */
public record DatabaseFingerprint(int schemaVersion, SortedMap<String, Long> rowCounts, String contentSha256) {

    /** Tables that do not count as user data (bookkeeping and app settings). */
    static final Set<String> NON_USER_TABLES = Set.of("schema_migrations", "app_settings");

    private static final char FIELD = '\u001f';
    private static final char ROW = '\u001e';

    public DatabaseFingerprint {
        rowCounts = Collections.unmodifiableSortedMap(new TreeMap<>(rowCounts));
    }

    public long count(String table) {
        return rowCounts.getOrDefault(table, 0L);
    }

    /** Rows outside bookkeeping/settings tables; zero means a new or empty database. */
    public long userRows() {
        long n = 0;
        for (Map.Entry<String, Long> e : rowCounts.entrySet()) {
            if (!NON_USER_TABLES.contains(e.getKey())) n += e.getValue();
        }
        return n;
    }

    public boolean hasUserData() {
        return userRows() > 0;
    }

    /** Full fingerprint (reads every row). */
    public static DatabaseFingerprint of(Connection conn) throws SQLException {
        MessageDigest md = sha256();
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT type, name, tbl_name, sql FROM sqlite_master "
                    + "WHERE name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY type, name")) {
                while (rs.next()) {
                    for (int i = 1; i <= 4; i++) field(md, rs.getString(i));
                    md.update((byte) ROW);
                }
            }
            List<String> tables = userTables(conn);
            SortedMap<String, Long> counts = new TreeMap<>();
            for (String table : tables) {
                List<String> cols = columns(conn, table);
                field(md, "T:" + table);
                long n = 0;
                if (!cols.isEmpty()) {
                    StringBuilder select = new StringBuilder("SELECT ");
                    StringBuilder order = new StringBuilder(" ORDER BY ");
                    for (int i = 0; i < cols.size(); i++) {
                        if (i > 0) {
                            select.append(", ");
                            order.append(", ");
                        }
                        select.append("quote(").append(ident(cols.get(i))).append(")");
                        order.append(i + 1);
                    }
                    select.append(" FROM ").append(ident(table)).append(order);
                    try (ResultSet rs = st.executeQuery(select.toString())) {
                        while (rs.next()) {
                            for (int i = 1; i <= cols.size(); i++) field(md, rs.getString(i));
                            md.update((byte) ROW);
                            n++;
                        }
                    }
                }
                counts.put(table, n);
            }
            int version = tables.contains("schema_migrations") ? maxVersion(st) : 0;
            return new DatabaseFingerprint(version, counts, HexFormat.of().formatHex(md.digest()));
        }
    }

    /** Row counts only (cheap): used to decide whether there is anything to copy. */
    public static SortedMap<String, Long> countRows(Connection conn) throws SQLException {
        SortedMap<String, Long> counts = new TreeMap<>();
        try (Statement st = conn.createStatement()) {
            for (String table : userTables(conn)) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + ident(table))) {
                    counts.put(table, rs.next() ? rs.getLong(1) : 0L);
                }
            }
        }
        return counts;
    }

    static long userRows(Map<String, Long> counts) {
        long n = 0;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (!NON_USER_TABLES.contains(e.getKey())) n += e.getValue();
        }
        return n;
    }

    static List<String> userTables(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                     + "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static int schemaVersion(Connection conn) throws SQLException {
        if (!userTables(conn).contains("schema_migrations")) return 0;
        try (Statement st = conn.createStatement()) {
            return maxVersion(st);
        }
    }

    private static int maxVersion(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static List<String> columns(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + ident(table) + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static void field(MessageDigest md, String value) {
        if (value == null) {
            md.update((byte) 0);
        } else {
            md.update((byte) 1);
            md.update(value.getBytes(StandardCharsets.UTF_8));
        }
        md.update((byte) FIELD);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

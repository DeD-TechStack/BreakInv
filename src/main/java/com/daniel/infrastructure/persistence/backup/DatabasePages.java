package com.daniel.infrastructure.persistence.backup;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The pages of the data file (BRK-32 S2:RF-01): {@code page_size}, {@code page_count} and {@code freelist_count}, read on
 * a read-only connection that writes nothing. A reading error comes up as the exception and the caller decides.
 */
public final class DatabasePages {

    /**
     * @param pageSize      bytes in a page
     * @param pageCount     pages in the file
     * @param freelistCount unused pages that SQLite reuses on the next writes
     */
    public record Reading(long pageSize, long pageCount, long freelistCount) {

        /** Space the file already holds and does not use: free pages times the page size. */
        public long reusableBytes() {
            return freelistCount * pageSize;
        }
    }

    private final Path databaseFile;

    public DatabasePages(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
    }

    /** Reads the three numbers of the data file on a new read-only connection. */
    public Reading read() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databaseFile, config.toProperties())) {
            return read(conn);
        }
    }

    /** The same reading on a connection the caller owns. */
    public static Reading read(Connection conn) throws SQLException {
        return new Reading(pragma(conn, "page_size"), pragma(conn, "page_count"), pragma(conn, "freelist_count"));
    }

    private static long pragma(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            if (!rs.next()) throw new SQLException("PRAGMA " + name + " não devolveu valor");
            return rs.getLong(1);
        }
    }
}

package com.daniel.infrastructure.persistence.config;

import org.sqlite.SQLiteConfig;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * SQLite access point.
 *
 * <p>Two kinds of connections are offered on the same configured JDBC URL:
 * <ul>
 *   <li>{@link #open()} — the long-lived shared connection used by the legacy repositories and
 *       the UI. Never put in a transaction by this class.</li>
 *   <li>{@link #newConnection()} — an independent connection the caller owns and closes. Used
 *       by {@link #inTransaction(TransactionBody)} and by services that need an atomic unit of
 *       work isolated from whatever the shared connection is doing.</li>
 * </ul>
 * Every connection is created with {@code PRAGMA foreign_keys=ON}, a busy timeout and
 * {@code BEGIN IMMEDIATE} transaction mode. Versioned migrations ({@link SchemaMigrator}) run
 * once per configured URL, on the first connection obtained; a migration failure (e.g. orphan
 * audit) propagates and nothing is changed.
 *
 * <p>{@code jdbc:sqlite::memory:} is accepted for tests and mapped to a named shared-cache
 * in-memory database so that {@link #open()} and {@link #newConnection()} see the same data;
 * the database lives while the shared connection stays open ({@link #close()} discards it).
 */
public final class Database {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());
    private static final String DEFAULT_URL = buildDefaultUrl();
    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final AtomicInteger MEMORY_SEQ = new AtomicInteger();

    private static String jdbcUrl = DEFAULT_URL;
    private static String migratedUrl = null;
    private static Connection connection = null;
    private static PreMigrationHook preMigrationHook = null;

    private static String buildDefaultUrl() {
        String dir = System.getProperty("user.home") + File.separator + "BreakInv";
        new File(dir).mkdirs();
        return "jdbc:sqlite:" + dir + File.separator + "breakinv.db";
    }

    private Database() {
        // Singleton
    }

    /** Unit of work executed by {@link #inTransaction(TransactionBody)}. */
    @FunctionalInterface
    public interface TransactionBody<T> {
        T run(Connection conn) throws Exception;
    }

    /**
     * Called once, before a migration changes an EXISTING database file (one with tables whose
     * recorded version is below {@link Schema#CURRENT_VERSION}). Throwing aborts the migration and
     * the open, leaving the file untouched. {@code candidate} is in auto-commit mode and must not be
     * closed by the hook.
     */
    @FunctionalInterface
    public interface PreMigrationHook {
        void beforeMigration(Connection candidate, Path databaseFile) throws Exception;
    }

    /** What {@link #runExclusive(ExclusiveBody)} offers while every other caller waits. */
    public interface ExclusiveAccess {
        /** Configured connection on the current URL, WITHOUT running migrations. Caller closes it. */
        Connection newConnection() throws SQLException;

        /** Re-creates the shared connection, migrating the current content if needed. */
        Connection reopenShared();
    }

    @FunctionalInterface
    public interface ExclusiveBody<T> {
        T run(ExclusiveAccess access) throws Exception;
    }

    /** Installs (or clears, with null) the hook run before migrating an existing database. */
    public static synchronized void setPreMigrationHook(PreMigrationHook hook) {
        preMigrationHook = hook;
    }

    /** Database file behind the configured URL; empty for in-memory databases. */
    public static synchronized Optional<Path> currentFile() {
        return fileOf(jdbcUrl);
    }

    static Optional<Path> fileOf(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) return Optional.empty();
        String rest = url.substring("jdbc:sqlite:".length());
        if (rest.isEmpty() || rest.contains(":memory:") || rest.contains("mode=memory")) return Optional.empty();
        int query = rest.indexOf('?');
        if (query >= 0) rest = rest.substring(0, query);
        if (rest.startsWith("file:")) rest = rest.substring("file:".length());
        if (rest.isEmpty()) return Optional.empty();
        return Optional.of(Path.of(rest).toAbsolutePath().normalize());
    }

    /**
     * Runs {@code body} with the shared connection closed and every other {@link #open()},
     * {@link #newConnection()} and {@link #inTransaction} caller waiting. Used to replace the
     * database content (restore). After the body, the next {@link #open()} migrates whatever content
     * is there; the body may call {@link ExclusiveAccess#reopenShared()} itself to see a migration
     * failure while it can still undo its change. In-memory databases are refused.
     */
    public static synchronized <T> T runExclusive(ExclusiveBody<T> body) throws Exception {
        if (fileOf(jdbcUrl).isEmpty()) throw new IllegalStateException("exclusive access needs a database file");
        close();
        migratedUrl = null;
        return body.run(new ExclusiveAccess() {
            @Override
            public Connection newConnection() throws SQLException {
                return createConfiguredConnection();
            }

            @Override
            public Connection reopenShared() {
                return open();
            }
        });
    }

    /**
     * Override the JDBC URL before the first {@link #open()} call.
     * Closes any existing connection first so the new URL takes effect.
     * Intended for tests only (e.g. {@code jdbc:sqlite::memory:} or a temp file).
     * Production code should never call this method.
     */
    public static synchronized void configure(String url) {
        close();
        migratedUrl = null;
        jdbcUrl = (url != null) ? resolveUrl(url) : DEFAULT_URL;
    }

    private static String resolveUrl(String url) {
        if ("jdbc:sqlite::memory:".equals(url) || "jdbc:sqlite:".equals(url)) {
            return "jdbc:sqlite:file:breakinv_mem_" + MEMORY_SEQ.incrementAndGet() + "?mode=memory&cache=shared";
        }
        return url;
    }

    /** Currently configured JDBC URL (after :memory: resolution). */
    public static synchronized String currentUrl() {
        return jdbcUrl;
    }

    /** Shared, long-lived connection (auto-commit). Migrates on first use. */
    public static synchronized Connection open() {
        try {
            if (connection == null || connection.isClosed()) {
                if (connection != null) LOG.warning("Connection estava fechada, reabrindo...");
                LOG.fine("Criando conexão com o banco de dados...");
                Connection candidate = createConfiguredConnection();
                try {
                    migrateIfNeeded(candidate);
                    connection = candidate;
                } catch (RuntimeException failure) {
                    try { candidate.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                    connection = null;
                    migratedUrl = null;
                    throw failure;
                }
                LOG.fine("Banco de dados pronto.");
            }
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao conectar ao banco: " + e.getMessage(), e);
        }
    }

    /**
     * Independent connection on the configured URL with foreign keys and busy timeout already
     * set, in auto-commit mode. The caller must close it. Does not re-run migrations once they
     * have been applied for the configured URL; if nothing has migrated yet, it migrates first.
     */
    public static synchronized Connection newConnection() {
        try {
            if (migratedUrl == null || !migratedUrl.equals(jdbcUrl)) {
                // make sure the schema exists (and keep a shared handle for :memory: databases)
                open();
            }
            return createConfiguredConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao abrir conexão independente: " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code body} on a fresh independent connection inside one {@code BEGIN IMMEDIATE}
     * transaction. Commits when the body returns, rolls back on any exception (rethrown as-is
     * when unchecked, wrapped in {@link RuntimeException} otherwise) and always closes the
     * connection. The shared connection from {@link #open()} is never involved.
     */
    public static <T> T inTransaction(TransactionBody<T> body) {
        try (Connection conn = newConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = body.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rb) {
                    e.addSuppressed(rb);
                }
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro de transação: " + e.getMessage(), e);
        }
    }

    private static Connection createConfiguredConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        Properties props = config.toProperties();
        return DriverManager.getConnection(jdbcUrl, props);
    }

    private static void migrateIfNeeded(Connection conn) {
        if (migratedUrl != null && migratedUrl.equals(jdbcUrl)) return;
        Optional<Path> file = fileOf(jdbcUrl);
        if (preMigrationHook != null && file.isPresent() && SchemaMigrator.wouldChangeExisting(conn)) {
            try {
                preMigrationHook.beforeMigration(conn, file.get());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        SchemaMigrator.migrate(conn);
        migratedUrl = jdbcUrl;
    }

    public static synchronized void close() {
        try {
            if (connection != null) {
                if (!connection.isClosed()) {
                    connection.close();
                }
                connection = null;
                if (jdbcUrl.contains("mode=memory")) {
                    migratedUrl = null; // in-memory database is discarded with its last connection
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

package com.daniel.infrastructure.persistence.config;

import org.sqlite.SQLiteConfig;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
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
    private static volatile WriteGate writeGate = null;

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

    /**
     * Storage gate consulted at every write boundary (BRK-31, MAPA-ESCRITAS-BRK31): returns the refusal message, or
     * null to let the write go on.
     */
    @FunctionalInterface
    public interface WriteGate {
        String refusal();
    }

    /** Installs (or clears, with null) the storage gate; without one every write goes on. */
    public static void setWriteGate(WriteGate gate) {
        writeGate = gate;
    }

    /**
     * Called first at each write boundary, before any SQL: throws {@link StorageRefusedException} when the gate
     * refuses, so nothing is written.
     */
    public static void checkWrite() {
        WriteGate gate = writeGate;
        if (gate == null) return;
        String refusal = gate.refusal();
        if (refusal != null) throw new StorageRefusedException(refusal);
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
     *
     * <p>Every failure of the write (BRK-31 u4b) carries the {@link RollbackState} fixed by
     * {@link RollbackDecision}, read from the exception that is thrown with
     * {@link #rollbackStateOf(Throwable)}; the exception itself is the same as it always was. A failure of
     * the closing alone, after a commit without error, is logged and the result is returned.
     */
    public static <T> T inTransaction(TransactionBody<T> body) {
        checkWrite();
        Connection conn = null;
        RollbackDecision.Stage stage = RollbackDecision.Stage.BEFORE_BODY;
        RollbackDecision.RollbackResult rollback = RollbackDecision.RollbackResult.NOT_RUN;
        RuntimeException failure = null;
        T result = null;
        try {
            conn = newConnection();
            conn.setAutoCommit(false);
            stage = RollbackDecision.Stage.BODY;
            result = body.run(conn);
            stage = RollbackDecision.Stage.COMMIT;
            conn.commit();
            stage = RollbackDecision.Stage.CLOSE;
        } catch (Exception e) {
            failure = thrownFor(stage, e);
            if (stage != RollbackDecision.Stage.BEFORE_BODY) rollback = rollbackStatement(conn, failure);
        } catch (Error e) {
            closeSuppressing(conn, e);
            throw e;
        }
        SQLException closeFailure = closeConnection(conn);
        if (failure == null) return committed(result, closeFailure);
        if (closeFailure != null) failure.addSuppressed(closeFailure);
        RollbackState state = RollbackDecision.decide(stage, rollback) == RollbackDecision.Outcome.CONFIRMED
                ? RollbackState.CONFIRMED : RollbackState.UNCONFIRMED;
        int rule = RollbackDecision.ruleOf(stage, rollback);
        STATES.put(failure, new Recorded(state, rule));
        LOG.log(Level.WARNING, "Falha ao gravar; desfazimento " + state + " (regra " + rule + " do RF-01)", failure);
        throw failure;
    }

    /**
     * Rule 5 of {@link RollbackDecision}: the commit went without error, so the write is done. A failure of the
     * closing alone ({@code closeFailure}, or null) is logged and the result is returned.
     */
    static <T> T committed(T result, SQLException closeFailure) {
        if (closeFailure == null) return result;
        if (RollbackDecision.decide(RollbackDecision.Stage.CLOSE, RollbackDecision.RollbackResult.NOT_RUN)
                == RollbackDecision.Outcome.SUCCESS_WITH_WARNING) {
            LOG.log(Level.WARNING, "Falha ao fechar a conexão depois de gravar; os dados foram gravados.", closeFailure);
        }
        return result;
    }

    /** The exception {@link #inTransaction} has always thrown for a failure at {@code stage}. */
    private static RuntimeException thrownFor(RollbackDecision.Stage stage, Exception e) {
        if (stage == RollbackDecision.Stage.BEFORE_BODY) {
            if (e instanceof RuntimeException re) return re; // opening the connection
            return new RuntimeException("Erro de transação: " + e.getMessage(), e); // the BEGIN
        }
        if (e instanceof RuntimeException re) return re;
        return new RuntimeException(e.getMessage(), e);
    }

    /**
     * The ROLLBACK statement itself, not {@code Connection.rollback()}: the driver adds a new {@code begin
     * immediate} to that one and the two errors could not be told apart. Its failure goes on {@code failure}.
     */
    private static RollbackDecision.RollbackResult rollbackStatement(Connection conn, RuntimeException failure) {
        try (Statement st = conn.createStatement()) {
            st.execute("ROLLBACK");
            return RollbackDecision.RollbackResult.OK;
        } catch (SQLException | RuntimeException rb) {
            failure.addSuppressed(rb);
            return rb instanceof SQLException sql
                    ? RollbackDecision.resultOf(sql) : RollbackDecision.RollbackResult.FAILED;
        }
    }

    private static SQLException closeConnection(Connection conn) {
        if (conn == null) return null;
        try {
            conn.close();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static void closeSuppressing(Connection conn, Error error) {
        SQLException closeFailure = closeConnection(conn);
        if (closeFailure != null) error.addSuppressed(closeFailure);
    }

    /** Failures of ledger writes and the state of their undoing; weak, so a thrown exception is not kept alive. */
    private static final Map<Throwable, Recorded> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    /** What is known of the undoing of one failed write: the state and the number of the rule that fixed it. */
    private record Recorded(RollbackState state, int rule) {
    }

    private static Optional<Recorded> recordedFor(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            Recorded recorded = STATES.get(t);
            if (recorded != null) return Optional.of(recorded);
            if (t.getCause() == t) break;
        }
        return Optional.empty();
    }

    /**
     * The state of the undoing of a failed ledger write, read from the exception {@link #inTransaction} threw or
     * from any exception that wraps it (the first one found, from the outside in); empty when the failure is
     * not from such a write.
     */
    public static Optional<RollbackState> rollbackStateOf(Throwable failure) {
        return recordedFor(failure).map(Recorded::state);
    }

    /** Number (1 to 5) of the rule of U4B:RF-01 that fixed the state read by {@link #rollbackStateOf}; for the log and tests. */
    public static Optional<Integer> rollbackRuleOf(Throwable failure) {
        return recordedFor(failure).map(Recorded::rule);
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
        if (file.isPresent() && SchemaMigrator.wouldChangeExisting(conn)) {
            if (preMigrationHook == null) {
                throw new IllegalStateException("Os dados precisam ser atualizados para esta versão, mas a cópia de "
                        + "segurança antes da atualização não está disponível; os dados não foram alterados.");
            }
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

package com.daniel.infrastructure.persistence.config;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public final class Database {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());
    private static final String DEFAULT_URL = buildDefaultUrl();
    private static String jdbcUrl = DEFAULT_URL;

    private static String buildDefaultUrl() {
        String dir = System.getProperty("user.home") + File.separator + "BreakInv";
        new File(dir).mkdirs();
        return "jdbc:sqlite:" + dir + File.separator + "breakinv.db";
    }

    private static Connection connection = null;

    private Database() {
        // Singleton
    }

    /**
     * Override the JDBC URL before the first {@link #open()} call.
     * Closes any existing connection first so the new URL takes effect.
     * Intended for tests only (e.g. {@code jdbc:sqlite::memory:}).
     * Production code should never call this method.
     */
    public static synchronized void configure(String url) {
        close();
        jdbcUrl = (url != null) ? url : DEFAULT_URL;
    }

    public static synchronized Connection open() {
        try {
            if (connection == null) {
                LOG.fine("Criando conexão com o banco de dados...");
                connection = DriverManager.getConnection(jdbcUrl);
                runMigrationIfNeeded(); // must run before createTables so new-column indexes succeed
                createTables();
                LOG.fine("Banco de dados pronto.");
            }

            if (connection.isClosed()) {
                LOG.warning("Connection estava fechada, reabrindo...");
                connection = DriverManager.getConnection(jdbcUrl);
                runMigrationIfNeeded();
                createTables();
            }

            return connection;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao conectar ao banco: " + e.getMessage(), e);
        }
    }

    private static void createTables() {
        try (Statement stmt = connection.createStatement()) {
            String sql = Schema.createTables();

            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }

        } catch (SQLException e) {
            LOG.severe("Erro ao criar tabelas: " + e.getMessage());
            throw new RuntimeException("Erro ao criar tabelas: " + e.getMessage(), e);
        }
    }

    /**
     * Applies the schema migration when the investment_type table already exists
     * but is missing newer columns. Skipped for fresh databases — createTables()
     * will build the table with all current columns.
     */
    private static void runMigrationIfNeeded() {
        if (!investmentTypeTableExists()) return;
        if (!Schema.needsMigration(connection)) return;
        LOG.info("Schema migration needed — applying...");
        try (Statement stmt = connection.createStatement()) {
            for (String statement : Schema.migrationScript().split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
            LOG.info("Schema migration applied.");
        } catch (SQLException e) {
            throw new RuntimeException("Schema migration failed: " + e.getMessage(), e);
        }
    }

    private static boolean investmentTypeTableExists() {
        try (Statement stmt = connection.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='investment_type'")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    public static void close() {
        try {
            if (connection != null) {
                if (!connection.isClosed()) {
                    connection.close();
                }
                connection = null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
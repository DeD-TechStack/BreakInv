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
                createTables();
                LOG.fine("Banco de dados pronto.");
            }

            if (connection.isClosed()) {
                LOG.warning("Connection estava fechada, reabrindo...");
                connection = DriverManager.getConnection(jdbcUrl);
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
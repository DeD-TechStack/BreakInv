package com.daniel.infrastructure.persistence.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {

    private static final String DEFAULT_URL = "jdbc:sqlite:investment_tracker.db";
    private static String jdbcUrl = DEFAULT_URL;
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
                System.out.println("🔧 Criando conexão com o banco de dados...");
                connection = DriverManager.getConnection(jdbcUrl);

                // Criar tabelas
                System.out.println("🔧 Criando tabelas...");
                createTables();
                System.out.println("✅ Banco de dados pronto!");
            }

            if (connection.isClosed()) {
                System.out.println("⚠️ Connection estava fechada, reabrindo...");
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
            System.err.println("⚠️ Erro ao criar tabelas: " + e.getMessage());
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
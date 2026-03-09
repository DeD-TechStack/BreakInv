package com.daniel.infrastructure.persistence.config;

public final class Schema {

    private Schema() {
    }

    public static String createTables() {
        return """
                -- Tipos de Investimento
                CREATE TABLE IF NOT EXISTS investment_type (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    category TEXT,
                    liquidity TEXT,
                    investment_date TEXT,
                    profitability REAL,
                    invested_value REAL,
                    type_of_investment TEXT,
                    index_type TEXT,
                    index_percentage REAL,
                    ticker TEXT,
                    purchase_price REAL,
                    quantity INTEGER
                );
                
                CREATE INDEX IF NOT EXISTS idx_investment_ticker ON investment_type(ticker);
                CREATE INDEX IF NOT EXISTS idx_investment_date ON investment_type(investment_date);
                CREATE INDEX IF NOT EXISTS idx_investment_category ON investment_type(category);
                
                -- Fluxos
                CREATE TABLE IF NOT EXISTS flows (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    date TEXT NOT NULL,
                    from_kind TEXT NOT NULL,
                    from_investment_type_id INTEGER,
                    to_kind TEXT NOT NULL,
                    to_investment_type_id INTEGER,
                    amount_cents INTEGER NOT NULL,
                    note TEXT,
                    FOREIGN KEY (from_investment_type_id) REFERENCES investment_type(id) ON DELETE CASCADE,
                    FOREIGN KEY (to_investment_type_id) REFERENCES investment_type(id) ON DELETE CASCADE
                );
                
                -- Snapshots de Caixa
                CREATE TABLE IF NOT EXISTS cash_snapshots (
                    date TEXT PRIMARY KEY,
                    value_cents INTEGER NOT NULL
                );
                
                -- Snapshots de Investimentos
                CREATE TABLE IF NOT EXISTS investment_snapshots (
                    date TEXT NOT NULL,
                    investment_type_id INTEGER NOT NULL,
                    value_cents INTEGER NOT NULL,
                    note TEXT,
                    PRIMARY KEY (date, investment_type_id),
                    FOREIGN KEY (investment_type_id) REFERENCES investment_type(id) ON DELETE CASCADE
                );
                
                -- Transações (append-only: compra/venda)
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    date TEXT NOT NULL,
                    investment_type_id INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    ticker TEXT,
                    quantity INTEGER,
                    unit_price_cents INTEGER,
                    total_cents INTEGER NOT NULL,
                    note TEXT,
                    FOREIGN KEY (investment_type_id) REFERENCES investment_type(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_transactions_date
                    ON transactions(date DESC);

                CREATE INDEX IF NOT EXISTS idx_transactions_type_id
                    ON transactions(investment_type_id);

                -- Índices para performance
                CREATE INDEX IF NOT EXISTS idx_flows_date
                    ON flows(date DESC);
                
                CREATE INDEX IF NOT EXISTS idx_flows_from_type 
                    ON flows(from_investment_type_id);
                
                CREATE INDEX IF NOT EXISTS idx_flows_to_type 
                    ON flows(to_investment_type_id);
                
                CREATE INDEX IF NOT EXISTS idx_investment_snapshots_date 
                    ON investment_snapshots(date DESC);
                
                CREATE INDEX IF NOT EXISTS idx_investment_snapshots_type
                    ON investment_snapshots(investment_type_id);

                -- Configurações do app
                CREATE TABLE IF NOT EXISTS app_settings (
                    key TEXT PRIMARY KEY,
                    value TEXT
                );
                """;
    }

    public static String migrationScript() {
        return """
                -- Adicionar novas colunas se não existirem
                ALTER TABLE investment_type ADD COLUMN type_of_investment TEXT;
                ALTER TABLE investment_type ADD COLUMN index_type TEXT;
                ALTER TABLE investment_type ADD COLUMN index_percentage REAL;
                ALTER TABLE investment_type ADD COLUMN ticker TEXT;
                ALTER TABLE investment_type ADD COLUMN purchase_price REAL;
                ALTER TABLE investment_type ADD COLUMN quantity INTEGER;
                """;
    }

    public static boolean needsMigration(java.sql.Connection conn) {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("PRAGMA table_info(investment_type)")) {

            boolean hasTypeField = false;
            while (rs.next()) {
                String colName = rs.getString("name");
                if ("type_of_investment".equals(colName)) {
                    hasTypeField = true;
                    break;
                }
            }
            return !hasTypeField;

        } catch (java.sql.SQLException e) {
            return true;
        }
    }
}
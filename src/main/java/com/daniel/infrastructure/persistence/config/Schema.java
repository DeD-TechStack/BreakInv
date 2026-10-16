package com.daniel.infrastructure.persistence.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema definition and versioned migration catalogue.
 *
 * <p>Versions (applied by {@link SchemaMigrator}, one SQLite transaction per version,
 * recorded in {@code schema_migrations}):
 * <ol>
 *   <li>baseline — the six historical tables ({@link #createTables()}), plus any
 *       {@code investment_type} column missing on legacy/partial databases.</li>
 *   <li>investment metadata — {@code investment_type.position_mode}, {@code archived_at},
 *       {@code note}.</li>
 *   <li>ledger — orphan audit (fails closed), {@code portfolio_movement} (append-only ledger,
 *       FK {@code ON DELETE RESTRICT}), delete guard trigger on {@code investment_type},
 *       and the E3 tables {@code allocation_plan}, {@code allocation_item},
 *       {@code allocation_selection}, {@code simulation_scenario}.</li>
 * </ol>
 *
 * <p>{@link #createTables()}, {@link #migrationScript()} and {@link #needsMigration(Connection)}
 * keep their historical (version 1) meaning for backwards compatibility; the current-state
 * check is {@link #isCurrent(Connection)} / {@link #appliedVersion(Connection)}.
 */
public final class Schema {

    /** Highest schema version known by this build. */
    public static final int CURRENT_VERSION = 3;

    private Schema() {
    }

    // =====================================================================
    // Version 1 — baseline (historical DDL, unchanged)
    // =====================================================================

    /** Columns of investment_type at version 1 (name → declared type), excluding id. */
    static Map<String, String> investmentTypeBaselineColumns() {
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("name", "TEXT NOT NULL DEFAULT ''");
        cols.put("category", "TEXT");
        cols.put("liquidity", "TEXT");
        cols.put("investment_date", "TEXT");
        cols.put("profitability", "REAL");
        cols.put("invested_value", "REAL");
        cols.put("type_of_investment", "TEXT");
        cols.put("index_type", "TEXT");
        cols.put("index_percentage", "REAL");
        cols.put("ticker", "TEXT");
        cols.put("purchase_price", "REAL");
        cols.put("quantity", "INTEGER");
        return cols;
    }

    /** Baseline DDL (version 1). Idempotent: every statement uses IF NOT EXISTS. */
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

    /** Alias of {@link #createTables()} that makes the "version 1 / legacy" intent explicit. */
    public static String legacyCreateTables() {
        return createTables();
    }

    /**
     * Historical column script (version 1 → adds the columns introduced after the first
     * release). Kept for compatibility; {@link SchemaMigrator} adds columns individually and
     * only when missing, so partially migrated tables are supported.
     */
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

    /**
     * Historical check: {@code true} when investment_type lacks {@code type_of_investment}
     * (i.e. the version 1 column migration is pending). Does NOT cover versions 2+;
     * use {@link #isCurrent(Connection)} for the full state.
     */
    public static boolean needsMigration(Connection conn) {
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

        } catch (SQLException e) {
            return true;
        }
    }

    // =====================================================================
    // Version bookkeeping
    // =====================================================================

    /** Highest version recorded in {@code schema_migrations}; 0 when the table is absent. */
    public static int appliedVersion(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public static boolean isCurrent(Connection conn) {
        return appliedVersion(conn) >= CURRENT_VERSION;
    }

    // =====================================================================
    // Version 2 — investment_type metadata
    // =====================================================================

    /**
     * Columns added at version 2 (name → declared type):
     * <ul>
     *   <li>{@code position_mode} TEXT NULL — {@code QUANTITY} or {@code VALUE} once an
     *       opening marker exists; NULL means "legacy / not yet recognized".</li>
     *   <li>{@code archived_at} TEXT NULL — ISO date-time when archived; NULL = active.</li>
     *   <li>{@code note} TEXT NULL — free text attached to the registration.</li>
     * </ul>
     */
    static Map<String, String> investmentTypeMetadataColumns() {
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("position_mode", "TEXT");
        cols.put("archived_at", "TEXT");
        cols.put("note", "TEXT");
        return cols;
    }

    // =====================================================================
    // Version 3 — ledger + E3 tables
    // =====================================================================

    /** Tables whose rows must reference an existing investment_type before version 3 applies. */
    static Map<String, String> orphanAuditQueries() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("transactions", "SELECT COUNT(*) FROM transactions t "
                + "WHERE NOT EXISTS (SELECT 1 FROM investment_type i WHERE i.id = t.investment_type_id)");
        q.put("investment_snapshots", "SELECT COUNT(*) FROM investment_snapshots s "
                + "WHERE NOT EXISTS (SELECT 1 FROM investment_type i WHERE i.id = s.investment_type_id)");
        q.put("flows", "SELECT COUNT(*) FROM flows f WHERE "
                + "(f.from_investment_type_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM investment_type i WHERE i.id = f.from_investment_type_id)) OR "
                + "(f.to_investment_type_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM investment_type i WHERE i.id = f.to_investment_type_id))");
        return q;
    }

    /** DDL of version 3. Idempotent. */
    public static String ledgerTables() {
        return """
                -- Ledger append-only de movimentações (fonte de verdade de posição, custo e caixa)
                CREATE TABLE IF NOT EXISTS portfolio_movement (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    intent_key TEXT NOT NULL UNIQUE,
                    payload_hash TEXT NOT NULL,
                    date TEXT NOT NULL,
                    kind TEXT NOT NULL CHECK (kind IN ('OPENING','DEPOSIT','WITHDRAWAL','BUY','SELL','ADJUSTMENT','REVERSAL','RECONCILIATION')),
                    investment_type_id INTEGER REFERENCES investment_type(id) ON DELETE RESTRICT,
                    position_mode TEXT CHECK (position_mode IS NULL OR position_mode IN ('QUANTITY','VALUE')),
                    quantity INTEGER CHECK (quantity IS NULL OR kind = 'ADJUSTMENT' OR quantity >= 0),
                    unit_price TEXT,
                    amount_cents INTEGER NOT NULL,
                    classification TEXT,
                    reason TEXT,
                    note TEXT,
                    reverses_id INTEGER UNIQUE REFERENCES portfolio_movement(id) ON DELETE RESTRICT,
                    reconciliation_flag INTEGER NOT NULL DEFAULT 0 CHECK (reconciliation_flag IN (0,1)),
                    source TEXT NOT NULL DEFAULT 'USER',
                    created_at TEXT NOT NULL,
                    CHECK (kind <> 'REVERSAL' OR reverses_id IS NOT NULL),
                    CHECK (kind <> 'ADJUSTMENT' OR (classification IS NOT NULL AND reason IS NOT NULL))
                );

                CREATE INDEX IF NOT EXISTS idx_portfolio_movement_order
                    ON portfolio_movement(date, id);

                CREATE INDEX IF NOT EXISTS idx_portfolio_movement_investment
                    ON portfolio_movement(investment_type_id);

                -- Guarda de histórico: cadastro com vínculos não pode ser apagado fisicamente
                CREATE TRIGGER IF NOT EXISTS trg_investment_type_delete_guard
                BEFORE DELETE ON investment_type
                FOR EACH ROW
                WHEN EXISTS (SELECT 1 FROM portfolio_movement m WHERE m.investment_type_id = OLD.id)
                  OR EXISTS (SELECT 1 FROM transactions t WHERE t.investment_type_id = OLD.id)
                  OR EXISTS (SELECT 1 FROM investment_snapshots s WHERE s.investment_type_id = OLD.id)
                  OR EXISTS (SELECT 1 FROM flows f WHERE f.from_investment_type_id = OLD.id OR f.to_investment_type_id = OLD.id)
                BEGIN
                    SELECT RAISE(ABORT, 'investment_type possui histórico; arquive em vez de excluir');
                END;

                -- E3: planos de alocação (classes AllocationPlan/SavedAnalysisRepository fora desta unidade)
                CREATE TABLE IF NOT EXISTS allocation_plan (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS allocation_item (
                    plan_id INTEGER NOT NULL REFERENCES allocation_plan(id) ON DELETE CASCADE,
                    category TEXT NOT NULL,
                    weight_bps INTEGER NOT NULL CHECK (weight_bps BETWEEN 0 AND 10000),
                    PRIMARY KEY (plan_id, category)
                );

                CREATE TABLE IF NOT EXISTS allocation_selection (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    plan_id INTEGER REFERENCES allocation_plan(id) ON DELETE SET NULL
                );

                -- E3: cenários de simulação salvos
                CREATE TABLE IF NOT EXISTS simulation_scenario (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    parameters_json TEXT NOT NULL,
                    calculation_version INTEGER NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """;
    }

    // =====================================================================
    // Migration catalogue
    // =====================================================================

    static List<SchemaMigrator.Migration> migrations() {
        return List.of(
                new SchemaMigrator.Migration(1, "baseline", conn -> {
                    SchemaMigrator.ensureInvestmentTypeTable(conn);
                    SchemaMigrator.addMissingColumns(conn, "investment_type", investmentTypeBaselineColumns());
                    SchemaMigrator.execScript(conn, createTables());
                }),
                new SchemaMigrator.Migration(2, "investment_type metadata", conn ->
                        SchemaMigrator.addMissingColumns(conn, "investment_type", investmentTypeMetadataColumns())),
                new SchemaMigrator.Migration(3, "ledger and saved analyses", conn -> {
                    SchemaMigrator.auditOrphans(conn, orphanAuditQueries());
                    SchemaMigrator.execScript(conn, ledgerTables());
                })
        );
    }
}

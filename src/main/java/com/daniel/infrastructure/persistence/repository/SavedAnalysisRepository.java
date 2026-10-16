package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for saved analyses: allocation plans (RF10 extension) and
 * simulation scenarios (RF13 extension).
 *
 * <p>Design: every public operation opens its own short-lived connection via a
 * {@link ConnectionFactory}, so callers may invoke it from any worker thread
 * without sharing the singleton connection of {@link Database}. Multi-statement
 * writes run in one transaction with explicit rollback: {@link #savePlan} covers
 * header + items, {@link #savePlanAndSelect} covers header + items + selection.
 * The repository never creates or migrates tables; the schema is owned by the
 * versioned migration.</p>
 *
 * <p>Invalid stored rows (plan missing a category, unknown category, undecodable
 * scenario JSON) are never deleted or rewritten by a read. The {@code *Detailed}
 * listings and {@link #selectedPlanState()} report them as {@link InvalidEntry}
 * next to the valid ones, so one damaged row does not hide the rest; a database
 * failure is still thrown, never reported as "no data".</p>
 */
public final class SavedAnalysisRepository {

    /** Opens an independent connection with FK enforcement and a busy timeout. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    /** A stored row that exists but cannot be used; {@code reason} is user-facing. */
    public record InvalidEntry(long id, String name, String reason) {}

    /** Result of a tolerant listing: usable rows plus the ones that were skipped and why. */
    public record Listing<T>(List<T> valid, List<InvalidEntry> invalid) {}

    /**
     * Persisted selection as seen by the analysis: {@code plan} is empty when the
     * default strategy must be used; {@code problem} is non-null when the selected
     * plan exists but is invalid (the selection row itself is left untouched).
     */
    public record SelectedPlanState(Optional<AllocationPlan> plan, InvalidEntry problem) {}

    /** Raised when a plan or scenario name collides with an existing one. */
    public static final class DuplicateNameException extends RuntimeException {
        public DuplicateNameException(String message) { super(message); }
    }

    /** Raised when an id does not exist. */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    private static final int BUSY_TIMEOUT_MS = 5_000;

    private final ConnectionFactory factory;

    public SavedAnalysisRepository(ConnectionFactory factory) {
        this.factory = factory;
    }

    /**
     * Production wiring: independent connections on the application database via
     * {@link Database#newConnection()} (foreign keys and busy timeout already set,
     * migrations applied once).
     */
    public static SavedAnalysisRepository forApplicationDatabase() {
        return new SavedAnalysisRepository(Database::newConnection);
    }

    /** Factory for a plain SQLite URL; enables foreign_keys and busy_timeout on every connection. */
    public static ConnectionFactory sqliteFactory(String jdbcUrl) {
        return () -> {
            Connection c = DriverManager.getConnection(jdbcUrl);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            } catch (SQLException e) {
                c.close();
                throw e;
            }
            return c;
        };
    }

    // =====================================================================
    // Allocation plans
    // =====================================================================

    /**
     * Inserts (id == null) or replaces (id != null) a plan with all its items in one
     * transaction. Returns the persisted plan with id and updated_at filled in.
     */
    public AllocationPlan savePlan(AllocationPlan plan) {
        return savePlan(plan, false);
    }

    /**
     * Saves the plan (header + items) and marks it as the selected plan in the same
     * transaction. If any step fails, nothing is committed: a new plan does not exist,
     * an edited plan keeps its previous name/weights and the previous selection stands.
     */
    public AllocationPlan savePlanAndSelect(AllocationPlan plan) {
        return savePlan(plan, true);
    }

    private AllocationPlan savePlan(AllocationPlan plan, boolean select) {
        String now = nowIso();
        try (Connection c = factory.open()) {
            c.setAutoCommit(false);
            try {
                long id;
                assertNameAvailable(c, "allocation_plan", plan.name(), plan.id());
                if (plan.id() == null) {
                    id = insertPlanHeader(c, plan.name(), now);
                } else {
                    id = plan.id();
                    updatePlanHeader(c, id, plan.name(), now);
                }
                replaceItems(c, id, plan.weightsBps());
                if (select) writeSelection(c, id);
                c.commit();
                return new AllocationPlan(id, plan.name(), plan.weightsBps(), now);
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw translate(e, plan.name());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar plano: " + e.getMessage(), e);
        }
    }

    public Optional<AllocationPlan> findPlan(long id) {
        try (Connection c = factory.open()) {
            return loadPlan(c, id);
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao carregar plano: " + e.getMessage(), e);
        }
    }

    public List<AllocationPlan> listPlans() {
        Listing<AllocationPlan> listing = listPlansDetailed();
        if (!listing.invalid().isEmpty()) {
            InvalidEntry first = listing.invalid().get(0);
            throw new IllegalStateException("Plano \"" + first.name() + "\" (id " + first.id() + ") inválido: " + first.reason());
        }
        return listing.valid();
    }

    /** Lists every plan, separating the ones whose stored items do not validate. */
    public Listing<AllocationPlan> listPlansDetailed() {
        String sql = "SELECT id, name FROM allocation_plan ORDER BY name COLLATE NOCASE";
        List<AllocationPlan> valid = new ArrayList<>();
        List<InvalidEntry> invalid = new ArrayList<>();
        try (Connection c = factory.open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (rs.next()) { ids.add(rs.getLong(1)); names.add(rs.getString(2)); }
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i);
                try {
                    loadPlan(c, id).ifPresent(valid::add);
                } catch (IllegalArgumentException invalidData) {
                    invalid.add(new InvalidEntry(id, names.get(i), invalidData.getMessage()));
                }
            }
            return new Listing<>(List.copyOf(valid), List.copyOf(invalid));
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar planos: " + e.getMessage(), e);
        }
    }

    /** Deletes a plan and its items; selection falls back to the default strategy via ON DELETE SET NULL. */
    public void deletePlan(long id) {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM allocation_plan WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir plano: " + e.getMessage(), e);
        }
    }

    /** Selects a saved plan, or {@code null} to use the default (ARCA) strategy. */
    public void selectPlan(Long planId) {
        try (Connection c = factory.open()) {
            if (planId != null && loadPlan(c, planId).isEmpty()) {
                throw new NotFoundException("Plano " + planId + " não existe.");
            }
            writeSelection(c, planId);
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao selecionar plano: " + e.getMessage(), e);
        }
    }

    private static void writeSelection(Connection c, Long planId) throws SQLException {
        String sql = "INSERT INTO allocation_selection(singleton, plan_id) VALUES (1, ?) "
                + "ON CONFLICT(singleton) DO UPDATE SET plan_id = excluded.plan_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (planId == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setLong(1, planId);
            ps.executeUpdate();
        }
    }

    public Optional<Long> selectedPlanId() {
        try (Connection c = factory.open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT plan_id FROM allocation_selection WHERE singleton = 1")) {
            if (rs.next()) {
                long id = rs.getLong(1);
                if (!rs.wasNull()) return Optional.of(id);
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao ler seleção de plano: " + e.getMessage(), e);
        }
    }

    public Optional<AllocationPlan> selectedPlan() {
        return selectedPlanId().flatMap(this::findPlan);
    }

    /**
     * Reads the persisted selection without throwing on an invalid plan: the caller
     * falls back to the default strategy and shows {@code problem}. The selection row
     * is not modified, so the user decides what to do with the damaged plan.
     */
    public SelectedPlanState selectedPlanState() {
        try (Connection c = factory.open()) {
            Long id = readSelectedPlanId(c);
            if (id == null) return new SelectedPlanState(Optional.empty(), null);
            String name = null;
            try {
                name = readPlanName(c, id);
                Optional<AllocationPlan> plan = loadPlan(c, id);
                if (plan.isEmpty()) {
                    return new SelectedPlanState(Optional.empty(),
                            new InvalidEntry(id, "", "o plano selecionado não existe mais"));
                }
                return new SelectedPlanState(plan, null);
            } catch (IllegalArgumentException invalidData) {
                return new SelectedPlanState(Optional.empty(),
                        new InvalidEntry(id, name == null ? "" : name, invalidData.getMessage()));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao ler seleção de plano: " + e.getMessage(), e);
        }
    }

    // ----- plan helpers -----

    private static Long readSelectedPlanId(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT plan_id FROM allocation_selection WHERE singleton = 1")) {
            if (rs.next()) {
                long id = rs.getLong(1);
                if (!rs.wasNull()) return id;
            }
            return null;
        }
    }

    private static String readPlanName(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM allocation_plan WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static long insertPlanHeader(Connection c, String name, String now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO allocation_plan(name, updated_at) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Nenhum id gerado para o plano.");
                return keys.getLong(1);
            }
        }
    }

    private static void updatePlanHeader(Connection c, long id, String name, String now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE allocation_plan SET name = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, now);
            ps.setLong(3, id);
            if (ps.executeUpdate() == 0) throw new NotFoundException("Plano " + id + " não existe.");
        }
    }

    private static void replaceItems(Connection c, long planId, Map<CategoryEnum, Integer> weights) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM allocation_item WHERE plan_id = ?")) {
            del.setLong(1, planId);
            del.executeUpdate();
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO allocation_item(plan_id, category, weight_bps) VALUES (?, ?, ?)")) {
            for (CategoryEnum cat : CategoryEnum.values()) {
                ins.setLong(1, planId);
                ins.setString(2, cat.name());
                ins.setInt(3, weights.get(cat));
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    /**
     * Loads a plan; throws {@link IllegalArgumentException} (never silently defaults)
     * when the stored items do not form a valid plan: unknown category persisted by
     * another version, missing category or wrong total.
     */
    private static Optional<AllocationPlan> loadPlan(Connection c, long id) throws SQLException {
        String name;
        String updatedAt;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, updated_at FROM allocation_plan WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                name = rs.getString(1);
                updatedAt = rs.getString(2);
            }
        }
        Map<CategoryEnum, Integer> weights = new EnumMap<>(CategoryEnum.class);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT category, weight_bps FROM allocation_item WHERE plan_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String category = rs.getString(1);
                    try {
                        weights.put(CategoryEnum.valueOf(category), rs.getInt(2));
                    } catch (IllegalArgumentException unknown) {
                        throw new IllegalArgumentException("Categoria desconhecida gravada no plano: " + category
                                + " (gravada por outra versão do aplicativo).", unknown);
                    }
                }
            }
        }
        return Optional.of(new AllocationPlan(id, name, weights, updatedAt));
    }

    // =====================================================================
    // Simulation scenarios
    // =====================================================================

    /** Raised when a stored scenario cannot be decoded; it is reported, never silently defaulted. */
    public static final class CorruptScenarioException extends RuntimeException {
        public CorruptScenarioException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Inserts (id == null) or updates (id != null) a scenario. A duplicated scenario
     * whose name already exists receives a numbered suffix ("X (cópia 2)"); any other
     * name collision is rejected with {@link DuplicateNameException}.
     */
    public SimulationScenario saveScenario(SimulationScenario scenario) {
        String now = nowIso();
        String json = SimulationParametersJson.toJson(scenario.parameters());
        try (Connection c = factory.open()) {
            c.setAutoCommit(false);
            try {
                String name = scenario.name();
                if (scenario.id() == null && name.endsWith(SimulationScenario.COPY_SUFFIX)) {
                    name = nextFreeCopyName(c, name);
                }
                assertNameAvailable(c, "simulation_scenario", name, scenario.id());
                long id;
                if (scenario.id() == null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO simulation_scenario(name, parameters_json, calculation_version, updated_at) VALUES (?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, name);
                        ps.setString(2, json);
                        ps.setInt(3, scenario.parameters().calculationVersion());
                        ps.setString(4, now);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Nenhum id gerado para o cenário.");
                            id = keys.getLong(1);
                        }
                    }
                } else {
                    id = scenario.id();
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE simulation_scenario SET name = ?, parameters_json = ?, calculation_version = ?, updated_at = ? WHERE id = ?")) {
                        ps.setString(1, name);
                        ps.setString(2, json);
                        ps.setInt(3, scenario.parameters().calculationVersion());
                        ps.setString(4, now);
                        ps.setLong(5, id);
                        if (ps.executeUpdate() == 0) throw new NotFoundException("Cenário " + id + " não existe.");
                    }
                }
                c.commit();
                return new SimulationScenario(id, name, scenario.parameters(), now);
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw translate(e, scenario.name());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar cenário: " + e.getMessage(), e);
        }
    }

    public Optional<SimulationScenario> findScenario(long id) {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, parameters_json, calculation_version, updated_at FROM simulation_scenario WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readScenario(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao carregar cenário: " + e.getMessage(), e);
        }
    }

    public List<SimulationScenario> listScenarios() {
        Listing<SimulationScenario> listing = listScenariosDetailed();
        if (!listing.invalid().isEmpty()) {
            InvalidEntry first = listing.invalid().get(0);
            throw new CorruptScenarioException(first.reason(), null);
        }
        return listing.valid();
    }

    /** Lists every scenario, separating rows whose parameters cannot be decoded. */
    public Listing<SimulationScenario> listScenariosDetailed() {
        List<SimulationScenario> valid = new ArrayList<>();
        List<InvalidEntry> invalid = new ArrayList<>();
        try (Connection c = factory.open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, name, parameters_json, calculation_version, updated_at FROM simulation_scenario ORDER BY name COLLATE NOCASE")) {
            while (rs.next()) {
                try {
                    valid.add(readScenario(rs));
                } catch (CorruptScenarioException corrupt) {
                    invalid.add(new InvalidEntry(rs.getLong("id"), rs.getString("name"), corrupt.getMessage()));
                }
            }
            return new Listing<>(List.copyOf(valid), List.copyOf(invalid));
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar cenários: " + e.getMessage(), e);
        }
    }

    public void deleteScenario(long id) {
        try (Connection c = factory.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM simulation_scenario WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir cenário: " + e.getMessage(), e);
        }
    }

    private static SimulationScenario readScenario(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String name = rs.getString("name");
        String json = rs.getString("parameters_json");
        int version = rs.getInt("calculation_version");
        String updatedAt = rs.getString("updated_at");
        SimulationParameters params;
        try {
            params = SimulationParametersJson.fromJson(json, version);
        } catch (RuntimeException e) {
            throw new CorruptScenarioException("Cenário \"" + name + "\" (id " + id + ") não pôde ser lido: " + e.getMessage(), e);
        }
        return new SimulationScenario(id, name, params, updatedAt);
    }

    /** "Base (cópia)" → first free among "Base (cópia)", "Base (cópia 2)", "Base (cópia 3)", … */
    private static String nextFreeCopyName(Connection c, String base) throws SQLException {
        String stem = base.substring(0, base.length() - SimulationScenario.COPY_SUFFIX.length());
        String candidate = base;
        for (int n = 2; nameExists(c, "simulation_scenario", candidate); n++) {
            candidate = stem + " (cópia " + n + ")";
        }
        return candidate;
    }

    private static boolean nameExists(Connection c, String table, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE TRIM(name) = TRIM(?) COLLATE NOCASE")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Names are unique ignoring case and surrounding whitespace, so "ARCA" and "arca " cannot coexist. */
    private static void assertNameAvailable(Connection c, String table, String name, Long selfId) throws SQLException {
        String sql = "SELECT id FROM " + table + " WHERE TRIM(name) = TRIM(?) COLLATE NOCASE"
                + (selfId != null ? " AND id <> ?" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            if (selfId != null) ps.setLong(2, selfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new DuplicateNameException("Já existe um item salvo com o nome \"" + name.trim() + "\".");
                }
            }
        }
    }

    private static RuntimeException translate(Exception e, String name) {
        if (e instanceof RuntimeException re && !(e instanceof IllegalStateException)) {
            if (re instanceof NotFoundException || re instanceof DuplicateNameException) return re;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("UNIQUE constraint failed") && msg.contains("name")) {
            return new DuplicateNameException("Já existe um item salvo com o nome \"" + name + "\".");
        }
        if (e instanceof RuntimeException re) return re;
        return new RuntimeException("Erro ao salvar: " + msg, e);
    }

    private static String nowIso() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}

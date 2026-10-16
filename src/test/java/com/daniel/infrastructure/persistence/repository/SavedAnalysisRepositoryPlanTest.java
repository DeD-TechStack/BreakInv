package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Allocation-plan persistence (RF10 extension; RN09 totals, RN10 informative only).
 *
 * Uses an isolated temp-file SQLite database with the agreed E3 DDL created
 * explicitly here, so the test does not depend on the versioned migration
 * (integration with Database.open() is covered separately). The repository
 * itself never creates tables.
 */
class SavedAnalysisRepositoryPlanTest {

    static final String SYNTHETIC_DDL = """
            CREATE TABLE allocation_plan (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE allocation_item (
                plan_id INTEGER NOT NULL REFERENCES allocation_plan(id) ON DELETE CASCADE,
                category TEXT NOT NULL,
                weight_bps INTEGER NOT NULL CHECK (weight_bps BETWEEN 0 AND 10000),
                PRIMARY KEY (plan_id, category)
            );
            CREATE TABLE allocation_selection (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                plan_id INTEGER REFERENCES allocation_plan(id) ON DELETE SET NULL
            );
            CREATE TABLE simulation_scenario (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parameters_json TEXT NOT NULL,
                calculation_version INTEGER NOT NULL,
                updated_at TEXT NOT NULL
            );
            """;

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("e3.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            for (String sql : SYNTHETIC_DDL.split(";")) {
                if (!sql.isBlank()) st.execute(sql);
            }
        }
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
    }

    static Map<CategoryEnum, Integer> weights(int rf, int acoes, int fii, int fundos, int prev, int cripto, int outros) {
        Map<CategoryEnum, Integer> w = new EnumMap<>(CategoryEnum.class);
        w.put(CategoryEnum.RENDA_FIXA, rf);
        w.put(CategoryEnum.ACOES, acoes);
        w.put(CategoryEnum.FUNDOS_IMOBILIARIOS, fii);
        w.put(CategoryEnum.FUNDOS, fundos);
        w.put(CategoryEnum.PREVIDENCIA, prev);
        w.put(CategoryEnum.CRIPTOMOEDAS, cripto);
        w.put(CategoryEnum.OUTROS, outros);
        return w;
    }

    static AllocationPlan conservador() {
        return AllocationPlan.create("Conservador", weights(7000, 1000, 1000, 500, 500, 0, 0));
    }

    static AllocationPlan arrojado() {
        return AllocationPlan.create("Arrojado", weights(2000, 4000, 1500, 500, 0, 1500, 500));
    }

    long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ===== save / list / find =====

    @Test
    void savePlan_newPlan_assignsIdAndPersistsAllSevenItems() throws SQLException {
        AllocationPlan saved = repo.savePlan(conservador());

        assertNotNull(saved.id());
        assertNotNull(saved.updatedAt());
        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals(7, count("SELECT COUNT(*) FROM allocation_item WHERE plan_id = " + saved.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_item WHERE plan_id = " + saved.id()
                + " AND category = 'CRIPTOMOEDAS' AND weight_bps <> 0"), "zero weights are stored explicitly");
    }

    @Test
    void savePlan_thenFind_roundTripsWeightsExactly() {
        AllocationPlan saved = repo.savePlan(arrojado());

        Optional<AllocationPlan> found = repo.findPlan(saved.id());

        assertTrue(found.isPresent());
        assertEquals("Arrojado", found.get().name());
        assertEquals(arrojado().weightsBps(), found.get().weightsBps());
        assertEquals(saved.updatedAt(), found.get().updatedAt());
    }

    @Test
    void listPlans_twoDistinctPlans_orderedByName() {
        repo.savePlan(conservador());
        repo.savePlan(arrojado());

        List<AllocationPlan> plans = repo.listPlans();

        assertEquals(2, plans.size());
        assertEquals("Arrojado", plans.get(0).name());
        assertEquals("Conservador", plans.get(1).name());
        assertEquals(10000, plans.get(0).totalBps());
        assertEquals(10000, plans.get(1).totalBps());
    }

    @Test
    void savePlan_existingId_replacesItemsWithoutOrphansAndBumpsUpdatedAt() throws Exception {
        AllocationPlan saved = repo.savePlan(conservador());
        Thread.sleep(5);
        AllocationPlan edited = saved.withName("Conservador v2")
                .withWeights(weights(6000, 2000, 1000, 500, 500, 0, 0));

        AllocationPlan updated = repo.savePlan(edited);

        assertEquals(saved.id(), updated.id());
        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals(7, count("SELECT COUNT(*) FROM allocation_item"));
        AllocationPlan reloaded = repo.findPlan(saved.id()).orElseThrow();
        assertEquals("Conservador v2", reloaded.name());
        assertEquals(6000, reloaded.weightsBps().get(CategoryEnum.RENDA_FIXA));
        assertEquals(2000, reloaded.weightsBps().get(CategoryEnum.ACOES));
        assertTrue(reloaded.updatedAt().compareTo(saved.updatedAt()) > 0, "updated_at must advance");
    }

    @Test
    void savePlan_duplicateName_rejectedAndOriginalUntouched() {
        AllocationPlan first = repo.savePlan(conservador());

        AllocationPlan clash = AllocationPlan.create("conservador ", weights(10000, 0, 0, 0, 0, 0, 0));
        assertThrows(SavedAnalysisRepository.DuplicateNameException.class, () -> repo.savePlan(clash));

        assertEquals(1, repo.listPlans().size());
        assertEquals(conservador().weightsBps(), repo.findPlan(first.id()).orElseThrow().weightsBps());
    }

    @Test
    void savePlan_updateFailingOnUniqueName_rollsBackItemsOfThatPlan() throws SQLException {
        repo.savePlan(conservador());
        AllocationPlan b = repo.savePlan(arrojado());

        // Rename B to A's name with new weights: header update must fail and the
        // already-rewritten items must be rolled back to B's original weights.
        AllocationPlan invalid = b.withName("Conservador").withWeights(weights(10000, 0, 0, 0, 0, 0, 0));
        assertThrows(SavedAnalysisRepository.DuplicateNameException.class, () -> repo.savePlan(invalid));

        AllocationPlan bAfter = repo.findPlan(b.id()).orElseThrow();
        assertEquals("Arrojado", bAfter.name());
        assertEquals(arrojado().weightsBps(), bAfter.weightsBps());
        assertEquals(14, count("SELECT COUNT(*) FROM allocation_item"));
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_item WHERE plan_id NOT IN (SELECT id FROM allocation_plan)"));
    }

    @Test
    void savePlan_unknownId_throwsNotFound() {
        AllocationPlan ghost = new AllocationPlan(999L, "Fantasma", conservador().weightsBps(), null);
        assertThrows(SavedAnalysisRepository.NotFoundException.class, () -> repo.savePlan(ghost));
    }

    // ===== selection =====

    @Test
    void selectedPlan_freshDatabase_isEmptyMeaningDefaultStrategy() {
        assertTrue(repo.selectedPlanId().isEmpty());
        assertTrue(repo.selectedPlan().isEmpty());
    }

    @Test
    void selectPlan_persistsAndIsReadBackByNewRepositoryInstance() {
        AllocationPlan saved = repo.savePlan(conservador());

        repo.selectPlan(saved.id());

        SavedAnalysisRepository reopened = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        assertEquals(Optional.of(saved.id()), reopened.selectedPlanId());
        assertEquals("Conservador", reopened.selectedPlan().orElseThrow().name());
    }

    @Test
    void selectPlan_null_resetsToDefaultStrategy() {
        AllocationPlan saved = repo.savePlan(conservador());
        repo.selectPlan(saved.id());

        repo.selectPlan(null);

        assertTrue(repo.selectedPlanId().isEmpty());
    }

    @Test
    void selectPlan_unknownId_rejected() {
        assertThrows(SavedAnalysisRepository.NotFoundException.class, () -> repo.selectPlan(42L));
        assertTrue(repo.selectedPlanId().isEmpty());
    }

    // ===== delete =====

    @Test
    void deletePlan_removesItemsAndKeepsOtherPlans() throws SQLException {
        AllocationPlan a = repo.savePlan(conservador());
        AllocationPlan b = repo.savePlan(arrojado());

        repo.deletePlan(a.id());

        assertTrue(repo.findPlan(a.id()).isEmpty());
        assertEquals(7, count("SELECT COUNT(*) FROM allocation_item"));
        assertEquals(7, count("SELECT COUNT(*) FROM allocation_item WHERE plan_id = " + b.id()));
    }

    @Test
    void deletePlan_selectedPlan_fallsBackToDefaultStrategy() {
        AllocationPlan a = repo.savePlan(conservador());
        repo.selectPlan(a.id());

        repo.deletePlan(a.id());

        assertTrue(repo.selectedPlanId().isEmpty(), "deleting the selected plan must fall back to ARCA (empty selection)");
    }

    @Test
    void deletePlan_unknownId_isNoOp() {
        assertDoesNotThrow(() -> repo.deletePlan(123L));
    }

    @Test
    void sqliteFactory_enforcesForeignKeys() throws SQLException {
        try (Connection c = SavedAnalysisRepository.sqliteFactory(jdbcUrl).open();
             Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO allocation_item(plan_id, category, weight_bps) VALUES (999, 'ACOES', 100)"));
        }
    }
}

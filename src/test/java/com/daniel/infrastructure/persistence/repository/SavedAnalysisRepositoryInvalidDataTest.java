package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.InvalidEntry;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.Listing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transparent recovery from invalid stored analyses (RN11 spirit: a damaged row
 * must not hide the healthy ones, must be reported by id/name/reason, and must
 * never be deleted or rewritten by a read). A general database failure is still
 * an error, never "no data".
 */
class SavedAnalysisRepositoryInvalidDataTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("invalid.db").toString().replace("\\", "/");
        exec(SavedAnalysisRepositoryPlanTest.SYNTHETIC_DDL.split(";"));
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
    }

    private void exec(String... sqls) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            for (String sql : sqls) if (!sql.isBlank()) st.execute(sql);
        }
    }

    // ===== plans: listing =====

    @Test
    void listPlansDetailed_planMissingAnItem_isReportedAndOthersStayVisible() throws SQLException {
        AllocationPlan a = repo.savePlan(SavedAnalysisRepositoryPlanTest.conservador());
        AllocationPlan b = repo.savePlan(SavedAnalysisRepositoryPlanTest.arrojado());
        exec("DELETE FROM allocation_item WHERE plan_id = " + b.id() + " AND category = 'ACOES'");

        Listing<AllocationPlan> listing = repo.listPlansDetailed();

        assertEquals(1, listing.valid().size());
        assertEquals(a.id(), listing.valid().get(0).id());
        assertEquals(1, listing.invalid().size());
        InvalidEntry bad = listing.invalid().get(0);
        assertEquals(b.id(), bad.id());
        assertEquals("Arrojado", bad.name());
        assertTrue(bad.reason().contains("Ações"), "reason must name the missing category: " + bad.reason());
    }

    @Test
    void listPlansDetailed_unknownCategory_isReportedNotSilentlyDropped() throws SQLException {
        AllocationPlan a = repo.savePlan(SavedAnalysisRepositoryPlanTest.conservador());
        exec("INSERT INTO allocation_item(plan_id, category, weight_bps) VALUES (" + a.id() + ", 'FUTURA', 0)");

        Listing<AllocationPlan> listing = repo.listPlansDetailed();

        assertTrue(listing.valid().isEmpty());
        assertEquals(1, listing.invalid().size());
        assertTrue(listing.invalid().get(0).reason().contains("FUTURA"), listing.invalid().get(0).reason());
    }

    @Test
    void listPlansDetailed_doesNotDeleteOrRewriteTheInvalidRows() throws SQLException {
        AllocationPlan b = repo.savePlan(SavedAnalysisRepositoryPlanTest.arrojado());
        exec("DELETE FROM allocation_item WHERE plan_id = " + b.id() + " AND category = 'ACOES'");

        repo.listPlansDetailed();
        repo.selectedPlanState();

        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            var rs = st.executeQuery("SELECT COUNT(*) FROM allocation_plan WHERE id = " + b.id());
            rs.next();
            assertEquals(1, rs.getLong(1), "header untouched");
            rs = st.executeQuery("SELECT COUNT(*) FROM allocation_item WHERE plan_id = " + b.id());
            rs.next();
            assertEquals(6, rs.getLong(1), "remaining items untouched");
        }
    }

    @Test
    void listPlansDetailed_databaseFailure_isAnErrorNotAnEmptyList() throws SQLException {
        repo.savePlan(SavedAnalysisRepositoryPlanTest.conservador());
        exec("DROP TABLE allocation_item");

        assertThrows(RuntimeException.class, () -> repo.listPlansDetailed());
    }

    // ===== plans: persisted selection =====

    @Test
    void selectedPlanState_validSelection_returnsPlanWithoutProblem() {
        AllocationPlan a = repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador());

        var state = repo.selectedPlanState();

        assertEquals(a.id(), state.plan().orElseThrow().id());
        assertNull(state.problem());
    }

    @Test
    void selectedPlanState_noSelection_isEmptyWithoutProblem() {
        var state = repo.selectedPlanState();

        assertTrue(state.plan().isEmpty());
        assertNull(state.problem());
    }

    @Test
    void selectedPlanState_selectedPlanInvalid_isEmptyWithExplicitProblemAndSelectionKept() throws SQLException {
        AllocationPlan a = repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador());
        exec("DELETE FROM allocation_item WHERE plan_id = " + a.id() + " AND category = 'RENDA_FIXA'");

        var state = repo.selectedPlanState();

        assertTrue(state.plan().isEmpty(), "caller falls back to the default strategy");
        assertNotNull(state.problem());
        assertEquals(a.id(), state.problem().id());
        assertEquals("Conservador", state.problem().name());
        assertTrue(state.problem().reason().contains("Renda Fixa"), state.problem().reason());
        assertEquals(a.id(), repo.selectedPlanId().orElseThrow(), "the persisted selection is not rewritten by a read");
    }

    @Test
    void selectedPlanState_databaseFailure_throwsInsteadOfPretendingNoSelection() throws SQLException {
        repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador());
        exec("DROP TABLE allocation_item");

        assertThrows(RuntimeException.class, () -> repo.selectedPlanState());
    }

    // ===== scenarios: listing =====

    @Test
    void listScenariosDetailed_oneCorruptRow_othersStayVisibleAndCorruptIsReported() throws SQLException {
        repo.saveScenario(com.daniel.core.domain.entity.SimulationScenario.create("Alpha",
                SavedAnalysisRepositoryScenarioTest.cdiScenario("0.10")));
        repo.saveScenario(com.daniel.core.domain.entity.SimulationScenario.create("Zeta",
                SavedAnalysisRepositoryScenarioTest.cdiScenario("0.10")));
        exec("INSERT INTO simulation_scenario(name, parameters_json, calculation_version, updated_at) "
                + "VALUES ('Quebrado', '{not json', 1, '2026-01-01T00:00:00Z')");

        Listing<com.daniel.core.domain.entity.SimulationScenario> listing = repo.listScenariosDetailed();

        assertEquals(java.util.List.of("Alpha", "Zeta"),
                listing.valid().stream().map(com.daniel.core.domain.entity.SimulationScenario::name).toList());
        assertEquals(1, listing.invalid().size());
        assertEquals("Quebrado", listing.invalid().get(0).name());
        assertNotNull(listing.invalid().get(0).id());
        assertEquals(3, repo.listScenariosDetailed().valid().size() + repo.listScenariosDetailed().invalid().size());
    }

    @Test
    void listScenariosDetailed_databaseFailure_isAnErrorNotAnEmptyList() throws SQLException {
        exec("DROP TABLE simulation_scenario");

        assertThrows(RuntimeException.class, () -> repo.listScenariosDetailed());
    }
}

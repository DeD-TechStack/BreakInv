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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saving a plan and selecting it must be one transaction (RF10 extension): if the
 * selection write fails, neither a new header/items nor an edited header/items may
 * remain committed, so a retry never hits "duplicate name" for a plan the user
 * never saw as saved.
 *
 * <p>Failure injection: a SQLite trigger on {@code allocation_selection} raises
 * ABORT for the write made after header + items. The trigger lives only in the
 * synthetic test database.</p>
 */
class SavedAnalysisRepositoryAtomicSelectTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("atomic.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            for (String sql : SavedAnalysisRepositoryPlanTest.SYNTHETIC_DDL.split(";")) {
                if (!sql.isBlank()) st.execute(sql);
            }
        }
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
    }

    private void injectSelectionFailure() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER fail_sel_ins BEFORE INSERT ON allocation_selection "
                    + "BEGIN SELECT RAISE(ABORT, 'injected selection failure'); END");
            st.execute("CREATE TRIGGER fail_sel_upd BEFORE UPDATE ON allocation_selection "
                    + "BEGIN SELECT RAISE(ABORT, 'injected selection failure'); END");
        }
    }

    private void removeSelectionFailure() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER fail_sel_ins");
            st.execute("DROP TRIGGER fail_sel_upd");
        }
    }

    long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void savePlanAndSelect_success_persistsPlanItemsAndSelectionTogether() throws SQLException {
        AllocationPlan saved = repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador());

        assertNotNull(saved.id());
        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals(7, count("SELECT COUNT(*) FROM allocation_item WHERE plan_id = " + saved.id()));
        assertEquals(Optional.of(saved.id()), repo.selectedPlanId());
    }

    @Test
    void savePlanAndSelect_newPlan_selectionFailure_rollsBackHeaderAndItems() throws SQLException {
        injectSelectionFailure();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador()));

        assertTrue(ex.getMessage().contains("injected selection failure"), ex.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_plan"), "header must not survive");
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_item"), "items must not survive");
        assertTrue(repo.selectedPlanId().isEmpty());
    }

    @Test
    void savePlanAndSelect_newPlan_retryAfterFailure_succeedsWithoutDuplicateName() throws SQLException {
        injectSelectionFailure();
        assertThrows(RuntimeException.class,
                () -> repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador()));
        removeSelectionFailure();

        AllocationPlan saved = assertDoesNotThrow(
                () -> repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador()));

        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals(Optional.of(saved.id()), repo.selectedPlanId());
    }

    @Test
    void savePlanAndSelect_editExisting_selectionFailure_preservesOriginalNameWeightsAndSelection() throws SQLException {
        AllocationPlan a = repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador());
        AllocationPlan b = repo.savePlan(SavedAnalysisRepositoryPlanTest.arrojado());
        injectSelectionFailure();

        AllocationPlan edited = b.withName("Arrojado v2")
                .withWeights(SavedAnalysisRepositoryPlanTest.weights(10000, 0, 0, 0, 0, 0, 0));
        assertThrows(RuntimeException.class, () -> repo.savePlanAndSelect(edited));

        AllocationPlan bAfter = repo.findPlan(b.id()).orElseThrow();
        assertEquals("Arrojado", bAfter.name());
        assertEquals(SavedAnalysisRepositoryPlanTest.arrojado().weightsBps(), bAfter.weightsBps());
        assertEquals(2000, bAfter.weightsBps().get(CategoryEnum.RENDA_FIXA));
        assertEquals(Optional.of(a.id()), repo.selectedPlanId(), "previous selection must be preserved");
        assertEquals(14, count("SELECT COUNT(*) FROM allocation_item"));
    }

    @Test
    void savePlanAndSelect_duplicateName_rejectedBeforeAnyWrite() throws SQLException {
        repo.savePlanAndSelect(SavedAnalysisRepositoryPlanTest.conservador());
        AllocationPlan clash = AllocationPlan.create("conservador",
                SavedAnalysisRepositoryPlanTest.weights(10000, 0, 0, 0, 0, 0, 0));

        assertThrows(SavedAnalysisRepository.DuplicateNameException.class, () -> repo.savePlanAndSelect(clash));

        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals(7, count("SELECT COUNT(*) FROM allocation_item"));
    }
}

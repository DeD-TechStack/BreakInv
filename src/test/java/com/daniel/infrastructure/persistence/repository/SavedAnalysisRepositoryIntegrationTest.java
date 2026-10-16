package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.infrastructure.persistence.config.Database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SavedAnalysisRepository on the real application schema: tables come from the
 * versioned migration run by {@link Database#open()} (no synthetic DDL), the
 * repository uses {@link Database#newConnection()}, and operations from a worker
 * thread coexist with the shared connection. Temp-file database only.
 */
class SavedAnalysisRepositoryIntegrationTest {

    @TempDir
    Path tempDir;

    SavedAnalysisRepository repo;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("app.db").toString().replace("\\", "/"));
        Database.open();
        repo = SavedAnalysisRepository.forApplicationDatabase();
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    static AllocationPlan plan(String name) {
        Map<CategoryEnum, Integer> w = new EnumMap<>(CategoryEnum.class);
        for (CategoryEnum c : CategoryEnum.values()) w.put(c, 0);
        w.put(CategoryEnum.RENDA_FIXA, 6000);
        w.put(CategoryEnum.ACOES, 4000);
        return AllocationPlan.create(name, w);
    }

    @Test
    void migratedSchema_hasE3Tables_andRepositoryWorksWithoutCreatingThem() throws SQLException {
        try (Statement st = Database.open().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN "
                     + "('allocation_plan','allocation_item','allocation_selection','simulation_scenario')")) {
            rs.next();
            assertEquals(4, rs.getInt(1), "E3 tables must come from the versioned migration");
        }

        AllocationPlan saved = repo.savePlan(plan("Integração"));
        repo.selectPlan(saved.id());
        SimulationScenario sc = repo.saveScenario(SimulationScenario.create("Cenário",
                SimulationParameters.fixedRate(100_000L, 12, new BigDecimal("0.1"))));

        assertEquals(Optional.of(saved.id()), repo.selectedPlanId());
        assertEquals("Cenário", repo.findScenario(sc.id()).orElseThrow().name());
    }

    @Test
    void reopenDatabase_restoresSelectedPlanAndScenario() {
        AllocationPlan saved = repo.savePlan(plan("Persistente"));
        repo.selectPlan(saved.id());
        SimulationScenario sc = repo.saveScenario(SimulationScenario.create("Reabrir",
                SimulationParameters.hybrid(50_000L, 6, new BigDecimal("0.05"), new BigDecimal("0.04"))));

        String url = Database.currentUrl();
        Database.close();
        Database.configure(url);
        Database.open();
        SavedAnalysisRepository after = SavedAnalysisRepository.forApplicationDatabase();

        assertEquals("Persistente", after.selectedPlan().orElseThrow().name());
        assertEquals(sc.parameters(), after.findScenario(sc.id()).orElseThrow().parameters());
    }

    @Test
    void workerThread_writesWhileSharedConnectionIsOpen() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<AllocationPlan> f = worker.submit(() -> repo.savePlan(plan("Worker")));
            AllocationPlan saved = f.get();
            assertNotNull(saved.id());

            Connection shared = Database.open();
            try (Statement st = shared.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM allocation_item WHERE plan_id = " + saved.id())) {
                rs.next();
                assertEquals(7, rs.getInt(1), "shared connection sees the committed worker write");
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void deletingSelectedPlan_onRealSchema_fallsBackViaOnDeleteSetNull() {
        AllocationPlan a = repo.savePlan(plan("Selecionado"));
        repo.selectPlan(a.id());

        repo.deletePlan(a.id());

        assertTrue(repo.selectedPlanId().isEmpty());
        assertTrue(repo.listPlans().isEmpty());
    }
}

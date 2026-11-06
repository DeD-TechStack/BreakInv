package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.core.service.HistoryFixtures;
import com.daniel.core.service.HistoryFixtures.Fixture;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-32 S1.RF-02: compacting the database with {@code VACUUM} changes nothing the user sees of the history and
 * of the ledger. For each synthetic history (H1 to H7, one database each, in a folder the test owns): free pages
 * are made from data that is not history, saved plans and saved scenarios removed through the product's
 * repository (each kind must add free pages of its own); every reader and the {@link DatabaseFingerprint} are read
 * only after the removals; the file is compacted by JDBC with every connection closed; and everything is read
 * again and must be equal.
 */
class CompactionEquivalenceTest {

    /** Saved plans and saved scenarios written and then removed, per fixture. */
    private static final int PLANS = 150;
    private static final int SCENARIOS = 150;

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        com.daniel.infrastructure.persistence.config.Database.close();
    }

    @Test
    void vacuum_changesNoNumberOfTheHistoryOrTheLedger() throws Exception {
        List<String> differences = new ArrayList<>();
        for (String name : HistoryFixtures.NAMES) {
            Fixture f = HistoryFixtures.build(name, Files.createDirectories(tmp.resolve(name)));

            // 1. free pages from data that is not history: plans and scenarios saved, then removed through the
            //    product's repository. Everything is saved first and removed after, so that the pages freed by one
            //    kind are not taken again by the other kind's inserts.
            f.open();
            SavedAnalysisRepository repo = SavedAnalysisRepository.forApplicationDatabase();
            long freeAtStart = pragma(f, "freelist_count");
            List<Long> planIds = savePlans(repo);
            List<Long> scenarioIds = saveScenarios(repo);
            for (long id : planIds) repo.deletePlan(id);
            long freeAfterPlans = pragma(f, "freelist_count");
            for (long id : scenarioIds) repo.deleteScenario(id);
            long freeAfterScenarios = pragma(f, "freelist_count");
            assertTrue(freeAfterPlans > freeAtStart, name + ": the removed plans must add free pages (" + freeAtStart
                    + " -> " + freeAfterPlans + ")");
            assertTrue(freeAfterScenarios > freeAfterPlans, name + ": the removed scenarios alone must add free pages ("
                    + freeAfterPlans + " -> " + freeAfterScenarios + ")");
            assertEquals(List.of(), repo.listPlans(), name + ": no saved plan is left");
            assertEquals(List.of(), repo.listScenarios(), name + ": no saved scenario is left");

            // 2. only then read every reader and the fingerprint, in the full period, the last week and the last day
            Map<String, String> before = HistoryFixtures.readAll(f);
            f.close(); // every connection of the product is closed before VACUUM: a BUSY here is a defect of the test
            long freeBefore = pragma(f, "freelist_count");
            long sizeBefore = Files.size(f.dbFile);
            assertTrue(freeBefore > 0, name + ": the fixture must have free pages, has " + freeBefore);

            // 3. VACUUM by JDBC on the test database
            try (Connection c = DriverManager.getConnection(f.url); Statement st = c.createStatement()) {
                st.execute("VACUUM");
            }

            // 4. no free page left and the file did not grow
            assertEquals(0L, pragma(f, "freelist_count"), name + ": free pages after VACUUM");
            long sizeAfter = Files.size(f.dbFile);
            assertTrue(sizeAfter <= sizeBefore, name + ": the file grew from " + sizeBefore + " to " + sizeAfter);

            // 5. read again: equal in every reader and in the fingerprint
            f.open();
            Map<String, String> after = HistoryFixtures.readAll(f);
            f.close();
            for (Map.Entry<String, String> e : before.entrySet()) {
                boolean changed = !e.getValue().equals(after.get(e.getKey()));
                System.out.println("BRK32S1 VACUUM " + name + " " + e.getKey() + " " + (changed ? "MUDA" : "IGUAL"));
                if (changed) differences.add(name + " " + e.getKey());
            }
            System.out.println("BRK32S1 INFO " + name + " freelistInicio=" + freeAtStart
                    + " freelistDepoisPlanos=" + freeAfterPlans + " freelistDepoisCenarios=" + freeAfterScenarios
                    + " freelistAntes=" + freeBefore + " bytesAntes=" + sizeBefore + " bytesDepois=" + sizeAfter);
        }
        assertEquals(List.of(), differences, "readers that changed after VACUUM");
    }

    /** {@link #PLANS} plans with long names (about 30 pages of table and 30 of index). Returns their ids. */
    private static List<Long> savePlans(SavedAnalysisRepository repo) {
        Map<CategoryEnum, Integer> w = new EnumMap<>(CategoryEnum.class);
        for (CategoryEnum c : CategoryEnum.values()) w.put(c, 0);
        w.put(CategoryEnum.RENDA_FIXA, 4000);
        w.put(CategoryEnum.ACOES, 3000);
        w.put(CategoryEnum.OUTROS, 2500);
        w.put(CategoryEnum.CRIPTOMOEDAS, 500);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < PLANS; i++) {
            ids.add(repo.savePlan(AllocationPlan.create("Plano temporario " + i + " " + "x".repeat(700), w)).id());
        }
        return ids;
    }

    /** {@link #SCENARIOS} scenarios with long names and valid fixed-rate parameters. Returns their ids. */
    private static List<Long> saveScenarios(SavedAnalysisRepository repo) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < SCENARIOS; i++) {
            SimulationParameters p = SimulationParameters.fixedRate(100_000L + i, 12, new BigDecimal("0.1"));
            ids.add(repo.saveScenario(SimulationScenario.create("Cenario temporario " + i + " " + "y".repeat(700), p)).id());
        }
        return ids;
    }

    private static long pragma(Fixture f, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(f.url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

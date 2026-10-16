package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.core.service.SimulationCalculator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saved simulation scenarios (RF13 extension, RN12): parameters are persisted,
 * not results; reopening reproduces the same projection with frozen rates;
 * duplicating/deleting never touches portfolio tables.
 */
class SavedAnalysisRepositoryScenarioTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("e3s.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            for (String sql : SavedAnalysisRepositoryPlanTest.SYNTHETIC_DDL.split(";")) {
                if (!sql.isBlank()) st.execute(sql);
            }
            // minimal portfolio tables to prove scenarios never write there
            st.execute("CREATE TABLE investment_type (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)");
            st.execute("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, total_cents INTEGER NOT NULL)");
            st.execute("CREATE TABLE cash_snapshots (date TEXT PRIMARY KEY, value_cents INTEGER NOT NULL)");
        }
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
    }

    static SimulationParameters cdiScenario(String cdi) {
        return SimulationParameters.benchmark(1_000_000L, 12, Benchmark.CDI, new BigDecimal("1.10"),
                new RateSnapshot(new BigDecimal(cdi), new BigDecimal("0.15"), new BigDecimal("0.045"), "BCB/SGS", "2026-10-15"));
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
    void saveScenario_thenFind_roundTripsAllParameters() throws SQLException {
        SimulationScenario saved = repo.saveScenario(SimulationScenario.create("CDB 110% CDI", cdiScenario("0.10")));

        assertNotNull(saved.id());
        assertNotNull(saved.updatedAt());
        Optional<SimulationScenario> found = repo.findScenario(saved.id());
        assertTrue(found.isPresent());
        assertEquals("CDB 110% CDI", found.get().name());
        assertEquals(cdiScenario("0.10"), found.get().parameters());
        assertEquals(SimulationParameters.CALCULATION_VERSION, found.get().parameters().calculationVersion());
        assertEquals(1, count("SELECT COUNT(*) FROM simulation_scenario WHERE calculation_version = 1"));
    }

    @Test
    void stockScenario_roundTripsIncludingManualPriceOrigin() {
        SimulationParameters stock = SimulationParameters.stock("hglg11", 16_000L, 50, 17_250L, 110L,
                new BigDecimal("2.5"), "manual", "2026-10-15");
        SimulationScenario saved = repo.saveScenario(SimulationScenario.create("HGLG offline", stock));

        SimulationParameters reloaded = repo.findScenario(saved.id()).orElseThrow().parameters();

        assertEquals("HGLG11", reloaded.ticker());
        assertEquals(stock, reloaded);
        assertEquals("manual", reloaded.priceOrigin());
    }

    @Test
    void reopen_reproducesSameProjectionWithFrozenRates_evenWhenTodayRateDiffers() {
        SimulationScenario saved = repo.saveScenario(SimulationScenario.create("Congelado", cdiScenario("0.10")));
        SimulationCalculator.Projection original = SimulationCalculator.project(saved.parameters());

        // "today" the CDI is different; a reopened scenario must ignore it
        SavedAnalysisRepository reopened = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        SimulationScenario loaded = reopened.findScenario(saved.id()).orElseThrow();
        SimulationCalculator.Projection replay = SimulationCalculator.project(loaded.parameters());

        assertEquals(original, replay);
        assertEquals(1_110_000L, replay.finalCents());
        assertNotEquals(SimulationCalculator.project(cdiScenario("0.1365")).finalCents(), replay.finalCents());
    }

    @Test
    void listScenarios_orderedByNameCaseInsensitive() {
        repo.saveScenario(SimulationScenario.create("zeta", cdiScenario("0.1")));
        repo.saveScenario(SimulationScenario.create("Alpha", cdiScenario("0.1")));
        repo.saveScenario(SimulationScenario.create("beta", cdiScenario("0.1")));

        List<SimulationScenario> all = repo.listScenarios();

        assertEquals(List.of("Alpha", "beta", "zeta"), all.stream().map(SimulationScenario::name).toList());
    }

    @Test
    void saveScenario_existingId_updatesInPlace() throws Exception {
        SimulationScenario saved = repo.saveScenario(SimulationScenario.create("Original", cdiScenario("0.10")));
        Thread.sleep(5);

        SimulationScenario edited = saved.withName("Editado").withParameters(cdiScenario("0.12"));
        SimulationScenario updated = repo.saveScenario(edited);

        assertEquals(saved.id(), updated.id());
        assertEquals(1, count("SELECT COUNT(*) FROM simulation_scenario"));
        SimulationScenario reloaded = repo.findScenario(saved.id()).orElseThrow();
        assertEquals("Editado", reloaded.name());
        assertEquals(new BigDecimal("0.12"), reloaded.parameters().rates().cdi());
        assertTrue(reloaded.updatedAt().compareTo(saved.updatedAt()) > 0);
    }

    @Test
    void duplicate_thenModifyCopy_originalUnchanged() throws SQLException {
        SimulationScenario original = repo.saveScenario(SimulationScenario.create("Base", cdiScenario("0.10")));

        SimulationScenario copy = repo.saveScenario(original.duplicate());
        SimulationScenario modifiedCopy = repo.saveScenario(copy.withParameters(cdiScenario("0.20")));

        assertNotEquals(original.id(), copy.id());
        assertEquals("Base (cópia)", copy.name());
        assertEquals(2, count("SELECT COUNT(*) FROM simulation_scenario"));
        SimulationScenario originalReloaded = repo.findScenario(original.id()).orElseThrow();
        assertEquals(new BigDecimal("0.10"), originalReloaded.parameters().rates().cdi());
        assertEquals("Base", originalReloaded.name());
        assertEquals(new BigDecimal("0.20"), repo.findScenario(modifiedCopy.id()).orElseThrow().parameters().rates().cdi());
    }

    @Test
    void duplicate_nameCollision_getsNumberedSuffix() {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("Base", cdiScenario("0.10")));
        repo.saveScenario(a.duplicate());

        SimulationScenario second = repo.saveScenario(a.duplicate());

        assertEquals("Base (cópia 2)", second.name());
    }

    @Test
    void saveScenario_duplicateName_rejected() {
        repo.saveScenario(SimulationScenario.create("Mesmo nome", cdiScenario("0.10")));

        assertThrows(SavedAnalysisRepository.DuplicateNameException.class,
                () -> repo.saveScenario(SimulationScenario.create("mesmo NOME", cdiScenario("0.10"))));
        assertEquals(1, repo.listScenarios().size());
    }

    @Test
    void deleteScenario_removesOnlyThatRow() throws SQLException {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", cdiScenario("0.10")));
        SimulationScenario b = repo.saveScenario(SimulationScenario.create("B", cdiScenario("0.10")));

        repo.deleteScenario(a.id());

        assertTrue(repo.findScenario(a.id()).isEmpty());
        assertTrue(repo.findScenario(b.id()).isPresent());
        assertDoesNotThrow(() -> repo.deleteScenario(a.id()));
    }

    @Test
    void scenarios_neverTouchPortfolioTables() throws SQLException {
        SimulationScenario s = repo.saveScenario(SimulationScenario.create("Hipotético", cdiScenario("0.10")));
        repo.saveScenario(s.duplicate());
        repo.saveScenario(s.withParameters(SimulationParameters.stock("PETR4", 1000L, 100, 1200L, 10L,
                BigDecimal.ZERO, "manual", null)));
        repo.deleteScenario(s.id());

        assertEquals(0, count("SELECT COUNT(*) FROM investment_type"));
        assertEquals(0, count("SELECT COUNT(*) FROM transactions"));
        assertEquals(0, count("SELECT COUNT(*) FROM cash_snapshots"));
    }

    @Test
    void findScenario_corruptJson_isReportedNotSilentlyDefaulted() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("INSERT INTO simulation_scenario(name, parameters_json, calculation_version, updated_at) "
                    + "VALUES ('Quebrado', '{not json', 1, '2026-01-01T00:00:00Z')");
        }
        long id = count("SELECT MAX(id) FROM simulation_scenario");

        assertThrows(SavedAnalysisRepository.CorruptScenarioException.class, () -> repo.findScenario(id));
    }
}

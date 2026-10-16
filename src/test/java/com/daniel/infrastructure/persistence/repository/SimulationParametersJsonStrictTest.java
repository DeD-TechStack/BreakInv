package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.Listing;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stored JSON is decoded strictly: integer keys must be exact JSON integers of the right
 * type, keys that {@link SimulationParametersJson#toJson} always writes are mandatory, text keys
 * must be JSON strings, {@code rates} must be an object and {@code calculationVersion} must
 * match the column. Anything else is an invalid row (reported, never defaulted, never rewritten).
 */
class SimulationParametersJsonStrictTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("strict.db").toString().replace("\\", "/");
        exec(SavedAnalysisRepositoryPlanTest.SYNTHETIC_DDL.split(";"));
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
    }

    private void exec(String... sqls) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            for (String sql : sqls) if (!sql.isBlank()) st.execute(sql);
        }
    }

    private long insertRaw(String name, String json, int version) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement("INSERT INTO simulation_scenario(name, parameters_json, calculation_version, updated_at) VALUES (?, ?, ?, '2026-01-01T00:00:00Z')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, json);
            ps.setInt(3, version);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    private String rawJson(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT parameters_json FROM simulation_scenario WHERE id = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ----- direct decoding -----

    static final String FIXED_OK = "{\"kind\":\"FIXED_RATE\",\"capitalCents\":100000,\"months\":12,\"annualRate\":\"0.1\","
            + "\"purchasePriceCents\":0,\"quantity\":0,\"currentPriceCents\":0,\"dividendPerShareCents\":0,\"calculationVersion\":1}";

    @Test
    void fromJson_exactIntegers_decode() {
        SimulationParameters p = SimulationParametersJson.fromJson(FIXED_OK, 1);
        assertEquals(100_000L, p.capitalCents());
        assertEquals(12, p.months());
        assertEquals(new BigDecimal("0.1"), p.annualRate());
    }

    @Test
    void fromJson_decimalInIntegerKey_isRejectedNotTruncated() {
        String json = FIXED_OK.replace("\"capitalCents\":100000", "\"capitalCents\":100000.9");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("capitalCents"), ex.getMessage());
    }

    @Test
    void fromJson_intOutOfRange_isRejectedNotWrapped() {
        String json = FIXED_OK.replace("\"months\":12", "\"months\":4294967308");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("months"), ex.getMessage());
    }

    @Test
    void fromJson_numericString_inIntegerKey_isRejected() {
        String json = FIXED_OK.replace("\"capitalCents\":100000", "\"capitalCents\":\"100000\"");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("capitalCents"), ex.getMessage());
    }

    @Test
    void fromJson_missingAlwaysWrittenKey_isRejectedNotZero() {
        String stockNoCurrent = "{\"kind\":\"STOCK\",\"capitalCents\":0,\"months\":0,\"ticker\":\"PETR4\",\"purchasePriceCents\":1000,"
                + "\"quantity\":100,\"dividendPerShareCents\":10,\"priceVariationPercent\":\"0\",\"priceOrigin\":\"manual\",\"calculationVersion\":1}";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(stockNoCurrent, 1));
        assertTrue(ex.getMessage().contains("currentPriceCents"), ex.getMessage());
    }

    @Test
    void fromJson_missingCalculationVersion_isRejected() {
        String json = FIXED_OK.replace(",\"calculationVersion\":1", "");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("calculationVersion"), ex.getMessage());
    }

    @Test
    void fromJson_calculationVersionDifferentFromColumn_isRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(FIXED_OK, 2));
        assertTrue(ex.getMessage().contains("calculationVersion"), ex.getMessage());
    }

    @Test
    void fromJson_decimalWrittenAsNumber_isRejected_stringsStayTheContract() {
        String json = FIXED_OK.replace("\"annualRate\":\"0.1\"", "\"annualRate\":0.1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("annualRate"), ex.getMessage());
    }

    @Test
    void fromJson_textKeyThatIsNotAString_isRejected() {
        String json = FIXED_OK.replace("\"kind\":\"FIXED_RATE\"", "\"kind\":1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("kind"), ex.getMessage());
    }

    @Test
    void fromJson_ratesPresentButNotAnObject_isRejected() {
        String json = "{\"kind\":\"BENCHMARK_PERCENT\",\"capitalCents\":100000,\"months\":12,\"benchmark\":\"CDI\",\"benchmarkPercent\":\"1.1\","
                + "\"rates\":\"BCB\",\"purchasePriceCents\":0,\"quantity\":0,\"currentPriceCents\":0,\"dividendPerShareCents\":0,\"calculationVersion\":1}";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SimulationParametersJson.fromJson(json, 1));
        assertTrue(ex.getMessage().contains("rates"), ex.getMessage());
    }

    // ----- repository: rows written by toJson stay valid; damaged rows are reported, preserved -----

    @Test
    void everyKindWrittenByToJson_roundTripsUnderStrictDecoding() {
        List<SimulationParameters> all = List.of(
                SimulationParameters.fixedRate(1_000_000L, 12, new BigDecimal("0.10")),
                SimulationParameters.benchmark(1_000_000L, 24, Benchmark.CDI, new BigDecimal("1.10"),
                        new RateSnapshot(new BigDecimal("0.10"), new BigDecimal("0.15"), new BigDecimal("0.045"), "BCB/SGS", null)),
                SimulationParameters.hybrid(500_000L, 36, new BigDecimal("0.05"), new BigDecimal("0.045")),
                SimulationParameters.stock("hglg11", 16_000L, 50, 0L, 0L, new BigDecimal("-5.0"), "manual", null));
        int i = 0;
        for (SimulationParameters p : all) {
            SimulationScenario saved = repo.saveScenario(SimulationScenario.create("S" + (i++), p));
            assertEquals(p, repo.findScenario(saved.id()).orElseThrow().parameters(), p.kind().name());
        }
        Listing<SimulationScenario> listing = repo.listScenariosDetailed();
        assertEquals(4, listing.valid().size());
        assertTrue(listing.invalid().isEmpty(), listing.invalid().toString());
    }

    @Test
    void listScenariosDetailed_reviewReproducer_isInvalidNotCoerced_andRowPreserved() throws SQLException {
        repo.saveScenario(SimulationScenario.create("Alpha", SavedAnalysisRepositoryScenarioTest.cdiScenario("0.10")));
        String damaged = "{\"kind\":\"FIXED_RATE\",\"capitalCents\":100000.9,\"months\":4294967308,\"annualRate\":\"0.1\"}";
        long id = insertRaw("Danificado", damaged, 1);

        Listing<SimulationScenario> listing = repo.listScenariosDetailed();

        assertEquals(List.of("Alpha"), listing.valid().stream().map(SimulationScenario::name).toList());
        assertEquals(1, listing.invalid().size());
        assertEquals(id, listing.invalid().get(0).id());
        assertEquals("Danificado", listing.invalid().get(0).name());
        assertEquals(damaged, rawJson(id), "a read never rewrites the row");
        assertThrows(SavedAnalysisRepository.CorruptScenarioException.class, () -> repo.findScenario(id));
    }

    @Test
    void listScenariosDetailed_versionMismatchBetweenJsonAndColumn_isInvalid() throws SQLException {
        long id = insertRaw("Versao", FIXED_OK, 2);

        Listing<SimulationScenario> listing = repo.listScenariosDetailed();

        assertTrue(listing.valid().isEmpty());
        assertEquals(id, listing.invalid().get(0).id());
        assertTrue(listing.invalid().get(0).reason().contains("calculationVersion"), listing.invalid().get(0).reason());
    }
}

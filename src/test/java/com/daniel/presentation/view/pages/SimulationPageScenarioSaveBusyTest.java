package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Cenários salvos" card of the Simulação page while a save is in flight (worker held by the
 * test): the name field and the parameter inputs are locked, Enter in the name field does not
 * queue a second write, and the save callback renders exactly what was persisted — it never
 * resurrects a stale form, and a user edit made after the lock is released is not overwritten.
 */
class SimulationPageScenarioSaveBusyTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository scenarios;
    final Deque<Runnable> worker = new ArrayDeque<>();
    SimulationPage page;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("scen.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE simulation_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "parameters_json TEXT NOT NULL, calculation_version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
        }
        scenarios = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        FxTestSupport.runAndWait(() -> page = new SimulationPage(scenarios, worker::add, Runnable::run));
    }

    long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = SimulationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    /** Fixed-rate form: R$ 10.000,00 for 12 months at 12,5% a.a. */
    void fillFixedRate(String name) throws Exception {
        TextField initialValueField = field("initialValueField");
        TextField fixedRateField = field("fixedRateField");
        TextField scenarioNameField = field("scenarioNameField");
        initialValueField.setText("10000");
        fixedRateField.setText("12,5");
        scenarioNameField.setText(name);
    }

    @Test
    void enterDuringSave_doesNotQueueSecondWrite_andInputsAreLocked() throws Exception {
        FxTestSupport.runAndWait(() -> {
            fillFixedRate("CDB");
            TextField scenarioNameField = field("scenarioNameField");
            TextField initialValueField = field("initialValueField");
            TextField fixedRateField = field("fixedRateField");
            Button saveScenarioBtn = field("saveScenarioBtn");
            Button newScenarioBtn = field("newScenarioBtn");
            ComboBox<?> monthsCombo = field("monthsCombo");

            saveScenarioBtn.fire();
            assertEquals(1, worker.size(), "one write queued");
            assertTrue(saveScenarioBtn.isDisabled());
            assertTrue(newScenarioBtn.isDisabled());
            assertTrue(scenarioNameField.isDisabled(), "name locked while the write is in flight");
            assertTrue(initialValueField.isDisabled(), "parameters locked while the write is in flight");
            assertTrue(fixedRateField.isDisabled());
            assertTrue(monthsCombo.isDisabled());

            scenarioNameField.fireEvent(new ActionEvent(scenarioNameField, scenarioNameField)); // Enter
            saveScenarioBtn.fire();
            assertEquals(1, worker.size(), "no second write while busy");

            drain();

            assertFalse(saveScenarioBtn.isDisabled());
            assertFalse(scenarioNameField.isDisabled());
            assertFalse(initialValueField.isDisabled());
            assertFalse(fixedRateField.isDisabled());
            assertFalse(monthsCombo.isDisabled());
            assertEquals(1, count("SELECT COUNT(*) FROM simulation_scenario"));
            assertEquals("CDB", scenarioNameField.getText());
            assertEquals("CDB", scenarios.listScenarios().get(0).name());
        });
    }

    @Test
    void saveLocksTypeAndMarketActions_untilTheWriteAnswers() throws Exception {
        FxTestSupport.runAndWait(() -> {
            fillFixedRate("Locked");
            ((Button) field("saveScenarioBtn")).fire();
            for (String name : new String[]{"prefixadoBtn", "posfixadoBtn", "hibridoBtn", "acaoBtn",
                    "refreshRatesBtn", "lookupTickerBtn"}) {
                assertTrue(((javafx.scene.control.Control) field(name)).isDisabled(), name);
            }
            drain();
            for (String name : new String[]{"prefixadoBtn", "posfixadoBtn", "hibridoBtn", "acaoBtn",
                    "refreshRatesBtn", "lookupTickerBtn"}) {
                assertFalse(((javafx.scene.control.Control) field(name)).isDisabled(), name);
            }
        });
    }

    @Test
    void saveInvalidatesEarlierMarketResponses_evenAfterTheWriteAnswers() throws Exception {
        FxTestSupport.runAndWait(() -> {
            fillFixedRate("Snapshot");
            var epoch = SimulationPage.class.getDeclaredMethod("formEpoch");
            epoch.setAccessible(true);
            long requested = (long) epoch.invoke(page);
            ((Button) field("saveScenarioBtn")).fire();
            assertNotEquals(requested, (long) epoch.invoke(page), "a pre-save response must be stale during save");
            drain();
            assertNotEquals(requested, (long) epoch.invoke(page), "it must remain stale after save");
            assertEquals(new BigDecimal("0.125"), scenarios.listScenarios().get(0).parameters().annualRate());
        });
    }

    @Test
    void saveCallback_rendersWhatWasPersisted_andReloadDoesNotOverwriteLaterEdit() throws Exception {
        FxTestSupport.runAndWait(() -> {
            fillFixedRate("Original");
            TextField scenarioNameField = field("scenarioNameField");
            TextField fixedRateField = field("fixedRateField");
            Button saveScenarioBtn = field("saveScenarioBtn");

            saveScenarioBtn.fire();
            assertEquals(1, worker.size());
            Runnable save = worker.poll();
            save.run();                       // the save callback renders "Original" and queues the reload
            assertEquals("Original", scenarioNameField.getText());
            assertEquals("12,5", fixedRateField.getText(), "renderSaved reflects the persisted parameters");
            assertEquals(1, worker.size(), "reload queued after the save");

            // the lock is released now: the user keeps editing while the reload is still pending
            assertFalse(scenarioNameField.isDisabled());
            scenarioNameField.setText("Original editado");
            fixedRateField.setText("13");

            drain();                          // reload callback arrives

            assertEquals("Original editado", scenarioNameField.getText(), "a pending reload must not overwrite the edit");
            assertEquals("13", fixedRateField.getText());
            SimulationScenario stored = scenarios.listScenarios().get(0);
            assertEquals("Original", stored.name(), "nothing was written by the edit itself");
            assertEquals(new BigDecimal("0.125"), stored.parameters().annualRate());
        });
    }
}

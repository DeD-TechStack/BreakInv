package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The controller is the real caller of the repository for every asynchronous
 * scenario operation of the simulation page. Worker tasks are queued in a
 * deterministic executor so the test can interleave user actions ("Novo") between
 * the start of an operation and its callback, exactly as the single shared
 * UiExecutor thread allows in the application. No JavaFX.
 */
class ScenarioCardControllerTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;
    final Deque<Runnable> worker = new ArrayDeque<>();
    final FakeView view = new FakeView();
    ScenarioCardController ctrl;

    static final class FakeView implements ScenarioCardController.View {
        List<SimulationScenario> lastList = null;
        SimulationScenario lastSelected = null;
        int listRenders = 0;
        SimulationScenario lastLoadedRendered = null;
        SimulationScenario lastSavedRendered = null;
        int newRenders = 0;
        boolean busy = false;
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> successes = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override public void renderList(List<SimulationScenario> scenarios, SimulationScenario selected) {
            lastList = scenarios; lastSelected = selected; listRenders++;
        }
        @Override public void renderLoaded(SimulationScenario scenario) { lastLoadedRendered = scenario; }
        @Override public void renderSaved(SimulationScenario saved) { lastSavedRendered = saved; }
        @Override public void renderNew() { newRenders++; }
        @Override public void setBusy(boolean busy) { this.busy = busy; }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public void error(String message) { errors.add(message); }
        @Override public void success(String message) { successes.add(message); }
        @Override public void info(String message) { infos.add(message); }
    }

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("ctrl.db").toString().replace("\\", "/");
        exec("CREATE TABLE simulation_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "parameters_json TEXT NOT NULL, calculation_version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        ctrl = new ScenarioCardController(repo, worker::add, Runnable::run, view);
    }

    void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute(sql);
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

    /** Runs every queued worker task (and the tasks they enqueue) in order. */
    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    static SimulationParameters params(String rate) {
        return SimulationParameters.fixedRate(1_000_000L, 12, new BigDecimal(rate));
    }

    // ===== LE3: ownership of callbacks =====

    @Test
    void reviewCase_novoWhileReloadIsQueued_doesNotReattachAndNextSaveInsertsNewScenario() throws SQLException {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        ctrl.load(a);
        ctrl.reload();                 // onShow: queued behind other UiExecutor work
        assertTrue(ctrl.startNew());   // user clicks "Novo" before the reload returns
        drain();                       // reload callback arrives now

        assertNull(ctrl.state().loaded(), "stale reload must not re-attach A");
        assertNull(view.lastSelected, "combo must stay unselected");
        assertEquals(1, view.lastList.size());

        ctrl.save("B", params("0.20"), false);
        drain();

        assertEquals(2, count("SELECT COUNT(*) FROM simulation_scenario"));
        assertEquals("A", repo.findScenario(a.id()).orElseThrow().name(), "A must not be overwritten");
        assertEquals(new BigDecimal("0.10"), repo.findScenario(a.id()).orElseThrow().parameters().annualRate());
        assertEquals("B", ctrl.state().loaded().name());
    }

    @Test
    void reviewCase_novoRightAfterSalvar_isRefusedWhileSaving_andSavedScenarioAttaches() {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        ctrl.load(a);
        ctrl.save("A renomeado", params("0.11"), false);

        assertTrue(view.busy, "Novo must be disabled while the save is in flight");
        assertFalse(ctrl.startNew(), "Novo during a save is refused");
        drain();

        assertFalse(view.busy);
        assertEquals(a.id(), ctrl.state().loaded().id());
        assertEquals("A renomeado", ctrl.state().loaded().name());
        assertEquals(1, repo.listScenarios().size());
    }

    @Test
    void save_newForm_attachesSavedId_thenSecondSaveUpdatesTheSameRow() throws SQLException {
        ctrl.startNew();
        ctrl.save("N", params("0.10"), false);
        drain();
        Long id = ctrl.state().loaded().id();
        assertNotNull(id);
        assertEquals(id, view.lastSavedRendered.id());
        assertEquals(id, view.lastSelected.id(), "reload after save selects the saved scenario");

        ctrl.save("N2", params("0.12"), false);
        drain();

        assertEquals(1, count("SELECT COUNT(*) FROM simulation_scenario"));
        assertEquals("N2", repo.findScenario(id).orElseThrow().name());
    }

    @Test
    void saveAsNew_whileLoaded_insertsAndAttachesTheCopy() throws SQLException {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        ctrl.load(a);

        ctrl.save("A2", params("0.15"), true);
        drain();

        assertEquals(2, count("SELECT COUNT(*) FROM simulation_scenario"));
        assertNotEquals(a.id(), ctrl.state().loaded().id());
        assertEquals("A", repo.findScenario(a.id()).orElseThrow().name());
    }

    @Test
    void duplicate_attachesCopyAndKeepsOriginalUnchanged() {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        ctrl.load(a);

        ctrl.duplicate();
        drain();

        assertEquals("A (cópia)", ctrl.state().loaded().name());
        assertNotEquals(a.id(), ctrl.state().loaded().id());
        assertEquals("A", repo.findScenario(a.id()).orElseThrow().name());
        assertEquals("A (cópia)", view.lastSavedRendered.name());
    }

    @Test
    void duplicate_whenNovoClickedBeforeCallback_copyExistsButFormStaysNew() {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        ctrl.load(a);
        ctrl.duplicate();
        // the form cannot be reset by the user while busy, but a programmatic reset (e.g. delete
        // callback from an earlier operation) advances the epoch: the copy must then not attach
        ctrl.state().startNew();
        drain();

        assertNull(ctrl.state().loaded());
        assertEquals(2, repo.listScenarios().size());
        assertFalse(view.infos.isEmpty(), "user is told the copy was created but not attached");
    }

    @Test
    void delete_clearsFormAndListNoLongerContainsIt() throws SQLException {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        ctrl.load(a);

        ctrl.delete();
        drain();

        assertNull(ctrl.state().loaded());
        assertEquals(1, view.newRenders);
        assertEquals(0, count("SELECT COUNT(*) FROM simulation_scenario"));
        assertTrue(view.lastList.isEmpty());
    }

    @Test
    void load_whileBusy_isRefused() {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        SimulationScenario b = repo.saveScenario(SimulationScenario.create("B", params("0.10")));
        ctrl.load(a);
        ctrl.save("A", params("0.10"), false);

        assertFalse(ctrl.load(b));
        drain();

        assertEquals(a.id(), ctrl.state().loaded().id());
    }

    @Test
    void saveError_keepsFormIdentityAndReportsMessage() {
        repo.saveScenario(SimulationScenario.create("Taken", params("0.10")));
        ctrl.startNew();

        ctrl.save("taken", params("0.10"), false);
        drain();

        assertNull(ctrl.state().loaded());
        assertEquals(1, view.errors.size());
        assertTrue(view.errors.get(0).contains("Taken") || view.errors.get(0).contains("taken"), view.errors.get(0));
        assertFalse(view.busy);
    }

    // ===== LE9: transparent recovery =====

    @Test
    void reload_withOneCorruptRow_rendersValidOnesAndWarnsNamingTheCorruptOne() throws SQLException {
        repo.saveScenario(SimulationScenario.create("Alpha", params("0.10")));
        exec("INSERT INTO simulation_scenario(name, parameters_json, calculation_version, updated_at) "
                + "VALUES ('Quebrado', '{not json', 1, '2026-01-01T00:00:00Z')");

        ctrl.reload();
        drain();

        assertEquals(List.of("Alpha"), view.lastList.stream().map(SimulationScenario::name).toList());
        assertEquals(1, view.warnings.size());
        assertTrue(view.warnings.get(0).contains("Quebrado"), view.warnings.get(0));
        assertTrue(view.errors.isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM simulation_scenario"), "corrupt row is not deleted");
    }

    @Test
    void reload_databaseFailure_isReportedAsErrorAndListIsNotRendered() throws SQLException {
        exec("DROP TABLE simulation_scenario");

        ctrl.reload();
        drain();

        assertEquals(1, view.errors.size());
        assertEquals(0, view.listRenders, "a database failure must not be shown as an empty list");
    }

    @Test
    void reload_keepsSelectionOfCurrentlyLoadedScenario() {
        SimulationScenario a = repo.saveScenario(SimulationScenario.create("A", params("0.10")));
        repo.saveScenario(SimulationScenario.create("B", params("0.10")));
        ctrl.load(a);

        ctrl.reload();
        drain();

        assertEquals(a.id(), view.lastSelected.id());
        assertEquals(a.id(), ctrl.state().loaded().id());
    }
}

package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.service.ARCADiversificationStrategy;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real caller of the plan repository for the Diversificação page (RF10 extension),
 * without JavaFX. Covers: save+select as one transaction from the editor (LE6),
 * invalid selected plan → explicit ARCA fallback with a warning while the stored
 * row and the stored selection are preserved (LE9), and partial listings.
 */
class AllocationPlanCardControllerTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository repo;
    final Deque<Runnable> worker = new ArrayDeque<>();
    final FakeView view = new FakeView();
    AllocationPlanCardController ctrl;

    static final class FakeView implements AllocationPlanCardController.View {
        List<AllocationPlan> lastPlans = null;
        Long lastSelectedId = null;
        int listRenders = 0;
        Map<CategoryEnum, Double> lastProfile = null;
        AllocationPlan lastProfilePlan = null;
        int profileRenders = 0;
        boolean busy = false;
        String editorError = null;
        int editorClosed = 0;
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> successes = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override public void renderPlans(List<AllocationPlan> plans, Long selectedId) {
            lastPlans = plans; lastSelectedId = selectedId; listRenders++;
        }
        @Override public void renderProfile(Map<CategoryEnum, Double> profile, AllocationPlan plan) {
            lastProfile = profile; lastProfilePlan = plan; profileRenders++;
        }
        @Override public void setBusy(boolean busy) { this.busy = busy; }
        @Override public void showEditorError(String message) { editorError = message; }
        @Override public void closeEditor() { editorClosed++; }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public void error(String message) { errors.add(message); }
        @Override public void success(String message) { successes.add(message); }
        @Override public void info(String message) { infos.add(message); }
    }

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("plans.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE allocation_plan (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, updated_at TEXT NOT NULL)");
            st.execute("CREATE TABLE allocation_item (plan_id INTEGER NOT NULL REFERENCES allocation_plan(id) ON DELETE CASCADE, "
                    + "category TEXT NOT NULL, weight_bps INTEGER NOT NULL CHECK (weight_bps BETWEEN 0 AND 10000), PRIMARY KEY (plan_id, category))");
            st.execute("CREATE TABLE allocation_selection (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
                    + "plan_id INTEGER REFERENCES allocation_plan(id) ON DELETE SET NULL)");
        }
        repo = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        ctrl = new AllocationPlanCardController(repo, worker::add, Runnable::run, view);
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

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
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

    // ===== LE6: save + select is one call, one transaction =====

    @Test
    void saveFromEditor_newPlan_isSavedAndSelectedAndEditorCloses() throws SQLException {
        ctrl.saveFromEditor(conservador());
        drain();

        assertEquals(1, view.editorClosed);
        assertNull(view.editorError);
        AllocationPlan saved = repo.listPlans().get(0);
        assertEquals(saved.id(), repo.selectedPlanId().orElseThrow());
        assertEquals(saved.id(), view.lastSelectedId);
        assertEquals(0.70, view.lastProfile.get(CategoryEnum.RENDA_FIXA), 1e-9);
    }

    @Test
    void saveFromEditor_selectionFailure_leavesNoPlanBehind_andRetrySucceeds() throws SQLException {
        exec("CREATE TRIGGER fail_sel BEFORE INSERT ON allocation_selection BEGIN SELECT RAISE(ABORT, 'injected'); END");

        ctrl.saveFromEditor(conservador());
        drain();

        assertNotNull(view.editorError);
        assertTrue(view.editorError.contains("injected"), view.editorError);
        assertEquals(0, view.editorClosed);
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_item"));

        exec("DROP TRIGGER fail_sel");
        ctrl.saveFromEditor(conservador());
        drain();

        assertEquals(1, view.editorClosed, "retry must not fail with duplicate name");
        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
    }

    @Test
    void saveFromEditor_duplicateName_showsEditorErrorAndKeepsOriginal() throws SQLException {
        AllocationPlan a = repo.savePlanAndSelect(conservador());

        ctrl.saveFromEditor(AllocationPlan.create("conservador", weights(10000, 0, 0, 0, 0, 0, 0)));
        drain();

        assertNotNull(view.editorError);
        assertEquals(0, view.editorClosed);
        assertEquals(7000, repo.findPlan(a.id()).orElseThrow().weightsBps().get(CategoryEnum.RENDA_FIXA));
    }

    // ===== busy guard: a write in flight refuses re-entrant writes =====

    @Test
    void saveFromEditor_whileSaveInFlight_secondSaveIsRefused_andOnlyOneRowIsWritten() throws SQLException {
        assertTrue(ctrl.saveFromEditor(conservador()), "first save starts");
        assertTrue(ctrl.isBusy(), "busy while the worker has not answered");
        assertTrue(view.busy);

        assertFalse(ctrl.saveFromEditor(AllocationPlan.create("Outro", weights(10000, 0, 0, 0, 0, 0, 0))),
                "Enter/double click during a save must not start a second write");
        assertFalse(ctrl.delete(conservador()), "delete during a save is refused too");
        assertEquals(1, worker.size(), "exactly one write queued");
        drain();

        assertFalse(ctrl.isBusy());
        assertFalse(view.busy);
        assertEquals(1, view.editorClosed, "the editor closes once, for the write that ran");
        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
        assertEquals("Conservador", repo.listPlans().get(0).name());
        assertTrue(view.successes.size() == 1, view.successes.toString());
    }

    @Test
    void saveFromEditor_afterFailure_busyIsReleased_andNextSaveIsAccepted() throws SQLException {
        exec("CREATE TRIGGER fail_sel BEFORE INSERT ON allocation_selection BEGIN SELECT RAISE(ABORT, 'injected'); END");
        assertTrue(ctrl.saveFromEditor(conservador()));
        drain();
        assertFalse(ctrl.isBusy(), "a failed write releases the guard");
        exec("DROP TRIGGER fail_sel");

        assertTrue(ctrl.saveFromEditor(conservador()));
        drain();
        assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"));
    }

    // ===== LE9: invalid selected plan → transparent fallback =====

    @Test
    void refresh_selectedPlanInvalid_usesArcaWithExplicitWarning_andKeepsRowAndSelection() throws SQLException {
        AllocationPlan a = repo.savePlanAndSelect(conservador());
        exec("DELETE FROM allocation_item WHERE plan_id = " + a.id() + " AND category = 'ACOES'");

        ctrl.refresh();
        drain();

        assertEquals(ARCADiversificationStrategy.getARCAProfile(), view.lastProfile, "fallback is ARCA");
        assertNull(view.lastProfilePlan);
        assertEquals(1, view.warnings.size());
        assertTrue(view.warnings.get(0).contains("Conservador"), view.warnings.get(0));
        assertTrue(view.warnings.get(0).contains("Ações"), view.warnings.get(0));
        assertTrue(view.errors.isEmpty(), "not a general error");
        assertEquals(a.id(), repo.selectedPlanId().orElseThrow(), "selection not rewritten by a read");
        assertEquals(6, count("SELECT COUNT(*) FROM allocation_item"), "stored items not deleted");
    }

    @Test
    void refresh_validSelectedPlan_usesItsProfileWithoutWarnings() {
        AllocationPlan a = repo.savePlanAndSelect(conservador());

        ctrl.refresh();
        drain();

        assertEquals(a.id(), view.lastProfilePlan.id());
        assertEquals(0.70, view.lastProfile.get(CategoryEnum.RENDA_FIXA), 1e-9);
        assertTrue(view.warnings.isEmpty());
    }

    @Test
    void refresh_noSelection_usesArcaSilently() {
        ctrl.refresh();
        drain();

        assertEquals(ARCADiversificationStrategy.getARCAProfile(), view.lastProfile);
        assertTrue(view.warnings.isEmpty());
    }

    @Test
    void refresh_databaseFailure_isReportedAsErrorNotAsArca() throws SQLException {
        repo.savePlanAndSelect(conservador());
        exec("DROP TABLE allocation_item");

        ctrl.refresh();
        drain();

        assertEquals(1, view.errors.size());
        assertEquals(0, view.profileRenders, "a database error must not be rendered as a fallback profile");
    }

    @Test
    void reload_invalidPlan_isSkippedWithWarning_validOnesListed_andComboFallsBackToArca() throws SQLException {
        AllocationPlan a = repo.savePlanAndSelect(conservador());
        AllocationPlan b = repo.savePlan(AllocationPlan.create("Arrojado", weights(2000, 4000, 1500, 500, 0, 1500, 500)));
        exec("DELETE FROM allocation_item WHERE plan_id = " + a.id() + " AND category = 'RENDA_FIXA'");

        ctrl.reload(true);
        drain();

        assertEquals(List.of(b.id()), view.lastPlans.stream().map(AllocationPlan::id).toList());
        assertNull(view.lastSelectedId, "combo shows ARCA because the selected plan is unusable");
        assertFalse(view.warnings.isEmpty());
        assertTrue(view.warnings.stream().anyMatch(w -> w.contains("Conservador")), view.warnings.toString());
        assertEquals(ARCADiversificationStrategy.getARCAProfile(), view.lastProfile);
        assertEquals(a.id(), repo.selectedPlanId().orElseThrow(), "persisted selection untouched");
    }

    @Test
    void reload_databaseFailure_isErrorAndNothingRendered() throws SQLException {
        exec("DROP TABLE allocation_plan");

        ctrl.reload(true);
        drain();

        assertEquals(1, view.errors.size());
        assertEquals(0, view.listRenders);
    }

    // ===== selection / delete through the controller =====

    @Test
    void choose_persistsSelectionAndRefreshesProfile() {
        AllocationPlan a = repo.savePlan(conservador());

        ctrl.choose(a.id());
        drain();

        assertEquals(a.id(), repo.selectedPlanId().orElseThrow());
        assertEquals(a.id(), view.lastProfilePlan.id());
    }

    @Test
    void choose_null_selectsArca() {
        AllocationPlan a = repo.savePlanAndSelect(conservador());

        ctrl.choose(null);
        drain();

        assertTrue(repo.selectedPlanId().isEmpty());
        assertEquals(ARCADiversificationStrategy.getARCAProfile(), view.lastProfile);
        assertNotNull(a.id());
    }

    @Test
    void delete_selectedPlan_fallsBackToArcaAndListNoLongerContainsIt() throws SQLException {
        AllocationPlan a = repo.savePlanAndSelect(conservador());

        ctrl.delete(a);
        drain();

        assertTrue(repo.selectedPlanId().isEmpty());
        assertTrue(view.lastPlans.isEmpty());
        assertNull(view.lastSelectedId);
        assertEquals(0, count("SELECT COUNT(*) FROM allocation_item"));
        assertFalse(view.busy);
    }
}

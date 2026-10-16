package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;

import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan editor of the Diversificação page while a save is in flight (worker held by the test):
 * ESC must not close the editor, Enter must not queue a second write, the inputs are locked, and
 * when the worker answers the single write closes the editor once. The JavaFX toolkit runs
 * without any window; events are fired on the nodes through the normal dispatch chain.
 */
class DiversificationPagePlanEditorBusyTest {

    @TempDir
    Path tempDir;

    String jdbcUrl;
    SavedAnalysisRepository plans;
    final Deque<Runnable> worker = new ArrayDeque<>();
    DiversificationPage page;

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
        plans = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        FxTestSupport.runAndWait(() -> page = new DiversificationPage(stubDaily(), plans, worker::add, Runnable::run));
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
        Field f = DiversificationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    void openEditorWithValidPlan(String name) throws Exception {
        Button newPlanBtn = field("newPlanBtn");
        newPlanBtn.fire();
        TextField planNameField = field("planNameField");
        planNameField.setText(name);
        Map<CategoryEnum, TextField> percents = field("planPercentFields");
        for (CategoryEnum cat : CategoryEnum.values()) percents.get(cat).setText(cat == CategoryEnum.RENDA_FIXA ? "100,00" : "0,00");
    }

    static KeyEvent escape() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
    }

    @Test
    void escapeAndEnterDuringSave_doNotCloseOrResubmit_singleCommitClosesEditorOnce() throws Exception {
        FxTestSupport.runAndWait(() -> {
            openEditorWithValidPlan("Conservador");
            VBox editor = field("planEditorBox");
            TextField planNameField = field("planNameField");
            Button savePlanBtn = field("savePlanBtn");
            Button newPlanBtn = field("newPlanBtn");
            ComboBox<?> planCombo = field("planCombo");
            assertTrue(editor.isVisible());

            savePlanBtn.fire();                                  // user clicks Salvar
            assertEquals(1, worker.size(), "one write queued");
            assertTrue(savePlanBtn.isDisabled(), "Salvar locked while the write is in flight");

            Event.fireEvent(planNameField, escape());            // ESC while saving
            assertTrue(editor.isVisible(), "ESC must not close the editor the pending callback acts on");
            assertTrue(newPlanBtn.isDisabled(), "Novo stays locked: no second editor can be started");
            assertTrue(planCombo.isDisabled());
            assertTrue(planNameField.isDisabled(), "inputs locked while the write is in flight");

            planNameField.fireEvent(new ActionEvent(planNameField, planNameField)); // Enter in the name field
            savePlanBtn.fire();                                  // and a second click on the disabled button path
            assertEquals(1, worker.size(), "no second write may be queued while busy");

            drain();                                             // the single write answers

            assertFalse(editor.isVisible(), "the one save closed the editor");
            assertFalse(savePlanBtn.isDisabled());
            assertFalse(planNameField.isDisabled());
            assertFalse(newPlanBtn.isDisabled());
            assertFalse(planCombo.isDisabled());
            assertEquals(1, count("SELECT COUNT(*) FROM allocation_plan"), "exactly one commit");
            assertEquals("Conservador", plans.listPlans().get(0).name());
            assertEquals(plans.listPlans().get(0).id(), plans.selectedPlanId().orElseThrow());
        });
    }

    @Test
    void escapeWhenNotBusy_stillClosesTheEditor() throws Exception {
        FxTestSupport.runAndWait(() -> {
            openEditorWithValidPlan("Qualquer");
            VBox editor = field("planEditorBox");
            TextField planNameField = field("planNameField");
            Event.fireEvent(planNameField, escape());
            assertFalse(editor.isVisible(), "ESC outside a write keeps its normal meaning");
            assertEquals(0, worker.size());
        });
    }

    @Test
    void failedSave_keepsEditorOpenWithError_andReleasesTheLock() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER fail_sel BEFORE INSERT ON allocation_selection BEGIN SELECT RAISE(ABORT, 'injected'); END");
        }
        FxTestSupport.runAndWait(() -> {
            openEditorWithValidPlan("Falha");
            Button savePlanBtn = field("savePlanBtn");
            savePlanBtn.fire();
            drain();
            VBox editor = field("planEditorBox");
            javafx.scene.control.Label error = field("planErrorLabel");
            TextField planNameField = field("planNameField");
            assertTrue(editor.isVisible(), "editor stays open on failure");
            assertTrue(error.isVisible());
            assertTrue(error.getText().contains("injected"), error.getText());
            assertFalse(planNameField.isDisabled(), "user can correct and retry");
            assertFalse(savePlanBtn.isDisabled());
            assertEquals("Falha", planNameField.getText(), "typed text preserved");
            assertEquals(0, count("SELECT COUNT(*) FROM allocation_plan"));
        });
    }

    @Test
    void changingSelection_updatesHintAndEditActionsInBothDirections() throws Exception {
        FxTestSupport.runAndWait(() -> {
            openEditorWithValidPlan("Conservador");
            ((Button) field("savePlanBtn")).fire();
            drain();
            ComboBox<?> combo = field("planCombo");
            Button edit = field("editPlanBtn");
            Button delete = field("deletePlanBtn");
            javafx.scene.control.Label hint = field("planHint");
            combo.getSelectionModel().select(0);
            drain();
            assertTrue(edit.isDisabled(), "ARCA cannot be edited");
            assertTrue(delete.isDisabled(), "ARCA cannot be deleted");
            assertTrue(hint.getText().contains("estratégia padrão"), hint.getText());
            combo.getSelectionModel().select(1);
            drain();
            assertFalse(edit.isDisabled(), "selected saved plan must be editable");
            assertFalse(delete.isDisabled(), "selected saved plan must be deletable");
            assertTrue(hint.getText().contains("100"), hint.getText());
            edit.fire();
            assertEquals("Conservador", ((TextField) field("planNameField")).getText());
            assertTrue(((VBox) field("planEditorBox")).isVisible());
        });
    }

    // ----- DailyTrackingUseCase without ledger and without data -----

    static DailyTrackingUseCase stubDaily() {
        IInvestmentTypeRepository types = new IInvestmentTypeRepository() {
            @Override public List<InvestmentType> listAll() { return List.of(); }
            @Override public void save(String name) {}
            @Override public void rename(int id, String newName) {}
            @Override public void delete(int id) {}
        };
        IFlowRepository flows = new IFlowRepository() {
            @Override public List<Flow> listForDate(LocalDate date) { return List.of(); }
            @Override public void delete(long id) {}
            @Override public long create(Flow flow) { return 0; }
        };
        ISnapshotRepository snapshots = new ISnapshotRepository() {
            @Override public long getCash(LocalDate date) { return 0; }
            @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) { return Map.of(); }
            @Override public Map<String, Long> seriesForInvestiments(long investimentsTypeId) { return Map.of(); }
        };
        ITransactionRepository txs = new ITransactionRepository() {
            @Override public long insert(Transaction transaction) { return 0; }
            @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) { return List.of(); }
        };
        return new DailyTrackingUseCase(flows, types, snapshots, txs, ticker -> null);
    }
}

package com.daniel.presentation.view.pages;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.main.LogCapture;
import com.daniel.main.SnapshotFixture;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Motion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U4C:RF-04 for the two pages, and U4C:RF-05 item 4: the daily snapshot that fails (the write gate refuses, turned
 * on after the data was created) shows the notice with the reason, logs a WARNING of the page's own class, and the
 * page goes on (Carteira refreshes, Extrato reloads). The executors and the notice are the test seams of the pages.
 */
class DailySnapshotCallersTest {

    @TempDir
    Path tempDir;
    String previousUrl;
    DailyTrackingUseCase uc;
    final Deque<Runnable> worker = new ArrayDeque<>();
    final Deque<Runnable> ui = new ArrayDeque<>();
    final List<String> shown = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Motion.configure(null);
        previousUrl = Database.currentUrl();
        uc = SnapshotFixture.useCaseWithOneInvestment(tempDir);
    }

    @AfterEach
    void tearDown() {
        Database.setWriteGate(null);
        Database.close();
        Database.configure(previousUrl);
        Motion.configure(null);
    }

    private void drain() {
        while (!worker.isEmpty() || !ui.isEmpty()) {
            while (!worker.isEmpty()) worker.poll().run();
            while (!ui.isEmpty()) ui.poll().run();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> owner, Object page, String name) {
        try {
            return PageRefreshReadCountTest.field(owner, page, name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void carteira_aFailedSnapshotShowsTheNoticeLogsAWarningAndTheListStillRefreshes() {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = new InvestmentTypesPage(uc, InvestmentTypesPageLayoutTest.memoryPrefs(),
                    worker::add, ui::add, shown::add);
            SnapshotFixture.refuseWrites();
            try (LogCapture log = new LogCapture(InvestmentTypesPage.class)) {
                page.refreshAndSnapshot();
                drain();

                var warnings = log.of(Level.WARNING, "daily snapshot failed");
                assertEquals(1, warnings.size());
                assertNotNull(warnings.get(0).getThrown());
            }
            System.out.println("U4C-RF05 caso 4 (Carteira) | aviso=" + shown);
            assertEquals(List.of(SnapshotFixture.expectedNotice()), shown);
            javafx.collections.ObservableList<?> items = field(InvestmentTypesPage.class, page, "allItems");
            assertEquals(1, items.size(), "the page went on refreshing");
        });
    }

    @Test
    void carteira_aSnapshotThatWorksShowsNothingAndLogsNothing() {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = new InvestmentTypesPage(uc, InvestmentTypesPageLayoutTest.memoryPrefs(),
                    worker::add, ui::add, shown::add);
            try (LogCapture log = new LogCapture(InvestmentTypesPage.class)) {
                page.refreshAndSnapshot();
                drain();

                assertEquals(List.of(), log.records().stream().filter(r -> r.getLevel() == Level.WARNING).toList());
            }
            assertEquals(List.of(), shown);
            assertEquals(1, uc.read().listTypes(false).size());
        });
    }

    @Test
    void carteira_theNoticeIsShownOnTheUiExecutorNotOnTheWorker() {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypesPage page = new InvestmentTypesPage(uc, InvestmentTypesPageLayoutTest.memoryPrefs(),
                    worker::add, ui::add, shown::add);
            SnapshotFixture.refuseWrites();
            worker.clear(); // whatever the construction queued is not the snapshot
            ui.clear();

            page.refreshAndSnapshot();
            worker.poll().run(); // the snapshot, queued first

            assertEquals(List.of(), shown);
            assertEquals(1, ui.size());
            ui.poll().run();
            assertEquals(1, shown.size());
        });
    }

    @Test
    void extrato_aFailedSnapshotAfterAReversalShowsTheNoticeLogsAWarningAndTheStatementStillReloads() {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(uc, worker::add, ui::add, shown::add);
            SnapshotFixture.refuseWrites();
            try (LogCapture log = new LogCapture(ReportsPage.class)) {
                page.snapshotAndReload();
                drain();

                var warnings = log.of(Level.WARNING, "daily snapshot failed");
                assertEquals(1, warnings.size());
                assertNotNull(warnings.get(0).getThrown());
            }
            System.out.println("U4C-RF05 caso 4 (Extrato) | aviso=" + shown);
            assertEquals(List.of(SnapshotFixture.expectedNotice()), shown);
            List<?> rows = field(ReportsPage.class, page, "allRows");
            assertFalse(rows.isEmpty(), "the statement was reloaded");
        });
    }

    @Test
    void extrato_aSnapshotThatWorksShowsNothingAndLogsNothing() {
        FxTestSupport.runAndWait(() -> {
            ReportsPage page = new ReportsPage(uc, worker::add, ui::add, shown::add);
            try (LogCapture log = new LogCapture(ReportsPage.class)) {
                page.snapshotAndReload();
                drain();

                assertEquals(List.of(), log.records().stream().filter(r -> r.getLevel() == Level.WARNING).toList());
            }
            assertEquals(List.of(), shown);
        });
    }
}

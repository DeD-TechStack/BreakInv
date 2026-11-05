package com.daniel.main;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.util.DailySnapshotNotice;
import com.daniel.presentation.view.util.UiExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U4C:RF-01 item 6 and U4C:RF-04 for the opening: after a successful opening the daily snapshot and the daily copy
 * are triggered, in that order, and a failed snapshot is a notice and a log, not the end of the rest. No window.
 */
class AppDailyWorkTest {

    @TempDir
    Path tempDir;
    final OpeningFixture fixture = new OpeningFixture();
    final Deque<Runnable> worker = new ArrayDeque<>();
    final Deque<Runnable> ui = new ArrayDeque<>();
    final List<String> order = new ArrayList<>();
    final List<String> shown = new ArrayList<>();

    @AfterEach
    void tearDown() {
        fixture.restore();
    }

    private void drain(Deque<Runnable> queue) {
        while (!queue.isEmpty()) queue.poll().run();
    }

    @Test
    void theDailySnapshotAndTheDailyCopyAreQueuedInThatOrderAndNothingIsShownWhenTheSnapshotWorks() {
        List<LocalDate> dates = new ArrayList<>();

        App.startDailyWork(date -> { dates.add(date); order.add("snapshot"); }, () -> order.add("copy"),
                worker::add, ui::add, shown::add);

        assertEquals(List.of(), order, "both run on the worker, not on the caller");
        assertEquals(2, worker.size());
        drain(worker);
        drain(ui);
        assertEquals(List.of("snapshot", "copy"), order);
        assertEquals(List.of(LocalDate.now()), dates);
        assertEquals(List.of(), shown);
    }

    @Test
    void aFailedSnapshotShowsTheNoticeLogsAWarningOfTheAppClassAndTheCopyStillRuns() {
        IllegalStateException failure = new IllegalStateException("limite de gravação atingido.");
        try (LogCapture appLog = new LogCapture(App.class)) {
            App.startDailyWork(date -> { throw failure; }, () -> order.add("copy"), worker::add, ui::add, shown::add);

            drain(worker);

            var warnings = appLog.of(Level.WARNING, "daily snapshot failed");
            assertEquals(1, warnings.size());
            assertSame(failure, warnings.get(0).getThrown());
        }
        assertEquals(List.of("copy"), order, "the daily copy goes on");
        assertEquals(List.of(), shown, "the notice is shown on the FX executor");
        drain(ui);
        assertEquals(List.of(DailySnapshotNotice.message(failure)), shown);
        assertTrue(shown.get(0).contains("(limite de gravação atingido)"), shown.get(0));
    }

    @Test
    void realFailure4_theOpeningSnapshotRefusedByTheWriteGate() {
        DailyTrackingUseCase uc = SnapshotFixture.useCaseWithOneInvestment(tempDir);
        SnapshotFixture.refuseWrites();
        try (LogCapture appLog = new LogCapture(App.class)) {
            App.startDailyWork(uc::takeSnapshotIfNeeded, () -> order.add("copy"), worker::add, ui::add, shown::add);
            drain(worker);
            drain(ui);

            assertEquals(1, appLog.of(Level.WARNING, "daily snapshot failed").size());
        }
        System.out.println("U4C-RF05 caso 4 (abertura) | aviso=" + shown);
        assertEquals(List.of(SnapshotFixture.expectedNotice()), shown);
        assertEquals(List.of("copy"), order);
    }

    @Test
    void theAttemptExecutorIsNotTheUiExecutorAndItsThreadIsNotADaemon() throws Exception {
        ExecutorService attempts = App.newAttemptExecutor();
        try {
            assertNotSame(UiExecutor.get(), attempts);
            AtomicReference<Thread> thread = new AtomicReference<>();
            attempts.submit(() -> thread.set(Thread.currentThread())).get(10, TimeUnit.SECONDS);

            assertNotNull(thread.get());
            assertFalse(thread.get().isDaemon(), "the program waits for the attempt");
            assertEquals("data-opening", thread.get().getName());
        } finally {
            attempts.shutdown();
        }
    }
}

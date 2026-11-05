package com.daniel.main;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.OpeningFailurePane;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U4C:RF-01 (the attempt of "Tentar de novo") and U4C:RF-02 (what the buttons do, also during an attempt). The
 * window itself is never shown: the pane is built on the FX thread, the attempt executor and the FX executor are
 * queues the test empties by hand.
 */
class OpeningFailureFlowTest {

    @TempDir
    Path tempDir;
    final OpeningFixture fixture = new OpeningFixture();

    final Deque<Runnable> attempts = new ArrayDeque<>();
    final Deque<Runnable> fx = new ArrayDeque<>();
    final List<String> events = new ArrayList<>();
    final List<DataOpening.Attempt> opened = new ArrayList<>();
    OpeningFailurePane pane;
    OpeningFailureFlow flow;

    @AfterEach
    void tearDown() {
        fixture.restore();
    }

    void build(DataOpening opening) {
        pane = new OpeningFailurePane("primeiro motivo", tempDir.resolve("dados.db"), tempDir.resolve("backups"));
        flow = new OpeningFailureFlow(opening, pane, attempts::add, fx::add,
                attempt -> {
                    events.add("opened");
                    opened.add(attempt);
                },
                () -> events.add("closeWindow"), () -> events.add("exit"));
    }

    void runAttempts() {
        while (!attempts.isEmpty()) attempts.poll().run();
    }

    void runFx() {
        while (!fx.isEmpty()) fx.poll().run();
    }

    String reasonLine() {
        return ((Label) pane.lookup("#opening-failure-reason")).getText();
    }

    Button retryButton() {
        return (Button) pane.lookup("#opening-failure-retry");
    }

    Button closeButton() {
        return (Button) pane.lookup("#opening-failure-close");
    }

    /** Opens a new file in the temp folder without a copy step: the attempt succeeds. */
    DataOpening openingThatSucceeds() {
        Path db = tempDir.resolve("dados.db");
        com.daniel.infrastructure.persistence.config.Database.configure(OpeningFixture.url(db));
        return new DataOpening((candidate, file) -> { });
    }

    /** The data file is a folder: the attempt fails with "arquivo de dados inacessível". */
    DataOpening openingThatFails() throws Exception {
        Path db = tempDir.resolve("dados.db");
        Files.createDirectory(db);
        com.daniel.infrastructure.persistence.config.Database.configure(OpeningFixture.url(db));
        return new DataOpening();
    }

    @Test
    void retryShowsOpeningDisablesRetryKeepsCloseAndRunsOneAttemptAtATime() throws Exception {
        DataOpening opening = openingThatFails();
        FxTestSupport.runAndWait(() -> {
            build(opening);

            flow.retry();
            flow.retry();

            assertEquals("Abrindo os dados…", reasonLine());
            assertTrue(retryButton().isDisable());
            assertFalse(closeButton().isDisable());
            assertEquals(1, attempts.size(), "a second click during the attempt starts nothing");
            assertTrue(fx.isEmpty());
            assertEquals(List.of(), events);
        });
    }

    @Test
    void aFailingAttemptKeepsTheWindowShowsTheNewReasonAndEnablesRetry() throws Exception {
        DataOpening opening = openingThatFails();
        FxTestSupport.runAndWait(() -> {
            build(opening);
            flow.retry();

            runAttempts();
            assertEquals("Abrindo os dados…", reasonLine(), "the answer waits for the FX thread");
            runFx();

            assertEquals("Motivo: arquivo de dados inacessível.", reasonLine());
            assertFalse(retryButton().isDisable());
            assertFalse(closeButton().isDisable());
            assertEquals(List.of(), events, "no window closes, nothing opens, the program does not end");

            // the folder becomes a file that is not a database: the next failure has another reason and replaces this one
            Path db = tempDir.resolve("dados.db");
            Files.delete(db);
            Files.write(db, "isto não é um banco de dados do BreakInv".repeat(100).getBytes());
            flow.retry();
            assertEquals("Abrindo os dados…", reasonLine());
            runAttempts();
            runFx();
            assertTrue(reasonLine().startsWith("Motivo: "), reasonLine());
            assertTrue(reasonLine().contains("danificado"), reasonLine());
            assertFalse(reasonLine().contains("inacessível"), reasonLine());
            assertFalse(retryButton().isDisable());
            assertEquals(List.of(), events);
        });
    }

    @Test
    void aSucceedingAttemptClosesTheFailureWindowThenOpensTheMainWindowAndDoesNotExit() throws Exception {
        DataOpening opening = openingThatSucceeds();
        FxTestSupport.runAndWait(() -> {
            build(opening);
            flow.retry();

            runAttempts();
            runFx();

            assertEquals(List.of("closeWindow", "opened"), events);
            assertEquals(1, opened.size());
            assertTrue(opened.get(0).opened());
            opened.get(0).release();
        });
    }

    @Test
    void anErrorOnTheNewAttemptIsLoggedAsSevereAndEndsTheProgramLikeClose() {
        FxTestSupport.runAndWait(() -> {
            class Boom extends Error { }
            build(new DataOpening() {
                @Override Attempt attempt() {
                    throw new Boom();
                }
            });
            try (LogCapture appLog = new LogCapture(App.class)) {
                flow.retry();

                runAttempts();
                runFx();

                var severe = appLog.of(Level.SEVERE, "data opening failed");
                assertEquals(1, severe.size());
                assertInstanceOf(Boom.class, severe.get(0).getThrown());
            }
            assertEquals(List.of("exit"), events);
            assertTrue(opened.isEmpty());
        });
    }

    @Test
    void closeOutsideAnAttemptEndsTheProgramAtOnceWithoutTouchingTheData() {
        FxTestSupport.runAndWait(() -> {
            build(new DataOpening());

            flow.close();

            assertEquals(List.of("closeWindow", "exit"), events);
            assertTrue(attempts.isEmpty());
        });
    }

    @Test
    void closeTwiceEndsTheProgramOnce() {
        FxTestSupport.runAndWait(() -> {
            build(new DataOpening());

            flow.close();
            flow.close();

            assertEquals(List.of("closeWindow", "exit"), events);
        });
    }

    @Test
    void theButtonsOfThePaneAreWiredToTheFlow() throws Exception {
        DataOpening opening = openingThatFails();
        FxTestSupport.runAndWait(() -> {
            build(opening);

            retryButton().fire();
            assertEquals(1, attempts.size());
            runAttempts();
            runFx();
            closeButton().fire();

            assertEquals(List.of("closeWindow", "exit"), events);
        });
    }

    @Test
    void retryAfterCloseStartsNothing() {
        FxTestSupport.runAndWait(() -> {
            build(new DataOpening());
            flow.close();

            flow.retry();

            assertTrue(attempts.isEmpty());
        });
    }

    @Test
    void closeDuringAnAttemptClosesTheWindowNowAndEndsTheProgramOnlyWhenTheAttemptEnds() throws Exception {
        DataOpening opening = openingThatFails();
        FxTestSupport.runAndWait(() -> {
            build(opening);
            flow.retry();

            flow.close();
            assertEquals(List.of("closeWindow"), events, "the window closes at once; the program waits");

            runAttempts();
            runFx();
            assertEquals(List.of("closeWindow", "exit"), events);
            assertTrue(opened.isEmpty());
        });
    }

    @Test
    void closeDuringAnAttemptThatSucceedsLetsGoOfWhatItOpenedAndNeverOpensTheMainWindow() throws Exception {
        DataOpening opening = openingThatSucceeds();
        Path db = tempDir.resolve("dados.db");
        FxTestSupport.runAndWait(() -> {
            build(opening);
            flow.retry();
            flow.close();

            runAttempts();
            runFx();

            assertEquals(List.of("closeWindow", "exit"), events);
            assertTrue(opened.isEmpty(), "no main window, so no daily snapshot and no daily copy either");
        });
        OpeningFixture.assertInstanceLockFree(db);
        assertDoesNotThrow(() -> Files.move(db, tempDir.resolve("movido.db")), "its connection was closed");
    }

    @Test
    void closeAfterTheAttemptEndedButBeforeTheFxAnswerAlsoLetsGoOfEverything() throws Exception {
        DataOpening opening = openingThatSucceeds();
        Path db = tempDir.resolve("dados.db");
        FxTestSupport.runAndWait(() -> {
            build(opening);
            flow.retry();
            runAttempts();
            assertEquals(1, fx.size());

            flow.close();
            runFx();

            assertEquals(List.of("closeWindow", "exit"), events);
            assertTrue(opened.isEmpty());
        });
        OpeningFixture.assertInstanceLockFree(db);
        assertDoesNotThrow(() -> Files.move(db, tempDir.resolve("movido.db")), "its connection was closed");
    }
}

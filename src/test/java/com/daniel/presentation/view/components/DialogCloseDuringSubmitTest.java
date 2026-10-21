package com.daniel.presentation.view.components;

import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import com.daniel.infrastructure.persistence.repository.FlowRepository;
import com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository;
import com.daniel.infrastructure.persistence.repository.SnapshotRepository;
import com.daniel.infrastructure.persistence.repository.TransactionRepository;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.viewmodel.MovementForm;

import javafx.event.Event;
import javafx.scene.control.DialogEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A window-close request (Alt+F4, the OS close button) arriving while a dialog's write is in
 * flight must be refused, and the completion callback ({@code onDone} / {@code onSaved}) must
 * run from the write's own success path, never through the dialog result the close may have
 * discarded. Dialogs are built but never shown; the close request is dispatched through the
 * dialog's normal event chain exactly as JavaFX does before closing.
 */
class DialogCloseDuringSubmitTest {

    @TempDir
    Path tempDir;
    DailyTrackingUseCase daily;
    final Deque<Runnable> worker = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("dialogs.db").toString().replace("\\", "/"));
        Database.open();
        daily = new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(), new SnapshotRepository(),
                new TransactionRepository(), ticker -> null, new DatabaseLedgerAccess());
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    static DialogEvent closeRequest(javafx.scene.control.Dialog<?> d) {
        return new DialogEvent(d, DialogEvent.DIALOG_CLOSE_REQUEST);
    }

    @Test
    void recognitionDialog_closeRequestDuringSubmit_isConsumed_andOnDoneRunsOnceFromSuccess() {
        OpeningRequest proposal = daily.proposeCashOpening("k", LocalDate.now());
        AtomicInteger onDone = new AtomicInteger();
        AtomicInteger hidden = new AtomicInteger();
        AtomicReference<LedgerRecognitionDialog> ref = new AtomicReference<>();
        FxTestSupport.runAndWait(() -> {
            LedgerRecognitionDialog d = LedgerRecognitionDialog.forTest(LedgerRecognitionDialog.Mode.OPENING, null, "Caixa",
                    null, proposal, daily, worker::add, Runnable::run, onDone::incrementAndGet);
            ref.set(d);
            d.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> hidden.incrementAndGet());

            DialogEvent before = closeRequest(d);
            Event.fireEvent(d, before);
            assertFalse(before.isConsumed(), "idle dialog may be closed");

            d.submit();
            assertEquals(1, worker.size(), "write queued");

            DialogEvent during = closeRequest(d);
            Event.fireEvent(d, during);
            assertTrue(during.isConsumed(), "Alt+F4 while the write is in flight must be refused");
            assertEquals(0, onDone.get());

            drain();

            assertEquals(1, onDone.get(), "onDone runs from the success path, not from the dialog result");
            assertEquals(Boolean.TRUE, d.getResult());
            assertTrue(hidden.get() >= 1, "Dialog.close() fires DIALOG_CLOSE_REQUEST itself: the success path must still close");
        });
        assertTrue(daily.cashStatus().opened(), "the opening was committed");
    }

    @Test
    void recognitionDialog_rejectedWrite_keepsDialogAndDoesNotRunOnDone() {
        AtomicInteger onDone = new AtomicInteger();
        FxTestSupport.runAndWait(() -> {
            LedgerRecognitionDialog d = LedgerRecognitionDialog.forTest(LedgerRecognitionDialog.Mode.RECONCILIATION, null, "Caixa",
                    null, OpeningRequest.cash("k2", LocalDate.now(), 0L, false, null), daily, worker::add, Runnable::run, onDone::incrementAndGet);
            d.reasonField().setText("conferido");
            d.submit();                                   // reconciliation without a prior opening: rejected by the domain
            drain();
            assertEquals(0, onDone.get());
            assertNull(d.getResult());
            DialogEvent after = closeRequest(d);
            Event.fireEvent(d, after);
            assertFalse(after.isConsumed(), "after the failure the user may close again");
        });
    }

    @Test
    void movementDialog_closeRequestDuringSubmit_isConsumed_andOnSavedRunsOnce() {
        AtomicInteger onSaved = new AtomicInteger();
        AtomicInteger hidden = new AtomicInteger();
        AtomicReference<Movement> saved = new AtomicReference<>();
        FxTestSupport.runAndWait(() -> {
            MovementForm form = MovementForm.deposit();
            MovementDialog d = new MovementDialog("Aporte", null, form, daily, m -> { onSaved.incrementAndGet(); saved.set(m); },
                    worker::add, Runnable::run);
            d.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> hidden.incrementAndGet());
            d.amountField().setText("100,00");

            DialogEvent before = closeRequest(d);
            Event.fireEvent(d, before);
            assertFalse(before.isConsumed());

            d.submit();
            assertEquals(1, worker.size());
            DialogEvent during = closeRequest(d);
            Event.fireEvent(d, during);
            assertTrue(during.isConsumed(), "Alt+F4 while the write is in flight must be refused");

            drain();

            assertEquals(1, onSaved.get());
            assertNotNull(saved.get());
            assertEquals(10_000L, saved.get().amountCents());
            assertEquals(saved.get(), d.getResult());
            assertTrue(hidden.get() >= 1, "Dialog.close() fires DIALOG_CLOSE_REQUEST itself: the success path must still close");
        });
        assertEquals(10_000L, daily.cashStatus().cashCents());
    }

    /**
     * The OS close (Alt+F4, the window's X) is a WINDOW_CLOSE_REQUEST on the dialog's window, not a
     * DIALOG_CLOSE_REQUEST. JavaFX's dialog answers it by calling close() WITHOUT consuming the window
     * event, and the window's default handler then hides the stage whatever the dialog decided: in the
     * r2 JAR the Aporte vanished during "Gravando…" and with no exit motion. The frame takes the
     * native close over before any window handler and routes it through the dialog's own close —
     * the same path as the header's close button — where the busy veto and the exit motion apply.
     */
    @Test
    void movementDialog_nativeWindowClose_isTheDialogsOwnClose_andNeverHidesTheWindowDuringTheWrite() {
        AtomicInteger onSaved = new AtomicInteger();
        FxTestSupport.runAndWait(() -> {
            MovementDialog d = new MovementDialog("Aporte", null, MovementForm.deposit(), daily, m -> onSaved.incrementAndGet(),
                    worker::add, Runnable::run);
            javafx.stage.Window w = d.getDialogPane().getScene().getWindow();
            assertNotNull(w, "the dialog has its window before it is shown");
            AtomicInteger reachedWindowHandlers = new AtomicInteger();
            AtomicInteger dialogCloses = new AtomicInteger();
            w.addEventHandler(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST, e -> reachedWindowHandlers.incrementAndGet());
            d.addEventFilter(DialogEvent.DIALOG_CLOSE_REQUEST, e -> dialogCloses.incrementAndGet());

            Event.fireEvent(w, new javafx.stage.WindowEvent(w, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            assertEquals(1, dialogCloses.get(), "idle: the native close is the dialog's own close (its exit motion applies)");
            assertEquals(0, reachedWindowHandlers.get(), "the window's default hide must not run behind the dialog's back");

            d.amountField().setText("100,00");
            d.submit();
            assertEquals(1, worker.size(), "write queued");
            Event.fireEvent(w, new javafx.stage.WindowEvent(w, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            assertEquals(1, dialogCloses.get(), "during the write the close is refused before it reaches the dialog");
            assertEquals(0, reachedWindowHandlers.get(), "and the window is not hidden");
            assertEquals(0, onSaved.get());

            drain();
            assertEquals(1, onSaved.get(), "the write completes once, from its own success path");
        });
        assertEquals(10_000L, daily.cashStatus().cashCents());
    }
}

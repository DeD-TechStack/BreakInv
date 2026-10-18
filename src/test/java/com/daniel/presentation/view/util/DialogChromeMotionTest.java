package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.MovementDialog;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.viewmodel.MovementForm;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.event.Event;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DialogChrome — review finding G4.
 *
 * <ul>
 *   <li>The height cap follows the screen the OWNER window is on (not always the primary one);
 *       the choice is a pure function tested here with explicit rectangles — the native owner
 *       itself is wired by {@code DialogChrome.ownerSupplier} and checked by the GUI pass.</li>
 *   <li>The "showing" hook is composable: a caller's own {@code setOnShowing} must not silently
 *       drop the cap.</li>
 *   <li>Dialogs animate through the central Motion on show: cancellable (settle leaves opacity 1),
 *       and nothing moves under "Reduzir animações". The wiring is the real one — a production
 *       dialog ({@link MovementDialog}) is built and its SHOWN/HIDDEN events are dispatched through
 *       the dialog's own chain.</li>
 * </ul>
 */
class DialogChromeMotionTest {

    UiPreferences prefs;

    @BeforeEach
    void setUp() {
        prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
        DialogChrome.ownerSupplier(null);
    }

    static Dialog<Void> dialog() {
        Dialog<Void> d = new Dialog<>();
        d.getDialogPane().getStylesheets().add(DialogChromeMotionTest.class.getResource("/styles/app.css").toExternalForm());
        d.getDialogPane().getButtonTypes().addAll(new ButtonType("Confirmar", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        return d;
    }

    // ── cap by the owner's screen ──────────────────────────────────────────

    @Test
    void usableHeight_picksTheScreenTheOwnerIsOn_notThePrimaryOne() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1032);
        Rectangle2D secondary = new Rectangle2D(1920, 0, 1280, 720);
        List<Rectangle2D> screens = List.of(primary, secondary);
        assertEquals(720, DialogChrome.usableHeight(new Rectangle2D(1940, 20, 655, 637), screens, 999), 1e-9,
                "the app sits on the lower secondary monitor: its height caps the dialog");
        assertEquals(1032, DialogChrome.usableHeight(new Rectangle2D(320, 116, 656, 639), screens, 999), 1e-9);
        assertEquals(1032, DialogChrome.usableHeight(null, screens, 999), 1e-9, "no owner → first (primary) screen");
        assertEquals(999, DialogChrome.usableHeight(null, List.of(), 999), 1e-9, "no screen measurable → fallback");
    }

    // ── composable showing hook ────────────────────────────────────────────

    @Test
    void aCallerSettingItsOwnOnShowing_doesNotLoseTheHeightCap() {
        FxTestSupport.runAndWait(() -> {
            Dialog<Void> d = dialog();
            DialogChrome.frame(d, "Teste", new VBox(new Label("corpo")), () -> true);
            AtomicInteger own = new AtomicInteger();
            d.setOnShowing(e -> own.incrementAndGet()); // a later caller, as the review warned
            assertEquals(-1, d.getDialogPane().getMaxHeight(), 1e-9, "no cap before showing");
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_SHOWING));
            assertEquals(1, own.get(), "the caller's hook ran");
            assertTrue(d.getDialogPane().getMaxHeight() > 0, "and so did the frame's cap: " + d.getDialogPane().getMaxHeight());
        });
    }

    // ── motion ─────────────────────────────────────────────────────────────

    @Test
    void showingPreservesTheFinancialDialogsDeclaredWidth() {
        FxTestSupport.runAndWait(() -> {
            MovementDialog d = new MovementDialog("Aporte", null, MovementForm.deposit(), FxStubs.emptyDaily(), m -> {});
            assertEquals(520, d.getDialogPane().getMinWidth());
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_SHOWING));
            assertTrue(d.getDialogPane().getPrefWidth() >= 520, "declared financial dialog width must not collapse to 440");
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_SHOWING));
            assertTrue(d.getDialogPane().getPrefWidth() >= 520, "repeated fit retains the original width");
        });
    }

    @Test
    void productionDialog_animatesInThroughMotionOnShown_andSettleLeavesItFullyVisible() {
        FxTestSupport.runAndWait(() -> {
            MovementDialog d = new MovementDialog("Aporte externo", null, MovementForm.deposit(), FxStubs.emptyDaily(), m -> {});
            DialogPane pane = d.getDialogPane();
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_SHOWN));
            assertTrue(Motion.isAnimating(pane), "entrance is a Motion animation on the dialog pane");
            assertTrue(pane.getOpacity() < 1.0, "it starts transparent and fades in");
            Motion.settle(pane); // cancelled (e.g. closed at once) → final state, never half-faded
            assertEquals(1.0, pane.getOpacity(), 1e-9);
            assertEquals(0.0, pane.getTranslateY(), 1e-9);
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_HIDDEN));
            assertFalse(Motion.isAnimating(pane));
        });
    }

    @Test
    void withReduceMotion_theDialogAppearsAtOnce() {
        prefs.setReduceMotion(true);
        FxTestSupport.runAndWait(() -> {
            Dialog<ButtonType> d = Dialogs.buildConfirm("Excluir", "Excluir?", "Nada é alterado.", "Excluir");
            DialogPane pane = d.getDialogPane();
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_SHOWN));
            assertFalse(Motion.isAnimating(pane));
            assertEquals(1.0, pane.getOpacity(), 1e-9);
        });
    }

    @Test
    void turningReduceMotionOnMidEntrance_settlesTheDialog() {
        FxTestSupport.runAndWait(() -> {
            Dialog<Void> d = dialog();
            DialogChrome.frame(d, "Teste", new VBox(new Label("corpo")), () -> true);
            Event.fireEvent(d, new DialogEvent(d, DialogEvent.DIALOG_SHOWN));
            assertTrue(Motion.isAnimating(d.getDialogPane()));
            prefs.setReduceMotion(true);
            assertFalse(Motion.isAnimating(d.getDialogPane()));
            assertEquals(1.0, d.getDialogPane().getOpacity(), 1e-9);
        });
    }
}

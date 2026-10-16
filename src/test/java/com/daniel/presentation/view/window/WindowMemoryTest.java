package com.daniel.presentation.view.window;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Remembering the window without a Stage: the store round-trips through a preferences node and
 * the memory debounces writes while the user drags, never confuses the maximized bounds with the
 * normal rectangle and writes immediately when the window closes.
 */
class WindowMemoryTest {

    /** Hand-driven debouncer: the test decides when the delay "elapses". */
    static final class ManualDebouncer implements WindowMemory.Debouncer {
        Runnable pending;
        int scheduled;
        @Override public void schedule(Runnable r) { pending = r; scheduled++; }
        @Override public void cancel() { pending = null; }
        void elapse() { Runnable r = pending; pending = null; if (r != null) r.run(); }
    }

    MemoryPreferences prefs;
    WindowStateStore store;
    ManualDebouncer debouncer;
    WindowMemory memory;

    @BeforeEach
    void setUp() {
        prefs = new MemoryPreferences();
        store = new WindowStateStore(prefs);
        debouncer = new ManualDebouncer();
        memory = new WindowMemory(store, debouncer);
    }

    @Test
    void store_roundTripsNormalRectAndMaximizedFlag() {
        assertTrue(store.load().isEmpty(), "fresh node has nothing");
        store.save(new WindowState(new WindowRect(10, 20, 900, 700), true));
        Optional<WindowState> back = store.load();
        assertTrue(back.isPresent());
        assertEquals(new WindowRect(10, 20, 900, 700), back.get().normal());
        assertTrue(back.get().maximized());
        assertTrue(prefs.flushCount() >= 1, "save flushes so a crash later does not lose it");
    }

    @Test
    void store_withCorruptValues_reportsNothingInsteadOfThrowing() {
        prefs.put("x", "abc");
        prefs.put("w", "900");
        assertTrue(store.load().isEmpty());
    }

    @Test
    void dragging_schedulesOneDeferredWriteAndTheLastBoundsWin() {
        memory.onNormalBoundsChanged(new WindowRect(0, 0, 1000, 700));
        memory.onNormalBoundsChanged(new WindowRect(5, 5, 1000, 700));
        memory.onNormalBoundsChanged(new WindowRect(40, 30, 1010, 710));
        assertTrue(store.load().isEmpty(), "nothing written while the drag is still going");
        assertEquals(3, debouncer.scheduled, "each change re-arms the single pending write");
        debouncer.elapse();
        assertEquals(new WindowRect(40, 30, 1010, 710), store.load().orElseThrow().normal());
        assertFalse(store.load().orElseThrow().maximized());
    }

    @Test
    void maximizing_keepsTheLastNormalRectangle() {
        memory.onNormalBoundsChanged(new WindowRect(40, 30, 1010, 710));
        debouncer.elapse();
        memory.onMaximizedChanged(true);
        debouncer.elapse();
        WindowState s = store.load().orElseThrow();
        assertTrue(s.maximized());
        assertEquals(new WindowRect(40, 30, 1010, 710), s.normal(), "maximized bounds must never replace the normal rect");
        memory.onMaximizedChanged(false);
        debouncer.elapse();
        assertFalse(store.load().orElseThrow().maximized());
        assertEquals(new WindowRect(40, 30, 1010, 710), store.load().orElseThrow().normal());
    }

    @Test
    void closing_writesImmediatelyEvenWithAPendingDebouncedWrite() {
        memory.onNormalBoundsChanged(new WindowRect(1, 2, 900, 600));
        assertNotNull(debouncer.pending);
        memory.onClose();
        assertNull(debouncer.pending, "pending timer cancelled: the close already wrote");
        assertEquals(new WindowRect(1, 2, 900, 600), store.load().orElseThrow().normal());
    }

    @Test
    void restoringFromAnEmptyStore_startsFromTheGivenState() {
        memory = new WindowMemory(store, debouncer);
        memory.onMaximizedChanged(true);
        memory.onClose();
        WindowState s = store.load().orElseThrow();
        assertTrue(s.maximized());
        assertNull(s.normal(), "no normal rect was ever observed, none is invented");
    }

    // ── Native event order observed on Windows (GUI evidence, pilot-v2-680.jar) ──────────
    // Normal stage [320,116,656,639]; native Maximize delivered WM_MOVE (x/y = -8,-8) BEFORE the
    // maximized flag turned true; close; reopen maximized; native Restore landed at [0,0,656,639]:
    // the x/y of the maximize transition had been stored as the normal rectangle (then clamped).

    @Test
    void maximizeWhoseMoveArrivesBeforeTheFlag_doesNotOverwriteTheNormalRect_evenWhenClosedMaximized() {
        memory.onNormalBoundsChanged(new WindowRect(320, 116, 656, 639));
        debouncer.elapse();
        // native maximize: position (and possibly size) change first, the flag comes right after
        memory.onNormalBoundsChanged(new WindowRect(-8, -8, 656, 639));
        memory.onNormalBoundsChanged(new WindowRect(-8, -8, 1936, 1048));
        memory.onMaximizedChanged(true);
        debouncer.elapse();
        assertEquals(new WindowRect(320, 116, 656, 639), store.load().orElseThrow().normal(),
                "bounds that precede the maximized flag belong to the maximize, not to the user");
        assertTrue(store.load().orElseThrow().maximized());
        memory.onClose();
        WindowState s = store.load().orElseThrow();
        assertEquals(new WindowRect(320, 116, 656, 639), s.normal(), "closing maximized keeps the rect to restore to");
        assertTrue(s.maximized());
    }

    @Test
    void reopenedMaximized_thenRestored_goesBackToTheRememberedRectangle() {
        store.save(new WindowState(new WindowRect(320, 116, 656, 639), true));
        memory = new WindowMemory(store, debouncer);
        // the stage shows maximized: bounds of the maximized window may still be reported
        memory.onNormalBoundsChanged(new WindowRect(-8, -8, 1936, 1048));
        debouncer.elapse();
        assertEquals(new WindowRect(320, 116, 656, 639), store.load().orElseThrow().normal());
        memory.onMaximizedChanged(false);
        memory.onNormalBoundsChanged(new WindowRect(320, 116, 656, 639));
        debouncer.elapse();
        WindowState s = store.load().orElseThrow();
        assertFalse(s.maximized());
        assertEquals(new WindowRect(320, 116, 656, 639), s.normal());
    }

    @Test
    void minimizeWhoseMoveArrivesBeforeTheFlag_doesNotStoreTheOffscreenPosition() {
        memory.onNormalBoundsChanged(new WindowRect(320, 116, 656, 639));
        debouncer.elapse();
        memory.onNormalBoundsChanged(new WindowRect(-32000, -32000, 656, 639));
        memory.onIconifiedChanged(true);
        memory.onClose(); // closed from the taskbar while minimized
        WindowState s = store.load().orElseThrow();
        assertEquals(new WindowRect(320, 116, 656, 639), s.normal());
        assertFalse(s.maximized());
    }

    @Test
    void aGenuineMoveThatSettles_isRemembered_andAMoveRightBeforeCloseIsNotLost() {
        memory.onNormalBoundsChanged(new WindowRect(320, 116, 656, 639));
        debouncer.elapse();
        memory.onNormalBoundsChanged(new WindowRect(400, 200, 656, 639));
        debouncer.elapse();
        assertEquals(new WindowRect(400, 200, 656, 639), store.load().orElseThrow().normal(), "a drag that settled is the new normal rect");
        memory.onNormalBoundsChanged(new WindowRect(500, 300, 700, 650));
        memory.onClose();
        assertEquals(new WindowRect(500, 300, 700, 650), store.load().orElseThrow().normal(), "a valid move right before closing is flushed, not dropped");
    }

    @Test
    void aDragNotYetSettledWhenMaximizeArrives_fallsBackToThePreviousSettledRect_neverToTheMaximizeBounds() {
        memory.onNormalBoundsChanged(new WindowRect(320, 116, 656, 639));
        debouncer.elapse();
        memory.onNormalBoundsChanged(new WindowRect(330, 120, 656, 639)); // drag still inside the debounce
        memory.onNormalBoundsChanged(new WindowRect(-8, -8, 1936, 1048));
        memory.onMaximizedChanged(true);
        memory.onClose();
        WindowState s = store.load().orElseThrow();
        assertEquals(new WindowRect(320, 116, 656, 639), s.normal(), "documented limit: the unsettled drag is dropped, not the maximize bounds kept");
        assertTrue(s.maximized());
    }

    @Test
    void boundsReportedWhileMaximizedOrMinimized_areIgnoredEvenWithoutTheStageFilter() {
        memory.onNormalBoundsChanged(new WindowRect(320, 116, 656, 639));
        debouncer.elapse();
        memory.onMaximizedChanged(true);
        memory.onNormalBoundsChanged(new WindowRect(-8, -8, 1936, 1048));
        debouncer.elapse();
        assertEquals(new WindowRect(320, 116, 656, 639), store.load().orElseThrow().normal());
        memory.onMaximizedChanged(false);
        memory.onIconifiedChanged(true);
        memory.onNormalBoundsChanged(new WindowRect(-32000, -32000, 656, 639));
        memory.onIconifiedChanged(false);
        debouncer.elapse();
        assertEquals(new WindowRect(320, 116, 656, 639), store.load().orElseThrow().normal());
    }

    @Test
    void bootstrappingFromAStoredState_keepsItsNormalRectWhenOnlyMaximizedToggles() {
        store.save(new WindowState(new WindowRect(3, 4, 800, 600), false));
        memory = new WindowMemory(store, debouncer);
        memory.onMaximizedChanged(true);
        memory.onClose();
        assertEquals(new WindowRect(3, 4, 800, 600), store.load().orElseThrow().normal());
        assertTrue(store.load().orElseThrow().maximized());
    }
}

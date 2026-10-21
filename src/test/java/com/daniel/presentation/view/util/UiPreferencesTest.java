package com.daniel.presentation.view.util;

import com.daniel.presentation.view.window.MemoryPreferences;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** UI preferences live in their own java.util.prefs node and survive a new instance over the same node. */
class UiPreferencesTest {

    @Test
    void reduceMotion_defaultsToFalse_andPersistsThroughTheNode() {
        MemoryPreferences node = new MemoryPreferences();
        UiPreferences p = new UiPreferences(node);
        assertFalse(p.reduceMotion());
        p.setReduceMotion(true);
        assertTrue(new UiPreferences(node).reduceMotion(), "a fresh reader over the same node sees it");
        assertTrue(node.flushCount() >= 1, "flushed so it survives a crash");
    }

    @Test
    void sidebarChoice_isTriState_nullMeansAutomatic() {
        MemoryPreferences node = new MemoryPreferences();
        UiPreferences p = new UiPreferences(node);
        assertNull(p.sidebarCollapsedChoice());
        p.setSidebarCollapsedChoice(Boolean.TRUE);
        assertEquals(Boolean.TRUE, new UiPreferences(node).sidebarCollapsedChoice());
        p.setSidebarCollapsedChoice(null);
        assertNull(new UiPreferences(node).sidebarCollapsedChoice());
    }

    @Test
    void reduceMotionListeners_areNotifiedOnChange_andCanUnsubscribe() {
        UiPreferences p = new UiPreferences(new MemoryPreferences());
        AtomicInteger seen = new AtomicInteger();
        Runnable unsubscribe = p.onReduceMotionChanged(v -> seen.incrementAndGet());
        p.setReduceMotion(true);
        assertEquals(1, seen.get());
        unsubscribe.run();
        p.setReduceMotion(false);
        assertEquals(1, seen.get());
    }

    @Test
    void windowNode_isAChildOfTheUiNode_notTheLegacyWelcomeNode() {
        MemoryPreferences node = new MemoryPreferences();
        UiPreferences p = new UiPreferences(node);
        assertSame(node, p.windowNode().parent());
        assertEquals("window", p.windowNode().name());
    }

    /** C04: the Carteira detail width chosen on the divider is a UI preference; absent = the page's default. */
    @Test
    void carteiraDetailWidth_isAbsentUntilChosen_thenSurvivesANewInstance() {
        MemoryPreferences node = new MemoryPreferences();
        UiPreferences p = new UiPreferences(node);
        assertTrue(Double.isNaN(p.carteiraDetailWidth()), "never chosen");
        p.setCarteiraDetailWidth(412.5);
        assertEquals(412.5, new UiPreferences(node).carteiraDetailWidth(), 1e-9);
        assertTrue(node.flushCount() >= 1, "flushed so it survives a crash");
        p.setCarteiraDetailWidth(Double.NaN);
        assertTrue(Double.isNaN(new UiPreferences(node).carteiraDetailWidth()), "an unusable width clears the choice");
    }

    @Test
    void defaultNodePath_isDedicatedToBreakInvUi() {
        assertEquals("/com/daniel/breakinv/ui", UiPreferences.NODE_PATH);
    }

    /**
     * App, AppShell and ConfiguracoesPage all resolve {@code forUser()}; listeners are kept per
     * instance, so the process must hand out one shared instance or a toggle in one place never
     * reaches the others. (User root is the synthetic factory under the isolated harness.)
     */
    @Test
    void forUser_isOneSharedInstancePerProcess_soListenersSeeEveryWriter() {
        UiPreferences a = UiPreferences.forUser();
        UiPreferences b = UiPreferences.forUser();
        assertSame(a, b);
        boolean original = a.reduceMotion();
        AtomicInteger seen = new AtomicInteger();
        Runnable unsubscribe = a.onReduceMotionChanged(v -> seen.incrementAndGet());
        try {
            b.setReduceMotion(!original);
            assertEquals(1, seen.get(), "a write through the other reference notifies this one");
        } finally {
            unsubscribe.run();
            a.setReduceMotion(original);
        }
    }
}

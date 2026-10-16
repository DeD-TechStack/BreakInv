package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Motion is the single place animations are started from: every animation is cancellable per
 * node, the last request for a node wins, data is assigned before any animation runs, and the
 * persistent "reduce motion" preference collapses animations to their end state.
 */
class MotionTest {

    UiPreferences prefs;

    @BeforeEach
    void setUp() {
        prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    @Test
    void labelChange_assignsTheNewTextImmediately_notAfterTheFade() {
        FxTestSupport.runAndWait(() -> {
            Label l = new Label("R$ 0,00");
            Motion.animateLabelChange(l, "R$ 950,00");
            assertEquals("R$ 950,00", l.getText(), "financial value must never lag behind the animation");
        });
    }

    @Test
    void labelChange_twoQuickUpdates_theLastValueStays_andOpacitySettlesToOne() {
        FxTestSupport.runAndWait(() -> {
            Label l = new Label("a");
            Motion.animateLabelChange(l, "b");
            Motion.animateLabelChange(l, "c");
            assertEquals("c", l.getText());
            Motion.settle(l);
            assertEquals("c", l.getText(), "an older animation's callback must not restore an older value");
            assertEquals(1.0, l.getOpacity(), 1e-9);
        });
    }

    @Test
    void swap_lastRequestWins_andThePreviousPageNeverReappears() {
        FxTestSupport.runAndWait(() -> {
            StackPane host = new StackPane();
            Region a = new Region(), b = new Region(), c = new Region();
            Motion.swap(host, a, 1);
            Motion.swap(host, b, 1);
            Motion.swap(host, c, -1);
            assertEquals(1, host.getChildren().size(), "no duplicate nodes while transitions overlap");
            assertSame(c, host.getChildren().get(0));
            Motion.settle(host);
            Motion.settle(c);
            assertEquals(1, host.getChildren().size());
            assertSame(c, host.getChildren().get(0));
            assertEquals(1.0, c.getOpacity(), 1e-9, "opacity is never left stuck at zero");
            assertEquals(0.0, c.getTranslateY(), 1e-9);
            assertEquals(1.0, a.getOpacity(), 1e-9, "a cancelled outgoing node is restored, not left half-faded");
        });
    }

    @Test
    void swap_withReduceMotion_showsTheNewNodeInItsFinalStateAtOnce() {
        prefs.setReduceMotion(true);
        FxTestSupport.runAndWait(() -> {
            StackPane host = new StackPane();
            Region a = new Region(), b = new Region();
            Motion.swap(host, a, 1);
            Motion.swap(host, b, 1);
            assertSame(b, host.getChildren().get(0));
            assertEquals(1, host.getChildren().size());
            assertEquals(1.0, b.getOpacity(), 1e-9);
            assertEquals(0.0, b.getTranslateY(), 1e-9);
            assertFalse(Motion.isAnimating(host));
            assertFalse(Motion.isAnimating(b));
        });
    }

    @Test
    void fadeSlideIn_withReduceMotion_leavesNoRunningAnimation_andKeepsFeedbackVisible() {
        prefs.setReduceMotion(true);
        FxTestSupport.runAndWait(() -> {
            Region r = new Region();
            Motion.fadeSlideIn(r, 180, 10);
            assertEquals(1.0, r.getOpacity(), 1e-9);
            assertEquals(0.0, r.getTranslateY(), 1e-9);
            assertFalse(Motion.isAnimating(r));
        });
    }

    @Test
    void dispose_stopsEverythingOnTheNode_andRestoresItsFinalState() {
        FxTestSupport.runAndWait(() -> {
            Region r = new Region();
            Motion.fadeSlideIn(r, 180, 10);
            assertTrue(Motion.isAnimating(r));
            Motion.dispose(r);
            assertFalse(Motion.isAnimating(r));
            assertEquals(1.0, r.getOpacity(), 1e-9);
            assertEquals(0.0, r.getTranslateY(), 1e-9);
        });
    }

    @Test
    void animateWidth_lastTargetWins_andSettleJumpsToIt() {
        FxTestSupport.runAndWait(() -> {
            Region r = new Region();
            r.setPrefWidth(220);
            Motion.animatePrefWidth(r, 56, 200);
            Motion.animatePrefWidth(r, 220, 200);
            Motion.settle(r);
            assertEquals(220, r.getPrefWidth(), 1e-9);
            assertFalse(Motion.isAnimating(r));
        });
    }

    @Test
    void turningReduceMotionOn_settlesAnimationsAlreadyRunning() {
        FxTestSupport.runAndWait(() -> {
            Region page = new Region(), panel = new Region();
            Motion.fadeSlideIn(page, 200, 10);
            Motion.slideInX(panel, 200, 24);
            Region sidebar = new Region();
            sidebar.setPrefWidth(220);
            Motion.animatePrefWidth(sidebar, 56, 200);
            assertTrue(Motion.isAnimating(page) && Motion.isAnimating(panel) && Motion.isAnimating(sidebar));
            prefs.setReduceMotion(true);
            assertFalse(Motion.isAnimating(page), "live toggle stops what is running");
            assertFalse(Motion.isAnimating(panel));
            assertFalse(Motion.isAnimating(sidebar));
            assertEquals(1.0, page.getOpacity(), 1e-9);
            assertEquals(0.0, panel.getTranslateX(), 1e-9);
            assertEquals(56, sidebar.getPrefWidth(), 1e-9, "and leaves each node in its end state");
        });
    }

    @Test
    void reduceMotionPreferenceChange_isObservedWithoutReconfiguring() {
        FxTestSupport.runAndWait(() -> {
            assertFalse(Motion.reduced());
            prefs.setReduceMotion(true);
            assertTrue(Motion.reduced());
            Region r = new Region();
            Motion.fadeSlideIn(r, 180, 10);
            assertFalse(Motion.isAnimating(r));
        });
    }
}

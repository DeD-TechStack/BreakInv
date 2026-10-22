package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E4 (M2): the dim behind a dialog and the toasts move through the central Motion — with
 * "Reduzir animações" they appear and leave at once (the r2 JAR faded the dim for 200/180 ms in
 * both modes), and turning the preference on mid-fade settles them in their final state.
 */
class ToastHostMotionTest {

    UiPreferences prefs;
    StackPane root;

    @BeforeEach
    void setUp() {
        prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
        FxTestSupport.runAndWait(() -> {
            root = new StackPane(new Pane());
            ToastHost.install(root);
        });
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    @Test
    void reducedMotion_dimAndToastAppearAndLeaveAtOnce() {
        FxTestSupport.runAndWait(() -> {
            prefs.setReduceMotion(true); // on the FX thread: settling running animations must not race it
            ToastHost.showDim();
            Node dim = root.lookup(".overlay-dim");
            assertNotNull(dim, "the dim is there");
            assertFalse(Motion.isAnimating(dim), "no fade under reduced motion");
            assertEquals(1.0, dim.getOpacity(), 1e-9, "fully dimmed at once");
            ToastHost.hideDim();
            assertNull(root.lookup(".overlay-dim"), "and gone at once");

            ToastHost.showSuccess("Aporte registrado");
            Node toast = root.lookup(".toast");
            assertNotNull(toast);
            assertFalse(Motion.isAnimating(toast));
            assertEquals(1.0, toast.getOpacity(), 1e-9, "the toast is readable at once");
        });
    }

    @Test
    void normalMotion_dimFadesThroughMotion_andReducingMidFadeSettlesIt() {
        FxTestSupport.runAndWait(() -> {
            ToastHost.showDim();
            Node dim = root.lookup(".overlay-dim");
            assertTrue(Motion.isAnimating(dim), "the dim fades in through Motion");
            ToastHost.hideDim();
            assertTrue(Motion.isAnimating(dim), "and fades out through Motion");
            prefs.setReduceMotion(true); // settles every running animation in its final state
            assertNull(root.lookup(".overlay-dim"), "a settled fade-out leaves no dim behind");

            prefs.setReduceMotion(false);
            ToastHost.showInfo("Cotações atualizadas");
            Node toast = root.lookup(".toast");
            assertTrue(Motion.isAnimating(toast), "the toast fades in through Motion");
            prefs.setReduceMotion(true);
            assertEquals(1.0, toast.getOpacity(), 1e-9, "settled: readable, never half-faded");
        });
    }
}

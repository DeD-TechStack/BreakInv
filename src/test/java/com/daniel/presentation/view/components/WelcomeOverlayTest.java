package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Welcome overlay goes through the central Motion: its entrance/exit are cancellable and follow
 * "Reduzir animações"; Esc closes it like any other overlay; the dismissal callback runs once.
 */
class WelcomeOverlayTest {

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
    void animateIn_withReduceMotion_isImmediate_andEscapeRemovesTheOverlayAtOnce() {
        prefs.setReduceMotion(true);
        FxTestSupport.runAndWait(() -> {
            AtomicInteger create = new AtomicInteger();
            WelcomeOverlay w = new WelcomeOverlay(create::incrementAndGet, () -> {});
            StackPane host = new StackPane(new StackPane(), w.getNode());
            w.animateIn();
            assertEquals(1.0, w.getNode().getOpacity(), 1e-9, "no fade when motion is reduced");
            assertFalse(Motion.isAnimating(w.getNode()));
            Event.fireEvent(w.getNode(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertFalse(host.getChildren().contains(w.getNode()), "Esc dismisses the overlay");
            assertEquals(0, create.get(), "Esc runs no action");
        });
    }

    @Test
    void animateIn_usesMotion_soItCanBeSettled_andTheCtaCallbackRunsOnceAfterDismiss() {
        FxTestSupport.runAndWait(() -> {
            AtomicInteger create = new AtomicInteger();
            WelcomeOverlay w = new WelcomeOverlay(create::incrementAndGet, () -> {});
            StackPane host = new StackPane(new StackPane(), w.getNode());
            w.animateIn();
            assertTrue(Motion.isAnimating(w.getNode()), "entrance is a Motion animation, not a private transition");
            Motion.settle(w.getNode());
            assertEquals(1.0, w.getNode().getOpacity(), 1e-9);
            Button primary = (Button) w.getNode().lookup(".welcome-cta-primary");
            assertNotNull(primary);
            primary.fire();
            Motion.settle(w.getNode()); // exit animation cancelled → final state applied: removed + callback
            assertFalse(host.getChildren().contains(w.getNode()));
            assertEquals(1, create.get(), "callback exactly once");
        });
    }

    @Test
    void featureBullets_useProductVocabulary() {
        FxTestSupport.runAndWait(() -> {
            WelcomeOverlay w = new WelcomeOverlay(() -> {}, () -> {});
            String all = w.getNode().lookupAll(".welcome-feature-text").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText()).reduce("", (a, b) -> a + " | " + b);
            assertFalse(all.contains("KPI"), all);
            assertTrue(all.contains("movimenta"), "features describe the movement history: " + all);
        });
    }
}

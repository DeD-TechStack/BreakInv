package com.daniel.presentation.view;

import com.daniel.presentation.view.components.WelcomeOverlay;
import com.daniel.presentation.view.pages.Page;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Welcome overlay — review finding L4, on the REAL path: {@code AppShell.build()} shows the
 * overlay on first run before the shell is in a Scene. The focus must land inside the overlay
 * once the Scene exists, Esc must close it when fired at the actual focus owner (as the Scene
 * dispatches keys), Tab must stay inside the card, and dismissing must hand the focus back.
 *
 * <p>Keys are fired at {@link Scene#getFocusOwner()}, never at the overlay node directly, so the
 * dispatch chain is the one a real key press takes. Native Tab traversal is not exercised here
 * (it lives in the toolkit's peer path); what is proven is the containment the overlay enforces.
 */
class AppShellWelcomeFocusTest {

    UiPreferences prefs;
    Map<String, Page> pages;
    AppShell shell;
    Parent root;
    Scene scene;
    boolean wasShown;

    @BeforeEach
    void setUp() {
        wasShown = !WelcomeOverlay.shouldShow();
        WelcomeOverlay.resetShown(); // eligible for the automatic first-run path (isolated prefs)
        prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
        pages = new LinkedHashMap<>();
        for (String k : AppShell.PAGE_KEYS) pages.put(k, new AppShellTest.StubPage(k));
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
        if (wasShown) WelcomeOverlay.markShown(); else WelcomeOverlay.resetShown();
    }

    void buildWithScene() {
        FxTestSupport.runAndWait(() -> {
            shell = new AppShell(FxStubs.emptyDaily(), pages, prefs);
            root = shell.build(); // the overlay is added HERE, before any Scene exists
            assertNotNull(overlay(), "first run with an empty portfolio shows the welcome");
            scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((Region) root).resize(1280, 800);
            root.applyCss();
            root.layout();
        });
        FxTestSupport.runAndWait(() -> {}); // drain the deferred focus request
        FxTestSupport.runAndWait(() -> {});
    }

    Node overlay() {
        return ((StackPane) root).getChildren().stream().filter(n -> n.getStyleClass().contains("welcome-overlay")).findFirst().orElse(null);
    }

    static boolean inside(Node n, Node ancestor) {
        for (Node c = n; c != null; c = c.getParent()) if (c == ancestor) return true;
        return false;
    }

    static List<Control> focusables(Parent p) {
        List<Control> out = new ArrayList<>();
        for (Node c : p.getChildrenUnmodifiable()) {
            if (c instanceof Control ctl && ctl.isFocusTraversable() && !ctl.isDisabled() && ctl.isVisible()) out.add(ctl);
            if (c instanceof Parent pp) out.addAll(focusables(pp));
        }
        return out;
    }

    static void key(Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    @Test
    void automaticFirstRun_focusLandsOnThePrimaryCta_onceTheSceneExists() {
        buildWithScene();
        FxTestSupport.runAndWait(() -> {
            Node owner = scene.getFocusOwner();
            assertNotNull(owner, "something has the focus");
            assertTrue(inside(owner, overlay()), "the focus is inside the welcome, not on the navigation behind it: " + owner);
            assertTrue(owner.getStyleClass().contains("welcome-cta-primary"), "the primary action is focused: " + owner.getStyleClass());
        });
    }

    @Test
    void escFiredAtTheFocusOwner_closesTheWelcome_andTheFocusGoesToAVisibleControl() {
        buildWithScene();
        FxTestSupport.runAndWait(() -> {
            Node ov = overlay();
            Node owner = scene.getFocusOwner();
            assertTrue(inside(owner, ov));
            key(owner, KeyCode.ESCAPE, false);
            Motion.settle(ov);
            assertNull(overlay(), "Esc dismissed the welcome");
            Node after = scene.getFocusOwner();
            assertNotNull(after, "the focus is not lost after the welcome goes away");
            assertFalse(inside(after, ov));
            assertTrue(after.getScene() == scene && after.isVisible(), "focus on a visible control of the app: " + after);
        });
    }

    @Test
    void tabIsContainedInsideTheCard_bothDirections_andPullsAStrayFocusBackIn() {
        buildWithScene();
        FxTestSupport.runAndWait(() -> {
            Parent ov = (Parent) overlay();
            List<Control> order = focusables(ov);
            assertTrue(order.size() >= 4, "close, three CTAs and the checkbox: " + order);
            Control first = order.get(0);
            Control last = order.get(order.size() - 1);
            assertTrue(last instanceof CheckBox, "the checkbox closes the card: " + last);

            last.requestFocus();
            key(scene.getFocusOwner(), KeyCode.TAB, false);
            assertSame(first, scene.getFocusOwner(), "Tab from the last control wraps to the first, never to the navigation behind");

            key(scene.getFocusOwner(), KeyCode.TAB, true);
            assertSame(last, scene.getFocusOwner(), "Shift+Tab from the first control wraps to the last");

            Button toggle = (Button) root.lookup(".sidebar-toggle");
            toggle.requestFocus(); // a stray focus behind the overlay (programmatic or native traversal)
            assertTrue(inside(scene.getFocusOwner(), ov), "a focus that escapes the welcome is pulled back in: " + scene.getFocusOwner());
            assertNotSame(toggle, scene.getFocusOwner());
        });
    }

    @Test
    void reopenedFromSettings_withTheSceneActive_restoresThePreviousFocusOnDismiss() {
        WelcomeOverlay.markShown(); // no automatic welcome this time
        FxTestSupport.runAndWait(() -> {
            shell = new AppShell(FxStubs.emptyDaily(), pages, prefs);
            root = shell.build();
            assertNull(overlay());
            scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((Region) root).resize(1280, 800);
            root.applyCss();
            root.layout();
            Button toggle = (Button) root.lookup(".sidebar-toggle");
            toggle.requestFocus();
            WelcomeOverlay.requestShow(); // what Configurações does
            Node ov = overlay();
            assertNotNull(ov);
            Node owner = scene.getFocusOwner();
            assertTrue(inside(owner, ov), "focus moved into the welcome: " + owner);
            key(owner, KeyCode.ESCAPE, false);
            Motion.settle(ov);
            assertNull(overlay());
            assertSame(toggle, scene.getFocusOwner(), "the control the user was on gets the focus back");
        });
    }
}

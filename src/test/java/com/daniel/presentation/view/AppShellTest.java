package com.daniel.presentation.view;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.pages.Page;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shell without a Stage: a Scene gives it a width, the sidebar collapses to a rail by
 * breakpoint or by the user's button, breakpoints follow the usable content width, and fast
 * navigation leaves exactly one page visible. Pages are stubs so no database is touched.
 */
class AppShellTest {

    static final class StubPage implements Page {
        final VBox view = new VBox();
        int shows;
        StubPage(String id) { view.setId(id); }
        @Override public Parent view() { return view; }
        @Override public void onShow() { shows++; }
    }

    UiPreferences prefs;
    Map<String, Page> pages;
    AppShell shell;
    Parent root;
    Scene scene;

    @BeforeEach
    void setUp() {
        prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
        pages = new LinkedHashMap<>();
        for (String k : AppShell.PAGE_KEYS) pages.put(k, new StubPage(k));
        FxTestSupport.runAndWait(() -> {
            shell = new AppShell(stubDaily(), pages, prefs);
            root = shell.build();
            scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((Region) root).resize(1280, 800);
            root.applyCss();
            root.layout();
        });
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    static DailyTrackingUseCase stubDaily() {
        return FxStubs.emptyDaily();
    }

    Region sidebar() { return (Region) root.lookup(".sidebar"); }
    BorderPane frame() { return (BorderPane) root.lookup(".app-root"); }
    StackPane content() { return (StackPane) root.lookup(".content-host"); }
    Button toggle() { return (Button) root.lookup(".sidebar-toggle"); }

    void resize(double w, double h) {
        FxTestSupport.runAndWait(() -> {
            // Scene has no public setter: a fresh scene over the same root is how the test "resizes"
            scene.setRoot(new Region());
            scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ((Region) root).resize(w, h);
            root.applyCss();
            root.layout();
        });
    }

    @Test
    void wideScene_sidebarExpandedAt220_andContentBreakpointIsWide() {
        FxTestSupport.runAndWait(() -> {
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getPrefWidth(), 1e-6);
            assertFalse(sidebar().getStyleClass().contains("rail"));
            assertTrue(frame().getStyleClass().contains(Breakpoint.WIDE.styleClass()), frame().getStyleClass().toString());
            assertEquals(1, content().getChildren().size());
        });
    }

    @Test
    void narrowScene_collapsesToRail56_andContentBreakpointIsNarrow() {
        resize(640, 600);
        FxTestSupport.runAndWait(() -> {
            Motion.settle(sidebar());
            assertEquals(AppShell.RAIL_WIDTH, sidebar().getPrefWidth(), 1e-6);
            assertTrue(sidebar().getStyleClass().contains("rail"));
            assertTrue(frame().getStyleClass().contains(Breakpoint.NARROW.styleClass()), frame().getStyleClass().toString());
            for (String cls : Breakpoint.allStyleClasses()) {
                if (!cls.equals(Breakpoint.NARROW.styleClass())) assertFalse(frame().getStyleClass().contains(cls), cls);
            }
        });
    }

    @Test
    void minimumHeightKeepsThePageOriginInsideTheScene() {
        resize(600, 480);
        FxTestSupport.runAndWait(() -> {
            Motion.settle(sidebar());
            if (sidebar().getStyleClass().contains("rail")) toggle().fire();
            Motion.settle(sidebar());
            for (int pass = 0; pass < 4; pass++) { root.applyCss(); root.layout(); }
            var bounds = frame().localToScene(frame().getLayoutBounds());
            assertTrue(bounds.getMinY() >= 0, "shell must not center an oversized frame above the scene: " + bounds);
            assertTrue(bounds.getMaxY() <= 480, "frame must fit the minimum-height scene: " + bounds);
            assertTrue(content().localToScene(content().getLayoutBounds()).getMinY() >= 0);
        });
    }

    @Test
    void breakpointUsesContentWidth_notSceneWidth() {
        // 960 scene − 220 sidebar = 740 content → MEDIUM, although 960 itself would read as WIDE
        resize(960, 720);
        FxTestSupport.runAndWait(() -> {
            assertTrue(frame().getStyleClass().contains(Breakpoint.MEDIUM.styleClass()), frame().getStyleClass().toString());
            assertEquals(Breakpoint.MEDIUM, shell.currentBreakpoint());
        });
    }

    @Test
    void userToggle_overridesTheAutomaticCollapse_andIsPersisted() {
        FxTestSupport.runAndWait(() -> {
            Button t = toggle();
            assertNotNull(t, "toggle button exists");
            assertFalse(t.getAccessibleText() == null || t.getAccessibleText().isBlank(), "accessible name");
            assertNotNull(t.getTooltip());
            t.fire();
            Motion.settle(sidebar());
            assertEquals(AppShell.RAIL_WIDTH, sidebar().getPrefWidth(), 1e-6);
            assertEquals(Boolean.TRUE, prefs.sidebarCollapsedChoice());
        });
        resize(640, 600);
        FxTestSupport.runAndWait(() -> {
            toggle().fire(); // user expands even in a narrow scene
            Motion.settle(sidebar());
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getPrefWidth(), 1e-6);
            assertEquals(Boolean.FALSE, prefs.sidebarCollapsedChoice());
        });
    }

    @Test
    void userToggle_animatesTheSidebarWidth_insteadOfJumpingToTheTarget() throws Exception {
        FxTestSupport.runAndWait(() -> {
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getWidth(), 1e-6, "starts laid out at 220");
            toggle().fire();
            root.layout();
            assertTrue(Motion.isAnimating(sidebar()), "a width animation is running");
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getWidth(), 1e-6,
                    "right after the click the laid-out width is still 220: min/max must not pin it to the target");
        });
        Thread.sleep(120); // part of the 200 ms transition
        FxTestSupport.runAndWait(() -> {
            root.layout();
            double w = sidebar().getWidth();
            assertTrue(w < AppShell.SIDEBAR_WIDTH && w >= AppShell.RAIL_WIDTH, "in progress or done, never stuck: " + w);
            Motion.settle(sidebar());
            root.layout();
            assertEquals(AppShell.RAIL_WIDTH, sidebar().getWidth(), 1e-6, "settles at the rail width");
            assertEquals(AppShell.RAIL_WIDTH, sidebar().getMaxWidth(), 1e-6, "and stays pinned there once settled");
        });
    }

    @Test
    void reversingTheToggleMidAnimation_continuesFromTheCurrentWidth_insteadOfJumping() throws Exception {
        FxTestSupport.runAndWait(() -> { toggle().fire(); root.layout(); });
        Thread.sleep(90); // part of the 200 ms collapse
        FxTestSupport.runAndWait(() -> {
            root.layout();
            double mid = sidebar().getWidth();
            assertTrue(mid < AppShell.SIDEBAR_WIDTH && mid > AppShell.RAIL_WIDTH, "mid-flight width: " + mid);
            toggle().fire(); // the user reverses while the collapse is still running
            root.layout();
            assertTrue(Motion.isAnimating(sidebar()), "the reversal animates too");
            assertEquals(AppShell.RAIL_WIDTH, sidebar().getMinWidth(), 1e-6, "min/max must span both sizes while the reversal animates");
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getMaxWidth(), 1e-6, "an earlier settle must not leave the old pin in place");
            double now = sidebar().getWidth();
            assertTrue(Math.abs(now - mid) < 30, "no jump: the width continues from " + mid + ", got " + now);
        });
        FxTestSupport.runAndWait(() -> {
            Motion.settle(sidebar());
            root.applyCss();
            root.layout();
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getWidth(), 1e-6);
            assertFalse(sidebar().getStyleClass().contains("rail"));
            assertEquals(Boolean.FALSE, prefs.sidebarCollapsedChoice());
        });
    }

    @Test
    void expanding_keepsTextsAndLogoHiddenUntilTheWidthHasArrived_toggleStaysVisible() {
        FxTestSupport.runAndWait(() -> {
            toggle().fire();
            Motion.settle(sidebar());
            root.applyCss();
            root.layout();
            assertTrue(sidebar().getStyleClass().contains("rail"));
        });
        FxTestSupport.runAndWait(() -> {
            toggle().fire(); // expand with animation
            root.applyCss();
            root.layout();
            assertTrue(Motion.isAnimating(sidebar()));
            javafx.scene.control.Label title = (javafx.scene.control.Label) root.lookup(".sidebar-title");
            assertFalse(title.isVisible(), "the title must wait for room: at ~56 px it would overflow the rail");
            for (Node n : root.lookupAll(".nav-btn")) {
                assertEquals(ContentDisplay.GRAPHIC_ONLY, ((Button) n).getContentDisplay(), n.getId() + " no label fragments while expanding");
            }
            assertTrue(toggle().isVisible() && toggle().isManaged(), "the toggle is always a target");
            Motion.settle(sidebar());
            root.applyCss();
            root.layout();
            assertTrue(title.isVisible() && title.isManaged(), "revealed once the width arrived");
            assertFalse(sidebar().getStyleClass().contains("rail"));
            for (Node n : root.lookupAll(".nav-btn")) {
                assertEquals(ContentDisplay.LEFT, ((Button) n).getContentDisplay(), n.getId());
            }
            assertEquals(AppShell.SIDEBAR_WIDTH, sidebar().getWidth(), 1e-6);
        });
    }

    @Test
    void userToggle_withReduceMotion_jumpsToTheTargetWithoutAnimation() {
        prefs.setReduceMotion(true);
        FxTestSupport.runAndWait(() -> {
            toggle().fire();
            root.layout();
            assertFalse(Motion.isAnimating(sidebar()));
            assertEquals(AppShell.RAIL_WIDTH, sidebar().getWidth(), 1e-6);
        });
    }

    @Test
    void rail_expandButtonIsAVisibleTargetInsideThe56pxRail_noOverflow() {
        resize(640, 600);
        FxTestSupport.runAndWait(() -> {
            Motion.settle(sidebar());
            root.layout();
            Button t = toggle();
            assertTrue(t.isVisible() && t.isManaged());
            double maxX = t.getBoundsInParent().getMaxX() + t.getParent().getLayoutX();
            assertTrue(maxX <= AppShell.RAIL_WIDTH + 0.5, "toggle must not leak out of the rail: maxX=" + maxX);
            assertTrue(t.getBoundsInParent().getMinX() + t.getParent().getLayoutX() >= -0.5, "nor be cut on the left");
            assertTrue(t.getWidth() >= 40 && t.getHeight() >= 32, "expand target at least 40×32, got " + t.getWidth() + "×" + t.getHeight());
            Region brand = (Region) root.lookup(".sidebar-brand");
            assertTrue(brand.prefWidth(-1) <= AppShell.RAIL_WIDTH + 0.5, "brand row fits the rail: " + brand.prefWidth(-1));
        });
    }

    @Test
    void railHidesTextButKeepsEveryNavButtonReachableWithATooltipAndAccessibleName() {
        resize(640, 600);
        FxTestSupport.runAndWait(() -> {
            List<Button> navs = root.lookupAll(".nav-btn").stream().map(n -> (Button) n).toList();
            assertEquals(AppShell.PAGE_KEYS.size(), navs.size(), "one button per page, also on the rail");
            for (Button b : navs) {
                assertTrue(b.isVisible() && b.isManaged(), b.getId());
                assertEquals(ContentDisplay.GRAPHIC_ONLY, b.getContentDisplay(),
                        b.getId() + " must hide label fragments after the production stylesheet is applied");
                assertNotNull(b.getTooltip(), b.getId() + " tooltip");
                assertNotNull(b.getAccessibleText(), b.getId() + " accessible name");
            }
        });
    }

    @Test
    void fastNavigation_lastPageWins_singleChild_opacityOne() {
        FxTestSupport.runAndWait(() -> {
            shell.go("Ranking");
            shell.go("Simulação");
            shell.go("Dashboard");
            shell.go("Configurações");
            assertEquals(1, content().getChildren().size(), "no duplicate nodes");
            Node shown = content().getChildren().get(0);
            assertEquals("Configurações", shown.getId());
            Motion.settle(content());
            Motion.settle(shown);
            assertEquals(1.0, shown.getOpacity(), 1e-9);
            assertEquals(0.0, shown.getTranslateY(), 1e-9);
            assertEquals(1, content().getChildren().size());
            assertSame(shown, content().getChildren().get(0));
            assertEquals(1, ((StubPage) pages.get("Configurações")).shows);
        });
    }

    @Test
    void navigationUpdatesActiveButton_andNotifiesListener() {
        FxTestSupport.runAndWait(() -> {
            StringBuilder seen = new StringBuilder();
            shell.setPageChangeListener(seen::append);
            shell.go("Ranking");
            Button active = root.lookupAll(".nav-btn").stream().map(n -> (Button) n)
                    .filter(b -> b.getStyleClass().contains("active")).findFirst().orElseThrow();
            assertEquals("Ranking", active.getUserData());
            assertEquals("Ranking", seen.toString());
            assertEquals(1, root.lookupAll(".nav-btn.active").size(), "exactly one active item");
        });
    }

    @Test
    void goingToTheSamePageTwice_isANoOpForTheContentButStillRefreshesTheData() {
        FxTestSupport.runAndWait(() -> {
            shell.go("Dashboard");
            Node first = content().getChildren().get(0);
            shell.go("Dashboard");
            assertSame(first, content().getChildren().get(0));
            assertEquals(1, content().getChildren().size());
            assertEquals(3, ((StubPage) pages.get("Dashboard")).shows, "build + 2 explicit go() calls");
        });
    }
}

package com.daniel.presentation.view.window;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Stage-independent half of the window chrome: what the stage should be told to do at
 * startup, given the remembered state and the screens.
 */
class WindowChromeTest {

    static final Rectangle2D PRIMARY = new Rectangle2D(0, 0, 2560, 1392);

    @Test
    void initialPlacement_fromNothing_isTheDefault1280x800CenteredAndNotMaximized() {
        WindowChrome.Placement p = WindowChrome.initialPlacement(null, List.of(PRIMARY));
        assertEquals(1280, p.rect().w());
        assertEquals(800, p.rect().h());
        assertFalse(p.maximized());
    }

    @Test
    void initialPlacement_fromARememberedState_restoresRectThenMaximizedFlag() {
        WindowState saved = new WindowState(new WindowRect(100, 100, 1000, 700), true);
        WindowChrome.Placement p = WindowChrome.initialPlacement(saved, List.of(PRIMARY));
        assertEquals(new WindowRect(100, 100, 1000, 700), p.rect(), "the normal rect is what the stage restores to");
        assertTrue(p.maximized(), "then it is maximized on top of that rect");
    }

    @Test
    void initialPlacement_maximizedWithoutANormalRect_usesTheDefaultSize() {
        WindowState saved = new WindowState(null, true);
        WindowChrome.Placement p = WindowChrome.initialPlacement(saved, List.of(PRIMARY));
        assertEquals(1280, p.rect().w());
        assertTrue(p.maximized());
    }

    @Test
    void minimums_areTheNarrowTarget600x480_sceneUnits() {
        assertEquals(600, WindowChrome.MIN_WIDTH);
        assertEquals(480, WindowChrome.MIN_HEIGHT);
        assertEquals(1280, WindowChrome.DEFAULT_WIDTH);
        assertEquals(800, WindowChrome.DEFAULT_HEIGHT);
    }
}

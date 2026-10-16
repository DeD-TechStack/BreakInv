package com.daniel.presentation.view.window;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure geometry: where a remembered window goes back to, given the monitors that exist now. */
class WindowGeometryTest {

    static final Rectangle2D PRIMARY = new Rectangle2D(0, 0, 2560, 1392);
    static final Rectangle2D SECOND  = new Rectangle2D(588, 1440, 1280, 720);
    static final Rectangle2D LEFT    = new Rectangle2D(-1080, 8, 1080, 1920);
    static final WindowRect FALLBACK = new WindowRect(0, 0, 1280, 800);
    static final double MIN_W = 600, MIN_H = 480;

    static WindowRect fit(WindowRect saved, Rectangle2D... screens) {
        return WindowGeometry.fit(saved, List.of(screens), MIN_W, MIN_H, FALLBACK);
    }

    static void assertInside(WindowRect r, Rectangle2D s) {
        assertTrue(r.x() >= s.getMinX() && r.y() >= s.getMinY(), "origin inside " + s + " but was " + r);
        assertTrue(r.x() + r.w() <= s.getMaxX() + 0.001 && r.y() + r.h() <= s.getMaxY() + 0.001, "extent inside " + s + " but was " + r);
    }

    @Test
    void nothingSaved_centersTheFallbackSizeOnThePrimaryScreen() {
        WindowRect r = fit(null, PRIMARY, SECOND);
        assertEquals(1280, r.w());
        assertEquals(800, r.h());
        assertEquals((2560 - 1280) / 2.0, r.x());
        assertEquals((1392 - 800) / 2.0, r.y());
    }

    @Test
    void nonFiniteValues_areTreatedAsNothingSaved() {
        WindowRect r = fit(new WindowRect(Double.NaN, 10, 900, 700), PRIMARY);
        assertEquals(1280, r.w());
        assertInside(r, PRIMARY);
        r = fit(new WindowRect(10, 10, Double.POSITIVE_INFINITY, 700), PRIMARY);
        assertEquals(1280, r.w());
    }

    @Test
    void savedOnASecondMonitorThatStillExists_staysThere() {
        WindowRect saved = new WindowRect(700, 1500, 1000, 600);
        assertEquals(saved, fit(saved, PRIMARY, SECOND, LEFT));
    }

    @Test
    void savedOnAMonitorThatIsGone_movesToThePrimaryScreenKeepingItsSize() {
        WindowRect saved = new WindowRect(700, 1500, 1000, 600);
        WindowRect r = fit(saved, PRIMARY);
        assertInside(r, PRIMARY);
        assertEquals(1000, r.w());
        assertEquals(600, r.h());
    }

    @Test
    void savedCompletelyOffscreen_isBroughtBackVisible() {
        WindowRect r = fit(new WindowRect(9000, -3000, 1000, 600), PRIMARY, SECOND);
        assertInside(r, PRIMARY);
    }

    @Test
    void savedPartiallyOffTheEdge_isShiftedInsideTheScreenItMostlyOccupies() {
        WindowRect r = fit(new WindowRect(2200, 1200, 1000, 600), PRIMARY);
        assertInside(r, PRIMARY);
        assertEquals(1000, r.w());
        assertEquals(600, r.h());
        assertEquals(2560 - 1000, r.x());
        assertEquals(1392 - 600, r.y());
    }

    @Test
    void smallerThanMinimum_isEnlargedToTheMinimum() {
        WindowRect r = fit(new WindowRect(100, 100, 300, 200), PRIMARY);
        assertEquals(MIN_W, r.w());
        assertEquals(MIN_H, r.h());
        assertInside(r, PRIMARY);
    }

    @Test
    void largerThanTheScreen_isClampedToTheScreen() {
        WindowRect r = fit(new WindowRect(0, 0, 5000, 3000), PRIMARY);
        assertEquals(2560, r.w());
        assertEquals(1392, r.h());
        assertInside(r, PRIMARY);
    }

    @Test
    void noScreensKnown_returnsTheFallbackUntouched() {
        assertEquals(FALLBACK, fit(new WindowRect(10, 10, 900, 700)));
    }

    @Test
    void screenSmallerThanTheMinimum_stillHonoursTheScreenNotTheMinimum() {
        Rectangle2D tiny = new Rectangle2D(0, 0, 500, 400);
        WindowRect r = fit(new WindowRect(0, 0, 900, 700), tiny);
        assertEquals(500, r.w());
        assertEquals(400, r.h());
    }
}

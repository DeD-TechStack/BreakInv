package com.daniel.presentation.view.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Breakpoints are decided by the usable content width, never by the window or scene width. */
class BreakpointTest {

    @Test
    void narrowBelow640_mediumBelow960_wideOtherwise() {
        assertEquals(Breakpoint.NARROW, Breakpoint.of(0));
        assertEquals(Breakpoint.NARROW, Breakpoint.of(584));   // 640 scene − 56 rail
        assertEquals(Breakpoint.NARROW, Breakpoint.of(639.9));
        assertEquals(Breakpoint.MEDIUM, Breakpoint.of(640));
        assertEquals(Breakpoint.MEDIUM, Breakpoint.of(740));   // 960 scene − 220 sidebar
        assertEquals(Breakpoint.MEDIUM, Breakpoint.of(959.9));
        assertEquals(Breakpoint.WIDE,   Breakpoint.of(960));
        assertEquals(Breakpoint.WIDE,   Breakpoint.of(1060));  // 1280 scene − 220 sidebar
    }

    @Test
    void nanOrNegative_isTreatedAsNarrowSoNothingHidesBehindAnUnmeasuredLayout() {
        assertEquals(Breakpoint.NARROW, Breakpoint.of(Double.NaN));
        assertEquals(Breakpoint.NARROW, Breakpoint.of(-10));
    }

    @Test
    void eachBreakpointHasItsOwnStyleClass_andTheyAreAllRemovable() {
        assertEquals("bp-narrow", Breakpoint.NARROW.styleClass());
        assertEquals("bp-medium", Breakpoint.MEDIUM.styleClass());
        assertEquals("bp-wide", Breakpoint.WIDE.styleClass());
        assertEquals(3, Breakpoint.allStyleClasses().size());
    }

    @Test
    void sidebarAutoCollapsesBelow900SceneWidth_unlessTheUserDecidedOtherwise() {
        assertTrue(Breakpoint.sidebarCollapsed(899, null));
        assertFalse(Breakpoint.sidebarCollapsed(900, null));
        assertFalse(Breakpoint.sidebarCollapsed(640, Boolean.FALSE), "user expanded it on purpose");
        assertTrue(Breakpoint.sidebarCollapsed(1280, Boolean.TRUE), "user collapsed it on purpose");
    }
}

package com.daniel.presentation.view.util;

import java.util.List;

/**
 * Layout breakpoints measured on the usable CONTENT width (scene width minus the navigation),
 * not on the window. Pages re-flow their containers on these; CSS only tunes spacing.
 *
 * <ul>
 *   <li>{@link #NARROW}: content &lt; 640 — single column, stat strip 2×2, details below/overlay.</li>
 *   <li>{@link #MEDIUM}: 640–959 — two columns where they fit, details below the table.</li>
 *   <li>{@link #WIDE}: ≥ 960 — side-by-side panels, details beside the table.</li>
 * </ul>
 */
public enum Breakpoint {
    NARROW("bp-narrow"),
    MEDIUM("bp-medium"),
    WIDE("bp-wide");

    public static final double MEDIUM_MIN = 640;
    public static final double WIDE_MIN = 960;
    /** Scene width below which the sidebar collapses to a rail unless the user chose otherwise. */
    public static final double SIDEBAR_AUTO_COLLAPSE_BELOW = 900;

    private final String styleClass;

    Breakpoint(String styleClass) {
        this.styleClass = styleClass;
    }

    public String styleClass() {
        return styleClass;
    }

    public static Breakpoint of(double contentWidth) {
        if (!(contentWidth >= MEDIUM_MIN)) return NARROW; // also catches NaN and negatives
        return contentWidth >= WIDE_MIN ? WIDE : MEDIUM;
    }

    public static List<String> allStyleClasses() {
        return List.of(NARROW.styleClass, MEDIUM.styleClass, WIDE.styleClass);
    }

    /**
     * @param sceneWidth  width of the whole scene
     * @param userChoice  explicit user toggle (true = collapsed, false = expanded), or null for automatic
     */
    public static boolean sidebarCollapsed(double sceneWidth, Boolean userChoice) {
        if (userChoice != null) return userChoice;
        return sceneWidth < SIDEBAR_AUTO_COLLAPSE_BELOW;
    }
}

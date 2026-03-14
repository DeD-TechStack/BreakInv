package com.daniel.presentation.view.util;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Factory for Feather icons styled with design-system CSS classes.
 * Use the named helpers (e.g. {@link #plus()}) whenever possible.
 */
public final class Icons {

    private Icons() {}

    // ── Generic factory ────────────────────────────────────────────────────

    /** Returns a FontIcon with the base .icon class applied. */
    public static FontIcon of(Feather icon) {
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("icon");
        return fi;
    }

    /** Returns a FontIcon with the base .icon class + an extra class. */
    public static FontIcon of(Feather icon, String extraClass) {
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().addAll("icon", extraClass);
        return fi;
    }

    /** Returns a FontIcon with a specific icon size (in px). */
    public static FontIcon of(Feather icon, int size) {
        FontIcon fi = new FontIcon(icon);
        fi.getStyleClass().add("icon");
        fi.setIconSize(size);
        return fi;
    }

    // ── Named helpers — match icon to semantic usage ───────────────────────

    public static FontIcon plus()       { return of(Feather.PLUS); }
    public static FontIcon edit()       { return of(Feather.EDIT_2, "icon-muted"); }
    public static FontIcon trash()      { return of(Feather.TRASH_2, "icon-danger"); }
    public static FontIcon sell()       { return of(Feather.TRENDING_DOWN, "icon-warn"); }
    public static FontIcon search()     { return of(Feather.SEARCH, "icon-muted"); }
    public static FontIcon close()      { return of(Feather.X, "icon-muted"); }
    public static FontIcon filter()     { return of(Feather.SLIDERS, "icon-muted"); }
    public static FontIcon dollar()     { return of(Feather.DOLLAR_SIGN, "icon-accent"); }
    public static FontIcon calendar()   { return of(Feather.CALENDAR, "icon-muted"); }
    public static FontIcon chevronDown(){ return of(Feather.CHEVRON_DOWN, "icon-muted"); }
    public static FontIcon info()       { return of(Feather.INFO, "icon-muted"); }
    public static FontIcon check()      { return of(Feather.CHECK, "icon-accent"); }
    public static FontIcon layers()     { return of(Feather.LAYERS, "icon-muted"); }
    public static FontIcon arrowLeft()  { return of(Feather.ARROW_LEFT, "icon-muted"); }
    public static FontIcon arrowRight() { return of(Feather.ARROW_RIGHT, "icon-muted"); }

    // ── Navigation / sidebar ───────────────────────────────────────────────
    public static FontIcon home()       { return of(Feather.HOME,       "icon-muted"); }
    public static FontIcon settings()   { return of(Feather.SETTINGS,   "icon-muted"); }
    public static FontIcon briefcase()  { return of(Feather.BRIEFCASE,  "icon-muted"); }
    public static FontIcon pieChart()   { return of(Feather.PIE_CHART,  "icon-muted"); }
    public static FontIcon trendingUp() { return of(Feather.TRENDING_UP,"icon-muted"); }
    public static FontIcon fileText()   { return of(Feather.FILE_TEXT,  "icon-muted"); }
    public static FontIcon activity()   { return of(Feather.ACTIVITY,   "icon-muted"); }
    public static FontIcon award()      { return of(Feather.AWARD,      "icon-muted"); }
}

package com.daniel.presentation.view.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Persistent UI preferences (not financial data): reduce motion, sidebar choice, window memory.
 * Stored in a dedicated java.util.prefs node so they never mix with app settings in SQLite nor
 * with the legacy welcome flag. The node is injected: the app passes its user-root node, tests
 * pass an in-memory one.
 */
public final class UiPreferences {

    public static final String NODE_PATH = "/com/daniel/breakinv/ui";
    static final String KEY_REDUCE_MOTION = "reduceMotion";
    static final String KEY_SIDEBAR = "sidebarCollapsed"; // absent = automatic
    static final String KEY_CARTEIRA_DETAIL_WIDTH = "carteiraDetailWidth"; // absent = page default

    private final Preferences node;
    private final List<Consumer<Boolean>> reduceMotionListeners = new CopyOnWriteArrayList<>();

    private static volatile UiPreferences userInstance;

    public UiPreferences(Preferences node) {
        this.node = node;
    }

    /**
     * The application's preferences under the current user root: ONE instance per process.
     * Listeners are kept per instance, so every production caller (App, AppShell, pages) must
     * share it or a change made in one place never reaches the others.
     */
    public static UiPreferences forUser() {
        UiPreferences p = userInstance;
        if (p == null) {
            synchronized (UiPreferences.class) {
                p = userInstance;
                if (p == null) {
                    p = new UiPreferences(Preferences.userRoot().node(NODE_PATH));
                    userInstance = p;
                }
            }
        }
        return p;
    }

    public boolean reduceMotion() {
        return node.getBoolean(KEY_REDUCE_MOTION, false);
    }

    public void setReduceMotion(boolean value) {
        node.putBoolean(KEY_REDUCE_MOTION, value);
        flush();
        for (Consumer<Boolean> l : reduceMotionListeners) l.accept(value);
    }

    /** @return a runnable that unsubscribes */
    public Runnable onReduceMotionChanged(Consumer<Boolean> listener) {
        reduceMotionListeners.add(listener);
        return () -> reduceMotionListeners.remove(listener);
    }

    /** True/false when the user toggled the sidebar themselves; null = follow the breakpoint. */
    public Boolean sidebarCollapsedChoice() {
        String v = node.get(KEY_SIDEBAR, null);
        return v == null ? null : Boolean.parseBoolean(v);
    }

    public void setSidebarCollapsedChoice(Boolean choice) {
        if (choice == null) node.remove(KEY_SIDEBAR);
        else node.put(KEY_SIDEBAR, choice.toString());
        flush();
    }

    /** C04: width the user gave the Carteira detail pane on the divider; NaN while never chosen. */
    public double carteiraDetailWidth() {
        double v = node.getDouble(KEY_CARTEIRA_DETAIL_WIDTH, Double.NaN);
        return Double.isFinite(v) && v > 0 ? v : Double.NaN;
    }

    /** A non-finite or non-positive width clears the choice (the page falls back to its default). */
    public void setCarteiraDetailWidth(double width) {
        if (Double.isFinite(width) && width > 0) node.putDouble(KEY_CARTEIRA_DETAIL_WIDTH, width);
        else node.remove(KEY_CARTEIRA_DETAIL_WIDTH);
        flush();
    }

    /** Child node where the window geometry is remembered. */
    public Preferences windowNode() {
        return node.node("window");
    }

    private void flush() {
        try {
            node.flush();
        } catch (Exception ignored) {
            // backend unavailable: value still applies for this session
        }
    }
}

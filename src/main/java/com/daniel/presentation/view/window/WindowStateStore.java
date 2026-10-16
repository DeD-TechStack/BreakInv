package com.daniel.presentation.view.window;

import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Persists {@link WindowState} in a java.util.prefs node. The node is injected so the app and the
 * tests decide where it lives (the app uses its own node under the user root; tests use memory).
 */
public final class WindowStateStore {

    static final String KEY_X = "x", KEY_Y = "y", KEY_W = "w", KEY_H = "h", KEY_MAX = "maximized";

    private final Preferences node;

    public WindowStateStore(Preferences node) {
        this.node = node;
    }

    public Optional<WindowState> load() {
        try {
            boolean maximized = node.getBoolean(KEY_MAX, false);
            WindowRect rect = null;
            if (node.get(KEY_W, null) != null) {
                double x = parse(node.get(KEY_X, null));
                double y = parse(node.get(KEY_Y, null));
                double w = parse(node.get(KEY_W, null));
                double h = parse(node.get(KEY_H, null));
                rect = new WindowRect(x, y, w, h);
                if (!rect.isFinite()) return Optional.empty();
            } else if (node.get(KEY_MAX, null) == null) {
                return Optional.empty();
            }
            return Optional.of(new WindowState(rect, maximized));
        } catch (RuntimeException corrupt) {
            return Optional.empty();
        }
    }

    public void save(WindowState state) {
        try {
            if (state.normal() != null && state.normal().isFinite()) {
                node.put(KEY_X, Double.toString(state.normal().x()));
                node.put(KEY_Y, Double.toString(state.normal().y()));
                node.put(KEY_W, Double.toString(state.normal().w()));
                node.put(KEY_H, Double.toString(state.normal().h()));
            }
            node.putBoolean(KEY_MAX, state.maximized());
            node.flush();
        } catch (Exception ignored) {
            // preferences backend unavailable: the window simply will not be remembered this time
        }
    }

    private static double parse(String s) {
        if (s == null) return Double.NaN;
        return Double.parseDouble(s);
    }
}

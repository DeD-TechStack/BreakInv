package com.daniel.presentation.view.window;

/** Position and size of the un-maximized window, in logical (scene) units. */
public record WindowRect(double x, double y, double w, double h) {

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(w) && Double.isFinite(h)
                && w > 0 && h > 0;
    }
}

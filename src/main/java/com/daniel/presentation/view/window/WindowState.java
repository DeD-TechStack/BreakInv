package com.daniel.presentation.view.window;

/**
 * What the app remembers about its window: the normal (restored) rectangle and whether it was
 * maximized. The two are kept apart so maximizing never overwrites the rectangle to restore to.
 * {@code normal} may be null when no un-maximized bounds were ever observed.
 */
public record WindowState(WindowRect normal, boolean maximized) {

    public WindowState withNormal(WindowRect rect) {
        return new WindowState(rect, maximized);
    }

    public WindowState withMaximized(boolean max) {
        return new WindowState(normal, max);
    }
}

package com.daniel.presentation.view.window;

/**
 * Keeps the remembered window state current without hammering the preferences backend: bounds
 * changes while the user drags or resizes are coalesced through a {@link Debouncer}; maximize
 * toggles never touch the normal rectangle; closing writes immediately.
 *
 * <p>Native windows report the bounds of a maximize/minimize transition BEFORE the corresponding
 * flag flips (observed on Windows: WM_MOVE to -8,-8 or -32000,-32000, then the flag). So a bounds
 * change is only a <em>candidate</em>: it becomes the normal rectangle when the debounce elapses
 * (or the window closes) with the window still neither maximized nor iconified. A flag that turns
 * on first discards the candidate — those bounds belonged to the transition, not to the user.
 * Limit: a drag that has not settled when a maximize arrives falls back to the previous settled
 * rectangle (never to an invented position).
 *
 * <p>Stage-free on purpose. {@code WindowChrome} wires a real Stage to these callbacks.
 */
public final class WindowMemory {

    /** Delays one runnable; re-scheduling replaces the previous one. */
    public interface Debouncer {
        void schedule(Runnable r);
        void cancel();
    }

    private final WindowStateStore store;
    private final Debouncer debouncer;
    private WindowState state;
    private WindowRect candidate;
    private boolean iconified;
    private boolean dirty;

    public WindowMemory(WindowStateStore store, Debouncer debouncer) {
        this.store = store;
        this.debouncer = debouncer;
        this.state = store.load().orElse(new WindowState(null, false));
    }

    /** Current in-memory state (what the next write will persist). */
    public WindowState state() {
        return state;
    }

    /** Bounds reported by the window; a candidate until the flags prove it is a normal rectangle. */
    public void onNormalBoundsChanged(WindowRect normal) {
        if (normal == null || !normal.isFinite()) return;
        if (state.maximized() || iconified) return;
        candidate = normal;
        debouncer.schedule(this::settle);
    }

    public void onMaximizedChanged(boolean maximized) {
        if (maximized) candidate = null; // the bounds that just arrived were the maximize itself
        state = state.withMaximized(maximized);
        markDirty();
    }

    public void onIconifiedChanged(boolean iconified) {
        this.iconified = iconified;
        if (iconified) candidate = null; // -32000,-32000 is where Windows parks a minimized window
    }

    /** Flush synchronously; the process may be about to exit. */
    public void onClose() {
        debouncer.cancel();
        commitCandidate();
        flush();
    }

    /** Debounce elapsed: the window stayed normal, so the last bounds are the real rectangle. */
    private void settle() {
        commitCandidate();
        flush();
    }

    private void commitCandidate() {
        if (candidate == null) return;
        if (!state.maximized() && !iconified) {
            state = state.withNormal(candidate);
            dirty = true;
        }
        candidate = null;
    }

    private void markDirty() {
        dirty = true;
        debouncer.schedule(this::settle);
    }

    private void flush() {
        if (!dirty) return;
        dirty = false;
        store.save(state);
    }
}

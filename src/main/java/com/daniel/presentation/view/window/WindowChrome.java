package com.daniel.presentation.view.window;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Native (DECORATED) window with memory. Wires a Stage to {@link WindowMemory}:
 * restores the remembered normal rectangle inside the monitors that exist now, re-applies the
 * maximized flag on top of it, debounces writes while the user drags/resizes and flushes on close.
 *
 * <p>Pure decisions live in {@link #initialPlacement} and {@link WindowGeometry} so they can be
 * tested without a Stage; this class only touches JavaFX.
 */
public final class WindowChrome {

    public static final double MIN_WIDTH = 600;
    public static final double MIN_HEIGHT = 480;
    public static final double DEFAULT_WIDTH = 1280;
    public static final double DEFAULT_HEIGHT = 800;
    static final int DEBOUNCE_MS = 400;

    /** What the stage must be told at startup: the normal rect first, then maximized or not. */
    public record Placement(WindowRect rect, boolean maximized) {}

    private WindowChrome() {}

    public static Placement initialPlacement(WindowState saved, List<Rectangle2D> screens) {
        WindowRect fallback = new WindowRect(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        WindowRect rect = WindowGeometry.fit(saved == null ? null : saved.normal(), screens, MIN_WIDTH, MIN_HEIGHT, fallback);
        return new Placement(rect, saved != null && saved.maximized());
    }

    /**
     * Applies the remembered placement to the stage and starts remembering changes.
     * Call before {@code stage.show()}; the scene must already be set so insets are known.
     */
    public static void install(Stage stage, Preferences windowNode) {
        WindowStateStore store = new WindowStateStore(windowNode);
        PauseTransition timer = new PauseTransition(Duration.millis(DEBOUNCE_MS));
        WindowMemory memory = new WindowMemory(store, new WindowMemory.Debouncer() {
            @Override public void schedule(Runnable r) { timer.stop(); timer.setOnFinished(e -> r.run()); timer.playFromStart(); }
            @Override public void cancel() { timer.stop(); }
        });

        List<Rectangle2D> screens = new ArrayList<>();
        Screen primary = Screen.getPrimary();
        if (primary != null) screens.add(primary.getVisualBounds());
        for (Screen s : Screen.getScreens()) {
            if (s != primary) screens.add(s.getVisualBounds());
        }

        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        Placement p = initialPlacement(memory.state().normal() == null && !memory.state().maximized()
                ? null : memory.state(), screens);
        stage.setX(p.rect().x());
        stage.setY(p.rect().y());
        stage.setWidth(p.rect().w());
        stage.setHeight(p.rect().h());
        if (p.maximized()) stage.setMaximized(true);

        // Observe only after the initial placement so the restore itself is not re-saved as a drag.
        // Bounds are only candidates: WindowMemory commits them once the debounce elapses with the
        // window still normal, so the x/y a native maximize/minimize reports BEFORE its flag never
        // becomes the restore rectangle (observed order on Windows: WM_MOVE, then the flag).
        Runnable boundsChanged = () -> {
            if (stage.isMaximized() || stage.isIconified() || stage.isFullScreen()) return;
            memory.onNormalBoundsChanged(new WindowRect(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
        };
        stage.xProperty().addListener((o, a, b) -> boundsChanged.run());
        stage.yProperty().addListener((o, a, b) -> boundsChanged.run());
        stage.widthProperty().addListener((o, a, b) -> boundsChanged.run());
        stage.heightProperty().addListener((o, a, b) -> boundsChanged.run());
        stage.maximizedProperty().addListener((o, was, max) -> {
            memory.onMaximizedChanged(max);
            // after a restore the bounds may have been reported while the flag was still on
            if (!max) Platform.runLater(boundsChanged);
        });
        stage.iconifiedProperty().addListener((o, was, ic) -> memory.onIconifiedChanged(ic));
        stage.setOnHiding(e -> memory.onClose());
    }
}

package com.daniel.presentation.view.util;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Removes the OS titlebar from any Dialog and enables transparent premium card styling.
 * Call {@link #apply(Dialog, Node)} in the dialog constructor, before show().
 */
public final class DialogChrome {

    private DialogChrome() {}

    /**
     * @param dialog     the dialog to decorate
     * @param dragHandle node the user can drag to reposition the dialog window
     */
    public static void apply(Dialog<?> dialog, Node dragHandle) {
        dialog.initStyle(StageStyle.TRANSPARENT);

        // Make the scene background transparent when the dialog opens
        dialog.setOnShowing(e -> {
            Scene sc = dialog.getDialogPane().getScene();
            if (sc != null) sc.setFill(Color.TRANSPARENT);
        });

        // Drag-to-move: capture origin on press, move window on drag
        double[] origin = {0, 0};
        dragHandle.setOnMousePressed(ev -> {
            Scene sc = dialog.getDialogPane().getScene();
            if (sc == null) return;
            Window w = sc.getWindow();
            if (w != null) {
                origin[0] = ev.getScreenX() - w.getX();
                origin[1] = ev.getScreenY() - w.getY();
            }
        });
        dragHandle.setOnMouseDragged(ev -> {
            Scene sc = dialog.getDialogPane().getScene();
            if (sc == null) return;
            Window w = sc.getWindow();
            if (w != null) {
                w.setX(ev.getScreenX() - origin[0]);
                w.setY(ev.getScreenY() - origin[1]);
            }
        });
    }
}

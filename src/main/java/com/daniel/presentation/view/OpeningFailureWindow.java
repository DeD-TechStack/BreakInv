package com.daniel.presentation.view;

import com.daniel.presentation.view.util.UiTheme;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * The window of the failure to open the data (BRK-31 u4c, U4C:RF-02): title "BreakInv", 520 px wide, the height of its
 * text, not resizable, with the theme of the app. Its own window: the primary stage stays for the main window.
 */
public final class OpeningFailureWindow {

    private OpeningFailureWindow() {
    }

    /**
     * Builds the window (not shown). The X of the window calls {@code onCloseRequest} and does not close it by itself:
     * whoever answers decides what ends.
     */
    public static Stage create(OpeningFailurePane pane, Runnable onCloseRequest) {
        UiTheme.install();
        Stage stage = new Stage();
        stage.setTitle("BreakInv");
        Scene scene = new Scene(pane, OpeningFailurePane.WIDTH, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        var css = OpeningFailureWindow.class.getResource("/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        UiTheme.applyFont(scene.getRoot());
        stage.setScene(scene);
        stage.setResizable(false);
        stage.sizeToScene();
        try {
            var icon = OpeningFailureWindow.class.getResourceAsStream("/app/icon.png");
            if (icon != null) stage.getIcons().add(new Image(icon));
        } catch (RuntimeException ignored) {
            // the window works without its icon
        }
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, e -> {
            e.consume();
            onCloseRequest.run();
        });
        return stage;
    }
}

package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

public final class TitleBar extends HBox {

    public TitleBar(Stage stage, VBox windowBody) {
        getStyleClass().add("titlebar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);

        // Drag region fills all available space
        Region dragRegion = new Region();
        HBox.setHgrow(dragRegion, Priority.ALWAYS);

        Button minBtn   = makeWcBtn(Feather.MINUS,      "wc-min");
        Button maxBtn   = makeWcBtn(Feather.MAXIMIZE_2, "wc-max");
        Button closeBtn = makeWcBtn(Feather.X,           "wc-close");

        minBtn.setOnAction(e   -> stage.setIconified(true));
        maxBtn.setOnAction(e   -> stage.setMaximized(!stage.isMaximized()));
        closeBtn.setOnAction(e -> stage.close());

        getChildren().addAll(dragRegion, minBtn, maxBtn, closeBtn);

        // Toggle CSS class for corner-radius reset when maximized
        stage.maximizedProperty().addListener((obs, old, max) -> {
            if (max) windowBody.getStyleClass().add("window-maximized");
            else     windowBody.getStyleClass().remove("window-maximized");
        });

        // Drag-to-move via drag region
        double[] origin = {0, 0};
        dragRegion.setOnMousePressed(e -> {
            origin[0] = e.getScreenX() - stage.getX();
            origin[1] = e.getScreenY() - stage.getY();
        });
        dragRegion.setOnMouseDragged(e -> {
            if (stage.isMaximized()) return;
            stage.setX(e.getScreenX() - origin[0]);
            stage.setY(e.getScreenY() - origin[1]);
        });
        dragRegion.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) stage.setMaximized(!stage.isMaximized());
        });
    }

    private static Button makeWcBtn(Feather icon, String extraClass) {
        Button btn = new Button();
        btn.setGraphic(Icons.of(icon, 13));
        btn.getStyleClass().addAll("wc-btn", extraClass);
        btn.setFocusTraversable(false);
        return btn;
    }

    /** No-op: TitleBar is icons-only. Kept so App.java wiring compiles cleanly. */
    public void setPageTitle(String key) {}
}

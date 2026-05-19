package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

public final class TitleBar extends HBox {

    private final Label pageLabel = new Label();

    public TitleBar(Stage stage, VBox windowBody) {
        getStyleClass().add("titlebar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);

        // Brand identity labels — left side of the drag area
        Label brandLabel = new Label("BreakInv");
        brandLabel.getStyleClass().add("titlebar-brand");

        Label sepLabel = new Label("·");
        sepLabel.getStyleClass().add("titlebar-sep");

        pageLabel.getStyleClass().add("titlebar-page");

        // Spacer pushes window controls to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Title area acts as the drag handle and hosts brand/page labels
        HBox titleArea = new HBox(0, brandLabel, sepLabel, pageLabel, spacer);
        titleArea.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleArea, Priority.ALWAYS);

        Button minBtn   = makeWcBtn(Feather.MINUS,      "wc-min");
        Button maxBtn   = makeWcBtn(Feather.MAXIMIZE_2, "wc-max");
        Button closeBtn = makeWcBtn(Feather.X,           "wc-close");

        minBtn.setOnAction(e   -> stage.setIconified(true));
        maxBtn.setOnAction(e   -> stage.setMaximized(!stage.isMaximized()));
        closeBtn.setOnAction(e -> stage.close());

        getChildren().addAll(titleArea, minBtn, maxBtn, closeBtn);

        // Toggle CSS class for corner-radius reset when maximized
        stage.maximizedProperty().addListener((obs, old, max) -> {
            if (max) windowBody.getStyleClass().add("window-maximized");
            else     windowBody.getStyleClass().remove("window-maximized");
        });

        // Drag-to-move via title area (covers brand text, page label and spacer)
        double[] origin = {0, 0};
        titleArea.setOnMousePressed(e -> {
            origin[0] = e.getScreenX() - stage.getX();
            origin[1] = e.getScreenY() - stage.getY();
        });
        titleArea.setOnMouseDragged(e -> {
            if (stage.isMaximized()) return;
            stage.setX(e.getScreenX() - origin[0]);
            stage.setY(e.getScreenY() - origin[1]);
        });
        titleArea.setOnMouseClicked(e -> {
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

    /** Updates the page name shown in the title bar. Called on every navigation event. */
    public void setPageTitle(String key) {
        pageLabel.setText(key);
    }
}

package com.daniel.main;

import com.daniel.infrastructure.config.AppConfig;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.AppShell;
import com.daniel.presentation.view.components.TitleBar;
import com.daniel.presentation.view.util.WindowResize;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.sql.Connection;

public class App extends Application {

    private static final boolean USE_CUSTOM_CHROME = true;

    private Connection conn;
    private AppConfig appConfig;

    @Override
    public void init() {
        this.appConfig = new AppConfig();
        Database.open();
    }

    @Override
    public void start(Stage stage) {
        conn = Database.open();

        DailyTrackingUseCase dailyTrackingUseCase = appConfig.getDailyTrackingUseCase();
        AppShell shell = new AppShell(dailyTrackingUseCase);

        final Scene scene;

        if (USE_CUSTOM_CHROME) {
            stage.initStyle(StageStyle.TRANSPARENT);

            Parent appRoot = shell.build();

            VBox windowBody = new VBox();
            windowBody.getStyleClass().add("window-body");
            TitleBar titleBar = new TitleBar(stage, windowBody);
            shell.setPageChangeListener(titleBar::setPageTitle);
            titleBar.setPageTitle("Dashboard"); // set initial title before first go()
            VBox.setVgrow(appRoot, Priority.ALWAYS);
            windowBody.getChildren().addAll(titleBar, appRoot);

            StackPane windowShell = new StackPane(windowBody);
            windowShell.getStyleClass().add("window-shell");

            scene = new Scene(windowShell, 1280, 760);
            scene.setFill(Color.TRANSPARENT);

            WindowResize.install(stage, windowShell);
        } else {
            scene = new Scene(shell.build(), 1280, 760);
        }

        var css = getClass().getResource("/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setTitle("Investment Tracker");
        stage.setScene(scene);
        stage.show();

        dailyTrackingUseCase.takeSnapshotIfNeeded(java.time.LocalDate.now());
    }

    @Override
    public void stop() {
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        launch();
    }
}

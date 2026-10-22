package com.daniel.main;

import com.daniel.infrastructure.config.AppConfig;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.AppShell;
import com.daniel.presentation.view.util.DialogChrome;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.WindowChrome;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.sql.Connection;

/**
 * Entry point. The window uses the native (DECORATED) title bar: resizing, snapping, moving and
 * minimize/maximize/close are the operating system's. The remembered size/position is restored
 * inside the monitors that exist now ({@link WindowChrome}); the interior is fully custom.
 */
public class App extends Application {

    private Connection conn;
    private AppConfig appConfig;

    @Override
    public void init() {
        this.appConfig = new AppConfig();
        Database.open();
    }

    @Override
    public void start(Stage stage) {
        com.daniel.presentation.view.util.UiTheme.install();
        conn = Database.open();

        UiPreferences uiPrefs = UiPreferences.forUser();
        Motion.configure(uiPrefs);
        // Dialogs open over this window and are capped by the screen IT is on, not always the primary one.
        DialogChrome.ownerSupplier(() -> stage);

        DailyTrackingUseCase dailyTrackingUseCase = appConfig.getDailyTrackingUseCase();
        AppShell shell = new AppShell(dailyTrackingUseCase, uiPrefs, appConfig.getDataProtection());
        shell.setPageChangeListener(key -> stage.setTitle("BreakInv — " + key));

        stage.initStyle(StageStyle.DECORATED);
        Scene scene = new Scene(shell.build(), WindowChrome.DEFAULT_WIDTH, WindowChrome.DEFAULT_HEIGHT);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        com.daniel.presentation.view.util.UiTheme.applyFont(scene.getRoot());

        stage.setScene(scene);
        try {
            var icon = getClass().getResourceAsStream("/app/icon.png");
            if (icon != null) stage.getIcons().add(new Image(icon));
        } catch (Exception ignored) {}

        WindowChrome.install(stage, uiPrefs.windowNode());
        // BRK-30: closing in the middle of a restore would stop it half-way
        stage.addEventFilter(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST, e -> {
            if (shell.isDataBusy()) {
                e.consume();
                com.daniel.presentation.view.components.ToastHost.showWarn(
                        "Aguarde o fim da restauração para fechar o BreakInv.");
            }
        });
        stage.show();

        UiExecutor.get().execute(
                () -> dailyTrackingUseCase.takeSnapshotIfNeeded(java.time.LocalDate.now()));
        // BRK-30: one verified copy at the first opening of the day, after the day's snapshot
        UiExecutor.get().execute(appConfig::runDailyBackup);
    }

    @Override
    public void stop() {
        UiExecutor.shutdown();
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        if (appConfig != null) appConfig.close();
    }

    public static void main(String[] args) {
        launch();
    }
}

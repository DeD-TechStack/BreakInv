package com.daniel.main;

import com.daniel.infrastructure.config.AppConfig;
import com.daniel.infrastructure.persistence.backup.BackupService;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.AppShell;
import com.daniel.presentation.view.OpeningFailurePane;
import com.daniel.presentation.view.OpeningFailureWindow;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.DailySnapshotNotice;
import com.daniel.presentation.view.util.DialogChrome;
import com.daniel.presentation.view.util.FailureReason;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.WindowChrome;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Entry point. The window uses the native (DECORATED) title bar: resizing, snapping, moving and
 * minimize/maximize/close are the operating system's. The remembered size/position is restored
 * inside the monitors that exist now ({@link WindowChrome}); the interior is fully custom.
 *
 * <p>BRK-31 u4c: when the data cannot be opened the program does not end silently; it shows a window with the reason
 * and "Tentar de novo" ({@link OpeningFailureFlow}), and the main window is built only after an attempt that worked.
 */
public class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    private Connection conn;
    private AppConfig appConfig;
    /** The first attempt, made in {@link #init()}. */
    private DataOpening.Attempt firstAttempt;
    private ExecutorService attemptExecutor;

    @Override
    public void init() {
        // outside the FX thread, where it has always run; a failure is kept for start() to show
        DataOpening.Attempt attempt = new DataOpening().attempt();
        if (attempt.opened()) adopt(attempt);
        else firstAttempt = attempt;
    }

    private void adopt(DataOpening.Attempt attempt) {
        this.appConfig = attempt.config();
        this.conn = attempt.connection();
    }

    @Override
    public void start(Stage stage) {
        com.daniel.presentation.view.util.UiTheme.install();
        if (firstAttempt != null) {
            showOpeningFailure(stage, firstAttempt.failure());
            return;
        }
        startMainWindow(stage);
    }

    /** U4C:RF-01/RF-02: the window with the reason, the data file, the copies folder and the two buttons. */
    private void showOpeningFailure(Stage primary, RuntimeException failure) {
        // while no window is shown (between the failure window closing and the main one opening) the program stays
        Platform.setImplicitExit(false);
        Path dataFile = Database.currentFile().orElse(null);
        Path copiesFolder = dataFile == null ? null : BackupService.defaultFolder(dataFile);
        OpeningFailurePane pane = new OpeningFailurePane(FailureReason.forOpening(failure), dataFile, copiesFolder);
        attemptExecutor = newAttemptExecutor();
        Stage[] window = new Stage[1];
        OpeningFailureFlow[] flow = new OpeningFailureFlow[1];
        window[0] = OpeningFailureWindow.create(pane, () -> flow[0].close());
        flow[0] = new OpeningFailureFlow(new DataOpening(), pane, attemptExecutor, Platform::runLater,
                attempt -> {
                    adopt(attempt);
                    attemptExecutor.shutdown();
                    startMainWindow(primary);
                },
                () -> window[0].close(),
                () -> {
                    attemptExecutor.shutdown();
                    Platform.exit();
                });
        window[0].show();
    }

    /**
     * The executor of the attempt of "Tentar de novo": its own thread, never the {@link UiExecutor} (whose shutdown
     * interrupts) and never a daemon (the program waits for the attempt after the window is closed).
     */
    static ExecutorService newAttemptExecutor() {
        return Executors.newSingleThreadExecutor(r -> new Thread(r, "data-opening"));
    }

    private void startMainWindow(Stage stage) {
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
                ToastHost.showWarn("Aguarde o fim da restauração para fechar o BreakInv.");
            }
        });
        stage.show();
        Platform.setImplicitExit(true);

        startDailyWork(dailyTrackingUseCase::takeSnapshotIfNeeded, appConfig::runDailyBackup,
                UiExecutor.get(), Platform::runLater, ToastHost::showWarn);
    }

    /**
     * After the data opened: today's snapshot and then the daily copy, both on {@code worker}. A failed snapshot is
     * logged (WARNING, class App) and shown as a notice on {@code ui} (U4C:RF-04); the copy is queued anyway.
     */
    static void startDailyWork(Consumer<LocalDate> snapshot, Runnable dailyBackup, Executor worker, Executor ui,
                               Consumer<String> warn) {
        worker.execute(() -> {
            try {
                snapshot.accept(LocalDate.now());
            } catch (RuntimeException failure) {
                DailySnapshotNotice.report(LOG, failure, ui, warn);
            }
        });
        // BRK-30: one verified copy at the first opening of the day, after the day's snapshot
        worker.execute(dailyBackup);
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

package com.daniel.presentation.view;

import com.daniel.presentation.view.pages.DataOperations;
import com.daniel.presentation.view.pages.Page;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-30 wiring: a page that replaces the data (the backup block in Configurações) reaches the
 * shell, which reloads the visible page afterwards and blocks itself while the restore runs.
 */
class AppShellDataOperationsTest {

    static class CountingPage implements Page {
        final VBox view = new VBox();
        int shows;

        @Override
        public Parent view() {
            return view;
        }

        @Override
        public void onShow() {
            shows++;
        }
    }

    static final class OperationsPage extends CountingPage implements DataOperations {
        Runnable onReplaced;
        Consumer<Boolean> onBusy;

        @Override
        public void setOnDataReplaced(Runnable r) {
            onReplaced = r;
        }

        @Override
        public void setOnDataBusy(Consumer<Boolean> c) {
            onBusy = c;
        }
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    @Test
    void restoreReloadsTheVisiblePage_andTheShellIsBlockedWhileItRuns() {
        UiPreferences prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
        Map<String, Page> pages = new LinkedHashMap<>();
        for (String k : AppShell.PAGE_KEYS) pages.put(k, new CountingPage());
        OperationsPage settings = new OperationsPage();
        pages.put("Configurações", settings);
        FxTestSupport.runAndWait(() -> {
            AppShell shell = new AppShell(FxStubs.emptyDaily(), pages, prefs);
            Parent root = shell.build();
            new Scene(root, 1280, 800);
            ((Region) root).resize(1280, 800);
            root.applyCss();
            root.layout();
            assertNotNull(settings.onReplaced, "the shell listens for a restore");
            assertNotNull(settings.onBusy, "and for the time it runs");

            CountingPage dashboard = (CountingPage) pages.get("Dashboard");
            int before = dashboard.shows;
            settings.onReplaced.run();
            assertEquals(before + 1, dashboard.shows, "the visible page reads the replaced data at once");

            BorderPane frame = (BorderPane) root.lookup(".app-root");
            settings.onBusy.accept(true);
            assertTrue(shell.isDataBusy());
            assertTrue(frame.isDisable(), "no navigation or other action while the data are replaced");
            settings.onBusy.accept(false);
            assertFalse(shell.isDataBusy());
            assertFalse(frame.isDisable());
        });
    }
}

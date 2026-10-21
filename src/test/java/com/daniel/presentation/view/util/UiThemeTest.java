package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiThemeTest {
    @Test
    void headingAndAmountUseTheBundledWeightRatherThanSilentlyFallingBackToRegular() {
        FxTestSupport.runAndWait(() -> {
            Label title = new Label("Patrimônio");
            title.getStyleClass().add("page-title");
            var amount = new javafx.scene.control.TextField("1.234,56");
            amount.getStyleClass().add("movement-amount");
            VBox root = new VBox(title, amount);
            root.getStyleClass().add("dark-dialog");
            var scene = new Scene(root, 600, 200);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            var semibold = javafx.scene.text.Font.loadFont(getClass().getResource("/fonts/IBMPlexSans-SemiBold.ttf").toExternalForm(), 26);
            var medium = javafx.scene.text.Font.loadFont(getClass().getResource("/fonts/IBMPlexSans-Medium.ttf").toExternalForm(), 24);
            assertEquals(semibold.getName(), title.getFont().getName(), "heading must resolve the real bundled face; family=" + semibold.getFamily());
            assertEquals(medium.getName(), amount.getFont().getName(), "amount must resolve the real bundled face; family=" + medium.getFamily());
        });
    }

    @Test
    void packagedThemeLoadsOfflineFontsAndPtBrGlyphs() {
        FxTestSupport.runAndWait(() -> {
            for (String weight : new String[]{"Regular", "Medium", "SemiBold"})
                assertNotNull(getClass().getResource("/fonts/IBMPlexSans-" + weight + ".ttf"), "offline font missing: " + weight);
            assertDoesNotThrow(UiTheme::install);
            assertEquals("/atlantafx/base/theme/primer-dark.css", Application.getUserAgentStylesheet());
            Label text = new Label("Ações · patrimônio · R$ 1.234,56 · −12,50%");
            VBox root = new VBox(text);
            var scene = new Scene(root, 600, 100);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            assertEquals("IBM Plex Sans", text.getFont().getFamily());
            var glyph = text.lookup(".text");
            assertNotNull(glyph);
            assertTrue(glyph.getBoundsInLocal().getWidth() > 100);
            assertTrue(text.getWidth() >= text.prefWidth(-1) - 1);
        });
    }
}

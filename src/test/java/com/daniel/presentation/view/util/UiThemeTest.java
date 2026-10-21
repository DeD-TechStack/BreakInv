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

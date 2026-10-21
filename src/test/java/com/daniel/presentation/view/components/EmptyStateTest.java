package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.feather.Feather;

import static org.junit.jupiter.api.Assertions.*;

/** R2-14: a long hint that wraps stays centred under the centred title (it read ragged-left). */
class EmptyStateTest {

    @Test
    void aWrappedHintIsCentredUnderTheTitle() {
        FxTestSupport.runAndWait(() -> {
            VBox state = EmptyState.of(Feather.LIST, "Nenhum lançamento neste período",
                    "Os lançamentos registrados neste mês aparecem aqui, com o filtro escolhido acima.", true);
            var scene = new Scene(new VBox(state), 300, 400);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            state.applyCss();
            state.resize(300, 400);
            state.layout();
            Label title = (Label) state.lookup(".empty-title");
            Label hint = (Label) state.lookup(".empty-hint");
            assertTrue(hint.isWrapText());
            assertEquals(TextAlignment.CENTER, title.getTextAlignment());
            assertEquals(TextAlignment.CENTER, hint.getTextAlignment(), "the hint's lines centre like the title's");
        });
    }
}

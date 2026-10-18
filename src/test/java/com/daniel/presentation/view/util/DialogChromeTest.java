package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared dialog frame: one header (title + close), a body that scrolls on its own so the
 * Confirm/Cancel bar is always reachable in a short window, and a height cap taken from the
 * available screen height — never from the content.
 */
class DialogChromeTest {

    static Dialog<Void> dialog() {
        Dialog<Void> d = new Dialog<>();
        d.getDialogPane().getStylesheets().add(DialogChromeTest.class.getResource("/styles/app.css").toExternalForm());
        d.getDialogPane().getButtonTypes().addAll(new ButtonType("Confirmar", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        return d;
    }

    static VBox tallBody() {
        VBox body = new VBox(8);
        for (int i = 0; i < 20; i++) body.getChildren().addAll(new Label("Campo " + i), new TextField());
        return body;
    }

    @Test
    void frame_buildsHeaderWithTitleAndCloseButton_andAScrollableBody() {
        FxTestSupport.runAndWait(() -> {
            Dialog<Void> d = dialog();
            boolean[] closeAllowed = {true};
            DialogChrome.frame(d, "Aporte externo", tallBody(), () -> closeAllowed[0]);
            Node content = d.getDialogPane().getContent();
            assertNotNull(content);
            Label title = (Label) content.lookup(".dialog-title-label");
            assertNotNull(title, "title in the header");
            assertEquals("Aporte externo", title.getText());
            Button close = (Button) content.lookup(".dialog-close-btn");
            assertNotNull(close, "close button in the header");
            assertNotNull(close.getTooltip());
            assertNotNull(close.getAccessibleText());
            ScrollPane body = (ScrollPane) content.lookup(".dialog-body-scroll");
            assertNotNull(body, "the body scrolls, the button bar does not");
            assertTrue(body.isFitToWidth());
            assertEquals(ScrollPane.ScrollBarPolicy.NEVER, body.getHbarPolicy(), "no horizontal scrolling inside a form");
        });
    }

    @Test
    void capHeight_shortScreen_keepsTheButtonBarInsideTheCap_bodyScrolls() {
        FxTestSupport.runAndWait(() -> {
            Dialog<Void> d = dialog();
            DialogChrome.frame(d, "Teste", tallBody(), () -> true);
            d.getDialogPane().setPrefWidth(520);
            DialogChrome.capHeight(d.getDialogPane(), 480);
            d.getDialogPane().applyCss();
            // the Scene sizes the root to clamp(min, pref, max): that is the height the window gets
            double shown = Math.min(d.getDialogPane().prefHeight(520), d.getDialogPane().maxHeight(520));
            assertTrue(shown <= 480, "window height follows the screen: " + shown);
            d.getDialogPane().resize(520, shown);
            d.getDialogPane().layout();
            Node bar = d.getDialogPane().lookup(".button-bar");
            assertNotNull(bar);
            assertTrue(bar.isVisible());
            assertTrue(bar.getBoundsInParent().getMaxY() <= shown + 0.5, "Confirm/Cancel inside the visible height: " + bar.getBoundsInParent().getMaxY());
            assertTrue(bar.getBoundsInParent().getHeight() >= 30, "button bar not squeezed: " + bar.getBoundsInParent().getHeight());
            ScrollPane body = (ScrollPane) d.getDialogPane().getContent().lookup(".dialog-body-scroll");
            assertTrue(body.getContent().prefHeight(-1) > body.getHeight(), "the tall form scrolls instead of pushing the buttons off-screen");
        });
    }
}

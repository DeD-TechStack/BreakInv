package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialogContrastTest {
    @Test
    void primaryTextHasContrastInEveryActiveStateWithProductionCss() {
        FxTestSupport.runAndWait(() -> {
            Dialog<ButtonType> dialog = Dialogs.buildConfirm("Salvar", null, "Teste", "Salvar");
            StackPane root = new StackPane(dialog.getDialogPane());
            new Scene(root, 480, 320);
            Button button = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().stream()
                    .filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
            for (String state : new String[]{"normal", "hover", "pressed", "focused"}) {
                for (String pseudo : new String[]{"hover", "pressed", "focused"})
                    button.pseudoClassStateChanged(PseudoClass.getPseudoClass(pseudo), state.equals(pseudo));
                root.applyCss(); root.layout();
                Color ink = (Color) button.getTextFill();
                Color background = (Color) button.getBackground().getFills().getLast().getFill();
                double a = luminance(ink), b = luminance(background);
                double ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
                assertTrue(ratio >= 4.5, state + " ink=" + ink + " background=" + background + " contrast=" + ratio);
                if (state.equals("focused")) {
                    assertNotNull(button.getBorder());
                    assertTrue(button.getBorder().getStrokes().stream().anyMatch(s ->
                            s.getTopStroke() instanceof Color c && c.getOpacity() > 0), "visible border focus cue");
                }
            }
        });
    }
    private static double luminance(Color c) {
        return .2126 * linear(c.getRed()) + .7152 * linear(c.getGreen()) + .0722 * linear(c.getBlue());
    }
    private static double linear(double c) { return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4); }
}

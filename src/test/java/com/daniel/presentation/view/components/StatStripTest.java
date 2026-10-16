package com.daniel.presentation.view.components;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Motion;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Money in the stat strip must stay readable with the production stylesheet applied: a value
 * that does not fit its column reflows (fewer columns, wrapped qualifier), it is never cut with
 * an ellipsis. Widths below are the MEDIUM content widths measured in the pilot (Carteira: 640 px
 * of content minus 24 px page padding each side = 592; Dashboard 4 stats at the same width).
 */
class StatStripTest {

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    static VBox layOut(StatStrip strip, double width) {
        VBox root = new VBox(strip);
        root.getStyleClass().add("bp-medium");
        Scene scene = new Scene(root, width, 400);
        scene.getStylesheets().add(StatStripTest.class.getResource("/styles/app.css").toExternalForm());
        root.resize(width, 400);
        root.applyCss();
        root.layout();
        return root;
    }

    /** What the user actually sees: the skin's Text node carries the ellipsized string when cut. */
    static String rendered(Label label) {
        Text t = (Text) label.lookup(".text");
        return t == null ? null : t.getText();
    }

    static int effectiveColumns(StatStrip strip) {
        return ((GridPane) strip.getChildren().get(0)).getColumnConstraints().size();
    }

    static void assertWholeOnOneLine(String l, Label v) {
        String shown = rendered(v);
        assertNotNull(shown, l + " rendered");
        assertFalse(shown.contains("…") || shown.contains("..."), l + " must not be cut: '" + shown + "'");
        assertEquals(v.getText(), shown, l + " shows the whole value");
        assertTrue(v.prefWidth(-1) <= v.getWidth() + 0.5, l + ": the number must fit its line, pref=" + v.prefWidth(-1) + " got=" + v.getWidth());
    }

    @Test
    void carteiraMedium_cashWithQualifier_isFullyVisible_notEllipsized() {
        FxTestSupport.runAndWait(() -> {
            StatStrip strip = new StatStrip("Caixa", "Custo das posições", "Ativos");
            strip.setColumns(3);
            strip.set("Caixa", "R$ 12.345,67", "muted", "a reconhecer");
            strip.set("Custo das posições", "R$ 1.234.567,89");
            strip.set("Ativos", "12");
            layOut(strip, 592);
            for (String l : strip.labels()) assertWholeOnOneLine(l, strip.valueLabel(l));
            Label q = strip.qualifierLabel("Caixa");
            assertTrue(q.isVisible() && q.isManaged(), "qualifier shown as its own line");
            assertEquals("a reconhecer", rendered(q), "qualifier whole, not cut");
            assertFalse(strip.qualifierLabel("Ativos").isVisible(), "no qualifier → no empty line");
        });
    }

    @Test
    void dashboardMedium_fourLargeSignedValues_reflowToTwoColumns_andStayWhole() {
        FxTestSupport.runAndWait(() -> {
            StatStrip strip = new StatStrip("Caixa", "Posições", "Ganho realizado", "Ganho não realizado");
            strip.setColumns(4);
            strip.set("Caixa", "R$ 1.234.567,89");
            strip.set("Posições", "R$ 98.765.432,10");
            strip.set("Ganho realizado", "- R$ 1.234.567,89", "neg");
            strip.set("Ganho não realizado", "+ R$ 12.345.678,90", "pos");
            layOut(strip, 592);
            assertEquals(2, effectiveColumns(strip), "4 cells at 592 px cannot each hold a large BRL value: balanced 2 × 2, not 3 + 1");
            assertEquals(2, strip.effectiveColumns());
            assertEquals(4, strip.columns(), "the page's request is remembered for wider layouts");
            for (String l : strip.labels()) assertWholeOnOneLine(l, strip.valueLabel(l));
        });
    }

    @Test
    void wideStrip_keepsTheRequestedColumns() {
        FxTestSupport.runAndWait(() -> {
            StatStrip strip = new StatStrip("Caixa", "Posições", "Ganho realizado", "Ganho não realizado");
            strip.setColumns(4);
            strip.set("Caixa", "R$ 950,00");
            layOut(strip, 1012);
            assertEquals(4, effectiveColumns(strip));
            assertEquals(4, strip.columns(), "the page's request is kept for the next reflow");
        });
    }
}

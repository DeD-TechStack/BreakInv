package com.daniel.presentation.view.pages;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.util.UiTheme;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1 test helpers: the text a screen shows (labels, tooltips, legends, axis ticks), the five patterns of
 * V1:RF-05.2 that mean "en-US or no thousands separator", reflection into the pages, and the axis table of V1:RF-01.6.
 */
final class Brk12V1Support {

    static final String NBSP = " ";

    /** V1:RF-05.2: a text matching any of these is en-US ({@code 97.7%}, {@code R$ 20.00}, {@code 22,500,000}) or lacks the thousands dot. */
    static final List<Pattern> NOT_PT_BR = List.of(
            Pattern.compile("\\d\\.\\d{1,2}%"),
            Pattern.compile("R\\$[\\s\\u00A0]?-?\\d+\\.\\d{1,2}\\b"),
            Pattern.compile("\\d,\\d{3},\\d{2}"),
            Pattern.compile("\\d{4,},\\d{2}"),
            Pattern.compile("\\d{1,3}(,\\d{3}){2,}"));

    private Brk12V1Support() {}

    // ── what the screen shows ────────────────────────────────────────────────

    /** Every text under {@code root}: labels, cells, legends, chart titles, axis ticks and tooltips; not what is typed in a field. */
    static List<String> texts(Node root) {
        List<String> out = new ArrayList<>();
        collect(root, out, false);
        return out;
    }

    private static void collect(Node node, List<String> out, boolean insideField) {
        boolean field = insideField || node instanceof TextInputControl;
        if (!field) {
            if (node instanceof Labeled labeled && labeled.getText() != null) out.add(labeled.getText());
            if (node instanceof Text text && text.getText() != null) out.add(text.getText());
        }
        if (node instanceof Control control && control.getTooltip() != null && control.getTooltip().getText() != null) {
            out.add(control.getTooltip().getText());
        }
        if (node.getProperties().get("javafx.scene.control.Tooltip") instanceof Tooltip tip && tip.getText() != null) {
            out.add(tip.getText());
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) collect(scroll.getContent(), out, field);
        if (node instanceof SplitPane split && split.getSkin() == null) {
            for (Node item : split.getItems()) collect(item, out, field);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, out, field);
        }
    }

    /** The texts under {@code root} that look en-US or lack the thousands separator (V1:RF-05.2). */
    static List<String> offenders(Node root) {
        List<String> bad = new ArrayList<>();
        for (String text : texts(root)) {
            for (Pattern pattern : NOT_PT_BR) {
                if (pattern.matcher(text).find()) {
                    bad.add(text.replace('\n', '|') + "   <- " + pattern.pattern());
                    break;
                }
            }
        }
        return bad;
    }

    static void assertPtBr(Node root, String where) {
        List<String> offenders = offenders(root);
        assertTrue(offenders.isEmpty(), where + ": " + offenders.size() + " text(s) not in pt-BR: " + offenders);
    }

    /** The fields of a detail pane as label → value text; a value that is not a plain label (a badge) reads "&lt;node&gt;". */
    static Map<String, String> detailFields(DetailsPane details) {
        GridPane grid = (GridPane) details.lookup(".details-fields");
        assertNotNull(grid, "the detail pane has its field grid");
        Map<Integer, String> labels = new TreeMap<>();
        Map<Integer, String> values = new TreeMap<>();
        for (Node n : grid.getChildren()) {
            int row = GridPane.getRowIndex(n) == null ? 0 : GridPane.getRowIndex(n);
            int column = GridPane.getColumnIndex(n) == null ? 0 : GridPane.getColumnIndex(n);
            if (column == 0 && n instanceof Labeled l) labels.put(row, l.getText());
            else if (column == 1) values.put(row, n instanceof Labeled l && n.getStyleClass().contains("details-field-value") ? l.getText() : "<node>");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> label : labels.entrySet()) out.put(label.getValue(), values.get(label.getKey()));
        return out;
    }

    // ── tone ─────────────────────────────────────────────────────────────────

    /** The node carries exactly the tone {@code expected} among pos / neg / muted. */
    static void assertTone(Node node, String expected, String what) {
        List<String> tones = new ArrayList<>();
        for (String tone : List.of("pos", "neg", "muted")) if (node.getStyleClass().contains(tone)) tones.add(tone);
        assertEquals(List.of(expected), tones, what + " (text '" + (node instanceof Labeled l ? l.getText() : "?")
                + "', classes " + node.getStyleClass() + ")");
    }

    // ── reflection ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(owner);
    }

    static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner, value);
    }

    static Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(owner, args);
    }

    // ── scene ────────────────────────────────────────────────────────────────

    /** The page in a scene with the production stylesheet and the font of the app, laid out at {@code width × height}. */
    static Scene styled(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        var css = Brk12V1Support.class.getResource("/styles/app.css");
        assertNotNull(css, "/styles/app.css");
        scene.getStylesheets().add(css.toExternalForm());
        UiTheme.applyFont(scene.getRoot());
        return scene;
    }

    static void settle(Parent root, double width, double height) {
        for (int pass = 0; pass < 6; pass++) {
            ((Region) root).resize(width, height);
            root.applyCss();
            root.layout();
        }
    }

    // ── DESIGN rule 6: no text cut or ellipsized ──────────────────────────────

    /**
     * Every visible text under {@code area} is drawn whole: no ellipsis in the skin's own text ({@link FxTestSupport#cutTexts})
     * and no non-wrapping label narrower than the width its text asks for. The area has CSS and the app font applied.
     */
    static void assertNotCut(Parent area, String what) {
        List<String> problems = new ArrayList<>(FxTestSupport.cutTexts(area));
        collectNarrow(area, problems);
        assertTrue(problems.isEmpty(), what + ": text cut or ellipsized: " + problems);
    }

    private static void collectNarrow(Node node, List<String> problems) {
        if (!node.isVisible()) return;
        if (node instanceof Labeled label && label.getText() != null && !label.getText().isBlank() && !label.isWrapText()
                && label.getContentDisplay() != javafx.scene.control.ContentDisplay.GRAPHIC_ONLY
                && label.getWidth() > 0 && label.getWidth() + 0.5 < label.prefWidth(-1)) {
            problems.add("'" + label.getText() + "' is " + Math.round(label.getWidth()) + " px wide, its text asks for "
                    + Math.round(label.prefWidth(-1)) + " px");
        }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) collectNarrow(child, problems);
    }

    // ── V1:RF-01.6 ───────────────────────────────────────────────────────────

    /** The axis writes its ticks as the table of RF-01.6 says: thousands dot, decimal comma, up to two places, no unit. */
    static void assertAxisTable(NumberAxis axis, String name) {
        StringConverter<Number> formatter = axis.getTickLabelFormatter();
        assertNotNull(formatter, name + ": the axis has no tick label formatter (it falls back to the locale of the JVM)");
        assertEquals("22.500.000", formatter.toString(22_500_000), name);
        assertEquals("1.250", formatter.toString(1250), name);
        assertEquals("3,5", formatter.toString(3.5), name);
        assertEquals("0,25", formatter.toString(0.25), name);
        assertFalse(formatter.toString(1250).contains("R$") || formatter.toString(3.5).contains("%"), name + ": no unit in a tick");
    }
}

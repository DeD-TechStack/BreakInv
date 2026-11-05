package com.daniel.presentation.view;

import com.daniel.presentation.view.util.UiTheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-31 u4c-vis, U4CV:RF-01 to RF-05: the failure pane follows the DESIGN.md (elevated surface, h3 title with the
 * warning icon, groups at 16/24 px, one primary action, nothing cut). The pane is the root of a {@link Scene} with
 * {@code /styles/app.css}, built without any {@code Stage}, in both states (failure and "Abrindo os dados…") and with
 * and without the copies line. Every check fails by assertion: nothing is cast or dereferenced before an
 * {@code assertNotNull} or {@code assertInstanceOf} has said what is missing.
 */
class OpeningFailurePaneStyleTest {

    private static final String REASON = "arquivo de dados inacessível";
    private static final String TITLE = "Não foi possível abrir os dados do BreakInv";
    private static final double TOLERANCE = 0.5;
    private static final int FILE_LENGTH = 180;
    private static final int COPIES_LENGTH = 190;
    private static final int MAX_SEGMENT = 24;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";

    private static final String ID_TITLE = "opening-failure-title";
    private static final String ID_REASON = "opening-failure-reason";
    private static final String ID_FILE = "opening-failure-file";
    private static final String ID_COPIES = "opening-failure-copies";
    private static final String ID_GUIDANCE = "opening-failure-guidance";

    /** One pane in one state, already styled and laid out. */
    private record Case(String name, OpeningFailurePane pane, boolean copies, boolean trying) {
        boolean hasFile() {
            return !name.startsWith("no-file");
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** A path whose {@code toAbsolutePath().toString()} has exactly {@code length} characters (nothing is created). */
    private static Path pathOfLength(int length, int seed) {
        Path path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        int segment = 0;
        while (true) {
            String current = path.toString();
            int separator = current.endsWith(File.separator) ? 0 : 1;
            int remaining = length - current.length() - separator;
            assertTrue(remaining >= 1, "the temporary folder is too long for a path of " + length + " characters: " + current);
            int letters = Math.min(MAX_SEGMENT, remaining);
            if (remaining > MAX_SEGMENT && remaining - MAX_SEGMENT < 2) letters = MAX_SEGMENT - 2;
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < letters; i++) name.append(LETTERS.charAt((seed + segment * 7 + i * 3) % LETTERS.length()));
            path = path.resolve(name.toString());
            segment++;
            if (path.toAbsolutePath().toString().length() == length) break;
        }
        Path result = path.toAbsolutePath();
        assertEquals(length, result.toString().length());
        return result;
    }

    private static Scene sceneOf(Parent root) {
        Scene scene = new Scene(root, OpeningFailurePane.WIDTH, Region.USE_COMPUTED_SIZE);
        var css = OpeningFailurePaneStyleTest.class.getResource("/styles/app.css");
        assertNotNull(css, "/styles/app.css");
        scene.getStylesheets().add(css.toExternalForm());
        return scene;
    }

    /** Applies the CSS and lays the pane out at its width and at the height it prefers (no window does it here). */
    private static void settle(OpeningFailurePane pane) {
        for (int pass = 0; pass < 3; pass++) {
            pane.applyCss();
            pane.resize(OpeningFailurePane.WIDTH, pane.prefHeight(OpeningFailurePane.WIDTH));
            pane.layout();
        }
    }

    private static Case build(String name, Path file, Path copies, boolean trying) {
        OpeningFailurePane pane = new OpeningFailurePane(REASON, file, copies);
        assertEquals("", pane.getStyle(), name + ": the pane sets no inline style itself");
        for (Node node : allNodes(pane)) {
            assertEquals("", node.getStyle(), name + ": no inline style on " + node.getClass().getSimpleName() + " " + node.getId());
        }
        Scene scene = sceneOf(pane);
        UiTheme.applyFont(scene.getRoot());
        if (trying) pane.showTrying();
        settle(pane);
        return new Case(name, pane, copies != null, trying);
    }

    private static List<Case> cases() {
        Path file = pathOfLength(FILE_LENGTH, 3);
        Path copies = pathOfLength(COPIES_LENGTH, 11);
        List<Case> cases = new ArrayList<>();
        cases.add(build("failure-with-copies", file, copies, false));
        cases.add(build("trying-with-copies", file, copies, true));
        cases.add(build("failure-without-copies", file, null, false));
        cases.add(build("trying-without-copies", file, null, true));
        cases.add(build("no-file-failure", null, null, false));
        cases.add(build("no-file-trying", null, null, true));
        return cases;
    }

    // ── tree helpers (assert before cast or access) ────────────────────────

    private static List<Node> allNodes(Node root) {
        List<Node> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, into);
        }
    }

    private static Label labelById(Case c, String id) {
        Node node = c.pane().lookup("#" + id);
        assertNotNull(node, c.name() + ": #" + id);
        return assertInstanceOf(Label.class, node, c.name() + ": #" + id);
    }

    private static Button buttonById(Case c, String id) {
        Node node = c.pane().lookup("#" + id);
        assertNotNull(node, c.name() + ": #" + id);
        return assertInstanceOf(Button.class, node, c.name() + ": #" + id);
    }

    /** The label ids the data group must hold, in order. */
    private static List<String> dataIds(Case c) {
        List<String> ids = new ArrayList<>(List.of(ID_REASON, ID_FILE));
        if (c.copies()) ids.add(ID_COPIES);
        return ids;
    }

    /** The four direct children of the pane (U4CV Termos), after saying what is wrong when they are not four. */
    private static List<Node> directChildren(Case c) {
        List<Node> children = c.pane().getChildren();
        assertEquals(4, children.size(), c.name() + ": direct children of the pane: " + describe(children));
        return children;
    }

    private static String describe(List<Node> nodes) {
        List<String> names = new ArrayList<>();
        for (Node node : nodes) names.add(node.getClass().getSimpleName() + (node.getId() == null ? "" : "#" + node.getId()));
        return names.toString();
    }

    private static HBox titleRow(Case c) {
        Node node = directChildren(c).get(0);
        return assertInstanceOf(HBox.class, node, c.name() + ": first child is the title row");
    }

    private static VBox dataGroup(Case c) {
        Node node = directChildren(c).get(1);
        return assertInstanceOf(VBox.class, node, c.name() + ": second child is the data group");
    }

    private static Label guidanceLabel(Case c) {
        Node node = directChildren(c).get(2);
        return assertInstanceOf(Label.class, node, c.name() + ": third child is the guidance label");
    }

    private static HBox buttonBar(Case c) {
        Node node = directChildren(c).get(3);
        return assertInstanceOf(HBox.class, node, c.name() + ": fourth child is the button bar");
    }

    private static FontIcon iconOf(Case c) {
        Label title = labelById(c, ID_TITLE);
        Node graphic = title.getGraphic();
        assertNotNull(graphic, c.name() + ": the title has a graphic (the alert icon)");
        return assertInstanceOf(FontIcon.class, graphic, c.name() + ": the graphic of the title");
    }

    private static double gap(Node above, Node below) {
        return below.getBoundsInParent().getMinY() - above.getBoundsInParent().getMaxY();
    }

    // ── resolving the design tokens through the CSS (never a literal hex) ──

    /** The color a looked-up color of {@code app.css} resolves to, read from a probe root in its own Scene. */
    private static Color token(String lookedUp) {
        Region probe = new Region();
        probe.setStyle("-fx-background-color: " + lookedUp + ";");
        sceneOf(probe);
        probe.applyCss();
        Background background = probe.getBackground();
        assertNotNull(background, "the token " + lookedUp + " resolves to a background");
        assertFalse(background.getFills().isEmpty(), "the token " + lookedUp + " resolves to a fill");
        Paint paint = background.getFills().get(0).getFill();
        return assertInstanceOf(Color.class, paint, "the token " + lookedUp);
    }

    private static void assertTextFill(Case c, String id, String lookedUp) {
        Label label = labelById(c, id);
        assertEquals(token(lookedUp), label.getTextFill(), c.name() + ": text color of #" + id + " is " + lookedUp);
    }

    // ── U4CV:RF-01 ─────────────────────────────────────────────────────────

    @Test
    void thePaneHasItsOwnClassAndNoLongerThePageRootClass() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                List<String> classes = c.pane().getStyleClass();
                assertTrue(classes.contains("opening-failure-pane"), c.name() + ": style classes " + classes);
                assertFalse(classes.contains("page-root"), c.name() + ": style classes " + classes);
            }
        });
    }

    @Test
    void theBackgroundIsTheElevatedSurfaceWithoutRadiusBorderOrShadow() {
        FxTestSupport.runAndWait(() -> {
            Color surface2 = token("-color-surface-2");
            for (Case c : cases()) {
                Background background = c.pane().getBackground();
                assertNotNull(background, c.name() + ": background");
                assertEquals(1, background.getFills().size(), c.name() + ": one background fill");
                BackgroundFill fill = background.getFills().get(0);
                assertEquals(surface2, fill.getFill(), c.name() + ": the background is -color-surface-2");
                assertEquals(CornerRadii.EMPTY, fill.getRadii(), c.name() + ": radius 0");
                assertEquals(Insets.EMPTY, fill.getInsets(), c.name() + ": the fill covers the whole pane");
                Border border = c.pane().getBorder();
                assertTrue(border == null || border.isEmpty(), c.name() + ": no border, found " + border);
                assertNull(c.pane().getEffect(), c.name() + ": no shadow");
            }
        });
    }

    @Test
    void thePaddingIs24OnEverySide() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                Insets padding = c.pane().getPadding();
                assertNotNull(padding, c.name() + ": padding");
                assertEquals(24, padding.getTop(), TOLERANCE, c.name() + ": top");
                assertEquals(24, padding.getRight(), TOLERANCE, c.name() + ": right");
                assertEquals(24, padding.getBottom(), TOLERANCE, c.name() + ": bottom");
                assertEquals(24, padding.getLeft(), TOLERANCE, c.name() + ": left");
            }
        });
    }

    @Test
    void theTitleIsH3WithoutTheOldClassesAndItsColorIsText1() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                Label title = labelById(c, ID_TITLE);
                List<String> classes = title.getStyleClass();
                assertTrue(classes.contains("h3"), c.name() + ": title classes " + classes);
                assertFalse(classes.contains("text-lg"), c.name() + ": title classes " + classes);
                assertFalse(classes.contains("text-strong"), c.name() + ": title classes " + classes);
                assertTrue(classes.contains("label"), c.name() + ": title classes " + classes);
                assertEquals(TITLE, title.getText(), c.name() + ": the title text does not change");
                assertTextFill(c, ID_TITLE, "-color-text-1");
            }
        });
    }

    @Test
    void theReasonIsText1EvenWhileTryingAndTheOtherLinesAreText2() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                assertTextFill(c, ID_REASON, "-color-text-1");
                assertTextFill(c, ID_FILE, "-color-text-2");
                if (c.copies()) assertTextFill(c, ID_COPIES, "-color-text-2");
                assertTextFill(c, ID_GUIDANCE, "-color-text-2");
            }
        });
    }

    @Test
    void theLinesExceptTheTitleAre14Px() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                for (String id : List.of(ID_REASON, ID_FILE, ID_GUIDANCE)) {
                    assertEquals(14, labelById(c, id).getFont().getSize(), TOLERANCE, c.name() + ": font size of #" + id);
                }
                if (c.copies()) assertEquals(14, labelById(c, ID_COPIES).getFont().getSize(), TOLERANCE, c.name() + ": font size of #" + ID_COPIES);
            }
        });
    }

    // ── U4CV:RF-02 ─────────────────────────────────────────────────────────

    @Test
    void theDirectChildrenAreTheTitleRowTheDataGroupTheGuidanceAndTheButtonBar() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                HBox row = titleRow(c);
                assertEquals(1, row.getChildren().size(), c.name() + ": the title row holds only the title: " + describe(row.getChildren()));
                assertSame(labelById(c, ID_TITLE), row.getChildren().get(0), c.name() + ": the title row holds the title label");

                VBox group = dataGroup(c);
                List<String> ids = new ArrayList<>();
                for (Node node : group.getChildren()) ids.add(node.getId());
                assertEquals(dataIds(c), ids, c.name() + ": the data group holds the reason, the file and the copies, in this order");

                assertSame(labelById(c, ID_GUIDANCE), guidanceLabel(c), c.name() + ": the guidance is the third child");
                buttonBar(c);
            }
        });
    }

    @Test
    void theGroupsAreSeparatedBy16And16And24AndTheLinesOfTheDataGroupBy8() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                List<Node> children = directChildren(c);
                assertEquals(16, gap(children.get(0), children.get(1)), TOLERANCE, c.name() + ": title row -> data group");
                assertEquals(16, gap(children.get(1), children.get(2)), TOLERANCE, c.name() + ": data group -> guidance");
                assertEquals(24, gap(children.get(2), children.get(3)), TOLERANCE, c.name() + ": guidance -> button bar");

                List<Node> lines = dataGroup(c).getChildren();
                assertEquals(dataIds(c).size(), lines.size(), c.name() + ": lines of the data group: " + describe(lines));
                for (int i = 0; i + 1 < lines.size(); i++) {
                    assertEquals(8, gap(lines.get(i), lines.get(i + 1)), TOLERANCE, c.name() + ": data line " + i + " -> " + (i + 1));
                }
            }
        });
    }

    // ── U4CV:RF-03 ─────────────────────────────────────────────────────────

    @Test
    void theTitleCarriesTheWarningAlertTriangleOnTheLeftAt15Px() {
        FxTestSupport.runAndWait(() -> {
            Color warning = token("-color-warning");
            for (Case c : cases()) {
                Label title = labelById(c, ID_TITLE);
                FontIcon icon = iconOf(c);
                assertEquals(Feather.ALERT_TRIANGLE, icon.getIconCode(), c.name() + ": Feather ALERT_TRIANGLE");
                List<String> classes = icon.getStyleClass();
                assertTrue(classes.contains("icon-warn"), c.name() + ": icon classes " + classes);
                assertTrue(classes.contains("opening-failure-icon"), c.name() + ": icon classes " + classes);
                assertEquals(15, icon.getIconSize(), c.name() + ": icon size (beats the 14 px of .icon-warn)");
                assertEquals(warning, icon.getIconColor(), c.name() + ": icon color is -color-warning");
                assertEquals(8, title.getGraphicTextGap(), TOLERANCE, c.name() + ": graphicTextGap");
                assertEquals(ContentDisplay.LEFT, title.getContentDisplay(), c.name() + ": the icon is left of the text");
                assertNull(icon.getAccessibleText(), c.name() + ": the icon has no accessibleText");
                assertEquals(TITLE, title.getText(), c.name() + ": the title text does not change");
                assertEquals(ID_TITLE, title.getId());

                Text text = null;
                for (Node child : title.getChildrenUnmodifiable()) {
                    if (child instanceof Text t && t.getStyleClass().contains("text")) text = t;
                }
                assertNotNull(text, c.name() + ": the text the title draws");
                double iconRight = icon.localToScene(icon.getBoundsInLocal()).getMaxX();
                double textLeft = text.localToScene(text.getBoundsInLocal()).getMinX();
                assertTrue(iconRight <= textLeft + TOLERANCE, c.name() + ": icon ends at " + iconRight + ", text starts at " + textLeft);
            }
        });
    }

    // ── U4CV:RF-04 ─────────────────────────────────────────────────────────

    @Test
    void onlyRetryIsThePrimaryActionAndItKeepsDefaultAndCancelRoles() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                Button retry = buttonById(c, "opening-failure-retry");
                Button close = buttonById(c, "opening-failure-close");
                List<Node> primaries = new ArrayList<>();
                for (Node node : allNodes(c.pane())) {
                    if (node.getStyleClass().contains("primary-btn")) primaries.add(node);
                }
                assertEquals(1, primaries.size(), c.name() + ": nodes with primary-btn: " + describe(primaries));
                assertSame(retry, primaries.get(0), c.name() + ": the only primary-btn is \"Tentar de novo\"");
                assertEquals("Tentar de novo", retry.getText());
                assertTrue(retry.isDefaultButton(), c.name() + ": retry is the default button");
                assertFalse(retry.isCancelButton(), c.name() + ": retry is not the cancel button");

                assertEquals("Fechar", close.getText());
                assertTrue(close.getStyleClass().contains("ghost-btn"), c.name() + ": close classes " + close.getStyleClass());
                assertFalse(close.getStyleClass().contains("primary-btn"), c.name() + ": close classes " + close.getStyleClass());
                assertTrue(close.isCancelButton(), c.name() + ": close is the cancel button");
                assertFalse(close.isDefaultButton(), c.name() + ": close is not the default button");

                assertEquals(c.trying(), retry.isDisable(), c.name() + ": retry is disabled only while trying");
                assertFalse(close.isDisable(), c.name() + ": close is always active");
            }
        });
    }

    @Test
    void theButtonBarKeepsTheOrderTheSpacingAndTheAlignment() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                HBox bar = buttonBar(c);
                assertEquals(2, bar.getChildren().size(), c.name() + ": buttons: " + describe(bar.getChildren()));
                assertSame(buttonById(c, "opening-failure-retry"), bar.getChildren().get(0), c.name() + ": retry is first");
                assertSame(buttonById(c, "opening-failure-close"), bar.getChildren().get(1), c.name() + ": close is second");
                assertEquals(8, bar.getSpacing(), TOLERANCE, c.name() + ": spacing");
                assertEquals(Pos.CENTER_RIGHT, bar.getAlignment(), c.name() + ": alignment");
                double betweenButtons = bar.getChildren().get(1).getBoundsInParent().getMinX() - bar.getChildren().get(0).getBoundsInParent().getMaxX();
                assertEquals(8, betweenButtons, TOLERANCE, c.name() + ": laid out gap between the buttons");
            }
        });
    }

    // ── U4CV:RF-05 ─────────────────────────────────────────────────────────

    private static List<Label> labelsOf(Parent root) {
        List<Label> labels = new ArrayList<>();
        for (Node node : allNodes(root)) {
            if (node instanceof Label label) labels.add(label);
        }
        return labels;
    }

    @Test
    void everyLabelWrapsAndNoneIsCutWithAnEllipsis() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                List<Label> labels = labelsOf(c.pane());
                assertEquals(4 + (c.copies() ? 1 : 0), labels.size(), c.name() + ": labels of the pane");
                for (Label label : labels) {
                    String who = c.name() + ": #" + label.getId();
                    assertTrue(label.isWrapText(), who + " wraps its text");
                    Text drawn = null;
                    for (Node child : label.getChildrenUnmodifiable()) {
                        if (child instanceof Text t && t.getStyleClass().contains("text")) drawn = t;
                    }
                    assertNotNull(drawn, who + ": the text node the label draws");
                    assertEquals(label.getText(), drawn.getText(), who + ": the drawn text is the whole text (no ellipsis)");
                }
            }
        });
    }

    @Test
    void theLongPathsAreWholeInTheirLabels() {
        FxTestSupport.runAndWait(() -> {
            Path file = pathOfLength(FILE_LENGTH, 3);
            Path copies = pathOfLength(COPIES_LENGTH, 11);
            for (Case c : cases()) {
                if (c.hasFile()) {
                    assertEquals("Arquivo de dados: " + file, labelById(c, ID_FILE).getText(), c.name() + ": the file line");
                } else {
                    assertEquals("Arquivo de dados: (não definido)", labelById(c, ID_FILE).getText(), c.name() + ": the file line");
                }
                if (c.copies()) assertEquals("Cópias de segurança: " + copies, labelById(c, ID_COPIES).getText(), c.name() + ": the copies line");
                else assertNull(c.pane().lookup("#" + ID_COPIES), c.name() + ": no copies line");
            }
        });
    }

    @Test
    void theWidthIs520AndTheHeightIsThePreferredOneWithNothingLeftOver() {
        FxTestSupport.runAndWait(() -> {
            for (Case c : cases()) {
                OpeningFailurePane pane = c.pane();
                assertEquals(520, pane.prefWidth(-1), TOLERANCE, c.name() + ": preferred width");
                assertEquals(520, pane.getMinWidth(), TOLERANCE, c.name() + ": min width");
                assertEquals(520, pane.getMaxWidth(), TOLERANCE, c.name() + ": max width");
                assertEquals(520, pane.getWidth(), TOLERANCE, c.name() + ": width");
                assertEquals(pane.prefHeight(520), pane.getHeight(), TOLERANCE, c.name() + ": height is prefHeight(520)");

                // the preferred height holds exactly the content: from the top padding to the bottom padding, none cut
                List<Node> children = directChildren(c);
                Insets padding = pane.getPadding();
                assertNotNull(padding, c.name() + ": padding");
                assertEquals(padding.getTop(), children.get(0).getBoundsInParent().getMinY(), TOLERANCE, c.name() + ": the content starts at the top padding");
                assertEquals(pane.getHeight() - padding.getBottom(), children.get(3).getBoundsInParent().getMaxY(), TOLERANCE,
                        c.name() + ": the content ends at the bottom padding");
                for (Node child : children) {
                    assertTrue(child.getBoundsInParent().getMaxX() <= pane.getWidth() - padding.getRight() + TOLERANCE,
                            c.name() + ": " + child.getClass().getSimpleName() + " stays inside the right padding");
                }
            }
        });
    }

    // ── U4CV:RF-01 item 2: no literal color in the new CSS blocks ──────────

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3,8}");
    private static final Pattern COLOR_FUNCTION = Pattern.compile("(?i)\\b(rgba?|hsla?)\\s*\\(");
    private static final Pattern BLOCK = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Set<String> COLOR_NAMES = new HashSet<>(Arrays.asList((
            "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood "
            + "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray "
            + "darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen "
            + "darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue "
            + "firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew "
            + "hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan "
            + "lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray "
            + "lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine mediumblue "
            + "mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred "
            + "midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid "
            + "palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple red "
            + "rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue slategray "
            + "slategrey snow springgreen steelblue tan teal thistle tomato turquoise violet wheat white whitesmoke yellow "
            + "yellowgreen transparent").split(" ")));

    /** What in the declarations of a block is a literal color: a hex, a rgb/hsl function or a CSS color name. */
    private static List<String> literalColorsIn(String declarations) {
        List<String> found = new ArrayList<>();
        for (String declaration : declarations.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) continue;
            String property = declaration.substring(0, colon).trim();
            String value = declaration.substring(colon + 1);
            Matcher hex = HEX.matcher(value);
            while (hex.find()) found.add(property + ": " + hex.group());
            Matcher function = COLOR_FUNCTION.matcher(value);
            while (function.find()) found.add(property + ": " + function.group().trim());
            for (String token : value.trim().split("[\\s,()/]+")) {
                if (COLOR_NAMES.contains(token.toLowerCase(Locale.ROOT))) found.add(property + ": " + token);
            }
        }
        return found;
    }

    private static String appCss() throws IOException {
        try (InputStream in = OpeningFailurePaneStyleTest.class.getResourceAsStream("/styles/app.css")) {
            assertNotNull(in, "/styles/app.css");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Selector and body of every block of the sheet whose selector contains {@code opening-failure}. */
    private static List<String[]> openingFailureBlocks(String css) {
        String withoutComments = css.replaceAll("(?s)/\\*.*?\\*/", "");
        List<String[]> blocks = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(withoutComments);
        while (matcher.find()) {
            String selector = matcher.group(1).trim();
            if (selector.contains("opening-failure")) blocks.add(new String[]{selector, matcher.group(2)});
        }
        return blocks;
    }

    @Test
    void theScannerFindsLiteralColorsAndLeavesLookedUpColorsAlone() {
        assertFalse(literalColorsIn("-fx-background-color: #223042;").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: #fff;").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: #11223344;").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: rgb(1,2,3);").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: RGBA(1, 2, 3, 0.5);").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: hsl(10, 20%, 30%);").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: hsla(10, 20%, 30%, 0.5);").isEmpty());
        assertFalse(literalColorsIn("-fx-border-color: transparent;").isEmpty());
        assertFalse(literalColorsIn("-fx-text-fill: White;").isEmpty());
        assertFalse(literalColorsIn("-fx-text-fill: black;").isEmpty());
        assertFalse(literalColorsIn("-fx-background-color: derive(red, 20%);").isEmpty());
        assertTrue(literalColorsIn("-fx-background-color: -color-surface-2;").isEmpty());
        assertTrue(literalColorsIn("-fx-text-fill: -color-white-a12;").isEmpty());
        assertTrue(literalColorsIn("-fx-background-color: derive(-color-accent, 20%); -fx-padding: 24;").isEmpty());
        assertTrue(literalColorsIn("-fx-icon-size: 15px;").isEmpty());
        assertEquals(1, openingFailureBlocks("/* .opening-failure-x { -fx-a: red; } */ .a { -fx-b: 1; } .opening-failure-pane { -fx-c: 2; }").size());
    }

    @Test
    void theNewBlocksOfTheSheetHaveNoLiteralColor() throws IOException {
        List<String[]> blocks = openingFailureBlocks(appCss());
        assertFalse(blocks.isEmpty(), "app.css has blocks whose selector contains opening-failure");
        assertTrue(blocks.stream().anyMatch(b -> b[0].contains(".opening-failure-pane")), "a block for .opening-failure-pane");
        assertTrue(blocks.stream().anyMatch(b -> b[0].contains(".opening-failure-icon")), "a block for .opening-failure-icon");
        for (String[] block : blocks) {
            assertEquals(List.of(), literalColorsIn(block[1]), "literal colors in the block `" + block[0] + "`");
        }
    }
}

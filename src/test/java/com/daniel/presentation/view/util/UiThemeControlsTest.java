package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiThemeControlsTest {
    @Test
    void focusUsesAPerimeterRatherThanTheSelectedNavigationMarker() {
        FxTestSupport.runAndWait(() -> {
            var nav = new javafx.scene.control.Button("Carteira");
            nav.getStyleClass().add("nav-btn");
            var field = new javafx.scene.control.TextField("125,00");
            var sidebar = new VBox(nav); sidebar.getStyleClass().add("sidebar");
            var root = new VBox(sidebar, field);
            var scene = new Scene(root, 400, 250);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            var focused = javafx.css.PseudoClass.getPseudoClass("focused");
            nav.pseudoClassStateChanged(focused, true); field.pseudoClassStateChanged(focused, true);
            root.applyCss(); root.layout();
            var border = nav.getBorder().getStrokes().getFirst();
            assertTrue(border.getWidths().getRight() > 0, "focus must not copy the active-page left marker");
            assertEquals(border.getTopStroke(), field.getBorder().getStrokes().getFirst().getTopStroke());
        });
    }

    static final javafx.scene.paint.Color FOCUS_BLUE = javafx.scene.paint.Color.web("#8ABFFF");

    static boolean sameRgb(javafx.scene.paint.Paint paint, javafx.scene.paint.Color expected) {
        return paint instanceof javafx.scene.paint.Color c
                && Math.abs(c.getRed() - expected.getRed()) < 0.01
                && Math.abs(c.getGreen() - expected.getGreen()) < 0.01
                && Math.abs(c.getBlue() - expected.getBlue()) < 0.01;
    }

    static javafx.scene.paint.Color token(Region probe, String token) {
        probe.setStyle("-fx-background-color: " + token + ";");
        probe.applyCss();
        return (javafx.scene.paint.Color) probe.getBackground().getFills().getFirst().getFill();
    }

    /**
     * E-V3: keyboard focus is one cue on every interactive variant, pages and dialogs alike: the
     * declared blue (-color-info) perimeter and no glow. A green ring reads as brand/gain or as the
     * selected state, a gray border or a faint white glow makes focus vanish; selection keeps its
     * own fill.
     */
    @Test
    void everyInteractiveVariantShowsTheSameBlueFocusPerimeterWithoutGlow() {
        FxTestSupport.runAndWait(() -> {
            java.util.Map<String, Region> targets = new java.util.LinkedHashMap<>();
            var root = new VBox(6);
            for (String variant : java.util.List.of("button", "primary-btn", "ghost-btn", "secondary-btn", "danger-btn",
                    "sell-btn", "icon-btn", "icon-text-btn", "danger-action-btn", "link-btn", "link-btn-accent", "link-btn-danger")) {
                var b = new javafx.scene.control.Button("Ação");
                if (!variant.equals("button")) b.getStyleClass().add(variant);
                root.getChildren().add(b);
                targets.put(variant, b);
            }
            for (String variant : java.util.List.of("filter-toggle", "bench-toggle")) {
                for (boolean selected : new boolean[]{false, true}) {
                    var t = new javafx.scene.control.ToggleButton("1M");
                    t.getStyleClass().add(variant);
                    t.setSelected(selected);
                    root.getChildren().add(t);
                    targets.put(variant + (selected ? ":selected" : ""), t);
                }
            }
            var chip = new javafx.scene.control.RadioButton("Mês");
            chip.getStyleClass().add("radio-chip");
            var seg = new javafx.scene.control.RadioButton("Por aporte");
            seg.getStyleClass().add("seg-btn");
            var segToggle = new javafx.scene.control.ToggleButton("Tudo");
            var segmented = new javafx.scene.layout.HBox(seg, segToggle);
            segmented.getStyleClass().add("segmented");
            var radio = new javafx.scene.control.RadioButton("Entrada");
            var field = new javafx.scene.control.TextField("125,00");
            var combo = new javafx.scene.control.ComboBox<String>();
            var date = new javafx.scene.control.DatePicker();
            var darkPane = new javafx.scene.control.DialogPane();
            darkPane.getStyleClass().add("dark-dialog");
            darkPane.getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            var dialogField = new javafx.scene.control.TextField();
            darkPane.setContent(dialogField);
            var plainPane = new javafx.scene.control.DialogPane();
            plainPane.getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
            root.getChildren().addAll(chip, segmented, radio, field, combo, date, darkPane, plainPane);
            var scene = new Scene(root, 900, 1500);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            var dialogPrimary = (Region) darkPane.lookupButton(javafx.scene.control.ButtonType.OK);
            dialogPrimary.getStyleClass().add("primary-btn");
            var dialogGhost = (Region) darkPane.lookupButton(javafx.scene.control.ButtonType.CANCEL);
            dialogGhost.getStyleClass().add("ghost-btn");
            targets.put("radio-chip", chip);
            targets.put("seg-btn", seg);
            targets.put("segmented toggle", segToggle);
            targets.put("text-field", field);
            targets.put("combo-box", combo);
            targets.put("date-picker", date);
            targets.put("dialog primary-btn", dialogPrimary);
            targets.put("dialog ghost-btn", dialogGhost);
            targets.put("dialog text-field", dialogField);
            targets.put("dialog plain button", (Region) plainPane.lookupButton(javafx.scene.control.ButtonType.CLOSE));

            var focused = javafx.css.PseudoClass.getPseudoClass("focused");
            targets.values().forEach(n -> n.pseudoClassStateChanged(focused, true));
            radio.pseudoClassStateChanged(focused, true);
            for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
            targets.put("radio .radio", (Region) radio.lookup(".radio"));

            java.util.List<String> problems = new java.util.ArrayList<>();
            targets.forEach((name, region) -> {
                boolean ring = region.getBorder() != null && region.getBorder().getStrokes().stream()
                        .anyMatch(s -> sameRgb(s.getTopStroke(), FOCUS_BLUE) && s.getWidths().getTop() > 0);
                if (!ring) problems.add(name + " border=" + (region.getBorder() == null ? null : region.getBorder().getStrokes()
                        .stream().map(s -> s.getTopStroke() + "/" + s.getWidths().getTop()).toList()));
                if (region.getEffect() != null) problems.add(name + " glow=" + region.getEffect());
            });
            if (radio.getEffect() != null) problems.add("radio glow=" + radio.getEffect());
            assertEquals(java.util.List.of(), problems);
        });
    }

    /**
     * E-V3: accent/info tints, the chart series and the crosshair dot follow the current tokens;
     * the retired #22C55E/#3B82F6 hues no longer appear in the stylesheet.
     */
    @Test
    void accentAndInfoTintsAndChartSeriesUseTheCurrentHues() {
        FxTestSupport.runAndWait(() -> {
            var probe = new Region();
            var accentLine = new javafx.scene.shape.Line(0, 0, 10, 10);
            var infoLine = new javafx.scene.shape.Line(0, 0, 10, 10);
            var root = new VBox(probe, new javafx.scene.Group(accentLine, infoLine));
            var scene = new Scene(root, 200, 200);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss();
            var accent = token(probe, "-color-accent");
            var info = token(probe, "-color-info");
            java.util.List<String> problems = new java.util.ArrayList<>();
            for (String t : java.util.List.of("-color-accent-soft", "-color-accent-a25", "-color-accent-glow",
                    "-color-accent-a35", "-color-accent-a40", "-color-accent-a45")) {
                if (!sameRgb(token(probe, t), accent)) problems.add(t + "=" + token(probe, t));
            }
            for (String t : java.util.List.of("-color-info-a12", "-fx-faint-focus-color")) {
                if (!sameRgb(token(probe, t), info)) problems.add(t + "=" + token(probe, t));
            }
            ChartSeriesStyle.accent(accentLine, 2);
            ChartSeriesStyle.info(infoLine, 2);
            root.applyCss();
            if (!sameRgb(accentLine.getStroke(), accent)) problems.add("chart accent series=" + accentLine.getStroke());
            if (!sameRgb(infoLine.getStroke(), info)) problems.add("chart info series=" + infoLine.getStroke());
            var makeDot = ChartCrosshair.class.getDeclaredMethod("makeDot");
            makeDot.setAccessible(true);
            var dot = (javafx.scene.shape.Circle) makeDot.invoke(null);
            if (!sameRgb(dot.getFill(), accent)) problems.add("crosshair dot=" + dot.getFill());
            String css;
            try (var in = getClass().getResourceAsStream("/styles/app.css")) {
                css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace(" ", "").toLowerCase();
            }
            for (String retired : java.util.List.of("rgba(34,197,94", "rgba(59,130,246", "#22c55e", "#3b82f6")) {
                if (css.contains(retired)) problems.add("app.css still uses " + retired);
            }
            assertEquals(java.util.List.of(), problems);
        });
    }

    @Test
    void valueStripHasNoStrandedVerticalDividerAtTheEndOfARow() {
        FxTestSupport.runAndWait(() -> {
            var strip = new com.daniel.presentation.view.components.StatStrip("Caixa", "Posições", "Ganho");
            var root = new VBox(strip); var scene = new Scene(root, 500, 200);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (int columns : new int[]{3, 2, 1}) {
                strip.setColumns(columns); root.applyCss(); root.layout();
                var last = (Region) strip.valueLabel("Ganho").getParent();
                assertTrue(last.getBorder() == null || last.getBorder().getStrokes().stream()
                        .allMatch(s -> s.getWidths().getRight() == 0));
            }
        });
    }

    @Test
    void analyticHeadersUseReadableTextWithoutVerticalGridBorders() {
        FxTestSupport.runAndWait(() -> {
            var table = new javafx.scene.control.TableView<String>();
            table.getStyleClass().addAll("table-analytic", "table-dense");
            var column = new javafx.scene.control.TableColumn<String, String>("Ativo");
            column.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()));
            table.getColumns().add(column); table.getItems().add("FICT1");
            var root = new VBox(table); var scene = new Scene(root, 400, 250);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            Region header = (Region) table.lookupAll(".column-header").stream()
                    .filter(n -> n.lookup(".label") != null).findFirst().orElseThrow();
            assertNotNull(header);
            assertTrue(header.getBorder() == null || header.getBorder().getStrokes().stream()
                    .allMatch(s -> s.getWidths().getRight() == 0 && s.getWidths().getLeft() == 0),
                    "no vertical header grid inherited from the old skin");
            var label = (javafx.scene.control.Label) header.lookup(".label");
            assertTrue(label.getFont().getSize() >= 12);
        });
    }

    // ── E4: what the contrast/focus audit of the r2 JAR reproved ─────────────

    static javafx.scene.paint.Color over(javafx.scene.paint.Color top, javafx.scene.paint.Color under) {
        double a = top.getOpacity();
        return new javafx.scene.paint.Color(top.getRed() * a + under.getRed() * (1 - a),
                top.getGreen() * a + under.getGreen() * (1 - a), top.getBlue() * a + under.getBlue() * (1 - a), 1);
    }

    static double contrast(javafx.scene.paint.Color a, javafx.scene.paint.Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    static double luminance(javafx.scene.paint.Color c) {
        return .2126 * lin(c.getRed()) + .7152 * lin(c.getGreen()) + .0722 * lin(c.getBlue());
    }

    static double lin(double v) { return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }

    /** The region's own colour under its content: its fills composited in order over {@code under}. */
    static javafx.scene.paint.Color fillsOver(Region r, javafx.scene.paint.Color under) {
        javafx.scene.paint.Color c = under;
        if (r.getBackground() != null)
            for (var f : r.getBackground().getFills()) if (f.getFill() instanceof javafx.scene.paint.Color fc) c = over(fc, c);
        return c;
    }

    /** What the eye gets: the button (ink on its fills) drawn with its opacity over the panel. */
    static javafx.scene.paint.Color seen(javafx.scene.paint.Color ink, double opacity, javafx.scene.paint.Color under) {
        return over(new javafx.scene.paint.Color(ink.getRed(), ink.getGreen(), ink.getBlue(), ink.getOpacity() * opacity), under);
    }

    /**
     * E4 (V7): text 4.5:1 and icons 3:1 against what is behind them, in normal, hover and pressed,
     * enabled. r2: "Novo ativo"'s light icon on the green primary 1.75:1, the primary pressed
     * (derive −25 %) 3.7:1, and "Excluir" pressed 4.33:1 — PrimerDark's `.button:hover` opacity 0.9
     * also dims a pressed button (a press is always under the pointer).
     */
    @Test
    void enabledButtonsKeepTextAndIconContrastInNormalHoverAndPressed() {
        FxTestSupport.runAndWait(() -> {
            var panel = new VBox(8);
            panel.setStyle("-fx-background-color: -color-surface;");
            java.util.Map<String, javafx.scene.control.Button> buttons = new java.util.LinkedHashMap<>();
            for (String variant : java.util.List.of("primary-btn", "secondary-btn", "ghost-btn", "danger-btn", "sell-btn")) {
                var icon = new org.kordamp.ikonli.javafx.FontIcon(org.kordamp.ikonli.feather.Feather.PLUS);
                var b = new javafx.scene.control.Button("Ação", icon);
                b.getStyleClass().add(variant);
                buttons.put(variant, b);
                panel.getChildren().add(b);
            }
            var scene = new Scene(panel, 400, 400);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            panel.applyCss();
            var surface = (javafx.scene.paint.Color) panel.getBackground().getFills().getFirst().getFill();
            var hover = javafx.css.PseudoClass.getPseudoClass("hover");
            var pressed = javafx.css.PseudoClass.getPseudoClass("pressed");
            java.util.List<String> problems = new java.util.ArrayList<>();
            for (String state : java.util.List.of("normal", "hover", "pressed")) {
                for (var e : buttons.entrySet()) {
                    var b = e.getValue();
                    b.pseudoClassStateChanged(hover, !state.equals("normal"));
                    b.pseudoClassStateChanged(pressed, state.equals("pressed"));
                    panel.applyCss();
                    var bg = seen(fillsOver(b, surface), b.getOpacity(), surface);
                    var text = seen((javafx.scene.paint.Color) b.getTextFill(), b.getOpacity(), surface);
                    var icon = seen((javafx.scene.paint.Color) ((org.kordamp.ikonli.javafx.FontIcon) b.getGraphic()).getIconColor(), b.getOpacity(), surface);
                    if (contrast(text, bg) < 4.5) problems.add(e.getKey() + " " + state + " text " + text + " on " + bg + " = " + contrast(text, bg));
                    if (contrast(icon, bg) < 3.0) problems.add(e.getKey() + " " + state + " icon " + icon + " on " + bg + " = " + contrast(icon, bg));
                }
            }
            assertEquals(java.util.List.of(), problems);
        });
    }

    /**
     * E4 (E-V3 residual) and R2-13: the active page's nav button, the CheckBox, the Hyperlink and the
     * Spinner show the same blue perimeter (r2: `.sidebar .nav-btn.active` out-ranked `:focused`; no
     * cue on CheckBox/Hyperlink; the Spinner's #315B85 read 2.29:1), the active marker survives focus,
     * the CheckBox does not move when focused, and the Spinner's editor is its text, not a second
     * framed field inside the spinner (as the ComboBox editor, A-V7).
     */
    @Test
    void activePageCheckboxHyperlinkAndSpinnerShowTheBluePerimeter_andTheSpinnerHasOneFrame() {
        FxTestSupport.runAndWait(() -> {
            var nav = new javafx.scene.control.Button("Carteira");
            nav.getStyleClass().addAll("nav-btn", "active");
            var sidebar = new VBox(nav);
            sidebar.getStyleClass().add("sidebar");
            var check = new CheckBox("Mostrar arquivados");
            var link = new javafx.scene.control.Hyperlink("brapi.dev");
            link.getStyleClass().addAll("text-helper", "text-link");
            var spinner = new javafx.scene.control.Spinner<Integer>(1, 50, 9);
            spinner.setEditable(true);
            var root = new VBox(8, sidebar, check, link, spinner);
            var scene = new Scene(root, 400, 300);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
            java.util.List<String> problems = new java.util.ArrayList<>();
            java.util.function.BiConsumer<String, Region> oneFrame = (name, editor) -> {
                if (editor.getBorder() != null && editor.getBorder().getStrokes().stream().anyMatch(s -> s.getWidths().getTop() > 0
                        && s.getTopStroke() instanceof javafx.scene.paint.Color c && c.getOpacity() > 0))
                    problems.add(name + ": the editor draws its own frame " + editor.getBorder().getStrokes().stream()
                            .map(s -> s.getTopStroke() + "/" + s.getWidths().getTop()).toList());
                if (editor.getBackground() != null && editor.getBackground().getFills().stream()
                        .anyMatch(f -> f.getFill() instanceof javafx.scene.paint.Color c && c.getOpacity() > 0))
                    problems.add(name + ": the editor paints its own box " + editor.getBackground().getFills().stream().map(f -> f.getFill()).toList());
            };
            oneFrame.accept("spinner", spinner.getEditor());
            Region box = (Region) check.lookup(".box");
            double boxWidth = box.getWidth(), boxHeight = box.getHeight();

            var focused = javafx.css.PseudoClass.getPseudoClass("focused");
            for (var n : java.util.List.of(nav, check, link, spinner, spinner.getEditor())) n.pseudoClassStateChanged(focused, true);
            for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }

            java.util.function.BiConsumer<String, Region> ring = (name, region) -> {
                boolean blue = region.getBorder() != null && region.getBorder().getStrokes().stream()
                        .anyMatch(s -> sameRgb(s.getTopStroke(), FOCUS_BLUE) && s.getWidths().getTop() > 0);
                if (!blue) problems.add(name + " border=" + (region.getBorder() == null ? null : region.getBorder().getStrokes().stream()
                        .map(s -> s.getTopStroke() + "/" + s.getWidths().getTop()).toList()));
                if (region.getEffect() != null) problems.add(name + " glow=" + region.getEffect());
            };
            ring.accept("active nav", nav);
            boolean marker = nav.getBorder() != null && nav.getBorder().getStrokes().stream()
                    .anyMatch(s -> sameRgb(s.getLeftStroke(), javafx.scene.paint.Color.web("#39CF8B")) && s.getWidths().getLeft() >= 3);
            if (!marker) problems.add("active nav lost its green page marker when focused");
            ring.accept("check-box .box", box);
            if (Math.abs(box.getWidth() - boxWidth) > 0.01 || Math.abs(box.getHeight() - boxHeight) > 0.01)
                problems.add("check-box moved when focused: " + boxWidth + "×" + boxHeight + " → " + box.getWidth() + "×" + box.getHeight());
            ring.accept("hyperlink", link);
            ring.accept("spinner", spinner);
            oneFrame.accept("focused spinner", spinner.getEditor());
            assertEquals(java.util.List.of(), problems);
        });
    }

    static final javafx.scene.paint.Color REFUSED_AMBER = javafx.scene.paint.Color.web("#F3C979");

    static boolean strokes(Region region, javafx.scene.paint.Color color) {
        return region.getBorder() != null && region.getBorder().getStrokes().stream()
                .anyMatch(s -> sameRgb(s.getTopStroke(), color) && s.getWidths().getTop() > 0);
    }

    /** An opaque layer of the colour: a ring, not the selection's 12 % tint of the same blue. */
    static boolean paints(Region region, javafx.scene.paint.Color color) {
        return region.getBackground() != null && region.getBackground().getFills().stream()
                .anyMatch(f -> sameRgb(f.getFill(), color) && ((javafx.scene.paint.Color) f.getFill()).getOpacity() > 0.9);
    }

    /**
     * R3-07: a refused value got its amber edge, but the field takes the focus and `:focused` swapped
     * the amber for the blue ring — focused, it no longer said its value was refused. Both show: the
     * blue perimeter outside, the amber edge inside, on pages and in dialogs, for the text fields, the
     * lists and the dates the Aporte marks; the content sits where it sits in the same control focused
     * without an error (the theme's focus widens a text field's edge by half a pixel, not a list's).
     */
    @Test
    void aRefusedFieldKeepsItsAmberEdgeInsideTheFocusRing() {
        FxTestSupport.runAndWait(() -> {
            java.util.List<java.util.function.Supplier<Region>> kinds = java.util.List.of(
                    () -> new javafx.scene.control.TextField("abc"),
                    () -> new javafx.scene.control.ComboBox<>(javafx.collections.FXCollections.observableArrayList("FICT1", "FICT2")),
                    () -> new javafx.scene.control.DatePicker(java.time.LocalDate.of(2026, 10, 20)));
            var page = new VBox(8);
            var dialog = new VBox(8);
            dialog.getStyleClass().add("dialog-pane");
            var root = new VBox(8, page, dialog);
            var scene = new Scene(root, 400, 600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            java.util.List<Region[]> pairs = new java.util.ArrayList<>();
            for (var host : java.util.List.of(page, dialog))
                for (var kind : kinds) {
                    Region refused = kind.get(), plain = kind.get();
                    host.getChildren().addAll(refused, plain);
                    pairs.add(new Region[]{refused, plain});
                }
            java.util.List<String> problems = new java.util.ArrayList<>();
            var focused = javafx.css.PseudoClass.getPseudoClass("focused");
            for (Region[] pair : pairs) {
                Region field = pair[0], plain = pair[1];
                String name = (field.getParent() == page ? "page " : "dialog ") + field.getClass().getSimpleName();
                field.getStyleClass().add("field-error");
                for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
                if (!strokes(field, REFUSED_AMBER)) problems.add(name + ": no amber edge when refused");
                field.pseudoClassStateChanged(focused, true);
                plain.pseudoClassStateChanged(focused, true);
                for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
                var before = plain.getInsets();
                if (!strokes(field, FOCUS_BLUE)) problems.add(name + ": focused without the blue perimeter");
                if (!strokes(field, REFUSED_AMBER)) problems.add(name + ": focused, the amber edge is gone");
                if (field.getEffect() != null) problems.add(name + ": glow " + field.getEffect());
                if (!field.getInsets().equals(before)) problems.add(name + ": content sits at " + field.getInsets() + ", focused without error at " + before);
                field.pseudoClassStateChanged(focused, false);
                plain.pseudoClassStateChanged(focused, false);
            }
            assertEquals(java.util.List.of(), problems);
        });
    }

    static javafx.scene.control.TableView<String> table(String... classes) {
        var t = new javafx.scene.control.TableView<>(javafx.collections.FXCollections.observableArrayList("FICT1", "FICT2", "FICT3"));
        var c = new javafx.scene.control.TableColumn<String, String>("Ativo");
        c.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue()));
        t.getColumns().add(c);
        t.getStyleClass().addAll(classes);
        t.setPrefHeight(200);
        return t;
    }

    static javafx.scene.control.TableRow<?> row(javafx.scene.control.TableView<?> table, int index) {
        for (var n : table.lookupAll(".table-row-cell"))
            if (n instanceof javafx.scene.control.TableRow<?> r && r.getIndex() == index && !r.isEmpty()) return r;
        return null;
    }

    /**
     * E4 (V7, r3 audit): a focused table showed no cue at all — the Carteira, the Ranking lists, the
     * Extrato and the Diversificação tables kept their gray (or no) border, and the focused row looked
     * like the others. A focused table takes the blue perimeter; inside an analytic table (every table
     * of the app) the focused row is ringed in blue, selected or not; an unfocused table rings nothing;
     * focus moves no cell (the selection keeps its own fill and its 3 px marker).
     */
    @Test
    void aFocusedTableAndItsFocusedRowShowTheBluePerimeter() {
        FxTestSupport.runAndWait(() -> {
            var carteira = table("table-analytic", "table-dense");
            var workspace = new VBox(carteira);
            workspace.getStyleClass().add("positions-workspace");
            var ranking = table("table-analytic", "table-dense");
            var fitted = table("fitted-table", "table-analytic");
            var plain = table();
            var root = new VBox(8, workspace, ranking, fitted, plain);
            var scene = new Scene(root, 500, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (int pass = 0; pass < 3; pass++) { root.applyCss(); root.layout(); }
            java.util.List<String> problems = new java.util.ArrayList<>();
            var focused = javafx.css.PseudoClass.getPseudoClass("focused");
            int k = 0;
            java.util.function.BiFunction<javafx.scene.control.TableView<?>, Boolean, javafx.geometry.Bounds> cellAt = (t, focus) -> {
                t.pseudoClassStateChanged(focused, focus);
                for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
                var c = row(t, 1).lookup(".table-cell");
                return c.localToScene(c.getLayoutBounds());
            };
            for (var t : java.util.List.of(carteira, ranking, fitted, plain)) {
                String name = "table " + k++;
                boolean analytic = t.getStyleClass().contains("table-analytic");
                t.getFocusModel().focus(1);
                for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
                if (row(t, 1) == null) { problems.add(name + ": row 1 not rendered"); continue; }
                for (boolean selected : new boolean[]{false, true}) {
                    if (selected) t.getSelectionModel().select(1); else t.getSelectionModel().clearSelection();
                    t.getFocusModel().focus(1);
                    String state = name + (selected ? " (row selected)" : "");
                    var unfocused = cellAt.apply(t, false);
                    var r1 = row(t, 1);
                    if (paints(r1, FOCUS_BLUE) || strokes(r1, FOCUS_BLUE)) problems.add(state + ": ring on a row of an unfocused table");
                    var focusedCell = cellAt.apply(t, true);
                    r1 = row(t, 1);
                    if (!strokes(t, FOCUS_BLUE)) problems.add(state + ": focused table without the blue perimeter " + (t.getBorder() == null ? null
                            : t.getBorder().getStrokes().stream().map(s -> s.getTopStroke() + "/" + s.getWidths().getTop()).toList()));
                    if (t.getEffect() != null) problems.add(state + ": glow " + t.getEffect());
                    if (analytic && !(paints(r1, FOCUS_BLUE) || strokes(r1, FOCUS_BLUE))) problems.add(state + ": focused row not ringed");
                    if (Math.abs(focusedCell.getMinX() - unfocused.getMinX()) > 0.01 || Math.abs(focusedCell.getMinY() - unfocused.getMinY()) > 0.01)
                        problems.add(state + ": focus moved the cell " + unfocused + " → " + focusedCell);
                    t.pseudoClassStateChanged(focused, false);
                }
            }
            assertEquals(java.util.List.of(), problems);
        });
    }

    /**
     * r2 review (suggestion 11): `.sidebar .nav-btn:focused` repainted the active page's button with
     * the plain surface — focus kept the ring and the marker but took the selection fill away. Wide
     * rail and icon-only rail; hover over the focused active page still answers.
     */
    @Test
    void theActivePageKeepsItsSelectionFillWhenFocused() {
        FxTestSupport.runAndWait(() -> {
            java.util.List<String> problems = new java.util.ArrayList<>();
            var focused = javafx.css.PseudoClass.getPseudoClass("focused");
            var hover = javafx.css.PseudoClass.getPseudoClass("hover");
            for (boolean compact : new boolean[]{false, true}) {
                var nav = new javafx.scene.control.Button("Carteira");
                nav.getStyleClass().addAll("nav-btn", "active");
                var sidebar = new VBox(nav);
                sidebar.getStyleClass().add("sidebar");
                var root = new VBox(sidebar);
                if (compact) root.getStyleClass().add("icon-compact");
                var scene = new Scene(root, 300, 200);
                scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
                root.applyCss();
                var idle = nav.getBackground().getFills().stream().map(f -> f.getFill()).toList();
                nav.pseudoClassStateChanged(hover, true);
                root.applyCss();
                var hovered = nav.getBackground().getFills().stream().map(f -> f.getFill()).toList();
                nav.pseudoClassStateChanged(hover, false);
                nav.pseudoClassStateChanged(focused, true);
                root.applyCss();
                var withFocus = nav.getBackground().getFills().stream().map(f -> f.getFill()).toList();
                nav.pseudoClassStateChanged(hover, true);
                root.applyCss();
                var hoveredWithFocus = nav.getBackground().getFills().stream().map(f -> f.getFill()).toList();
                String rail = compact ? "icon rail" : "rail";
                if (!idle.equals(withFocus)) problems.add(rail + ": focus repainted the fill " + idle + " → " + withFocus);
                if (!hovered.equals(hoveredWithFocus)) problems.add(rail + ": hover lost under focus " + hovered + " → " + hoveredWithFocus);
            }
            assertEquals(java.util.List.of(), problems);
        });
    }

    /**
     * V8 (E4 audit of the r2 JAR): a disabled ComboBox, DatePicker or Spinner dimmed itself AND its
     * editor (disabled with it): 0.45 × 0.45 ≈ 0.2, so "20/10/2026", "9" and "21" read 1.5–1.8:1 — the
     * disabled state hid the value. A disabled control dims once: still visibly disabled, value legible.
     */
    @Test
    void aDisabledComboDateOrSpinnerDimsOnce_itsValueStaysIdentifiable() {
        FxTestSupport.runAndWait(() -> {
            var combo = new javafx.scene.control.ComboBox<String>();
            combo.setEditable(true);
            combo.getEditor().setText("PETR4");
            var date = new javafx.scene.control.DatePicker(java.time.LocalDate.of(2026, 10, 20));
            var spinner = new javafx.scene.control.Spinner<Integer>(2, 50, 9);
            spinner.setEditable(true);
            var root = new VBox(8, combo, date, spinner);
            var scene = new Scene(root, 400, 300);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            for (var c : java.util.List.of(combo, date, spinner)) c.setDisable(true);
            for (int pass = 0; pass < 2; pass++) { root.applyCss(); root.layout(); }
            java.util.List<String> problems = new java.util.ArrayList<>();
            java.util.Map<String, javafx.scene.control.Control> controls = new java.util.LinkedHashMap<>();
            controls.put("combo", combo);
            controls.put("date", date);
            controls.put("spinner", spinner);
            java.util.Map<String, javafx.scene.Node> editors = java.util.Map.of(
                    "combo", combo.getEditor(), "date", date.getEditor(), "spinner", spinner.getEditor());
            for (var e : controls.entrySet()) {
                double effective = 1;
                for (javafx.scene.Node n = editors.get(e.getKey()); n != null && n != root; n = n.getParent()) effective *= n.getOpacity();
                if (e.getValue().getOpacity() >= 1) problems.add(e.getKey() + ": not visibly disabled");
                if (effective < 0.4) problems.add(e.getKey() + ": the value is dimmed twice, effective opacity " + effective);
            }
            assertEquals(java.util.List.of(), problems);
        });
    }

    @Test
    void uncheckedCheckboxDoesNotDisplayAFaintCheckmarkFromTheLegacyBackground() {
        FxTestSupport.runAndWait(() -> {
            var check = new CheckBox("Mostrar arquivados");
            var root = new VBox(check);
            var scene = new Scene(root, 400, 200);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            root.applyCss(); root.layout();
            Region box = (Region) check.lookup(".box");
            Region mark = (Region) check.lookup(".mark");
            assertEquals(box.getBackground().getFills().getLast().getFill(),
                    mark.getBackground().getFills().getLast().getFill(),
                    "unchecked mark must merge with its background, not look checked");
            check.setSelected(true); root.applyCss(); root.layout();
            assertNotEquals(box.getBackground().getFills().getLast().getFill(),
                    mark.getBackground().getFills().getLast().getFill());
        });
    }
}

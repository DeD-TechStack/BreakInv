package com.daniel.presentation.view.pages;

import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configurações (direction A): three groups in the order the user reasons about them —
 * appearance/behaviour, integrations (quotes, reference rates), data/about — as panel blocks
 * with sentence-case titles; the plan toggles wrap; every control has a name; the rates are a
 * value strip that reflows; no implementation vocabulary.
 */
class ConfiguracoesPageLayoutTest {

    ConfiguracoesPage page;
    Parent view;

    @BeforeEach
    void setUp() {
        UiPreferences prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
        FxTestSupport.runAndWait(() -> {
            page = new ConfiguracoesPage(prefs);
            view = page.view();
        });
    }

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        return out;
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = ConfiguracoesPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    Pane pageRoot() {
        return (Pane) ((ScrollPane) view).getContent();
    }

    @Test
    void integrationsShareAWorkspaceThatStacksWithoutRecreatingControls() {
        FxTestSupport.runAndWait(() -> {
            Node workspace = pageRoot().lookup(".settings-integrations");
            assertNotNull(workspace, "quotes and reference data should be adjacent at wide widths");
            var pair = (com.daniel.presentation.view.layout.AdaptivePair) workspace;
            assertTrue(flatten(pair.first()).contains(field("tokenField")));
            assertTrue(flatten(pair.second()).contains(field("ratesStrip")));
            page.onBreakpoint(Breakpoint.WIDE); assertTrue(pair.isSideBySide());
            page.onBreakpoint(Breakpoint.NARROW); assertFalse(pair.isSideBySide());
            page.onBreakpoint(Breakpoint.WIDE); assertTrue(pair.isSideBySide());
        });
    }

    @Test
    void groupsAreAppearanceIntegrationsData_asPanelsWithSentenceCaseTitles() {
        FxTestSupport.runAndWait(() -> {
            List<Panel> panels = flatten(pageRoot()).stream().filter(Panel.class::isInstance).map(Panel.class::cast).distinct().toList();
            List<String> titles = panels.stream().map(p -> ((Label) p.lookup(".panel-title")).getText()).toList();
            assertEquals(4, titles.size(), titles.toString());
            assertTrue(titles.get(0).toLowerCase().startsWith("aparência"), titles.toString());
            assertTrue(titles.get(1).toLowerCase().contains("cotações"), titles.toString());
            assertTrue(titles.get(2).toLowerCase().contains("taxas"), titles.toString());
            assertTrue(titles.get(3).toLowerCase().contains("dados"), titles.toString());
            for (String t : titles) assertNotEquals(t.toUpperCase(), t, "no uppercase micro-type titles: " + t);
            List<String> bad = flatten(view).stream().filter(Labeled.class::isInstance).map(n -> ((Labeled) n).getText())
                    .filter(t -> t != null).map(String::toLowerCase)
                    .filter(s -> s.contains("ledger") || s.contains("replay") || s.contains("schema")).toList();
            assertTrue(bad.isEmpty(), bad.toString());
        });
    }

    @Test
    void appearanceGroup_ownsReduceMotionAndTheWelcomeReplay() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Panel appearance = pageRoot().getChildren().stream().filter(Panel.class::isInstance).map(Panel.class::cast).findFirst().orElseThrow();
            CheckBox reduce = field("reduceMotionCheck");
            Button welcome = field("showWelcomeBtn");
            assertTrue(flatten(appearance).contains(reduce));
            assertTrue(flatten(appearance).contains(welcome), "showing the welcome again is behaviour, not an integration");
            assertNotNull(welcome.getTooltip());
        });
    }

    @Test
    void integrationControls_haveNames_andPlanTogglesWrap() throws Exception {
        FxTestSupport.runAndWait(() -> {
            TextField token = field("tokenField");
            assertNotNull(token.getAccessibleText());
            Button test = field("testTokenBtn");
            Button save = field("saveBtn");
            assertNotNull(test.getTooltip());
            assertNotNull(save.getTooltip());
            assertEquals("Salvar", save.getText());
            Node planRow = field("planRow");
            assertTrue(planRow instanceof FlowPane, "plan toggles wrap in a narrow window: " + planRow.getClass());
            CheckBox auto = field("autoUpdateCheckbox");
            assertFalse(auto.getText().isBlank());
            Label status = field("tokenStatusLabel");
            assertTrue(status.isWrapText());
        });
    }

    @Test
    void ratesAreAValueStrip_withSourceAndFreshness_andReflow() throws Exception {
        FxTestSupport.runAndWait(() -> {
            StatStrip rates = field("ratesStrip");
            assertEquals(List.of("CDI a.a.", "SELIC a.a.", "IPCA a.a."), rates.labels());
            Label fresh = field("bcbLastUpdateLabel");
            assertTrue(fresh.getText().toLowerCase().contains("nunca") || fresh.getText().toLowerCase().contains("atualizad"), fresh.getText());
            Button update = field("updateRatesBtn");
            assertNotNull(update.getTooltip());
            // A-V6: three short rates are one row wherever they fit; the strip itself drops
            // columns only when a value would not fit (it measures the real width).
            for (Breakpoint bp : List.of(Breakpoint.NARROW, Breakpoint.WIDE, Breakpoint.MEDIUM)) {
                page.onBreakpoint(bp);
                assertEquals(3, rates.columns(), "three rates requested at " + bp);
            }
        });
    }

    /** A-V6: in the wide integration column the three rates sit on one line, not a 220 px stack of dashes. */
    @Test
    void wideIntegrationColumnShowsTheThreeRatesOnOneLine() throws Exception {
        FxTestSupport.runAndWait(() -> {
            var view = page.view();
            var scene = new javafx.scene.Scene(view, 1060, 760);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 760);
            for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
            StatStrip rates = field("ratesStrip");
            assertEquals(3, rates.effectiveColumns(), "three rates fit beside the token column at 1060 px");
            for (String label : rates.labels()) {
                Label value = rates.valueLabel(label);
                var shown = (javafx.scene.text.Text) value.lookup(".text");
                assertEquals(value.getText(), shown == null ? null : shown.getText(), "rate value never cut: " + label);
            }
        });
    }

    /**
     * R2-20: the rates strip drew its own box inside the rates panel (box in box), and the panel was
     * stretched to its BRAPI neighbour's height — ~165 px empty under "Atualizar CDI, SELIC e IPCA".
     */
    @Test
    void ratesPanelHasOneFrameAndItsOwnHeight() throws Exception {
        FxTestSupport.runAndWait(() -> {
            var view = page.view();
            var scene = new javafx.scene.Scene(view, 1060, 900);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.WIDE);
            ((javafx.scene.layout.Region) view).resize(1060, 900);
            for (int pass = 0; pass < 6; pass++) { view.applyCss(); view.layout(); }
            StatStrip rates = field("ratesStrip");
            var pair = (com.daniel.presentation.view.layout.AdaptivePair) pageRoot().lookup(".settings-integrations");
            assertTrue(pair.isSideBySide());
            var ratesPanel = (javafx.scene.layout.Region) pair.second();
            var brapiPanel = (javafx.scene.layout.Region) pair.first();
            List<String> problems = new ArrayList<>();
            if (rates.getBorder() != null && rates.getBorder().getStrokes().stream().anyMatch(s -> s.getWidths().getTop() > 0
                    && s.getTopStroke() instanceof javafx.scene.paint.Color c && c.getOpacity() > 0))
                problems.add("the strip draws its own frame inside the panel");
            if (rates.getBackground() != null && rates.getBackground().getFills().stream()
                    .anyMatch(f -> f.getFill() instanceof javafx.scene.paint.Color c && c.getOpacity() > 0))
                problems.add("the strip paints its own box inside the panel");
            double own = ratesPanel.prefHeight(ratesPanel.getWidth());
            if (brapiPanel.getHeight() > own + 40 && ratesPanel.getHeight() > own + 1)
                problems.add("rates panel stretched to " + ratesPanel.getHeight() + " for " + own + " of content");
            assertEquals(List.of(), problems);
        });
    }
}

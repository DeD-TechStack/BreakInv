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
    void groupsAreAppearanceIntegrationsData_asPanelsWithSentenceCaseTitles() {
        FxTestSupport.runAndWait(() -> {
            List<Panel> panels = pageRoot().getChildren().stream().filter(Panel.class::isInstance).map(Panel.class::cast).toList();
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
            page.onBreakpoint(Breakpoint.NARROW);
            assertEquals(1, rates.columns());
            page.onBreakpoint(Breakpoint.WIDE);
            assertEquals(3, rates.columns());
        });
    }
}

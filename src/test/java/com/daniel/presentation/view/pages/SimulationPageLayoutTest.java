package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.LineChart;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulação (direction A): parameters and result sit side by side when wide and stack when not;
 * the result is a hero value that says it is an estimate; one "Calcular" serves every type;
 * saved scenarios are a discreet panel after the work area; re-flowing never loses what was
 * typed. The busy/epoch flows are covered by SimulationPageScenarioSaveBusyTest.
 */
class SimulationPageLayoutTest {

    @TempDir
    Path tempDir;

    final Deque<Runnable> worker = new ArrayDeque<>();
    SimulationPage page;
    Parent view;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("scen.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE simulation_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "parameters_json TEXT NOT NULL, calculation_version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
        }
        SavedAnalysisRepository scenarios = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        FxTestSupport.runAndWait(() -> {
            page = new SimulationPage(scenarios, worker::add, Runnable::run);
            view = page.view();
        });
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
        Field f = SimulationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    Pane pageRoot() {
        return (Pane) ((ScrollPane) view).getContent();
    }

    int indexOfAncestorIn(Node target) {
        Pane root = pageRoot();
        for (int i = 0; i < root.getChildren().size(); i++) {
            if (flatten(root.getChildren().get(i)).contains(target)) return i;
        }
        return -1;
    }

    @Test
    void parametersAndResultAreNeighbours_sideBySideWhenWide_stackedOtherwise() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AdaptivePair pair = field("workPair");
            Button calc = field("calculateBtn");
            HeroValue hero = field("resultHero");
            LineChart<?, ?> chart = field("projectionChart");
            assertTrue(flatten(pair.first()).contains(calc), "Calcular closes the parameters column");
            assertTrue(flatten(pair.second()).contains(hero), "the result is the first thing in the second column");
            assertTrue(flatten(pair.second()).contains(chart));
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(pair.isSideBySide());
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertFalse(pair.isSideBySide(), "a form plus a chart do not fit side by side under 960");
            page.onBreakpoint(Breakpoint.NARROW);
            assertFalse(pair.isSideBySide());
            assertEquals("Valor final estimado", hero.caption());
        });
    }

    @Test
    void reflowKeepsEveryTypedParameter_andTheSameNodes() throws Exception {
        FxTestSupport.runAndWait(() -> {
            TextField initial = field("initialValueField");
            TextField rate = field("fixedRateField");
            TextField name = field("scenarioNameField");
            ComboBox<Integer> months = field("monthsCombo");
            initial.setText("10000");
            rate.setText("12,5");
            name.setText("CDB");
            months.setValue(24);
            page.onBreakpoint(Breakpoint.NARROW);
            page.onBreakpoint(Breakpoint.WIDE);
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertEquals("12,5", rate.getText());
            assertEquals("CDB", name.getText());
            assertEquals(24, months.getValue());
            assertFalse(initial.getText().isBlank());
            assertTrue(flatten(view).contains(initial) && flatten(view).contains(rate) && flatten(view).contains(name),
                    "the same controls are re-parented, never rebuilt");
        });
    }

    @Test
    void oneCalculateForEveryType_resultHeroAndChartFollow_withEstimateWording() throws Exception {
        FxTestSupport.runAndWait(() -> {
            TextField initial = field("initialValueField");
            TextField rate = field("fixedRateField");
            initial.setText("10000");
            rate.setText("12,5");
            Button calc = field("calculateBtn");
            calc.fire();
            HeroValue hero = field("resultHero");
            assertNotEquals("—", hero.valueLabel().getText());
            Label secondary = (Label) hero.lookup(".hero-secondary");
            assertTrue(secondary.getText().toLowerCase().contains("lucro"), secondary.getText());
            Label context = (Label) hero.lookup(".hero-context");
            assertTrue(context.getText().toLowerCase().contains("estimativa"), "says it is an estimate: " + context.getText());
            LineChart<?, ?> chart = field("projectionChart");
            assertEquals(1, chart.getData().size());
            assertEquals(13, chart.getData().get(0).getData().size(), "12 months + month 0");
            Panel chartPanel = field("chartPanel");
            assertTrue(flatten(chartPanel).contains(chart));
            // the stock type uses the same button
            ToggleButton acao = field("acaoBtn");
            acao.setSelected(true);
            assertTrue(calc.isVisible() && calc.isManaged());
            List<Button> allCalc = flatten(view).stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getText() != null && b.getText().startsWith("Calcular")).toList();
            assertEquals(1, allCalc.size(), "one Calcular on the page: " + allCalc.size());
        });
    }

    @Test
    void savedScenariosAreADiscreetPanel_afterTheWorkArea_withNamedActions() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Panel scenarios = field("scenarioPanel");
            AdaptivePair pair = field("workPair");
            assertTrue(indexOfAncestorIn(scenarios) > indexOfAncestorIn(pair), "scenarios after the work area");
            Label subtitle = (Label) scenarios.lookup(".panel-subtitle");
            assertTrue(subtitle.isVisible() && !subtitle.getText().isBlank(), "says what a saved scenario keeps");
            for (String name : new String[]{"newScenarioBtn", "saveScenarioBtn", "saveAsScenarioBtn", "duplicateScenarioBtn", "deleteScenarioBtn"}) {
                Button b = field(name);
                assertNotNull(b.getTooltip(), name + " tooltip");
                assertTrue(flatten(scenarios).contains(b), name + " inside the scenarios panel");
            }
            ComboBox<?> combo = field("scenarioCombo");
            assertNotNull(combo.getAccessibleText());
            List<String> bad = flatten(view).stream().filter(Labeled.class::isInstance).map(n -> ((Labeled) n).getText())
                    .filter(t -> t != null).map(String::toLowerCase)
                    .filter(s -> s.contains("ledger") || s.contains("replay") || s.contains("schema")).toList();
            assertTrue(bad.isEmpty(), bad.toString());
        });
    }
}

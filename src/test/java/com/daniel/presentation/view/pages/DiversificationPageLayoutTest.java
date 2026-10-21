package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
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
 * Diversificação (direction A): the wealth is the hero with the computed target beside it; the
 * calculation choice and the plan are one row that stacks; the current/target comparison is two
 * panels side by side only when wide (never two squeezed tables); the plan editor lives inside
 * the plan panel; suggestions are their own panel. Titles are sentence case; no implementation
 * vocabulary. The busy/atomic plan flows are covered by DiversificationPagePlanEditorBusyTest.
 */
class DiversificationPageLayoutTest {

    @TempDir
    Path tempDir;

    final Deque<Runnable> worker = new ArrayDeque<>();
    DiversificationPage page;
    Parent view;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("plans.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE allocation_plan (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, updated_at TEXT NOT NULL)");
            st.execute("CREATE TABLE allocation_item (plan_id INTEGER NOT NULL REFERENCES allocation_plan(id) ON DELETE CASCADE, "
                    + "category TEXT NOT NULL, weight_bps INTEGER NOT NULL CHECK (weight_bps BETWEEN 0 AND 10000), PRIMARY KEY (plan_id, category))");
            st.execute("CREATE TABLE allocation_selection (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
                    + "plan_id INTEGER REFERENCES allocation_plan(id) ON DELETE SET NULL)");
        }
        SavedAnalysisRepository plans = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        FxTestSupport.runAndWait(() -> {
            page = new DiversificationPage(FxStubs.emptyDaily(), plans, worker::add, Runnable::run);
            view = page.view();
        });
    }

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        if (n instanceof TitledPane tp && tp.getContent() != null) out.addAll(flatten(tp.getContent()));
        return out;
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = DiversificationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    Pane pageRoot() {
        return (Pane) ((ScrollPane) view).getContent();
    }

    static String title(Panel p) {
        return ((Label) p.lookup(".panel-title")).getText();
    }

    @Test
    void wealthIsTheHero_withTheComputedTargetBesideIt() throws Exception {
        FxTestSupport.runAndWait(() -> {
            HeroValue hero = field("hero");
            assertEquals("Patrimônio atual", hero.caption());
            assertTrue(pageRoot().getChildren().indexOf(hero) >= 0 || flatten(pageRoot().getChildren().get(1)).contains(hero),
                    "hero right under the header");
            Label target = field("impliedTargetLabel");
            assertTrue(flatten(hero).contains(target), "the computed target is a trailing value of the hero, not a second KPI card");
        });
    }

    @Test
    void controlsAndComparisonReflow_andTablesAreNeverSqueezedSideBySide() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AdaptivePair controls = field("controlsPair");
            AdaptivePair comparison = field("comparisonPair");
            page.onBreakpoint(Breakpoint.WIDE);
            assertTrue(controls.isSideBySide());
            assertTrue(comparison.isSideBySide());
            page.onBreakpoint(Breakpoint.MEDIUM);
            assertTrue(controls.isSideBySide(), "two small control panels fit at medium width");
            assertFalse(comparison.isSideBySide(), "two tables with three columns each do not fit at medium width");
            page.onBreakpoint(Breakpoint.NARROW);
            assertFalse(controls.isSideBySide());
            assertFalse(comparison.isSideBySide());
            TableView<?> current = field("currentTable");
            TableView<?> ideal = field("idealTable");
            assertTrue(flatten(comparison).contains(current) && flatten(comparison).contains(ideal));
            assertNull(pageRoot().lookup(".comparison-arrow"), "no decorative arrow that has nowhere to go when stacked");
        });
    }

    @Test
    void panelsHaveSentenceCaseTitles_andTheTargetPanelNamesThePlan() throws Exception {
        FxTestSupport.runAndWait(() -> {
            List<Panel> panels = flatten(view).stream().filter(Panel.class::isInstance).map(Panel.class::cast).toList();
            List<String> titles = panels.stream().map(DiversificationPageLayoutTest::title).toList();
            for (String t : titles) assertNotEquals(t.toUpperCase(), t, "no uppercase micro-type titles: " + t);
            assertTrue(titles.stream().anyMatch(t -> t.startsWith("Distribuição atual")), titles.toString());
            Panel targetPanel = field("idealPanel");
            assertTrue(title(targetPanel).contains("ARCA"), "the target panel says which plan it follows: " + title(targetPanel));
            assertTrue(titles.stream().anyMatch(t -> t.startsWith("Sugestões")), titles.toString());
            List<String> bad = flatten(view).stream().filter(Labeled.class::isInstance).map(n -> ((Labeled) n).getText())
                    .filter(t -> t != null).map(String::toLowerCase)
                    .filter(s -> s.contains("ledger") || s.contains("replay") || s.contains("schema")).toList();
            assertTrue(bad.isEmpty(), bad.toString());
        });
    }

    @Test
    void planEditorOpensInsideThePlanPanel_andSuggestionsHaveTheirOwnPanel() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Panel planPanel = field("planPanel");
            VBox editor = field("planEditorBox");
            Button newPlan = field("newPlanBtn");
            assertTrue(flatten(planPanel).contains(editor), "editor is part of the plan panel, not a floating block");
            assertFalse(editor.isVisible());
            newPlan.fire();
            assertTrue(editor.isVisible());
            Button save = field("savePlanBtn");
            Button cancel = field("cancelPlanBtn");
            assertNotNull(save.getTooltip());
            assertNotNull(cancel.getTooltip());
            Panel suggestions = field("suggestionsPanel");
            TableView<?> table = field("suggestionsTable");
            assertTrue(flatten(suggestions).contains(table));
            Label total = field("totalAporteLabel");
            assertTrue(flatten(suggestions).contains(total));
        });
    }
}

package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
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
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
    SavedAnalysisRepository plans;
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
        plans = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
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
            // Changed with evidence (candidate-retomada-r1 at 960×720): at medium the 0.42 share left
            // the calculation options ~250 px and cut them ("Rebalancear por ...", "Patrimôn...").
            // The controls stack until wide; DiversificationPageReadabilityTest
            // #calculationTypeOptionsAreWholeAtEveryWidth measures the rendered text.
            assertFalse(controls.isSideBySide(), "the control panels stack at medium width");
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

    /**
     * R4-02: the detailed tables measure their columns at the end of their own layout. As in the app, the page
     * is laid out empty and the data arrive afterwards: the pass that receives them measures before the rows
     * exist (Valor and % on their 72 px floors), the rows are created later in that same pass, and the layout
     * they request never reaches the table — its parents are laying out — so it stayed cut, closed or open,
     * until a resize ("R$ 1…" and "100,…" in the r4 capture at 150 %; the CP3 probe reproduced it at 100 % and
     * 150 % on the financial fixture, with and without the R3-10 max height, which is not the cause). Within a
     * pulse of opening "Ver tabelas detalhadas", "R$ 150,00" and "100,0%" are whole.
     */
    @Test
    void detailedTablesFitTheirRowsWhenTheDataArriveAfterTheFirstLayout() throws Exception {
        var types = List.of(new InvestmentType(1, "Ativo sintético TEST3", "ACOES", "ALTA", LocalDate.of(2026, 10, 15), null,
                new BigDecimal("150"), "ACAO", null, null, "TEST3", new BigDecimal("25"), 6, null));
        FxTestSupport.runAndWait(() -> {
            page = new DiversificationPage(FxStubs.dailyWithTypes(types), plans, worker::add, Runnable::run);
            view = page.view();
            com.daniel.presentation.view.util.UiTheme.applyFont(view);
            var scene = new javafx.scene.Scene(view, 1020, 640);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            page.onBreakpoint(Breakpoint.of(1020));
            ((javafx.scene.layout.Region) view).resize(1020, 640);
            view.applyCss();
            view.layout(); // shown empty, as when the page opens
            page.onShow();
            while (!worker.isEmpty()) worker.removeFirst().run(); // the data arrive
            TableView<?> current = field("currentTable");
            assertEquals(1, current.getItems().size(), "one category held: Ações");
            view.applyCss();
            view.layout(); // the pulse that receives them, tables still closed
        });
        FxTestSupport.runAndWait(() -> {
            view.applyCss();
            view.layout();
            TitledPane tables = (TitledPane) pageRoot().lookup(".allocation-tables");
            assertFalse(tables.isExpanded(), "the detailed tables start closed");
            tables.setExpanded(true);
            view.applyCss();
            view.layout(); // the pulse that opens them
        });
        FxTestSupport.runAndWait(() -> {
            view.applyCss();
            view.layout(); // the next pulse: nothing resized
            assertEquals(List.of(), cutAmounts(), "a pulse after opening");
        });
        FxTestSupport.runAndWait(() -> {
            for (int i = 0; i < 3; i++) { view.applyCss(); view.layout(); }
            assertEquals(List.of(), cutAmounts(), "settled");
        });
    }

    /** What the current table's rows fail to show whole, plus a note when the expected amounts are missing. */
    List<String> cutAmounts() throws Exception {
        TableView<?> current = field("currentTable");
        List<String> cut = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (Node rowNode : current.lookupAll(".table-row-cell")) {
            if (!(rowNode instanceof TableRow<?> row) || row.isEmpty() || !row.isVisible()) continue;
            for (Node n : flatten(row)) {
                if (!(n instanceof Labeled l) || !n.isVisible() || l.getText() == null || l.getText().isBlank()) continue;
                for (Node c : l.getChildrenUnmodifiable()) {
                    if (!(c instanceof javafx.scene.text.Text s)) continue;
                    shown.add(s.getText());
                    if (!l.getText().equals(s.getText())) cut.add("'" + s.getText() + "' instead of '" + l.getText() + "'");
                }
            }
        }
        if (shown.stream().noneMatch(s -> s.contains("150,"))) cut.add("no R$ 150,00 among " + shown);
        if (shown.stream().noneMatch(s -> s.startsWith("100,"))) cut.add("no 100,0% among " + shown);
        return cut;
    }
}

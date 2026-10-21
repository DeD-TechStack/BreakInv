package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.Flow;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.repository.IFlowRepository;
import com.daniel.core.domain.repository.IInvestmentTypeRepository;
import com.daniel.core.domain.repository.ISnapshotRepository;
import com.daniel.core.domain.repository.ITransactionRepository;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diversificação — review finding G3, from the GUI evidence
 * {@code evidencias/gui/v3-financial743-primary/03-1280x800.json}: "Fundos Imobiliários" and
 * "Fundos de Investimento" rendered as "Fundos Imobil..." / "Fundos de Inv..." in the two
 * comparison tables, and the wealth hero (R$ 950 including R$ 800 of cash) sat over a table that
 * distributes only the R$ 150 of positions, with nothing saying so.
 *
 * <p>No calculator is touched: the figures stay the same; the page says what they are based on,
 * and long category names remain fully readable (whole text, or tooltip with the whole text).
 */
class DiversificationPageReadabilityTest {

    @TempDir
    Path tempDir;

    final Deque<Runnable> worker = new ArrayDeque<>();
    DiversificationPage page;
    Parent view;
    SavedAnalysisRepository plans;

    /** Legacy wiring (no ledger): one stock worth R$ 150 plus R$ 800 of cash → wealth R$ 950. */
    static DailyTrackingUseCase financialDaily() {
        return financialDaily(80_000);
    }

    static DailyTrackingUseCase financialDaily(long cashCents) {
        InvestmentType stock = new InvestmentType(7, "Ativo fictício E2", "ACOES", "ALTA", LocalDate.of(2026, 10, 15),
                null, new BigDecimal("120.00"), "ACAO", null, null, "FICT1", new BigDecimal("20.00"), 6, null);
        // a registration in a long-named category with no value yet: it lists at R$ 0,00 and keeps the total at 150
        InvestmentType fii = new InvestmentType(8, "FII longo", "FUNDOS_IMOBILIARIOS", "MEDIA", LocalDate.of(2026, 10, 15),
                null, null, "POS_FIXADO", null, null, null, null, null, null);
        IInvestmentTypeRepository types = new IInvestmentTypeRepository() {
            @Override public List<InvestmentType> listAll() { return List.of(stock, fii); }
            @Override public void save(String name) {}
            @Override public void rename(int id, String newName) {}
            @Override public void delete(int id) {}
        };
        IFlowRepository flows = new IFlowRepository() {
            @Override public List<Flow> listForDate(LocalDate date) { return List.of(); }
            @Override public void delete(long id) {}
            @Override public long create(Flow flow) { return 0; }
        };
        ISnapshotRepository snapshots = new ISnapshotRepository() {
            @Override public long getCash(LocalDate date) { return cashCents; }
            @Override public long getCashOnOrBefore(LocalDate date) { return cashCents; }
            @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) { return Map.of(); }
            @Override public Map<String, Long> seriesForInvestiments(long id) { return Map.of(); }
        };
        ITransactionRepository txs = new ITransactionRepository() {
            @Override public long insert(Transaction transaction) { return 0; }
            @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) { return List.of(); }
        };
        return new DailyTrackingUseCase(flows, types, snapshots, txs, ticker -> "FICT1".equals(ticker) ? 25.0 : null);
    }

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
            page = new DiversificationPage(financialDaily(), plans, worker::add, Runnable::run);
            view = page.view();
        });
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = DiversificationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    static String rendered(Labeled label) {
        Text t = (Text) label.lookup(".text");
        return t == null ? null : t.getText();
    }

    /** Wide layout as in the 1280×800 evidence: 1060 px of content. */
    void layOutWide() {
        Scene scene = new Scene(view, 1060, 800);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        page.onBreakpoint(Breakpoint.WIDE);
        ((Region) view).resize(1060, 800);
        view.applyCss();
        view.layout();
        view.applyCss();
        view.layout();
    }

    @Test
    void comparisonUsesPortuguesePercentagesAndOmitsUnallocatedEmptyCategories() {
        FxTestSupport.runAndWait(() -> {
            var previous = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.US);
                page.onShow(); drain(); layOutWide();
                var rows = view.lookupAll(".allocation-comparison-row");
                var labels = rows.stream().flatMap(n -> n.lookupAll(".label").stream()).map(n -> ((Label)n).getText()).toList();
                assertFalse(labels.stream().anyMatch(s -> s.matches("\\d+\\.\\d+%")), labels.toString());
                assertTrue(labels.stream().anyMatch(s -> s.startsWith("Aporte sugerido")), "show the computed action, not infer it from incomparable percentage bars");
                assertFalse(rows.stream().anyMatch(n -> n.getUserData() == CategoryEnum.FUNDOS), "zero current / zero target does not need a summary row");
            } finally { java.util.Locale.setDefault(previous); }
        });
    }

    @Test
    void comparisonRowsApplyTheirSpacingAndNeverSplitALargeMoneyAmount() {
        FxTestSupport.runAndWait(() -> {
            page.onShow(); drain(); layOutWide();
            Node row = view.lookup(".allocation-comparison-row");
            assertNotNull(row);
            var content = ((javafx.scene.Parent)row).getChildrenUnmodifiable().getFirst();
            assertTrue(content.getBoundsInParent().getMinY() >= 12, "row padding must participate in layout");
            Label value = (Label)row.lookup(".allocation-value");
            value.setText("R$ 12.345.678,90");
            page.onBreakpoint(Breakpoint.NARROW); ((Region)view).resize(380, 800);
            for(int i=0;i<6;i++){view.applyCss();view.layout();}
            assertEquals(value.getText(), rendered(value));
            assertTrue(value.getHeight() < value.getFont().getSize()*1.8, "money must stay on one line; columns should stack");
        });
    }

    @Test
    void categoryComparisonKeepsCurrentAndTargetAdjacentWithTheirOwnBases() {
        FxTestSupport.runAndWait(() -> {
            page.onShow(); drain();
            layOutWide();
            var rows = view.lookupAll(".allocation-comparison-row");
            assertFalse(rows.isEmpty(), "current and target need a shared category row, not separate tables");
            Node stock = rows.stream().filter(n -> n.getUserData() == CategoryEnum.ACOES).findFirst().orElseThrow();
            var labels = stock.lookupAll(".label").stream().map(n -> ((Label)n).getText().replace('\u00a0', ' ')).toList();
            assertTrue(labels.contains("Atual · posições"), labels.toString());
            assertTrue(labels.contains("Alvo · base ideal"), labels.toString());
            assertTrue(labels.contains("R$ 150,00"), labels.toString());
            assertEquals(2, stock.lookupAll(".progress-bar").size());
            Label basis = field("distributionBasisLabel");
            assertTrue(basis.isVisible() && basis.getText().contains("sem caixa"));
            for (int width : new int[]{1060, 380, 740, 1060}) {
                page.onBreakpoint(width < 600 ? Breakpoint.NARROW : width < 900 ? Breakpoint.MEDIUM : Breakpoint.WIDE);
                ((Region)view).resize(width, 800);
                for (int i=0;i<4;i++) { view.applyCss(); view.layout(); }
                for (Node n : view.lookupAll(".allocation-comparison-row")) {
                    assertTrue(n.getLayoutBounds().getWidth() <= width, "category row overflows at " + width);
                    for (Node value : n.lookupAll(".allocation-value")) {
                        Label label = (Label)value;
                        assertEquals(label.getText(), rendered(label), "numeric value clipped at " + width);
                    }
                }
            }
        });
    }

    /**
     * A-V3: both bars of a category row share one base, the ideal patrimony, so a category that
     * still needs a contribution never reads as over target. Ações holds R$ 150 of an ideal R$ 285
     * (30% of R$ 950) and the suggestion is to contribute R$ 135: its current bar must be the
     * shorter one, and both percentages name that same base.
     */
    @Test
    void comparisonBarsShareTheIdealBaseSoAPendingContributionNeverReadsAsExcess() {
        FxTestSupport.runAndWait(() -> {
            page.onShow(); drain(); layOutWide();
            Node stock = view.lookupAll(".allocation-comparison-row").stream()
                    .filter(n -> n.getUserData() == CategoryEnum.ACOES).findFirst().orElseThrow();
            var current = (javafx.scene.control.ProgressBar) stock.lookup(".allocation-current").lookup(".progress-bar");
            var target = (javafx.scene.control.ProgressBar) stock.lookup(".allocation-target").lookup(".progress-bar");
            assertTrue(current.getProgress() < target.getProgress(),
                    "R$ 150 against an ideal R$ 285: current " + current.getProgress() + " vs target " + target.getProgress());
            assertEquals(150.0 / 950.0, current.getProgress(), 0.002, "current bar on the ideal base");
            assertEquals(0.30, target.getProgress(), 0.002, "target bar on the same base");
            var labels = stock.lookupAll(".label").stream().map(n -> ((Label) n).getText().replace('\u00a0', ' ')).toList();
            assertTrue(labels.contains("15,8% da base ideal"), labels.toString());
            assertTrue(labels.contains("30,0% da base ideal"), labels.toString());
            assertTrue(labels.stream().anyMatch(s -> s.startsWith("Aporte sugerido · R$ 135,00")), labels.toString());
            Parent summary = field("comparisonSummary");
            assertTrue(summary.lookupAll(".label").stream().map(n -> ((Label) n).getText()).anyMatch(s -> s.contains("mesma base")),
                    "the panel says both bars share one base");
        });
    }

    /** Large synthetic amounts in the long-named categories (r1 populated: "R$ 20.226.800,..." in a 433 px table). */
    static DailyTrackingUseCase largeDaily() {
        InvestmentType stock = new InvestmentType(7, "Ativo sintético 01", "ACOES", "ALTA", LocalDate.of(2026, 7, 1),
                null, new BigDecimal("20000000.00"), "ACAO", null, null, "FICT1", new BigDecimal("20.00"), 1_000_000, null);
        InvestmentType fii = new InvestmentType(8, "Ativo sintético 02", "FUNDOS_IMOBILIARIOS", "MEDIA", LocalDate.of(2026, 7, 1),
                null, new BigDecimal("8000000.00"), "ACAO", null, null, "FICT2", new BigDecimal("80.00"), 100_000, null);
        InvestmentType fund = new InvestmentType(9, "Ativo sintético 03", "FUNDOS", "MEDIA", LocalDate.of(2026, 7, 1),
                null, new BigDecimal("6000000.00"), "ACAO", null, null, "FICT3", new BigDecimal("60.00"), 100_000, null);
        IInvestmentTypeRepository types = new IInvestmentTypeRepository() {
            @Override public List<InvestmentType> listAll() { return List.of(stock, fii, fund); }
            @Override public void save(String name) {}
            @Override public void rename(int id, String newName) {}
            @Override public void delete(int id) {}
        };
        IFlowRepository flows = new IFlowRepository() {
            @Override public List<Flow> listForDate(LocalDate date) { return List.of(); }
            @Override public void delete(long id) {}
            @Override public long create(Flow flow) { return 0; }
        };
        ISnapshotRepository snapshots = new ISnapshotRepository() {
            @Override public long getCash(LocalDate date) { return 0; }
            @Override public long getCashOnOrBefore(LocalDate date) { return 0; }
            @Override public Map<Long, Long> getAllInvestimentsForDate(LocalDate date) { return Map.of(); }
            @Override public Map<String, Long> seriesForInvestiments(long id) { return Map.of(); }
        };
        ITransactionRepository txs = new ITransactionRepository() {
            @Override public long insert(Transaction transaction) { return 0; }
            @Override public List<Transaction> listBetween(LocalDate start, LocalDate end) { return List.of(); }
        };
        Map<String, Double> quotes = Map.of("FICT1", 20.2268, "FICT2", 86.78195, "FICT3", 65.08646);
        return new DailyTrackingUseCase(flows, types, snapshots, txs, quotes::get);
    }

    static List<Node> nodes(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(nodes(c));
        return out;
    }

    /**
     * r1 at 960×720 (740 px of page): the calculation-type options were cut to "Rebalancear por ..."
     * and "Patrimôn..." with the controls side by side. Every option and text in that panel is whole
     * at every width, down to the expanded-sidebar minimum.
     */
    @Test
    void calculationTypeOptionsAreWholeAtEveryWidth() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Scene scene = new Scene(view, 1060, 1200);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            ToggleButton first = field("rebalanceByContributionRadio");
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 960, 740, 700, 640, 544, 380, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((Region) view).resize(width, 1200);
                for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
                Node panel = first;
                while (panel != null && !(panel instanceof com.daniel.presentation.view.components.Panel)) panel = panel.getParent();
                assertNotNull(panel);
                FxTestSupport.cutTexts((Parent) panel).forEach(c -> problems.add(width + ": " + c));
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * r1: in the detailed tables (three equal columns) "R$ 20.226.800,..." and "Fundos Imo..." were
     * cut in 433 px. Amounts and percentages are never cut; a category name is whole whenever the
     * table has room for it (only at the expanded-sidebar minimum may it shorten, with the whole name
     * in its tooltip); returning to wide restores the same columns.
     */
    @Test
    void detailedTablesKeepEveryAmountWholeAndCategoriesWholeWhenThereIsRoom() throws Exception {
        FxTestSupport.runAndWait(() -> {
            DiversificationPage big = new DiversificationPage(largeDaily(), plans, worker::add, Runnable::run);
            Parent bigView = big.view();
            Scene scene = new Scene(bigView, 1060, 1600);
            scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            big.onShow();
            drain();
            drain();
            bigView.applyCss();
            TitledPane tables = (TitledPane) bigView.lookup(".allocation-tables");
            tables.setExpanded(true);
            List<String> problems = new ArrayList<>();
            List<Double> firstWide = null;
            for (double width : new double[]{1060, 740, 544, 380, 1060}) {
                big.onBreakpoint(Breakpoint.of(width));
                ((Region) bigView).resize(width, 1600);
                for (int i = 0; i < 8; i++) { bigView.applyCss(); bigView.layout(); }
                List<Double> widths = new ArrayList<>();
                for (String name : List.of("currentTable", "idealTable", "suggestionsTable")) {
                    Field f = DiversificationPage.class.getDeclaredField(name);
                    f.setAccessible(true);
                    TableView<?> table = (TableView<?>) f.get(big);
                    assertFalse(table.getItems().isEmpty(), name + " has rows");
                    for (TableColumn<?, ?> c : table.getColumns()) widths.add((double) Math.round(c.getWidth()));
                    for (Node n : nodes(table)) {
                        if (!(n instanceof Labeled l) || !l.isVisible() || l.getText() == null || l.getText().isBlank()) continue;
                        String shown = rendered(l);
                        if (shown == null || shown.equals(l.getText())) continue;
                        boolean amount = l.getText().contains("R$") || l.getText().contains("%");
                        if (amount || width > 380 || l.getTooltip() == null)
                            problems.add(width + " " + name + ": '" + l.getText() + "' -> '" + shown + "'");
                    }
                }
                if (firstWide == null) firstWide = widths;
                else if (width == 1060 && !widths.equals(firstWide))
                    problems.add("back to wide: columns " + widths + " differ from the first wide " + firstWide);
            }
            assertEquals(List.of(), problems);
        });
    }

    // ── R2: what the candidate-retomada-r2 review reproved ──────────────────

    DiversificationPage bigPage;
    Parent bigView;

    void openBigTables() {
        bigPage = new DiversificationPage(largeDaily(), plans, worker::add, Runnable::run);
        bigView = bigPage.view();
        Scene scene = new Scene(bigView, 1060, 1600);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        bigPage.onShow();
        drain();
        drain();
        bigView.applyCss();
        ((TitledPane) bigView.lookup(".allocation-tables")).setExpanded(true);
    }

    void layOutBig(double width) {
        bigPage.onBreakpoint(Breakpoint.of(width));
        ((Region) bigView).resize(width, 1600);
        for (int i = 0; i < 8; i++) { bigView.applyCss(); bigView.layout(); }
    }

    TableView<?> bigTable(String name) throws Exception {
        Field f = DiversificationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (TableView<?>) f.get(bigPage);
    }

    /** R2-04 (A-L2): the % bars of the detailed tables were drawn only at 536 px — 0 px tall at 740 and 1052. */
    @Test
    void detailedTablesDrawEveryPercentageBarAtEveryWidth() throws Exception {
        FxTestSupport.runAndWait(() -> {
            openBigTables();
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 544, 380, 1060}) {
                layOutBig(width);
                for (String name : List.of("currentTable", "idealTable")) {
                    TableView<?> table = bigTable(name);
                    int bars = 0;
                    for (Node n : nodes(table)) {
                        if (!(n instanceof javafx.scene.control.ProgressBar bar) || !bar.isVisible()) continue;
                        bars++;
                        Node cell = bar;
                        while (cell != null && !(cell instanceof TableCell<?, ?>)) cell = cell.getParent();
                        var b = bar.localToScene(bar.getLayoutBounds());
                        var c = cell.localToScene(cell.getLayoutBounds());
                        if (b.getHeight() < 3 || b.getWidth() < 30 || b.getMaxY() > c.getMaxY() + 0.5)
                            problems.add(width + " " + name + ": bar " + Math.round(b.getWidth()) + "×" + b.getHeight()
                                    + " in a cell " + Math.round(c.getWidth()) + "×" + c.getHeight());
                    }
                    if (bars < table.getItems().size()) problems.add(width + " " + name + ": " + bars + " bars for " + table.getItems().size() + " rows");
                    for (Node n : nodes(table)) { // a fixed row size must not squeeze any cell content
                        if (!(n instanceof Label l) || !l.isVisible() || l.getText() == null) continue;
                        Node cell = l;
                        while (cell != null && !(cell instanceof TableCell<?, ?>)) cell = cell.getParent();
                        if (cell == null) continue;
                        var lb = l.localToScene(l.getLayoutBounds());
                        var cb = cell.localToScene(cell.getLayoutBounds());
                        if (l.getHeight() + 0.5 < l.prefHeight(l.getWidth()) || lb.getMinY() < cb.getMinY() - 0.5 || lb.getMaxY() > cb.getMaxY() + 0.5)
                            problems.add(width + " " + name + ": '" + l.getText() + "' " + l.getHeight() + " of " + l.prefHeight(l.getWidth())
                                    + " at y " + Math.round(lb.getMinY() - cb.getMinY()) + " in a " + cb.getHeight() + " cell");
                    }
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * R3-10: side by side, "Distribuição atual" stretched to the height of the taller target panel and
     * left ~270 px of empty surface under its table (financial and populated). Each detailed panel
     * keeps the height of its own content, side by side and stacked.
     */
    @Test
    void detailedPanelsKeepTheirOwnHeightSideBySide() throws Exception {
        FxTestSupport.runAndWait(() -> {
            openBigTables();
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 1060}) {
                layOutBig(width);
                for (String name : List.of("currentTable", "idealTable")) {
                    Node panel = bigTable(name);
                    while (panel != null && !panel.getStyleClass().contains("panel-block")) panel = panel.getParent();
                    assertNotNull(panel, name + " sits in a panel");
                    Region p = (Region) panel;
                    double own = p.prefHeight(p.getWidth());
                    if (p.getHeight() > own + 1)
                        problems.add(width + " " + name + ": panel " + Math.round(p.getHeight()) + " px for " + Math.round(own) + " px of content");
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /** R2-05: fixed 220/200 px left two-row tables half empty and hid the 4th row of a five-row table under a scroll. */
    @Test
    void detailedTablesTakeTheHeightOfTheirRows() throws Exception {
        FxTestSupport.runAndWait(() -> {
            openBigTables();
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 380, 1060}) {
                layOutBig(width);
                for (String name : List.of("currentTable", "idealTable", "suggestionsTable")) {
                    TableView<?> table = bigTable(name);
                    double lastRowBottom = 0, rowHeight = 0;
                    for (Node n : table.lookupAll(".table-row-cell")) {
                        if (n instanceof TableRow<?> row && !row.isEmpty() && row.isVisible()) {
                            lastRowBottom = Math.max(lastRowBottom, table.sceneToLocal(row.localToScene(row.getLayoutBounds())).getMaxY());
                            rowHeight = Math.max(rowHeight, row.getHeight());
                        }
                    }
                    double inner = table.getHeight() - table.snappedBottomInset();
                    boolean vbar = table.lookupAll(".scroll-bar").stream().anyMatch(s -> s instanceof ScrollBar sb
                            && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL && sb.isVisible());
                    boolean hbar = table.lookupAll(".scroll-bar").stream().anyMatch(s -> s instanceof ScrollBar sb
                            && sb.getOrientation() == javafx.geometry.Orientation.HORIZONTAL && sb.isVisible());
                    double columns = table.getColumns().stream().mapToDouble(TableColumn::getWidth).sum();
                    if (vbar) problems.add(width + " " + name + ": rows scroll inside the table (height " + table.getHeight()
                            + ", row " + table.getFixedCellSize() + "×" + table.getItems().size() + ", columns " + Math.round(columns)
                            + " in " + Math.round(table.getWidth()) + (hbar ? ", horizontal bar" : "") + ", header "
                            + ((Region) table.lookup(".column-header-background")).getHeight() + ", bars " + table.lookupAll(".scroll-bar").stream()
                            .map(s -> (ScrollBar) s).filter(ScrollBar::isVisible).map(s -> s.getOrientation() + " " + s.getWidth() + "×" + s.getHeight()
                                    + " pref " + s.prefHeight(-1) + " at " + s.getLayoutY()).toList()
                            + ", flow " + ((Region) table.lookup(".virtual-flow")).getHeight() + " position "
                            + ((javafx.scene.control.skin.VirtualFlow<?>) table.lookup(".virtual-flow")).getPosition() + " cells "
                            + ((javafx.scene.control.skin.VirtualFlow<?>) table.lookup(".virtual-flow")).getCellCount() + " last "
                            + java.util.Optional.ofNullable(((javafx.scene.control.skin.VirtualFlow<?>) table.lookup(".virtual-flow")).getLastVisibleCell())
                                    .map(c -> c.getIndex() + "@" + c.getLayoutY() + "+" + c.getHeight()).orElse("none") + ")");
                    if (inner - lastRowBottom > Math.max(8, rowHeight * 0.5))
                        problems.add(width + " " + name + ": " + Math.round(inner - lastRowBottom) + " px empty under " + table.getItems().size() + " rows");
                }
            }
            assertEquals(List.of(), problems);
        });
    }

    /**
     * R2-03: "Sugestões de aporte" listed only contributions above R$ 100 while the total and every
     * "Aporte sugerido" of Atual × alvo counted them all (financial: total R$ 800, rows R$ 610).
     */
    @Test
    void suggestionsListEveryContributionTheTotalCounts() {
        FxTestSupport.runAndWait(() -> {
            page.onShow(); drain(); layOutWide();
            TableView<?> table = field("suggestionsTable");
            Label total = field("totalAporteLabel");
            long rows = 0;
            for (Object item : table.getItems()) {
                var accessor = item.getClass().getDeclaredMethod("actionProperty");
                accessor.setAccessible(true);
                String action = ((javafx.beans.property.StringProperty) accessor.invoke(item)).get();
                var m = java.util.regex.Pattern.compile("R\\$\\s*([0-9.]+,[0-9]{2})").matcher(action.replace('\u00a0', ' '));
                if (m.find()) rows += Long.parseLong(m.group(1).replace(".", "").replace(",", ""));
            }
            var t = java.util.regex.Pattern.compile("R\\$\\s*([0-9.]+,[0-9]{2})").matcher(total.getText().replace('\u00a0', ' '));
            assertTrue(t.find(), total.getText());
            assertEquals(Long.parseLong(t.group(1).replace(".", "").replace(",", "")), rows, "rows add up to the total");
        });
    }

    /** A-V3 (review suggestion 3): each bar's track was as long as its own text, so lengths did not compare. */
    @Test
    void comparisonBarsShareOneTrackAtEveryWidth() {
        FxTestSupport.runAndWait(() -> {
            page.onShow(); drain();
            Scene scene = view.getScene() != null ? view.getScene() : new Scene(view, 1060, 1200);
            if (scene.getStylesheets().isEmpty()) scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            List<String> problems = new ArrayList<>();
            for (double width : new double[]{1060, 740, 544, 380, 1060}) {
                page.onBreakpoint(Breakpoint.of(width));
                ((Region) view).resize(width, 1200);
                for (int i = 0; i < 6; i++) { view.applyCss(); view.layout(); }
                List<Double> tracks = view.lookupAll(".allocation-comparison-row").stream()
                        .flatMap(r -> r.lookupAll(".progress-bar").stream())
                        .map(b -> (double) Math.round(((Region) b).getWidth())).toList();
                if (tracks.isEmpty() || new HashSet<>(tracks).size() != 1 || tracks.get(0) < 100)
                    problems.add(width + ": tracks " + tracks);
            }
            assertEquals(List.of(), problems);
        });
    }

    @Test
    void longCategoryNames_areReadableInFull_inTheComparisonTables() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.onShow();
            drain();            // plans reload → profile → refreshData (worker) → tables
            drain();
            layOutWide();
            TableView<?> ideal = field("currentTable");
            assertEquals(2, ideal.getItems().size(), "stock + the long-named category");
            List<Labeled> labels = new ArrayList<>();
            for (Node n : ideal.lookupAll(".table-cell .label")) if (n instanceof Labeled l && l.getText() != null) labels.add(l);
            Labeled fii = labels.stream().filter(l -> l.getText().startsWith("Fundos Imobili")).findFirst().orElseThrow(
                    () -> new AssertionError("category label not found among " + labels.stream().map(Labeled::getText).toList()));
            String shown = rendered(fii);
            assertNotNull(shown);
            if (shown.contains("\u2026") || shown.contains("...")) {
                assertNotNull(fii.getTooltip(), "a cut name must offer the whole text on hover");
                assertEquals(fii.getText(), fii.getTooltip().getText());
            }
            // the category column must have room for the longest display name at the table font
            TableColumn<?, ?> catCol = ideal.getColumns().get(0);
            double needed = fii.prefWidth(-1) + 8 + 10 + 28; // text + badge dot + gaps + cell padding
            assertTrue(catCol.getWidth() + 0.5 >= Math.min(needed, 200) || fii.getTooltip() != null,
                    "category column wide enough (" + catCol.getWidth() + ") or tooltip offered");
        });
    }

    @Test
    void heroSaysWhatTheWealthIncludes_andTheTablesSayTheyDistributePositionsOnly() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.onShow();
            drain();
            drain();
            HeroValue hero = field("hero");
            assertEquals("R$ 950,00", hero.valueLabel().getText().replace('\u00a0', ' '), "same figure as before: positions + cash");
            Label context = (Label) hero.lookup(".hero-context");
            assertTrue(context.getText().toLowerCase().contains("caixa"), "the hero names the cash it includes: " + context.getText());
            assertTrue(context.getText().contains("800"), "and how much: " + context.getText());
            assertTrue(context.getText().contains("150"), "and the positions: " + context.getText());
            Label basis = field("distributionBasisLabel");
            assertTrue(basis.isVisible() && basis.isManaged());
            assertTrue(basis.getText().toLowerCase().contains("posições"), basis.getText());
            assertTrue(basis.getText().toLowerCase().contains("caixa"), "says the cash is not distributed: " + basis.getText());
            TableView<?> current = field("currentTable");
            assertEquals(2, current.getItems().size(), "the stock and the (valueless) FII category are listed; the cash is not");
        });
    }

    @Test
    void basisDistinguishesCurrentPositionsFromIdealAndSuggestionsInBothModes() {
        FxTestSupport.runAndWait(() -> {
            page.onShow(); drain();
            Label basis = field("distributionBasisLabel");
            assertFalse(basis.getText().contains("não entra na comparação nem nas sugestões"));
            assertTrue(basis.getText().contains("950"), "ideal base must include total wealth: " + basis.getText());
            assertTrue(basis.getText().contains("150"));
            assertTrue(basis.getText().contains("sem descontar o caixa"), basis.getText());
            TextField target = field("targetPatrimonyField");
            target.setText("1500,00");
            ToggleButton mode = field("rebalanceByTargetRadio");
            mode.fire(); drain();
            assertTrue(basis.getText().contains("1.500"), basis.getText());
            assertTrue(basis.getText().contains("alvo informado"), basis.getText());
            target.setText("900,00");
            page.onShow(); drain();
            assertTrue(basis.getText().contains("não supera"), "target <= wealth uses contribution fallback");
            assertTrue(basis.getText().contains("950"));
        });
    }

    @Test
    void unexplainedDifferenceIsNotClampedOrReportedAsConfirmedCash() {
        FxTestSupport.runAndWait(() -> {
            for (long cash : new long[]{0, -16_000}) {
                page = new DiversificationPage(financialDaily(cash), plans, worker::add, Runnable::run);
                page.onShow(); drain();
                HeroValue hero = field("hero");
                String context = ((Label) hero.lookup(".hero-context")).getText();
                assertTrue(context.contains("diferença"), context);
                assertTrue(context.contains("não confirma saldo de caixa"), context);
                if (cash < 0) assertTrue(context.contains(financialDaily(cash).brl(cash)), context);
                assertFalse(context.contains("+ caixa"), context);
            }
        });
    }

    @Test
    void savedPlanNameSurvivesReloadRenameAndResponsiveReparenting() {
        FxTestSupport.runAndWait(() -> {
            var weights = new EnumMap<CategoryEnum, Integer>(CategoryEnum.class);
            for (CategoryEnum category : CategoryEnum.values()) weights.put(category, 0);
            weights.put(CategoryEnum.ACOES, 10_000);
            var saved = plans.savePlanAndSelect(com.daniel.core.domain.entity.AllocationPlan.create("Plano persistido", weights));
            layOutWide();
            for (int i = 0; i < 10; i++) {
                saved = plans.savePlanAndSelect(saved.withName("Plano persistido " + i));
                for (Breakpoint bp : List.of(Breakpoint.WIDE, Breakpoint.NARROW, Breakpoint.MEDIUM, Breakpoint.WIDE)) {
                    page.onShow(); drain();
                    page.onBreakpoint(bp);
                    ((Region) view).resize(bp == Breakpoint.NARROW ? 540 : 1060, 600);
                    view.applyCss(); view.layout();
                    ComboBox<?> combo = field("planCombo");
                    ListCell<?> cell = combo.getButtonCell();
                    String diagnostic = "cycle=" + i + ", bp=" + bp + ", value=" + combo.getValue()
                            + ", selected=" + combo.getSelectionModel().getSelectedItem() + ", index="
                            + combo.getSelectionModel().getSelectedIndex() + ", item=" + cell.getItem()
                            + ", empty=" + cell.isEmpty() + ", text=" + cell.getText() + ", width=" + cell.getWidth();
                    assertEquals(saved.name(), combo.getValue().toString(), diagnostic);
                    assertEquals(saved.name(), cell.getText(), diagnostic);
                    assertFalse(cell.isEmpty(), diagnostic);
                }
            }
        });
    }

    @Test
    void planCombo_showsTheSelectedPlanName_throughAButtonCellThatNeverGoesBlank() throws Exception {
        FxTestSupport.runAndWait(() -> {
            page.onShow();
            drain();
            ComboBox<?> combo = field("planCombo");
            assertNotNull(combo.getButtonCell(), "an explicit button cell: the default one is skin-managed and went blank at 150 % in the evidence");
            ListCell<?> cell = combo.getButtonCell();
            layOutWide();
            assertNotNull(combo.getValue());
            assertEquals(combo.getValue().toString(), cell.getText(), "the visible text is the selected plan");
            assertTrue(cell.getText().contains("ARCA"), cell.getText());
        });
    }
}

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

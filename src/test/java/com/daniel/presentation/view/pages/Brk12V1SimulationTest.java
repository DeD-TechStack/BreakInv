package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.daniel.core.util.Money;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

import static com.daniel.presentation.view.pages.Brk12V1Support.NBSP;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertAxisTable;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertPtBr;
import static com.daniel.presentation.view.pages.Brk12V1Support.call;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static com.daniel.presentation.view.pages.Brk12V1Support.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1 on the Simulação, with the JVM in en-US: a profit of R$ 1.000 or more keeps its thousands dot (the
 * sentence used to be run through {@code replace('.', ',')} and read "R$ 1,350,00" and "a,a,"), the reference dates
 * read dd/MM/aaaa, the slider and the rates in pt-BR (V1:RF-01.1, .2, .5, .6 and RF-05.3).
 */
class Brk12V1SimulationTest {

    @TempDir
    Path tempDir;

    private final Deque<Runnable> worker = new ArrayDeque<>();
    private Locale previous;
    private SimulationPage page;

    @BeforeEach
    void setUp() throws SQLException {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("scen.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE simulation_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "parameters_json TEXT NOT NULL, calculation_version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
        }
        SavedAnalysisRepository scenarios = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        FxTestSupport.runAndWait(() -> page = new SimulationPage(scenarios, worker::add, Runnable::run));
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previous);
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    // ── forms ────────────────────────────────────────────────────────────────

    /** R$ 10.000,00 for 12 months at 13,5 % a.a.: a profit of R$ 1.350,00. */
    void fixedRate() throws Exception {
        ((TextField) field(page, "initialValueField")).setText("10000");
        ((TextField) field(page, "fixedRateField")).setText("13,5");
        ((Button) field(page, "calculateBtn")).fire();
    }

    /** 110 % of the CDI with the rates frozen on {@code referenceDate}: R$ 10.000,00 for 12 months. */
    void benchmark(String referenceDate) throws Exception {
        ((ToggleButton) field(page, "posfixadoBtn")).setSelected(true);
        set(page, "frozenRates", new RateSnapshot(new BigDecimal("0.1365"), new BigDecimal("0.15"), new BigDecimal("0.045"),
                "BCB/SGS", referenceDate));
        ComboBox<Object> mode = field(page, "rentabilityModeCombo");
        mode.setValue(mode.getItems().stream().filter(item -> item.toString().equals("BENCHMARK_PERCENT")).findFirst().orElseThrow());
        ((TextField) field(page, "initialValueField")).setText("10000");
        ((TextField) field(page, "benchmarkPercentField")).setText("110");
    }

    /** 1.000 shares bought at R$ 10,00 and worth R$ 15,00, R$ 2,50 of dividends each: a profit of R$ 7.500,00. */
    void stock() throws Exception {
        ((ToggleButton) field(page, "acaoBtn")).setSelected(true);
        ((TextField) field(page, "tickerField")).setText("FICT1");
        ((TextField) field(page, "purchasePriceField")).setText("R$ 10,00");
        ((TextField) field(page, "quantityField")).setText("1000");
        ((TextField) field(page, "currentPriceField")).setText("R$ 15,00");
        ((TextField) field(page, "dividendsField")).setText("R$ 2,50");
    }

    private Label secondary() throws Exception {
        HeroValue hero = field(page, "resultHero");
        return (Label) hero.lookup(".hero-secondary");
    }

    private Label context() throws Exception {
        HeroValue hero = field(page, "resultHero");
        return (Label) hero.lookup(".hero-context");
    }

    private Label value() throws Exception {
        HeroValue hero = field(page, "resultHero");
        return hero.valueLabel();
    }

    // ── V1:RF-05.3: thousands in the result, the sentence kept whole ─────────

    @Test
    void fixedRateProfitOfMoreThanAThousand_keepsItsThousandsDot_andTheSentenceItsDots() throws Exception {
        FxTestSupport.runAndWait(() -> {
            fixedRate();
            assertEquals("R$" + NBSP + "11.350,00", value().getText());
            assertEquals("Lucro: " + Money.centsToText(135_000) + " (13,5%)", secondary().getText());
            assertEquals("Lucro: R$" + NBSP + "1.350,00 (13,5%)", secondary().getText());
            assertEquals("Estimativa em 12 meses — taxa efetiva 13,50% a.a. Sem efeito na carteira.", context().getText());
            assertFalse(context().getText().contains("a,a,"), context().getText());
            assertTrue(context().getText().contains("a.a."), context().getText());
            assertTrue(context().getText().endsWith("carteira."), "the full stop stays: " + context().getText());
        });
    }

    @Test
    void benchmarkResult_namesItsRatesDateAsDayMonthYear_andKeepsTheDots() throws Exception {
        FxTestSupport.runAndWait(() -> {
            benchmark("2026-11-05");
            ((Button) field(page, "calculateBtn")).fire();
            assertEquals("Lucro: R$" + NBSP + "1.501,50 (15,0%)", secondary().getText());
            assertEquals("Estimativa em 12 meses — taxa efetiva 15,02% a.a. Taxas: BCB/SGS 05/11/2026. Sem efeito na carteira.",
                    context().getText());
            assertFalse(context().getText().contains("a,a,"), context().getText());
            assertFalse(context().getText().contains("2026-11-05"), context().getText());
        });
    }

    @Test
    void stockResult_keepsItsThousandsDots_andWritesTheQuantityWithThem() throws Exception {
        FxTestSupport.runAndWait(() -> {
            stock();
            ((Button) field(page, "calculateBtn")).fire();
            assertEquals("R$" + NBSP + "15.000,00", value().getText());
            assertEquals("Lucro total: " + Money.centsToText(750_000) + " (75,00%)", secondary().getText());
            assertEquals("Lucro total: R$" + NBSP + "7.500,00 (75,00%)", secondary().getText());
            assertEquals("Estimativa — investido " + Money.centsToText(1_000_000) + "; dividendos 1.000 × " + Money.centsToText(250)
                    + " = " + Money.centsToText(250_000) + ". Sem efeito na carteira.", context().getText());
        });
    }

    // ── V1:RF-01.5: every reference date as dd/MM/aaaa ───────────────────────

    @Test
    void ratesStatus_showsTheReferenceDateAsDayMonthYear_andNothingWhenThereIsNone() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Label status = field(page, "ratesStatusLabel");
            call(page, "updateRatesStatus", new Class<?>[]{String.class}, new Object[]{null});
            assertEquals("CDI 13,50% | SELIC 15,00% | IPCA 4,50% — origem: estimativa padrão", status.getText(), "no date, no parentheses");
            set(page, "frozenRates", new RateSnapshot(new BigDecimal("0.1365"), new BigDecimal("0.15"), new BigDecimal("0.045"),
                    "BCB/SGS", "2026-11-05"));
            call(page, "updateRatesStatus", new Class<?>[]{String.class}, new Object[]{null});
            assertEquals("CDI 13,65% | SELIC 15,00% | IPCA 4,50% — origem: BCB/SGS (05/11/2026)", status.getText());
        });
    }

    @Test
    void priceOrigin_showsTheReferenceDateAsDayMonthYear() throws Exception {
        FxTestSupport.runAndWait(() -> {
            set(page, "priceOrigin", "Brapi");
            set(page, "priceReferenceDate", "2026-11-05");
            call(page, "updatePriceOriginLabel", new Class<?>[]{});
            Label label = field(page, "priceOriginLabel");
            assertTrue(label.getText().startsWith("Preço e dividendos: Brapi (05/11/2026) — "), label.getText());
        });
    }

    @Test
    void savedScenarioStatus_showsItsRatesAndPriceDatesAsDayMonthYear() throws Exception {
        FxTestSupport.runAndWait(() -> {
            benchmark("2026-10-15");
            ((TextField) field(page, "scenarioNameField")).setText("CDI 110");
            ((Button) field(page, "saveScenarioBtn")).fire();
            drain();
            Label status = field(page, "scenarioStatusLabel");
            assertTrue(status.getText().contains("— taxas congeladas: BCB/SGS 15/10/2026."), status.getText());
            assertFalse(status.getText().contains("2026-10-15"), status.getText());
        });
        FxTestSupport.runAndWait(() -> {
            ((javafx.scene.control.Button) field(page, "newScenarioBtn")).fire();
            stock();
            set(page, "priceOrigin", "Brapi");
            set(page, "priceReferenceDate", "2026-10-15");
            ((TextField) field(page, "scenarioNameField")).setText("FICT1");
            ((Button) field(page, "saveScenarioBtn")).fire();
            drain();
            Label status = field(page, "scenarioStatusLabel");
            assertTrue(status.getText().contains("— preço: Brapi 15/10/2026."), status.getText());
            assertFalse(status.getText().contains("2026-10-15"), status.getText());
        });
    }

    // ── V1:RF-01.2: the slider's percentage ──────────────────────────────────

    @Test
    void sliderValue_isWrittenInPtBr_withoutAPlusAndWithoutASignAtZero() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Slider slider = field(page, "priceVariationSlider");
            Label label = field(page, "sliderValueLabel");
            String[][] cases = {{"12.5", "12,5%"}, {"-15", "-15,0%"}, {"-0.04", "0,0%"}, {"0.04", "0,0%"}, {"20", "20,0%"}, {"1", "1,0%"}};
            for (String[] c : cases) {
                slider.setValue(Double.parseDouble(c[0]));
                assertEquals(c[1], label.getText(), "slider at " + c[0]);
            }
        });
    }

    // ── V1:RF-01.6: the axis ─────────────────────────────────────────────────

    @Test
    void theValueAxisFollowsTheTableOfRf01_6() throws Exception {
        FxTestSupport.runAndWait(() -> {
            LineChart<Number, Number> chart = field(page, "projectionChart");
            assertAxisTable((NumberAxis) chart.getYAxis(), "Simulação eixo Y (Valor R$)");
        });
    }

    // ── DESIGN rule 6 ────────────────────────────────────────────────────────

    @Test
    void theResultKeepsEveryTextWhole_withThousandsInIt() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            HeroValue hero = field(page, "resultHero");
            benchmark("2026-11-05");
            ((Button) field(page, "calculateBtn")).fire();
            Brk12V1Support.settle(page.view(), 1280, 800);
            Brk12V1Support.assertNotCut(hero, "Simulação, 110 % do CDI");
            stock();
            ((Button) field(page, "calculateBtn")).fire();
            Brk12V1Support.settle(page.view(), 1280, 800);
            Brk12V1Support.assertNotCut(hero, "Simulação, ação");
        });
    }

    // ── V1:RF-05.2 ───────────────────────────────────────────────────────────

    @Test
    void noTextOfTheSimulationIsEnUsOrWithoutThousands() throws Exception {
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            benchmark("2026-11-05");
            ((Button) field(page, "calculateBtn")).fire();
            Brk12V1Support.settle(page.view(), 1280, 800);
            assertPtBr(page.view(), "Simulação, 110 % do CDI");
            stock();
            ((Button) field(page, "calculateBtn")).fire();
            Brk12V1Support.settle(page.view(), 1280, 800);
            assertPtBr(page.view(), "Simulação, ação");
        });
    }
}

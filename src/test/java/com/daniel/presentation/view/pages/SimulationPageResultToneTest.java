package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulação — review finding L3, reproduced from the GUI evidence
 * {@code evidencias/gui/v3-financial743-primary/simulation-negative.json}: R$ 1.000 at −5 % for
 * 12 months gives R$ 950,00 and "Lucro: -R$ 50,00 (-5,0%)", yet both the hero number and the
 * secondary line carried the {@code pos} class. The tone must follow the sign of the profit in
 * the fixed-rate / benchmark / hybrid branch exactly as it already does for stocks.
 */
class SimulationPageResultToneTest {

    @TempDir
    Path tempDir;

    final Deque<Runnable> worker = new ArrayDeque<>();
    SimulationPage page;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("scen.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE simulation_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "parameters_json TEXT NOT NULL, calculation_version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
        }
        SavedAnalysisRepository scenarios = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        FxTestSupport.runAndWait(() -> page = new SimulationPage(scenarios, worker::add, Runnable::run));
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) throws Exception {
        Field f = SimulationPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(page);
    }

    @Test
    void fixedRateLoss_isPaintedAsALoss_onTheHeroAndOnTheSecondaryLine() throws Exception {
        FxTestSupport.runAndWait(() -> {
            TextField initial = field("initialValueField");
            TextField rate = field("fixedRateField");
            initial.setText("1000");
            rate.setText("-5");
            ((Button) field("calculateBtn")).fire();
            HeroValue hero = field("resultHero");
            assertEquals("R$ 950,00", hero.valueLabel().getText().replace('\u00a0', ' '));
            Label secondary = (Label) hero.lookup(".hero-secondary");
            assertTrue(secondary.getText().contains("-"), secondary.getText());
            assertTrue(secondary.getText().startsWith("Prejuízo:"), secondary.getText());
            assertTrue(secondary.getStyleClass().contains("neg"), "loss in red: " + secondary.getStyleClass());
            assertFalse(secondary.getStyleClass().contains("pos"), secondary.getStyleClass().toString());
            assertTrue(hero.valueLabel().getStyleClass().contains("neg"), "hero number follows the sign: " + hero.valueLabel().getStyleClass());
            assertFalse(hero.valueLabel().getStyleClass().contains("pos"));
        });
    }

    @Test
    void fixedRateZeroIsNeutralRatherThanProfit() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ((TextField) field("initialValueField")).setText("1000");
            ((TextField) field("fixedRateField")).setText("0");
            ((Button) field("calculateBtn")).fire();
            HeroValue hero = field("resultHero");
            Label secondary = (Label) hero.lookup(".hero-secondary");
            assertTrue(secondary.getText().startsWith("Resultado:"), secondary.getText());
            for (Label label : java.util.List.of(secondary, hero.valueLabel())) {
                assertFalse(label.getStyleClass().contains("pos"));
                assertFalse(label.getStyleClass().contains("neg"));
            }
            assertEquals("R$ 1.000,00", hero.valueLabel().getText().replace('\u00a0', ' '));
        });
    }

    /**
     * A-V9: after a valid projection, a form error keeps the previous curve for reference, but it
     * must not read as the result of the current fields: it is labelled as the last valid
     * calculation and dimmed until a new valid calculation replaces it. No value changes.
     */
    @Test
    void fieldErrorKeepsThePreviousCurveOnlyAsLabelledLastValidCalculation() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ((TextField) field("initialValueField")).setText("1000");
            ((TextField) field("fixedRateField")).setText("10");
            ((Button) field("calculateBtn")).fire();
            javafx.scene.chart.LineChart<?, ?> chart = field("projectionChart");
            assertFalse(chart.getData().isEmpty(), "valid projection drawn");
            Label note = field("staleCurveNote");
            assertFalse(note.isVisible() || note.isManaged(), "a current curve carries no warning");
            javafx.scene.Node wrapper = chart.getParent();
            var dataBefore = new java.util.ArrayList<>(chart.getData().get(0).getData().stream().map(d -> d.getYValue()).toList());

            ((TextField) field("fixedRateField")).setText("abc");
            ((Button) field("calculateBtn")).fire();
            HeroValue hero = field("resultHero");
            assertEquals("Verifique os campos", hero.valueLabel().getText());
            assertEquals(dataBefore, chart.getData().get(0).getData().stream().map(d -> d.getYValue()).toList(),
                    "the previous curve is kept unchanged for reference");
            assertTrue(note.isVisible() && note.isManaged(), "the kept curve is labelled");
            assertTrue(note.getText().toLowerCase().contains("último cálculo válido"), note.getText());
            assertTrue(wrapper.getStyleClass().contains("projection-stale"), "the kept curve is dimmed: " + wrapper.getStyleClass());

            ((TextField) field("fixedRateField")).setText("12");
            ((Button) field("calculateBtn")).fire();
            assertFalse(note.isVisible() || note.isManaged(), "a new valid calculation is current again");
            assertFalse(wrapper.getStyleClass().contains("projection-stale"));
        });
    }

    @Test
    void fixedRateGain_staysPositive() throws Exception {
        FxTestSupport.runAndWait(() -> {
            ((TextField) field("initialValueField")).setText("1000");
            ((TextField) field("fixedRateField")).setText("10");
            ((Button) field("calculateBtn")).fire();
            HeroValue hero = field("resultHero");
            Label secondary = (Label) hero.lookup(".hero-secondary");
            assertTrue(secondary.getStyleClass().contains("pos"));
            assertTrue(hero.valueLabel().getStyleClass().contains("pos"));
        });
    }
}

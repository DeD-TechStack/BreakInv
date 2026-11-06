package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryResult;
import com.daniel.infrastructure.api.BrapiClient.FetchResult;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.util.Breakpoint;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.daniel.presentation.view.pages.Brk12V1Support.NBSP;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertAxisTable;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertPtBr;
import static com.daniel.presentation.view.pages.Brk12V1Support.assertTone;
import static com.daniel.presentation.view.pages.Brk12V1Support.field;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BRK-12 v1 on Análise de ativo, with the JVM in en-US: the quote, the day and year figures and the price axis in
 * pt-BR, the day's change and the 52-week change in the tone of their SHOWN percentage.
 */
class Brk12V1AnalysisTest {

    private final Deque<Runnable> worker = new ArrayDeque<>();
    private Locale previous;

    @BeforeEach
    void english() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previous);
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A price of R$ 1.234,56, a volume of 1,2 billion, a 12 % year; the day's change is the caller's. */
    static StockData quote(double change, double percent, double yearChange) {
        return new StockData("PETR4", null, "Petróleo Brasileiro S.A.", 1234.56, change, percent, 1200.0, 1250.0, 1190.0,
                1_234_500_000L, 1500.0, 900.0, 1100.0, "BRL", 8.5, yearChange, null);
    }

    /** Closes of R$ 22.500.000 and more: the axis has millions to write. */
    static List<HistoryPoint> points(int n) {
        List<HistoryPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new HistoryPoint(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(i), 22_500_000 + i * 250_000.0));
        return out;
    }

    AssetAnalysisPage page(StockData quote) {
        return new AssetAnalysisPage(ticker -> new FetchResult.Success<>(quote),
                (ticker, period) -> new HistoryResult(points(30), period.range, Set.of()),
                worker::add, Runnable::run);
    }

    @SuppressWarnings("unchecked")
    void search(AssetAnalysisPage page) throws Exception {
        ((ComboBox<Object>) (ComboBox<?>) field(page, "tickerCombo")).getEditor().setText("PETR4");
        ((Button) field(page, "searchBtn")).fire();
        drain();
    }

    private Label secondaryAfter(StockData quote) throws Exception {
        AssetAnalysisPage page = page(quote);
        page.renderQuote(quote);
        HeroValue hero = field(page, "hero");
        return (Label) hero.lookup(".hero-secondary");
    }

    // ── V1:RF-01 / RF-05.2 ───────────────────────────────────────────────────

    @Test
    void noTextOfTheAnalysisIsEnUsOrWithoutThousands() throws Exception {
        AssetAnalysisPage[] holder = new AssetAnalysisPage[1];
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = page(quote(12.34, 1.01, 0.12));
            holder[0] = page;
            Brk12V1Support.styled(page.view(), 1280, 800);
            page.onBreakpoint(Breakpoint.WIDE);
            search(page);
            Brk12V1Support.settle(page.view(), 1280, 800);
        });
        FxTestSupport.runAndWait(() -> {
            Brk12V1Support.settle(holder[0].view(), 1280, 800);
            assertPtBr(holder[0].view(), "Análise de ativo");
        });
    }

    @Test
    void thePriceAxisFollowsTheTableOfRf01_6() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = page(quote(0, 0, 0));
            NumberAxis axis = field(page, "yAxis");
            assertAxisTable(axis, "Análise yAxis (Preço R$)");
        });
    }

    @Test
    void theQuoteAndItsFiguresAreWrittenInPtBr() throws Exception {
        FxTestSupport.runAndWait(() -> {
            AssetAnalysisPage page = page(quote(12.34, 1.01, 0.12));
            page.renderQuote(quote(12.34, 1.01, 0.12));
            HeroValue hero = field(page, "hero");
            assertEquals("R$" + NBSP + "1.234,56", hero.valueLabel().getText());
            assertEquals("+ R$" + NBSP + "12,34 · +1,01% hoje", ((Label) hero.lookup(".hero-secondary")).getText());
            StatStrip day = field(page, "dayStrip");
            assertEquals("R$" + NBSP + "1.200,00", day.valueLabel("Abertura").getText());
            assertEquals("R$" + NBSP + "1.250,00", day.valueLabel("Máx. do dia").getText());
            assertEquals("R$" + NBSP + "1.190,00", day.valueLabel("Mín. do dia").getText());
            assertEquals("1.234,5M", day.valueLabel("Volume").getText());
            StatStrip year = field(page, "yearStrip");
            assertEquals("R$" + NBSP + "1.500,00", year.valueLabel("Máx. 52 sem.").getText());
            assertEquals("R$" + NBSP + "900,00", year.valueLabel("Mín. 52 sem.").getText());
            assertEquals("R$" + NBSP + "1.100,00", year.valueLabel("Média 200 dias").getText());
            assertEquals("+12,00%", year.valueLabel("Var. 52 sem.").getText());
            assertEquals("8,50%", year.valueLabel("Dividend yield").getText());
        });
    }

    // ── V1:RF-03 T9: the day's change follows the shown PERCENTAGE ───────────

    private record Day(String name, double change, double percent, String text, String tone) {}

    @Test
    void dayChange_toneFollowsTheShownPercentage_andZeroHasNoSign() throws Exception {
        List<Day> cases = List.of(
                new Day("gain", 0.48, 1.23, "+ R$" + NBSP + "0,48 · +1,23% hoje", "pos"),
                new Day("loss", -0.48, -1.23, "- R$" + NBSP + "0,48 · -1,23% hoje", "neg"),
                new Day("zero", 0.0, 0.0, "R$" + NBSP + "0,00 · 0,00% hoje", "muted"),
                new Day("0,004 %", 0.001, 0.004, "R$" + NBSP + "0,00 · 0,00% hoje", "muted"),
                new Day("-0,004 %", -0.001, -0.004, "R$" + NBSP + "0,00 · 0,00% hoje", "muted"),
                // the tone is the percentage's: R$ 0,01 is a gain, 0,004 % shows zero
                new Day("R$ up, % zero", 0.01, 0.004, "+ R$" + NBSP + "0,01 · 0,00% hoje", "muted"));
        FxTestSupport.runAndWait(() -> {
            for (Day c : cases) {
                Label secondary = secondaryAfter(quote(c.change(), c.percent(), 0.12));
                assertEquals(c.text(), secondary.getText(), c.name());
                assertTone(secondary, c.tone(), c.name());
            }
        });
    }

    // ── V1:RF-03 T10: "Var. 52 sem." follows the shown percentage; the dash is for a raw zero ──

    private record Year(String name, double change, String text, String tone) {}

    @Test
    void yearChange_toneFollowsTheShownPercentage_theDashIsOnlyForARawZero() throws Exception {
        List<Year> cases = List.of(
                new Year("gain", 0.12, "+12,00%", "pos"),
                new Year("loss", -0.05, "-5,00%", "neg"),
                new Year("raw zero", 0.0, "—", "muted"),
                new Year("0,004 %", 0.00004, "0,00%", "muted"),
                new Year("-0,004 %", -0.00004, "0,00%", "muted"));
        FxTestSupport.runAndWait(() -> {
            for (Year c : cases) {
                AssetAnalysisPage page = page(quote(0.48, 1.23, c.change()));
                page.renderQuote(quote(0.48, 1.23, c.change()));
                StatStrip year = field(page, "yearStrip");
                Label value = year.valueLabel("Var. 52 sem.");
                assertEquals(c.text(), value.getText(), c.name());
                assertTone(value, c.tone(), c.name());
            }
        });
    }
}

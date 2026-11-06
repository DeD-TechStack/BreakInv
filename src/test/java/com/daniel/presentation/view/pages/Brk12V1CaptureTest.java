package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiTheme;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-12 v1, RN-04 item 1: writes PNGs of the areas whose numbers and tones this unit changes, by
 * {@code snapshot} of the node, at scales 1.0 and 1.5, with the JVM in en-US. The pages are the root of a 1280×800
 * {@link Scene} with the production {@code /styles/app.css} and the font of the app, without any {@code Stage}.
 *
 * <p>It does NOT assert any format: it has to pass on the code before the unit (the "antes" run) and on the code after
 * it (the "depois" run), over the SAME fixtures, so the two sets of images compare. Do not change the fixtures. Each
 * run writes into a new folder {@code <java.io.tmpdir>/brk12-v1/<yyyyMMdd-HHmmss-SSS>/}; earlier folders are never
 * touched. The images are written with {@link ImageIO} from the pixels ({@code javafx-swing} is not a dependency).</p>
 */
class Brk12V1CaptureTest {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    @TempDir
    Path tempDir;

    private static Path folder;
    private Locale previousLocale;

    /** One new folder per run of the suite, shared by the three tests; earlier folders are never touched. */
    @BeforeAll
    static void newFolder() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "brk12-v1");
        Files.createDirectories(root);
        String stamp = LocalDateTime.now().format(STAMP);
        Path candidate = root.resolve(stamp);
        for (int n = 2; Files.exists(candidate); n++) candidate = root.resolve(stamp + "-" + n);
        folder = Files.createDirectory(candidate);
    }

    @BeforeEach
    void setUp() {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(previousLocale);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner, value);
    }

    /** The production stylesheet and the app font, the page at 1280×800, laid out. */
    private static Scene sceneOf(Parent root) {
        Scene scene = new Scene(root, 1280, 800);
        var css = Brk12V1CaptureTest.class.getResource("/styles/app.css");
        assertNotNull(css, "/styles/app.css");
        scene.getStylesheets().add(css.toExternalForm());
        UiTheme.applyFont(scene.getRoot());
        return scene;
    }

    /** Lays the page out and lets every pulse the page started (label changes, chart refreshes) reach its end state. */
    private static void settle(Parent root) {
        for (int pass = 0; pass < 6; pass++) {
            ((Region) root).resize(1280, 800);
            root.applyCss();
            root.layout();
        }
        Motion.settleAll();
        root.applyCss();
        root.layout();
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) buffered.setRGB(x, y, reader.getArgb(x, y));
        }
        return buffered;
    }

    private static BufferedImage shot(Node node, double scale) {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(scale, scale));
        // the canvas colour of the app (bg-0): the page content has no box, and light text on a transparent PNG is unreadable
        parameters.setFill(javafx.scene.paint.Color.web("#10151D"));
        WritableImage image = node.snapshot(parameters, null);
        assertNotNull(image, "snapshot of " + node.getStyleClass());
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0, "empty snapshot of " + node.getStyleClass());
        return toBufferedImage(image);
    }

    /** The images one above the other on the canvas colour of the app (used when one area has two states). */
    private static BufferedImage stack(List<BufferedImage> images, double scale) {
        int gap = (int) Math.round(12 * scale);
        int width = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(1);
        int height = images.stream().mapToInt(BufferedImage::getHeight).sum() + gap * (images.size() - 1);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0x10151D));
        g.fillRect(0, 0, width, height);
        int y = 0;
        for (BufferedImage image : images) {
            g.drawImage(image, 0, y, null);
            y += image.getHeight() + gap;
        }
        g.dispose();
        return out;
    }

    private void write(String area, double scale, BufferedImage image) throws IOException {
        Path target = folder.resolve(area + "-" + (scale == 1.0 ? "1.0" : "1.5") + ".png");
        assertTrue(ImageIO.write(image, "png", target.toFile()), "no PNG writer for " + target);
        assertTrue(Files.isRegularFile(target) && Files.size(target) > 0, "not written: " + target);
    }

    private void capture(String area, Node node) throws IOException {
        for (double scale : new double[]{1.0, 1.5}) write(area, scale, shot(node, scale));
    }

    // ── Dashboard ───────────────────────────────────────────────────────────

    private static StockData quote(String ticker, double price, double change, double percent) {
        return new StockData(ticker, null, ticker + " S.A.", price, change, percent, price - change, price + 1, price - 1,
                1000L, 0, 0, 0, "BRL", 0, 0, null);
    }

    private static InvestmentType stock(int id, String ticker, String invested, int quantity) {
        return new InvestmentType(id, "Ativo fictício " + ticker, "ACOES", "ALTA", LocalDate.of(2026, 1, 10), null,
                new BigDecimal(invested), "ACAO", null, null, ticker, new BigDecimal("10.00"), quantity, null);
    }

    /**
     * One loss of R$ 112,50 in the positions (the hero reads a loss) whose gap to the CDI is -0,0026 points (a tie to two
     * places), a flat evolution (variation zero), values of R$ 1.000.000 or more per asset, and a day with a rise, a fall
     * and a move of 0,004% (shows zero).
     */
    @Test
    void writesTheDashboardCaptures() throws Exception {
        Deque<Runnable> worker = new ArrayDeque<>();
        Map<String, StockData> quotes = new LinkedHashMap<>();
        quotes.put("FICT1", quote("FICT1", 28.12, -0.34, -1.20));
        quotes.put("FICT2", quote("FICT2", 150.00, 3.45, 2.35));
        quotes.put("FICT3", quote("FICT3", 50.00, 0.002, 0.004));
        DashboardQuoteSource source = new DashboardQuoteSource() {
            @Override public boolean hasToken() { return false; }
            @Override public long lastSuccessfulFetchMs() { return 0; }
            @Override public Optional<Double> fetchIbovespaReturn() { return Optional.empty(); }
            @Override public Map<String, StockData> fetchMultipleStocks(String tickers) { return quotes; }
            @Override public StockData fetchStockData(String ticker) { return null; }
        };
        DashboardPage[] holder = new DashboardPage[1];

        FxTestSupport.runAndWait(() -> {
            DashboardPage page = new DashboardPage(FxStubs.emptyDaily(), source, worker::add, Runnable::run);
            holder[0] = page;
            sceneOf(page.view());
            page.onBreakpoint(Breakpoint.WIDE);
            set(page, "rateCdi", 0.0);
            javafx.scene.chart.XYChart<?, ?> bars = field(page, "waterfallChart");
            bars.setAnimated(false);

            List<InvestmentType> investments = List.of(
                    stock(1, "FICT1", "2812500.00", 1_000_000),
                    stock(2, "FICT2", "1500000.00", 10_000),
                    stock(3, "FICT3", "50000.00", 1_000));
            Map<Long, Long> values = new HashMap<>();
            values.put(1L, 281_238_750L);
            values.put(2L, 150_000_000L);
            values.put(3L, 5_000_000L);
            TreeMap<LocalDate, Long> snapshots = new TreeMap<>();
            for (int month = 5; month <= 10; month++) snapshots.put(LocalDate.of(2026, month, 1), 436_238_750L);

            Class<?> dashData = Class.forName("com.daniel.presentation.view.pages.DashboardPage$DashData");
            Constructor<?> constructor = dashData.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object data = constructor.newInstance(investments, values, 436_238_750L, -11_250L, 436_238_750L, snapshots,
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 11, 5), List.of(), null, null);
            Method apply = DashboardPage.class.getDeclaredMethod("applyDashData", dashData, LocalDate.class);
            apply.setAccessible(true);
            apply.invoke(page, data, TODAY);
            while (!worker.isEmpty()) worker.poll().run();
        });
        // the page's own runLater calls (tooltips, axis density) settle before the pictures
        FxTestSupport.runAndWait(() -> {
            DashboardPage page = holder[0];
            Parent view = page.view();
            settle(view);
            capture("dash-hero-cdi", field(page, "hero"));
            capture("dash-valor-por-ativo-eixo", field(page, "waterfallChart"));
            capture("dash-altas-baixas", field(page, "rankPair"));
            javafx.scene.control.Label variation = field(page, "metricRendimentoLabel");
            capture("dash-variacao-pct", variation.getParent().getParent());
        });
    }

    // ── Simulação ───────────────────────────────────────────────────────────

    /** A stock and a % of the CDI, both with a profit of R$ 1.000 or more, and the rates frozen at a known date. */
    @Test
    void writesTheSimulationCapture() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("scen.db").toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE simulation_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "parameters_json TEXT NOT NULL, calculation_version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
        }
        SavedAnalysisRepository scenarios = new SavedAnalysisRepository(SavedAnalysisRepository.sqliteFactory(jdbcUrl));
        List<BufferedImage> at1 = new ArrayList<>();
        List<BufferedImage> at15 = new ArrayList<>();

        FxTestSupport.runAndWait(() -> {
            SimulationPage page = new SimulationPage(scenarios, new ArrayDeque<Runnable>()::add, Runnable::run);
            sceneOf(page.view());
            page.onBreakpoint(Breakpoint.WIDE);
            Node hero = field(page, "resultHero");

            // a stock: 1.000 shares bought at R$ 10,00, worth R$ 15,00 → the stock branch of the result
            ((javafx.scene.control.ToggleButton) field(page, "acaoBtn")).setSelected(true);
            ((TextField) field(page, "tickerField")).setText("FICT1");
            ((TextField) field(page, "purchasePriceField")).setText("R$ 10,00");
            ((TextField) field(page, "quantityField")).setText("1000");
            ((TextField) field(page, "currentPriceField")).setText("R$ 15,00");
            ((TextField) field(page, "dividendsField")).setText("R$ 2,50");
            ((Button) field(page, "calculateBtn")).fire();
            settle(page.view());
            at1.add(shot(hero, 1.0));
            at15.add(shot(hero, 1.5));

            // a post-fixed 110% of the CDI over 12 months with rates frozen at a known date → the fixed-income branch
            ((javafx.scene.control.ToggleButton) field(page, "posfixadoBtn")).setSelected(true);
            set(page, "frozenRates", new com.daniel.core.domain.entity.SimulationParameters.RateSnapshot(
                    new BigDecimal("0.1365"), new BigDecimal("0.15"), new BigDecimal("0.045"), "BCB/SGS", "2026-11-05"));
            ComboBox<Object> mode = field(page, "rentabilityModeCombo");
            mode.setValue(mode.getItems().stream().filter(item -> item.toString().equals("BENCHMARK_PERCENT")).findFirst().orElseThrow());
            ((TextField) field(page, "initialValueField")).setText("10000");
            ((TextField) field(page, "benchmarkPercentField")).setText("110");
            ((Button) field(page, "calculateBtn")).fire();
            settle(page.view());
            at1.add(shot(hero, 1.0));
            at15.add(shot(hero, 1.5));
        });
        write("sim-resultado", 1.0, stack(at1, 1.0));
        write("sim-resultado", 1.5, stack(at15, 1.5));
    }

    // ── Ranking ─────────────────────────────────────────────────────────────

    /** A rise, a fall and a move of 0,004% (shows zero) in the two day lists, with prices of R$ 1.000 or more. */
    @Test
    void writesTheRankingCapture() throws Exception {
        FxTestSupport.runAndWait(() -> {
            RankingPage page = new RankingPage(FxStubs.emptyDaily());
            sceneOf(page.view());
            page.onBreakpoint(Breakpoint.WIDE);
            Map<String, StockData> stocks = new LinkedHashMap<>();
            stocks.put("UPPR3", quote("UPPR3", 1234.56, 61.0, 5.26));
            stocks.put("DOWN4", quote("DOWN4", 4321.09, -216.0, -4.76));
            stocks.put("ZERO3", quote("ZERO3", 20.00, 0.0008, 0.004));
            page.renderStocks(stocks);
            settle(page.view());
            capture("ranking-celula-var", field(page, "listsPair"));
        });
    }
}

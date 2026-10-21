package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryResult;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.infrastructure.api.BrapiClient.TickerSuggestion;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.ChartAxisUtils;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Análise de ativo (direction A): the search is the first thing on the page; every query state
 * (idle, loading, error, no result, result) has its own presentation on the page itself; the
 * price is the hero with its change and provenance; day figures and 52-week figures are two
 * value strips; the price history is one panel with its period controls.
 *
 * <p>The X axis uses {@link NumberAxis} with epoch seconds, so the time scale is proportional to
 * the real interval between points. Chart data is assigned first; {@link Motion#chartRefresh}
 * only makes the change visible. Late responses for a ticker that is no longer the one being
 * searched are discarded (epoch guard).
 */
public final class AssetAnalysisPage implements ResponsivePage {

    private static final Logger LOG = Logger.getLogger(AssetAnalysisPage.class.getName());

    /** Quote source (BRAPI in production); never throws by contract, but the page must survive it if it does. */
    @FunctionalInterface
    interface QuoteSource {
        BrapiClient.FetchResult<StockData> fetch(String ticker);
    }

    /** Price history source (BRAPI in production). */
    @FunctionalInterface
    interface HistorySource {
        HistoryResult fetch(String ticker, Period period) throws IOException;
    }

    private final QuoteSource quotes;
    private final HistorySource histories;
    private final Executor worker;
    private final Executor ui;

    static final String STAT_OPEN = "Abertura";
    static final String STAT_HIGH = "Máx. do dia";
    static final String STAT_LOW = "Mín. do dia";
    static final String STAT_VOLUME = "Volume";
    static final String STAT_WEEK_HIGH = "Máx. 52 sem.";
    static final String STAT_WEEK_LOW = "Mín. 52 sem.";
    static final String STAT_AVG200 = "Média 200 dias";
    static final String STAT_YEAR_CHANGE = "Var. 52 sem.";
    static final String STAT_DY = "Dividend yield";

    // Formatter of the current period — read by the axis and the crosshair through a lambda
    private DateTimeFormatter currentDateFmt = DateTimeFormatter.ofPattern("dd/MM");

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(16);

    // ── Search ──
    private final ComboBox<TickerSuggestion> tickerCombo = new ComboBox<>();
    private final Button searchBtn = new Button("Buscar", Icons.search());
    private String currentTicker = "";
    private final AtomicLong searchEpoch = new AtomicLong();
    /** One epoch per chart request (ticker × period): the last REQUEST wins, not the last answer. */
    private final AtomicLong chartEpoch = new AtomicLong();

    // ── States ──
    private final VBox emptyState = EmptyState.of("🔍", "Pesquise um ativo",
            "Digite um ticker, como PETR4, ITUB4 ou VALE3, para consultar cotação e histórico.");
    private final HBox loadingBox = new HBox(10);
    private final Label loadingLabel = new Label();
    private final Label errorBanner = new Label();
    private final Label noResultLabel = new Label();

    // ── Result ──
    private final VBox resultBox = new VBox(16);
    private final HeroValue hero = new HeroValue("Cotação");
    private final StatStrip dayStrip = new StatStrip(STAT_OPEN, STAT_HIGH, STAT_LOW, STAT_VOLUME);
    private final StatStrip yearStrip = new StatStrip(STAT_WEEK_HIGH, STAT_WEEK_LOW, STAT_AVG200, STAT_YEAR_CHANGE, STAT_DY);

    // ── Chart — NumberAxis for a proportional time scale ──
    private final NumberAxis xAxis   = new NumberAxis();
    private final NumberAxis yAxis   = new NumberAxis();
    private final AreaChart<Number, Number> areaChart = new AreaChart<>(xAxis, yAxis);
    private final Panel chartPanel;
    private final AdaptivePair summaryPair;
    private final FlowPane periodBar = new FlowPane(6, 6);
    private final ToggleGroup periodGroup = new ToggleGroup();
    private final Label chartEmptyLabel = new Label("Sem dados para este período");
    private final Label chartLoadingLabel = new Label("Carregando histórico…");
    /** Epoch seconds (ZoneOffset.UTC) of the last render, for the axis refresh. */
    private List<Long> lastEpochSecs = List.of();
    private final Timeline resizeDebounce;
    /** Keeps the ToggleGroup listener from reloading the chart during programmatic adjustments. */
    private boolean suppressPeriodReload = false;

    public AssetAnalysisPage() {
        this(BrapiClient::fetchStockDataSafe, AssetHistoryClient::fetchHistory, UiExecutor.get(), Platform::runLater);
    }

    /** Test seam: the sources the page queries and the executors it queries on (worker) and answers on (ui). */
    AssetAnalysisPage(QuoteSource quotes, HistorySource histories, Executor worker, Executor ui) {
        this.quotes = quotes;
        this.histories = histories;
        this.worker = worker;
        this.ui = ui;
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Análise de ativo",
                "Cotação e histórico de preço de um ticker da B3 (BRAPI)");

        HBox searchRow = buildSearchRow();

        errorBanner.setGraphic(Icons.alertTriangle());
        errorBanner.setGraphicTextGap(8);
        errorBanner.setWrapText(true);
        errorBanner.setMaxWidth(Double.MAX_VALUE); // a banner spans its panel, whatever the message length (E-V2)
        errorBanner.getStyleClass().add("warning-banner");
        hide(errorBanner);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(18, 18);
        loadingLabel.getStyleClass().add("text-secondary");
        loadingBox.setAlignment(Pos.CENTER_LEFT);
        loadingBox.getChildren().addAll(spinner, loadingLabel);
        hide(loadingBox);

        noResultLabel.getStyleClass().addAll("empty-state", "empty-title");
        noResultLabel.setWrapText(true);
        noResultLabel.setMaxWidth(Double.MAX_VALUE);
        hide(noResultLabel);

        chartPanel = buildChartPanel();
        summaryPair = new AdaptivePair(hero, dayStrip, Breakpoint.MEDIUM, 0.56, 24);
        VBox summary = new VBox(summaryPair);
        summary.getStyleClass().addAll("portfolio-summary", "asset-summary");
        resultBox.getChildren().addAll(summary, chartPanel, yearStrip);
        hide(resultBox);

        Panel searchWorkspace = new Panel("Consultar ativo", searchRow, errorBanner, loadingBox, noResultLabel, emptyState);
        searchWorkspace.getStyleClass().add("asset-search-workspace");
        root.getChildren().addAll(header, searchWorkspace, resultBox);

        // Resize debounce: recomputes the tick density when the width changes by more than 50 px
        resizeDebounce = new Timeline(new KeyFrame(javafx.util.Duration.millis(150), ev -> {
            if (!lastEpochSecs.isEmpty()) {
                ChartAxisUtils.refreshTemporalAxis(xAxis, lastEpochSecs, areaChart.getWidth());
            }
        }));
        resizeDebounce.setCycleCount(1);
        areaChart.widthProperty().addListener((obs, oldW, newW) -> {
            if (Math.abs(newW.doubleValue() - oldW.doubleValue()) > 50) {
                resizeDebounce.stop();
                resizeDebounce.playFromStart();
            }
        });

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");
        onBreakpoint(Breakpoint.WIDE);
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        // Nothing to refresh automatically; the user drives the search.
    }

    @Override
    public void onBreakpoint(Breakpoint bp) {
        boolean narrow = bp == Breakpoint.NARROW;
        // Beside the quote (wide) the day figures form a 2×2 block; stacked under it at medium
        // width they own the whole row, so one line of four (the strip still drops columns that
        // would cut a value) instead of a 2×2 block twice as tall (A-V10).
        dayStrip.setColumns(bp == Breakpoint.MEDIUM ? 4 : 2);
        summaryPair.onBreakpoint(bp);
        yearStrip.setColumns(narrow ? 2 : bp == Breakpoint.MEDIUM ? 3 : 5);
        // period toggles sit in the panel header when there is room, under the title otherwise
        chartPanel.body().getChildren().remove(periodBar);
        if (narrow) {
            chartPanel.actions();
            chartPanel.body().getChildren().add(0, periodBar);
        } else {
            chartPanel.actions(periodBar);
        }
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    private static void hide(javafx.scene.Node n) { n.setVisible(false); n.setManaged(false); }
    private static void show(javafx.scene.Node n) { n.setVisible(true); n.setManaged(true); }

    // ── Build helpers ──────────────────────────────────────────────────────

    private HBox buildSearchRow() {
        tickerCombo.setEditable(true);
        tickerCombo.setPromptText("Ticker ou nome (ex.: PETR4 ou Petrobras)");
        tickerCombo.setAccessibleText("Ticker ou nome do ativo");
        tickerCombo.setMinWidth(180);
        tickerCombo.setPrefWidth(360);
        tickerCombo.setMaxWidth(Double.MAX_VALUE);
        tickerCombo.getStyleClass().add("search-field");
        HBox.setHgrow(tickerCombo, Priority.ALWAYS);

        tickerCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(TickerSuggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                String display = item.ticker();
                if (item.name() != null && !item.name().isBlank()) {
                    display += "  —  " + item.name();
                }
                setText(display);
            }
        });
        tickerCombo.setConverter(new StringConverter<>() {
            @Override public String toString(TickerSuggestion r) {
                return r == null ? "" : r.ticker();
            }
            @Override public TickerSuggestion fromString(String s) {
                if (s == null || s.isBlank()) return null;
                return new TickerSuggestion(s.trim().toUpperCase(), null, null);
            }
        });

        // Debounce 300ms antes de disparar a busca de sugestões
        Timeline debounce = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(300),
                ev -> {
                    String raw = tickerCombo.getEditor().getText();
                    if (raw == null || raw.length() < 2) return;
                    String query = raw.trim().toUpperCase();
                    CompletableFuture
                            .supplyAsync(() -> BrapiClient.searchTickers(query))
                            .thenComposeAsync(results -> {
                                if (!results.isEmpty()) {
                                    return CompletableFuture.completedFuture(results);
                                }
                                if (query.length() > 4) {
                                    String shortQuery = query.substring(0, 4);
                                    return CompletableFuture.supplyAsync(
                                            () -> BrapiClient.searchTickers(shortQuery));
                                }
                                return CompletableFuture.completedFuture(results);
                            })
                            .thenAcceptAsync(results -> Platform.runLater(() -> {
                                if (results.isEmpty()) {
                                    TickerSuggestion hint = new TickerSuggestion(
                                            query, "Pressione Enter para buscar diretamente", null);
                                    tickerCombo.getItems().setAll(hint);
                                    tickerCombo.show();
                                } else {
                                    tickerCombo.getItems().setAll(results);
                                    tickerCombo.show();
                                }
                            }));
                }
        ));

        tickerCombo.getEditor().textProperty().addListener((obs, old, val) -> {
            debounce.stop();
            debounce.playFromStart();
        });

        tickerCombo.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                currentTicker = selected.ticker();
                tickerCombo.getEditor().setText(currentTicker);
                doSearch();
            }
        });

        tickerCombo.getEditor().setOnAction(e -> doSearch());

        searchBtn.getStyleClass().add("button");
        searchBtn.setMinWidth(Region.USE_PREF_SIZE);
        searchBtn.setTooltip(new Tooltip("Buscar cotação e histórico (Enter)"));
        searchBtn.setAccessibleText("Buscar cotação");
        searchBtn.setOnAction(e -> doSearch());

        HBox row = new HBox(8, tickerCombo, searchBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("search-row");
        return row;
    }

    private Panel buildChartPanel() {
        periodBar.setAlignment(Pos.CENTER_LEFT);
        for (Period p : Period.values()) {
            if (p == Period.ONE_DAY) continue; // intraday is not available on the free plan
            ToggleButton tb = new ToggleButton(p.label);
            tb.setToggleGroup(periodGroup);
            tb.setUserData(p);
            tb.getStyleClass().add("filter-toggle");
            periodBar.getChildren().add(tb);
            if (p == Period.ONE_MONTH) tb.setSelected(true);
        }
        periodGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { if (old != null && !suppressPeriodReload) periodGroup.selectToggle(old); return; }
            if (!suppressPeriodReload) reloadChart();
        });

        areaChart.setAnimated(false);
        areaChart.setLegendVisible(false);
        areaChart.setCreateSymbols(false);
        areaChart.setMinHeight(280);
        areaChart.getStyleClass().add("area-chart");
        yAxis.setAutoRanging(false);
        VBox.setVgrow(areaChart, Priority.ALWAYS);

        // Temporal axis: distribution proportional to the real interval between dates
        ChartAxisUtils.installTemporalAxis(xAxis, areaChart, () -> currentDateFmt, () -> lastEpochSecs);
        // R2-16: the axis names the dates and the unit; the crosshair gives the exact point
        yAxis.setLabel("Preço (R$)");

        StackPane chartWrapper = ChartCrosshair.installTemporal(areaChart,
                epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC).format(currentDateFmt),
                y -> "R$ " + String.format("%.2f", y).replace('.', ','));
        VBox.setVgrow(chartWrapper, Priority.ALWAYS);

        chartEmptyLabel.getStyleClass().add("text-secondary");
        chartEmptyLabel.setWrapText(true);
        hide(chartEmptyLabel);
        chartLoadingLabel.getStyleClass().add("text-helper");
        hide(chartLoadingLabel);

        Panel panel = new Panel("Histórico de preço", chartLoadingLabel, chartEmptyLabel, chartWrapper)
                .subtitle("Fechamento diário (BRAPI); passe o mouse sobre o gráfico para ler data e preço");
        panel.getStyleClass().add("main-chart-panel");
        return panel;
    }

    // ── Query states ──────────────────────────────────────────────────────

    /** Loading state: spinner + which ticker; the search button is locked until the answer. */
    void showLoading(String ticker) {
        loadingLabel.setText("Consultando " + ticker + "…");
        hide(emptyState); hide(errorBanner); hide(noResultLabel);
        show(loadingBox);
        searchBtn.setDisable(true);
    }

    /** Failure state: the message stays on the page; no stale result is left under it. */
    void showFailure(String message) {
        errorBanner.setText(message == null ? "Não foi possível consultar." : message);
        hide(loadingBox); hide(resultBox); hide(noResultLabel); hide(emptyState);
        show(errorBanner);
        searchBtn.setDisable(false);
    }

    /** No-result state: names the ticker that was not found. */
    void showNoResult(String ticker) {
        noResultLabel.setText("Nenhum ativo encontrado para \"" + ticker + "\". Confira o ticker (ex.: PETR4) ou tente pelo nome.");
        hide(loadingBox); hide(resultBox); hide(errorBanner); hide(emptyState);
        show(noResultLabel);
        searchBtn.setDisable(false);
    }

    // ── Search logic ───────────────────────────────────────────────────────

    private void doSearch() {
        String ticker = tickerCombo.getEditor().getText() == null ? "" : tickerCombo.getEditor().getText().trim().toUpperCase();
        if (ticker.isBlank()) return;
        currentTicker = ticker;
        final long epoch = searchEpoch.incrementAndGet();
        showLoading(ticker);

        CompletableFuture.supplyAsync(() -> quotes.fetch(ticker), worker)
                .thenAccept(result -> ui.execute(() -> {
                    if (searchEpoch.get() != epoch) return; // a newer search replaced this one
                    if (!result.isSuccess()) {
                        switch (result.getReason()) {
                            case NO_TOKEN      -> showFailure("Configure um token BRAPI em Configurações para buscar cotações.");
                            case HTTP_ERROR    -> showFailure("Serviço BRAPI indisponível (HTTP " + result.getDetail() + "). Tente de novo em instantes.");
                            case NETWORK_ERROR -> showFailure("Sem conexão. Verifique sua internet e tente novamente.");
                            case NOT_FOUND     -> showNoResult(ticker);
                            case PARSE_ERROR   -> showFailure("Resposta inesperada da BRAPI. Tente novamente.");
                        }
                        return;
                    }
                    StockData data = result.getData();
                    if (!data.isValid()) {
                        if (data.hasError()) showFailure("Não foi possível carregar " + data.ticker() + ": " + data.error());
                        else showNoResult(ticker);
                        return;
                    }
                    renderQuote(data);
                    resetPeriodVisibility(); // new ticker → rediscover the limits from scratch
                    reloadChart();
                    probeCapabilities();     // discover the real permitted ranges in parallel
                }))
                .exceptionally(ex -> {
                    // The source promises not to throw; if it does anyway (parser, settings, null body)
                    // the page must still reach a terminal state: never "loading" with Buscar locked.
                    Throwable root = ex;
                    while (root.getCause() != null) root = root.getCause();
                    final String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                    LOG.warning("[Quote] " + ticker + " → " + root.getClass().getSimpleName() + ": " + msg);
                    ui.execute(() -> {
                        if (searchEpoch.get() != epoch) return;
                        showFailure("Falha inesperada ao consultar " + ticker + ": " + msg + ". Tente novamente.");
                    });
                    return null;
                });
    }

    /** Result state: assigns every figure at once (the pulse only highlights the change). */
    void renderQuote(StockData data) {
        String name = data.longName() != null ? data.longName() : data.ticker();
        hero.setValue(money(data.regularMarketPrice()));
        double chg = data.regularMarketChange();
        double chgPct = data.regularMarketChangePercent();
        String tone = chgPct > 0 ? "pos" : chgPct < 0 ? "neg" : "muted";
        hero.setSecondary((chg >= 0 ? "+ " : "- ") + money(Math.abs(chg)) + " · " + signedPct(chgPct) + " hoje", tone);
        hero.setContext(name + " (" + data.ticker() + ") · cotação BRAPI, atraso típico de 15–30 min no plano gratuito");

        dayStrip.set(STAT_OPEN, money(data.regularMarketOpen()));
        dayStrip.set(STAT_HIGH, money(data.regularMarketDayHigh()));
        dayStrip.set(STAT_LOW, money(data.regularMarketDayLow()));
        dayStrip.set(STAT_VOLUME, volume(data.regularMarketVolume()));

        setOptional(yearStrip, STAT_WEEK_HIGH, data.fiftyTwoWeekHigh() > 0, money(data.fiftyTwoWeekHigh()));
        setOptional(yearStrip, STAT_WEEK_LOW, data.fiftyTwoWeekLow() > 0, money(data.fiftyTwoWeekLow()));
        setOptional(yearStrip, STAT_AVG200, data.twoHundredDayAverage() > 0, money(data.twoHundredDayAverage()));
        double yearChg = data.fiftyTwoWeekChange();
        if (yearChg != 0) yearStrip.set(STAT_YEAR_CHANGE, signedPct(yearChg * 100), yearChg > 0 ? "pos" : "neg");
        else yearStrip.set(STAT_YEAR_CHANGE, "—", "muted");
        setOptional(yearStrip, STAT_DY, data.dividendYield() > 0, pct(data.dividendYield()));

        hide(loadingBox); hide(errorBanner); hide(noResultLabel); hide(emptyState);
        boolean wasVisible = resultBox.isVisible();
        show(resultBox);
        if (!wasVisible) Motion.fadeSlideIn(resultBox, Motion.PANEL_MS, 12);
        searchBtn.setDisable(false);
    }

    private static void setOptional(StatStrip strip, String label, boolean present, String text) {
        if (present) strip.set(label, text);
        else strip.set(label, "—", "muted");
    }

    private static String money(double v) {
        return String.format("R$ %.2f", v).replace('.', ',');
    }

    private static String pct(double v) {
        return String.format("%.2f%%", v).replace('.', ',');
    }

    private static String signedPct(double v) {
        return String.format("%s%.2f%%", v >= 0 ? "+" : "", v).replace('.', ',');
    }

    private static String volume(long vol) {
        if (vol >= 1_000_000) return String.format("%.1fM", vol / 1_000_000.0).replace('.', ',');
        if (vol >= 1_000) return String.format("%.1fK", vol / 1_000.0).replace('.', ',');
        return vol > 0 ? String.valueOf(vol) : "—";
    }

    // ── Chart ──────────────────────────────────────────────────────────────

    private void reloadChart() {
        if (currentTicker.isBlank()) return;
        final String ticker = currentTicker;
        final long epoch = searchEpoch.get();
        final long requestEpoch = chartEpoch.incrementAndGet();

        Toggle selected = periodGroup.getSelectedToggle();
        Period period = selected != null ? (Period) selected.getUserData() : Period.ONE_MONTH;
        currentDateFmt = DateTimeFormatter.ofPattern(period.datePattern);

        areaChart.getData().clear();
        setChartEmpty(null);
        show(chartLoadingLabel);

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return histories.fetch(ticker, period);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, worker)
                .thenAccept(result -> ui.execute(() -> {
                    // stale if the ticker changed OR a newer period request was issued meanwhile
                    if (searchEpoch.get() != epoch || !ticker.equals(currentTicker) || chartEpoch.get() != requestEpoch) return;
                    hide(chartLoadingLabel);
                    applyPermittedRanges(result.permittedRanges(), result.effectiveRange());
                    renderChart(result.points());
                }))
                .exceptionally(ex -> {
                    Throwable root = ex;
                    while (root.getCause() != null) root = root.getCause();
                    String msg = root.getMessage();
                    LOG.warning("[Chart] " + ticker + " " + period.range
                            + "/" + period.interval + " → " + root.getClass().getSimpleName() + ": " + msg);
                    ui.execute(() -> {
                        if (searchEpoch.get() != epoch || chartEpoch.get() != requestEpoch) return;
                        hide(chartLoadingLabel);
                        setChartEmpty(msg != null ? "Histórico indisponível: " + msg : "Histórico indisponível para este período");
                    });
                    return null;
                });
    }

    private void setChartEmpty(String message) {
        boolean hasMsg = message != null && !message.isBlank();
        chartEmptyLabel.setText(hasMsg ? message : "Sem dados para este período");
        chartEmptyLabel.setVisible(hasMsg);
        chartEmptyLabel.setManaged(hasMsg);
        if (hasMsg) areaChart.getData().clear();
    }

    private void renderChart(List<HistoryPoint> points) {
        areaChart.getData().clear();
        if (points.isEmpty()) {
            setChartEmpty("Sem dados disponíveis para este período");
            return;
        }

        List<HistoryPoint> valid = points.stream().filter(p -> p.close() > 0).toList();
        if (valid.isEmpty()) {
            setChartEmpty("Sem dados válidos para este período");
            return;
        }
        setChartEmpty(null); // data OK — hide the error label

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        double minPrice = Double.MAX_VALUE;
        double maxPrice = -Double.MAX_VALUE;

        List<Long> epochSecs = new ArrayList<>(valid.size());
        for (HistoryPoint p : valid) {
            long sec = p.dateTime().toEpochSecond(ZoneOffset.UTC);
            series.getData().add(new XYChart.Data<>(sec, p.close()));
            epochSecs.add(sec);
            if (p.close() < minPrice) minPrice = p.close();
            if (p.close() > maxPrice) maxPrice = p.close();
        }
        lastEpochSecs = epochSecs;

        if (series.getData().isEmpty()) return;

        // R3-08: round steps on round bounds around the prices (a sixth of the padded range read 34.704, 33.856…)
        ChartAxisUtils.roundValueAxis(yAxis, minPrice, maxPrice);

        areaChart.getData().add(series);          // data first
        Motion.chartRefresh(areaChart);           // then the pulse that shows it changed

        // Recompute the X axis time scale for the current width
        Platform.runLater(() -> ChartAxisUtils.refreshTemporalAxis(xAxis, lastEpochSecs, areaChart.getWidth()));
    }

    /**
     * Restaura todos os botões de período para visível.
     * Chamado quando o ticker muda — redescobre os limites a partir do zero.
     */
    private void resetPeriodVisibility() {
        for (Toggle t : periodGroup.getToggles()) {
            ToggleButton btn = (ToggleButton) t;
            btn.setVisible(true);
            btn.setManaged(true);
        }
    }

    /**
     * Oculta botões de período não suportados pelo plano/ticker atual.
     * Se o botão selecionado for ocultado, seleciona o botão do effectiveRange.
     *
     * @param permitted      ranges confirmados pela Brapi (vazio = sem info, não altera nada)
     * @param effectiveRange range que foi efetivamente buscado (usado para corrigir a seleção)
     */
    private void applyPermittedRanges(Set<String> permitted, String effectiveRange) {
        if (permitted.isEmpty()) return; // sem informação de limite — mantém visibilidade atual

        suppressPeriodReload = true;
        try {
            boolean selectedHidden = false;
            for (Toggle t : periodGroup.getToggles()) {
                ToggleButton btn = (ToggleButton) t;
                Period p = (Period) btn.getUserData();
                boolean visible = permitted.contains(p.range);
                btn.setVisible(visible);
                btn.setManaged(visible);
                if (!visible && btn.isSelected()) {
                    btn.setSelected(false);
                    selectedHidden = true;
                }
            }
            if (selectedHidden) {
                // Seleciona o botão do range efetivo; atualiza o formatter de data
                Period effective = selectPeriodByRange(effectiveRange);
                if (effective != null) {
                    currentDateFmt = DateTimeFormatter.ofPattern(effective.datePattern);
                }
            }
        } finally {
            suppressPeriodReload = false;
        }
    }

    /**
     * Seleciona o primeiro botão visível cujo range corresponde ao dado.
     * Se não encontrar correspondência exata, seleciona o primeiro botão visível.
     *
     * @return o Period do botão selecionado, ou null se nenhum botão estiver visível
     */
    private Period selectPeriodByRange(String range) {
        // Busca correspondência exata primeiro
        for (Toggle t : periodGroup.getToggles()) {
            ToggleButton btn = (ToggleButton) t;
            if (!btn.isVisible()) continue;
            Period p = (Period) btn.getUserData();
            if (p.range.equals(range)) {
                btn.setSelected(true);
                return p;
            }
        }
        // Fallback: primeiro botão visível
        for (Toggle t : periodGroup.getToggles()) {
            ToggleButton btn = (ToggleButton) t;
            if (btn.isVisible()) {
                btn.setSelected(true);
                return (Period) btn.getUserData();
            }
        }
        return null;
    }

    /**
     * Dispara uma busca de sondagem para o range MAX em paralelo com o fetch inicial.
     * Objetivo: descobrir os ranges reais permitidos pelo plano/token sem esperar que o
     * usuário clique em um período não suportado.
     *
     * <p>Se o probe retornar com permittedRanges não vazio (o range MAX falhou e a Brapi
     * informou os ranges disponíveis), aplica visibilidade imediatamente.
     * Se MAX tiver sucesso (plano sem restrição), mantém todos os botões visíveis.</p>
     */
    private void probeCapabilities() {
        String ticker = currentTicker;
        CompletableFuture.supplyAsync(() -> {
            try {
                return histories.fetch(ticker, Period.MAX);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, worker).thenAccept(result -> ui.execute(() -> {
            if (!ticker.equals(currentTicker)) return; // ticker mudou enquanto probe rodava
            if (!result.permittedRanges().isEmpty()) {
                applyPermittedRanges(result.permittedRanges(), result.effectiveRange());
            }
            // permittedRanges vazio = MAX teve sucesso → plano sem restrição, mantém tudo visível
        })).exceptionally(ex -> null); // falha do probe é não-crítica
    }
}

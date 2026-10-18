package com.daniel.presentation.view.pages;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.infrastructure.api.BrapiClient.TickerSuggestion;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.ChartAxisUtils;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.ChartSeriesStyle;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.RankingViewModel;
import com.daniel.presentation.viewmodel.RankingViewModel.RankingRow;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Ranking (direction A): the metric and the period are named on screen; the two lists are
 * comparable rows (asset, quote, day change) that stack when the window is narrow; a row opens a
 * contextual detail with the real quote figures; the empty state and the connection warning say
 * what is missing without discarding the data already on screen.
 *
 * <p>The day change is the change of the QUOTE, and the period chart is the change of the PRICE
 * normalised at the start of the period — neither is the return of the user's position (no
 * contributions, dividends or purchase price), and the texts say so. Charts with a time axis use
 * {@link NumberAxis} with epoch seconds; the bar chart keeps {@link CategoryAxis} because its X
 * values are asset names, not dates.</p>
 */
public final class RankingPage implements ResponsivePage {

    private static final Logger LOG = Logger.getLogger(RankingPage.class.getName());
    private static final DateTimeFormatter MA_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yy");

    /** Batch quote source (BRAPI in production); the page must survive it throwing. */
    @FunctionalInterface
    interface QuoteSource {
        BrapiClient.FetchResult<Map<String, StockData>> fetch(String joinedTickers);
    }

    /** Price history source (BRAPI in production). */
    @FunctionalInterface
    interface HistorySource {
        AssetHistoryClient.HistoryResult fetch(String ticker, AssetHistoryClient.Period period) throws Exception;
    }

    private final DailyTrackingUseCase daily;
    private final QuoteSource quotes;
    private final HistorySource histories;
    private final Executor worker;
    private final Executor ui;

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(16);

    // ── Day change: two lists + bar chart ──
    private final TableView<RankingRow> gainersTable = new TableView<>();
    private final TableView<RankingRow> losersTable  = new TableView<>();
    private final Label gainersPlaceholder = new Label("Nenhum ativo nesta lista");
    private final Label losersPlaceholder = new Label("Nenhum ativo nesta lista");
    private final AdaptivePair listsPair;
    private final Panel dayPanel;
    private final Label freshnessChip = new Label();
    private final CategoryAxis barXAxis = new CategoryAxis();
    private final NumberAxis   barYAxis = new NumberAxis();
    private final BarChart<String, Number> barChart = new BarChart<>(barXAxis, barYAxis);
    private final Label barEmptyLabel = new Label();

    // ── Row detail ──
    private final DetailsPane details = new DetailsPane();
    /** The list the user picked the detailed row from: Esc/X hand the focus back to it. */
    private TableView<RankingRow> detailSource;

    // ── Price change over a period — temporal NumberAxis ──
    private final NumberAxis retXAxis = new NumberAxis();
    private final NumberAxis retYAxis = new NumberAxis();
    private final LineChart<Number, Number> returnChart = new LineChart<>(retXAxis, retYAxis);
    private final ToggleGroup returnPeriodGroup = new ToggleGroup();
    private final FlowPane returnPeriodBar = new FlowPane(6, 6);
    private final Panel returnPanel;
    private final Label returnLoadingLabel = new Label("Carregando histórico…");
    private final Label returnEmptyLabel = new Label();
    /** One epoch per period-chart request (period × selection): the last REQUEST wins, not the last answer. */
    private final AtomicLong returnEpoch = new AtomicLong();
    /** Epoch seconds of the last render of returnChart. */
    private List<Long> lastReturnEpochSecs = List.of();
    /** Current formatter of returnChart — updated when the period changes. */
    private DateTimeFormatter currentReturnFmt = DateTimeFormatter.ofPattern("dd/MM");

    // ── Moving average — temporal NumberAxis ──
    private final NumberAxis maXAxis = new NumberAxis();
    private final NumberAxis maYAxis = new NumberAxis();
    private final LineChart<Number, Number> maChart = new LineChart<>(maXAxis, maYAxis);
    private final ComboBox<TickerSuggestion> maTickerCombo = new ComboBox<>();
    private final DatePicker maFromPicker = new DatePicker();
    private final DatePicker maToPicker   = new DatePicker();
    private final Spinner<Integer> maShortPeriod = new Spinner<>(2, 50, 9);
    private final Spinner<Integer> maLongPeriod  = new Spinner<>(5, 200, 21);
    private final FlowPane maInputBar = new FlowPane(8, 8);
    private final Button maCalcBtn = new Button("Calcular");
    private final Label maLoadingLabel = new Label("Calculando médias móveis…");
    private final Label maCrossLabel = new Label("Sem cruzamento recente detectado.");
    /** Epoch seconds of the last render of maChart. */
    private List<Long> lastMAEpochSecs = List.of();

    // ── State ──
    private Map<String, StockData> lastStocks = new LinkedHashMap<>();
    private boolean loading = false;
    /** When the quotes on screen were obtained (0 = none yet); the chip speaks about THESE quotes. */
    private long quotesObtainedAtMs = 0L;

    // ── Page states ──
    private final HBox loadingBox = new HBox(10);
    private final Label errorBanner = new Label();
    private final VBox emptyState;
    private final VBox contentArea;

    public RankingPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this(dailyTrackingUseCase, BrapiClient::fetchMultipleStocksSafe, AssetHistoryClient::fetchHistory,
                UiExecutor.get(), Platform::runLater);
    }

    /** Test seam: the sources the page queries and the executors it queries on (worker) and answers on (ui). */
    RankingPage(DailyTrackingUseCase dailyTrackingUseCase, QuoteSource quotes, HistorySource histories,
                Executor worker, Executor ui) {
        this.daily = dailyTrackingUseCase;
        this.quotes = quotes;
        this.histories = histories;
        this.worker = worker;
        this.ui = ui;
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Ranking",
                "Variação do dia e do período dos ativos com ticker na sua carteira (BRAPI)");

        errorBanner.setGraphic(Icons.alertTriangle());
        errorBanner.setGraphicTextGap(8);
        errorBanner.setWrapText(true);
        errorBanner.getStyleClass().add("warning-banner");
        hide(errorBanner);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(18, 18);
        Label loadingLabel = new Label("Consultando cotações…");
        loadingLabel.getStyleClass().add("text-secondary");
        loadingBox.setAlignment(Pos.CENTER_LEFT);
        loadingBox.getChildren().addAll(spinner, loadingLabel);
        hide(loadingBox);

        emptyState = EmptyState.of("🏆", "Nenhum ativo com ticker",
                "Cadastre ativos com ticker na Carteira para comparar a variação das cotações.");

        Panel gainersPanel = buildListPanel("Maiores altas", gainersTable);
        Panel losersPanel  = buildListPanel("Maiores baixas", losersTable);
        listsPair = new AdaptivePair(gainersPanel, losersPanel, Breakpoint.NARROW, 0.5, 16);

        dayPanel = buildDayPanel();
        returnPanel = buildReturnPanel();
        Panel maPanel = buildMAPanel();

        details.setTitle("Ativo", null);
        details.setOnClose(() -> {
            gainersTable.getSelectionModel().clearSelection();
            losersTable.getSelectionModel().clearSelection();
            // U02: the USER closed the detail (Esc / X) → the focus returns to the list the row came from,
            // never stays on the now-hidden close button.
            TableView<RankingRow> back = detailSource != null ? detailSource : gainersTable;
            back.requestFocus();
        });
        details.hide();

        contentArea = new VBox(16, dayPanel, details, returnPanel, maPanel);
        hide(contentArea);

        root.getChildren().addAll(header, errorBanner, loadingBox, emptyState, contentArea);

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
        loadStockData();
    }

    @Override
    public void onBreakpoint(Breakpoint bp) {
        boolean narrow = bp == Breakpoint.NARROW;
        listsPair.onBreakpoint(bp);
        // period toggles sit in the panel header when there is room, under the title otherwise
        returnPanel.body().getChildren().remove(returnPeriodBar);
        if (narrow) {
            returnPanel.actions();
            returnPanel.body().getChildren().add(0, returnPeriodBar);
        } else {
            returnPanel.actions(returnPeriodBar);
        }
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    private static void hide(Node n) { n.setVisible(false); n.setManaged(false); }
    private static void show(Node n) { n.setVisible(true); n.setManaged(true); }

    // ── Build helpers ──────────────────────────────────────────────────────

    private Panel buildDayPanel() {
        barChart.setAnimated(false);
        barChart.setLegendVisible(false);
        barChart.setMinHeight(240);
        barChart.setCategoryGap(30);
        barChart.getStyleClass().add("bar-chart");
        barYAxis.setAutoRanging(true);
        barYAxis.setLabel("Variação do dia (%)");
        barXAxis.setLabel("");
        // No installSmartAxis on the bar chart — the native CategoryAxis owns the ticker labels;
        // hiding intermediate labels would break its readability.

        Label chartHint = new Label("Selecione linhas nas listas para comparar só esses ativos; sem seleção, até 8 ativos.");
        chartHint.getStyleClass().add("text-helper");
        chartHint.setWrapText(true);

        freshnessChip.getStyleClass().addAll("brapi-chip", "brapi-chip-neutral");
        // The panel's wrapped subtitle asks for the whole row; the status must not be the one that
        // yields (it would be cut to "atraso ~..."): it keeps its width and the subtitle wraps instead.
        freshnessChip.setMinWidth(Region.USE_PREF_SIZE);
        updateFreshnessChip();

        barEmptyLabel.getStyleClass().add("text-secondary");
        barEmptyLabel.setWrapText(true);
        barEmptyLabel.setText("Aguardando cotações para montar o gráfico.");
        show(barEmptyLabel);

        return new Panel("Variação do dia", listsPair, chartHint, barEmptyLabel, barChart)
                .subtitle("Variação da cotação em relação ao fechamento anterior (BRAPI, atraso de 15–30 min). "
                        + "É a variação do preço, não é a rentabilidade da sua posição.")
                .actions(freshnessChip);
    }

    private Panel buildListPanel(String title, TableView<RankingRow> table) {
        TableColumn<RankingRow, String> tickerCol = new TableColumn<>("Ativo");
        tickerCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ticker()));
        tickerCol.setMinWidth(72);

        TableColumn<RankingRow, Number> priceCol = new TableColumn<>("Cotação");
        priceCol.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().price()));
        priceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("num");
                if (empty || item == null) { setText(null); return; }
                setText(money(item.doubleValue()));
                getStyleClass().add("num");
            }
        });
        priceCol.setMinWidth(90);
        priceCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<RankingRow, Number> chgCol = new TableColumn<>("Variação do dia");
        chgCol.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().changePct()));
        chgCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg", "num");
                if (empty || item == null) { setText(null); return; }
                double v = item.doubleValue();
                setText(signedPct(v));
                getStyleClass().addAll("num", v >= 0 ? "pos" : "neg");
            }
        });
        chgCol.setMinWidth(110);
        chgCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        table.getColumns().add(tickerCol);
        table.getColumns().add(priceCol);
        table.getColumns().add(chgCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(42);
        table.setPrefHeight(42 * 5 + 30);
        table.setPlaceholder(table == gainersTable ? gainersPlaceholder : losersPlaceholder);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setAccessibleText(title);
        // Esc with the focus on the list closes the detail (same as Carteira); the detail's own Esc still works.
        table.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && details.isVisible()) { details.requestClose(); e.consume(); }
        });

        // Click toggles the row (a second click deselects) while keeping the other selected rows.
        table.setRowFactory(tv -> {
            TableRow<RankingRow> row = new TableRow<>();
            final boolean[] wasSelectedOnPress = {false};
            @SuppressWarnings("unchecked") final List<Integer>[] prevIndices = new List[]{List.of()};

            row.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (!row.isEmpty()) {
                    wasSelectedOnPress[0] = row.isSelected();
                    prevIndices[0] = new ArrayList<>(tv.getSelectionModel().getSelectedIndices());
                }
            });

            row.setOnMouseClicked(event -> {
                if (row.isEmpty()) return;
                if (wasSelectedOnPress[0]) {
                    tv.getSelectionModel().clearSelection(tv.getItems().indexOf(row.getItem()));
                } else {
                    for (int idx : prevIndices[0]) {
                        tv.getSelectionModel().select(idx);
                    }
                }
            });
            return row;
        });

        table.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<RankingRow>) change -> onTableSelectionChanged(table));

        Panel panel = new Panel(title, table);
        panel.getStyleClass().add("ranking-list");
        return panel;
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private void loadStockData() {
        if (loading) return;

        List<String> tickers = getPortfolioTickers();
        if (tickers.isEmpty()) {
            renderStocks(Map.of());
            reloadReturnChart();
            hide(contentArea);
            hide(errorBanner);
            show(emptyState);
            return;
        }

        loading = true;
        hide(emptyState);
        show(loadingBox);

        String joined = String.join(",", tickers);

        CompletableFuture.supplyAsync(() -> quotes.fetch(joined), worker)
                .thenAccept(result -> ui.execute(() -> {
            loading = false;
            hide(loadingBox);

            if (!result.isSuccess()) {
                String msg = switch (result.getReason()) {
                    case NO_TOKEN      -> "Configure um token BRAPI em Configurações para ver as cotações.";
                    case HTTP_ERROR    -> "Serviço BRAPI indisponível (HTTP " + result.getDetail() + "). Cotações podem estar desatualizadas.";
                    case NETWORK_ERROR -> "Sem conexão. Cotações podem estar desatualizadas.";
                    case NOT_FOUND     -> "Alguns tickers não foram encontrados na BRAPI.";
                    case PARSE_ERROR   -> "Resposta inesperada da BRAPI. Tente novamente.";
                };
                showFailure(msg);
                return;
            }

            renderStocks(result.getData());
            reloadReturnChart();
        }))
                .exceptionally(ex -> {
                    // The source promises not to throw; if it does, the page must still end somewhere:
                    // never "loading" forever with the next onShow() refused.
                    Throwable root = ex;
                    while (root.getCause() != null) root = root.getCause();
                    final String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                    LOG.warning("[Ranking] quotes → " + root.getClass().getSimpleName() + ": " + msg);
                    ui.execute(() -> {
                        loading = false;
                        showFailure("Falha inesperada ao consultar as cotações: " + msg + ". Tente novamente.");
                    });
                    return null;
                });
    }

    /** Failure state: the warning stays on the page; a previous ranking remains readable under it. */
    void showFailure(String message) {
        errorBanner.setText(message == null ? "Não foi possível consultar as cotações." : message);
        hide(loadingBox);
        show(errorBanner);
        if (lastStocks.isEmpty()) {
            hide(contentArea);
            show(emptyState);
        }
    }

    /** Result state: lists, bar chart and freshness assigned at once; clears any previous warning. */
    void renderStocks(Map<String, StockData> stocks) {
        lastStocks = new LinkedHashMap<>();
        for (StockData stock : stocks.values()) {
            if (stock != null && stock.ticker() != null && !stock.ticker().isBlank() && stock.isValid()) {
                lastStocks.put(stock.ticker(), stock);
            }
        }
        quotesObtainedAtMs = lastStocks.isEmpty() ? 0 : System.currentTimeMillis();
        gainersTable.setItems(FXCollections.observableArrayList(RankingViewModel.topGainers(lastStocks)));
        losersTable.setItems(FXCollections.observableArrayList(RankingViewModel.topLosers(lastStocks)));
        // Empty lists are a RESULT, not a missing one: say what was obtained and what was not found.
        long obtained = lastStocks.values().stream().filter(StockData::isValid).count();
        String among = obtained == 1 ? "entre a 1 cotação obtida" : "entre as " + obtained + " cotações obtidas";
        gainersPlaceholder.setText(obtained == 0 ? "Nenhuma cotação obtida" : "Nenhuma alta " + among);
        losersPlaceholder.setText(obtained == 0 ? "Nenhuma cotação obtida" : "Nenhuma baixa " + among);
        hide(errorBanner);
        hide(emptyState);
        boolean wasVisible = contentArea.isVisible();
        show(contentArea);
        if (!wasVisible) Motion.fadeSlideIn(contentArea, Motion.PANEL_MS, 12);
        reloadChart();
        updateFreshnessChip();
    }

    private void onTableSelectionChanged(TableView<RankingRow> source) {
        // The detail follows the selection at once; the chart reloads are coalesced to the next pulse.
        List<String> selected = selectedTickers();
        if (selected.isEmpty()) {
            details.hide();
        } else {
            detailSource = source;
            // selectedItems is already current inside its own change event; selectedItem is not yet
            RankingRow focus = lastOf(source.getSelectionModel().getSelectedItems());
            if (focus == null) focus = lastOf(source == gainersTable
                    ? losersTable.getSelectionModel().getSelectedItems()
                    : gainersTable.getSelectionModel().getSelectedItems());
            if (focus != null) showDetails(focus);
        }
        Platform.runLater(() -> {
            List<String> now = selectedTickers();
            if (now.isEmpty()) reloadChart();
            else reloadChartForTickers(now);
            reloadReturnChart();
        });
    }

    private static RankingRow lastOf(List<RankingRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i) != null) return rows.get(i);
        }
        return null;
    }

    private List<String> selectedTickers() {
        Set<String> selected = new LinkedHashSet<>();
        for (RankingRow row : gainersTable.getSelectionModel().getSelectedItems()) {
            if (row != null) selected.add(row.ticker());
        }
        for (RankingRow row : losersTable.getSelectionModel().getSelectedItems()) {
            if (row != null) selected.add(row.ticker());
        }
        return new ArrayList<>(selected);
    }

    /** Contextual detail: the real figures of the quote behind the row. */
    private void showDetails(RankingRow row) {
        StockData sd = lastStocks.get(row.ticker());
        String name = sd != null && sd.longName() != null ? sd.longName() : row.name();
        details.setTitle(row.ticker(), name);
        details.setHeadline(money(row.price()), "cotação BRAPI");
        details.setPrimaryActions();
        details.setAdvancedActions();
        details.clearFields();
        details.addField("Variação do dia", signedPct(row.changePct()));
        if (sd != null) {
            details.addField("Variação em R$", (sd.regularMarketChange() >= 0 ? "+ " : "- ") + money(Math.abs(sd.regularMarketChange())));
            details.addField("Abertura", money(sd.regularMarketOpen()));
            details.addField("Máx. do dia", money(sd.regularMarketDayHigh()));
            details.addField("Mín. do dia", money(sd.regularMarketDayLow()));
            details.addField("Volume", volume(sd.regularMarketVolume()));
        }
        details.setNote("Valores da cotação do ativo na B3, não da sua posição.");
        details.open(false);
    }

    private void reloadChart() {
        List<String> tickers = new ArrayList<>(lastStocks.keySet());
        if (tickers.size() > 8) tickers = tickers.subList(0, 8);
        reloadChartForTickers(tickers);
    }

    private void reloadChartForTickers(List<String> tickers) {
        barChart.getData().clear();

        if (tickers.isEmpty()) { barEmptyLabel.setText("Sem cotações válidas para montar o gráfico."); show(barEmptyLabel); return; }

        int n = tickers.size();
        barChart.setCategoryGap(n <= 3 ? 60 : n <= 6 ? 30 : 12);

        boolean anyChange = false;
        for (String ticker : tickers) {
            StockData d = lastStocks.get(ticker);
            if (d == null || !d.isValid()) continue;

            double pct = d.regularMarketChangePercent();
            if (pct != 0) anyChange = true;

            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(ticker);
            XYChart.Data<String, Number> bar = new XYChart.Data<>(ticker, pct);
            series.getData().add(bar);
            barChart.getData().add(series);

            final double finalPct = pct;
            final StockData finalD  = d;

            Platform.runLater(() -> {
                if (bar.getNode() != null) {
                    applyBarTooltip(bar, finalPct, finalD);
                } else {
                    bar.nodeProperty().addListener((obs, o, node) -> {
                        if (node != null) applyBarTooltip(bar, finalPct, finalD);
                    });
                }
            });
        }
        // A chart of zero-height bars reads as "no data": say that the quotes came but did not move.
        if (!anyChange) {
            barEmptyLabel.setText("Sem variação do dia nas cotações obtidas — as barras ficam em zero.");
            show(barEmptyLabel);
        } else {
            hide(barEmptyLabel);
        }
        Motion.chartRefresh(barChart); // data assigned above; the pulse only shows it changed
    }

    // ── Price change over a period ─────────────────────────────────────────

    private Panel buildReturnPanel() {
        returnPeriodBar.setAlignment(Pos.CENTER_LEFT);
        for (AssetHistoryClient.Period p : AssetHistoryClient.Period.values()) {
            ToggleButton tb = new ToggleButton(p.label);
            tb.setToggleGroup(returnPeriodGroup);
            tb.setUserData(p);
            tb.getStyleClass().add("filter-toggle");
            returnPeriodBar.getChildren().add(tb);
            if (p == AssetHistoryClient.Period.ONE_MONTH) tb.setSelected(true);
        }

        returnPeriodGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { if (old != null) returnPeriodGroup.selectToggle(old); return; }
            if (!lastStocks.isEmpty()) reloadReturnChart();
        });

        returnChart.setAnimated(false);
        returnChart.setLegendVisible(true);
        returnChart.setCreateSymbols(false);
        returnChart.setMinHeight(280);
        retYAxis.setLabel("Variação do preço (%)");
        retXAxis.setLabel("");

        // Temporal axis: distribution proportional to the real interval between dates
        ChartAxisUtils.installTemporalAxis(retXAxis, returnChart, () -> currentReturnFmt, () -> lastReturnEpochSecs);
        ChartAxisUtils.hideTemporalAxisLabels(retXAxis); // the crosshair is the reading mechanism

        returnLoadingLabel.getStyleClass().add("text-helper");
        hide(returnLoadingLabel);
        returnEmptyLabel.getStyleClass().add("text-secondary");
        returnEmptyLabel.setWrapText(true);
        hide(returnEmptyLabel);

        StackPane returnWrapper = ChartCrosshair.installTemporal(returnChart,
                epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC).format(currentReturnFmt),
                y -> signedPct(y));

        Label chartHint = new Label("Selecione ativos nas listas para comparar; sem seleção, até 5 ativos.");
        chartHint.getStyleClass().add("text-helper");
        chartHint.setWrapText(true);

        return new Panel("Variação do preço no período", chartHint, returnLoadingLabel, returnEmptyLabel, returnWrapper)
                .subtitle("Preço de fechamento em relação ao início do período (BRAPI). "
                        + "Não considera aportes, dividendos nem o seu preço de compra.");
    }

    private void reloadReturnChart() {
        List<String> tickers = getSelectedOrAllTickers();
        final long epoch = returnEpoch.incrementAndGet();
        returnChart.getData().clear();
        lastReturnEpochSecs = List.of();
        hide(returnLoadingLabel);
        hide(returnEmptyLabel);
        if (tickers.isEmpty()) {
            returnEmptyLabel.setText("Sem cotações válidas para consultar o histórico.");
            show(returnEmptyLabel);
            return;
        }

        AssetHistoryClient.Period period = getSelectedReturnPeriod();
        currentReturnFmt = DateTimeFormatter.ofPattern(period.datePattern);
        show(returnLoadingLabel);

        List<CompletableFuture<Map.Entry<String, List<HistoryPoint>>>> futures = tickers.stream()
                .map(t -> CompletableFuture.<Map.Entry<String, List<HistoryPoint>>>supplyAsync(() -> {
                    try {
                        return Map.entry(t, histories.fetch(t, period).points());
                    } catch (Exception e) {
                        return Map.entry(t, List.<HistoryPoint>of());
                    }
                }, worker))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    Map<String, List<HistoryPoint>> all = new LinkedHashMap<>();
                    for (var f : futures) {
                        var entry = f.join();
                        if (!entry.getValue().isEmpty()) all.put(entry.getKey(), entry.getValue());
                    }
                    ui.execute(() -> {
                        if (returnEpoch.get() != epoch) return; // a newer period/selection request replaced this one
                        renderReturnChart(all);
                    });
                });
    }

    private void renderReturnChart(Map<String, List<HistoryPoint>> allHistories) {
        hide(returnLoadingLabel);
        returnChart.getData().clear();

        if (allHistories.isEmpty()) {
            returnEmptyLabel.setText("Sem histórico disponível para estes ativos. Verifique a conexão ou o token BRAPI em Configurações.");
            show(returnEmptyLabel);
            return;
        }
        hide(returnEmptyLabel);

        List<Long> refEpochSecs = new ArrayList<>();

        for (var entry : allHistories.entrySet()) {
            List<HistoryPoint> points = entry.getValue();
            if (points.isEmpty()) continue;
            double base = points.get(0).close();
            if (base <= 0) continue;

            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(entry.getKey());

            for (HistoryPoint p : points) {
                long sec = p.dateTime().toEpochSecond(ZoneOffset.UTC);
                double returnPct = ((p.close() / base) - 1) * 100.0;
                series.getData().add(new XYChart.Data<>(sec, returnPct));
            }

            if (!series.getData().isEmpty()) {
                returnChart.getData().add(series);
                // Usa a primeira série como referência para o eixo
                if (refEpochSecs.isEmpty()) {
                    for (XYChart.Data<Number, Number> d : series.getData()) {
                        refEpochSecs.add(d.getXValue().longValue());
                    }
                }
            }
        }

        lastReturnEpochSecs = refEpochSecs;
        Motion.chartRefresh(returnChart);
        Platform.runLater(() -> ChartAxisUtils.refreshTemporalAxis(retXAxis, lastReturnEpochSecs, returnChart.getWidth()));
    }

    private List<String> getSelectedOrAllTickers() {
        List<String> selected = selectedTickers();
        if (!selected.isEmpty()) return selected;
        List<String> all = new ArrayList<>(lastStocks.keySet());
        return all.size() > 5 ? all.subList(0, 5) : all;
    }

    private AssetHistoryClient.Period getSelectedReturnPeriod() {
        Toggle t = returnPeriodGroup.getSelectedToggle();
        return t != null
                ? (AssetHistoryClient.Period) t.getUserData()
                : AssetHistoryClient.Period.ONE_MONTH;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String money(double v) {
        return String.format("R$ %.2f", v).replace('.', ',');
    }

    private static String signedPct(double v) {
        return String.format("%s%.2f%%", v >= 0 ? "+" : "", v).replace('.', ',');
    }

    private static String volume(long vol) {
        if (vol >= 1_000_000) return String.format("%.1fM", vol / 1_000_000.0).replace('.', ',');
        if (vol >= 1_000) return String.format("%.1fK", vol / 1_000.0).replace('.', ',');
        return vol > 0 ? String.valueOf(vol) : "—";
    }

    private void applyBarTooltip(XYChart.Data<String, Number> bar, double pct, StockData sd) {
        Node node = bar.getNode();
        if (node == null) return;

        String color      = pct >= 0 ? "#22c55e" : "#ef4444";
        String hoverColor = pct >= 0 ? "#16a34a" : "#dc2626";
        String baseStyle  = "-fx-background-color: " + color + "; -fx-background-radius: 3 3 0 0;";

        node.setStyle(baseStyle);

        if (node instanceof StackPane sp
                && sp.getChildren().stream().noneMatch(c -> c instanceof Label)) {
            Label lbl = new Label(bar.getXValue());
            lbl.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold;");
            lbl.setRotate(-90);
            lbl.setMouseTransparent(true);
            StackPane.setAlignment(lbl, Pos.CENTER);
            sp.getChildren().add(lbl);
        }

        Tooltip tp = new Tooltip(
                bar.getXValue()
                + "\n" + signedPct(pct) + " hoje"
                + "\nCotação:  " + money(sd.regularMarketPrice())
                + "\nVariação: R$ " + String.format("%+.2f", sd.regularMarketChange()).replace('.', ',')
        );
        tp.setStyle("-fx-font-size: 12px;");
        tp.setShowDelay(javafx.util.Duration.ZERO);
        tp.setHideDelay(javafx.util.Duration.millis(150));
        Tooltip.install(node, tp);

        node.setOnMouseEntered(e -> node.setStyle(
                "-fx-background-color: " + hoverColor + "; -fx-background-radius: 3 3 0 0; -fx-opacity: 0.85;"));
        node.setOnMouseExited(e -> node.setStyle(baseStyle));
    }

    // ── Moving average ───────────────────────────────────────────────────────

    private Panel buildMAPanel() {
        Label hintLegend = new Label(
                "Linhas: verde contínua — preço do ativo · amarela tracejada — média curta · azul contínua — média longa. "
                + "Cruzamento para cima da curta sobre a longa é lido como sinal de alta; para baixo, de baixa.");
        hintLegend.getStyleClass().add("text-helper");
        hintLegend.setWrapText(true);

        // ── Input fields ──
        maTickerCombo.setEditable(true);
        maTickerCombo.setPromptText("Ticker ou nome (ex.: PETR4)");
        maTickerCombo.setAccessibleText("Ticker para a média móvel");
        maTickerCombo.setPrefWidth(240);
        maTickerCombo.getStyleClass().add("search-field");

        maTickerCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(TickerSuggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                String display = item.ticker();
                if (item.name() != null && !item.name().isBlank()) display += "  —  " + item.name();
                setText(display);
            }
        });
        maTickerCombo.setConverter(new StringConverter<>() {
            @Override public String toString(TickerSuggestion r) { return r == null ? "" : r.ticker(); }
            @Override public TickerSuggestion fromString(String s) {
                if (s == null || s.isBlank()) return null;
                return new TickerSuggestion(s.trim().toUpperCase(), null, null);
            }
        });

        final boolean[] programmaticChange = {false};

        Timeline maDebounce = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(300),
                ev -> {
                    String raw = maTickerCombo.getEditor().getText();
                    if (raw == null || raw.length() < 2) return;
                    String query = raw.trim().toUpperCase();
                    CompletableFuture
                            .supplyAsync(() -> BrapiClient.searchTickers(query))
                            .thenComposeAsync(results -> {
                                if (!results.isEmpty()) return CompletableFuture.completedFuture(results);
                                if (query.length() > 4) {
                                    String short4 = query.substring(0, 4);
                                    return CompletableFuture.supplyAsync(() -> BrapiClient.searchTickers(short4));
                                }
                                return CompletableFuture.completedFuture(results);
                            })
                            .thenAcceptAsync(results -> Platform.runLater(() -> {
                                if (results.isEmpty()) {
                                    TickerSuggestion noResult = new TickerSuggestion(
                                            query, "Pressione Enter para buscar diretamente", null);
                                    maTickerCombo.getItems().setAll(noResult);
                                } else {
                                    maTickerCombo.getItems().setAll(results);
                                }
                                maTickerCombo.show();
                            }));
                }
        ));

        maTickerCombo.getEditor().textProperty().addListener((obs, old, val) -> {
            if (programmaticChange[0]) return;
            maDebounce.stop();
            maDebounce.playFromStart();
        });

        maTickerCombo.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                maDebounce.stop();
                programmaticChange[0] = true;
                maTickerCombo.getEditor().setText(selected.ticker());
                programmaticChange[0] = false;
                maTickerCombo.hide();
            }
        });

        maTickerCombo.getEditor().setOnAction(e -> loadMAChart());

        maFromPicker.setPromptText("Data inicial");
        maFromPicker.setValue(LocalDate.now().minusMonths(6));
        maFromPicker.setPrefWidth(140);
        maFromPicker.setAccessibleText("Data inicial");
        com.daniel.presentation.view.util.UiDates.applyBrFormat(maFromPicker);

        maToPicker.setPromptText("Data final");
        maToPicker.setValue(LocalDate.now());
        maToPicker.setPrefWidth(140);
        maToPicker.setAccessibleText("Data final");
        com.daniel.presentation.view.util.UiDates.applyBrFormat(maToPicker);

        maCalcBtn.getStyleClass().add("button");
        maCalcBtn.setTooltip(new Tooltip("Calcular preço e médias móveis para o intervalo (Enter)"));
        maCalcBtn.setOnAction(e -> loadMAChart());

        maShortPeriod.setPrefWidth(80);
        maShortPeriod.setEditable(true);
        maShortPeriod.setTooltip(new Tooltip("Janela da média curta, em pregões"));
        maLongPeriod.setPrefWidth(80);
        maLongPeriod.setEditable(true);
        maLongPeriod.setTooltip(new Tooltip("Janela da média longa, em pregões; define o mínimo de pontos necessários"));

        // Inputs wrap instead of one fixed row, so a narrow window keeps every control reachable
        maInputBar.setAlignment(Pos.CENTER_LEFT);
        maInputBar.setRowValignment(javafx.geometry.VPos.CENTER);
        maInputBar.getChildren().addAll(
                maTickerCombo,
                labelled("De", maFromPicker),
                labelled("Até", maToPicker),
                labelled("Média curta", maShortPeriod),
                labelled("Média longa", maLongPeriod),
                maCalcBtn);

        maLoadingLabel.getStyleClass().add("text-helper");
        hide(maLoadingLabel);

        // ── Chart setup — temporal NumberAxis ──
        maChart.setAnimated(false);
        maChart.setLegendVisible(false);
        maChart.setCreateSymbols(false);
        maChart.setMinHeight(280);
        maYAxis.setLabel("Preço (R$)");
        maXAxis.setLabel("");
        maYAxis.setAutoRanging(false);

        ChartAxisUtils.installTemporalAxis(maXAxis, maChart, () -> MA_DATE_FMT, () -> lastMAEpochSecs);
        ChartAxisUtils.hideTemporalAxisLabels(maXAxis); // the crosshair is the reading mechanism

        StackPane chartWrapper = ChartCrosshair.installTemporal(maChart,
                epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC).format(MA_DATE_FMT),
                y -> money(y));

        maCrossLabel.getStyleClass().add("text-secondary");
        maCrossLabel.setWrapText(true);

        return new Panel("Média móvel", maInputBar, hintLegend, maLoadingLabel, chartWrapper, maCrossLabel)
                .subtitle("Preço de fechamento e duas médias móveis simples de um ticker. "
                        + "A média longa exige pelo menos esse número de pregões no intervalo (ex.: 200 ≈ 10 meses).");
    }

    private static HBox labelled(String text, Control control) {
        Label l = new Label(text);
        l.getStyleClass().add("text-secondary");
        l.setLabelFor(control);
        HBox box = new HBox(6, l, control);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void loadMAChart() {
        String ticker = maTickerCombo.getEditor().getText() == null ? "" : maTickerCombo.getEditor().getText().trim().toUpperCase();
        LocalDate from = maFromPicker.getValue();
        LocalDate to   = maToPicker.getValue();

        if (ticker.isBlank()) {
            maCrossLabel.setText("Informe o ticker do ativo.");
            return;
        }
        if (from == null || to == null) {
            maCrossLabel.setText("Informe a data inicial e a data final.");
            return;
        }
        if (from.isAfter(to)) {
            maCrossLabel.setText("A data inicial deve ser anterior à data final.");
            return;
        }

        maChart.getData().clear();
        show(maLoadingLabel);

        final LocalDate fFrom = from;
        final LocalDate fTo   = to;
        final String fTicker  = ticker;

        CompletableFuture.supplyAsync(() -> {
            try {
                return AssetHistoryClient.fetchHistoryByDateRange(fTicker, fFrom, fTo);
            } catch (Exception e) {
                LOG.warning("[MA] " + fTicker + ": " + e.getMessage());
                return List.<HistoryPoint>of();
            }
        }).thenAccept(points -> Platform.runLater(() -> renderMAChart(points, fTicker)));
    }

    private void renderMAChart(List<HistoryPoint> points, String ticker) {
        hide(maLoadingLabel);
        maChart.getData().clear();

        if (points.isEmpty()) {
            maCrossLabel.setText("Sem dados para " + ticker + " no intervalo informado. Confira o ticker, a conexão ou o token BRAPI em Configurações.");
            return;
        }

        int shortPeriod = maShortPeriod.getValue();
        int longPeriod  = maLongPeriod.getValue();

        if (shortPeriod >= longPeriod) {
            maCrossLabel.setText("A média curta deve ter janela menor que a média longa.");
            return;
        }

        int total = points.size();
        if (total < longPeriod) {
            int monthsNeeded = (int) Math.ceil(longPeriod / 21.0);
            maCrossLabel.setText("Dados insuficientes: " + total + " pregões obtidos; a média longa de " + longPeriod
                    + " exige ~" + longPeriod + " pregões (~" + monthsNeeded + " meses). Amplie o intervalo de datas.");
            return;
        }

        XYChart.Series<Number, Number> priceSeries   = new XYChart.Series<>();
        XYChart.Series<Number, Number> maShortSeries = new XYChart.Series<>();
        XYChart.Series<Number, Number> maLongSeries  = new XYChart.Series<>();

        priceSeries.setName("Preço " + ticker);
        maShortSeries.setName("Média " + shortPeriod);
        maLongSeries.setName("Média " + longPeriod);

        double minPrice = Double.MAX_VALUE;
        double maxPrice = -Double.MAX_VALUE;

        List<Long> epochSecs = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            HistoryPoint p   = points.get(i);
            long sec         = p.dateTime().toEpochSecond(ZoneOffset.UTC);
            double price     = p.close();

            priceSeries.getData().add(new XYChart.Data<>(sec, price));
            epochSecs.add(sec);
            if (price < minPrice) minPrice = price;
            if (price > maxPrice) maxPrice = price;

            if (i >= shortPeriod - 1) {
                double sum = 0;
                for (int j = i - shortPeriod + 1; j <= i; j++) sum += points.get(j).close();
                maShortSeries.getData().add(new XYChart.Data<>(sec, sum / shortPeriod));
            }

            if (i >= longPeriod - 1) {
                double sum = 0;
                for (int j = i - longPeriod + 1; j <= i; j++) sum += points.get(j).close();
                maLongSeries.getData().add(new XYChart.Data<>(sec, sum / longPeriod));
            }
        }

        lastMAEpochSecs = epochSecs;

        // Eixo Y com padding de 5%
        double range   = maxPrice - minPrice;
        double padding = range > 0 ? range * 0.05 : maxPrice * 0.02;
        maYAxis.setLowerBound(minPrice - padding);
        maYAxis.setUpperBound(maxPrice + padding);
        maYAxis.setTickUnit((range + 2 * padding) / 6.0);

        maChart.getData().add(priceSeries);
        maChart.getData().add(maShortSeries);
        maChart.getData().add(maLongSeries);

        // Detectar cruzamento nos dois últimos pontos sobrepostos
        int shortSize = maShortSeries.getData().size();
        int longSize  = maLongSeries.getData().size();
        if (shortSize >= 2 && longSize >= 2) {
            double shortLast = maShortSeries.getData().get(shortSize - 1).getYValue().doubleValue();
            double longLast  = maLongSeries.getData().get(longSize - 1).getYValue().doubleValue();
            double shortPrev = maShortSeries.getData().get(shortSize - 2).getYValue().doubleValue();
            double longPrev  = maLongSeries.getData().get(longSize - 2).getYValue().doubleValue();

            if (shortLast > longLast && shortPrev <= longPrev) {
                maCrossLabel.setText("Cruzamento para cima: a média curta passou acima da longa no último pregão (lido como sinal de alta).");
            } else if (shortLast < longLast && shortPrev >= longPrev) {
                maCrossLabel.setText("Cruzamento para baixo: a média curta passou abaixo da longa no último pregão (lido como sinal de baixa).");
            } else {
                maCrossLabel.setText("Sem cruzamento recente detectado.");
            }
        } else {
            maCrossLabel.setText("Sem cruzamento recente detectado.");
        }

        Motion.chartRefresh(maChart); // data assigned above; the pulse only shows it changed

        // Style the lines after rendering — the double runLater lets JavaFX finish the layout
        // pass and expose the series nodes in the scene graph.
        Platform.runLater(() -> Platform.runLater(() -> {
            ChartSeriesStyle.accent(maChart.lookup(".series0.chart-series-line"), 1.5);
            ChartSeriesStyle.warningDashed(maChart.lookup(".series1.chart-series-line"), 2.0);
            ChartSeriesStyle.info(maChart.lookup(".series2.chart-series-line"), 2.0);
        }));

        Platform.runLater(() -> ChartAxisUtils.refreshTemporalAxis(maXAxis, lastMAEpochSecs, maChart.getWidth()));
    }

    private List<String> getPortfolioTickers() {
        return daily.listTypes().stream()
                .map(t -> t.ticker())
                .filter(ticker -> ticker != null && !ticker.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Freshness chip of the day-change block: says when the quotes ON THIS PAGE were obtained
     * (not a global BRAPI timestamp another page may have set). B3 data has a typical 15–30 min
     * delay on the free BRAPI plan — the chip never implies real-time data.
     */
    private void updateFreshnessChip() {
        long lastFetch = quotesObtainedAtMs;
        freshnessChip.getStyleClass().removeAll("brapi-chip-success", "brapi-chip-warn", "brapi-chip-neutral");
        if (lastFetch == 0) {
            freshnessChip.setText("Sem cotações ainda");
            freshnessChip.getStyleClass().add("brapi-chip-neutral");
        } else {
            long elapsedMin = (System.currentTimeMillis() - lastFetch) / 60_000L;
            if (elapsedMin < 2) {
                freshnessChip.setText("Cotações obtidas agora · atraso ~15–30 min");
                freshnessChip.getStyleClass().add("brapi-chip-success");
            } else if (elapsedMin < 60) {
                freshnessChip.setText("Cotações há " + elapsedMin + " min · atraso ~15–30 min");
                freshnessChip.getStyleClass().add(elapsedMin <= 30 ? "brapi-chip-success" : "brapi-chip-warn");
            } else {
                long elapsedHours = elapsedMin / 60;
                freshnessChip.setText("Cotações há " + elapsedHours + "h");
                freshnessChip.getStyleClass().add("brapi-chip-warn");
            }
        }
        freshnessChip.setTooltip(new Tooltip(freshnessChip.getText()
                + "\nCotações da B3 pela BRAPI (plano gratuito): atraso típico de 15–30 min em relação ao pregão."));
    }
}

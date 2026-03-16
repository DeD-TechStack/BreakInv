package com.daniel.presentation.view.pages;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.infrastructure.api.BrapiClient.TickerSuggestion;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.StringConverter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.ChartAxisUtils;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.viewmodel.RankingViewModel;
import com.daniel.presentation.viewmodel.RankingViewModel.RankingRow;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Página de Ranking de Ativos da carteira por variação diária.
 *
 * <p>Gráficos de rentabilidade acumulada e média móvel usam {@link NumberAxis}
 * com epoch seconds como eixo X, garantindo escala temporal proporcional ao
 * intervalo real entre os dados. O BarChart de variação diária mantém
 * {@link CategoryAxis} pois exibe nomes de ativos, não datas.</p>
 */
public final class RankingPage implements Page {

    private static final Logger LOG = Logger.getLogger(RankingPage.class.getName());
    private static final DateTimeFormatter MA_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final DailyTrackingUseCase daily;

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    // ── Tables ──
    private final TableView<RankingRow> gainersTable = new TableView<>();
    private final TableView<RankingRow> losersTable  = new TableView<>();

    // ── Bar chart (variação do dia) — CategoryAxis, pois X = ticker names ──
    private final CategoryAxis barXAxis = new CategoryAxis();
    private final NumberAxis   barYAxis = new NumberAxis();
    private final BarChart<String, Number> barChart = new BarChart<>(barXAxis, barYAxis);

    // ── Return chart (rentabilidade acumulada) — NumberAxis temporal ──
    private final NumberAxis retXAxis = new NumberAxis();
    private final NumberAxis retYAxis = new NumberAxis();
    private final LineChart<Number, Number> returnChart = new LineChart<>(retXAxis, retYAxis);
    private final ToggleGroup returnPeriodGroup = new ToggleGroup();
    private Label returnLoadingLabel;
    /** Epoch seconds dos pontos do último render do returnChart. */
    private List<Long> lastReturnEpochSecs = List.of();
    /** Formatter atual do returnChart — atualizado ao mudar período. */
    private DateTimeFormatter currentReturnFmt = DateTimeFormatter.ofPattern("dd/MM");

    // ── Moving Average chart — NumberAxis temporal ──
    private final NumberAxis maXAxis = new NumberAxis();
    private final NumberAxis maYAxis = new NumberAxis();
    private final LineChart<Number, Number> maChart = new LineChart<>(maXAxis, maYAxis);
    private final ComboBox<TickerSuggestion> maTickerCombo = new ComboBox<>();
    private final javafx.scene.control.DatePicker maFromPicker = new javafx.scene.control.DatePicker();
    private final javafx.scene.control.DatePicker maToPicker   = new javafx.scene.control.DatePicker();
    private Label maLoadingLabel;
    /** Epoch seconds dos pontos do último render do maChart. */
    private List<Long> lastMAEpochSecs = List.of();

    // ── State ──
    private Map<String, StockData> lastStocks = new HashMap<>();
    private boolean loading = false;

    // ── Loading / empty overlays ──
    private Label loadingLabel;
    private VBox emptyState;
    private VBox contentArea;

    public RankingPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Ranking de Ativos",
                "Desempenho diário dos ativos da sua carteira");

        emptyState  = buildEmptyState();
        contentArea = buildContent();

        loadingLabel = new Label("Carregando dados...");
        loadingLabel.getStyleClass().add("section-subtitle");
        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        root.getChildren().addAll(header, loadingLabel, emptyState, contentArea);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        loadStockData();
    }

    // ── Build helpers ──────────────────────────────────────────────────────

    private VBox buildEmptyState() {
        VBox box = new VBox(8);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        Label icon  = new Label("🏆");
        icon.getStyleClass().add("empty-icon");
        Label title = new Label("Nenhum ativo com ticker encontrado");
        title.getStyleClass().add("empty-title");
        Label hint  = new Label("Cadastre ativos com ticker em \"Carteira\" para ver o ranking");
        hint.getStyleClass().add("empty-hint");
        box.getChildren().addAll(icon, title, hint);
        return box;
    }

    private VBox buildContent() {
        // ── Gainers / Losers tables side by side ──
        VBox gainersCard = buildTableCard("📈 Maiores Altas", gainersTable);
        VBox losersCard  = buildTableCard("📉 Maiores Baixas", losersTable);

        HBox tables = new HBox(16, gainersCard, losersCard);
        HBox.setHgrow(gainersCard, Priority.ALWAYS);
        HBox.setHgrow(losersCard,  Priority.ALWAYS);

        // ── Bar chart card — CategoryAxis (ticker names) ──
        barChart.setAnimated(false);
        barChart.setLegendVisible(false);
        barChart.setMinHeight(280);
        barChart.setCategoryGap(30);
        barChart.getStyleClass().add("bar-chart");
        barYAxis.setAutoRanging(true);
        barYAxis.setLabel("Variação (%)");
        barXAxis.setLabel("");
        ChartAxisUtils.installSmartAxis(barXAxis, barChart);

        Label chartTitle = new Label("VARIAÇÃO DO DIA (%)");
        chartTitle.getStyleClass().add("card-title");

        Label chartHint = new Label("Clique em ativos para filtrar (clique novamente para desmarcar)");
        chartHint.getStyleClass().add("section-subtitle");

        VBox chartCard = new VBox(8, chartTitle, chartHint, barChart);
        chartCard.getStyleClass().add("chart-card");

        VBox content = new VBox(16, tables, chartCard, buildReturnChartCard(), buildMAChartCard());
        return content;
    }

    private VBox buildTableCard(String title, TableView<RankingRow> table) {
        Label lbl = new Label(title);
        lbl.getStyleClass().add("card-title");

        TableColumn<RankingRow, String> tickerCol = new TableColumn<>("Ticker");
        tickerCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ticker()));
        tickerCol.setPrefWidth(80);

        TableColumn<RankingRow, Number> priceCol = new TableColumn<>("Preço");
        priceCol.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().price()));
        priceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : String.format("R$ %.2f", item.doubleValue()).replace('.', ','));
            }
        });
        priceCol.setPrefWidth(90);

        TableColumn<RankingRow, Number> chgCol = new TableColumn<>("Var. (%)");
        chgCol.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().changePct()));
        chgCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg");
                if (empty || item == null) { setText(null); return; }
                double v = item.doubleValue();
                setText(String.format("%s%.2f%%", v >= 0 ? "+" : "", v).replace('.', ','));
                getStyleClass().add(v >= 0 ? "pos" : "neg");
            }
        });
        chgCol.setPrefWidth(90);

        table.getColumns().addAll(tickerCol, priceCol, chgCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(220);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        table.setRowFactory(tv -> {
            TableRow<RankingRow> row = new TableRow<>();
            final boolean[] wasSelectedOnPress = {false};
            final List<Integer>[] prevIndices = new List[]{List.of()};

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
                (ListChangeListener<RankingRow>) change -> onTableSelectionChanged());

        VBox card = new VBox(8, lbl, table);
        card.getStyleClass().add("card");
        return card;
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private void loadStockData() {
        if (loading) return;

        List<String> tickers = getPortfolioTickers();
        if (tickers.isEmpty()) {
            setContentVisible(false);
            return;
        }

        loading = true;
        loadingLabel.setVisible(true);
        loadingLabel.setManaged(true);
        setContentVisible(false);

        String joined = String.join(",", tickers);

        CompletableFuture.supplyAsync(() -> BrapiClient.fetchMultipleStocksSafe(joined))
                .thenAccept(result -> Platform.runLater(() -> {
            loading = false;
            loadingLabel.setVisible(false);
            loadingLabel.setManaged(false);

            if (!result.isSuccess()) {
                String msg = switch (result.getReason()) {
                    case NO_TOKEN      -> "Configure um token BRAPI em Configurações para ver cotações.";
                    case HTTP_ERROR    -> "Serviço BRAPI indisponível (HTTP " + result.getDetail() + "). Exibindo dados em cache.";
                    case NETWORK_ERROR -> "Sem conexão. Cotações podem estar desatualizadas.";
                    case NOT_FOUND     -> "Alguns tickers não foram encontrados na BRAPI.";
                    case PARSE_ERROR   -> "Erro ao processar dados da BRAPI. Tente novamente.";
                };
                ToastHost.showWarn(msg);
                setContentVisible(false);
                return;
            }

            Map<String, StockData> stocks = result.getData();
            lastStocks = new HashMap<>();
            for (StockData sd : stocks.values()) {
                if (sd != null && sd.ticker() != null) lastStocks.put(sd.ticker(), sd);
            }
            populateTables(lastStocks);
            setContentVisible(true);
            reloadChart();
            reloadReturnChart();
        }));
    }

    private void populateTables(Map<String, StockData> stocks) {
        gainersTable.setItems(FXCollections.observableArrayList(RankingViewModel.topGainers(stocks)));
        losersTable.setItems(FXCollections.observableArrayList(RankingViewModel.topLosers(stocks)));
    }

    private void onTableSelectionChanged() {
        Platform.runLater(() -> {
            Set<String> selected = new LinkedHashSet<>();
            for (RankingRow row : gainersTable.getSelectionModel().getSelectedItems()) {
                if (row != null) selected.add(row.ticker());
            }
            for (RankingRow row : losersTable.getSelectionModel().getSelectedItems()) {
                if (row != null) selected.add(row.ticker());
            }

            if (selected.isEmpty()) {
                reloadChart();
            } else {
                reloadChartForTickers(new ArrayList<>(selected));
            }
            reloadReturnChart();
        });
    }

    private void reloadChart() {
        List<String> tickers = new ArrayList<>(lastStocks.keySet());
        if (tickers.size() > 8) tickers = tickers.subList(0, 8);
        reloadChartForTickers(tickers);
    }

    private void reloadChartForTickers(List<String> tickers) {
        barChart.getData().clear();

        if (tickers.isEmpty()) return;

        int n = tickers.size();
        barChart.setCategoryGap(n <= 3 ? 60 : n <= 6 ? 30 : 12);

        for (String ticker : tickers) {
            StockData d = lastStocks.get(ticker);
            if (d == null || !d.isValid()) continue;

            double pct = d.regularMarketChangePercent();

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

        Platform.runLater(() -> ChartAxisUtils.refreshLabels(barXAxis, barChart.getWidth()));
    }

    // ── Return chart build & load ───────────────────────────────────────────

    private VBox buildReturnChartCard() {
        HBox periodBar = new HBox(6);
        periodBar.setAlignment(Pos.CENTER_LEFT);

        for (AssetHistoryClient.Period p : AssetHistoryClient.Period.values()) {
            ToggleButton tb = new ToggleButton(p.label);
            tb.setToggleGroup(returnPeriodGroup);
            tb.setUserData(p);
            tb.getStyleClass().add("period-btn");
            periodBar.getChildren().add(tb);
            if (p == AssetHistoryClient.Period.ONE_MONTH) tb.setSelected(true);
        }

        returnPeriodGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null && !lastStocks.isEmpty()) reloadReturnChart();
        });

        returnChart.setAnimated(false);
        returnChart.setLegendVisible(true);
        returnChart.setCreateSymbols(false);
        returnChart.setMinHeight(300);
        retYAxis.setLabel("Rentabilidade (%)");
        retXAxis.setLabel("");

        // Eixo temporal: distribuição proporcional ao intervalo real entre datas
        ChartAxisUtils.installTemporalAxis(retXAxis, returnChart,
                () -> currentReturnFmt,
                () -> lastReturnEpochSecs);
        // Oculta labels do eixo X — hover/crosshair é o mecanismo principal de leitura
        ChartAxisUtils.hideTemporalAxisLabels(retXAxis);

        returnLoadingLabel = new Label("Carregando histórico...");
        returnLoadingLabel.getStyleClass().add("section-subtitle");
        returnLoadingLabel.setVisible(false);
        returnLoadingLabel.setManaged(false);

        StackPane returnWrapper = ChartCrosshair.installTemporal(returnChart,
                epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC)
                        .format(currentReturnFmt),
                y -> String.format("%s%.2f%%", y >= 0 ? "+" : "", y).replace('.', ','));

        Label chartTitle = new Label("RENTABILIDADE ACUMULADA (%)");
        chartTitle.getStyleClass().add("card-title");

        Label chartHint = new Label("Selecione ativos nas tabelas para comparar • Sem seleção: exibe todos os ativos");
        chartHint.getStyleClass().add("section-subtitle");

        VBox card = new VBox(8, chartTitle, chartHint, periodBar, returnLoadingLabel, returnWrapper);
        card.getStyleClass().add("chart-card");
        return card;
    }

    private void reloadReturnChart() {
        List<String> tickers = getSelectedOrAllTickers();
        returnChart.getData().clear();
        if (tickers.isEmpty()) return;

        AssetHistoryClient.Period period = getSelectedReturnPeriod();
        currentReturnFmt = DateTimeFormatter.ofPattern(period.datePattern);
        returnLoadingLabel.setVisible(true);
        returnLoadingLabel.setManaged(true);

        List<CompletableFuture<Map.Entry<String, List<HistoryPoint>>>> futures = tickers.stream()
                .map(t -> CompletableFuture.<Map.Entry<String, List<HistoryPoint>>>supplyAsync(() -> {
                    try {
                        return Map.entry(t, AssetHistoryClient.fetchHistory(t, period));
                    } catch (Exception e) {
                        return Map.entry(t, List.<HistoryPoint>of());
                    }
                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    Map<String, List<HistoryPoint>> all = new LinkedHashMap<>();
                    for (var f : futures) {
                        var entry = f.join();
                        if (!entry.getValue().isEmpty()) all.put(entry.getKey(), entry.getValue());
                    }
                    Platform.runLater(() -> renderReturnChart(all));
                });
    }

    private void renderReturnChart(Map<String, List<HistoryPoint>> allHistories) {
        returnLoadingLabel.setVisible(false);
        returnLoadingLabel.setManaged(false);
        returnChart.getData().clear();

        if (allHistories.isEmpty()) {
            ToastHost.showWarn("Sem histórico disponível. Configure um token BRAPI em Configurações.");
            return;
        }

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
        Platform.runLater(() -> ChartAxisUtils.refreshTemporalAxis(retXAxis, lastReturnEpochSecs, returnChart.getWidth()));
    }

    private List<String> getSelectedOrAllTickers() {
        Set<String> selected = new LinkedHashSet<>();
        for (RankingRow row : gainersTable.getSelectionModel().getSelectedItems()) {
            if (row != null) selected.add(row.ticker());
        }
        for (RankingRow row : losersTable.getSelectionModel().getSelectedItems()) {
            if (row != null) selected.add(row.ticker());
        }
        if (!selected.isEmpty()) return new ArrayList<>(selected);
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

    private static void applyReturnTooltip(XYChart.Data<String, Number> dp,
                                            String tip, String baseColor, String hoverColor) {
        javafx.scene.Node n = dp.getNode();
        if (n != null) {
            applyReturnNode(n, tip, baseColor, hoverColor);
        } else {
            dp.nodeProperty().addListener((obs, o, node) -> {
                if (node != null) applyReturnNode(node, tip, baseColor, hoverColor);
            });
        }
    }

    private static void applyReturnNode(javafx.scene.Node node, String tip,
                                         String baseColor, String hoverColor) {
        node.setStyle("-fx-background-color: " + baseColor + "; -fx-padding: 4; -fx-background-radius: 50;");
        Tooltip tp = new Tooltip(tip);
        tp.setShowDelay(javafx.util.Duration.ZERO);
        Tooltip.install(node, tp);
        node.setOnMouseEntered(e -> node.setStyle(
                "-fx-background-color: " + hoverColor + "; -fx-padding: 5; -fx-background-radius: 50;"));
        node.setOnMouseExited(e -> node.setStyle(
                "-fx-background-color: " + baseColor + "; -fx-padding: 4; -fx-background-radius: 50;"));
    }

    private void applyBarTooltip(XYChart.Data<String, Number> bar, double pct, StockData sd) {
        javafx.scene.Node node = bar.getNode();
        if (node == null) return;

        String color      = pct >= 0 ? "#22c55e" : "#ef4444";
        String hoverColor = pct >= 0 ? "#16a34a" : "#dc2626";
        String baseStyle  = "-fx-background-color: " + color + "; -fx-background-radius: 3 3 0 0;";

        node.setStyle(baseStyle);

        if (node instanceof StackPane sp
                && sp.getChildren().stream().noneMatch(c -> c instanceof Label)) {
            Label lbl = new Label(bar.getXValue());
            lbl.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
            lbl.setRotate(-90);
            lbl.setMouseTransparent(true);
            StackPane.setAlignment(lbl, Pos.CENTER);
            sp.getChildren().add(lbl);
        }

        String sign = pct >= 0 ? "+" : "";
        Tooltip tp = new Tooltip(
                bar.getXValue()
                + "\n" + sign + String.format("%.2f%%", pct).replace('.', ',')
                + "\nPreço:    R$ " + String.format("%.2f", sd.regularMarketPrice()).replace('.', ',')
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

    // ── Moving Average chart ─────────────────────────────────────────────────

    private VBox buildMAChartCard() {
        Label title = new Label("ANÁLISE DE MÉDIA MÓVEL");
        title.getStyleClass().add("card-title");

        Label hint = new Label("Informe o ticker e o período para calcular a média móvel do ativo");
        hint.getStyleClass().add("section-subtitle");

        // ── Input fields ──
        maTickerCombo.setEditable(true);
        maTickerCombo.setPromptText("Digite ticker ou nome (ex: PETR4 ou Petrobras)");
        maTickerCombo.setPrefWidth(280);
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
        maFromPicker.setPrefWidth(145);

        maToPicker.setPromptText("Data final");
        maToPicker.setValue(LocalDate.now());
        maToPicker.setPrefWidth(145);

        Button calcBtn = new Button("Calcular");
        calcBtn.getStyleClass().addAll("btn-primary", "btn-sm");
        calcBtn.setOnAction(e -> loadMAChart());

        HBox inputBar = new HBox(8,
                maTickerCombo,
                new Label("De:"), maFromPicker,
                new Label("Até:"), maToPicker,
                calcBtn);
        inputBar.setAlignment(Pos.CENTER_LEFT);
        inputBar.getStyleClass().add("toolbar");

        maLoadingLabel = new Label("Calculando média móvel...");
        maLoadingLabel.getStyleClass().add("section-subtitle");
        maLoadingLabel.setVisible(false);
        maLoadingLabel.setManaged(false);

        // ── Chart setup — NumberAxis temporal ──
        maChart.setAnimated(false);
        maChart.setLegendVisible(true);
        maChart.setCreateSymbols(false);
        maChart.setMinHeight(300);
        maYAxis.setLabel("Preço (R$)");
        maXAxis.setLabel("");
        maYAxis.setAutoRanging(false);

        ChartAxisUtils.installTemporalAxis(maXAxis, maChart,
                () -> MA_DATE_FMT,
                () -> lastMAEpochSecs);
        // Oculta labels do eixo X — hover/crosshair é o mecanismo principal de leitura
        ChartAxisUtils.hideTemporalAxisLabels(maXAxis);

        StackPane chartWrapper = ChartCrosshair.installTemporal(maChart,
                epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC)
                        .format(MA_DATE_FMT),
                y -> "R$ " + String.format("%.2f", y).replace('.', ','));

        VBox card = new VBox(8, title, hint, inputBar, maLoadingLabel, chartWrapper);
        card.getStyleClass().add("chart-card");
        return card;
    }

    private void loadMAChart() {
        String ticker = maTickerCombo.getEditor().getText().trim().toUpperCase();
        LocalDate from = maFromPicker.getValue();
        LocalDate to   = maToPicker.getValue();

        if (ticker.isBlank()) {
            ToastHost.showWarn("Informe o ticker do ativo.");
            return;
        }
        if (from == null || to == null) {
            ToastHost.showWarn("Informe a data de início e a data final.");
            return;
        }
        if (from.isAfter(to)) {
            ToastHost.showWarn("A data de início deve ser anterior à data final.");
            return;
        }

        maChart.getData().clear();
        maLoadingLabel.setVisible(true);
        maLoadingLabel.setManaged(true);

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
        maLoadingLabel.setVisible(false);
        maLoadingLabel.setManaged(false);
        maChart.getData().clear();

        if (points.isEmpty()) {
            ToastHost.showWarn("Sem dados para " + ticker + " no período informado. Verifique o ticker ou configure um token BRAPI.");
            return;
        }

        int total = points.size();
        int maWindow;
        if      (total < 10)  { ToastHost.showWarn("Período muito curto para calcular média móvel (mínimo 10 pontos)."); return; }
        else if (total < 30)  maWindow = 5;
        else if (total < 60)  maWindow = 10;
        else if (total < 200) maWindow = 20;
        else                  maWindow = 50;

        XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
        priceSeries.setName("Preço " + ticker);

        XYChart.Series<Number, Number> maSeries = new XYChart.Series<>();
        maSeries.setName("MM" + maWindow);

        double minPrice = Double.MAX_VALUE;
        double maxPrice = -Double.MAX_VALUE;

        List<Long> epochSecs = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            HistoryPoint p = points.get(i);
            long sec = p.dateTime().toEpochSecond(ZoneOffset.UTC);
            double price = p.close();

            priceSeries.getData().add(new XYChart.Data<>(sec, price));
            epochSecs.add(sec);
            if (price < minPrice) minPrice = price;
            if (price > maxPrice) maxPrice = price;

            if (i >= maWindow - 1) {
                double sum = 0;
                for (int j = i - maWindow + 1; j <= i; j++) sum += points.get(j).close();
                long maSec = points.get(i).dateTime().toEpochSecond(ZoneOffset.UTC);
                maSeries.getData().add(new XYChart.Data<>(maSec, sum / maWindow));
            }
        }

        lastMAEpochSecs = epochSecs;

        // Eixo Y com padding de 5%
        double range   = maxPrice - minPrice;
        double padding = range > 0 ? range * 0.05 : maxPrice * 0.02;
        maYAxis.setLowerBound(minPrice - padding);
        maYAxis.setUpperBound(maxPrice + padding);
        maYAxis.setTickUnit((range + 2 * padding) / 6.0);

        maChart.getData().addAll(priceSeries, maSeries);

        // Estiliza as linhas após renderização
        Platform.runLater(() -> {
            javafx.scene.Node priceLine = maChart.lookup(".series0.chart-series-line");
            if (priceLine != null)
                priceLine.setStyle("-fx-stroke: #22c55e; -fx-stroke-width: 1.5;");

            javafx.scene.Node maLine = maChart.lookup(".series1.chart-series-line");
            if (maLine != null)
                maLine.setStyle("-fx-stroke: #f59e0b; -fx-stroke-width: 2.5; -fx-stroke-dash-array: 8 4;");
        });

        Platform.runLater(() -> ChartAxisUtils.refreshTemporalAxis(maXAxis, lastMAEpochSecs, maChart.getWidth()));
    }

    private List<String> getPortfolioTickers() {
        return daily.listTypes().stream()
                .map(t -> t.ticker())
                .filter(ticker -> ticker != null && !ticker.isBlank())
                .distinct()
                .toList();
    }

    private void setContentVisible(boolean visible) {
        emptyState.setVisible(!visible);
        emptyState.setManaged(!visible);
        contentArea.setVisible(visible);
        contentArea.setManaged(visible);
    }
}

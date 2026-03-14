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
 * Exibe top altas e top baixas, com BarChart de variação % dos ativos selecionados.
 */
public final class RankingPage implements Page {

    private static final Logger LOG = Logger.getLogger(RankingPage.class.getName());

    private final DailyTrackingUseCase daily;

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    // ── Tables ──
    private final TableView<RankingRow> gainersTable = new TableView<>();
    private final TableView<RankingRow> losersTable  = new TableView<>();

    // ── Bar chart (variação do dia) ──
    private final CategoryAxis barXAxis = new CategoryAxis();
    private final NumberAxis   barYAxis = new NumberAxis();
    private final BarChart<String, Number> barChart = new BarChart<>(barXAxis, barYAxis);

    // ── Return / history chart (rentabilidade acumulada) ──
    private final CategoryAxis retXAxis = new CategoryAxis();
    private final NumberAxis   retYAxis = new NumberAxis();
    private final LineChart<String, Number> returnChart = new LineChart<>(retXAxis, retYAxis);
    private final ToggleGroup returnPeriodGroup = new ToggleGroup();
    private Label returnLoadingLabel;

    // ── Moving Average chart ──
    private final CategoryAxis maXAxis = new CategoryAxis();
    private final NumberAxis   maYAxis = new NumberAxis();
    private final LineChart<String, Number> maChart = new LineChart<>(maXAxis, maYAxis);
    private final ComboBox<TickerSuggestion> maTickerCombo = new ComboBox<>();
    private final javafx.scene.control.DatePicker maFromPicker = new javafx.scene.control.DatePicker();
    private final javafx.scene.control.DatePicker maToPicker   = new javafx.scene.control.DatePicker();
    private Label maLoadingLabel;

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

        // ── Bar chart card ──
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

        // Seleção por toggle sem CTRL:
        //  - MOUSE_PRESSED: captura quais índices já estavam selecionados e se a
        //    linha clicada já estava selecionada — sem consumir o evento, deixando
        //    o JavaFX processar normalmente (MOUSE_CLICKED sempre dispara).
        //  - MOUSE_CLICKED: corrige o estado depois do comportamento padrão do
        //    JavaFX que, em modo MULTIPLE sem CTRL, substitui toda a seleção.
        table.setRowFactory(tv -> {
            TableRow<RankingRow> row = new TableRow<>();
            final boolean[] wasSelectedOnPress = {false};
            final List<Integer>[] prevIndices = new List[]{List.of()};

            // Usa addEventFilter (fase de captura) para garantir que o estado da seleção
            // seja capturado ANTES do CellBehaviorBase (handler interno do JavaFX) alterar a seleção.
            row.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (!row.isEmpty()) {
                    wasSelectedOnPress[0] = row.isSelected();
                    // Guarda os índices selecionados ANTES do JavaFX mudar a seleção
                    prevIndices[0] = new ArrayList<>(tv.getSelectionModel().getSelectedIndices());
                }
            });

            row.setOnMouseClicked(event -> {
                if (row.isEmpty()) return;
                if (wasSelectedOnPress[0]) {
                    // linha já estava selecionada → desmarcar apenas ela
                    tv.getSelectionModel().clearSelection(tv.getItems().indexOf(row.getItem()));
                } else {
                    // linha não estava selecionada → o JavaFX a selecionou e limpou
                    // as demais; restaura as anteriores para manter acumulação
                    for (int idx : prevIndices[0]) {
                        tv.getSelectionModel().select(idx);
                    }
                }
            });
            return row;
        });

        // ListChangeListener detecta qualquer mudança na seleção múltipla
        // (selectedItemProperty só monitora o último item focado)
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
            // Re-indexa por StockData.ticker() para garantir que lastStocks.get(row.ticker())
            // sempre encontre o dado, independente do formato da chave retornada pela API
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
        // Difere para o próximo pulso do FX, após todo o processamento do evento de mouse
        // ter terminado — evita múltiplas limpezas/redesenhos do gráfico em sequência.
        Platform.runLater(() -> {
            Set<String> selected = new LinkedHashSet<>();
            for (RankingRow row : gainersTable.getSelectionModel().getSelectedItems()) {
                if (row != null) selected.add(row.ticker());
            }
            for (RankingRow row : losersTable.getSelectionModel().getSelectedItems()) {
                if (row != null) selected.add(row.ticker());
            }

            if (selected.isEmpty()) {
                reloadChart(); // sem seleção → mostrar todos
            } else {
                reloadChartForTickers(new ArrayList<>(selected));
            }
            reloadReturnChart();
        });
    }

    private void reloadChart() {
        // Mostrar todos os ativos carregados (limite visual de 8)
        List<String> tickers = new ArrayList<>(lastStocks.keySet());
        if (tickers.size() > 8) tickers = tickers.subList(0, 8);
        reloadChartForTickers(tickers);
    }

    private void reloadChartForTickers(List<String> tickers) {
        barChart.getData().clear();

        if (tickers.isEmpty()) return;

        // Espaçamento dinâmico entre barras (independente da densidade de labels)
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

            // O JavaFX pode criar o node imediatamente (síncrono) ou na próxima passagem
            // de layout. Platform.runLater garante que verificamos APÓS o add, e o listener
            // cobre o caso em que o node ainda não foi criado nesse instante.
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

        // Atualiza densidade de labels do eixo X após renderização
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

        // Instala listener de largura para atualizar labels automaticamente no resize
        ChartAxisUtils.installSmartAxis(retXAxis, returnChart);

        returnLoadingLabel = new Label("Carregando histórico...");
        returnLoadingLabel.getStyleClass().add("section-subtitle");
        returnLoadingLabel.setVisible(false);
        returnLoadingLabel.setManaged(false);

        javafx.scene.layout.StackPane returnWrapper = ChartCrosshair.install(returnChart,
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
                    Platform.runLater(() -> renderReturnChart(all, period));
                });
    }

    private void renderReturnChart(Map<String, List<HistoryPoint>> allHistories,
                                    AssetHistoryClient.Period period) {
        returnLoadingLabel.setVisible(false);
        returnLoadingLabel.setManaged(false);
        returnChart.getData().clear();

        if (allHistories.isEmpty()) {
            ToastHost.showWarn("Sem histórico disponível. Configure um token BRAPI em Configurações.");
            return;
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(period.datePattern);

        for (var entry : allHistories.entrySet()) {
            List<HistoryPoint> points = entry.getValue();
            if (points.isEmpty()) continue;
            double base = points.get(0).close();
            if (base <= 0) continue;

            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(entry.getKey());

            for (HistoryPoint p : points) {
                double returnPct = ((p.close() / base) - 1) * 100.0;
                series.getData().add(new XYChart.Data<>(p.dateTime().format(fmt), returnPct));
            }

            if (!series.getData().isEmpty()) returnChart.getData().add(series);
        }

        // Atualiza densidade de labels do eixo X com base na largura atual
        Platform.runLater(() -> ChartAxisUtils.refreshLabels(retXAxis, returnChart.getWidth()));
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
        // Sem seleção → todos (limite 5 para não sobrecarregar)
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

        // Label do ticker dentro da barra (rotacionado -90°)
        if (node instanceof StackPane sp
                && sp.getChildren().stream().noneMatch(c -> c instanceof Label)) {
            Label lbl = new Label(bar.getXValue());
            lbl.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
            lbl.setRotate(-90);
            lbl.setMouseTransparent(true);
            StackPane.setAlignment(lbl, Pos.CENTER);
            sp.getChildren().add(lbl);
        }

        // Tooltip com informações ao passar o mouse
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

        // Flag para suprimir o debounce durante mudanças programáticas no editor
        final boolean[] programmaticChange = {false};

        // Debounce 300ms antes de disparar sugestões
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

        // Só dispara o debounce para digitação do usuário; ignora mudanças programáticas
        maTickerCombo.getEditor().textProperty().addListener((obs, old, val) -> {
            if (programmaticChange[0]) return;
            maDebounce.stop();
            maDebounce.playFromStart();
        });

        // Ao clicar num item do dropdown: congela o debounce, grava o ticker no editor
        // e fecha o popup — sem disparar nova busca
        maTickerCombo.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                maDebounce.stop();
                programmaticChange[0] = true;
                maTickerCombo.getEditor().setText(selected.ticker());
                programmaticChange[0] = false;
                maTickerCombo.hide();
            }
        });

        // Enter no editor → calcular
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

        // ── Loading label ──
        maLoadingLabel = new Label("Calculando média móvel...");
        maLoadingLabel.getStyleClass().add("section-subtitle");
        maLoadingLabel.setVisible(false);
        maLoadingLabel.setManaged(false);

        // ── Chart setup ──
        maChart.setAnimated(false);
        maChart.setLegendVisible(true);
        maChart.setCreateSymbols(false);
        maChart.setMinHeight(300);
        maYAxis.setLabel("Preço (R$)");
        maXAxis.setLabel("");
        maYAxis.setAutoRanging(false);

        ChartAxisUtils.installSmartAxis(maXAxis, maChart);

        javafx.scene.layout.StackPane chartWrapper = ChartCrosshair.install(maChart,
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

        // Janela da MA: adaptada ao total de pontos para sempre gerar uma curva útil
        int total = points.size();
        int maWindow;
        if      (total < 10)  { ToastHost.showWarn("Período muito curto para calcular média móvel (mínimo 10 pontos)."); return; }
        else if (total < 30)  maWindow = 5;
        else if (total < 60)  maWindow = 10;
        else if (total < 200) maWindow = 20;
        else                  maWindow = 50;

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yy");

        XYChart.Series<String, Number> priceSeries = new XYChart.Series<>();
        priceSeries.setName("Preço " + ticker);

        XYChart.Series<String, Number> maSeries = new XYChart.Series<>();
        maSeries.setName("MM" + maWindow);

        double minPrice = Double.MAX_VALUE;
        double maxPrice = -Double.MAX_VALUE;

        for (int i = 0; i < total; i++) {
            HistoryPoint p = points.get(i);
            String dateLabel = p.dateTime().format(fmt);
            double price = p.close();

            priceSeries.getData().add(new XYChart.Data<>(dateLabel, price));
            if (price < minPrice) minPrice = price;
            if (price > maxPrice) maxPrice = price;

            // MA: começa quando há dados suficientes
            if (i >= maWindow - 1) {
                double sum = 0;
                for (int j = i - maWindow + 1; j <= i; j++) sum += points.get(j).close();
                maSeries.getData().add(new XYChart.Data<>(dateLabel, sum / maWindow));
            }
        }

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

        Platform.runLater(() -> ChartAxisUtils.refreshLabels(maXAxis, maChart.getWidth()));
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

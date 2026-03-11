package com.daniel.presentation.view.pages;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.viewmodel.RankingViewModel;
import com.daniel.presentation.viewmodel.RankingViewModel.RankingRow;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Página de Ranking de Ativos da carteira por variação diária.
 * Exibe top altas e top baixas, com gráfico de linha comparativo (até 3 tickers).
 */
public final class RankingPage implements Page {

    private static final Logger LOG = Logger.getLogger(RankingPage.class.getName());
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM");

    private final DailyTrackingUseCase daily;

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    // ── Tables ──
    private final TableView<RankingRow> gainersTable = new TableView<>();
    private final TableView<RankingRow> losersTable  = new TableView<>();

    // ── Comparison chart ──
    private final CategoryAxis xAxis = new CategoryAxis();
    private final NumberAxis   yAxis = new NumberAxis();
    private final LineChart<String, Number> lineChart = new LineChart<>(xAxis, yAxis);

    // ── Period toggle ──
    private final ToggleGroup periodGroup = new ToggleGroup();

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

        HBox toolbar = buildPeriodToolbar();
        emptyState   = buildEmptyState();
        contentArea  = buildContent();

        loadingLabel = new Label("Carregando dados...");
        loadingLabel.getStyleClass().add("section-subtitle");
        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        root.getChildren().addAll(header, toolbar, loadingLabel, emptyState, contentArea);

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

    private HBox buildPeriodToolbar() {
        HBox bar = new HBox(6);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar");

        for (Period p : Period.values()) {
            ToggleButton tb = new ToggleButton(p.label);
            tb.setToggleGroup(periodGroup);
            tb.setUserData(p);
            tb.getStyleClass().add("period-btn");
            bar.getChildren().add(tb);
            if (p == Period.ONE_WEEK) tb.setSelected(true);
        }

        periodGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null && !lastStocks.isEmpty()) reloadChart();
        });

        return bar;
    }

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
        VBox gainersCard = buildTableCard("📈 Maiores Altas", gainersTable, true);
        VBox losersCard  = buildTableCard("📉 Maiores Baixas", losersTable, false);

        HBox tables = new HBox(16, gainersCard, losersCard);
        HBox.setHgrow(gainersCard, Priority.ALWAYS);
        HBox.setHgrow(losersCard,  Priority.ALWAYS);

        // ── Line chart card ──
        lineChart.setAnimated(true);
        lineChart.setCreateSymbols(false);
        lineChart.setMinHeight(300);
        lineChart.getStyleClass().add("line-chart");
        xAxis.setTickLabelRotation(-30);

        Label chartTitle = new Label("COMPARATIVO DE PREÇOS");
        chartTitle.getStyleClass().add("card-title");

        Label chartHint = new Label("Selecione até 3 linhas na tabela para comparar");
        chartHint.getStyleClass().add("section-subtitle");

        VBox chartCard = new VBox(8, chartTitle, chartHint, lineChart);
        chartCard.getStyleClass().add("chart-card");

        VBox content = new VBox(16, tables, chartCard);
        return content;
    }

    private VBox buildTableCard(String title, TableView<RankingRow> table, boolean gainers) {
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

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            onTableSelectionChanged();
        });

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

        CompletableFuture.supplyAsync(() -> {
            try {
                return BrapiClient.fetchMultipleStocks(joined);
            } catch (Exception e) {
                LOG.warning("[RankingPage] fetchMultipleStocks: " + e.getMessage());
                return new HashMap<String, StockData>();
            }
        }).thenAccept(stocks -> Platform.runLater(() -> {
            loading = false;
            loadingLabel.setVisible(false);
            loadingLabel.setManaged(false);

            if (stocks.isEmpty()) {
                ToastHost.showError("Não foi possível carregar cotações. Verifique o token Brapi.");
                setContentVisible(false);
                return;
            }

            lastStocks = stocks;
            populateTables(stocks);
            setContentVisible(true);
            reloadChart();
        }));
    }

    private void populateTables(Map<String, StockData> stocks) {
        List<RankingRow> gainers = RankingViewModel.topGainers(stocks);
        List<RankingRow> losers  = RankingViewModel.topLosers(stocks);

        gainersTable.setItems(FXCollections.observableArrayList(gainers));
        losersTable.setItems(FXCollections.observableArrayList(losers));
    }

    private void onTableSelectionChanged() {
        Set<String> selected = new LinkedHashSet<>();

        for (RankingRow row : gainersTable.getSelectionModel().getSelectedItems()) {
            if (row != null) selected.add(row.ticker());
        }
        for (RankingRow row : losersTable.getSelectionModel().getSelectedItems()) {
            if (row != null) selected.add(row.ticker());
        }

        if (!selected.isEmpty()) {
            List<String> tickers = new ArrayList<>(selected);
            if (tickers.size() > 3) tickers = tickers.subList(0, 3);
            reloadChartForTickers(tickers);
        }
    }

    private void reloadChart() {
        // Default: show top 3 gainers
        List<String> tickers = gainersTable.getItems().stream()
                .limit(3)
                .map(RankingRow::ticker)
                .toList();

        if (tickers.isEmpty()) {
            tickers = losersTable.getItems().stream()
                    .limit(3)
                    .map(RankingRow::ticker)
                    .toList();
        }

        reloadChartForTickers(tickers);
    }

    private void reloadChartForTickers(List<String> tickers) {
        if (tickers.isEmpty()) return;

        Toggle selected = periodGroup.getSelectedToggle();
        Period period = selected != null ? (Period) selected.getUserData() : Period.ONE_WEEK;

        lineChart.getData().clear();

        for (String ticker : tickers) {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return Map.entry(ticker, AssetHistoryClient.fetchHistory(ticker, period));
                } catch (Exception e) {
                    return Map.entry(ticker, List.<HistoryPoint>of());
                }
            }).thenAccept(entry -> Platform.runLater(() ->
                    addChartSeries(entry.getKey(), entry.getValue())));
        }
    }

    private void addChartSeries(String ticker, List<HistoryPoint> points) {
        if (points.isEmpty()) return;

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(ticker);

        for (HistoryPoint p : points) {
            series.getData().add(new XYChart.Data<>(DMY.format(p.date()), p.close()));
        }

        lineChart.getData().add(series);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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

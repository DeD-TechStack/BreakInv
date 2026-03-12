package com.daniel.presentation.view.pages;

import com.daniel.core.service.DailyTrackingUseCase;
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

    // ── Bar chart ──
    private final CategoryAxis barXAxis = new CategoryAxis();
    private final NumberAxis   barYAxis = new NumberAxis();
    private final BarChart<String, Number> barChart = new BarChart<>(barXAxis, barYAxis);

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
        barYAxis.setAutoRanging(false);
        barYAxis.setLabel("Variação (%)");
        barXAxis.setLabel("");

        Label chartTitle = new Label("VARIAÇÃO DO DIA (%)");
        chartTitle.getStyleClass().add("card-title");

        Label chartHint = new Label("Clique em ativos para filtrar (clique novamente para desmarcar)");
        chartHint.getStyleClass().add("section-subtitle");

        VBox chartCard = new VBox(8, chartTitle, chartHint, barChart);
        chartCard.getStyleClass().add("chart-card");

        VBox content = new VBox(16, tables, chartCard);
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

        // Toggle deselect: clicar em linha já selecionada → desmarcar
        table.setRowFactory(tv -> {
            TableRow<RankingRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && row.isSelected()) {
                    tv.getSelectionModel().clearSelection(
                            tv.getItems().indexOf(row.getItem()));
                    event.consume();
                }
            });
            return row;
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) ->
                onTableSelectionChanged());

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
                String hint = BrapiClient.hasToken()
                        ? "Erro ao carregar cotações. Verifique sua conexão."
                        : "Configure um token BRAPI em Configurações para carregar cotações.";
                ToastHost.showWarn(hint);
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
        gainersTable.setItems(FXCollections.observableArrayList(RankingViewModel.topGainers(stocks)));
        losersTable.setItems(FXCollections.observableArrayList(RankingViewModel.topLosers(stocks)));
    }

    private void onTableSelectionChanged() {
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

        List<Double> pcts = new ArrayList<>();

        for (String ticker : tickers) {
            StockData d = lastStocks.get(ticker);
            if (d == null || !d.isValid()) continue;

            double pct = d.regularMarketChangePercent();
            pcts.add(pct);

            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(ticker);
            XYChart.Data<String, Number> bar = new XYChart.Data<>(ticker, pct);
            series.getData().add(bar);
            barChart.getData().add(series);

            // Colorir e instalar tooltip após o nó ser criado
            final double finalPct = pct;
            final StockData finalD  = d;
            bar.nodeProperty().addListener((obs, o, node) -> {
                if (node == null) return;
                String color = finalPct >= 0 ? "#22c55e" : "#ef4444";
                node.setStyle("-fx-bar-fill: " + color + ";");
                String sign = finalPct >= 0 ? "+" : "";
                Tooltip tp = new Tooltip(
                        ticker + "\n"
                        + String.format("%s%.2f%%", sign, finalPct).replace('.', ',') + "\n"
                        + String.format("R$ %.2f", finalD.regularMarketPrice()).replace('.', ',')
                );
                tp.setShowDelay(javafx.util.Duration.millis(0));
                Tooltip.install(node, tp);
            });
        }

        // Ajustar eixo Y ao range de variações com padding
        if (!pcts.isEmpty()) {
            double min = pcts.stream().mapToDouble(Double::doubleValue).min().orElse(-5);
            double max = pcts.stream().mapToDouble(Double::doubleValue).max().orElse(5);
            double pad = Math.max(Math.abs(max - min) * 0.2, 0.5);
            barYAxis.setLowerBound(Math.min(min - pad, -0.1));
            barYAxis.setUpperBound(max + pad);
            barYAxis.setTickUnit(Math.max((max - min + 2 * pad) / 5.0, 0.1));
        }
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

package com.daniel.presentation.view.pages;

import com.daniel.core.util.MoneyFormat;
import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Icons;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Página de análise de ativos: busca cotação atual e histórico de preços.
 */
public final class AssetAnalysisPage implements Page {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM");

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    // ── Search bar ──
    private final TextField searchField = new TextField();
    private final Button searchBtn = new Button("Buscar", Icons.search());

    // ── KPI cards ──
    private final Label lblPrice    = kpiValue("—");
    private final Label lblChange   = kpiValue("—");
    private final Label lblChangePct= kpiValue("—");
    private final Label lblHigh     = kpiValue("—");
    private final Label lblLow      = kpiValue("—");
    private final Label lblOpen     = kpiValue("—");
    private final Label lblDY       = kpiValue("—");
    private final Label lblName     = new Label();

    // ── Chart ──
    private final CategoryAxis xAxis = new CategoryAxis();
    private final NumberAxis   yAxis = new NumberAxis();
    private final AreaChart<String, Number> areaChart = new AreaChart<>(xAxis, yAxis);

    // ── Period selector ──
    private final ToggleGroup periodGroup = new ToggleGroup();

    // ── Sections shown/hidden ──
    private VBox kpiSection;
    private VBox chartSection;
    private VBox emptyState;

    public AssetAnalysisPage() {
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Análise de Ativos",
                "Pesquise um ticker para ver cotação e histórico");

        HBox searchBar = buildSearchBar();
        emptyState     = buildEmptyState();
        kpiSection     = buildKpiSection();
        chartSection   = buildChartSection();

        setResultVisible(false);

        root.getChildren().addAll(header, searchBar, emptyState, kpiSection, chartSection);

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
        // Nothing to refresh automatically; user drives search
    }

    // ── Build helpers ──────────────────────────────────────────────────────

    private HBox buildSearchBar() {
        searchField.setPromptText("Ex: PETR4, VALE3, ITUB4...");
        searchField.setPrefWidth(260);
        searchField.getStyleClass().add("search-field");
        searchBtn.getStyleClass().addAll("btn-primary", "btn-sm");

        searchBtn.setOnAction(e -> doSearch());
        searchField.setOnAction(e -> doSearch());

        HBox bar = new HBox(8, searchField, searchBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar");
        return bar;
    }

    private VBox buildEmptyState() {
        VBox box = new VBox(8);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        Label icon  = new Label("🔍");
        icon.getStyleClass().add("empty-icon");
        Label title = new Label("Pesquise um ativo");
        title.getStyleClass().add("empty-title");
        Label hint  = new Label("Digite o ticker (ex: PETR4) e pressione Buscar");
        hint.getStyleClass().add("empty-hint");
        box.getChildren().addAll(icon, title, hint);
        return box;
    }

    private VBox buildKpiSection() {
        lblName.getStyleClass().add("section-subtitle");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);

        grid.add(kpiCard("Preço Atual",  lblPrice),     0, 0);
        grid.add(kpiCard("Variação (R$)", lblChange),   1, 0);
        grid.add(kpiCard("Variação (%)",  lblChangePct),2, 0);
        grid.add(kpiCard("Abertura",     lblOpen),      0, 1);
        grid.add(kpiCard("Máx. Dia",     lblHigh),      1, 1);
        grid.add(kpiCard("Mín. Dia",     lblLow),       2, 1);
        grid.add(kpiCard("Div. Yield",   lblDY),        0, 2);

        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(33.33);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }

        VBox section = new VBox(8, lblName, grid);
        section.getStyleClass().add("card");
        return section;
    }

    private VBox buildChartSection() {
        // ── Period toggle buttons ──
        HBox periodBar = new HBox(6);
        periodBar.setAlignment(Pos.CENTER_LEFT);

        for (Period p : Period.values()) {
            ToggleButton tb = new ToggleButton(p.label);
            tb.setToggleGroup(periodGroup);
            tb.setUserData(p);
            tb.getStyleClass().add("period-btn");
            periodBar.getChildren().add(tb);
            if (p == Period.ONE_MONTH) tb.setSelected(true);
        }

        periodGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null) reloadChart();
        });

        // ── Area chart ──
        areaChart.setAnimated(true);
        areaChart.setLegendVisible(false);
        areaChart.setCreateSymbols(false);
        areaChart.setMinHeight(320);
        areaChart.getStyleClass().add("area-chart");
        xAxis.setTickLabelRotation(-30);

        VBox section = new VBox(10, periodBar, areaChart);
        section.getStyleClass().add("chart-card");
        return section;
    }

    // ── Card factories ─────────────────────────────────────────────────────

    private static VBox kpiCard(String title, Label value) {
        Label lbl = new Label(title.toUpperCase());
        lbl.getStyleClass().add("kpi-label");
        value.getStyleClass().add("kpi-value");
        VBox card = new VBox(4, lbl, value);
        card.getStyleClass().add("kpi-card");
        card.setPadding(new Insets(10, 14, 10, 14));
        return card;
    }

    private static Label kpiValue(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("kpi-value");
        return l;
    }

    // ── Search logic ───────────────────────────────────────────────────────

    private void doSearch() {
        String ticker = searchField.getText().trim().toUpperCase();
        if (ticker.isBlank()) return;

        searchBtn.setDisable(true);
        searchBtn.setText("Buscando...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return BrapiClient.fetchStockData(ticker);
            } catch (Exception e) {
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0,
                        e.getMessage());
            }
        }).thenAccept(data -> Platform.runLater(() -> {
            searchBtn.setDisable(false);
            searchBtn.setText("Buscar");
            onStockDataLoaded(data);
        }));
    }

    private void onStockDataLoaded(StockData data) {
        if (!data.isValid()) {
            String msg = data.hasError() ? data.error() : "Ticker não encontrado";
            ToastHost.showError("Não foi possível carregar " + data.ticker() + ": " + msg);
            setResultVisible(false);
            return;
        }

        String name = data.longName() != null ? data.longName() : data.ticker();
        lblName.setText(name + " (" + data.ticker() + ")");

        lblPrice.setText(String.format("R$ %.2f", data.regularMarketPrice()).replace('.', ','));

        double chg = data.regularMarketChange();
        double chgPct = data.regularMarketChangePercent();
        lblChange.setText(String.format("%s%.2f", chg >= 0 ? "+" : "", chg).replace('.', ','));
        lblChangePct.setText(String.format("%s%.2f%%", chgPct >= 0 ? "+" : "", chgPct).replace('.', ','));

        String changeStyle = chgPct >= 0 ? "pos" : "neg";
        lblChange.getStyleClass().removeAll("pos", "neg");
        lblChangePct.getStyleClass().removeAll("pos", "neg");
        lblChange.getStyleClass().add(changeStyle);
        lblChangePct.getStyleClass().add(changeStyle);

        lblOpen.setText(String.format("R$ %.2f", data.regularMarketOpen()).replace('.', ','));
        lblHigh.setText(String.format("R$ %.2f", data.regularMarketDayHigh()).replace('.', ','));
        lblLow.setText(String.format("R$ %.2f", data.regularMarketDayLow()).replace('.', ','));
        lblDY.setText(data.dividendYield() > 0
                ? String.format("%.2f%%", data.dividendYield()).replace('.', ',') : "—");

        setResultVisible(true);
        reloadChart();
    }

    private void reloadChart() {
        String ticker = searchField.getText().trim().toUpperCase();
        if (ticker.isBlank()) return;

        Toggle selected = periodGroup.getSelectedToggle();
        Period period = selected != null ? (Period) selected.getUserData() : Period.ONE_MONTH;

        areaChart.getData().clear();

        CompletableFuture.supplyAsync(() -> {
            try {
                return AssetHistoryClient.fetchHistory(ticker, period);
            } catch (Exception e) {
                return List.<HistoryPoint>of();
            }
        }).thenAccept(points -> Platform.runLater(() -> renderChart(points)));
    }

    private void renderChart(List<HistoryPoint> points) {
        areaChart.getData().clear();
        if (points.isEmpty()) return;

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (HistoryPoint p : points) {
            series.getData().add(new XYChart.Data<>(DMY.format(p.date()), p.close()));
        }
        areaChart.getData().add(series);

        for (XYChart.Data<String, Number> d : series.getData()) {
            d.nodeProperty().addListener((obs, old, n) -> {
                if (n != null) {
                    Tooltip.install(n, new Tooltip(
                            String.format("R$ %.2f", ((Number) d.getYValue()).doubleValue())
                                    .replace('.', ',')));
                }
            });
        }
    }

    private void setResultVisible(boolean visible) {
        emptyState.setVisible(!visible);
        emptyState.setManaged(!visible);
        kpiSection.setVisible(visible);
        kpiSection.setManaged(visible);
        chartSection.setVisible(visible);
        chartSection.setManaged(visible);
    }
}

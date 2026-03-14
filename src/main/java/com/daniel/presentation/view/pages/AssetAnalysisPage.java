package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.infrastructure.api.BrapiClient.TickerSuggestion;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.ChartAxisUtils;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.Icons;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Página de análise de ativos: busca cotação atual e histórico de preços.
 *
 * <p>O eixo X usa {@link NumberAxis} com epoch seconds como valores, garantindo
 * escala temporal proporcional ao intervalo real entre os dados.</p>
 */
public final class AssetAnalysisPage implements Page {

    // Formatter do período atual — lido pelo eixo e pelo crosshair via lambda
    private DateTimeFormatter currentDateFmt = DateTimeFormatter.ofPattern("dd/MM");

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    // ── Search bar ──
    private final ComboBox<TickerSuggestion> tickerCombo = new ComboBox<>();
    private final Button searchBtn = new Button("Buscar", Icons.search());
    private String currentTicker = "";

    // ── KPI cards ──
    private final Label lblPrice      = kpiValue("—");
    private final Label lblChange     = kpiValue("—");
    private final Label lblChangePct  = kpiValue("—");
    private final Label lblHigh       = kpiValue("—");
    private final Label lblLow        = kpiValue("—");
    private final Label lblOpen       = kpiValue("—");
    private final Label lblDY         = kpiValue("—");
    private final Label lblWeekHigh   = kpiValue("—");
    private final Label lblWeekLow    = kpiValue("—");
    private final Label lblAvg200     = kpiValue("—");
    private final Label lblYearChange = kpiValue("—");
    private final Label lblVolume     = kpiValue("—");
    private final Label lblName       = new Label();

    // ── Chart — NumberAxis para escala temporal proporcional ──
    private final NumberAxis xAxis   = new NumberAxis();
    private final NumberAxis yAxis   = new NumberAxis();
    private final AreaChart<Number, Number> areaChart = new AreaChart<>(xAxis, yAxis);

    // ── Period selector ──
    private final ToggleGroup periodGroup = new ToggleGroup();

    // ── Estado do último render ──
    private List<HistoryPoint> lastRenderedPoints = List.of();
    /** Epoch seconds (ZoneOffset.UTC) dos pontos do último render, para refresh do eixo. */
    private List<Long> lastEpochSecs = List.of();
    private Timeline resizeDebounce;

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
        tickerCombo.setEditable(true);
        tickerCombo.setPromptText("Digite ticker ou nome (ex: PETR4 ou Petrobras)");
        tickerCombo.setPrefWidth(340);
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

        searchBtn.getStyleClass().addAll("btn-primary", "btn-sm");
        searchBtn.setOnAction(e -> doSearch());

        HBox bar = new HBox(8, tickerCombo, searchBtn);
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

        grid.add(kpiCard("Preço Atual",   lblPrice),     0, 0);
        grid.add(kpiCard("Variação (R$)", lblChange),    1, 0);
        grid.add(kpiCard("Variação (%)",  lblChangePct), 2, 0);
        grid.add(kpiCard("Abertura",      lblOpen),      0, 1);
        grid.add(kpiCard("Máx. Dia",      lblHigh),      1, 1);
        grid.add(kpiCard("Mín. Dia",      lblLow),       2, 1);
        grid.add(kpiCard("Máx. 52 Sem.", lblWeekHigh),   0, 2);
        grid.add(kpiCard("Mín. 52 Sem.", lblWeekLow),    1, 2);
        grid.add(kpiCard("Méd. 200 dias", lblAvg200),    2, 2);
        grid.add(kpiCard("Div. Yield",    lblDY),        0, 3);
        grid.add(kpiCard("Var. 52 Sem.",  lblYearChange),1, 3);
        grid.add(kpiCard("Volume",        lblVolume),    2, 3);

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

        // ── Area chart com eixo temporal ──
        areaChart.setAnimated(false);
        areaChart.setLegendVisible(false);
        areaChart.setCreateSymbols(true);
        areaChart.setMinHeight(320);
        areaChart.getStyleClass().add("area-chart");
        yAxis.setAutoRanging(false);

        VBox.setVgrow(areaChart, Priority.ALWAYS);

        // Instala eixo temporal: distribuição proporcional ao intervalo real entre datas
        ChartAxisUtils.installTemporalAxis(xAxis, areaChart,
                () -> currentDateFmt,
                () -> lastEpochSecs);

        // Debounce de resize: recalcula tick density quando largura muda > 50px
        resizeDebounce = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(150),
                ev -> {
                    if (!lastEpochSecs.isEmpty()) {
                        ChartAxisUtils.refreshTemporalAxis(xAxis, lastEpochSecs, areaChart.getWidth());
                    }
                }
        ));
        resizeDebounce.setCycleCount(1);
        areaChart.widthProperty().addListener((obs, oldW, newW) -> {
            if (Math.abs(newW.doubleValue() - oldW.doubleValue()) > 50) {
                resizeDebounce.stop();
                resizeDebounce.playFromStart();
            }
        });

        // Crosshair temporal: nearest-neighbor por epoch second
        StackPane chartWrapper = ChartCrosshair.installTemporal(areaChart,
                epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC)
                        .format(currentDateFmt),
                y -> "R$ " + String.format("%.2f", y).replace('.', ','));
        VBox.setVgrow(chartWrapper, Priority.ALWAYS);

        VBox section = new VBox(10, periodBar, chartWrapper);
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
        String ticker = tickerCombo.getEditor().getText().trim().toUpperCase();
        if (ticker.isBlank()) return;
        currentTicker = ticker;

        searchBtn.setDisable(true);
        searchBtn.setText("Buscando...");

        CompletableFuture.supplyAsync(() -> BrapiClient.fetchStockDataSafe(ticker))
                .thenAccept(result -> Platform.runLater(() -> {
                    searchBtn.setDisable(false);
                    searchBtn.setText("Buscar");
                    if (!result.isSuccess()) {
                        String msg = switch (result.getReason()) {
                            case NO_TOKEN      -> "Configure um token BRAPI em Configurações para buscar cotações.";
                            case HTTP_ERROR    -> "Serviço BRAPI indisponível (HTTP " + result.getDetail() + ").";
                            case NETWORK_ERROR -> "Sem conexão. Verifique sua internet.";
                            case NOT_FOUND     -> "Ticker " + ticker + " não encontrado na BRAPI.";
                            case PARSE_ERROR   -> "Erro ao processar dados. Tente novamente.";
                        };
                        ToastHost.showError(msg);
                        setResultVisible(false);
                        return;
                    }
                    onStockDataLoaded(result.getData());
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

        lblWeekHigh.setText(data.fiftyTwoWeekHigh() > 0
                ? String.format("R$ %.2f", data.fiftyTwoWeekHigh()).replace('.', ',') : "—");
        lblWeekLow.setText(data.fiftyTwoWeekLow() > 0
                ? String.format("R$ %.2f", data.fiftyTwoWeekLow()).replace('.', ',') : "—");
        lblAvg200.setText(data.twoHundredDayAverage() > 0
                ? String.format("R$ %.2f", data.twoHundredDayAverage()).replace('.', ',') : "—");
        long vol = data.regularMarketVolume();
        if (vol >= 1_000_000) {
            lblVolume.setText(String.format("%.1fM", vol / 1_000_000.0).replace('.', ','));
        } else if (vol >= 1_000) {
            lblVolume.setText(String.format("%.1fK", vol / 1_000.0).replace('.', ','));
        } else {
            lblVolume.setText(vol > 0 ? String.valueOf(vol) : "—");
        }

        double yearChg = data.fiftyTwoWeekChange();
        if (yearChg != 0) {
            String sign = yearChg >= 0 ? "+" : "";
            lblYearChange.setText(String.format("%s%.2f%%", sign, yearChg * 100).replace('.', ','));
            lblYearChange.getStyleClass().removeAll("pos", "neg");
            lblYearChange.getStyleClass().add(yearChg >= 0 ? "pos" : "neg");
        } else {
            lblYearChange.setText("—");
            lblYearChange.getStyleClass().removeAll("pos", "neg");
        }

        setResultVisible(true);
        reloadChart();
    }

    private void reloadChart() {
        if (currentTicker.isBlank()) return;

        Toggle selected = periodGroup.getSelectedToggle();
        Period period = selected != null ? (Period) selected.getUserData() : Period.ONE_MONTH;
        currentDateFmt = DateTimeFormatter.ofPattern(period.datePattern);

        areaChart.getData().clear();

        CompletableFuture.supplyAsync(() -> {
            try {
                return AssetHistoryClient.fetchHistory(currentTicker, period);
            } catch (Exception e) {
                return List.<HistoryPoint>of();
            }
        }).thenAccept(points -> Platform.runLater(() -> renderChart(points)));
    }

    private void renderChart(List<HistoryPoint> points) {
        areaChart.getData().clear();
        if (points.isEmpty()) return;

        List<HistoryPoint> valid = points.stream().filter(p -> p.close() > 0).toList();
        if (valid.isEmpty()) return;
        lastRenderedPoints = valid;

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

        // Eixo Y com padding de 5% acima e abaixo do range real
        double range   = maxPrice - minPrice;
        double padding = range > 0 ? range * 0.05 : maxPrice * 0.02;
        yAxis.setLowerBound(minPrice - padding);
        yAxis.setUpperBound(maxPrice + padding);
        yAxis.setTickUnit((range + 2 * padding) / 6.0);

        areaChart.getData().add(series);

        // Recalcula escala temporal do eixo X com base na largura atual
        Platform.runLater(() -> ChartAxisUtils.refreshTemporalAxis(xAxis, lastEpochSecs, areaChart.getWidth()));

        // Instalar tooltip nos nós dos pontos
        Platform.runLater(() -> {
            for (XYChart.Data<Number, Number> d : series.getData()) {
                if (d.getNode() != null) {
                    installHoverTooltip(d);
                } else {
                    d.nodeProperty().addListener((obs, old, n) -> {
                        if (n != null) installHoverTooltip(d);
                    });
                }
            }
        });
    }

    private void installHoverTooltip(XYChart.Data<Number, Number> d) {
        javafx.scene.Node node = d.getNode();
        if (node == null) return;

        node.setStyle("-fx-background-color: transparent; -fx-padding: 5;");

        String dateLabel = LocalDateTime.ofEpochSecond(d.getXValue().longValue(), 0, ZoneOffset.UTC)
                .format(currentDateFmt);
        String label = dateLabel + "   R$ "
                + String.format("%.2f", d.getYValue().doubleValue()).replace('.', ',');

        Tooltip tp = new Tooltip(label);
        tp.setShowDelay(javafx.util.Duration.ZERO);
        tp.setHideDelay(javafx.util.Duration.millis(50));
        Tooltip.install(node, tp);

        node.setOnMouseEntered(e -> node.setStyle(
                "-fx-background-color: rgba(59,130,246,0.8); -fx-padding: 5; -fx-background-radius: 50;"));
        node.setOnMouseExited(e -> node.setStyle(
                "-fx-background-color: transparent; -fx-padding: 5;"));
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

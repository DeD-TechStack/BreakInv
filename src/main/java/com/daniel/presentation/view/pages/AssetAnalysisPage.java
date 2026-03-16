package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.AssetHistoryClient;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryPoint;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryResult;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Página de análise de ativos: busca cotação atual e histórico de preços.
 *
 * <p>O eixo X usa {@link NumberAxis} com epoch seconds como valores, garantindo
 * escala temporal proporcional ao intervalo real entre os dados.</p>
 */
public final class AssetAnalysisPage implements Page {

    private static final Logger LOG = Logger.getLogger(AssetAnalysisPage.class.getName());

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

    // ── Chart empty/error state ──
    private Label chartEmptyLabel;

    /** Impede que o listener do ToggleGroup dispare reloadChart durante ajustes programáticos. */
    private boolean suppressPeriodReload = false;

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
            if (p == Period.ONE_DAY) continue; // intraday não suportado no plano free Brapi
            ToggleButton tb = new ToggleButton(p.label);
            tb.setToggleGroup(periodGroup);
            tb.setUserData(p);
            tb.getStyleClass().add("period-btn");
            periodBar.getChildren().add(tb);
            if (p == Period.ONE_MONTH) tb.setSelected(true);
        }

        periodGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null && !suppressPeriodReload) reloadChart();
        });

        // ── Area chart com eixo temporal ──
        areaChart.setAnimated(false);
        areaChart.setLegendVisible(false);
        areaChart.setCreateSymbols(false);
        areaChart.setMinHeight(320);
        areaChart.getStyleClass().add("area-chart");
        yAxis.setAutoRanging(false);

        VBox.setVgrow(areaChart, Priority.ALWAYS);

        // Instala eixo temporal: distribuição proporcional ao intervalo real entre datas
        ChartAxisUtils.installTemporalAxis(xAxis, areaChart,
                () -> currentDateFmt,
                () -> lastEpochSecs);

        // Oculta labels e marcas do eixo X — o crosshair é o mecanismo principal de leitura
        ChartAxisUtils.hideTemporalAxisLabels(xAxis);

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

        // Empty/error state sobreposto ao gráfico
        chartEmptyLabel = new Label("Sem dados para este período");
        chartEmptyLabel.getStyleClass().addAll("empty-hint", "chart-empty-label");
        chartEmptyLabel.setVisible(false);
        chartEmptyLabel.setManaged(false);

        VBox section = new VBox(10, periodBar, chartWrapper, chartEmptyLabel);
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
        resetPeriodVisibility(); // novo ticker → redescobre limites a partir do zero
        reloadChart();
        probeCapabilities();    // descobre limites reais em paralelo com o fetch inicial
    }

    private void reloadChart() {
        if (currentTicker.isBlank()) return;

        Toggle selected = periodGroup.getSelectedToggle();
        Period period = selected != null ? (Period) selected.getUserData() : Period.ONE_MONTH;
        currentDateFmt = DateTimeFormatter.ofPattern(period.datePattern);

        areaChart.getData().clear();
        setChartEmpty(null); // limpa estado anterior

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return AssetHistoryClient.fetchHistory(currentTicker, period);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .thenAccept(result -> Platform.runLater(() -> {
                    applyPermittedRanges(result.permittedRanges(), result.effectiveRange(), period);
                    renderChart(result.points());
                }))
                .exceptionally(ex -> {
                    Throwable root = ex;
                    while (root.getCause() != null) root = root.getCause();
                    String msg = root.getMessage();
                    LOG.warning("[Chart] " + currentTicker + " " + period.range
                            + "/" + period.interval + " → " + root.getClass().getSimpleName() + ": " + msg);
                    Platform.runLater(() -> setChartEmpty(msg != null ? msg : "Erro ao carregar dados"));
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
        setChartEmpty(null); // dados OK — esconde label de erro
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
     * @param requested      período que o usuário solicitou
     */
    private void applyPermittedRanges(Set<String> permitted, String effectiveRange,
                                       Period requested) {
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
                return AssetHistoryClient.fetchHistory(ticker, Period.MAX);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            if (!ticker.equals(currentTicker)) return; // ticker mudou enquanto probe rodava
            if (!result.permittedRanges().isEmpty()) {
                Toggle sel = periodGroup.getSelectedToggle();
                Period current = sel != null ? (Period) sel.getUserData() : Period.ONE_MONTH;
                applyPermittedRanges(result.permittedRanges(), result.effectiveRange(), current);
            }
            // permittedRanges vazio = MAX teve sucesso → plano sem restrição, mantém tudo visível
        })).exceptionally(ex -> null); // falha do probe é não-crítica
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

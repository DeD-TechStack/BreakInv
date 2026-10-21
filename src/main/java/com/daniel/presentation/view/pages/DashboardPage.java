package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.util.ChartAxisUtils;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.Motion;
import com.daniel.core.service.DiversificationCalculator;
import com.daniel.core.service.DiversificationCalculator.*;
import com.daniel.infrastructure.api.BcbClient;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.core.domain.ledger.PortfolioMetrics;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.HeaderActions;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.layout.LineFlow;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.ChartSeriesStyle;
import com.daniel.presentation.view.components.EmptyState;
import org.kordamp.ikonli.feather.Feather;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.UiSpacer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import com.daniel.presentation.view.util.UiExecutor;

public final class DashboardPage implements ResponsivePage {

    static final String STAT_CASH = "Caixa";
    static final String STAT_POSITIONS = "Posições";
    static final String STAT_REALIZED = "Ganho realizado";
    static final String STAT_UNREALIZED = "Ganho não realizado";

    private final DailyTrackingUseCase daily;
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    private final HeroValue hero = new HeroValue("Patrimônio");
    private final StatStrip strip = new StatStrip(STAT_CASH, STAT_POSITIONS, STAT_REALIZED, STAT_UNREALIZED);
    private final Label cdiComparisonLabel = new Label("—");
    private final Label cdiCaption = new Label("vs CDI atual (projeção)");
    private Panel chartPanel;
    private AdaptivePair chartPair;
    private AdaptivePair summaryPair;
    private AdaptivePair supportPair;
    private AdaptivePair secondPair;
    private AdaptivePair rankPair;
    private Breakpoint breakpoint = Breakpoint.WIDE;
    private LocalDate referenceDate = LocalDate.now();

    private final ProgressBar healthBar = new ProgressBar(0);
    private final Label healthScoreLabel = new Label("—");
    private final Label healthDescLabel = new Label("Carregando...");
    private final VBox recentActivityList = new VBox(6);

    private final PieChart pieChart = new PieChart();
    private final BarChart<String, Number> waterfallChart;
    private final CategoryAxis waterfallXAxis = new CategoryAxis();
    private final CategoryAxis compXAxis = new CategoryAxis();
    private final NumberAxis compYAxis = new NumberAxis();
    private final LineChart<String, Number> comparisonChart = new LineChart<>(compXAxis, compYAxis);
    private javafx.scene.layout.StackPane compWrapper;

    private final VBox investmentsByCategoryContainer = new VBox(16);
    private final VBox rankPanelAltas = new VBox(8);
    private final VBox rankPanelBaixas = new VBox(8);

    private double rateCdi = 0.135;
    private double rateSelic = 0.1175;
    private double rateIpca = 0.045;
    private double rateIbov = Double.NaN;
    private boolean ratesFetched = false;

    private String selectedBenchmark = "CDI";
    private final Label metricRendimentoLabel = new Label("—");
    private final Label metricRentabilidadeLabel = new Label("—");
    private final Label metricBenchmarkLabel = new Label("—");
    private final Label metricBenchmarkTitleLabel = new Label("Rent. CDI");

    // R2-08 (V9): an empty chart states why instead of drawing a bare 0–110 grid or a blank square.
    private static final String VALUE_EMPTY = "Nenhuma posição com valor";
    private static final String VALUE_EMPTY_HINT = "Cada ativo aparece aqui com o seu valor atual assim que houver posições.";
    private static final String PIE_EMPTY = "Sem posições para compor";
    private static final String PIE_EMPTY_HINT = "A composição por categoria aparece quando houver posições com valor.";
    private final VBox valueEmpty = EmptyState.of(Feather.BAR_CHART_2, VALUE_EMPTY, VALUE_EMPTY_HINT, true);
    private final VBox pieEmpty = EmptyState.of(Feather.PIE_CHART, PIE_EMPTY, PIE_EMPTY_HINT, true);
    // R3-03 (R2-08 while loading): until the first result no chart draws its default grid; each panel says so.
    private final Label evolutionLoading = new Label("Carregando a evolução da carteira…");
    private boolean firstLoad = true;
    private final Label noComparisonHint = new Label(
            "Cadastre seus primeiros investimentos para desbloquear o gráfico de performance, benchmarks e indicadores de rentabilidade.");
    private final Label sparseChartHint = new Label(
            "Histórico insuficiente. Selecione um período maior ou aguarde mais dias de registro para desbloquear o gráfico.");
    private final Label comparisonMethodHint = new Label(
            "Linha \"Carteira\": variação do valor dos investimentos em relação ao primeiro snapshot do período — inclui aportes e "
            + "resgates, NÃO é rentabilidade (TWR/XIRR). Benchmark: taxa anual ATUAL projetada sobre o período, não a série histórica.");

    private final AtomicLong refreshEpoch = new AtomicLong(0);

    private int selectedFilterMonths = 12;
    private LocalDate customFrom = null;
    private LocalDate customTo = null;
    private boolean useCustomRange = false;
    private final FlowPane filterBar = new LineFlow(0, 6);
    private final DatePicker fromPicker = new DatePicker();
    private final DatePicker toPicker = new DatePicker();
    private final FlowPane datePickerBox = new FlowPane(8, 8);

    private final Label tokenWarningBanner = new Label(
            "Token Brapi não configurado: as ações ficam sem cotação de mercado.");
    private final AppSettingsRepository settingsRepo = new AppSettingsRepository();

    /** C04: the token warning as one line: message, then its action. */
    private final HBox tokenWarningRow = new HBox(12);
    private Runnable onConfigureToken = () -> {};
    /** C04: period | comparison on one toolbar line when wide, stacked otherwise. */
    private AdaptivePair toolbarPair;
    private LineFlow benchBar;

    // BRAPI freshness chips
    private final Label freshnessChip = new Label();
    private final Label connectionChip = new Label();

    public DashboardPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;

        NumberAxis yAxis = new NumberAxis();
        waterfallXAxis.setLabel("Investimentos");
        yAxis.setLabel("Valor (R$)");
        waterfallChart = new BarChart<>(waterfallXAxis, yAxis);
        // R2-21: the panel title ("Valor por ativo") names this chart; an inner title competed with it.
        waterfallChart.setTitle(null);
        waterfallChart.setLegendVisible(false);
        // R2-11: the bars take their colour from this class, never from default-colorN, which JavaFX
        // keeps advancing each time a refresh recreates the series (orange first, green later).
        waterfallChart.getStyleClass().add("value-chart");
        ChartAxisUtils.installSmartAxis(waterfallXAxis, waterfallChart);

        comparisonChart.setTitle(null);
        // R2-01: legend symbols follow the same series colours as the lines (app.css .evolution-chart).
        comparisonChart.getStyleClass().add("evolution-chart");
        comparisonChart.setMinHeight(280);
        comparisonChart.setCreateSymbols(false);
        comparisonChart.setAnimated(false);

        root.getStyleClass().add("page-root");

        // ── Header: title + data-origin chips ─────────────────────────────
        PageHeader header = new PageHeader("Dashboard", "Resumo da carteira");
        freshnessChip.getStyleClass().addAll("brapi-chip", "brapi-chip-neutral");
        connectionChip.getStyleClass().addAll("brapi-chip", "brapi-chip-neutral");
        HeaderActions headerRow = new HeaderActions(header, connectionChip, freshnessChip);

        // C04: the missing token is said once, in one compact line that carries its own action; the
        // base of the value stays in the hero ("preço de compra como referência").
        tokenWarningBanner.setGraphic(Icons.alertTriangle());
        tokenWarningBanner.setGraphicTextGap(8);
        tokenWarningBanner.setWrapText(true);
        tokenWarningBanner.setMinHeight(Region.USE_PREF_SIZE);
        tokenWarningBanner.setMaxWidth(Double.MAX_VALUE);
        tokenWarningBanner.getStyleClass().add("warning-banner-text");
        HBox.setHgrow(tokenWarningBanner, Priority.ALWAYS);
        Button configureToken = new Button("Configurar token");
        configureToken.getStyleClass().add("warning-banner-action");
        configureToken.setMinWidth(Region.USE_PREF_SIZE);
        configureToken.setOnAction(e -> onConfigureToken.run());
        tokenWarningRow.getChildren().setAll(tokenWarningBanner, configureToken);
        tokenWarningRow.setAlignment(Pos.CENTER_LEFT);
        tokenWarningRow.setMaxWidth(Double.MAX_VALUE); // a banner spans the content, whatever the message length (E-V2)
        tokenWarningRow.getStyleClass().addAll("warning-banner", "token-warning-row");
        tokenWarningRow.setVisible(false);
        tokenWarningRow.setManaged(false);

        // ── Hero: patrimony, with the gain beside it and the CDI projection as a chip ──
        cdiCaption.getStyleClass().add("hero-chip-caption");
        cdiComparisonLabel.getStyleClass().addAll("hero-chip-value", "num");
        HBox cdiChip = new HBox(6, cdiCaption, cdiComparisonLabel);
        cdiChip.getStyleClass().add("hero-chip");
        cdiChip.setAlignment(Pos.CENTER_LEFT);
        hero.setTrailing(cdiChip);
        hero.setContext("Referência " + formatDate(referenceDate));
        summaryPair = new AdaptivePair(hero, strip, Breakpoint.MEDIUM, 0.56, 24);
        VBox summary = new VBox(summaryPair);
        summary.getStyleClass().add("portfolio-summary");

        // ── Main chart block, period controls inside it ────────────────────
        chartPanel = buildComparisonSection();

        // ── Support: composition (+ health) beside recent activity ─────────
        pieChart.setMinHeight(240);
        pieChart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pieChart.setLegendSide(Side.BOTTOM);
        // R2-12: labels drawn around the pie took the width of a half-row card and left a ~45 px pie;
        // the legend already gives category and share, the tooltip the value.
        pieChart.setLabelsVisible(false);
        pieEmpty.setVisible(false);
        pieEmpty.setManaged(false);
        valueEmpty.setVisible(false);
        valueEmpty.setManaged(false);
        VBox.setVgrow(pieChart, Priority.ALWAYS);
        healthBar.setMaxWidth(Double.MAX_VALUE);
        healthScoreLabel.getStyleClass().addAll("text-bold", "num");
        healthDescLabel.getStyleClass().add("text-secondary");
        healthDescLabel.setWrapText(true);
        HBox healthRow = new HBox(10, new Label("Saúde da carteira"), UiSpacer.hGrow(), healthScoreLabel);
        healthRow.setAlignment(Pos.CENTER_LEFT);
        Panel compositionPanel = new Panel("Composição", pieChart, pieEmpty, healthRow, healthBar, healthDescLabel)
                .subtitle("Por categoria, sobre o valor das posições");
        recentActivityList.setFillWidth(true);
        Label emptyActivity = new Label("Nenhum lançamento este mês");
        emptyActivity.getStyleClass().add("text-secondary");
        recentActivityList.getChildren().add(emptyActivity);
        Panel activityPanel = new Panel("Atividade recente", recentActivityList).subtitle("Lançamentos do mês atual");
        supportPair = new AdaptivePair(compositionPanel, activityPanel, Breakpoint.NARROW, 0.5, 16);

        // ── Ranking with its own block + value distribution ────────────────
        Label altasTitle = new Label("Maiores altas");
        altasTitle.getStyleClass().addAll("rank-title", "pos");
        rankPanelAltas.getChildren().add(altasTitle);
        Label baixasTitle = new Label("Maiores baixas");
        baixasTitle.getStyleClass().addAll("rank-title", "neg");
        rankPanelBaixas.getChildren().add(baixasTitle);
        rankPair = new AdaptivePair(rankPanelAltas, rankPanelBaixas, Breakpoint.NARROW, 0.5, 16);
        Panel rankBlock = new Panel("Variação do dia", rankPair)
                .subtitle("Cotação do dia (BRAPI) por ativo; não é a rentabilidade da posição");
        rankBlock.getStyleClass().add("rank-panel");
        waterfallChart.setMinHeight(240);
        VBox.setVgrow(waterfallChart, Priority.ALWAYS);
        Panel waterfallPanel = new Panel("Valor por ativo", waterfallChart, valueEmpty).subtitle("Posições ordenadas pelo valor atual");
        secondPair = new AdaptivePair(rankBlock, waterfallPanel, Breakpoint.MEDIUM, 0.45, 16);

        root.getChildren().addAll(headerRow, tokenWarningRow, summary, chartPanel,
                supportPair, secondPair, investmentsByCategoryContainer);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");
        onBreakpoint(Breakpoint.WIDE);
        showFirstLoad();
    }

    /** R3-03: before the first result (populated: ~5 s) no default 0–110 grid and no blank panel. */
    private void showFirstLoad() {
        EmptyState.setText(valueEmpty, "Carregando posições…", "O valor de cada ativo aparece assim que a carteira for lida.");
        EmptyState.setText(pieEmpty, "Carregando composição…", "A composição por categoria aparece assim que a carteira for lida.");
        showChart(waterfallChart, valueEmpty, false);
        showChart(pieChart, pieEmpty, false);
        noComparisonHint.setVisible(false);
        noComparisonHint.setManaged(false);
        sparseChartHint.setVisible(false);
        sparseChartHint.setManaged(false);
        compWrapper.setVisible(false);
        compWrapper.setManaged(false);
        evolutionLoading.setVisible(true);
        evolutionLoading.setManaged(true);
        // the composition's own empty state says it is loading: no second "Carregando..." under it
        healthDescLabel.setVisible(false);
        healthDescLabel.setManaged(false);
        showRankPlaceholder("Carregando cotações…");
    }

    /** The first result (or a later one after a failed first load) gives every panel back its own states. */
    private void endFirstLoad() {
        firstLoad = false;
        EmptyState.setText(valueEmpty, VALUE_EMPTY, VALUE_EMPTY_HINT);
        EmptyState.setText(pieEmpty, PIE_EMPTY, PIE_EMPTY_HINT);
        evolutionLoading.setVisible(false);
        evolutionLoading.setManaged(false);
        healthDescLabel.setVisible(true);
        healthDescLabel.setManaged(true);
    }

    /** A first load that fails says so instead of loading forever (later refreshes keep their data). */
    private void showFirstLoadFailure() {
        evolutionLoading.setText("Não foi possível carregar a carteira. Abra a página de novo para tentar outra vez.");
        EmptyState.setText(valueEmpty, "Não foi possível carregar", "Abra a página de novo para tentar outra vez.");
        EmptyState.setText(pieEmpty, "Não foi possível carregar", "Abra a página de novo para tentar outra vez.");
        showRankPlaceholder("Cotações indisponíveis");
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onBreakpoint(Breakpoint bp) {
        breakpoint = bp;
        strip.setColumns(bp == Breakpoint.NARROW ? 2 : 4);
        summaryPair.onBreakpoint(bp);
        supportPair.onBreakpoint(bp);
        secondPair.onBreakpoint(bp);
        rankPair.onBreakpoint(bp);
        chartPair.onBreakpoint(bp);
        toolbarPair.onBreakpoint(bp);
        benchBar.setAlignment(bp == Breakpoint.WIDE ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        // Period controls keep the full plot width instead of competing with the title.
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    @Override
    public void onShow() {
        applyTokenState();
        refreshData();
        if (!ratesFetched) {
            boolean autoUpdate = Boolean.parseBoolean(
                    settingsRepo.get("brapi_auto_update").orElse("true"));
            if (autoUpdate) {
                fetchRealRates();
            }
        }
    }

    /** C04: the shell's way to the token setting, offered by the warning line. */
    public void setOnConfigureToken(Runnable action) {
        onConfigureToken = action == null ? () -> {} : action;
    }

    /** Token present or not: the warning line and the data-origin chips follow it. */
    void applyTokenState() {
        boolean hasToken = BrapiClient.hasToken();
        tokenWarningRow.setVisible(!hasToken);
        tokenWarningRow.setManaged(!hasToken);
        updateBrapiChips();
    }

    private void updateBrapiChips() {
        boolean hasToken = BrapiClient.hasToken();
        long lastFetch = BrapiClient.getLastSuccessfulFetchMs();
        int delayMinutes = Integer.parseInt(
                settingsRepo.get(ConfiguracoesPage.SETTINGS_KEY_BRAPI_DELAY).orElse("30"));

        // ── Freshness chip ───────────────────────────────────────────────
        freshnessChip.getStyleClass().removeAll("brapi-chip-success", "brapi-chip-warn", "brapi-chip-neutral");
        if (lastFetch == 0) {
            freshnessChip.setText("Sem sincronização");
            freshnessChip.getStyleClass().add("brapi-chip-neutral");
        } else {
            long elapsedMs = System.currentTimeMillis() - lastFetch;
            long elapsedMin = elapsedMs / 60_000;
            if (elapsedMin < 1) {
                freshnessChip.setText("Atualizado agora");
                freshnessChip.getStyleClass().add("brapi-chip-success");
            } else if (elapsedMin <= delayMinutes) {
                freshnessChip.setText("Atualizado há " + elapsedMin + " min");
                freshnessChip.getStyleClass().add("brapi-chip-success");
            } else {
                freshnessChip.setText("Atualização atrasada (" + elapsedMin + " min)");
                freshnessChip.getStyleClass().add("brapi-chip-warn");
            }
        }

        // ── Connection chip ──────────────────────────────────────────────
        connectionChip.getStyleClass().removeAll("brapi-chip-success", "brapi-chip-warn", "brapi-chip-neutral");
        // C04: without a token the warning line already says so (and offers the setting): no second badge.
        connectionChip.setVisible(hasToken);
        connectionChip.setManaged(hasToken);
        if (!hasToken) {
            connectionChip.setText("BRAPI sem token");
            connectionChip.getStyleClass().add("brapi-chip-neutral");
        } else {
            String planLabel = switch (delayMinutes) {
                case  5 -> "~5 min";
                case 15 -> "~15 min";
                case 30 -> "~30 min";
                default -> "~" + delayMinutes + " min";
            };
            connectionChip.setText("BRAPI conectado · " + planLabel);
            connectionChip.getStyleClass().add("brapi-chip-success");
        }
    }

    private void fetchRealRates() {
        CompletableFuture.supplyAsync(() -> {
            double cdi = BcbClient.fetchCdi().orElse(-1.0);
            double selic = BcbClient.fetchSelic().orElse(-1.0);
            double ipca = BcbClient.fetchIpca().orElse(-1.0);

            double ibov = BrapiClient.fetchIbovespaReturn().orElse(Double.NaN);

            return new double[]{cdi, selic, ipca, ibov};
        }).thenAcceptAsync(rates -> Platform.runLater(() -> {
            if (rates[0] > 0) rateCdi = rates[0];
            if (rates[1] > 0) rateSelic = rates[1];
            if (rates[2] > 0) rateIpca = rates[2];
            if (!Double.isNaN(rates[3])) rateIbov = rates[3];
            ratesFetched = true;
            refreshData();
        }));
    }

    private void refreshData() {
        LocalDate today = LocalDate.now();
        referenceDate = today;
        hero.setContext("Referência " + formatDate(today) + " · "
                + (BrapiClient.hasToken() ? "cotações BRAPI" : "preço de compra como referência"));

        final int filterMonths = selectedFilterMonths;
        final boolean useCustom = useCustomRange;
        final LocalDate customFromCapture = customFrom;
        final LocalDate customToCapture = customTo;

        final long epoch = refreshEpoch.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            List<InvestmentType> investments = daily.listTypes();

            if (investments.isEmpty()) {
                long cashPatrimony = daily.getTotalPatrimony(today);
                List<StatementEntry> txs = daily.hasLedger() ? daily.statement(YearMonth.now()) : legacyRows(YearMonth.now());
                return new DashData(investments, null, cashPatrimony, 0, 0, null, null, null, txs, safeMetrics(today), safeCash());
            }

            LocalDate dataFim = today;
            LocalDate dataInicio;
            if (useCustom && customFromCapture != null && customToCapture != null) {
                dataInicio = customFromCapture;
                dataFim = customToCapture;
            } else {
                dataInicio = today.minusMonths(filterMonths);
                LocalDate maisAntiga = investments.stream()
                        .filter(inv -> inv.investmentDate() != null)
                        .map(InvestmentType::investmentDate)
                        .min(LocalDate::compareTo)
                        .orElse(dataInicio);
                if (maisAntiga.isAfter(dataInicio)) dataInicio = maisAntiga;
            }

            Map<Long, Long> currentValues = daily.getAllCurrentValues(today);
            long totalPatrimony = daily.getTotalPatrimony(today);
            long totalProfit = daily.getTotalProfit(today);
            long totalInvestmentValue = daily.getTotalInvestmentValue(today);
            java.util.TreeMap<LocalDate, Long> snaps =
                    daily.getInvestmentSnapshotSeries(dataInicio, dataFim);
            List<StatementEntry> txs = daily.hasLedger() ? daily.statement(YearMonth.now()) : legacyRows(YearMonth.now());

            return new DashData(investments, currentValues, totalPatrimony, totalProfit,
                    totalInvestmentValue, snaps, dataInicio, dataFim, txs, safeMetrics(today), safeCash());
        }, UiExecutor.get()).thenAcceptAsync(data -> {
            if (refreshEpoch.get() != epoch) return;
            Platform.runLater(() -> {
                if (refreshEpoch.get() != epoch) return;
                applyDashData(data, today);
            });
        }).exceptionally(err -> {
            // R3-03: only the first load had no answer for a failure (it would read "Carregando…" forever)
            Platform.runLater(() -> {
                if (refreshEpoch.get() == epoch && firstLoad) showFirstLoadFailure();
            });
            return null;
        });
    }

    /** Ledger metrics for the stat strip; null without the ledger or when it cannot be read. */
    private PortfolioMetrics safeMetrics(LocalDate today) {
        if (!daily.hasLedger()) return null;
        try {
            return daily.metrics(today);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Cash state for the strip qualifier (same source Carteira uses); null without the ledger or on failure. */
    private DailyTrackingUseCase.CashStatus safeCash() {
        if (!daily.hasLedger()) return null;
        try {
            return daily.cashStatus();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Same words Carteira uses for the same state: a legacy cash figure is "a reconhecer", a
     * pending review is "revisão pendente", and without a movement history the figure comes
     * from the manual daily record. No new figure is computed here — only the existing one is qualified.
     */
    private static String cashQualifier(DailyTrackingUseCase.CashStatus cs, boolean hasLedger) {
        if (!hasLedger || cs == null) return "sem histórico de movimentações";
        if (!cs.opened() && cs.legacySnapshotsPresent()) return "a reconhecer";
        if (cs.reconciliationPending()) return "revisão pendente";
        return null;
    }

    private void applyStrip(long totalPatrimony, long totalInvestmentValue, PortfolioMetrics m,
                            DailyTrackingUseCase.CashStatus cash) {
        // cash = patrimony − positions: the two numbers the page already shows, nothing new
        strip.set(STAT_CASH, daily.brl(Math.max(0, totalPatrimony - totalInvestmentValue)), null,
                cashQualifier(cash, daily.hasLedger()));
        strip.set(STAT_POSITIONS, daily.brl(totalInvestmentValue));
        if (m == null) {
            // Unknown is not zero: the gain cannot be split without the movement history.
            strip.set(STAT_REALIZED, "—", "muted", "sem histórico de movimentações");
            strip.set(STAT_UNREALIZED, "—", "muted", "sem histórico de movimentações");
            return;
        }
        strip.set(STAT_REALIZED, signed(m.realizedGainCents()), tone(m.realizedGainCents()));
        strip.set(STAT_UNREALIZED, signed(m.unrealizedGainCents()), tone(m.unrealizedGainCents()));
    }

    private String signed(long cents) {
        if (cents == 0) return daily.brl(0);
        return (cents > 0 ? "+ " : "- ") + daily.brlAbs(Math.abs(cents));
    }

    private static String tone(long cents) {
        return cents == 0 ? "muted" : (cents > 0 ? "pos" : "neg");
    }

    private void applyDashData(DashData data, LocalDate today) {
        if (firstLoad) endFirstLoad();
        List<InvestmentType> investments = data.investments();

        if (investments.isEmpty()) {
            long cashPatrimony = data.totalPatrimony();
            // Zero is a known value (the strip shows R$ 0,00 too); "—" is reserved for unknown.
            hero.setValue(daily.brl(cashPatrimony));
            hero.setSecondary(cashPatrimony > 0 ? "só caixa, sem posições" : "nenhum investimento cadastrado", "muted");
            applyStrip(cashPatrimony, 0, data.metrics(), data.cash());
            cdiComparisonLabel.setText("—");
            pieChart.getData().clear();
            waterfallChart.getData().clear();
            comparisonChart.getData().clear();
            showChart(pieChart, pieEmpty, false);
            showChart(waterfallChart, valueEmpty, false);
            showComparisonEmpty();
            showRankPlaceholder("Nenhum ativo com cotação");
            investmentsByCategoryContainer.getChildren().clear();
            healthBar.setProgress(0);
            healthScoreLabel.setText("—");
            healthDescLabel.setText("Sem investimentos cadastrados.");
            updateRecentActivity(data.recentTransactions());
            return;
        }

        Map<Long, Long> currentValues = data.currentValues();
        long totalPatrimony = data.totalPatrimony();
        long totalProfit = data.totalProfit();

        hero.setValue(daily.brl(totalPatrimony));
        hero.setSecondary(totalProfit == 0 ? "sem ganho registrado"
                : ((totalProfit >= 0 ? "+ " : "- ") + daily.brlAbs(Math.abs(totalProfit)) + " de ganho"),
                tone(totalProfit));

        // CDI comparison uses investment-only value: cash is not invested capital and should not
        // inflate the benchmark comparison as if it earned CDI returns.
        long totalInvestmentValue = data.totalInvestmentValue();
        applyStrip(totalPatrimony, totalInvestmentValue, data.metrics(), data.cash());
        updateCDIComparison(today, investments, totalInvestmentValue);
        updatePieChart(investments, currentValues);
        updateWaterfallChart(investments, currentValues);
        updateComparisonChart(investments, currentValues, today,
                data.snapshotFrom(), data.snapshotTo(), data.snapshotSeries());
        // Pass investment-only total as denominator: % Alocação represents share of the investment
        // portfolio, not share of total patrimony (which would dilute % when cash exists).
        updateInvestmentsByCategory(investments, currentValues, totalInvestmentValue);
        updateRankPanel(investments, currentValues);
        updateHealthScore(investments, currentValues, totalPatrimony, totalProfit);
        updateRecentActivity(data.recentTransactions());
    }

    private void updateCDIComparison(LocalDate today, List<InvestmentType> investments, long totalPatrimony) {
        LocalDate oldestDate = null;
        long totalInvested = 0L;

        for (InvestmentType inv : investments) {
            if (inv.investmentDate() != null) {
                if (oldestDate == null || inv.investmentDate().isBefore(oldestDate)) {
                    oldestDate = inv.investmentDate();
                }
            }
            if (inv.investedValue() != null) {
                totalInvested += inv.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            }
        }

        if (oldestDate == null || totalInvested == 0) {
            cdiComparisonLabel.setText("Sem histórico");
            cdiComparisonLabel.getStyleClass().removeAll("pos", "neg", "muted");
            cdiComparisonLabel.getStyleClass().add("muted");
            return;
        }

        long months = java.time.temporal.ChronoUnit.MONTHS.between(oldestDate, today);
        if (months < 1) months = 1;

        CDIComparison comparison = DiversificationCalculator.compareWithCDI(
                totalInvested,
                totalPatrimony,
                (int) months,
                rateCdi
        );

        String text;
        if (comparison.outperformsCDI()) {
            text = String.format("↑ %.2f%% acima", Math.abs(comparison.difference()));
            cdiComparisonLabel.getStyleClass().removeAll("pos", "neg", "muted");
            cdiComparisonLabel.getStyleClass().add("pos");
        } else {
            text = String.format("↓ %.2f%% abaixo", Math.abs(comparison.difference()));
            cdiComparisonLabel.getStyleClass().removeAll("pos", "neg", "muted");
            cdiComparisonLabel.getStyleClass().add("neg");
        }

        cdiComparisonLabel.setText(text);
    }

    private void updatePieChart(List<InvestmentType> investments, Map<Long, Long> currentValues) {
        DiversificationData data = DiversificationCalculator.calculateCurrent(
                investments,
                currentValues
        );

        pieChart.getData().clear();
        pieChart.setTitle(null);

        if (data.totalCents() == 0) {
            showChart(pieChart, pieEmpty, false);
            return;
        }
        showChart(pieChart, pieEmpty, true);

        List<CategoryAllocation> validAllocs = new ArrayList<>();
        for (CategoryAllocation alloc : data.allocations()) {
            if (alloc.valueCents() > 0) {
                String label = String.format("%s (%.1f%%)",
                        alloc.category().getDisplayName(),
                        alloc.percentage());
                pieChart.getData().add(new PieChart.Data(label, alloc.valueCents() / 100.0));
                validAllocs.add(alloc);
            }
        }

        // Instalar cor e tooltip após renderização
        Platform.runLater(() -> {
            List<PieChart.Data> slices = pieChart.getData();
            for (int i = 0; i < slices.size() && i < validAllocs.size(); i++) {
                PieChart.Data slice = slices.get(i);
                CategoryAllocation alloc = validAllocs.get(i);
                String color   = alloc.category().getColor();
                String tipText = alloc.category().getDisplayName()
                        + "\n" + daily.brl(alloc.valueCents())
                        + String.format("\n%.1f%%", alloc.percentage());
                applyPieStyle(slice, color, tipText);
            }
        });
    }

    /** R2-21: the legend dot wears its slice colour (it fell back to the theme's default-colorN). */
    private void styleLegendSymbol(PieChart.Data slice, String color) {
        for (javafx.scene.Node item : pieChart.lookupAll(".chart-legend-item")) {
            if (item instanceof Label l && slice.getName().equals(l.getText()) && l.getGraphic() != null) {
                l.getGraphic().setStyle("-fx-pie-color: " + color + ";");
            }
        }
    }

    /** One of a chart and its empty state is shown, never a bare grid. */
    private static void showChart(javafx.scene.Node chart, javafx.scene.Node empty, boolean hasData) {
        chart.setVisible(hasData);
        chart.setManaged(hasData);
        empty.setVisible(!hasData);
        empty.setManaged(!hasData);
    }

    /** No investment to compare: the reason, without the evolution grid or the sparse-history hint. */
    private void showComparisonEmpty() {
        noComparisonHint.setVisible(true);
        noComparisonHint.setManaged(true);
        sparseChartHint.setVisible(false);
        sparseChartHint.setManaged(false);
        compWrapper.setVisible(false);
        compWrapper.setManaged(false);
    }

    /** Both rank lists say why they are empty (\"Maiores baixas\" used to stay blank). */
    private void showRankPlaceholder(String text) {
        for (VBox list : List.of(rankPanelAltas, rankPanelBaixas)) {
            list.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("rank-title")));
            Label empty = new Label(text);
            empty.getStyleClass().add("muted");
            empty.setWrapText(true);
            list.getChildren().add(empty);
        }
    }

    private void applyPieStyle(PieChart.Data slice, String color, String tipText) {
        styleLegendSymbol(slice, color);
        javafx.scene.Node n = slice.getNode();
        if (n != null) {
            n.setStyle("-fx-pie-color: " + color + ";");
            Tooltip tp = new Tooltip(tipText);
            tp.setShowDelay(javafx.util.Duration.ZERO);
            Tooltip.install(n, tp);
        } else {
            slice.nodeProperty().addListener((obs, o, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + color + ";");
                    Tooltip tp = new Tooltip(tipText);
                    tp.setShowDelay(javafx.util.Duration.ZERO);
                    Tooltip.install(newNode, tp);
                }
            });
        }
    }

    private void updateWaterfallChart(List<InvestmentType> investments, Map<Long, Long> currentValues) {
        waterfallChart.getData().clear();

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Valores");

        // One bar per asset, as in the position list: lots of one ticker add up; an asset without
        // ticker is its name. The tooltip keeps the whole name.
        Map<String, Long> grouped = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (InvestmentType inv : investments) {
            long value = currentValues.getOrDefault((long) inv.id(), 0L);
            if (value > 0) {
                String key = assetKey(inv);
                grouped.merge(key, value, Long::sum);
                names.putIfAbsent(key, inv.name());
            }
        }

        List<InvestmentValue> invValues = grouped.entrySet().stream()
                .map(e -> new InvestmentValue(names.get(e.getKey()), e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.valueCents, a.valueCents))
                .toList();

        Set<String> used = new HashSet<>();
        for (InvestmentValue iv : invValues) {
            boolean ticker = !iv.key.equals(iv.name);
            series.getData().add(new XYChart.Data<>(axisLabel(iv.key, ticker, used), iv.valueCents / 100.0));
        }

        waterfallChart.getData().add(series);
        showChart(waterfallChart, valueEmpty, !series.getData().isEmpty());

        // Atualiza densidade de labels do eixo X após renderização
        Platform.runLater(() -> ChartAxisUtils.refreshLabels(waterfallXAxis, waterfallChart.getWidth()));

        // Tooltips após adição ao chart (nós criados no próximo ciclo de layout)
        Platform.runLater(() -> {
            for (int i = 0; i < series.getData().size() && i < invValues.size(); i++) {
                XYChart.Data<String, Number> bar = series.getData().get(i);
                InvestmentValue iv = invValues.get(i);
                String tip = iv.name + "\n" + daily.brl(iv.valueCents);
                installXYTooltip(bar, tip);
            }
        });
    }

    private Panel buildComparisonSection() {
        // Botões de benchmark
        ToggleGroup benchTg = new ToggleGroup();
        // C04: one segmented group (AtlantaFX pills) beside the period group, no rule below them.
        benchBar = new LineFlow(0, 6);
        benchBar.setAlignment(Pos.CENTER_LEFT);
        benchBar.getStyleClass().add("chart-toolbar");
        Label benchmarkCaption = new Label("Comparar com");
        benchmarkCaption.getStyleClass().add("text-secondary");
        FlowPane.setMargin(benchmarkCaption, new Insets(0, 8, 0, 0));
        benchBar.getChildren().add(benchmarkCaption);

        List<String> benchmarks = List.of("CDI", "SELIC", "IPCA", "IBOVESPA");
        for (String bench : benchmarks) {
            ToggleButton btn = new ToggleButton(bench);
            btn.setToggleGroup(benchTg);
            btn.getStyleClass().addAll("bench-toggle", pill(benchmarks.indexOf(bench), benchmarks.size()));
            if (bench.equals("CDI")) btn.setSelected(true);
            btn.setOnAction(e -> {
                selectedBenchmark = bench;
                metricBenchmarkTitleLabel.setText("Rent. " + bench);
                refreshData();
            });
            benchBar.getChildren().add(btn);
        }

        // Painel de métricas lateral
        metricRendimentoLabel.getStyleClass().addAll("metric-lg", "pos");
        metricRentabilidadeLabel.getStyleClass().addAll("metric-lg", "pos");
        metricBenchmarkLabel.getStyleClass().addAll("metric-lg", "state-info");
        metricBenchmarkTitleLabel.getStyleClass().add("metric-subtitle");

        VBox metricsPanel = new VBox(14);
        metricsPanel.setAlignment(Pos.TOP_RIGHT);
        metricsPanel.setMinWidth(150);
        metricsPanel.setPadding(new Insets(4, 0, 0, 8));
        metricsPanel.getChildren().addAll(
                buildMetricBox("Variação (R$, inclui aportes)", metricRendimentoLabel),
                buildMetricBox("Variação % do valor", metricRentabilidadeLabel),
                buildMetricBoxCustomTitle(metricBenchmarkTitleLabel, metricBenchmarkLabel)
        );

        // Configurar gráfico
        compXAxis.setLabel(null);
        // R2-02: the line is the variation of the positions' value, not a return (the note says so).
        compYAxis.setLabel("Variação (%)");
        comparisonChart.setAnimated(false);
        comparisonChart.setCreateSymbols(false);
        comparisonChart.setLegendVisible(true);
        comparisonChart.setMinHeight(300);
        // C04 (adapting to the monitor): the evolution takes a share of the window height, so a taller
        // monitor shows more of it and a short window keeps the readable minimum.
        comparisonChart.prefHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(300, Math.min(520, scrollPane.getHeight() * 0.42)), scrollPane.heightProperty()));

        // Instala listener de largura para atualizar labels automaticamente no resize
        ChartAxisUtils.installSmartAxis(compXAxis, comparisonChart);

        compWrapper = ChartCrosshair.install(comparisonChart,
                y -> String.format("%s%.2f%%", y >= 0 ? "+" : "", y).replace('.', ','));
        VBox chartRow = new VBox(0, compWrapper);
        VBox.setVgrow(compWrapper, Priority.ALWAYS);
        chartRow.setAlignment(Pos.TOP_LEFT);

        // metrics beside the chart when wide, under it when the content is narrow
        chartPair = new AdaptivePair(chartRow, metricsPanel, Breakpoint.MEDIUM, 0.78, 12);

        // Barra de filtro de período
        buildFilterBar();

        noComparisonHint.getStyleClass().add("empty-hint");
        noComparisonHint.setWrapText(true);
        noComparisonHint.setVisible(false);
        noComparisonHint.setManaged(false);

        sparseChartHint.getStyleClass().add("empty-hint");
        sparseChartHint.setWrapText(true);
        sparseChartHint.setVisible(false);
        sparseChartHint.setManaged(false);

        comparisonMethodHint.getStyleClass().add("text-secondary");
        comparisonMethodHint.setWrapText(true);

        evolutionLoading.getStyleClass().add("text-secondary");
        evolutionLoading.setWrapText(true);
        evolutionLoading.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        // C04: period | comparison on one toolbar line when wide (each group wraps whole when stacked)
        toolbarPair = new AdaptivePair(filterBar, benchBar, Breakpoint.MEDIUM, 0.47, 16);
        Panel panel = new Panel("Evolução da carteira", toolbarPair, datePickerBox, noComparisonHint, evolutionLoading, sparseChartHint, chartPair, comparisonMethodHint)
                .subtitle("Variação do valor das posições no período, comparada ao benchmark");
        panel.getStyleClass().add("main-chart-panel");
        return panel;
    }

    private VBox buildMetricBox(String label, Label valueLabel) {
        VBox b = new VBox(3);
        b.setAlignment(Pos.CENTER_RIGHT);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("metric-subtitle");
        b.getChildren().addAll(valueLabel, lbl);
        return b;
    }

    private VBox buildMetricBoxCustomTitle(Label titleLabel, Label valueLabel) {
        VBox b = new VBox(3);
        b.setAlignment(Pos.CENTER_RIGHT);
        b.getChildren().addAll(valueLabel, titleLabel);
        return b;
    }

    /** AtlantaFX pill position of segment {@code index} in a group of {@code size}. */
    static String pill(int index, int size) {
        if (index == 0) return atlantafx.base.theme.Styles.LEFT_PILL;
        return index == size - 1 ? atlantafx.base.theme.Styles.RIGHT_PILL : atlantafx.base.theme.Styles.CENTER_PILL;
    }

    private void buildFilterBar() {
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getStyleClass().add("chart-toolbar");

        Map<String, Integer> filters = new LinkedHashMap<>();
        filters.put("1M", 1);
        filters.put("3M", 3);
        filters.put("6M", 6);
        filters.put("1A", 12);
        filters.put("3A", 36);
        filters.put("5A", 60);
        filters.put("10A", 120);

        ToggleGroup group = new ToggleGroup();
        List<ToggleButton> buttons = new ArrayList<>();

        for (var entry : filters.entrySet()) {
            ToggleButton btn = new ToggleButton(entry.getKey());
            btn.setToggleGroup(group);
            btn.getStyleClass().add("filter-toggle");
            int months = entry.getValue();
            btn.setOnAction(e -> {
                selectedFilterMonths = months;
                useCustomRange = false;
                datePickerBox.setVisible(false);
                datePickerBox.setManaged(false);
                refreshData();
            });
            if (months == 12) btn.setSelected(true);
            buttons.add(btn);
        }

        ToggleButton customBtn = new ToggleButton("Intervalo");
        customBtn.setToggleGroup(group);
        customBtn.getStyleClass().add("filter-toggle");
        customBtn.setOnAction(e -> {
            useCustomRange = true;
            datePickerBox.setVisible(true);
            datePickerBox.setManaged(true);
        });
        buttons.add(customBtn);
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).getStyleClass().add(pill(i, buttons.size()));

        filterBar.getChildren().addAll(buttons);

        fromPicker.setPromptText("De");
        fromPicker.setPrefWidth(130);
        toPicker.setPromptText("Até");
        toPicker.setPrefWidth(130);
        toPicker.setValue(LocalDate.now());
        com.daniel.presentation.view.util.UiDates.applyBrFormat(fromPicker);
        com.daniel.presentation.view.util.UiDates.applyBrFormat(toPicker);

        Button applyBtn = new Button("Aplicar");
        applyBtn.getStyleClass().addAll("primary-btn", "filter-toggle");
        applyBtn.setOnAction(e -> {
            customFrom = fromPicker.getValue();
            customTo = toPicker.getValue();
            if (customFrom != null && customTo != null) {
                refreshData();
            }
        });

        datePickerBox.setAlignment(Pos.CENTER_LEFT);
        datePickerBox.setPadding(new Insets(4, 0, 0, 0));
        datePickerBox.getChildren().addAll(new Label("De:"), fromPicker, new Label("Até:"), toPicker, applyBtn);
        datePickerBox.setVisible(false);
        datePickerBox.setManaged(false);
    }

    private void updateComparisonChart(List<InvestmentType> investments,
                                       Map<Long, Long> currentValues,
                                       LocalDate today,
                                       LocalDate dataInicio,
                                       LocalDate dataFim,
                                       java.util.TreeMap<LocalDate, Long> snapshots) {
        comparisonChart.getData().clear();

        // ── Empty state ──────────────────────────────────────────────────────
        long totalInvestido = calcTotalInvestido(investments);
        if (investments.isEmpty() || totalInvestido == 0) {
            showComparisonEmpty();
            return;
        }
        noComparisonHint.setVisible(false);
        noComparisonHint.setManaged(false);

        // ── Benchmark rate ───────────────────────────────────────────────────
        double taxaAnualBench = switch (selectedBenchmark) {
            case "SELIC"    -> rateSelic;
            case "IPCA"     -> rateIpca;
            case "IBOVESPA" -> Double.isNaN(rateIbov) ? 0.0 : rateIbov;
            default         -> rateCdi;
        };
        double taxaMensalBench = Math.pow(1 + taxaAnualBench, 1.0 / 12) - 1;

        // ── Range-aware bucket strategy ───────────────────────────────────────
        long totalRangeMonths = java.time.temporal.ChronoUnit.MONTHS.between(dataInicio, dataFim);
        if (totalRangeMonths < 1) totalRangeMonths = 1;

        // Blank-final variables — assigned exactly once in the if-else below, captured by lambdas
        final DateTimeFormatter fmt;
        final java.util.function.Function<LocalDate, String> bucketFn;
        final long projStepDays; // >0 = day-based projection steps; 0 = monthly steps

        if (totalRangeMonths <= 1) {
            // 1M: daily granularity (~30 points)
            fmt = DateTimeFormatter.ofPattern("dd/MM");
            bucketFn = d -> d.format(fmt);
            projStepDays = 1;
        } else if (totalRangeMonths <= 3) {
            // 3M: weekly granularity (~13 points) — bucket anchored to range start
            fmt = DateTimeFormatter.ofPattern("dd/MM");
            final LocalDate weekAnchor = dataInicio;
            bucketFn = d -> {
                long weekNum = java.time.temporal.ChronoUnit.DAYS.between(weekAnchor, d) / 7;
                return weekAnchor.plusDays(weekNum * 7).format(fmt);
            };
            projStepDays = 7;
        } else if (totalRangeMonths <= 6) {
            // 6M: biweekly granularity (~13 points) — bucket anchored to range start
            fmt = DateTimeFormatter.ofPattern("dd/MM");
            final LocalDate biweekAnchor = dataInicio;
            bucketFn = d -> {
                long biweekNum = java.time.temporal.ChronoUnit.DAYS.between(biweekAnchor, d) / 14;
                return biweekAnchor.plusDays(biweekNum * 14).format(fmt);
            };
            projStepDays = 14;
        } else {
            // 1A+: monthly granularity
            fmt = DateTimeFormatter.ofPattern("MM/yy");
            bucketFn = d -> d.format(fmt);
            projStepDays = 0;
        }

        XYChart.Series<String, Number> carteiraSeries = new XYChart.Series<>();
        carteiraSeries.setName("Carteira");
        XYChart.Series<String, Number> benchSeries = new XYChart.Series<>();
        benchSeries.setName(selectedBenchmark);

        double rentCartFinal = 0;
        double rentBenchFinal = 0;

        // ── Try real snapshot data (investment-only: performance chart, not patrimony evolution) ──

        // Deduplicate by bucket key — keep last snapshot per bucket to avoid vertical-line artifact
        LinkedHashMap<String, Map.Entry<LocalDate, Long>> dedupMap = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, Long> e : snapshots.entrySet()) {
            dedupMap.put(bucketFn.apply(e.getKey()), e);
        }
        List<Map.Entry<LocalDate, Long>> dedupedEntries = new ArrayList<>(dedupMap.values());

        if (dedupedEntries.size() >= 2) {
            // Real data path — % change relative to first snapshot
            sparseChartHint.setVisible(false);
            sparseChartHint.setManaged(false);
            compWrapper.setVisible(true);
            compWrapper.setManaged(true);
            long firstValue = dedupedEntries.get(0).getValue();
            LocalDate startDate = dedupedEntries.get(0).getKey();
            DateTimeFormatter exactFmt = DateTimeFormatter.ofPattern("dd/MM/yy");

            for (Map.Entry<LocalDate, Long> entry : dedupedEntries) {
                String lbl = bucketFn.apply(entry.getKey());
                double rentCart = firstValue > 0
                        ? (entry.getValue() - firstValue) * 100.0 / firstValue : 0;
                double monthsFromStart = java.time.temporal.ChronoUnit.DAYS.between(
                        startDate, entry.getKey()) / 30.44;
                double rentBench = (Math.pow(1 + taxaMensalBench, monthsFromStart) - 1) * 100;
                // Store real snapshot date as extraValue for precise crosshair tooltip
                XYChart.Data<String, Number> cartPoint = new XYChart.Data<>(lbl, rentCart);
                cartPoint.setExtraValue(entry.getKey().format(exactFmt));
                carteiraSeries.getData().add(cartPoint);
                benchSeries.getData().add(new XYChart.Data<>(lbl, rentBench));
            }

            Map.Entry<LocalDate, Long> last = dedupedEntries.get(dedupedEntries.size() - 1);
            rentCartFinal = firstValue > 0
                    ? (last.getValue() - firstValue) * 100.0 / firstValue : 0;
            double monthsFinal = java.time.temporal.ChronoUnit.DAYS.between(
                    startDate, last.getKey()) / 30.44;
            rentBenchFinal = (Math.pow(1 + taxaMensalBench, monthsFinal) - 1) * 100;

        } else {
            // Sparse state — insufficient real history; hide chart, show hint, still populate metrics
            sparseChartHint.setVisible(true);
            sparseChartHint.setManaged(true);
            compWrapper.setVisible(false);
            compWrapper.setManaged(false);

            long patrimonioAtual = currentValues.values().stream()
                    .mapToLong(Long::longValue).sum();
            double rentTotalCarteira = totalInvestido > 0
                    ? (patrimonioAtual - totalInvestido) * 100.0 / totalInvestido : 0;
            long mesesTotaisCarteira = calcularMesesDesdeInvestimentoMaisAntigo(investments, today);
            if (mesesTotaisCarteira < 1) mesesTotaisCarteira = 1;
            double taxaMensalCarteira =
                    Math.pow(1 + rentTotalCarteira / 100.0, 1.0 / mesesTotaisCarteira) - 1;

            // Compute side-panel metrics without adding any chart data
            rentCartFinal  = (Math.pow(1 + taxaMensalCarteira, (double) totalRangeMonths) - 1) * 100;
            rentBenchFinal = (Math.pow(1 + taxaMensalBench,    (double) totalRangeMonths) - 1) * 100;
        }

        // Only render if both series have at least 2 distinct points
        if (carteiraSeries.getData().size() >= 2) {
            comparisonChart.getData().add(carteiraSeries);
            comparisonChart.getData().add(benchSeries);

            // Atualiza densidade de labels do eixo X com base na largura atual
            Platform.runLater(() -> ChartAxisUtils.refreshLabels(compXAxis, comparisonChart.getWidth()));

            // Estilizar linhas das séries — aplica cores após o layout (lookup exige nós renderizados)
            Platform.runLater(() -> {
                ChartSeriesStyle.accent(comparisonChart.lookup(".series0.chart-series-line"), 2.0);
                ChartSeriesStyle.muted(comparisonChart.lookup(".series1.chart-series-line"), 1.5);
            });
        }

        // ── Métricas laterais ────────────────────────────────────────────────
        long rendimentoPeriodo = Math.round(totalInvestido * rentCartFinal / 100.0);
        String corClass = rendimentoPeriodo >= 0 ? "pos" : "neg";

        metricRendimentoLabel.setText(daily.brl(rendimentoPeriodo));
        metricRendimentoLabel.getStyleClass().removeAll("pos", "neg");
        metricRendimentoLabel.getStyleClass().add(corClass);

        metricRentabilidadeLabel.setText(String.format("%.2f%%", rentCartFinal));
        metricRentabilidadeLabel.getStyleClass().removeAll("pos", "neg");
        metricRentabilidadeLabel.getStyleClass().add(corClass);

        boolean ibovUnavailable = "IBOVESPA".equals(selectedBenchmark) && Double.isNaN(rateIbov);
        metricBenchmarkLabel.setText(ibovUnavailable ? "—" : String.format("%.2f%%", rentBenchFinal));
    }

    private long calcTotalInvestido(List<InvestmentType> investments) {
        long total = 0L;
        for (InvestmentType inv : investments) {
            if (inv.investedValue() != null) {
                total += inv.investedValue()
                        .multiply(java.math.BigDecimal.valueOf(100)).longValue();
            }
        }
        return total;
    }

    private record RankEntry(String name, String ticker, double changePercent, long valueCents) {}

    private void updateRankPanel(List<InvestmentType> investments, Map<Long, Long> currentValues) {
        final long rankEpoch = refreshEpoch.get();
        // Keep the titles; R3-03: both lists say the quotes are loading ("Maiores baixas" stood blank meanwhile)
        showRankPlaceholder("Carregando cotações…");

        CompletableFuture.supplyAsync(() -> {
            List<RankEntry> entries = new ArrayList<>();

            // Only ticker-based assets have meaningful daily market variation
            List<InvestmentType> withTicker = investments.stream()
                    .filter(inv -> inv.ticker() != null && !inv.ticker().isBlank())
                    .toList();

            if (!withTicker.isEmpty()) {
                // Agrupar por ticker — garante uma entrada única por ticker no ranking
                Map<String, List<InvestmentType>> tickerGroups = new LinkedHashMap<>();
                for (InvestmentType inv : withTicker) {
                    tickerGroups.computeIfAbsent(
                            inv.ticker().trim().toUpperCase(), k -> new ArrayList<>()
                    ).add(inv);
                }

                // Buscar tickers únicos em batch (sem chamadas individuais por compra)
                String tickersStr = String.join(",", tickerGroups.keySet());
                Map<String, BrapiClient.StockData> rawMap = new HashMap<>();
                try {
                    rawMap = BrapiClient.fetchMultipleStocks(tickersStr);
                } catch (Exception ignored) {}

                // Normalizar chaves para uppercase — evita falha de lookup por casing da API
                final Map<String, BrapiClient.StockData> stockMap = new HashMap<>();
                for (var e : rawMap.entrySet()) {
                    stockMap.put(e.getKey().toUpperCase().trim(), e.getValue());
                }

                // Uma entrada por ticker (soma dos valores de todas as compras do grupo)
                for (var tickerEntry : tickerGroups.entrySet()) {
                    String ticker = tickerEntry.getKey();
                    List<InvestmentType> group = tickerEntry.getValue();

                    BrapiClient.StockData data = stockMap.get(ticker);
                    if (data == null || !data.isValid()) continue; // sem dados Brapi válidos

                    double change = data.regularMarketChangePercent();

                    long totalValue = group.stream()
                            .mapToLong(inv -> currentValues.getOrDefault((long) inv.id(), 0L))
                            .sum();

                    if (totalValue <= 0) continue; // ignorar tickers sem valor atual

                    String displayName = group.size() == 1 ? group.get(0).name() : ticker;
                    entries.add(new RankEntry(displayName, ticker, change, totalValue));
                }
            }

            // Retornar lista completa; separação em altas/baixas feita no Platform.runLater
            return entries;
        }).thenAcceptAsync(entries -> Platform.runLater(() -> {
            if (refreshEpoch.get() != rankEpoch) return;
            rankPanelAltas.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("rank-title")));
            rankPanelBaixas.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("rank-title")));

            if (entries.isEmpty()) {
                showRankPlaceholder("Sem cotação do dia para comparar");
                return;
            }

            List<RankEntry> altas = entries.stream()
                    .filter(e -> e.changePercent() >= 0)
                    .sorted((a, b) -> Double.compare(b.changePercent(), a.changePercent()))
                    .limit(4)
                    .toList();

            List<RankEntry> baixas = entries.stream()
                    .filter(e -> e.changePercent() < 0)
                    .sorted((a, b) -> Double.compare(a.changePercent(), b.changePercent()))
                    .limit(4)
                    .toList();

            if (altas.isEmpty()) {
                Label empty = new Label("Sem altas hoje");
                empty.getStyleClass().add("muted");
                rankPanelAltas.getChildren().add(empty);
            } else {
                for (RankEntry entry : altas) {
                    rankPanelAltas.getChildren().add(buildRankRow(entry));
                }
            }

            if (baixas.isEmpty()) {
                Label empty = new Label("Sem baixas hoje");
                empty.getStyleClass().add("muted");
                rankPanelBaixas.getChildren().add(empty);
            } else {
                for (RankEntry entry : baixas) {
                    rankPanelBaixas.getChildren().add(buildRankRow(entry));
                }
            }
        }));
    }

    private HBox buildRankRow(RankEntry entry) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("rank-row");

        VBox nameBox = new VBox(1);
        Label nameLabel = new Label(entry.ticker() != null ? entry.ticker() : entry.name());
        nameLabel.getStyleClass().addAll("text-bold", "text-sm");
        if (entry.ticker() != null) {
            Label subLabel = new Label(entry.name());
            subLabel.getStyleClass().add("text-dim-xs");
            nameBox.getChildren().addAll(nameLabel, subLabel);
        } else {
            nameBox.getChildren().add(nameLabel);
        }
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        VBox rightBox = new VBox(1);
        rightBox.setAlignment(Pos.CENTER_RIGHT);

        boolean positive = entry.changePercent() >= 0;
        Label changeLabel = new Label(String.format("%s%.2f%%", positive ? "+" : "", entry.changePercent()));
        changeLabel.getStyleClass().addAll("rent-value", positive ? "pos" : "neg");

        Label valueLabel = new Label(daily.brl(entry.valueCents()));
        valueLabel.getStyleClass().add("text-dim-xs");

        rightBox.getChildren().addAll(changeLabel, valueLabel);

        row.getChildren().addAll(nameBox, rightBox);
        return row;
    }

    private long calcularMesesDesdeInvestimentoMaisAntigo(List<InvestmentType> investments, LocalDate today) {
        return investments.stream()
                .filter(inv -> inv.investmentDate() != null)
                .mapToLong(inv -> java.time.temporal.ChronoUnit.MONTHS.between(inv.investmentDate(), today))
                .max()
                .orElse(1L);
    }

    private void updateInvestmentsByCategory(List<InvestmentType> investments,
                                             Map<Long, Long> currentValues,
                                             long totalPatrimony) {
        investmentsByCategoryContainer.getChildren().clear();

        Label title = new Label("Investimentos por categoria");
        title.getStyleClass().add("category-section-title");

        investmentsByCategoryContainer.getChildren().add(title);

        Map<CategoryEnum, List<InvestmentType>> byCategory = new LinkedHashMap<>();
        for (InvestmentType inv : investments) {
            if (inv.category() != null) {
                try {
                    CategoryEnum cat = CategoryEnum.valueOf(inv.category());
                    byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(inv);
                } catch (Exception ignored) {}
            }
        }

        List<Map.Entry<CategoryEnum, List<InvestmentType>>> sortedCategories = new ArrayList<>(byCategory.entrySet());
        sortedCategories.sort((a, b) -> {
            long totalA = a.getValue().stream()
                    .mapToLong(inv -> currentValues.getOrDefault((long)inv.id(), 0L)).sum();
            long totalB = b.getValue().stream()
                    .mapToLong(inv -> currentValues.getOrDefault((long)inv.id(), 0L)).sum();
            return Long.compare(totalB, totalA);
        });

        for (var entry : sortedCategories) {
            CategoryEnum category = entry.getKey();
            List<InvestmentType> categoryInvestments = entry.getValue();

            long categoryTotal = categoryInvestments.stream()
                    .mapToLong(inv -> currentValues.getOrDefault((long)inv.id(), 0L)).sum();

            if (categoryTotal == 0) continue;

            double categoryPercent = (categoryTotal * 100.0) / totalPatrimony;

            VBox categorySection = buildCategorySection(category, categoryInvestments,
                    currentValues, categoryPercent,
                    categoryTotal, totalPatrimony);
            investmentsByCategoryContainer.getChildren().add(categorySection);
        }
    }

    private VBox buildCategorySection(CategoryEnum category, List<InvestmentType> investments,
                                      Map<Long, Long> currentValues, double categoryPercent,
                                      long categoryTotal, long totalPatrimony) {
        VBox section = new VBox(12);
        section.getStyleClass().add("card");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Circle circle = new Circle(6);
        circle.setFill(Color.web(category.getColor()));

        Label categoryName = new Label(category.getDisplayName());
        categoryName.getStyleClass().add("category-name");

        Label categoryPercentLabel = new Label(String.format("%.1f%% • %s",
                categoryPercent, daily.brl(categoryTotal)));
        categoryPercentLabel.getStyleClass().add("category-percent");

        header.getChildren().addAll(circle, categoryName, UiSpacer.hGrow(), categoryPercentLabel);

        VBox investmentsList = new VBox(8);

        // Agrupar por ticker normalizado: trim + uppercase evita "AURE3" vs "aure3 " separados
        Map<String, List<InvestmentType>> grouped = new LinkedHashMap<>();
        List<InvestmentType> nonTickered = new ArrayList<>();

        for (InvestmentType inv : investments) {
            if (inv.ticker() != null && !inv.ticker().isBlank()) {
                String tickerKey = inv.ticker().trim().toUpperCase();
                grouped.computeIfAbsent(tickerKey, k -> new ArrayList<>()).add(inv);
            } else {
                nonTickered.add(inv);
            }
        }

        // Ordenar grupos por valor total
        List<Map.Entry<String, List<InvestmentType>>> sortedGroups = new ArrayList<>(grouped.entrySet());
        sortedGroups.sort((a, b) -> {
            long totalA = a.getValue().stream()
                    .mapToLong(inv -> currentValues.getOrDefault((long)inv.id(), 0L)).sum();
            long totalB = b.getValue().stream()
                    .mapToLong(inv -> currentValues.getOrDefault((long)inv.id(), 0L)).sum();
            return Long.compare(totalB, totalA);
        });

        // Adicionar grupos de tickers
        for (var entry : sortedGroups) {
            String ticker = entry.getKey();
            List<InvestmentType> tickerInvs = entry.getValue();

            long groupTotal = tickerInvs.stream()
                    .mapToLong(inv -> currentValues.getOrDefault((long)inv.id(), 0L)).sum();

            if (groupTotal == 0) continue;

            VBox invRow = buildGroupedStockRow(ticker, tickerInvs, groupTotal, totalPatrimony);
            investmentsList.getChildren().add(invRow);
        }

        // Adicionar investimentos não agrupados
        nonTickered.sort((a, b) -> {
            long valA = currentValues.getOrDefault((long)a.id(), 0L);
            long valB = currentValues.getOrDefault((long)b.id(), 0L);
            return Long.compare(valB, valA);
        });

        for (InvestmentType inv : nonTickered) {
            long currentValue = currentValues.getOrDefault((long)inv.id(), 0L);
            if (currentValue == 0) continue;

            VBox invRow = buildInvestmentRow(inv, currentValue, totalPatrimony);
            investmentsList.getChildren().add(invRow);
        }

        section.getChildren().addAll(header, new Separator(), investmentsList);
        // R2-22: the populated base's 75 positions became 75 cards and a 10 694 px page. A category
        // shows its first positions and offers the others on demand (every row is still built: the
        // same data and requests as before, only fewer on screen until asked).
        List<Node> rows = new ArrayList<>(investmentsList.getChildren());
        if (rows.size() > CATEGORY_ROWS) {
            Button more = new Button();
            more.getStyleClass().add("ghost-btn");
            boolean[] all = {false};
            Runnable apply = () -> {
                for (int i = CATEGORY_ROWS; i < rows.size(); i++) {
                    rows.get(i).setVisible(all[0]);
                    rows.get(i).setManaged(all[0]);
                }
                more.setText(all[0] ? "Mostrar só as " + CATEGORY_ROWS + " primeiras"
                        : "Mostrar todas as " + rows.size() + " posições");
            };
            more.setOnAction(e -> { all[0] = !all[0]; apply.run(); });
            apply.run();
            section.getChildren().add(more);
        }
        return section;
    }

    /** R2-22: positions a category shows before "Mostrar todas". */
    static final int CATEGORY_ROWS = 5;

    private VBox buildGroupedStockRow(String ticker, List<InvestmentType> investments,
                                      long totalValueCents, long totalPatrimony) {
        // ─── Calcular consolidado (null quantity tratado como 0) ───
        int qtdTotal = 0;
        double somaPrecoQtd = 0;
        long totalInvestido = 0;
        LocalDate dataInvestimento = null;

        for (InvestmentType inv : investments) {
            int qty = (inv.quantity() != null) ? inv.quantity() : 0;
            double preco = (inv.purchasePrice() != null) ? inv.purchasePrice().doubleValue() : 0.0;

            qtdTotal += qty;
            somaPrecoQtd += preco * qty;
            totalInvestido += (long)(preco * qty * 100);

            // Data mais recente do grupo — calculada no mesmo loop
            if (inv.investmentDate() != null) {
                if (dataInvestimento == null || inv.investmentDate().isAfter(dataInvestimento)) {
                    dataInvestimento = inv.investmentDate();
                }
            }
        }

        // Cópias finais para captura nas lambdas
        final double precoMedio = (qtdTotal > 0) ? somaPrecoQtd / qtdTotal : 0.0;
        final int qtdFinal = qtdTotal;
        final double precoMedioFinal = precoMedio;
        final String tickerFinal = ticker;
        final double alocacao = totalPatrimony > 0 ? (totalValueCents * 100.0) / totalPatrimony : 0;

        // ─── Construir UI ───
        // Labels atualizados assincronamente (Posição Atual e Rentabilidade)
        Label rentLabel = new Label("...");
        rentLabel.getStyleClass().add("rent-dimmed");
        Label posicaoLabel = new Label(daily.brl(totalValueCents));
        posicaoLabel.getStyleClass().add("info-box-value");

        // Primary row: name + key async metrics
        HBox primary = new HBox(12);
        primary.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(ticker);
        nameLabel.getStyleClass().add("inv-row-name");
        nameLabel.setMinWidth(Region.USE_PREF_SIZE); // the ticker names the row: never cut
        primary.getChildren().addAll(nameLabel, UiSpacer.hGrow(), metrics(
                createInfoBox("Posição Atual", posicaoLabel),
                createInfoBox("Rentabilidade", rentLabel)));

        // Secondary row: static metadata
        HBox secondary = new HBox(12);
        secondary.setAlignment(Pos.CENTER_LEFT);
        secondary.getStyleClass().add("inv-row-meta");
        Label metaLabel = new Label("Qtd: " + qtdFinal
                + "  ·  PM: R$ " + String.format("%.2f", precoMedioFinal));
        metaLabel.getStyleClass().add("text-dim");
        LineFlow secondaryMetrics = metrics(
                createInfoBox("Investido", daily.brl(totalInvestido)),
                createInfoBox("Alocação", String.format("%.1f%%", alocacao)));
        if (dataInvestimento != null) {
            secondaryMetrics.getChildren().add(createInfoBox("Data",
                    dataInvestimento.format(DateTimeFormatter.ofPattern("dd/MM/yy"))));
        }
        secondary.getChildren().addAll(wrapLead(metaLabel), UiSpacer.hGrow(), secondaryMetrics);

        VBox row = new VBox(8);
        row.getStyleClass().add("inv-row-card");
        row.getChildren().addAll(primary, secondary);

        // ─── Busca assíncrona do preço atual via Brapi ───
        if (BrapiClient.hasToken() && qtdFinal > 0) {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return BrapiClient.fetchStockData(tickerFinal);
                } catch (Exception e) {
                    return null;
                }
            }).thenAcceptAsync(data -> Platform.runLater(() -> {
                if (data != null && data.isValid()) {
                    double precoAtual = data.regularMarketPrice();
                    long posicaoAtualCents = (long)(precoAtual * qtdFinal * 100);
                    double rent = precoMedioFinal > 0
                            ? ((precoAtual - precoMedioFinal) / precoMedioFinal) * 100
                            : 0;

                    posicaoLabel.setText(daily.brl(posicaoAtualCents));

                    rentLabel.setText(String.format("%+.2f%%", rent));
                    applyRentStyle(rentLabel, rent);
                } else {
                    posicaoLabel.setText(daily.brl(totalValueCents));
                    rentLabel.setText("—");
                    applyRentDimmed(rentLabel);
                }
            }));
        } else {
            rentLabel.setText("—");
            applyRentDimmed(rentLabel);
        }

        return row;
    }

    private VBox buildInvestmentRow(InvestmentType inv, long currentValueCents, long totalPatrimony) {
        double alocacao = totalPatrimony > 0 ? (currentValueCents * 100.0) / totalPatrimony : 0;
        boolean isStock = inv.ticker() != null && !inv.ticker().isBlank();

        // ── Primary row ──
        HBox primary = new HBox(12);
        primary.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(inv.name());
        nameLabel.getStyleClass().add("inv-row-name");
        primary.getChildren().add(wrapLead(nameLabel));

        if (isStock) {
            Label tickerBadge = new Label(inv.ticker());
            tickerBadge.getStyleClass().add("ticker-badge");
            tickerBadge.setMinWidth(Region.USE_PREF_SIZE);
            primary.getChildren().add(tickerBadge);
        }

        LineFlow primaryMetrics = metrics(createInfoBox("Posição Atual", daily.brl(currentValueCents)));
        primary.getChildren().addAll(UiSpacer.hGrow(), primaryMetrics);

        if (isStock && inv.quantity() != null && inv.purchasePrice() != null) {
            double precoMedio = inv.purchasePrice().doubleValue();
            double ultimoPreco = (currentValueCents / 100.0) / inv.quantity();
            Label rentLabel;
            if (!BrapiClient.hasToken()) {
                rentLabel = new Label("—");
                applyRentDimmed(rentLabel);
            } else {
                double rent = ((ultimoPreco - precoMedio) / precoMedio) * 100;
                rentLabel = new Label(String.format("%+.2f%%", rent));
                applyRentStyle(rentLabel, rent);
            }
            primaryMetrics.getChildren().add(createInfoBox("Rentabilidade", rentLabel));
        } else if (!isStock && inv.investedValue() != null) {
            long investido = inv.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            double rent = investido > 0 ? ((currentValueCents - investido) * 100.0) / investido : 0;
            Label rentLabel = new Label(String.format("%+.2f%%", rent));
            applyRentStyle(rentLabel, rent);
            primaryMetrics.getChildren().add(createInfoBox("Rentabilidade", rentLabel));
        }

        // ── Secondary row ──
        HBox secondary = new HBox(12);
        secondary.setAlignment(Pos.CENTER_LEFT);
        secondary.getStyleClass().add("inv-row-meta");

        if (inv.typeOfInvestment() != null) {
            Label typeLabel = new Label(getTypeDisplayName(inv.typeOfInvestment()));
            typeLabel.getStyleClass().add("text-dim");
            secondary.getChildren().add(wrapLead(typeLabel));
        }

        LineFlow secondaryMetrics = metrics(createInfoBox("Alocação", String.format("%.1f%%", alocacao)));
        secondary.getChildren().addAll(UiSpacer.hGrow(), secondaryMetrics);

        if (isStock && inv.quantity() != null && inv.purchasePrice() != null) {
            long valorInvestido = (long)(inv.purchasePrice().doubleValue() * inv.quantity() * 100);
            secondaryMetrics.getChildren().add(createInfoBox("Investido", daily.brl(valorInvestido)));
            secondaryMetrics.getChildren().add(createInfoBox("PM", String.format("R$ %.2f",
                    inv.purchasePrice().doubleValue())));
        } else if (!isStock && inv.investedValue() != null) {
            long investido = inv.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            secondaryMetrics.getChildren().add(createInfoBox("Investido", daily.brl(investido)));
        }

        if (inv.profitability() != null) {
            secondaryMetrics.getChildren().add(createInfoBox("Taxa",
                    String.format("%.2f%% a.a.", inv.profitability())));
        }

        if (inv.investmentDate() != null) {
            secondaryMetrics.getChildren().add(createInfoBox("Data",
                    inv.investmentDate().format(DateTimeFormatter.ofPattern("dd/MM/yy"))));
        }

        VBox row = new VBox(8);
        row.getStyleClass().add("inv-row-card");
        row.getChildren().addAll(primary, secondary);
        return row;
    }

    private void applyRentStyle(Label label, double value) {
        label.getStyleClass().removeAll("rent-value", "rent-dimmed", "pos", "neg");
        label.getStyleClass().addAll("rent-value", value >= 0 ? "pos" : "neg");
    }

    private void applyRentDimmed(Label label) {
        label.getStyleClass().removeAll("rent-value", "rent-dimmed", "pos", "neg");
        label.getStyleClass().add("rent-dimmed");
    }

    private VBox createInfoBox(String label, String value) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.getStyleClass().add("info-box");

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("info-box-value");
        valueLabel.setMinWidth(Region.USE_PREF_SIZE);

        Label labelLabel = new Label(label);
        labelLabel.getStyleClass().add("info-box-label");
        labelLabel.setMinWidth(Region.USE_PREF_SIZE);

        box.getChildren().addAll(valueLabel, labelLabel);
        return box;
    }

    private VBox createInfoBox(String label, Label customLabel) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.getStyleClass().add("info-box");

        customLabel.getStyleClass().add("text-sm");
        customLabel.setMinWidth(Region.USE_PREF_SIZE); // the async value never shrinks below its text

        Label labelLabel = new Label(label);
        labelLabel.getStyleClass().add("info-box-label");
        labelLabel.setMinWidth(Region.USE_PREF_SIZE);

        box.getChildren().addAll(customLabel, labelLabel);
        return box;
    }

    private String getTypeDisplayName(String type) {
        return switch (type) {
            case "PREFIXADO" -> "Prefixado";
            case "POS_FIXADO" -> "Pós-fixado";
            case "HIBRIDO" -> "Híbrido";
            case "ACAO" -> "Ação";
            default -> type;
        };
    }

    private VBox buildHealthCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        Label title = new Label("SAÚDE DA CARTEIRA");
        title.getStyleClass().add("card-title");

        healthBar.setMaxWidth(Double.MAX_VALUE);
        healthScoreLabel.getStyleClass().addAll("text-lg", "text-bold");
        healthDescLabel.getStyleClass().add("text-helper");
        healthDescLabel.setWrapText(true);

        card.getChildren().addAll(title, healthBar, healthScoreLabel, healthDescLabel);
        return card;
    }

    private VBox buildRecentActivityCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        Label title = new Label("ATIVIDADE RECENTE");
        title.getStyleClass().add("card-title");

        recentActivityList.setFillWidth(true);
        Label empty = new Label("Nenhum lançamento este mês");
        empty.getStyleClass().add("text-helper");
        recentActivityList.getChildren().add(empty);

        card.getChildren().addAll(title, recentActivityList);
        return card;
    }

    private void updateHealthScore(List<InvestmentType> investments, Map<Long, Long> currentValues,
                                   long totalPatrimony, long totalProfit) {
        int score = 0;
        long categories = investments.stream()
                .filter(inv -> inv.category() != null)
                .map(InvestmentType::category)
                .distinct().count();

        if (categories >= 4) score += 30;
        else if (categories >= 3) score += 20;
        else if (categories >= 2) score += 10;

        if (totalProfit > 0) score += 30;
        else if (totalProfit == 0) score += 10;

        if (investments.size() >= 5) score += 20;
        else if (investments.size() >= 3) score += 10;

        long withTicker = investments.stream().filter(i -> i.ticker() != null && !i.ticker().isBlank()).count();
        long withFI = investments.stream().filter(i -> i.ticker() == null || i.ticker().isBlank()).count();
        if (withTicker > 0 && withFI > 0) score += 20;
        else if (withTicker > 0 || withFI > 0) score += 10;

        score = Math.min(score, 100);
        double progress = score / 100.0;
        healthBar.setProgress(progress);

        String barClass = score >= 70 ? "bar-success" : (score >= 40 ? "bar-warn" : "bar-danger");
        healthBar.getStyleClass().removeAll("bar-success", "bar-warn", "bar-danger");
        healthBar.getStyleClass().add(barClass);

        healthScoreLabel.setText(score + " / 100");
        healthScoreLabel.getStyleClass().removeAll("pos", "neg", "state-warning");
        healthScoreLabel.getStyleClass().add(score >= 70 ? "pos" : (score >= 40 ? "state-warning" : "neg"));

        String desc;
        if (score >= 80) desc = "Carteira bem diversificada e saudável.";
        else if (score >= 60) desc = "Boa diversificação — considere adicionar mais categorias.";
        else if (score >= 40) desc = "Carteira em desenvolvimento — diversifique mais.";
        else desc = "Carteira pouco diversificada — adicione mais ativos.";
        healthDescLabel.setText(desc);
    }

    /** Without the ledger (stub wiring) fall back to the legacy transactions table. */
    private List<StatementEntry> legacyRows(YearMonth month) {
        List<StatementEntry> out = new ArrayList<>();
        for (var tx : daily.listTransactions(month)) {
            out.add(new StatementEntry(tx.date(), null, tx.type(), null, tx.id(), (long) tx.investmentTypeId(), tx.name(),
                    null, tx.quantity() == null ? null : tx.quantity().longValue(), null, tx.totalCents(), null, null,
                    tx.note(), null, null, false, true));
        }
        return out;
    }

    private void updateRecentActivity(List<StatementEntry> rows) {
        recentActivityList.getChildren().clear();
        List<StatementEntry> txs = new ArrayList<>(rows);
        txs.removeIf(StatementEntry::marker); // openings/reconciliations are not activity
        Collections.reverse(txs);             // newest first
        if (txs.isEmpty()) {
            Label empty = new Label("Nenhum lançamento este mês");
            empty.getStyleClass().add("text-secondary");
            recentActivityList.getChildren().add(empty);
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        txs.stream().limit(4).forEach(tx -> {
            boolean outflow = isOutflow(tx);
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("rank-row");
            Label typeIcon = new Label(outflow ? "▼" : "▲");
            typeIcon.getStyleClass().add(outflow ? "neg" : "pos");
            String kind = tx.legacy() ? ("BUY".equals(tx.legacyType()) ? "Compra (legado)" : "Venda (legado)")
                    : DailyTrackingUseCase.kindLabel(tx.kind());
            String who = tx.investmentName() != null ? tx.investmentName() : "Caixa";
            Label nameLabel = new Label(kind + " · " + who + (tx.reversed() ? " (estornado)" : ""));
            nameLabel.getStyleClass().add("text-sm");
            Label valLabel = new Label((outflow ? "- " : "+ ") + daily.brl(Math.abs(tx.amountCents())));
            valLabel.getStyleClass().addAll("text-sm", outflow ? "neg" : "pos");
            Label dateLabel = new Label(tx.date().format(fmt));
            dateLabel.getStyleClass().add("text-dim-xs");
            // V5 (r1 at 740 px of page: "- R$ 200,...", "15/..."): sign, amount and date keep their
            // width; the description is what yields, by wrapping.
            for (Label fixed : List.of(typeIcon, valLabel, dateLabel)) fixed.setMinWidth(Region.USE_PREF_SIZE);
            wrapLead(nameLabel);
            row.getChildren().addAll(typeIcon, nameLabel, UiSpacer.hGrow(), valLabel, dateLabel);
            recentActivityList.getChildren().add(row);
        });
    }

    /** Cash-perspective sign: money leaving cash (buy, withdrawal, negative adjustment) is an outflow. */
    private static boolean isOutflow(StatementEntry e) {
        if (e.legacy()) return "BUY".equals(e.legacyType());
        return switch (e.kind()) {
            case BUY, WITHDRAWAL -> true;
            case ADJUSTMENT -> e.amountCents() < 0;
            case REVERSAL -> false;
            default -> false;
        };
    }

    private String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** The asset a bar or a position stands for: its normalized ticker, or its name when it has none. */
    private static String assetKey(InvestmentType inv) {
        return inv.ticker() != null && !inv.ticker().isBlank() ? inv.ticker().trim().toUpperCase() : inv.name();
    }

    static final int AXIS_NAME_MAX = 24;

    /**
     * V9/V5 (r1 populated): a category per asset. A CategoryAxis merges equal labels, so cutting every
     * name to 15 characters put 75 assets under a single "Ativo sintét..." bar. A ticker names its
     * bar; a name without ticker stays whole up to {@value #AXIS_NAME_MAX} characters and longer ones
     * are shortened; a label that would repeat another one keeps the whole name, then a counter.
     */
    static String axisLabel(String key, boolean ticker, Set<String> used) {
        String label = ticker || key.length() <= AXIS_NAME_MAX ? key : key.substring(0, AXIS_NAME_MAX - 1).strip() + "\u2026";
        if (used.add(label)) return label;
        if (used.add(key)) return key;
        int n = 2;
        while (!used.add(key + " (" + n + ")")) n++;
        return key + " (" + n + ")";
    }

    /**
     * V5 (r1 populated at 600×480: "R$ 20.000.000,..." in the position list): a row's metrics move to
     * another line whole instead of being squeezed below their text.
     */
    private static LineFlow metrics(Node... boxes) {
        LineFlow flow = new LineFlow(12, 6, boxes);
        flow.setAlignment(Pos.CENTER_RIGHT);
        return flow;
    }

    /** The descriptive text of a row yields first: it wraps rather than pushing a value out. */
    private static Label wrapLead(Label label) {
        label.setWrapText(true);
        label.setMinWidth(0);
        return label;
    }

    private record InvestmentValue(String name, String key, long valueCents) {}

    private record DashData(
        List<InvestmentType> investments,
        Map<Long, Long> currentValues,
        long totalPatrimony,
        long totalProfit,
        long totalInvestmentValue,
        java.util.TreeMap<LocalDate, Long> snapshotSeries,
        LocalDate snapshotFrom,
        LocalDate snapshotTo,
        List<StatementEntry> recentTransactions,
        PortfolioMetrics metrics,
        DailyTrackingUseCase.CashStatus cash
    ) {}

    /** Instala tooltip num nó XYChart, com fallback para nodeProperty se o nó ainda não existe. */
    private static void installXYTooltip(XYChart.Data<String, Number> d, String text) {
        installXYTooltipStyled(d, text, null, null);
    }

    private static void installXYTooltipStyled(XYChart.Data<String, Number> d,
                                                String text,
                                                String baseColor,
                                                String hoverColor) {
        javafx.scene.Node n = d.getNode();
        if (n != null) {
            applyNodeTooltip(n, text, baseColor, hoverColor);
        } else {
            d.nodeProperty().addListener((obs, o, node) -> {
                if (node != null) applyNodeTooltip(node, text, baseColor, hoverColor);
            });
        }
    }

    private static void applyNodeTooltip(javafx.scene.Node node, String text,
                                          String baseColor, String hoverColor) {
        if (baseColor != null) {
            node.setStyle("-fx-background-color: " + baseColor + "; -fx-padding: 4; -fx-background-radius: 50;");
            node.setOnMouseEntered(e -> node.setStyle(
                    "-fx-background-color: " + hoverColor + "; -fx-padding: 5; -fx-background-radius: 50;"));
            node.setOnMouseExited(e -> node.setStyle(
                    "-fx-background-color: " + baseColor + "; -fx-padding: 4; -fx-background-radius: 50;"));
        }
        Tooltip tp = new Tooltip(text);
        tp.setShowDelay(javafx.util.Duration.ZERO);
        Tooltip.install(node, tp);
    }
}

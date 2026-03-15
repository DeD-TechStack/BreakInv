package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.util.ChartAxisUtils;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.Motion;
import com.daniel.core.service.DiversificationCalculator;
import com.daniel.core.service.DiversificationCalculator.*;
import com.daniel.infrastructure.api.BcbClient;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.presentation.view.PageHeader;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
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

public final class DashboardPage implements Page {

    private final DailyTrackingUseCase daily;
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    private final Label dateLabel = new Label("—");
    private final Label totalLabel = new Label("—");
    private final Label profitLabel = new Label("—");
    private final Label cdiComparisonLabel = new Label("—");

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

    private final VBox investmentsByCategoryContainer = new VBox(16);
    private final VBox rankPanelAltas = new VBox(8);
    private final VBox rankPanelBaixas = new VBox(8);
    private final HBox rankPanel = new HBox(12);

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

    private final Label noComparisonHint = new Label(
            "Sem dados suficientes — adicione investimentos com valor registrado para ver o gráfico de performance.");
    private final Label projectionHint = new Label(
            "Projeção estimada — sem histórico de snapshots no período selecionado. Os dados reais serão usados quando houver snapshots registrados.");

    private int selectedFilterMonths = 12;
    private LocalDate customFrom = null;
    private LocalDate customTo = null;
    private boolean useCustomRange = false;
    private final HBox filterBar = new HBox(6);
    private final DatePicker fromPicker = new DatePicker();
    private final DatePicker toPicker = new DatePicker();
    private final HBox datePickerBox = new HBox(8);

    private final Label tokenWarningBanner = new Label(
            "⚠️  Token Brapi não configurado — cotações de ações usam preço de compra como referência. " +
            "Configure seu token na página Configurações para ver rentabilidade real.");
    private final AppSettingsRepository settingsRepo = new AppSettingsRepository();

    // BRAPI freshness chips
    private final Label freshnessChip = new Label();
    private final Label connectionChip = new Label();

    public DashboardPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;

        NumberAxis yAxis = new NumberAxis();
        waterfallXAxis.setLabel("Investimentos");
        yAxis.setLabel("Valor (R$)");
        waterfallChart = new BarChart<>(waterfallXAxis, yAxis);
        waterfallChart.setTitle("Composição do Patrimônio");
        waterfallChart.setLegendVisible(false);
        ChartAxisUtils.installSmartAxis(waterfallXAxis, waterfallChart);

        comparisonChart.setTitle(null);
        comparisonChart.setMinHeight(300);
        comparisonChart.setCreateSymbols(true);
        comparisonChart.setAnimated(false);

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Dashboard", "Resumo do seu portfólio de investimentos");

        freshnessChip.getStyleClass().addAll("brapi-chip", "brapi-chip-neutral");
        connectionChip.getStyleClass().addAll("brapi-chip", "brapi-chip-neutral");

        VBox chipBox = new VBox(4, freshnessChip, connectionChip);
        chipBox.setAlignment(javafx.geometry.Pos.TOP_RIGHT);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox.setHgrow(header, Priority.ALWAYS);

        HBox headerRow = new HBox(8, header, headerSpacer, chipBox);
        headerRow.setAlignment(javafx.geometry.Pos.TOP_LEFT);

        HBox cards = new HBox(12,
                kpiCard("📅", "Data", dateLabel, null),
                kpiCard("💰", "Patrimônio Total", totalLabel, null),
                kpiCard("📈", "Lucro acumulado", profitLabel, null),
                kpiCard("📊", "vs CDI (acum.)", cdiComparisonLabel, null)
        );
        cards.getStyleClass().add("dashboard-kpi-row");

        Label h2 = new Label("ANÁLISE DA CARTEIRA");
        h2.getStyleClass().add("section-title");

        HBox chartsRow = new HBox(12);

        VBox pieBox = new VBox(8);
        pieBox.getStyleClass().add("chart-card");
        HBox.setHgrow(pieBox, Priority.ALWAYS);
        Label pieTitle = new Label("DIVERSIFICAÇÃO");
        pieTitle.getStyleClass().add("card-title");
        pieChart.setMinHeight(280);
        pieChart.setLegendSide(Side.RIGHT);
        VBox.setVgrow(pieChart, Priority.ALWAYS);
        pieBox.getChildren().addAll(pieTitle, pieChart);

        VBox waterfallBox = new VBox(8);
        waterfallBox.getStyleClass().add("chart-card");
        HBox.setHgrow(waterfallBox, Priority.ALWAYS);
        Label waterfallTitle = new Label("DISTRIBUIÇÃO DE VALORES");
        waterfallTitle.getStyleClass().add("card-title");
        waterfallChart.setMinHeight(280);
        VBox.setVgrow(waterfallChart, Priority.ALWAYS);
        waterfallBox.getChildren().addAll(waterfallTitle, waterfallChart);

        rankPanel.getStyleClass().add("chart-card");
        HBox.setHgrow(rankPanel, Priority.ALWAYS);

        HBox.setHgrow(rankPanelAltas, Priority.ALWAYS);
        HBox.setHgrow(rankPanelBaixas, Priority.ALWAYS);

        Label altasTitle = new Label("MAIORES ALTAS");
        altasTitle.getStyleClass().addAll("card-title", "pos");
        rankPanelAltas.getChildren().add(altasTitle);

        Label baixasTitle = new Label("MAIORES BAIXAS");
        baixasTitle.getStyleClass().addAll("card-title", "neg");
        rankPanelBaixas.getChildren().add(baixasTitle);

        Separator vertSep = new Separator(javafx.geometry.Orientation.VERTICAL);
        rankPanel.getChildren().addAll(rankPanelAltas, vertSep, rankPanelBaixas);

        chartsRow.getChildren().addAll(pieBox, waterfallBox, rankPanel);

        VBox comparisonBox = buildComparisonSection();

        tokenWarningBanner.setWrapText(true);
        tokenWarningBanner.getStyleClass().add("warning-banner");
        tokenWarningBanner.setVisible(false);
        tokenWarningBanner.setManaged(false);

        HBox extraRow = new HBox(12, buildHealthCard(), buildRecentActivityCard());
        HBox.setHgrow(extraRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(extraRow.getChildren().get(1), Priority.ALWAYS);

        root.getChildren().addAll(headerRow, tokenWarningBanner, cards, extraRow, h2, chartsRow, comparisonBox, investmentsByCategoryContainer);

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
        boolean hasToken = BrapiClient.hasToken();
        tokenWarningBanner.setVisible(!hasToken);
        tokenWarningBanner.setManaged(!hasToken);
        updateBrapiChips();
        refreshData();
        if (!ratesFetched) {
            fetchRealRates();
        }
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
        dateLabel.setText(formatDate(today));

        List<InvestmentType> investments = daily.listTypes();

        if (investments.isEmpty()) {
            totalLabel.setText("—");
            profitLabel.setText("—");
            cdiComparisonLabel.setText("—");
            pieChart.getData().clear();
            waterfallChart.getData().clear();
            comparisonChart.getData().clear();
            investmentsByCategoryContainer.getChildren().clear();
            healthBar.setProgress(0);
            healthScoreLabel.setText("—");
            healthDescLabel.setText("Sem investimentos cadastrados.");
            updateRecentActivity();
            return;
        }

        Map<Long, Long> currentValues = daily.getAllCurrentValues(today);
        long totalPatrimony = daily.getTotalPatrimony(today);
        long totalProfit = daily.getTotalProfit(today);

        Motion.animateLabelChange(totalLabel, daily.brl(totalPatrimony));

        profitLabel.setText(totalProfit == 0 ? "—" :
                ((totalProfit >= 0 ? "+ " : "- ") + daily.brlAbs(Math.abs(totalProfit))));
        profitLabel.getStyleClass().removeAll("pos", "neg", "muted");
        if (totalProfit == 0) {
            profitLabel.getStyleClass().add("muted");
        } else {
            profitLabel.getStyleClass().add(totalProfit >= 0 ? "pos" : "neg");
        }

        updateCDIComparison(today, investments, totalPatrimony);
        updatePieChart(investments, currentValues);
        updateWaterfallChart(investments, currentValues);
        updateComparisonChart(investments, currentValues, today);
        updateInvestmentsByCategory(investments, currentValues, totalPatrimony);
        updateRankPanel(investments, currentValues);
        updateHealthScore(investments, currentValues, totalPatrimony, totalProfit);
        updateRecentActivity();
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

        if (data.totalCents() == 0) {
            pieChart.setTitle("Sem dados de diversificação");
            return;
        }

        pieChart.setTitle(null);

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

    private void applyPieStyle(PieChart.Data slice, String color, String tipText) {
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

        // Agrupar por nome, somando os valores (evita duplicatas para o mesmo ativo)
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (InvestmentType inv : investments) {
            long value = currentValues.getOrDefault((long) inv.id(), 0L);
            if (value > 0) {
                grouped.merge(inv.name(), value, Long::sum);
            }
        }

        List<InvestmentValue> invValues = grouped.entrySet().stream()
                .map(e -> new InvestmentValue(e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.valueCents, a.valueCents))
                .toList();

        for (InvestmentValue iv : invValues) {
            series.getData().add(new XYChart.Data<>(truncateName(iv.name, 15), iv.valueCents / 100.0));
        }

        waterfallChart.getData().add(series);

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

    private VBox buildComparisonSection() {
        VBox box = new VBox(12);
        box.getStyleClass().add("chart-card");

        Label title = new Label("PERFORMANCE DA CARTEIRA");
        title.getStyleClass().add("card-title");

        // Botões de benchmark
        ToggleGroup benchTg = new ToggleGroup();
        HBox benchBar = new HBox(8);
        benchBar.setAlignment(Pos.CENTER_RIGHT);

        for (String bench : List.of("CDI", "SELIC", "IPCA", "IBOVESPA")) {
            ToggleButton btn = new ToggleButton(bench);
            btn.setToggleGroup(benchTg);
            btn.getStyleClass().add("bench-toggle");
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
        metricsPanel.setMinWidth(160);
        metricsPanel.setPadding(new Insets(4, 0, 0, 20));
        metricsPanel.getChildren().addAll(
                benchBar,
                buildMetricBox("Rendimento", metricRendimentoLabel),
                buildMetricBox("Rentabilidade", metricRentabilidadeLabel),
                buildMetricBoxCustomTitle(metricBenchmarkTitleLabel, metricBenchmarkLabel)
        );

        // Configurar gráfico
        compXAxis.setLabel(null);
        compYAxis.setLabel("Rentabilidade %");
        comparisonChart.setAnimated(false);
        comparisonChart.setCreateSymbols(true);
        comparisonChart.setLegendVisible(true);
        comparisonChart.setMinHeight(300);

        // Instala listener de largura para atualizar labels automaticamente no resize
        ChartAxisUtils.installSmartAxis(compXAxis, comparisonChart);

        javafx.scene.layout.StackPane compWrapper = ChartCrosshair.install(comparisonChart,
                y -> String.format("%s%.2f%%", y >= 0 ? "+" : "", y).replace('.', ','));
        HBox chartRow = new HBox(0, compWrapper);
        HBox.setHgrow(compWrapper, Priority.ALWAYS);
        chartRow.setAlignment(Pos.TOP_LEFT);

        // metricsPanel fica fora do chartRow, em coluna separada à direita
        HBox contentRow = new HBox(12, chartRow, metricsPanel);
        HBox.setHgrow(chartRow, Priority.ALWAYS);
        contentRow.setAlignment(Pos.TOP_LEFT);

        // Barra de filtro de período
        buildFilterBar();

        noComparisonHint.getStyleClass().add("empty-hint");
        noComparisonHint.setWrapText(true);
        noComparisonHint.setVisible(false);
        noComparisonHint.setManaged(false);

        projectionHint.getStyleClass().add("text-helper");
        projectionHint.setWrapText(true);
        projectionHint.setVisible(false);
        projectionHint.setManaged(false);

        box.getChildren().addAll(title, filterBar, datePickerBox, noComparisonHint, projectionHint, contentRow);
        return box;
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

    private void buildFilterBar() {
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(4, 0, 4, 0));

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

        filterBar.getChildren().addAll(buttons);

        fromPicker.setPromptText("De");
        fromPicker.setPrefWidth(130);
        toPicker.setPromptText("Até");
        toPicker.setPrefWidth(130);
        toPicker.setValue(LocalDate.now());

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
                                       LocalDate today) {
        comparisonChart.getData().clear();

        // ── Empty state ──────────────────────────────────────────────────────
        long totalInvestido = calcTotalInvestido(investments);
        if (investments.isEmpty() || totalInvestido == 0) {
            noComparisonHint.setVisible(true);
            noComparisonHint.setManaged(true);
            projectionHint.setVisible(false);
            projectionHint.setManaged(false);
            return;
        }
        noComparisonHint.setVisible(false);
        noComparisonHint.setManaged(false);

        // ── Determine date range ─────────────────────────────────────────────
        LocalDate dataInicio;
        LocalDate dataFim = today;
        if (useCustomRange && customFrom != null && customTo != null) {
            dataInicio = customFrom;
            dataFim = customTo;
        } else {
            dataInicio = today.minusMonths(selectedFilterMonths);
            LocalDate maisAntiga = investments.stream()
                    .filter(inv -> inv.investmentDate() != null)
                    .map(InvestmentType::investmentDate)
                    .min(LocalDate::compareTo)
                    .orElse(dataInicio);
            if (maisAntiga.isAfter(dataInicio)) dataInicio = maisAntiga;
        }

        // ── Benchmark rate ───────────────────────────────────────────────────
        double taxaAnualBench = switch (selectedBenchmark) {
            case "SELIC"    -> rateSelic;
            case "IPCA"     -> rateIpca;
            case "IBOVESPA" -> Double.isNaN(rateIbov) ? 0.0 : rateIbov;
            default         -> rateCdi;
        };
        double taxaMensalBench = Math.pow(1 + taxaAnualBench, 1.0 / 12) - 1;

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/yy");

        XYChart.Series<String, Number> carteiraSeries = new XYChart.Series<>();
        carteiraSeries.setName("Carteira");
        XYChart.Series<String, Number> benchSeries = new XYChart.Series<>();
        benchSeries.setName(selectedBenchmark);
        XYChart.Series<String, Number> zeroSeries = new XYChart.Series<>();
        zeroSeries.setName("—");

        double rentCartFinal = 0;
        double rentBenchFinal = 0;

        // ── Try real snapshot data ───────────────────────────────────────────
        java.util.TreeMap<LocalDate, Long> snapshots =
                daily.getPortfolioSnapshotSeries(dataInicio, dataFim);

        if (snapshots.size() >= 2) {
            // Real data path — % change relative to first snapshot
            projectionHint.setVisible(false);
            projectionHint.setManaged(false);

            List<Map.Entry<LocalDate, Long>> entries = new ArrayList<>(snapshots.entrySet());
            long firstValue = entries.get(0).getValue();
            LocalDate startDate = entries.get(0).getKey();

            for (Map.Entry<LocalDate, Long> entry : entries) {
                String lbl = entry.getKey().format(fmt);
                double rentCart = firstValue > 0
                        ? (entry.getValue() - firstValue) * 100.0 / firstValue : 0;
                double monthsFromStart = java.time.temporal.ChronoUnit.DAYS.between(
                        startDate, entry.getKey()) / 30.44;
                double rentBench = (Math.pow(1 + taxaMensalBench, monthsFromStart) - 1) * 100;
                carteiraSeries.getData().add(new XYChart.Data<>(lbl, rentCart));
                benchSeries.getData().add(new XYChart.Data<>(lbl, rentBench));
                zeroSeries.getData().add(new XYChart.Data<>(lbl, 0));
            }

            Map.Entry<LocalDate, Long> last = entries.get(entries.size() - 1);
            rentCartFinal = firstValue > 0
                    ? (last.getValue() - firstValue) * 100.0 / firstValue : 0;
            double monthsFinal = java.time.temporal.ChronoUnit.DAYS.between(
                    startDate, last.getKey()) / 30.44;
            rentBenchFinal = (Math.pow(1 + taxaMensalBench, monthsFinal) - 1) * 100;

        } else {
            // Projection path — honest note shown
            projectionHint.setVisible(true);
            projectionHint.setManaged(true);

            long patrimonioAtual = currentValues.values().stream()
                    .mapToLong(Long::longValue).sum();
            double rentTotalCarteira = totalInvestido > 0
                    ? (patrimonioAtual - totalInvestido) * 100.0 / totalInvestido : 0;
            long mesesTotaisCarteira = calcularMesesDesdeInvestimentoMaisAntigo(investments, today);
            if (mesesTotaisCarteira < 1) mesesTotaisCarteira = 1;
            double taxaMensalCarteira =
                    Math.pow(1 + rentTotalCarteira / 100.0, 1.0 / mesesTotaisCarteira) - 1;

            long totalMeses = java.time.temporal.ChronoUnit.MONTHS.between(dataInicio, dataFim);
            if (totalMeses < 1) totalMeses = 1;
            long pontos = Math.min(totalMeses, mesesTotaisCarteira);

            for (long m = 0; m <= pontos; m++) {
                String lbl = dataInicio.plusMonths(m).format(fmt);
                double rentCart  = (Math.pow(1 + taxaMensalCarteira, m) - 1) * 100;
                double rentBench = (Math.pow(1 + taxaMensalBench,    m) - 1) * 100;
                carteiraSeries.getData().add(new XYChart.Data<>(lbl, rentCart));
                benchSeries.getData().add(new XYChart.Data<>(lbl, rentBench));
                zeroSeries.getData().add(new XYChart.Data<>(lbl, 0));
            }

            rentCartFinal  = (Math.pow(1 + taxaMensalCarteira, (double) pontos) - 1) * 100;
            rentBenchFinal = (Math.pow(1 + taxaMensalBench,    (double) pontos) - 1) * 100;
        }

        comparisonChart.getData().addAll(carteiraSeries, benchSeries, zeroSeries);

        // Atualiza densidade de labels do eixo X com base na largura atual
        Platform.runLater(() -> ChartAxisUtils.refreshLabels(compXAxis, comparisonChart.getWidth()));

        // Estilizar linhas das séries após renderização
        Platform.runLater(() -> {
            javafx.scene.Node cartLine = comparisonChart.lookup(".series0.chart-series-line");
            if (cartLine != null) cartLine.setStyle("-fx-stroke: #22c55e; -fx-stroke-width: 2;");

            javafx.scene.Node benchLine = comparisonChart.lookup(".series1.chart-series-line");
            if (benchLine != null) benchLine.setStyle(
                    "-fx-stroke: rgba(255,255,255,0.85); -fx-stroke-width: 1.5;");

            javafx.scene.Node zeroLine = comparisonChart.lookup(".series2.chart-series-line");
            if (zeroLine != null) zeroLine.setStyle(
                    "-fx-stroke: rgba(255,255,255,0.25); -fx-stroke-width: 1; -fx-stroke-dash-array: 6 4;");
        });

        // Tooltips + coloração após renderização
        final String benchName = benchSeries.getName();
        Platform.runLater(() -> {
            for (XYChart.Data<String, Number> d : carteiraSeries.getData()) {
                double val = d.getYValue().doubleValue();
                String lbl = d.getXValue() + "\nCarteira: "
                        + String.format("%.2f%%", val).replace('.', ',');
                String baseColor  = val >= 0 ? "rgba(34,197,94,0.8)"  : "rgba(239,68,68,0.8)";
                String hoverColor = val >= 0 ? "rgba(34,197,94,1.0)"  : "rgba(239,68,68,1.0)";
                installXYTooltipStyled(d, lbl, baseColor, hoverColor);
            }
            for (XYChart.Data<String, Number> d : benchSeries.getData()) {
                String lbl = d.getXValue() + "\n" + benchName + ": "
                        + String.format("%.2f%%", d.getYValue().doubleValue()).replace('.', ',');
                installXYTooltipStyled(d, lbl,
                        "rgba(255,255,255,0.7)", "rgba(255,255,255,1.0)");
            }
            for (XYChart.Data<String, Number> d : zeroSeries.getData()) {
                javafx.scene.Node n = d.getNode();
                if (n != null) n.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                else d.nodeProperty().addListener((obs, o, n2) -> {
                    if (n2 != null) n2.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                });
            }
        });

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
        // Keep the titles, show loading
        rankPanelAltas.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("card-title")));
        rankPanelBaixas.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("card-title")));
        Label loading = new Label("Carregando...");
        loading.getStyleClass().add("muted");
        rankPanelAltas.getChildren().add(loading);

        CompletableFuture.supplyAsync(() -> {
            List<RankEntry> entries = new ArrayList<>();

            // Collect tickers that need Brapi lookup
            List<InvestmentType> withTicker = new ArrayList<>();
            List<InvestmentType> withoutTicker = new ArrayList<>();

            for (InvestmentType inv : investments) {
                if (inv.ticker() != null && !inv.ticker().isBlank()) {
                    withTicker.add(inv);
                } else {
                    withoutTicker.add(inv);
                }
            }

            // Agrupar por ticker — garante uma entrada única por ticker no ranking
            if (!withTicker.isEmpty()) {
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
                    double change = (data != null && data.isValid()) ? data.regularMarketChangePercent() : 0;

                    long totalValue = group.stream()
                            .mapToLong(inv -> currentValues.getOrDefault((long) inv.id(), 0L))
                            .sum();

                    if (totalValue <= 0) continue; // ignorar tickers sem valor atual

                    String displayName = group.size() == 1 ? group.get(0).name() : ticker;
                    entries.add(new RankEntry(displayName, ticker, change, totalValue));
                }
            }

            // Renda fixa: variação diária estimada = taxa anual / 252
            for (InvestmentType inv : withoutTicker) {
                long value = currentValues.getOrDefault((long) inv.id(), 0L);
                if (value <= 0) continue; // ignorar ativos sem valor atual
                double dailyChange = inv.profitability() != null
                        ? inv.profitability().doubleValue() / 252.0
                        : 0;
                entries.add(new RankEntry(inv.name(), null, dailyChange, value));
            }

            // Retornar lista completa; separação em altas/baixas feita no Platform.runLater
            return entries;
        }).thenAcceptAsync(entries -> Platform.runLater(() -> {
            rankPanelAltas.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("card-title")));
            rankPanelBaixas.getChildren().removeIf(n -> !(n instanceof Label l && l.getStyleClass().contains("card-title")));

            if (entries.isEmpty()) {
                Label empty = new Label("Nenhum ativo");
                empty.getStyleClass().add("muted");
                rankPanelAltas.getChildren().add(empty);
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

        Label title = new Label("Investimentos por Categoria");
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(circle, categoryName, spacer, categoryPercentLabel);

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

            HBox invRow = buildGroupedStockRow(ticker, tickerInvs, groupTotal, totalPatrimony);
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

            HBox invRow = buildInvestmentRow(inv, currentValue, totalPatrimony);
            investmentsList.getChildren().add(invRow);
        }

        section.getChildren().addAll(header, new Separator(), investmentsList);
        return section;
    }

    private HBox buildGroupedStockRow(String ticker, List<InvestmentType> investments,
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
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("inv-row-card");

        VBox mainInfo = new VBox(4);
        Label nameLabel = new Label(ticker);
        nameLabel.getStyleClass().add("inv-row-name");
        Label qtdSubLabel = new Label("Qtd: " + qtdFinal);
        qtdSubLabel.getStyleClass().add("text-dim");
        mainInfo.getChildren().addAll(nameLabel, qtdSubLabel);
        row.getChildren().add(mainInfo);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);

        // Ticker Badge
        VBox tickerBox = new VBox(2);
        tickerBox.setAlignment(Pos.CENTER_RIGHT);
        Label tickerLabel = new Label(ticker);
        tickerLabel.getStyleClass().add("ticker-badge");
        Label tickerHint = new Label("Ticker");
        tickerHint.getStyleClass().add("text-dim-xs");
        tickerBox.getChildren().addAll(tickerLabel, tickerHint);
        row.getChildren().add(tickerBox);

        // Labels atualizados assincronamente (Posição Atual e Rentabilidade)
        Label rentLabel = new Label("...");
        rentLabel.getStyleClass().add("rent-dimmed");

        Label posicaoLabel = new Label(daily.brl(totalValueCents));
        posicaoLabel.getStyleClass().add("info-box-value");

        row.getChildren().add(createInfoBox("Valor Investido", daily.brl(totalInvestido)));
        row.getChildren().add(createInfoBox("Posição Atual", posicaoLabel));
        row.getChildren().add(createInfoBox("Rentabilidade", rentLabel));
        row.getChildren().add(createInfoBox("% Alocação", String.format("%.1f%%", alocacao)));
        // Qtd Total e Preço Médio são dados locais — sempre visíveis, independente de token
        row.getChildren().add(createInfoBox("Qtd Total", String.valueOf(qtdFinal)));
        row.getChildren().add(createInfoBox("Preço Médio", String.format("R$ %.2f", precoMedioFinal)));

        if (dataInvestimento != null) {
            row.getChildren().add(createInfoBox("Data Investimento",
                    dataInvestimento.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        }

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

    private HBox buildInvestmentRow(InvestmentType inv, long currentValueCents, long totalPatrimony) {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("inv-row-card");

        VBox mainInfo = new VBox(4);
        Label nameLabel = new Label(inv.name());
        nameLabel.getStyleClass().add("inv-row-name");

        if (inv.typeOfInvestment() != null) {
            Label typeLabel = new Label(getTypeDisplayName(inv.typeOfInvestment()));
            typeLabel.getStyleClass().add("text-dim");
            mainInfo.getChildren().addAll(nameLabel, typeLabel);
        } else {
            mainInfo.getChildren().add(nameLabel);
        }
        row.getChildren().add(mainInfo);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);

        if (inv.ticker() != null && !inv.ticker().isBlank()) {
            row.getChildren().addAll(buildStockInfo(inv, currentValueCents, totalPatrimony));
        } else if (inv.category() != null && inv.category().equals("RENDA_FIXA")) {
            row.getChildren().addAll(buildRendaFixaInfo(inv, currentValueCents, totalPatrimony));
        } else {
            row.getChildren().addAll(buildGenericInfo(inv, currentValueCents, totalPatrimony));
        }

        return row;
    }

    private List<javafx.scene.Node> buildStockInfo(InvestmentType inv, long currentValueCents, long totalPatrimony) {
        List<javafx.scene.Node> nodes = new ArrayList<>();

        VBox tickerBox = new VBox(2);
        tickerBox.setAlignment(Pos.CENTER_RIGHT);
        Label tickerLabel = new Label(inv.ticker());
        tickerLabel.getStyleClass().add("ticker-badge");
        Label tickerHint = new Label("Ticker");
        tickerHint.getStyleClass().add("text-dim-xs");
        tickerBox.getChildren().addAll(tickerLabel, tickerHint);
        nodes.add(tickerBox);

        if (inv.quantity() != null && inv.purchasePrice() != null) {
            int qtdTotal = inv.quantity();
            double precoMedio = inv.purchasePrice().doubleValue();
            double posicaoAtual = currentValueCents / 100.0;
            double ultimoPreco = posicaoAtual / qtdTotal;

            long valorInvestidoCents = (long)(precoMedio * qtdTotal * 100);
            nodes.add(createInfoBox("Valor Investido", daily.brl(valorInvestidoCents)));

            Label posicaoLabel = new Label(daily.brl(currentValueCents));
            posicaoLabel.getStyleClass().add("info-box-value");
            nodes.add(createInfoBox("Posição Atual", posicaoLabel));

            double rentabilidade = ((ultimoPreco - precoMedio) / precoMedio) * 100;
            Label rentLabel;
            if (!BrapiClient.hasToken()) {
                rentLabel = new Label("—");
                applyRentDimmed(rentLabel);
            } else {
                rentLabel = new Label(String.format("%+.2f%%", rentabilidade));
                applyRentStyle(rentLabel, rentabilidade);
            }
            nodes.add(createInfoBox("Rentabilidade", rentLabel));

            double alocacao = (currentValueCents * 100.0) / totalPatrimony;
            nodes.add(createInfoBox("% Alocação", String.format("%.1f%%", alocacao)));
            nodes.add(createInfoBox("Preço Médio", String.format("R$ %.2f", precoMedio)));
            nodes.add(createInfoBox("Último Preço", String.format("R$ %.2f",
                    BrapiClient.hasToken() ? ultimoPreco : precoMedio)));
            nodes.add(createInfoBox("Qtd Total", String.valueOf(qtdTotal)));

            if (inv.investmentDate() != null) {
                nodes.add(createInfoBox("Data Investimento",
                        inv.investmentDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
            }
        }

        return nodes;
    }

    private List<javafx.scene.Node> buildRendaFixaInfo(InvestmentType inv, long currentValueCents, long totalPatrimony) {
        List<javafx.scene.Node> nodes = new ArrayList<>();

        double alocacao = (currentValueCents * 100.0) / totalPatrimony;

        if (inv.investedValue() != null) {
            long aplicado = inv.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            nodes.add(createInfoBox("Valor Investido", daily.brl(aplicado)));
            nodes.add(createInfoBox("Posição Atual", daily.brl(currentValueCents)));

            long lucro = currentValueCents - aplicado;
            double rentabilidade = (lucro * 100.0) / aplicado;
            Label rentLabel = new Label(String.format("%+.2f%%", rentabilidade));
            applyRentStyle(rentLabel, rentabilidade);
            nodes.add(createInfoBox("Rentabilidade", rentLabel));
        }

        nodes.add(createInfoBox("% Alocação", String.format("%.1f%%", alocacao)));

        if (inv.profitability() != null) {
            String taxa = String.format("%.2f%% a.a.", inv.profitability());
            nodes.add(createInfoBox("Taxa", taxa));
        }

        if (inv.investmentDate() != null) {
            nodes.add(createInfoBox("Data Investimento",
                    inv.investmentDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        }

        return nodes;
    }

    private List<javafx.scene.Node> buildGenericInfo(InvestmentType inv, long currentValueCents, long totalPatrimony) {
        List<javafx.scene.Node> nodes = new ArrayList<>();

        double alocacao = (currentValueCents * 100.0) / totalPatrimony;

        if (inv.investedValue() != null) {
            long investido = inv.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            nodes.add(createInfoBox("Valor Investido", daily.brl(investido)));
            nodes.add(createInfoBox("Posição Atual", daily.brl(currentValueCents)));

            long lucro = currentValueCents - investido;
            double rentabilidade = investido > 0 ? (lucro * 100.0) / investido : 0;
            Label rentLabel = new Label(String.format("%+.2f%%", rentabilidade));
            applyRentStyle(rentLabel, rentabilidade);
            nodes.add(createInfoBox("Rentabilidade", rentLabel));
        }

        nodes.add(createInfoBox("% Alocação", String.format("%.1f%%", alocacao)));

        if (inv.investmentDate() != null) {
            nodes.add(createInfoBox("Data Investimento",
                    inv.investmentDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        }

        return nodes;
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

        Label labelLabel = new Label(label);
        labelLabel.getStyleClass().add("info-box-label");

        box.getChildren().addAll(valueLabel, labelLabel);
        return box;
    }

    private VBox createInfoBox(String label, Label customLabel) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.getStyleClass().add("info-box");

        customLabel.getStyleClass().add("text-sm");

        Label labelLabel = new Label(label);
        labelLabel.getStyleClass().add("info-box-label");

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

    private VBox kpiCard(String icon, String title, Label value, String subText) {
        VBox box = new VBox(6);
        box.getStyleClass().add("hero-card");

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        Label iconLbl = new Label(icon);
        iconLbl.getStyleClass().addAll("kpi-label");
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("kpi-label");
        header.getChildren().addAll(iconLbl, titleLbl);

        value.getStyleClass().addAll("kpi-value", "num");
        box.getChildren().addAll(header, value);

        if (subText != null) {
            Label sub = new Label(subText);
            sub.getStyleClass().add("kpi-sub");
            box.getChildren().add(sub);
        }
        HBox.setHgrow(box, Priority.ALWAYS);
        Motion.hoverLift(box);
        return box;
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

    private void updateRecentActivity() {
        recentActivityList.getChildren().clear();
        List<Transaction> txs = daily.listTransactions(YearMonth.now());
        if (txs.isEmpty()) {
            Label empty = new Label("Nenhum lançamento este mês");
            empty.getStyleClass().add("text-helper");
            recentActivityList.getChildren().add(empty);
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        txs.stream().limit(4).forEach(tx -> {
            boolean isBuy = Transaction.BUY.equals(tx.type());
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("rank-row");
            Label typeIcon = new Label(isBuy ? "▼" : "▲");
            typeIcon.getStyleClass().add(isBuy ? "neg" : "pos");
            Label nameLabel = new Label(tx.name());
            nameLabel.getStyleClass().add("text-sm");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Label valLabel = new Label((isBuy ? "- " : "+ ") + daily.brl(tx.totalCents()));
            valLabel.getStyleClass().addAll("text-sm", isBuy ? "neg" : "pos");
            Label dateLabel = new Label(tx.date().format(fmt));
            dateLabel.getStyleClass().add("text-dim-xs");
            row.getChildren().addAll(typeIcon, nameLabel, sp, valLabel, dateLabel);
            recentActivityList.getChildren().add(row);
        });
    }

    private String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private String truncateName(String name, int maxLength) {
        if (name.length() <= maxLength) return name;
        return name.substring(0, maxLength - 3) + "...";
    }

    private record InvestmentValue(String name, long valueCents) {}

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
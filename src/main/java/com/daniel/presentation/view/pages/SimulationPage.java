package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Enums.InvestmentTypeEnum;
import com.daniel.core.util.Money;
import com.daniel.infrastructure.api.BcbClient;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.util.ChartCrosshair;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

public final class SimulationPage implements Page {

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    private enum RentabilityMode {
        FIXED_RATE("Taxa Fixa (% a.a.)"),
        BENCHMARK_PERCENT("% do Benchmark"),
        HYBRID("Híbrida (Índice + Taxa)");

        private final String display;
        RentabilityMode(String display) { this.display = display; }
        public String getDisplay() { return display; }
    }

    private final TextField initialValueField = new TextField();
    private final ComboBox<Integer> monthsCombo = new ComboBox<>();

    private final ComboBox<RentabilityMode> rentabilityModeCombo = new ComboBox<>();
    private RentabilityMode currentRentabilityMode = RentabilityMode.FIXED_RATE;

    private final TextField fixedRateField = new TextField();
    private final ComboBox<String> benchmarkCombo = new ComboBox<>();
    private final TextField benchmarkPercentField = new TextField();
    private final TextField indexRateField = new TextField();
    private final TextField hybridFixedField = new TextField();

    private final TextField tickerField = new TextField();
    private final TextField purchasePriceField = new TextField();
    private final TextField quantityField = new TextField();
    private final TextField currentPriceField = new TextField();
    private final TextField dividendsField = new TextField();
    private final Slider priceVariationSlider = new Slider(-20, 20, 0);
    private final Label sliderValueLabel = new Label("0%");

    private final Label resultLabel = new Label("—");
    private final Label resultSubLabel = new Label("Preencha os campos e clique em Calcular");
    private final LineChart<Number, Number> projectionChart;

    private final Label ratesStatusLabel = new Label();

    private double rateCdi = 0.135;
    private double rateSelic = 0.15;
    private double rateIpca = 0.045;

    private InvestmentTypeEnum currentType = InvestmentTypeEnum.PREFIXADO;

    // Visibility-controlled VBoxes
    private VBox fixedRateSection;
    private VBox benchmarkSection;
    private VBox hybridSection;
    private VBox stockSection;
    private VBox rentabilityModeSection;

    public SimulationPage() {
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Simulação",
                "Projete o rendimento dos seus investimentos");

        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Meses");
        yAxis.setLabel("Valor (R$)");
        projectionChart = new LineChart<>(xAxis, yAxis);
        projectionChart.setTitle(null);
        projectionChart.setMinHeight(380);
        projectionChart.setCreateSymbols(false);

        HBox typeSelector = buildTypeSelector();
        VBox baseParamsCard = buildBaseParamsCard();
        VBox rentabilityCard = buildRentabilityCard();
        VBox stockCard = buildStockCard();
        VBox resultCard = buildResultCard();

        VBox chartCard = new VBox(8);
        chartCard.getStyleClass().add("chart-card");
        Label chartTitle = new Label("PROJEÇÃO DE RENTABILIDADE");
        chartTitle.getStyleClass().add("card-title");
        javafx.scene.layout.StackPane projWrapper = ChartCrosshair.installNumeric(projectionChart,
                month -> "Mês " + month,
                y -> "R$ " + String.format("%.2f", y).replace('.', ','));
        VBox.setVgrow(projWrapper, Priority.ALWAYS);
        chartCard.getChildren().addAll(chartTitle, projWrapper);

        root.getChildren().addAll(header, typeSelector, baseParamsCard,
                rentabilityCard, stockCard, resultCard, chartCard);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");

        updateInputsVisibility();
        updateRentabilityInputs();
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        fetchRealRates();
    }

    private void fetchRealRates() {
        ratesStatusLabel.setText("Buscando taxas...");
        applyRatesStatusStyle("text-helper");

        CompletableFuture.supplyAsync(() -> {
            var cdi = BcbClient.fetchCdi();
            var selic = BcbClient.fetchSelic();
            var ipca = BcbClient.fetchIpca();
            return new double[]{
                    cdi.orElse(-1.0), selic.orElse(-1.0), ipca.orElse(-1.0)
            };
        }).thenAcceptAsync(rates -> Platform.runLater(() -> {
            boolean anyFailed = false;

            if (rates[0] > 0) { rateCdi = rates[0]; } else { anyFailed = true; }
            if (rates[1] > 0) { rateSelic = rates[1]; } else { anyFailed = true; }
            if (rates[2] > 0) { rateIpca = rates[2]; } else { anyFailed = true; }

            if (anyFailed) {
                ratesStatusLabel.setText("Algumas taxas usando valor estimado");
                applyRatesStatusStyle("text-xs", "state-warning");
            } else {
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                ratesStatusLabel.setText(String.format(
                        "Taxas atualizadas %s — CDI: %.2f%% | SELIC: %.2f%% | IPCA: %.2f%%",
                        time, rateCdi * 100, rateSelic * 100, rateIpca * 100));
                applyRatesStatusStyle("text-xs", "state-positive");
            }
        }));
    }

    private HBox buildTypeSelector() {
        HBox box = new HBox(12);
        box.getStyleClass().add("toolbar");
        box.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("Tipo:");
        label.getStyleClass().add("form-label");

        ToggleGroup group = new ToggleGroup();

        ToggleButton prefixadoBtn = new ToggleButton("Prefixado");
        ToggleButton posfixadoBtn = new ToggleButton("Pós-fixado");
        ToggleButton hibridoBtn   = new ToggleButton("Híbrido");
        ToggleButton acaoBtn      = new ToggleButton("Ação / FII");

        for (ToggleButton b : new ToggleButton[]{prefixadoBtn, posfixadoBtn, hibridoBtn, acaoBtn}) {
            b.getStyleClass().add("seg-btn");
            b.setToggleGroup(group);
        }

        prefixadoBtn.setSelected(true);

        // Prevent deselect — always keep one button selected
        group.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                group.selectToggle(oldVal);
                return;
            }
            if      (newVal == prefixadoBtn) currentType = InvestmentTypeEnum.PREFIXADO;
            else if (newVal == posfixadoBtn) currentType = InvestmentTypeEnum.POS_FIXADO;
            else if (newVal == hibridoBtn)   currentType = InvestmentTypeEnum.HIBRIDO;
            else if (newVal == acaoBtn)      currentType = InvestmentTypeEnum.ACAO;
            updateInputsVisibility();
        });

        HBox segmented = new HBox(0, prefixadoBtn, posfixadoBtn, hibridoBtn, acaoBtn);
        segmented.getStyleClass().add("segmented");
        segmented.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(label, segmented);
        return box;
    }

    private VBox buildBaseParamsCard() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("PARÂMETROS BASE");
        title.getStyleClass().add("card-title");

        Label initialLabel = new Label("Valor Inicial:");
        initialLabel.getStyleClass().add("form-label");
        initialValueField.setPromptText("R$ 10.000,00");
        initialValueField.setTextFormatter(Money.currencyFormatterEditable());
        Money.applyFormatOnBlur(initialValueField);

        Label monthsLabel = new Label("Período (meses):");
        monthsLabel.getStyleClass().add("form-label");
        monthsCombo.getItems().addAll(1, 3, 6, 12, 24, 36, 48, 60, 120);
        monthsCombo.setValue(12);

        HBox row = new HBox(12);
        VBox initialBox = new VBox(6, initialLabel, initialValueField);
        VBox monthsBox = new VBox(6, monthsLabel, monthsCombo);
        HBox.setHgrow(initialBox, Priority.ALWAYS);
        HBox.setHgrow(monthsBox, Priority.ALWAYS);
        monthsCombo.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().addAll(initialBox, monthsBox);

        box.getChildren().addAll(title, row);
        return box;
    }

    private VBox buildRentabilityCard() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("MODALIDADE DE RENTABILIDADE");
        title.getStyleClass().add("card-title");

        Label modeLabel = new Label("Modalidade:");
        modeLabel.getStyleClass().add("form-label");
        rentabilityModeCombo.getItems().addAll(RentabilityMode.values());
        rentabilityModeCombo.setValue(RentabilityMode.FIXED_RATE);
        rentabilityModeCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RentabilityMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplay());
            }
        });
        rentabilityModeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(RentabilityMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplay());
            }
        });
        rentabilityModeCombo.setMaxWidth(Double.MAX_VALUE);

        rentabilityModeCombo.valueProperty().addListener((obs, old, newVal) -> {
            currentRentabilityMode = newVal;
            updateRentabilityInputs();
        });

        rentabilityModeSection = new VBox(10);

        // Fixed rate section
        Label fixedLabel = new Label("Taxa Anual (%):");
        fixedLabel.getStyleClass().add("form-label");
        fixedRateField.setPromptText("12.50");
        fixedRateSection = new VBox(6, fixedLabel, fixedRateField);

        // Benchmark section
        Label benchmarkLabel = new Label("Benchmark:");
        benchmarkLabel.getStyleClass().add("form-label");
        benchmarkCombo.getItems().addAll("CDI", "SELIC", "IPCA");
        benchmarkCombo.setValue("CDI");
        benchmarkCombo.setMaxWidth(Double.MAX_VALUE);

        Label benchmarkPercentLabel = new Label("Percentual do Benchmark:");
        benchmarkPercentLabel.getStyleClass().add("form-label");
        benchmarkPercentField.setPromptText("110 (= 110% do CDI)");

        HBox benchRow = new HBox(12);
        VBox benchBox = new VBox(6, benchmarkLabel, benchmarkCombo);
        VBox benchPctBox = new VBox(6, benchmarkPercentLabel, benchmarkPercentField);
        HBox.setHgrow(benchBox, Priority.ALWAYS);
        HBox.setHgrow(benchPctBox, Priority.ALWAYS);
        benchRow.getChildren().addAll(benchBox, benchPctBox);
        VBox ratesPanel = new VBox(ratesStatusLabel);
        ratesPanel.getStyleClass().add("panel");
        benchmarkSection = new VBox(8, benchRow, ratesPanel);

        // Hybrid section
        Label hybridLabel = new Label("Taxa Fixa (% a.a.):");
        hybridLabel.getStyleClass().add("form-label");
        hybridFixedField.setPromptText("5.0");

        Label indexLabel = new Label("Taxa do Índice (% a.a.):");
        indexLabel.getStyleClass().add("form-label");
        indexRateField.setPromptText("4.5 (IPCA estimado)");

        HBox hybridRow = new HBox(12);
        VBox hybFixedBox = new VBox(6, hybridLabel, hybridFixedField);
        VBox hybIdxBox = new VBox(6, indexLabel, indexRateField);
        HBox.setHgrow(hybFixedBox, Priority.ALWAYS);
        HBox.setHgrow(hybIdxBox, Priority.ALWAYS);
        hybridRow.getChildren().addAll(hybFixedBox, hybIdxBox);
        hybridSection = new VBox(8, hybridRow);

        rentabilityModeSection.getChildren().addAll(
                new VBox(6, modeLabel, rentabilityModeCombo),
                fixedRateSection, benchmarkSection, hybridSection);

        Button calculateBtn = new Button("Calcular Simulação");
        calculateBtn.getStyleClass().add("button");
        calculateBtn.setMaxWidth(Double.MAX_VALUE);
        calculateBtn.setOnAction(e -> calculate());

        box.getChildren().addAll(title, rentabilityModeSection, calculateBtn);
        return box;
    }

    private VBox buildStockCard() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("DADOS DO ATIVO");
        title.getStyleClass().add("card-title");

        Label tickerLabel = new Label("Ticker:");
        tickerLabel.getStyleClass().add("form-label");
        tickerField.setPromptText("PETR4, VALE3, HGLG11...");

        tickerField.textProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.length() >= 4) {
                loadStockDataFromBrapi(newVal);
            }
        });

        Label purchasePriceLabel = new Label("Preço de Compra:");
        purchasePriceLabel.getStyleClass().add("form-label");
        purchasePriceField.setPromptText("R$ 35,50");
        purchasePriceField.setTextFormatter(Money.currencyFormatterEditable());

        Label quantityLabel = new Label("Quantidade:");
        quantityLabel.getStyleClass().add("form-label");
        quantityField.setPromptText("100");

        Label currentPriceLabel = new Label("Preço Atual (automático):");
        currentPriceLabel.getStyleClass().add("form-label");
        currentPriceField.setPromptText("Preenchido automaticamente");
        currentPriceField.setTextFormatter(Money.currencyFormatterEditable());
        currentPriceField.setDisable(true);

        Label dividendsLabel = new Label("Dividendos Estimados (automático):");
        dividendsLabel.getStyleClass().add("form-label");
        dividendsField.setPromptText("Calculado automaticamente");
        dividendsField.setTextFormatter(Money.currencyFormatterEditable());
        dividendsField.setDisable(true);

        Label variationLabel = new Label("Variação de Preço (%):");
        variationLabel.getStyleClass().add("form-label");
        priceVariationSlider.setShowTickLabels(true);
        priceVariationSlider.setShowTickMarks(true);
        priceVariationSlider.setMajorTickUnit(10);
        priceVariationSlider.setMinorTickCount(5);
        priceVariationSlider.setBlockIncrement(1);
        priceVariationSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            sliderValueLabel.setText(String.format("%.1f%%", newVal.doubleValue()));
            if (currentType == InvestmentTypeEnum.ACAO) {
                calculateStock();
            }
        });
        sliderValueLabel.getStyleClass().addAll("text-bold", "state-positive");
        HBox sliderRow = new HBox(12, priceVariationSlider, sliderValueLabel);
        HBox.setHgrow(priceVariationSlider, Priority.ALWAYS);
        sliderRow.setAlignment(Pos.CENTER_LEFT);

        HBox row1 = new HBox(12);
        VBox tickerBox = new VBox(6, tickerLabel, tickerField);
        VBox priceBox = new VBox(6, purchasePriceLabel, purchasePriceField);
        VBox qtyBox = new VBox(6, quantityLabel, quantityField);
        HBox.setHgrow(tickerBox, Priority.ALWAYS);
        HBox.setHgrow(priceBox, Priority.ALWAYS);
        HBox.setHgrow(qtyBox, Priority.ALWAYS);
        row1.getChildren().addAll(tickerBox, priceBox, qtyBox);

        HBox row2 = new HBox(12);
        VBox curPriceBox = new VBox(6, currentPriceLabel, currentPriceField);
        VBox divBox = new VBox(6, dividendsLabel, dividendsField);
        HBox.setHgrow(curPriceBox, Priority.ALWAYS);
        HBox.setHgrow(divBox, Priority.ALWAYS);
        row2.getChildren().addAll(curPriceBox, divBox);

        Button calculateBtn = new Button("Calcular Simulação");
        calculateBtn.getStyleClass().add("button");
        calculateBtn.setMaxWidth(Double.MAX_VALUE);
        calculateBtn.setOnAction(e -> calculate());

        VBox sliderPanel = new VBox(6, variationLabel, sliderRow);
        sliderPanel.getStyleClass().add("panel");

        box.getChildren().addAll(title, row1, row2, sliderPanel, calculateBtn);

        stockSection = box;
        return box;
    }

    private VBox buildResultCard() {
        VBox box = new VBox(8);
        box.getStyleClass().add("result-card");

        Label title = new Label("RESULTADO DA SIMULAÇÃO");
        title.getStyleClass().add("kpi-label");

        resultLabel.getStyleClass().addAll("kpi-value", "num");
        resultSubLabel.getStyleClass().add("kpi-sub");

        box.getChildren().addAll(title, resultLabel, resultSubLabel);
        return box;
    }

    private void updateInputsVisibility() {
        boolean isAcao = currentType == InvestmentTypeEnum.ACAO;

        if (rentabilityModeSection != null) {
            rentabilityModeSection.setVisible(!isAcao);
            rentabilityModeSection.setManaged(!isAcao);
        }
        if (stockSection != null) {
            stockSection.setVisible(isAcao);
            stockSection.setManaged(isAcao);
        }

        if (!isAcao) {
            updateRentabilityInputs();
        }
    }

    private void updateRentabilityInputs() {
        if (currentType == InvestmentTypeEnum.ACAO) return;

        if (fixedRateSection == null) return;

        fixedRateSection.setVisible(false);
        fixedRateSection.setManaged(false);
        benchmarkSection.setVisible(false);
        benchmarkSection.setManaged(false);
        hybridSection.setVisible(false);
        hybridSection.setManaged(false);

        switch (currentRentabilityMode) {
            case FIXED_RATE -> {
                fixedRateSection.setVisible(true);
                fixedRateSection.setManaged(true);
            }
            case BENCHMARK_PERCENT -> {
                benchmarkSection.setVisible(true);
                benchmarkSection.setManaged(true);
            }
            case HYBRID -> {
                hybridSection.setVisible(true);
                hybridSection.setManaged(true);
            }
        }
    }

    private void loadStockDataFromBrapi(String ticker) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return BrapiClient.fetchStockData(ticker);
            } catch (Exception e) {
                return null;
            }
        }).thenAcceptAsync(data -> {
            if (data != null && data.isValid()) {
                Platform.runLater(() -> {
                    currentPriceField.setText(Money.centsToText((long)(data.regularMarketPrice() * 100)));

                    double estimatedAnnualDividend = data.regularMarketPrice() * (data.dividendYield() / 100.0);
                    dividendsField.setText(Money.centsToText((long)(estimatedAnnualDividend * 100)));
                });
            }
        });
    }

    private void calculate() {
        try {
            if (currentType == InvestmentTypeEnum.ACAO) {
                calculateStock();
            } else {
                calculateFixedIncome();
            }
        } catch (Exception e) {
            resultLabel.setText("Erro: " + e.getMessage());
            applyResultStyle(false);
        }
    }

    private void calculateFixedIncome() {
        try {
            double capital = Money.textToCentsOrZero(initialValueField.getText()) / 100.0;
            int months = monthsCombo.getValue();

            if (capital == 0 || months == 0) {
                resultLabel.setText("Preencha valor inicial e período");
                resultSubLabel.setText("");
                return;
            }

            double annualRate = getAnnualRate();
            double monthlyRate = Math.pow(1 + annualRate, 1.0/12) - 1;

            double result = capital * Math.pow(1 + monthlyRate, months);
            double profit = result - capital;

            resultLabel.setText(String.format("R$ %.2f", result).replace('.', ','));
            resultSubLabel.setText(String.format("Lucro: R$ %.2f (%.1f%%) em %d meses",
                    profit, (profit / capital) * 100, months).replace('.', ','));
            applyResultStyle(true);

            updateChartMonths(capital, monthlyRate, months, "Renda Fixa");

        } catch (Exception e) {
            resultLabel.setText("Verifique os valores");
            resultSubLabel.setText("Certifique-se de que todos os campos estão preenchidos corretamente.");
            applyResultStyle(false);
        }
    }

    private double getAnnualRate() {
        switch (currentRentabilityMode) {
            case FIXED_RATE -> {
                return Double.parseDouble(fixedRateField.getText().replace(",", ".")) / 100.0;
            }
            case BENCHMARK_PERCENT -> {
                double benchmarkRate = getBenchmarkRate(benchmarkCombo.getValue());
                double percent = Double.parseDouble(benchmarkPercentField.getText().replace(",", ".")) / 100.0;
                return benchmarkRate * percent;
            }
            case HYBRID -> {
                double fixedPart = Double.parseDouble(hybridFixedField.getText().replace(",", ".")) / 100.0;
                double indexPart = Double.parseDouble(indexRateField.getText().replace(",", ".")) / 100.0;
                return (1 + indexPart) * (1 + fixedPart) - 1;
            }
        }
        return 0;
    }

    private double getBenchmarkRate(String benchmark) {
        return switch (benchmark) {
            case "CDI" -> rateCdi;
            case "SELIC" -> rateSelic;
            case "IPCA" -> rateIpca;
            default -> 0.10;
        };
    }

    private void calculateStock() {
        try {
            double purchasePrice = Money.textToCentsOrZero(purchasePriceField.getText()) / 100.0;
            String qtyText = quantityField.getText();

            if (qtyText == null || qtyText.isBlank()) {
                resultLabel.setText("Preencha a quantidade");
                applyResultStyle(false);
                return;
            }

            int quantity = Integer.parseInt(qtyText);
            double currentPrice = Money.textToCentsOrZero(currentPriceField.getText()) / 100.0;
            double dividends = Money.textToCentsOrZero(dividendsField.getText()) / 100.0;

            if (purchasePrice == 0 || quantity == 0) {
                resultLabel.setText("Preencha preço de compra e quantidade");
                applyResultStyle(false);
                return;
            }

            if (currentPrice == 0) {
                currentPrice = purchasePrice;
            }

            double variation = priceVariationSlider.getValue() / 100.0;
            double adjustedPrice = currentPrice * (1 + variation);

            double valorInvestido = purchasePrice * quantity;
            double valorAtual = adjustedPrice * quantity;
            double lucro = valorAtual - valorInvestido;
            double rentabilidade = (lucro / valorInvestido) * 100;
            double lucroTotal = (valorAtual + dividends) - valorInvestido;

            resultLabel.setText(String.format("R$ %.2f", valorAtual).replace('.', ','));
            resultSubLabel.setText(String.format("Investido: R$ %.2f | Lucro: R$ %.2f (%.2f%%)",
                    valorInvestido, lucroTotal, rentabilidade).replace('.', ','));

            applyResultStyle(lucroTotal >= 0);

            projectionChart.getData().clear();
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName("Ação");
            series.getData().add(new XYChart.Data<>(0, valorInvestido));
            series.getData().add(new XYChart.Data<>(1, valorAtual + dividends));
            projectionChart.getData().add(series);

        } catch (NumberFormatException e) {
            resultLabel.setText("Quantidade inválida");
            resultSubLabel.setText("Informe apenas números inteiros no campo Quantidade.");
            applyResultStyle(false);
        } catch (Exception e) {
            resultLabel.setText("Verifique os campos");
            resultSubLabel.setText("Preencha o ticker, preço de compra e quantidade.");
            applyResultStyle(false);
        }
    }

    private void updateChartMonths(double capital, double monthlyRate, int months, String title) {
        projectionChart.getData().clear();

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(title);

        for (int month = 0; month <= months; month++) {
            double value = capital * Math.pow(1 + monthlyRate, month);
            series.getData().add(new XYChart.Data<>(month, value));
        }

        projectionChart.getData().add(series);
    }

    private void applyResultStyle(boolean positive) {
        resultLabel.getStyleClass().removeAll("pos", "neg");
        resultLabel.getStyleClass().add(positive ? "pos" : "neg");
    }

    private void applyRatesStatusStyle(String... classes) {
        ratesStatusLabel.getStyleClass().removeAll("text-helper", "text-xs", "state-warning", "state-positive");
        ratesStatusLabel.getStyleClass().addAll(java.util.List.of(classes));
    }
}

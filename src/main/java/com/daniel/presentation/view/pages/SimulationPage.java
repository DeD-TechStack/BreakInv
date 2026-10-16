package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Enums.InvestmentTypeEnum;
import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Kind;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.core.service.SimulationCalculator;
import com.daniel.core.service.SimulationCalculator.Projection;
import com.daniel.core.util.Money;
import com.daniel.infrastructure.api.BcbClient;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.ScenarioCardController;
import com.daniel.presentation.viewmodel.SimulationForm;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Simulation page (RF13 + saved scenarios extension, RN12).
 *
 * <p>Projections are estimates computed by {@link SimulationCalculator} from a complete
 * {@link SimulationParameters}; they never touch the real portfolio. Benchmark rates are
 * a frozen {@link RateSnapshot} with origin/date: a reopened scenario uses its own frozen
 * rates, and the only way to change them is the explicit "Atualizar taxas" button. Ticker
 * quotes are fetched only on explicit request, and late responses are discarded when a
 * scenario was loaded in the meantime (epoch guard). Price and dividends are editable so
 * the page works offline with manual values.</p>
 *
 * <p>Scenario identity (which saved row "Salvar" updates) is owned by
 * {@link ScenarioCardController}: every async save/reload/duplicate captures the form
 * epoch and is applied only if the form was not replaced meanwhile, and "Novo" is
 * disabled while a write is in flight.</p>
 */
public final class SimulationPage implements Page {

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);
    private final ScenarioCardController scenarioCtrl;

    private enum RentabilityMode {
        FIXED_RATE("Taxa Fixa (% a.a.)"),
        BENCHMARK_PERCENT("% do Benchmark"),
        HYBRID("Híbrida (Índice + Taxa)");

        private final String display;
        RentabilityMode(String display) { this.display = display; }
        public String getDisplay() { return display; }
    }

    private static final String DEFAULT_RATES_ORIGIN = "estimativa padrão";
    private static final String BCB_ORIGIN = "BCB/SGS";

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
    private final Button lookupTickerBtn = new Button("Buscar cotação");
    private final Label priceOriginLabel = new Label();
    private final Slider priceVariationSlider = new Slider(-20, 20, 0);
    private final Label sliderValueLabel = new Label("0%");

    private final Label resultLabel = new Label("—");
    private final Label resultSubLabel = new Label("Preencha os campos e clique em Calcular");
    private final LineChart<Number, Number> projectionChart;

    private final Label ratesStatusLabel = new Label();
    private final Button refreshRatesBtn = new Button("Atualizar taxas (BCB)");

    // Scenario card
    private final ComboBox<SimulationScenario> scenarioCombo = new ComboBox<>();
    private final TextField scenarioNameField = new TextField();
    private final Button newScenarioBtn = new Button("Novo");
    private final Button saveScenarioBtn = new Button("Salvar");
    private final Button saveAsScenarioBtn = new Button("Salvar como novo");
    private final Button duplicateScenarioBtn = new Button("Duplicar");
    private final Button deleteScenarioBtn = new Button("Excluir");
    private final Label scenarioStatusLabel = new Label();
    private final Label scenarioEmptyLabel = new Label("Nenhum cenário salvo. Calcule uma simulação, dê um nome e clique em \"Salvar\".");
    private boolean suppressScenarioEvents = false;

    /** Frozen benchmark rates used by every calculation until explicitly refreshed or a scenario is loaded. */
    private RateSnapshot frozenRates = SimulationForm.snapshot(0.135, 0.15, 0.045, DEFAULT_RATES_ORIGIN, null);
    /** Provenance of the stock price/dividends currently in the fields. */
    private String priceOrigin = "manual";
    private String priceReferenceDate = null;

    private InvestmentTypeEnum currentType = InvestmentTypeEnum.PREFIXADO;
    private ToggleButton prefixadoBtn, posfixadoBtn, hibridoBtn, acaoBtn;

    // Visibility-controlled VBoxes
    private VBox fixedRateSection;
    private VBox benchmarkSection;
    private VBox hybridSection;
    private VBox stockSection;
    private VBox rentabilityModeSection;

    public SimulationPage() {
        this(SavedAnalysisRepository.forApplicationDatabase());
    }

    public SimulationPage(SavedAnalysisRepository scenarios) {
        this(scenarios, UiExecutor.get(), Platform::runLater);
    }

    /** Test seam: the executors the scenario card controller uses (worker for persistence, ui for callbacks). */
    SimulationPage(SavedAnalysisRepository scenarios, java.util.concurrent.Executor worker, java.util.concurrent.Executor ui) {
        this.scenarioCtrl = new ScenarioCardController(scenarios, worker, ui, new ScenarioCardView());
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Simulação",
                "Projete o rendimento dos seus investimentos — estimativa, sem efeito na carteira");

        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Meses");
        yAxis.setLabel("Valor (R$)");
        projectionChart = new LineChart<>(xAxis, yAxis);
        projectionChart.setTitle(null);
        projectionChart.setMinHeight(380);
        projectionChart.setCreateSymbols(false);

        VBox scenarioCard = buildScenarioCard();
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

        root.getChildren().addAll(header, scenarioCard, typeSelector, baseParamsCard,
                rentabilityCard, stockCard, resultCard, chartCard);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");

        updateInputsVisibility();
        updateRentabilityInputs();
        updateRatesStatus(null);
        updatePriceOriginLabel();
        updateScenarioStatus();
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        // No network on show: rates stay frozen until the user asks. Only the scenario list is refreshed.
        scenarioCtrl.reload();
    }

    private SimulationScenario loadedScenario() {
        return scenarioCtrl.state().loaded();
    }

    private long marketWriteEpoch;

    private long formEpoch() {
        return scenarioCtrl.state().epoch() + marketWriteEpoch;
    }

    // =====================================================================
    // Rates (frozen snapshot; refreshed only on explicit request)
    // =====================================================================

    private void fetchRealRates() {
        if (scenarioCtrl.isBusy()) return;
        final long epoch = formEpoch();
        ratesStatusLabel.setText("Buscando taxas no BCB...");
        applyRatesStatusStyle("text-helper");
        refreshRatesBtn.setDisable(true);

        CompletableFuture.supplyAsync(() -> {
            var cdi = BcbClient.fetchCdi();
            var selic = BcbClient.fetchSelic();
            var ipca = BcbClient.fetchIpca();
            return new double[]{cdi.orElse(-1.0), selic.orElse(-1.0), ipca.orElse(-1.0)};
        }).whenComplete((rates, err) -> Platform.runLater(() -> {
            refreshRatesBtn.setDisable(scenarioCtrl.isBusy());
            if (formEpoch() != epoch) { // a scenario was loaded or "Novo" clicked meanwhile: keep the current frozen rates
                ToastHost.showInfo("Taxas do BCB recebidas, mas o formulário mudou durante a consulta — as taxas congeladas atuais foram mantidas. Clique em \"Atualizar taxas\" de novo se quiser aplicá-las.");
                return;
            }
            if (err != null || rates[0] <= 0 || rates[1] <= 0 || rates[2] <= 0) {
                ToastHost.showWarn("Não foi possível obter todas as taxas do BCB — mantendo as taxas congeladas atuais.");
                updateRatesStatus("falha na consulta");
                return;
            }
            frozenRates = SimulationForm.snapshot(rates[0], rates[1], rates[2], BCB_ORIGIN, LocalDate.now().toString());
            updateRatesStatus(null);
            SimulationScenario loaded = loadedScenario();
            if (loaded != null && loaded.parameters().kind() == Kind.BENCHMARK_PERCENT) {
                ToastHost.showInfo("Taxas atualizadas: o cenário carregado passa a usar premissas novas até ser salvo.");
                scenarioStatusLabel.setText("Cenário \"" + loaded.name() + "\" com premissas alteradas (taxas "
                        + frozenRates.origin() + " " + nvl(frozenRates.referenceDate()) + ") — clique em Salvar para manter ou recarregue o cenário para descartar.");
            }
        }));
    }

    private void updateRatesStatus(String failure) {
        String base = String.format("CDI %s | SELIC %s | IPCA %s — origem: %s%s",
                pct(frozenRates.cdi()), pct(frozenRates.selic()), pct(frozenRates.ipca()),
                frozenRates.origin(),
                frozenRates.referenceDate() == null ? "" : " (" + frozenRates.referenceDate() + ")");
        if (failure != null) {
            ratesStatusLabel.setText(base + " — " + failure);
            applyRatesStatusStyle("text-xs", "state-warning");
        } else if (DEFAULT_RATES_ORIGIN.equals(frozenRates.origin())) {
            ratesStatusLabel.setText(base);
            applyRatesStatusStyle("text-xs", "state-warning");
        } else {
            ratesStatusLabel.setText(base);
            applyRatesStatusStyle("text-xs", "state-positive");
        }
    }

    private static String pct(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%";
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    // =====================================================================
    // Scenario card
    // =====================================================================

    private VBox buildScenarioCard() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("CENÁRIOS SALVOS");
        title.getStyleClass().add("card-title");

        scenarioCombo.setMaxWidth(Double.MAX_VALUE);
        scenarioCombo.setPromptText("Selecione um cenário salvo");
        scenarioCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(SimulationScenario item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        scenarioCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(SimulationScenario item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        scenarioCombo.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (suppressScenarioEvents || n == null) return;
            if (!scenarioCtrl.load(n)) {
                // busy: a write is in flight; keep the combo on the loaded scenario
                suppressScenarioEvents = true;
                try { scenarioCombo.getSelectionModel().select(loadedScenario()); } finally { suppressScenarioEvents = false; }
            }
        });
        HBox.setHgrow(scenarioCombo, Priority.ALWAYS);

        newScenarioBtn.getStyleClass().add("ghost-btn");
        newScenarioBtn.setOnAction(e -> scenarioCtrl.startNew());
        duplicateScenarioBtn.getStyleClass().add("ghost-btn");
        duplicateScenarioBtn.setOnAction(e -> scenarioCtrl.duplicate());
        deleteScenarioBtn.getStyleClass().add("danger-btn");
        deleteScenarioBtn.setOnAction(e -> deleteScenario());

        HBox row1 = new HBox(8, scenarioCombo, newScenarioBtn, duplicateScenarioBtn, deleteScenarioBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label("Nome:");
        nameLabel.getStyleClass().add("form-label");
        scenarioNameField.setPromptText("Ex.: CDB 110% CDI por 2 anos");
        scenarioNameField.setOnAction(e -> saveScenario(false));
        HBox.setHgrow(scenarioNameField, Priority.ALWAYS);
        saveScenarioBtn.getStyleClass().add("button");
        saveScenarioBtn.setOnAction(e -> saveScenario(false));
        saveAsScenarioBtn.getStyleClass().add("secondary-btn");
        saveAsScenarioBtn.setOnAction(e -> saveScenario(true));
        HBox row2 = new HBox(8, nameLabel, scenarioNameField, saveScenarioBtn, saveAsScenarioBtn);
        row2.setAlignment(Pos.CENTER_LEFT);

        scenarioStatusLabel.getStyleClass().add("text-helper");
        scenarioStatusLabel.setWrapText(true);
        scenarioEmptyLabel.getStyleClass().add("text-helper");
        scenarioEmptyLabel.setWrapText(true);

        box.getChildren().addAll(title, row1, row2, scenarioStatusLabel, scenarioEmptyLabel);
        return box;
    }

    private void updateScenarioStatus() {
        boolean hasAny = !scenarioCombo.getItems().isEmpty();
        scenarioEmptyLabel.setVisible(!hasAny);
        scenarioEmptyLabel.setManaged(!hasAny);
        boolean loaded = loadedScenario() != null;
        duplicateScenarioBtn.setDisable(!loaded);
        deleteScenarioBtn.setDisable(!loaded);
        saveAsScenarioBtn.setDisable(!loaded);
        if (!loaded) {
            scenarioStatusLabel.setText("Cenário novo (não salvo). São gravados os parâmetros e as taxas congeladas; o resultado é recalculado ao reabrir.");
        } else {
            SimulationScenario s = loadedScenario();
            SimulationParameters p = s.parameters();
            String rates = p.rates() == null ? "" : " — taxas congeladas: " + p.rates().origin() + " " + nvl(p.rates().referenceDate());
            String price = p.kind() == Kind.STOCK ? " — preço: " + p.priceOrigin() + " " + nvl(p.priceReferenceDate()) : "";
            scenarioStatusLabel.setText("Cenário \"" + s.name() + "\" carregado (versão de cálculo "
                    + p.calculationVersion() + ")" + rates + price + ".");
        }
    }

    private void setScenarioControlsDisabled(boolean disabled) {
        // Responses requested before this write must not mutate its captured parameters,
        // even if they arrive after the write completes. Form identity stays unchanged.
        if (disabled) marketWriteEpoch++;
        boolean loaded = loadedScenario() != null;
        scenarioCombo.setDisable(disabled);
        newScenarioBtn.setDisable(disabled);
        saveScenarioBtn.setDisable(disabled);
        saveAsScenarioBtn.setDisable(disabled || !loaded);
        duplicateScenarioBtn.setDisable(disabled || !loaded);
        deleteScenarioBtn.setDisable(disabled || !loaded);
        // The write in flight captured the form as it was: lock name and parameters so the
        // response (renderSaved) never overwrites an edit made meanwhile.
        scenarioNameField.setDisable(disabled);
        initialValueField.setDisable(disabled);
        monthsCombo.setDisable(disabled);
        rentabilityModeCombo.setDisable(disabled);
        fixedRateField.setDisable(disabled);
        benchmarkCombo.setDisable(disabled);
        benchmarkPercentField.setDisable(disabled);
        indexRateField.setDisable(disabled);
        hybridFixedField.setDisable(disabled);
        tickerField.setDisable(disabled);
        purchasePriceField.setDisable(disabled);
        quantityField.setDisable(disabled);
        currentPriceField.setDisable(disabled);
        dividendsField.setDisable(disabled);
        priceVariationSlider.setDisable(disabled);
        for (ToggleButton button : new ToggleButton[]{prefixadoBtn, posfixadoBtn, hibridoBtn, acaoBtn}) {
            button.setDisable(disabled);
        }
        refreshRatesBtn.setDisable(disabled);
        lookupTickerBtn.setDisable(disabled);
    }

    /** JavaFX side of the scenario card; all methods run on the FX thread. */
    private final class ScenarioCardView implements ScenarioCardController.View {
        @Override public void renderList(List<SimulationScenario> list, SimulationScenario selected) {
            suppressScenarioEvents = true;
            try {
                scenarioCombo.getItems().setAll(list);
                scenarioCombo.getSelectionModel().select(selected);
            } finally {
                suppressScenarioEvents = false;
            }
            updateScenarioStatus();
        }
        @Override public void renderLoaded(SimulationScenario scenario) { applyScenarioToForm(scenario); }
        @Override public void renderSaved(SimulationScenario saved) {
            scenarioNameField.setText(saved.name());
            updateScenarioStatus();
            showProjection(saved.parameters());
        }
        @Override public void renderNew() { clearFormForNewScenario(); }
        @Override public void setBusy(boolean busy) { setScenarioControlsDisabled(busy); }
        @Override public void warn(String message) { ToastHost.showWarn(message); }
        @Override public void error(String message) { ToastHost.showError(message); }
        @Override public void success(String message) { ToastHost.showSuccess(message); }
        @Override public void info(String message) { ToastHost.showInfo(message); }
    }

    private void clearFormForNewScenario() {
        suppressScenarioEvents = true;
        try {
            scenarioCombo.getSelectionModel().clearSelection();
        } finally {
            suppressScenarioEvents = false;
        }
        scenarioNameField.clear();
        resultLabel.setText("—");
        resultSubLabel.setText("Preencha os campos e clique em Calcular");
        projectionChart.getData().clear();
        updateScenarioStatus();
        scenarioNameField.requestFocus();
    }

    /** Applies a saved scenario to the form, freezes its rates/prices and replays the projection. */
    private void applyScenarioToForm(SimulationScenario scenario) {
        SimulationParameters p = scenario.parameters();
        scenarioNameField.setText(scenario.name());

        switch (p.kind()) {
            case FIXED_RATE -> {
                prefixadoBtn.setSelected(true);
                rentabilityModeCombo.setValue(RentabilityMode.FIXED_RATE);
                initialValueField.setText(Money.centsToText(p.capitalCents()));
                selectMonths(p.months());
                fixedRateField.setText(pctText(p.annualRate()));
            }
            case BENCHMARK_PERCENT -> {
                posfixadoBtn.setSelected(true);
                rentabilityModeCombo.setValue(RentabilityMode.BENCHMARK_PERCENT);
                initialValueField.setText(Money.centsToText(p.capitalCents()));
                selectMonths(p.months());
                benchmarkCombo.setValue(p.benchmark().name());
                benchmarkPercentField.setText(pctText(p.benchmarkPercent()));
                frozenRates = p.rates();
                updateRatesStatus(null);
            }
            case HYBRID -> {
                hibridoBtn.setSelected(true);
                rentabilityModeCombo.setValue(RentabilityMode.HYBRID);
                initialValueField.setText(Money.centsToText(p.capitalCents()));
                selectMonths(p.months());
                hybridFixedField.setText(pctText(p.annualRate()));
                indexRateField.setText(pctText(p.indexRate()));
            }
            case STOCK -> {
                acaoBtn.setSelected(true);
                tickerField.setText(p.ticker() == null ? "" : p.ticker());
                purchasePriceField.setText(Money.centsToText(p.purchasePriceCents()));
                quantityField.setText(Integer.toString(p.quantity()));
                currentPriceField.setText(p.currentPriceCents() > 0 ? Money.centsToText(p.currentPriceCents()) : "");
                dividendsField.setText(p.dividendPerShareCents() > 0 ? Money.centsToText(p.dividendPerShareCents()) : "");
                priceOrigin = p.priceOrigin();
                priceReferenceDate = p.priceReferenceDate();
                updatePriceOriginLabel();
                priceVariationSlider.setValue(p.priceVariationPercent().doubleValue());
            }
        }
        updateScenarioStatus();
        showProjection(p);
    }

    private void selectMonths(int months) {
        if (!monthsCombo.getItems().contains(months)) monthsCombo.getItems().add(months);
        monthsCombo.setValue(months);
    }

    private static String pctText(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private void saveScenario(boolean asNew) {
        if (scenarioCtrl.isBusy()) return; // Enter or a second click while the previous write has not answered
        SimulationParameters params;
        try {
            params = buildParameters();
        } catch (IllegalArgumentException ex) {
            resultLabel.setText("Verifique os campos");
            resultSubLabel.setText(ex.getMessage());
            applyResultStyle(false);
            ToastHost.showWarn(ex.getMessage());
            return;
        }
        String name = scenarioNameField.getText() == null ? "" : scenarioNameField.getText().trim();
        if (name.isEmpty()) {
            ToastHost.showWarn("Informe um nome para o cenário.");
            scenarioNameField.requestFocus();
            return;
        }
        scenarioCtrl.save(name, params, asNew);
    }

    private void deleteScenario() {
        SimulationScenario target = loadedScenario();
        if (target == null || scenarioCtrl.isBusy()) return;
        if (!Dialogs.confirm("Excluir cenário", "Excluir o cenário \"" + target.name() + "\"?",
                "Apenas a simulação salva é removida. Nenhum investimento, caixa ou histórico é alterado.")) return;
        scenarioCtrl.delete();
    }

    // =====================================================================
    // Form cards (existing layout)
    // =====================================================================

    private HBox buildTypeSelector() {
        HBox box = new HBox(12);
        box.getStyleClass().add("toolbar");
        box.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("Tipo:");
        label.getStyleClass().add("form-label");

        ToggleGroup group = new ToggleGroup();

        prefixadoBtn = new ToggleButton("Prefixado");
        posfixadoBtn = new ToggleButton("Pós-fixado");
        hibridoBtn   = new ToggleButton("Híbrido");
        acaoBtn      = new ToggleButton("Ação / FII");

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
        fixedRateField.setPromptText("12,50");
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

        refreshRatesBtn.getStyleClass().add("ghost-btn");
        refreshRatesBtn.setOnAction(e -> fetchRealRates());
        ratesStatusLabel.setWrapText(true);
        HBox.setHgrow(ratesStatusLabel, Priority.ALWAYS);
        HBox ratesRow = new HBox(10, ratesStatusLabel, refreshRatesBtn);
        ratesRow.setAlignment(Pos.CENTER_LEFT);
        VBox ratesPanel = new VBox(ratesRow);
        ratesPanel.getStyleClass().add("panel");
        benchmarkSection = new VBox(8, benchRow, ratesPanel);

        // Hybrid section
        Label hybridLabel = new Label("Taxa Fixa (% a.a.):");
        hybridLabel.getStyleClass().add("form-label");
        hybridFixedField.setPromptText("5,0");

        Label indexLabel = new Label("Taxa do Índice (% a.a.):");
        indexLabel.getStyleClass().add("form-label");
        indexRateField.setPromptText("4,5 (IPCA estimado)");

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
        tickerField.setOnAction(e -> lookupTicker());

        lookupTickerBtn.getStyleClass().add("ghost-btn");
        lookupTickerBtn.setOnAction(e -> lookupTicker());

        Label purchasePriceLabel = new Label("Preço de Compra:");
        purchasePriceLabel.getStyleClass().add("form-label");
        purchasePriceField.setPromptText("R$ 35,50");
        purchasePriceField.setTextFormatter(Money.currencyFormatterEditable());

        Label quantityLabel = new Label("Quantidade:");
        quantityLabel.getStyleClass().add("form-label");
        quantityField.setPromptText("100");

        Label currentPriceLabel = new Label("Preço Atual (manual ou cotação):");
        currentPriceLabel.getStyleClass().add("form-label");
        currentPriceField.setPromptText("Vazio = usar preço de compra");
        currentPriceField.setTextFormatter(Money.currencyFormatterEditable());
        currentPriceField.textProperty().addListener((obs, o, n) -> { if (currentPriceField.isFocused()) markManualPrice(); });

        Label dividendsLabel = new Label("Dividendos por cota/ação (anual):");
        dividendsLabel.getStyleClass().add("form-label");
        dividendsField.setPromptText("R$ 0,00");
        dividendsField.setTextFormatter(Money.currencyFormatterEditable());
        dividendsField.textProperty().addListener((obs, o, n) -> { if (dividendsField.isFocused()) markManualPrice(); });

        priceOriginLabel.getStyleClass().add("text-helper");
        priceOriginLabel.setWrapText(true);

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
                calculateQuietly();
            }
        });
        sliderValueLabel.getStyleClass().addAll("text-bold", "state-positive");
        HBox sliderRow = new HBox(12, priceVariationSlider, sliderValueLabel);
        HBox.setHgrow(priceVariationSlider, Priority.ALWAYS);
        sliderRow.setAlignment(Pos.CENTER_LEFT);

        HBox row1 = new HBox(12);
        HBox tickerRow = new HBox(6, tickerField, lookupTickerBtn);
        HBox.setHgrow(tickerField, Priority.ALWAYS);
        VBox tickerBox = new VBox(6, tickerLabel, tickerRow);
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

        box.getChildren().addAll(title, row1, row2, priceOriginLabel, sliderPanel, calculateBtn);

        stockSection = box;
        return box;
    }

    private VBox buildResultCard() {
        VBox box = new VBox(8);
        box.getStyleClass().add("result-card");

        Label title = new Label("RESULTADO DA SIMULAÇÃO (ESTIMATIVA)");
        title.getStyleClass().add("kpi-label");

        resultLabel.getStyleClass().addAll("kpi-value", "num");
        resultSubLabel.getStyleClass().add("kpi-sub");
        resultSubLabel.setWrapText(true);

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

    // =====================================================================
    // Stock quote (explicit only; late responses never overwrite frozen data)
    // =====================================================================

    private void markManualPrice() {
        priceOrigin = "manual";
        priceReferenceDate = null;
        updatePriceOriginLabel();
    }

    private void updatePriceOriginLabel() {
        priceOriginLabel.setText("Preço e dividendos: " + priceOrigin
                + (priceReferenceDate == null ? "" : " (" + priceReferenceDate + ")")
                + " — edite os campos para usar valores manuais (funciona sem rede).");
    }

    private void lookupTicker() {
        if (scenarioCtrl.isBusy()) return;
        String ticker = tickerField.getText() == null ? "" : tickerField.getText().trim().toUpperCase();
        if (ticker.length() < 4) {
            ToastHost.showWarn("Informe um ticker com pelo menos 4 caracteres.");
            return;
        }
        final long epoch = formEpoch();
        lookupTickerBtn.setDisable(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return BrapiClient.fetchStockData(ticker);
            } catch (Exception e) {
                return null;
            }
        }).whenComplete((data, err) -> Platform.runLater(() -> {
            lookupTickerBtn.setDisable(scenarioCtrl.isBusy());
            if (formEpoch() != epoch) { // a scenario was loaded or "Novo" clicked meanwhile: keep the current prices
                ToastHost.showInfo("Cotação de " + ticker + " recebida, mas o formulário mudou durante a consulta — valores atuais mantidos. Clique em \"Buscar cotação\" de novo se quiser aplicá-la.");
                return;
            }
            if (!ticker.equalsIgnoreCase(tickerField.getText() == null ? "" : tickerField.getText().trim())) return;
            if (err != null || data == null || !data.isValid()) {
                ToastHost.showWarn("Cotação indisponível para " + ticker + " — informe preço e dividendos manualmente.");
                return;
            }
            currentPriceField.setText(Money.centsToText(Math.round(data.regularMarketPrice() * 100)));
            double estimatedAnnualDividendPerShare = data.regularMarketPrice() * (data.dividendYield() / 100.0);
            dividendsField.setText(Money.centsToText(Math.round(estimatedAnnualDividendPerShare * 100)));
            priceOrigin = "Brapi";
            priceReferenceDate = LocalDate.now().toString();
            updatePriceOriginLabel();
            ToastHost.showInfo("Cotação de " + ticker + " carregada (" + priceReferenceDate + ").");
        }));
    }

    // =====================================================================
    // Calculation (pure, from parameters)
    // =====================================================================

    private SimulationParameters buildParameters() {
        if (currentType == InvestmentTypeEnum.ACAO) {
            return SimulationForm.stock(tickerField.getText(), purchasePriceField.getText(), quantityField.getText(),
                    currentPriceField.getText(), dividendsField.getText(), priceVariationSlider.getValue(),
                    priceOrigin, priceReferenceDate);
        }
        Integer months = monthsCombo.getValue();
        int m = months == null ? 0 : months;
        return switch (currentRentabilityMode) {
            case FIXED_RATE -> SimulationForm.fixedRate(initialValueField.getText(), m, fixedRateField.getText());
            case BENCHMARK_PERCENT -> SimulationForm.benchmark(initialValueField.getText(), m,
                    benchmarkCombo.getValue(), benchmarkPercentField.getText(), frozenRates);
            case HYBRID -> SimulationForm.hybrid(initialValueField.getText(), m,
                    hybridFixedField.getText(), indexRateField.getText());
        };
    }

    private void calculate() {
        try {
            showProjection(buildParameters());
        } catch (IllegalArgumentException | ArithmeticException e) {
            resultLabel.setText("Verifique os campos");
            resultSubLabel.setText(e.getMessage());
            applyResultStyle(false);
        }
    }

    /** Slider-driven recalculation: silently ignores incomplete forms. */
    private void calculateQuietly() {
        try {
            showProjection(buildParameters());
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            // user is still filling the form
        }
    }

    private void showProjection(SimulationParameters p) {
        Projection r;
        try {
            r = SimulationCalculator.project(p);
        } catch (ArithmeticException e) {
            resultLabel.setText("Fora do intervalo calculável");
            resultSubLabel.setText(e.getMessage());
            applyResultStyle(false);
            return;
        }
        if (p.kind() == Kind.STOCK) {
            resultLabel.setText(Money.centsToText(r.finalCents()));
            resultSubLabel.setText(String.format("Investido: %s | Dividendos (%d × %s): %s | Lucro total: %s (%.2f%%)",
                    Money.centsToText(r.investedCents()), p.quantity(), Money.centsToText(p.dividendPerShareCents()),
                    Money.centsToText(r.dividendsCents()), Money.centsToText(r.profitCents()), r.returnPercent())
                    .replace('.', ','));
            applyResultStyle(r.profitCents() >= 0);
            projectionChart.getData().clear();
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName("Ação");
            series.getData().add(new XYChart.Data<>(0, r.seriesCents().get(0) / 100.0));
            series.getData().add(new XYChart.Data<>(1, r.seriesCents().get(1) / 100.0));
            projectionChart.getData().add(series);
        } else {
            resultLabel.setText(Money.centsToText(r.finalCents()));
            String ratesNote = p.kind() == Kind.BENCHMARK_PERCENT
                    ? " | taxas: " + p.rates().origin() + " " + nvl(p.rates().referenceDate())
                    : "";
            resultSubLabel.setText(String.format("Lucro: %s (%.1f%%) em %d meses — taxa efetiva %.2f%% a.a.%s",
                    Money.centsToText(r.profitCents()), r.returnPercent(), p.months(),
                    r.effectiveAnnualRate() * 100, ratesNote).replace('.', ','));
            applyResultStyle(true);
            updateChartMonths(r.seriesCents(), "Renda Fixa");
        }
    }

    private void updateChartMonths(List<Long> seriesCents, String title) {
        projectionChart.getData().clear();

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(title);

        for (int month = 0; month < seriesCents.size(); month++) {
            series.getData().add(new XYChart.Data<>(month, seriesCents.get(month) / 100.0));
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

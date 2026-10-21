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
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.ChartCrosshair;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.view.util.Motion;
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
 *
 * <p>Layout (direction A): parameters and result are neighbours — side by side when wide,
 * stacked otherwise — the result is a hero that says it is an estimate, one "Calcular" serves
 * every type, and saved scenarios are a discreet panel after the work area. Re-flowing moves
 * the same nodes; nothing typed is lost.</p>
 */
public final class SimulationPage implements ResponsivePage {

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(16);
    private final ScenarioCardController scenarioCtrl;
    private AdaptivePair workPair;
    private Panel scenarioPanel;
    private Panel chartPanel;
    private final Button calculateBtn = new Button("Calcular");

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

    private static final String RESULT_IDLE = "Preencha os parâmetros e clique em Calcular — estimativa, sem efeito na carteira";
    private final HeroValue resultHero = new HeroValue("Valor final estimado");
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
        projectionChart.setMinHeight(280);
        projectionChart.setCreateSymbols(false);

        scenarioPanel = buildScenarioCard();
        HBox typeSelector = buildTypeSelector();
        VBox baseParams = buildBaseParamsCard();
        VBox rentability = buildRentabilityCard();
        VBox stock = buildStockCard();

        calculateBtn.getStyleClass().add("primary-btn");
        calculateBtn.setMaxWidth(Double.MAX_VALUE);
        calculateBtn.setTooltip(new Tooltip("Projeta o resultado com os parâmetros informados (Enter também calcula)"));
        calculateBtn.setOnAction(e -> calculate());
        Panel paramsPanel = new Panel("Parâmetros", typeSelector, baseParams, rentability, stock, calculateBtn);

        resultHero.setContext(RESULT_IDLE);
        javafx.scene.layout.StackPane projWrapper = ChartCrosshair.installNumeric(projectionChart,
                month -> "Mês " + month,
                y -> "R$ " + String.format("%.2f", y).replace('.', ','));
        VBox.setVgrow(projWrapper, Priority.ALWAYS);
        var noProjection = javafx.beans.binding.Bindings.isEmpty(projectionChart.getData());
        projWrapper.visibleProperty().bind(noProjection.not());
        projWrapper.managedProperty().bind(projWrapper.visibleProperty());
        Label guidance = new Label("A curva aparece após calcular, com valores em reais e período em meses.");
        guidance.setWrapText(true);
        guidance.getStyleClass().addAll("text-secondary", "projection-guidance");
        guidance.visibleProperty().bind(noProjection);
        guidance.managedProperty().bind(guidance.visibleProperty());
        chartPanel = new Panel("Resultado da simulação", resultHero, guidance, projWrapper);
        chartPanel.getStyleClass().add("simulation-result");
        VBox resultColumn = new VBox(16, chartPanel);
        VBox.setVgrow(chartPanel, Priority.ALWAYS);

        workPair = new AdaptivePair(paramsPanel, resultColumn, Breakpoint.MEDIUM, 0.44, 16);

        root.getChildren().addAll(header, workPair, scenarioPanel);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");

        updateInputsVisibility();
        updateRentabilityInputs();
        updateRatesStatus(null);
        updatePriceOriginLabel();
        updateScenarioStatus();
        onBreakpoint(Breakpoint.WIDE);
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

    @Override
    public void onBreakpoint(Breakpoint bp) {
        workPair.onBreakpoint(bp);
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
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

    private Panel buildScenarioCard() {
        scenarioCombo.setMaxWidth(Double.MAX_VALUE);
        scenarioCombo.setPromptText("Selecione um cenário salvo");
        scenarioCombo.setAccessibleText("Cenário salvo");
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
        newScenarioBtn.setTooltip(new Tooltip("Limpa o nome e o resultado para começar um cenário novo; os parâmetros ficam"));
        newScenarioBtn.setOnAction(e -> scenarioCtrl.startNew());
        duplicateScenarioBtn.getStyleClass().add("ghost-btn");
        duplicateScenarioBtn.setTooltip(new Tooltip("Grava uma cópia do cenário carregado"));
        duplicateScenarioBtn.setOnAction(e -> scenarioCtrl.duplicate());
        deleteScenarioBtn.getStyleClass().add("danger-btn");
        deleteScenarioBtn.setTooltip(new Tooltip("Exclui o cenário carregado; a carteira não muda"));
        deleteScenarioBtn.setOnAction(e -> deleteScenario());

        FlowPane listActions = new FlowPane(8, 8, newScenarioBtn, duplicateScenarioBtn, deleteScenarioBtn);
        listActions.setAlignment(Pos.CENTER_LEFT);
        VBox row1 = new VBox(8, scenarioCombo, listActions);

        Label nameLabel = new Label("Nome");
        nameLabel.getStyleClass().add("form-label");
        nameLabel.setLabelFor(scenarioNameField);
        scenarioNameField.setPromptText("Ex.: CDB 110% CDI por 2 anos");
        scenarioNameField.setAccessibleText("Nome do cenário");
        scenarioNameField.setOnAction(e -> saveScenario(false));
        HBox.setHgrow(scenarioNameField, Priority.ALWAYS);
        saveScenarioBtn.getStyleClass().add("button");
        saveScenarioBtn.setTooltip(new Tooltip("Grava os parâmetros e as taxas congeladas com este nome"));
        saveScenarioBtn.setOnAction(e -> saveScenario(false));
        saveAsScenarioBtn.getStyleClass().add("secondary-btn");
        saveAsScenarioBtn.setTooltip(new Tooltip("Grava como um cenário novo, mantendo o carregado"));
        saveAsScenarioBtn.setOnAction(e -> saveScenario(true));
        FlowPane saveActions = new FlowPane(8, 8, saveScenarioBtn, saveAsScenarioBtn);
        saveActions.setAlignment(Pos.CENTER_LEFT);
        VBox row2 = new VBox(6, nameLabel, scenarioNameField, saveActions);

        scenarioStatusLabel.getStyleClass().add("text-helper");
        scenarioStatusLabel.setWrapText(true);
        scenarioEmptyLabel.getStyleClass().add("text-helper");
        scenarioEmptyLabel.setWrapText(true);

        return new Panel("Cenários salvos", row1, row2, scenarioStatusLabel, scenarioEmptyLabel)
                .subtitle("Um cenário guarda os parâmetros e as taxas congeladas; o resultado é recalculado ao reabrir.");
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
            scenarioStatusLabel.setText("Cenário novo, ainda não salvo.");
        } else {
            SimulationScenario s = loadedScenario();
            SimulationParameters p = s.parameters();
            String rates = p.rates() == null ? "" : " — taxas congeladas: " + p.rates().origin() + " " + nvl(p.rates().referenceDate());
            String price = p.kind() == Kind.STOCK ? " — preço: " + p.priceOrigin() + " " + nvl(p.priceReferenceDate()) : "";
            scenarioStatusLabel.setText("Cenário \"" + s.name() + "\" carregado (cálculo v"
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
        resultHero.setValue("—");
        resultHero.setSecondary(null, null);
        resultHero.setContext(RESULT_IDLE);
        applyResultStyle(null);
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
            showResultProblem("Verifique os campos", ex.getMessage());
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
                "Apenas a simulação salva é removida. Nenhum investimento, caixa ou histórico é alterado.", "Excluir")) return;
        scenarioCtrl.delete();
    }

    // =====================================================================
    // Form cards (existing layout)
    // =====================================================================

    private HBox buildTypeSelector() {
        HBox box = new HBox(12);
        box.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("Tipo");
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
        Label initialLabel = new Label("Valor inicial");
        initialLabel.getStyleClass().add("form-label");
        initialLabel.setLabelFor(initialValueField);
        initialValueField.setPromptText("R$ 10.000,00");
        initialValueField.setAccessibleText("Valor inicial");
        initialValueField.setTextFormatter(Money.currencyFormatterEditable());
        Money.applyFormatOnBlur(initialValueField);

        Label monthsLabel = new Label("Período (meses)");
        monthsLabel.getStyleClass().add("form-label");
        monthsLabel.setLabelFor(monthsCombo);
        monthsCombo.getItems().addAll(1, 3, 6, 12, 24, 36, 48, 60, 120);
        monthsCombo.setValue(12);
        monthsCombo.setAccessibleText("Período em meses");

        HBox row = new HBox(12);
        VBox initialBox = new VBox(6, initialLabel, initialValueField);
        VBox monthsBox = new VBox(6, monthsLabel, monthsCombo);
        HBox.setHgrow(initialBox, Priority.ALWAYS);
        HBox.setHgrow(monthsBox, Priority.ALWAYS);
        monthsCombo.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().addAll(initialBox, monthsBox);
        return new VBox(row);
    }

    private VBox buildRentabilityCard() {
        Label modeLabel = new Label("Modalidade de rentabilidade");
        modeLabel.getStyleClass().add("form-label");
        modeLabel.setLabelFor(rentabilityModeCombo);
        rentabilityModeCombo.getItems().addAll(RentabilityMode.values());
        rentabilityModeCombo.setValue(RentabilityMode.FIXED_RATE);
        rentabilityModeCombo.setAccessibleText("Modalidade de rentabilidade");
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
        Label fixedLabel = new Label("Taxa anual (%)");
        fixedLabel.getStyleClass().add("form-label");
        fixedLabel.setLabelFor(fixedRateField);
        fixedRateField.setPromptText("12,50");
        fixedRateField.setAccessibleText("Taxa anual em porcentagem");
        fixedRateField.setOnAction(e -> calculate());
        fixedRateSection = new VBox(6, fixedLabel, fixedRateField);

        // Benchmark section
        Label benchmarkLabel = new Label("Benchmark");
        benchmarkLabel.getStyleClass().add("form-label");
        benchmarkCombo.getItems().addAll("CDI", "SELIC", "IPCA");
        benchmarkCombo.setValue("CDI");
        benchmarkCombo.setMaxWidth(Double.MAX_VALUE);
        benchmarkCombo.setAccessibleText("Benchmark");

        Label benchmarkPercentLabel = new Label("Percentual do benchmark");
        benchmarkPercentLabel.getStyleClass().add("form-label");
        benchmarkPercentField.setPromptText("110 (= 110% do CDI)");
        benchmarkPercentField.setAccessibleText("Percentual do benchmark");
        benchmarkPercentField.setOnAction(e -> calculate());

        HBox benchRow = new HBox(12);
        VBox benchBox = new VBox(6, benchmarkLabel, benchmarkCombo);
        VBox benchPctBox = new VBox(6, benchmarkPercentLabel, benchmarkPercentField);
        HBox.setHgrow(benchBox, Priority.ALWAYS);
        HBox.setHgrow(benchPctBox, Priority.ALWAYS);
        benchRow.getChildren().addAll(benchBox, benchPctBox);

        refreshRatesBtn.getStyleClass().add("ghost-btn");
        refreshRatesBtn.setTooltip(new Tooltip("Consulta CDI, SELIC e IPCA no Banco Central e congela as taxas neste formulário"));
        refreshRatesBtn.setOnAction(e -> fetchRealRates());
        ratesStatusLabel.setWrapText(true);
        VBox ratesPanel = new VBox(8, ratesStatusLabel, refreshRatesBtn);
        ratesPanel.getStyleClass().add("panel");
        benchmarkSection = new VBox(8, benchRow, ratesPanel);

        // Hybrid section
        Label hybridLabel = new Label("Taxa fixa (% a.a.)");
        hybridLabel.getStyleClass().add("form-label");
        hybridFixedField.setPromptText("5,0");
        hybridFixedField.setAccessibleText("Taxa fixa anual em porcentagem");
        hybridFixedField.setOnAction(e -> calculate());

        Label indexLabel = new Label("Taxa do índice (% a.a.)");
        indexLabel.getStyleClass().add("form-label");
        indexRateField.setPromptText("4,5 (IPCA estimado)");
        indexRateField.setAccessibleText("Taxa do índice anual em porcentagem");
        indexRateField.setOnAction(e -> calculate());

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
        return rentabilityModeSection;
    }

    private VBox buildStockCard() {
        VBox box = new VBox(12);

        Label tickerLabel = new Label("Ticker");
        tickerLabel.getStyleClass().add("form-label");
        tickerField.setPromptText("PETR4, VALE3, HGLG11...");
        tickerField.setAccessibleText("Ticker");
        tickerField.setOnAction(e -> lookupTicker());

        lookupTickerBtn.getStyleClass().add("ghost-btn");
        lookupTickerBtn.setTooltip(new Tooltip("Busca preço e dividendos na BRAPI; os campos continuam editáveis"));
        lookupTickerBtn.setOnAction(e -> lookupTicker());

        Label purchasePriceLabel = new Label("Preço de compra");
        purchasePriceLabel.getStyleClass().add("form-label");
        purchasePriceField.setPromptText("R$ 35,50");
        purchasePriceField.setAccessibleText("Preço de compra");
        purchasePriceField.setTextFormatter(Money.currencyFormatterEditable());

        Label quantityLabel = new Label("Quantidade");
        quantityLabel.getStyleClass().add("form-label");
        quantityField.setPromptText("100");
        quantityField.setAccessibleText("Quantidade");

        Label currentPriceLabel = new Label("Preço atual (manual ou cotação)");
        currentPriceLabel.getStyleClass().add("form-label");
        currentPriceField.setPromptText("Vazio = usar preço de compra");
        currentPriceField.setAccessibleText("Preço atual");
        currentPriceField.setTextFormatter(Money.currencyFormatterEditable());
        currentPriceField.textProperty().addListener((obs, o, n) -> { if (currentPriceField.isFocused()) markManualPrice(); });

        Label dividendsLabel = new Label("Dividendos por cota/ação (anual)");
        dividendsLabel.getStyleClass().add("form-label");
        dividendsField.setPromptText("R$ 0,00");
        dividendsField.setAccessibleText("Dividendos anuais por cota ou ação");
        dividendsField.setTextFormatter(Money.currencyFormatterEditable());
        dividendsField.textProperty().addListener((obs, o, n) -> { if (dividendsField.isFocused()) markManualPrice(); });

        priceOriginLabel.getStyleClass().add("text-helper");
        priceOriginLabel.setWrapText(true);

        Label variationLabel = new Label("Variação de preço (%)");
        variationLabel.getStyleClass().add("form-label");
        priceVariationSlider.setAccessibleText("Variação de preço em porcentagem");
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

        HBox tickerRow = new HBox(6, tickerField, lookupTickerBtn);
        HBox.setHgrow(tickerField, Priority.ALWAYS);
        VBox tickerBox = new VBox(6, tickerLabel, tickerRow);
        HBox row1 = new HBox(12);
        VBox priceBox = new VBox(6, purchasePriceLabel, purchasePriceField);
        VBox qtyBox = new VBox(6, quantityLabel, quantityField);
        HBox.setHgrow(priceBox, Priority.ALWAYS);
        HBox.setHgrow(qtyBox, Priority.ALWAYS);
        row1.getChildren().addAll(priceBox, qtyBox);

        HBox row2 = new HBox(12);
        VBox curPriceBox = new VBox(6, currentPriceLabel, currentPriceField);
        VBox divBox = new VBox(6, dividendsLabel, dividendsField);
        HBox.setHgrow(curPriceBox, Priority.ALWAYS);
        HBox.setHgrow(divBox, Priority.ALWAYS);
        row2.getChildren().addAll(curPriceBox, divBox);

        VBox sliderPanel = new VBox(6, variationLabel, sliderRow);
        sliderPanel.getStyleClass().add("panel");

        box.getChildren().addAll(tickerBox, row1, row2, priceOriginLabel, sliderPanel);

        stockSection = box;
        return box;
    }

    /** A form problem shown in the result block itself; the chart is left as it was. */
    private void showResultProblem(String title, String detail) {
        resultHero.setValue(title);
        resultHero.setSecondary(null, null);
        resultHero.setContext(detail);
        applyResultStyle(null);
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
            showResultProblem("Verifique os campos", e.getMessage());
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
            showResultProblem("Fora do intervalo calculável", e.getMessage());
            return;
        }
        Boolean gain = r.profitCents() == 0 ? null : r.profitCents() > 0;
        String tone = gain == null ? null : gain ? "pos" : "neg";
        String resultName = gain == null ? "Resultado" : gain ? "Lucro" : "Prejuízo";
        if (p.kind() == Kind.STOCK) {
            resultHero.setValue(Money.centsToText(r.finalCents()));
            resultHero.setSecondary(String.format("%s total: %s (%.2f%%)", resultName, Money.centsToText(r.profitCents()), r.returnPercent())
                    .replace('.', ','), tone);
            resultHero.setContext(String.format("Estimativa — investido %s; dividendos %d × %s = %s. Sem efeito na carteira.",
                    Money.centsToText(r.investedCents()), p.quantity(), Money.centsToText(p.dividendPerShareCents()),
                    Money.centsToText(r.dividendsCents())));
            applyResultStyle(gain);
            projectionChart.getData().clear();
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName("Ação");
            series.getData().add(new XYChart.Data<>(0, r.seriesCents().get(0) / 100.0));
            series.getData().add(new XYChart.Data<>(1, r.seriesCents().get(1) / 100.0));
            projectionChart.getData().add(series);
            Motion.chartRefresh(projectionChart);
        } else {
            String ratesNote = p.kind() == Kind.BENCHMARK_PERCENT
                    ? " Taxas: " + p.rates().origin() + " " + nvl(p.rates().referenceDate()) + "."
                    : "";
            // Negative rates are accepted (fixed −5 %, a negative frozen index): the tone follows the sign
            // of the profit here exactly as it does for stocks, never "always positive".
            resultHero.setValue(Money.centsToText(r.finalCents()));
            resultHero.setSecondary(String.format("%s: %s (%.1f%%)", resultName, Money.centsToText(r.profitCents()), r.returnPercent())
                    .replace('.', ','), tone);
            resultHero.setContext(String.format("Estimativa em %d meses — taxa efetiva %.2f%% a.a.%s Sem efeito na carteira.",
                    p.months(), r.effectiveAnnualRate() * 100, ratesNote).replace('.', ','));
            applyResultStyle(gain);
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
        Motion.chartRefresh(projectionChart);
    }

    /** {@code null} = neutral (idle or a problem to fix), otherwise gain/loss tone on the number. */
    private void applyResultStyle(Boolean positive) {
        Label value = resultHero.valueLabel();
        value.getStyleClass().removeAll("pos", "neg");
        if (positive != null) value.getStyleClass().add(positive ? "pos" : "neg");
    }

    private void applyRatesStatusStyle(String... classes) {
        ratesStatusLabel.getStyleClass().removeAll("text-helper", "text-xs", "state-warning", "state-positive");
        ratesStatusLabel.getStyleClass().addAll(java.util.List.of(classes));
    }
}

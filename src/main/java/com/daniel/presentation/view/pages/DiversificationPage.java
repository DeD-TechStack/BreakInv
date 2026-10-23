package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.service.ARCADiversificationStrategy;
import com.daniel.core.service.ARCADiversificationStrategy.*;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.DiversificationCalculator;
import com.daniel.core.service.DiversificationCalculator.*;
import com.daniel.core.util.Money;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.HeroValue;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.layout.AdaptivePair;
import com.daniel.presentation.view.layout.LineFlow;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.viewmodel.AllocationPlanCardController;
import com.daniel.presentation.viewmodel.AllocationPlanForm;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import com.daniel.presentation.view.util.UiExecutor;

/**
 * Diversificação (direction A): the wealth is the hero with the computed target beside it;
 * calculation type and plan are one row of two small panels that stacks only when narrow;
 * the current/target comparison is two panels side by side only when wide; the plan editor
 * opens inside the plan panel; suggestions have their own panel. Figures and plan rules are
 * unchanged — the controller and the calculators are the same.
 */
public final class DiversificationPage implements ResponsivePage {

    private final DailyTrackingUseCase daily;
    private final java.util.concurrent.Executor worker;
    private final java.util.concurrent.Executor ui;
    /**
     * Owns plan persistence rules: save+select in one transaction, ARCA fallback with an
     * explicit warning for an invalid selected plan, partial listing of invalid plans.
     */
    private final AllocationPlanCardController planCtrl;
    /** Profile delivered by the controller from the persisted selection (null = ARCA). */
    private Map<CategoryEnum, Double> activeProfile = ARCADiversificationStrategy.getARCAProfile();
    private final VBox root = new VBox(16);
    private final ScrollPane scrollPane = new ScrollPane();

    /** Combo entry: {@code plan == null} means the built-in ARCA strategy. */
    private record PlanChoice(AllocationPlan plan) {
        @Override public String toString() { return plan == null ? "ARCA (Primo Rico) — padrão" : plan.name(); }
        Long id() { return plan == null ? null : plan.id(); }
    }

    private final ComboBox<PlanChoice> planCombo = new ComboBox<>();
    private final Button newPlanBtn = new Button("Novo plano");
    private final Button editPlanBtn = new Button("Editar");
    private final Button deletePlanBtn = new Button("Excluir");
    private final Label planHint = new Label();
    private final Label planEmptyHint = new Label("Nenhum plano personalizado salvo. Clique em \"Novo plano\" para criar o primeiro.");
    private boolean suppressSelectionEvents = false;

    private final ToggleGroup calculationTypeGroup = new ToggleGroup();
    private final ToggleButton rebalanceByContributionRadio = new ToggleButton("Rebalancear por aporte");
    private final ToggleButton rebalanceByTargetRadio = new ToggleButton("Patrimônio alvo");

    private final TextField targetPatrimonyField = new TextField();
    // Plan editor (shown for "Novo plano" / "Editar"); gets .panel class in buildPlanEditor()
    private final VBox planEditorBox = new VBox(12);
    private final TextField planNameField = new TextField();
    private final Map<CategoryEnum, TextField> planPercentFields = new EnumMap<>(CategoryEnum.class);
    private final Label planTotalLabel = new Label();
    private final Label planErrorLabel = new Label();
    private final Button savePlanBtn = new Button("Salvar plano");
    private final Button cancelPlanBtn = new Button("Cancelar");
    private Long editingPlanId = null;

    private final TableView<AllocationRow> currentTable = fittedTable();
    private final TableView<AllocationRow> idealTable = fittedTable();
    private final TableView<SuggestionRow> suggestionsTable = fittedTable();

    private final HeroValue hero = new HeroValue("Patrimônio atual");
    /** Distinguishes current positions, the strategy's ideal base, and contribution suggestions. */
    private final Label distributionBasisLabel = new Label();
    private final Label totalAporteLabel    = new Label();
    private final Label impliedTargetLabel  = new Label("—");
    private VBox impliedTargetBox;

    private Panel planPanel;
    private Panel idealPanel;
    private Panel suggestionsPanel;
    private AdaptivePair controlsPair;
    private AdaptivePair comparisonPair;
    private final VBox alignedComparison = new VBox(0);
    private final List<AdaptivePair> categoryRows = new ArrayList<>();
    private Panel comparisonSummary;
    private Breakpoint currentBreakpoint = Breakpoint.WIDE;

    // Empty state — shown when no investments are registered
    private final VBox noInvestmentsPanel = buildNoInvestmentsPanel();

    public DiversificationPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this(dailyTrackingUseCase, SavedAnalysisRepository.forApplicationDatabase());
    }

    public DiversificationPage(DailyTrackingUseCase dailyTrackingUseCase, SavedAnalysisRepository plans) {
        this(dailyTrackingUseCase, plans, UiExecutor.get(), Platform::runLater);
    }

    /** Test seam: the executors the plan card controller uses (worker for persistence, ui for callbacks). */
    DiversificationPage(DailyTrackingUseCase dailyTrackingUseCase, SavedAnalysisRepository plans,
                        java.util.concurrent.Executor worker, java.util.concurrent.Executor ui) {
        this.daily = dailyTrackingUseCase;
        this.worker = worker;
        this.ui = ui;
        this.planCtrl = new AllocationPlanCardController(plans, worker, ui, new PlanCardView());

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Diversificação",
                "Como a carteira se distribui hoje e quanto aportar para chegar ao plano");

        buildPatrimonyHero();

        Panel currentPanel = buildCurrentAllocationTable();
        idealPanel = buildIdealAllocationTable();
        // R3-10: side by side each detailed panel keeps its own height — stretched to the taller target,
        // "Distribuição atual" left ~270 px of empty surface under its table (as R2-20 in Configurações)
        currentPanel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        idealPanel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Panel calculationPanel = buildCalculationType();
        planPanel = buildMethodSelector();
        suggestionsPanel = buildSuggestionsTable();

        // two small control panels fit from medium width on; two tables only when wide
        // V5 (r1 at 960×720: "Rebalancear por ...", "Patrimôn..."): side by side only when wide;
        // a 0.42 share of the medium page left the calculation options 250 px.
        controlsPair = new AdaptivePair(calculationPanel, planPanel, Breakpoint.MEDIUM, 0.42, 16);
        comparisonPair = new AdaptivePair(currentPanel, idealPanel, Breakpoint.MEDIUM, 0.5, 16);
        comparisonSummary = new Panel("Atual × alvo", alignedComparison)
                .subtitle("Barras e percentuais na mesma base, o patrimônio ideal: a diferença positiva entre alvo e atual é o aporte sugerido.");
        comparisonSummary.setVisible(false);
        comparisonSummary.setManaged(false);
        TitledPane detailedTables = new TitledPane("Ver tabelas detalhadas", comparisonPair);
        detailedTables.setAnimated(false);
        detailedTables.setExpanded(false);
        detailedTables.getStyleClass().add("allocation-tables");

        distributionBasisLabel.getStyleClass().add("text-secondary");
        distributionBasisLabel.setWrapText(true);
        distributionBasisLabel.setVisible(false);
        distributionBasisLabel.setManaged(false);

        root.getChildren().addAll(header, hero, noInvestmentsPanel, controlsPair, distributionBasisLabel,
                comparisonSummary, suggestionsPanel, detailedTables);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");
        onBreakpoint(Breakpoint.WIDE);
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        planCtrl.reload(true);
    }

    @Override
    public void onBreakpoint(Breakpoint bp) {
        currentBreakpoint = bp;
        controlsPair.onBreakpoint(bp);
        comparisonPair.onBreakpoint(bp);
        categoryRows.forEach(row -> row.onBreakpoint(bp));
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    // Seletor de tipo de cálculo
    private Panel buildCalculationType() {
        rebalanceByContributionRadio.setToggleGroup(calculationTypeGroup);
        rebalanceByTargetRadio.setToggleGroup(calculationTypeGroup);
        rebalanceByContributionRadio.getStyleClass().add("seg-btn");
        rebalanceByTargetRadio.getStyleClass().add("seg-btn");
        rebalanceByContributionRadio.setSelected(true);
        rebalanceByContributionRadio.setTooltip(new Tooltip("Sugere aportes para equilibrar a carteira sem vender nada"));
        rebalanceByTargetRadio.setTooltip(new Tooltip("Sugere quanto aportar em cada categoria para chegar a um valor total"));

        LineFlow segCalc = new LineFlow(2, 2, rebalanceByContributionRadio, rebalanceByTargetRadio);
        segCalc.getStyleClass().add("segmented");

        Label hint = new Label("Calcula o patrimônio alvo a partir da categoria mais pesada da carteira e sugere aportes para equilibrar — sem vender nenhum ativo.");
        hint.getStyleClass().add("text-helper");
        hint.setWrapText(true);

        // Target patrimony field (shown in target mode)
        Label targetLabel = new Label("Patrimônio alvo");
        targetLabel.getStyleClass().add("form-label");
        targetLabel.setLabelFor(targetPatrimonyField);
        targetPatrimonyField.setPromptText("R$ 100.000,00");
        targetPatrimonyField.setAccessibleText("Patrimônio alvo");
        targetPatrimonyField.setTextFormatter(Money.currencyFormatterEditable());
        Money.applyFormatOnBlur(targetPatrimonyField);

        VBox targetBox = new VBox(6, targetLabel, targetPatrimonyField);
        targetBox.setVisible(false);
        targetBox.setManaged(false);

        calculationTypeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) { if (oldVal != null) oldVal.setSelected(true); return; }
            boolean isTarget = newVal == rebalanceByTargetRadio;
            targetBox.setVisible(isTarget);
            targetBox.setManaged(isTarget);
            hint.setText(isTarget
                    ? "Calcula quanto aportar em cada categoria para atingir um patrimônio alvo"
                    : "Calcula o patrimônio alvo a partir da categoria mais pesada da carteira e sugere aportes para equilibrar — sem vender nenhum ativo.");
            refreshData(activeProfile);
        });

        Button recalculateBtn = new Button("Recalcular");
        recalculateBtn.getStyleClass().add("button");
        recalculateBtn.setTooltip(new Tooltip("Refaz a análise com os valores de hoje"));
        recalculateBtn.setOnAction(e -> planCtrl.refresh());

        return new Panel("Tipo de cálculo", segCalc, hint, targetBox, recalculateBtn);
    }

    private Panel buildMethodSelector() {
        planCombo.setMaxWidth(Double.MAX_VALUE);
        planCombo.setAccessibleText("Plano de alocação");
        // Explicit cells: the selected plan's name is rendered by our own ListCell (the skin's default
        // button cell is what the 150 % capture showed blank although the value was correct).
        planCombo.setButtonCell(planCell());
        planCombo.setCellFactory(lv -> planCell());
        planCombo.getItems().add(new PlanChoice(null));
        planCombo.getSelectionModel().selectFirst();
        planCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressSelectionEvents || newVal == null) return;
            onPlanChosen(newVal);
        });
        HBox.setHgrow(planCombo, Priority.ALWAYS);

        newPlanBtn.getStyleClass().add("secondary-btn");
        editPlanBtn.getStyleClass().add("ghost-btn");
        deletePlanBtn.getStyleClass().add("danger-btn");
        newPlanBtn.setTooltip(new Tooltip("Cria um plano com seus próprios pesos por categoria"));
        editPlanBtn.setTooltip(new Tooltip("Altera os pesos do plano selecionado"));
        deletePlanBtn.setTooltip(new Tooltip("Exclui o plano selecionado; a análise volta ao ARCA"));
        newPlanBtn.setOnAction(e -> openPlanEditor(null));
        editPlanBtn.setOnAction(e -> {
            PlanChoice sel = planCombo.getValue();
            if (sel != null && sel.plan() != null) openPlanEditor(sel.plan());
        });
        deletePlanBtn.setOnAction(e -> deleteSelectedPlan());

        FlowPane planActions = new FlowPane(8, 8, newPlanBtn, editPlanBtn, deletePlanBtn);
        planActions.setAlignment(Pos.CENTER_LEFT);
        VBox row = new VBox(8, planCombo, planActions);

        planHint.getStyleClass().add("text-helper");
        planHint.setWrapText(true);
        planEmptyHint.getStyleClass().add("text-helper");
        planEmptyHint.setWrapText(true);

        buildPlanEditor();
        planEditorBox.setVisible(false);
        planEditorBox.setManaged(false);

        Panel panel = new Panel("Plano de alocação", row, planHint, planEmptyHint, planEditorBox);
        updatePlanHint();
        return panel;
    }

    private static ListCell<PlanChoice> planCell() {
        return new ListCell<>() {
            @Override protected void updateItem(PlanChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toString());
            }
        };
    }

    private void buildPlanEditor() {
        planEditorBox.getStyleClass().add("panel");

        Label nameLabel = new Label("Nome do plano");
        nameLabel.getStyleClass().add("form-label");
        nameLabel.setLabelFor(planNameField);
        planNameField.setPromptText("Ex.: Conservador");
        planNameField.setAccessibleText("Nome do plano");

        Label customTitle = new Label("Pesos por categoria (a soma precisa dar 100,00%)");
        customTitle.getStyleClass().add("form-label");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("rate-grid");

        int row = 0;
        for (CategoryEnum cat : CategoryEnum.values()) {
            Circle circle = new Circle(5);
            circle.setFill(Color.web(cat.getColor()));

            Label label = new Label(cat.getDisplayName());

            TextField field = new TextField("0,00");
            field.setPrefWidth(90);
            field.setPromptText("0,00");
            field.setAccessibleText("Peso de " + cat.getDisplayName() + " em porcentagem");
            field.textProperty().addListener((obs, o, n) -> updatePlanTotal());

            Label percent = new Label("%");

            planPercentFields.put(cat, field);

            grid.add(circle, 0, row);
            grid.add(label, 1, row);
            grid.add(field, 2, row);
            grid.add(percent, 3, row);

            row++;
        }

        planTotalLabel.getStyleClass().addAll("text-helper", "text-bold");
        planErrorLabel.getStyleClass().add("status-warning");
        planErrorLabel.setWrapText(true);
        planErrorLabel.setVisible(false);
        planErrorLabel.setManaged(false);

        savePlanBtn.getStyleClass().add("button");
        cancelPlanBtn.getStyleClass().add("ghost-btn");
        savePlanBtn.setTooltip(new Tooltip("Grava o plano e passa a usá-lo na análise"));
        cancelPlanBtn.setTooltip(new Tooltip("Fecha o editor sem gravar (Esc)"));
        savePlanBtn.setOnAction(e -> savePlanFromEditor());
        cancelPlanBtn.setOnAction(e -> { if (!planCtrl.isBusy()) closePlanEditor(); });
        planEditorBox.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                // While a save is in flight the editor is the one the pending callback acts on:
                // ESC is swallowed, never closes it (the response closes it or shows the error).
                if (!planCtrl.isBusy()) closePlanEditor();
                ev.consume();
            }
        });
        planNameField.setOnAction(e -> savePlanFromEditor());
        HBox actions = new HBox(8, savePlanBtn, cancelPlanBtn);

        planEditorBox.getChildren().addAll(nameLabel, planNameField, customTitle, grid, planTotalLabel, planErrorLabel, actions);
    }

    // ----- plan selection / editing -----

    private Map<CategoryEnum, String> editorTexts() {
        Map<CategoryEnum, String> texts = new EnumMap<>(CategoryEnum.class);
        planPercentFields.forEach((cat, f) -> texts.put(cat, f.getText()));
        return texts;
    }

    private void updatePlanTotal() {
        int total = AllocationPlanForm.sumBps(editorTexts());
        planTotalLabel.setText("Soma: " + AllocationPlan.formatPercent(total)
                + (total == AllocationPlan.TOTAL_BPS ? " \u2713" : " (precisa ser 100,00%)"));
    }

    private void updatePlanHint() {
        PlanChoice sel = planCombo.getValue();
        boolean hasCustom = planCombo.getItems().size() > 1;
        planEmptyHint.setVisible(!hasCustom);
        planEmptyHint.setManaged(!hasCustom);
        if (sel == null || sel.plan() == null) {
            planHint.setText("Renda Fixa 40% \u2022 A\u00e7\u00f5es 30% \u2022 Outros 25% \u2022 Cripto 5% \u2014 estrat\u00e9gia padr\u00e3o, n\u00e3o edit\u00e1vel");
            editPlanBtn.setDisable(true);
            deletePlanBtn.setDisable(true);
            if (idealPanel != null) idealPanel.setTitle("Distribuição alvo — ARCA (padrão)");
        } else {
            StringBuilder sb = new StringBuilder();
            sel.plan().weightsBps().forEach((cat, bps) -> {
                if (bps > 0) {
                    if (sb.length() > 0) sb.append(" \u2022 ");
                    sb.append(cat.getDisplayName()).append(' ').append(AllocationPlan.formatPercent(bps));
                }
            });
            planHint.setText(sb.toString());
            editPlanBtn.setDisable(false);
            deletePlanBtn.setDisable(false);
            if (idealPanel != null) idealPanel.setTitle("Distribuição alvo — " + sel.plan().name());
        }
    }

    private void openPlanEditor(AllocationPlan plan) {
        editingPlanId = plan == null ? null : plan.id();
        planNameField.setText(plan == null ? "" : plan.name());
        for (CategoryEnum cat : CategoryEnum.values()) {
            int bps = plan == null ? 0 : plan.weightsBps().getOrDefault(cat, 0);
            planPercentFields.get(cat).setText(AllocationPlanForm.formatBps(bps));
        }
        showPlanError(null);
        updatePlanTotal();
        planEditorBox.setVisible(true);
        planEditorBox.setManaged(true);
        setPlanControlsDisabled(true);
        planNameField.requestFocus();
    }

    private void closePlanEditor() {
        planEditorBox.setVisible(false);
        planEditorBox.setManaged(false);
        editingPlanId = null;
        setPlanControlsDisabled(false);
        updatePlanHint();
        planCombo.requestFocus();
    }

    private void setPlanControlsDisabled(boolean disabled) {
        planCombo.setDisable(disabled);
        newPlanBtn.setDisable(disabled);
        editPlanBtn.setDisable(disabled);
        deletePlanBtn.setDisable(disabled);
    }

    private void showPlanError(String message) {
        boolean has = message != null && !message.isBlank();
        planErrorLabel.setText(has ? message : "");
        planErrorLabel.setVisible(has);
        planErrorLabel.setManaged(has);
    }

    /** Validates on the FX thread; the controller persists plan + selection atomically and reloads the combo. */
    private void savePlanFromEditor() {
        if (planCtrl.isBusy()) return; // Enter or a second click while the previous save has not answered
        AllocationPlan candidate;
        try {
            candidate = AllocationPlanForm.toPlan(editingPlanId, planNameField.getText(), editorTexts());
        } catch (IllegalArgumentException ex) {
            showPlanError(ex.getMessage());
            return;
        }
        showPlanError(null);
        planCtrl.saveFromEditor(candidate);
    }

    private void deleteSelectedPlan() {
        PlanChoice sel = planCombo.getValue();
        if (sel == null || sel.plan() == null) return;
        AllocationPlan plan = sel.plan();
        if (!Dialogs.confirm("Excluir plano", "Excluir o plano \"" + plan.name() + "\"?",
                "A an\u00e1lise voltar\u00e1 para a estrat\u00e9gia ARCA padr\u00e3o. Nenhum investimento \u00e9 alterado.", "Excluir")) return;
        planCtrl.delete(plan);
    }

    private void onPlanChosen(PlanChoice choice) {
        updatePlanHint();
        planCtrl.choose(choice.id());
    }

    /** JavaFX side of the plan card; all methods run on the FX thread. */
    private final class PlanCardView implements AllocationPlanCardController.View {
        @Override public void renderPlans(List<AllocationPlan> all, Long selectedId) {
            suppressSelectionEvents = true;
            try {
                List<PlanChoice> choices = new ArrayList<>();
                choices.add(new PlanChoice(null));
                PlanChoice toSelect = choices.get(0);
                for (AllocationPlan p : all) {
                    PlanChoice c = new PlanChoice(p);
                    choices.add(c);
                    if (selectedId != null && selectedId.equals(p.id())) toSelect = c;
                }
                // Publish a complete list: an ARCA-only intermediate empties the skin's selected
                // cell, while reselecting an equal persisted record need not invalidate its value.
                planCombo.getItems().setAll(choices);
                planCombo.getSelectionModel().select(toSelect);
            } finally {
                suppressSelectionEvents = false;
            }
            updatePlanHint();
        }
        @Override public void renderProfile(Map<CategoryEnum, Double> profile, AllocationPlan plan) {
            activeProfile = profile;
            refreshData(profile);
        }
        @Override public void setBusy(boolean busy) {
            savePlanBtn.setDisable(busy);
            cancelPlanBtn.setDisable(busy);
            planNameField.setDisable(busy);
            planPercentFields.values().forEach(f -> f.setDisable(busy));
            if (!planEditorBox.isVisible()) setPlanControlsDisabled(busy);
        }
        @Override public void showEditorError(String message) { showPlanError(message); }
        @Override public void closeEditor() { closePlanEditor(); }
        @Override public void warn(String message) { ToastHost.showWarn(message); }
        @Override public void error(String message) { ToastHost.showError(message); }
        @Override public void success(String message) { ToastHost.showSuccess(message); }
        @Override public void info(String message) { ToastHost.showInfo(message); }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof java.util.concurrent.CompletionException
                || c instanceof java.util.concurrent.ExecutionException)) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    /** Wealth as the hero; the computed target is a trailing value of the same block, not a second card. */
    private void buildPatrimonyHero() {
        hero.setContext("com os valores de hoje");
        Label impliedTitle = new Label("Alvo calculado");
        impliedTitle.getStyleClass().add("hero-caption");
        impliedTargetLabel.getStyleClass().addAll("hero-secondary", "num");
        impliedTargetBox = new VBox(2, impliedTitle, impliedTargetLabel);
        impliedTargetBox.setVisible(false);
        impliedTargetBox.setManaged(false);
        hero.setTrailing(impliedTargetBox);
    }

    // Detailed tables (A-V4/A-L2; r1 populated: "R$ 20.226.800,..." and "Fundos Imo..." in three
    // equal 144 px columns). Amounts and percentages keep what their widest rendered cell asks for;
    // the category takes the rest down to CATEGORY_FLOOR (below it the whole name stays in the
    // tooltip) and, if even that does not fit, the table scrolls sideways instead of cutting a value.
    // A function of the width and the rows alone, so a return to wide restores the same columns.
    static final double CATEGORY_FLOOR = 120;
    static final double AMOUNT_FLOOR = 72;
    static final double HEADER_PADDING = 24;
    private static final double BAR_PREF_WIDTH = 40;
    /** R2-05: a detailed table shows every row up to this many (category lists stay below it). */
    static final int MAX_TABLE_ROWS = 10;
    /** A-V3: one track for every current/target bar, so their lengths compare (was each text's width). */
    static final double SHARE_TRACK = 140;

    private static <T> TableView<T> fittedTable() {
        TableView<T> table = new TableView<>() {
            // R4-02: the rows of new items, or of a new size, are built after this measure, later in the same pass,
            // and the relayout they then ask for does not propagate past the table while its parents are still
            // laying out, so no later pulse came back to it: data arriving after the first layout left Valor and %
            // on their 72 px floors ("R$ 1…", "100,…") until a resize. The first pass that sees new items or a new
            // size therefore measures again on the next pulse — once, so it never loops.
            private int itemsVersion, fittedVersion = -1;
            private double fittedWidth = -1, fittedHeight = -1;
            {
                javafx.collections.ListChangeListener<T> changed = c -> itemsVersion++;
                getItems().addListener(changed);
                itemsProperty().addListener((o, a, b) -> {
                    itemsVersion++;
                    if (a != null) a.removeListener(changed);
                    if (b != null) b.addListener(changed);
                });
            }
            @Override protected void layoutChildren() {
                super.layoutChildren();
                fitColumns(this); // measures the rows built so far
                if (fittedVersion != itemsVersion || fittedWidth != getWidth() || fittedHeight != getHeight()) {
                    fittedVersion = itemsVersion;
                    fittedWidth = getWidth();
                    fittedHeight = getHeight();
                    Platform.runLater(this::requestLayout); // the rows built after this measure, on the next pulse
                }
            }
            @Override protected double computePrefHeight(double width) {
                double fitted = fittedHeight(this, width);
                return fitted > 0 ? fitted : super.computePrefHeight(width);
            }
            @Override protected double computeMinHeight(double width) {
                double fitted = fittedHeight(this, width);
                return fitted > 0 ? fitted : super.computeMinHeight(width);
            }
            // the height depends on the width: below the column budget a horizontal bar is reserved
            @Override public javafx.geometry.Orientation getContentBias() { return javafx.geometry.Orientation.HORIZONTAL; }
        };
        table.getStyleClass().add("fitted-table");
        // the height is a function of the row count and of the breakpoint's row size (CSS)
        table.itemsProperty().addListener((o, a, b) -> table.requestLayout());
        table.fixedCellSizeProperty().addListener((o, a, b) -> table.requestLayout());
        return table;
    }

    static void fitColumns(TableView<?> table) {
        var columns = table.getColumns();
        if (table.getWidth() <= 0 || columns.size() < 2) return;
        double[] need = columnNeeds(table);
        double bar = 0;
        for (Node n : table.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL && sb.isVisible())
                bar = Math.ceil(sb.getWidth());
        }
        double available = Math.floor(table.getWidth() - table.snappedLeftInset() - table.snappedRightInset() - bar);
        double fixed = 0;
        for (int i = 1; i < columns.size(); i++) fixed += Math.max(AMOUNT_FLOOR, need[i]);
        setColumnWidth(columns.get(0), Math.max(CATEGORY_FLOOR, available - fixed));
        for (int i = 1; i < columns.size(); i++) setColumnWidth(columns.get(i), Math.max(AMOUNT_FLOOR, need[i]));
    }

    /** What each column's cells and title ask for, with the current CSS padding of the cells. */
    static double[] columnNeeds(TableView<?> table) {
        var columns = table.getColumns();
        double[] need = new double[columns.size()];
        for (Node n : table.lookupAll(".table-cell")) {
            if (n instanceof TableCell<?, ?> cell && !cell.isEmpty() && cell.isVisible()) {
                int i = columns.indexOf(cell.getTableColumn());
                if (i >= 0) need[i] = Math.max(need[i], contentWidth(cell));
            }
        }
        for (Node n : table.lookupAll(".column-header")) {
            if (n instanceof javafx.scene.control.skin.TableColumnHeader header && header.lookup(".label") instanceof Label title) {
                int i = columns.indexOf(header.getTableColumn());
                if (i >= 0) need[i] = Math.max(need[i], Math.ceil(title.prefWidth(-1)) + HEADER_PADDING);
            }
        }
        return need;
    }

    /**
     * R2-05: the table is as tall as its header and rows (up to {@link #MAX_TABLE_ROWS}) — a fixed
     * 220/200 px left two-row tables half empty and pushed the 4th row of five under a scroll.
     * Rows have one fixed size per breakpoint ({@code .fitted-table} in app.css), so the height is
     * known when the parent lays the table out; heights measured on rows a pass later never reached
     * a parent already laid out (first wide view and the narrow padding kept the old height).
     */
    static double fittedHeight(TableView<?> table, double width) {
        double row = table.getFixedCellSize();
        Node header = table.lookup(".column-header-background");
        if (row <= 0 || !(header instanceof Region h)) return -1;
        // At the expanded-sidebar minimum the amounts keep their width and the columns can exceed the
        // table (as the Ranking lists, A-V5): the horizontal bar then gets its own height under the rows.
        double w = width > 0 ? width : table.getWidth();
        double hbar = 0;
        if (w > 0 && table.getColumns().size() > 1) {
            double[] need = columnNeeds(table);
            double budget = CATEGORY_FLOOR;
            for (int i = 1; i < need.length; i++) budget += Math.max(AMOUNT_FLOOR, need[i]);
            if (budget > Math.floor(w - table.snappedLeftInset() - table.snappedRightInset())) {
                for (Node n : table.lookupAll(".scroll-bar")) {
                    if (n instanceof ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.HORIZONTAL)
                        hbar = Math.max(hbar, Math.ceil(sb.prefHeight(-1)));
                }
            }
        }
        int rows = Math.max(1, Math.min(table.getItems().size(), MAX_TABLE_ROWS)); // one row for the placeholder
        // exact: one spare pixel makes the flow add an empty filler row, and a filler row plus a
        // horizontal bar turns the vertical bar on (VirtualFlow.computeBarVisiblity)
        return Math.ceil(table.snappedTopInset() + h.prefHeight(-1) + row * rows + hbar + table.snappedBottomInset());
    }

    /** The % cell: the share above a thin bar that keeps its 4 px (the generic 6 px bar overflowed 28 px). */
    private static VBox shareCell(String item, ProgressBar bar) {
        try { bar.setProgress(Double.parseDouble(item.replace("%", "").replace(',', '.').trim()) / 100.0); }
        catch (Exception ignored) { bar.setProgress(0); }
        Label lbl = new Label(item);
        VBox cell = new VBox(1, lbl, bar);
        cell.setMinHeight(Region.USE_PREF_SIZE);
        return cell;
    }

    private static ProgressBar shareBar() {
        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("share-bar");
        bar.setMinWidth(0);
        bar.setPrefWidth(BAR_PREF_WIDTH);
        bar.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    /** What a cell's content asks for: a TableCell reports its column's width as its own pref width. */
    private static double contentWidth(TableCell<?, ?> cell) {
        double width = 0;
        String text = cell.getText();
        if (text != null && !text.isEmpty()) {
            javafx.scene.text.Text probe = new javafx.scene.text.Text(text);
            probe.setFont(cell.getFont());
            width += probe.getLayoutBounds().getWidth();
        }
        Node graphic = cell.getGraphic();
        if (graphic != null) width += graphic.prefWidth(-1) + (width > 0 ? cell.getGraphicTextGap() : 0);
        return Math.ceil(width + cell.snappedLeftInset() + cell.snappedRightInset()) + 2;
    }

    private static void setColumnWidth(TableColumn<?, ?> column, double width) {
        if (Math.abs(column.getPrefWidth() - width) >= 0.5) column.setPrefWidth(width);
    }

    private Panel buildCurrentAllocationTable() {
        currentTable.getStyleClass().add("table-analytic");
        currentTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY); // budget: fitColumns

        TableColumn<AllocationRow, String> catCol = new TableColumn<>("Categoria");
        catCol.setCellValueFactory(c -> c.getValue().categoryProperty());
        catCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    AllocationRow row = getTableRow().getItem();
                    if (row != null) {
                        setGraphic(createCategoryBadge(row.getCategory(), item));
                        setText(null);
                    } else {
                        setText(item);
                        setGraphic(null);
                    }
                }
            }
        });

        TableColumn<AllocationRow, String> valueCol = new TableColumn<>("Valor");
        valueCol.setCellValueFactory(c -> c.getValue().valueProperty());

        TableColumn<AllocationRow, String> percentCol = new TableColumn<>("%");
        percentCol.setCellValueFactory(c -> c.getValue().percentageProperty());
        percentCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = shareBar();
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); } else {
                    setGraphic(shareCell(item, bar)); setText(null);
                }
            }
        });

        currentTable.getColumns().add(catCol);
        currentTable.getColumns().add(valueCol);
        currentTable.getColumns().add(percentCol);
        Label currentPh = new Label("Sem investimentos para distribuir");
        currentPh.getStyleClass().add("text-helper");
        currentTable.setPlaceholder(currentPh);

        return new Panel("Distribuição atual", currentTable);
    }

    private Panel buildIdealAllocationTable() {
        idealTable.getStyleClass().add("table-analytic");
        idealTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY); // budget: fitColumns

        TableColumn<AllocationRow, String> catCol = new TableColumn<>("Categoria");
        catCol.setCellValueFactory(c -> c.getValue().categoryProperty());
        catCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    AllocationRow row = getTableRow().getItem();
                    if (row != null) {
                        setGraphic(createCategoryBadge(row.getCategory(), item));
                        setText(null);
                    } else {
                        setText(item);
                        setGraphic(null);
                    }
                }
            }
        });

        TableColumn<AllocationRow, String> valueCol = new TableColumn<>("Valor");
        valueCol.setCellValueFactory(c -> c.getValue().valueProperty());

        TableColumn<AllocationRow, String> percentCol = new TableColumn<>("%");
        percentCol.setCellValueFactory(c -> c.getValue().percentageProperty());
        percentCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = shareBar();
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); } else {
                    setGraphic(shareCell(item, bar)); setText(null);
                }
            }
        });

        idealTable.getColumns().add(catCol);
        idealTable.getColumns().add(valueCol);
        idealTable.getColumns().add(percentCol);
        Label idealPh = new Label("A distribuição alvo aparece quando houver investimentos");
        idealPh.getStyleClass().add("text-helper");
        idealTable.setPlaceholder(idealPh);

        return new Panel("Distribuição alvo — ARCA (padrão)", idealTable);
    }

    private Panel buildSuggestionsTable() {
        suggestionsTable.getStyleClass().add("table-analytic");
        suggestionsTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY); // budget: fitColumns

        TableColumn<SuggestionRow, String> catCol = new TableColumn<>("Categoria");
        catCol.setCellValueFactory(c -> c.getValue().categoryProperty());
        catCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    SuggestionRow row = getTableRow().getItem();
                    if (row != null) {
                        setGraphic(createCategoryBadge(row.getCategory(), item));
                        setText(null);
                    } else {
                        setText(item);
                        setGraphic(null);
                    }
                }
            }
        });

        TableColumn<SuggestionRow, String> actionCol = new TableColumn<>("Aporte sugerido");
        actionCol.setCellValueFactory(c -> c.getValue().actionProperty());
        actionCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().removeAll("suggestion-invest", "suggestion-balanced");
                } else {
                    setText(item);
                    getStyleClass().removeAll("suggestion-invest", "suggestion-balanced");
                    if (item.contains("Investir") || item.contains("Aportar")) {
                        getStyleClass().add("suggestion-invest");
                    } else if (item.contains("balanceado")) {
                        getStyleClass().add("suggestion-balanced");
                    }
                }
            }
        });

        suggestionsTable.getColumns().add(catCol);
        suggestionsTable.getColumns().add(actionCol);
        Label suggPh = new Label("Cadastre investimentos para receber sugestões de aporte.");
        suggPh.getStyleClass().add("text-helper");
        suggestionsTable.setPlaceholder(suggPh);

        totalAporteLabel.getStyleClass().addAll("text-helper", "text-bold");
        return new Panel("Sugestões de aporte", suggestionsTable, totalAporteLabel)
                .subtitle("Só sugere aportes; nunca vende nem movimenta nada.");
    }

    private VBox buildNoInvestmentsPanel() {
        VBox box = EmptyState.of("📊", "Nenhum investimento cadastrado",
                "Cadastre seus ativos para analisar a distribuição da carteira e receber sugestões de rebalanceamento.", true);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    private record DivData(List<InvestmentType> investments, Map<Long, Long> currentValues, long totalPatrimony) {}

    private final AtomicLong refreshEpoch = new AtomicLong(0);

    /**
     * Recomputes tables for the given profile. The profile is always the one delivered by
     * {@link AllocationPlanCardController} from the persisted selection (RF10 ext.), never a
     * form copy; an invalid selected plan arrives here already resolved to ARCA with a warning.
     */
    private void refreshData(Map<CategoryEnum, Double> profile) {
        LocalDate today = LocalDate.now();
        final long epoch = refreshEpoch.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            // BRK-36: one consistent read and at most one ledger replay for the whole refresh; it stays in this task
            DailyTrackingUseCase.PortfolioRead r = daily.read();
            List<InvestmentType> investments = r.listTypes();
            if (investments.isEmpty()) {
                return new DivData(investments, Map.of(), 0);
            }
            Map<Long, Long> currentValues = r.getAllCurrentValues(today);
            long totalPatrimony = r.getTotalPatrimony(today);
            return new DivData(investments, currentValues, totalPatrimony);
        }, worker).whenComplete((data, err) -> {
            if (refreshEpoch.get() != epoch) return;
            ui.execute(() -> {
                if (err != null) {
                    ToastHost.showError("N\u00e3o foi poss\u00edvel calcular a diversifica\u00e7\u00e3o: " + rootMessage(err));
                    return;
                }
                if (data.investments().isEmpty()) {
                    hero.setValue("\u2014");
                    hero.setContext("cadastre investimentos para analisar a distribuição");
                    distributionBasisLabel.setVisible(false);
                    distributionBasisLabel.setManaged(false);
                    currentTable.getItems().clear();
                    idealTable.getItems().clear();
                    suggestionsTable.getItems().clear();
                    alignedComparison.getChildren().clear();
                    categoryRows.clear();
                    comparisonSummary.setVisible(false);
                    comparisonSummary.setManaged(false);
                    noInvestmentsPanel.setVisible(true);
                    noInvestmentsPanel.setManaged(true);
                    return;
                }
                noInvestmentsPanel.setVisible(false);
                noInvestmentsPanel.setManaged(false);

                DiversificationData currentData = DiversificationCalculator.calculateCurrent(
                        data.investments(),
                        data.currentValues()
                );

                // These valuations are separate reads. Their difference alone does not confirm cash.
                long positions = currentData.totalCents();
                hero.setValue(daily.brl(data.totalPatrimony()));
                String difference;
                try { difference = daily.brl(Math.subtractExact(data.totalPatrimony(), positions)); }
                catch (ArithmeticException ex) { difference = "indisponível"; }
                hero.setContext("Posições " + daily.brl(positions) + " · diferença para o patrimônio " + difference
                        + " (não confirma saldo de caixa)");
                distributionBasisLabel.setVisible(true);
                distributionBasisLabel.setManaged(true);

                updateCurrentTable(currentData);

                updateIdeal(data.totalPatrimony(), currentData, profile);
            });
        });
    }

    private void updateCurrentTable(DiversificationData data) {
        var rows = FXCollections.<AllocationRow>observableArrayList();

        for (CategoryAllocation alloc : data.allocations()) {
            rows.add(new AllocationRow(
                    alloc.category(),
                    daily.brl(alloc.valueCents()),
                    String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f%%", alloc.percentage())
            ));
        }

        currentTable.setItems(rows);
    }

    /** Ideal distribution and informative contribution suggestions for the given profile (RN10: never trades). */
    private void updateIdeal(long currentPatrimony, DiversificationData currentData, Map<CategoryEnum, Double> profile) {
        List<DiversificationSuggestion> suggestions;
        long idealBase;
        String basisMode;

        if (rebalanceByTargetRadio.isSelected()) {
            long targetPatrimony = getTargetPatrimony(currentPatrimony);
            suggestions = ARCADiversificationStrategy.calculateSuggestionsByTarget(
                    currentPatrimony, targetPatrimony, currentData.valuesCents(), profile
            );
            boolean aboveCurrent = targetPatrimony > currentPatrimony;
            idealBase = aboveCurrent ? targetPatrimony : ARCADiversificationStrategy.calculateImpliedTarget(
                    currentPatrimony, currentData.valuesCents(), profile);
            basisMode = aboveCurrent ? "alvo informado"
                    : "o alvo informado não supera o patrimônio; usa-se o alvo calculado por aporte";
            impliedTargetBox.setVisible(false);
            impliedTargetBox.setManaged(false);
        } else {
            suggestions = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                    currentPatrimony, currentData.valuesCents(), profile
            );
            long implied = ARCADiversificationStrategy.calculateImpliedTarget(
                    currentPatrimony, currentData.valuesCents(), profile);
            idealBase = implied;
            basisMode = "alvo calculado pelas posições, no mínimo o patrimônio total";
            impliedTargetLabel.setText(daily.brl(implied));
            impliedTargetBox.setVisible(true);
            impliedTargetBox.setManaged(true);
        }

        distributionBasisLabel.setText("Atual: posições " + daily.brl(currentData.totalCents()) + " (sem caixa). "
                + "Ideal: " + daily.brl(idealBase) + " — " + basisMode + ". Patrimônio total: " + daily.brl(currentPatrimony)
                + ". Sugestões: diferenças positivas entre ideal e posições, sem descontar o caixa à parte.");

        var idealRows = FXCollections.<AllocationRow>observableArrayList();
        for (var sug : suggestions) {
            double targetPct = profile.getOrDefault(sug.category(), 0.0) * 100.0;
            idealRows.add(new AllocationRow(
                    sug.category(),
                    daily.brl(sug.idealCents()),
                    String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f%%", targetPct)
            ));
        }
        idealTable.setItems(idealRows);
        Map<CategoryEnum, Long> contributions = new java.util.EnumMap<>(CategoryEnum.class);
        suggestions.forEach(s -> contributions.put(s.category(), s.aporteNecessarioCents()));
        updateAlignedComparison(currentData, idealRows, profile, contributions, idealBase);

        var suggestionRows = FXCollections.<SuggestionRow>observableArrayList();
        for (var sug : suggestions) {
            // R2-03: every contribution the total counts (and Atual × alvo names) is listed; a R$ 100
            // cut made the rows add up to less than "Aporte total sugerido".
            if (sug.aporteNecessarioCents() > 0) {
                String action = "Aportar " + daily.brl(sug.aporteNecessarioCents());
                suggestionRows.add(new SuggestionRow(sug.category(), action));
            }
        }

        if (suggestionRows.isEmpty()) {
            suggestionRows.add(new SuggestionRow(CategoryEnum.RENDA_FIXA, "\u2705 Carteira balanceada"));
        }

        suggestionsTable.setItems(suggestionRows);

        long totalAporte = suggestions.stream().mapToLong(s -> s.aporteNecessarioCents()).sum();
        totalAporteLabel.setText(totalAporte > 0 ? "Aporte total sugerido: " + daily.brl(totalAporte) : "");
    }

    /**
     * One row per category with its current and target amounts. A-V3: both bars and both
     * percentages use ONE base, the ideal patrimony, so the gap between the bars is the suggested
     * contribution; a share of current positions (100% when there is a single category) beside a
     * share of the ideal base (30%) made a pending contribution read as an excess.
     */
    private void updateAlignedComparison(DiversificationData currentData, List<AllocationRow> idealRows,
                                         Map<CategoryEnum, Double> profile, Map<CategoryEnum, Long> contributions,
                                         long idealBase) {
        alignedComparison.getChildren().clear();
        categoryRows.clear();
        for (AllocationRow ideal : idealRows.stream().sorted(java.util.Comparator.comparing(AllocationRow::getCategory)).toList()) {
            CategoryEnum category = ideal.getCategory();
            long currentCents = currentData.valuesCents().getOrDefault(category, 0L);
            double targetShare = profile.getOrDefault(category, 0.0);
            if (currentCents == 0 && targetShare == 0) continue;
            double currentShare = idealBase > 0 ? (double) currentCents / idealBase : 0;
            Label name = new Label(category.getDisplayName());
            name.getStyleClass().add("text-strong");
            name.setWrapText(true);
            name.setMinWidth(0);
            VBox current = allocationMetric("Atual · posições", daily.brl(currentCents),
                    shareOfIdealBase(currentShare), currentShare, "allocation-current");
            VBox target = allocationMetric("Alvo · base ideal", ideal.valueProperty().get(),
                    shareOfIdealBase(targetShare), targetShare, "allocation-target");
            FlowPane metrics = new FlowPane(16, 8, current, target);
            metrics.setMinWidth(0);
            metrics.setPrefWrapLength(400);
            long contribution = contributions.getOrDefault(category, 0L);
            Label action = new Label(contribution > 0 ? "Aporte sugerido · " + daily.brl(contribution) : "Sem aporte sugerido");
            action.setWrapText(true);
            action.getStyleClass().add("text-helper");
            VBox identity = new VBox(5, name, action);
            identity.setMinWidth(0);
            AdaptivePair row = new AdaptivePair(identity, metrics, Breakpoint.MEDIUM, 0.27, 12);
            VBox padded = new VBox(row);
            padded.setUserData(category);
            padded.getStyleClass().add("allocation-comparison-row");
            row.onBreakpoint(currentBreakpoint);
            categoryRows.add(row);
            alignedComparison.getChildren().add(padded);
        }
        comparisonSummary.setVisible(true);
        comparisonSummary.setManaged(true);
    }

    private static String shareOfIdealBase(double fraction) {
        return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f%% da base ideal", fraction * 100.0);
    }

    private VBox allocationMetric(String caption, String value, String percentage, double fraction, String style) {
        Label title = new Label(caption);
        title.getStyleClass().add("text-helper");
        title.setWrapText(true);
        Label amount = new Label(value);
        amount.getStyleClass().addAll("text-strong", "allocation-value");
        amount.setMinWidth(Region.USE_PREF_SIZE);
        Label share = new Label(percentage);
        share.getStyleClass().add("text-secondary");
        ProgressBar bar = new ProgressBar(Math.max(0, Math.min(1, fraction)));
        bar.setMinWidth(SHARE_TRACK);
        bar.setPrefWidth(SHARE_TRACK);
        bar.setMaxWidth(SHARE_TRACK);
        bar.setAccessibleText(caption + ": " + percentage);
        VBox metric = new VBox(3, title, amount, bar, share);
        metric.setMinWidth(Region.USE_PREF_SIZE);
        metric.getStyleClass().add(style);
        return metric;
    }

    private long getTargetPatrimony(long currentPatrimony) {
        String targetText = targetPatrimonyField.getText();
        if (targetText == null || targetText.trim().isEmpty()) {
            return currentPatrimony;
        }

        try {
            return Money.textToCentsSafe(targetText);
        } catch (Exception e) {
            return currentPatrimony;
        }
    }

    private HBox createCategoryBadge(CategoryEnum category, String text) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);

        Circle circle = new Circle(5);
        circle.setFill(Color.web(category.getColor()));

        Label label = new Label(text);
        label.setTooltip(new Tooltip(text)); // a long name that still gets cut is one hover away in full

        box.getChildren().addAll(circle, label);
        return box;
    }

    private static class AllocationRow {
        private final CategoryEnum category;
        private final SimpleStringProperty categoryProp;
        private final SimpleStringProperty valueProp;
        private final SimpleStringProperty percentageProp;

        AllocationRow(CategoryEnum category, String value, String percentage) {
            this.category = category;
            this.categoryProp = new SimpleStringProperty(category.getDisplayName());
            this.valueProp = new SimpleStringProperty(value);
            this.percentageProp = new SimpleStringProperty(percentage);
        }

        public SimpleStringProperty categoryProperty() { return categoryProp; }
        public SimpleStringProperty valueProperty()    { return valueProp; }
        public SimpleStringProperty percentageProperty(){ return percentageProp; }
        public CategoryEnum getCategory() { return category; }
    }

    private static class SuggestionRow {
        private final CategoryEnum category;
        private final SimpleStringProperty categoryProp;
        private final SimpleStringProperty actionProp;

        SuggestionRow(CategoryEnum category, String action) {
            this.category = category;
            this.categoryProp = new SimpleStringProperty(category.getDisplayName());
            this.actionProp   = new SimpleStringProperty(action);
        }

        public SimpleStringProperty categoryProperty() { return categoryProp; }
        public SimpleStringProperty actionProperty()   { return actionProp; }
        public CategoryEnum getCategory() { return category; }
    }
}

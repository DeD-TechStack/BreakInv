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
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.viewmodel.AllocationPlanCardController;
import com.daniel.presentation.viewmodel.AllocationPlanForm;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.UiExecutor;
import org.kordamp.ikonli.javafx.FontIcon;

public final class DiversificationPage implements Page {

    private final DailyTrackingUseCase daily;
    /**
     * Owns plan persistence rules: save+select in one transaction, ARCA fallback with an
     * explicit warning for an invalid selected plan, partial listing of invalid plans.
     */
    private final AllocationPlanCardController planCtrl;
    /** Profile delivered by the controller from the persisted selection (null = ARCA). */
    private Map<CategoryEnum, Double> activeProfile = ARCADiversificationStrategy.getARCAProfile();
    private final VBox root = new VBox(20);
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
    private final ToggleButton rebalanceByContributionRadio = new ToggleButton("Rebalancear por Aporte");
    private final ToggleButton rebalanceByTargetRadio = new ToggleButton("Patrimônio Alvo");

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

    private final TableView<AllocationRow> currentTable = new TableView<>();
    private final TableView<AllocationRow> idealTable = new TableView<>();
    private final TableView<SuggestionRow> suggestionsTable = new TableView<>();

    private final Label totalPatrimonyLabel = new Label("—");
    private final Label totalAporteLabel    = new Label();
    private final Label impliedTargetLabel  = new Label("—");
    private VBox impliedTargetBox;

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
        this.planCtrl = new AllocationPlanCardController(plans, worker, ui, new PlanCardView());

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Diversificação",
                "Analise e otimize a distribuição dos seus investimentos");

        VBox patrimonyBox = buildPatrimonyCard();

        // Controls row: method + calculation type side by side
        VBox methodBox = buildMethodSelector();
        VBox calculationBox = buildCalculationType();
        HBox.setHgrow(methodBox, Priority.ALWAYS);
        HBox.setHgrow(calculationBox, Priority.ALWAYS);
        HBox controlsRow = new HBox(12, calculationBox, methodBox);

        HBox tablesRow = new HBox(12);
        tablesRow.setAlignment(Pos.CENTER);
        VBox currentBox = buildCurrentAllocationTable();
        VBox idealBox = buildIdealAllocationTable();
        HBox.setHgrow(currentBox, Priority.ALWAYS);
        HBox.setHgrow(idealBox, Priority.ALWAYS);

        FontIcon arrowLabel = Icons.arrowRight();
        arrowLabel.getStyleClass().add("comparison-arrow");
        VBox arrowBox = new VBox(arrowLabel);
        arrowBox.setAlignment(Pos.CENTER);
        arrowBox.setMinWidth(32);

        tablesRow.getChildren().addAll(currentBox, arrowBox, idealBox);

        VBox suggestionsBox = buildSuggestionsTable();

        root.getChildren().addAll(header, patrimonyBox, noInvestmentsPanel, controlsRow, tablesRow, suggestionsBox);

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
        planCtrl.reload(true);
    }

    // Seletor de tipo de cálculo
    private VBox buildCalculationType() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("TIPO DE CÁLCULO");
        title.getStyleClass().add("card-title");

        rebalanceByContributionRadio.setToggleGroup(calculationTypeGroup);
        rebalanceByTargetRadio.setToggleGroup(calculationTypeGroup);
        rebalanceByContributionRadio.getStyleClass().add("seg-btn");
        rebalanceByTargetRadio.getStyleClass().add("seg-btn");
        rebalanceByContributionRadio.setSelected(true);

        HBox segCalc = new HBox(2, rebalanceByContributionRadio, rebalanceByTargetRadio);
        segCalc.getStyleClass().add("segmented");

        Label hint = new Label("Calcula o patrimônio alvo a partir da categoria mais pesada da carteira e sugere aportes para equilibrar — sem vender nenhum ativo.");
        hint.getStyleClass().add("text-helper");
        hint.setWrapText(true);

        // Target patrimony field (shown in target mode)
        Label targetLabel = new Label("Patrimônio Alvo:");
        targetLabel.getStyleClass().addAll("text-bold", "text-sm");
        targetPatrimonyField.setPromptText("R$ 100.000,00");
        targetPatrimonyField.setTextFormatter(Money.currencyFormatterEditable());
        Money.applyFormatOnBlur(targetPatrimonyField);

        VBox targetBox = new VBox(8, targetLabel, targetPatrimonyField);
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
        recalculateBtn.setOnAction(e -> planCtrl.refresh());

        box.getChildren().addAll(title, segCalc, hint, targetBox, recalculateBtn);
        return box;
    }

    private VBox buildMethodSelector() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("PLANO DE ALOCAÇÃO");
        title.getStyleClass().add("card-title");

        planCombo.setMaxWidth(Double.MAX_VALUE);
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
        newPlanBtn.setOnAction(e -> openPlanEditor(null));
        editPlanBtn.setOnAction(e -> {
            PlanChoice sel = planCombo.getValue();
            if (sel != null && sel.plan() != null) openPlanEditor(sel.plan());
        });
        deletePlanBtn.setOnAction(e -> deleteSelectedPlan());

        HBox row = new HBox(8, planCombo, newPlanBtn, editPlanBtn, deletePlanBtn);
        row.setAlignment(Pos.CENTER_LEFT);

        planHint.getStyleClass().add("text-helper");
        planHint.setWrapText(true);
        planEmptyHint.getStyleClass().add("text-helper");
        planEmptyHint.setWrapText(true);

        buildPlanEditor();
        planEditorBox.setVisible(false);
        planEditorBox.setManaged(false);

        box.getChildren().addAll(title, row, planHint, planEmptyHint, planEditorBox);
        updatePlanHint();
        return box;
    }

    private void buildPlanEditor() {
        planEditorBox.getStyleClass().add("panel");

        Label nameLabel = new Label("Nome do plano:");
        nameLabel.getStyleClass().addAll("text-bold", "text-sm");
        planNameField.setPromptText("Ex.: Conservador");

        Label customTitle = new Label("Pesos por categoria (duas casas decimais; soma 100,00%):");
        customTitle.getStyleClass().addAll("text-bold", "text-sm");

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
                "A an\u00e1lise voltar\u00e1 para a estrat\u00e9gia ARCA padr\u00e3o. Nenhum investimento \u00e9 alterado.")) return;
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
                planCombo.getItems().setAll(new PlanChoice(null));
                PlanChoice toSelect = planCombo.getItems().get(0);
                for (AllocationPlan p : all) {
                    PlanChoice c = new PlanChoice(p);
                    planCombo.getItems().add(c);
                    if (selectedId != null && selectedId.equals(p.id())) toSelect = c;
                }
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

    private VBox buildPatrimonyCard() {
        VBox box = new VBox(6);
        box.getStyleClass().add("hero-card");

        Label title = new Label("PATRIMÔNIO ATUAL");
        title.getStyleClass().add("kpi-label");
        totalPatrimonyLabel.getStyleClass().addAll("kpi-value", "num");
        Label sub = new Label("baseado em valores de hoje");
        sub.getStyleClass().add("kpi-sub");
        VBox currentBox = new VBox(4, title, totalPatrimonyLabel, sub);

        Label impliedTitle = new Label("PATRIMÔNIO ALVO CALCULADO");
        impliedTitle.getStyleClass().add("kpi-label");
        impliedTargetLabel.getStyleClass().addAll("kpi-value", "num");
        impliedTargetBox = new VBox(4, impliedTitle, impliedTargetLabel);
        impliedTargetBox.setVisible(false);
        impliedTargetBox.setManaged(false);

        HBox row = new HBox(40, currentBox, impliedTargetBox);
        box.getChildren().add(row);
        return box;
    }

    private VBox buildCurrentAllocationTable() {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");

        Label title = new Label("DISTRIBUIÇÃO ATUAL");
        title.getStyleClass().add("card-title");

        currentTable.getStyleClass().add("table-analytic");
        currentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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
            private final ProgressBar bar = new ProgressBar(0);
            { bar.setPrefHeight(4); bar.setMaxWidth(Double.MAX_VALUE); }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); } else {
                    try { bar.setProgress(Double.parseDouble(item.replace("%","").trim()) / 100.0); }
                    catch (Exception ignored) { bar.setProgress(0); }
                    Label lbl = new Label(item);
                    VBox cell = new VBox(2, lbl, bar);
                    setGraphic(cell); setText(null);
                }
            }
        });

        currentTable.getColumns().add(catCol);
        currentTable.getColumns().add(valueCol);
        currentTable.getColumns().add(percentCol);
        Label currentPh = new Label("Nenhum dado disponível");
        currentPh.getStyleClass().add("text-helper");
        currentTable.setPlaceholder(currentPh);

        box.getChildren().addAll(title, currentTable);
        VBox.setVgrow(currentTable, Priority.ALWAYS);
        return box;
    }

    private VBox buildIdealAllocationTable() {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");

        Label title = new Label("DISTRIBUIÇÃO IDEAL");
        title.getStyleClass().add("card-title");

        idealTable.getStyleClass().add("table-analytic");
        idealTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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
            private final ProgressBar bar = new ProgressBar(0);
            { bar.setPrefHeight(4); bar.setMaxWidth(Double.MAX_VALUE); }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); } else {
                    try { bar.setProgress(Double.parseDouble(item.replace("%","").trim()) / 100.0); }
                    catch (Exception ignored) { bar.setProgress(0); }
                    Label lbl = new Label(item);
                    VBox cell = new VBox(2, lbl, bar);
                    setGraphic(cell); setText(null);
                }
            }
        });

        idealTable.getColumns().add(catCol);
        idealTable.getColumns().add(valueCol);
        idealTable.getColumns().add(percentCol);
        Label idealPh = new Label("Selecione um método");
        idealPh.getStyleClass().add("text-helper");
        idealTable.setPlaceholder(idealPh);

        box.getChildren().addAll(title, idealTable);
        VBox.setVgrow(idealTable, Priority.ALWAYS);
        return box;
    }

    private VBox buildSuggestionsTable() {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");

        Label title = new Label("SUGESTÕES DE APORTE");
        title.getStyleClass().add("card-title");

        suggestionsTable.getStyleClass().add("table-analytic");
        suggestionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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

        TableColumn<SuggestionRow, String> actionCol = new TableColumn<>("Aporte Sugerido");
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
        VBox.setVgrow(suggestionsTable, Priority.ALWAYS);
        box.getChildren().addAll(title, suggestionsTable, totalAporteLabel);
        return box;
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
            List<InvestmentType> investments = daily.listTypes();
            if (investments.isEmpty()) {
                return new DivData(investments, Map.of(), 0);
            }
            Map<Long, Long> currentValues = daily.getAllCurrentValues(today);
            long totalPatrimony = daily.getTotalPatrimony(today);
            return new DivData(investments, currentValues, totalPatrimony);
        }, UiExecutor.get()).whenComplete((data, err) -> {
            if (refreshEpoch.get() != epoch) return;
            Platform.runLater(() -> {
                if (err != null) {
                    ToastHost.showError("N\u00e3o foi poss\u00edvel calcular a diversifica\u00e7\u00e3o: " + rootMessage(err));
                    return;
                }
                if (data.investments().isEmpty()) {
                    totalPatrimonyLabel.setText("\u2014");
                    currentTable.getItems().clear();
                    idealTable.getItems().clear();
                    suggestionsTable.getItems().clear();
                    noInvestmentsPanel.setVisible(true);
                    noInvestmentsPanel.setManaged(true);
                    return;
                }
                noInvestmentsPanel.setVisible(false);
                noInvestmentsPanel.setManaged(false);
                totalPatrimonyLabel.setText(daily.brl(data.totalPatrimony()));

                DiversificationData currentData = DiversificationCalculator.calculateCurrent(
                        data.investments(),
                        data.currentValues()
                );

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
                    String.format("%.1f%%", alloc.percentage())
            ));
        }

        currentTable.setItems(rows);
    }

    /** Ideal distribution and informative contribution suggestions for the given profile (RN10: never trades). */
    private void updateIdeal(long currentPatrimony, DiversificationData currentData, Map<CategoryEnum, Double> profile) {
        List<DiversificationSuggestion> suggestions;

        if (rebalanceByTargetRadio.isSelected()) {
            long targetPatrimony = getTargetPatrimony(currentPatrimony);
            suggestions = ARCADiversificationStrategy.calculateSuggestionsByTarget(
                    currentPatrimony, targetPatrimony, currentData.valuesCents(), profile
            );
            impliedTargetBox.setVisible(false);
            impliedTargetBox.setManaged(false);
        } else {
            suggestions = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                    currentPatrimony, currentData.valuesCents(), profile
            );
            long implied = ARCADiversificationStrategy.calculateImpliedTarget(
                    currentPatrimony, currentData.valuesCents(), profile);
            impliedTargetLabel.setText(daily.brl(implied));
            impliedTargetBox.setVisible(true);
            impliedTargetBox.setManaged(true);
        }

        var idealRows = FXCollections.<AllocationRow>observableArrayList();
        for (var sug : suggestions) {
            double targetPct = profile.getOrDefault(sug.category(), 0.0) * 100.0;
            idealRows.add(new AllocationRow(
                    sug.category(),
                    daily.brl(sug.idealCents()),
                    String.format("%.1f%%", targetPct)
            ));
        }
        idealTable.setItems(idealRows);

        var suggestionRows = FXCollections.<SuggestionRow>observableArrayList();
        for (var sug : suggestions) {
            if (sug.aporteNecessarioCents() > 100_00) {
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
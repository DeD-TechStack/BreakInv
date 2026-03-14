package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.service.ARCADiversificationStrategy;
import com.daniel.core.service.ARCADiversificationStrategy.*;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.DiversificationCalculator;
import com.daniel.core.service.DiversificationCalculator.*;
import com.daniel.core.util.Money;
import com.daniel.presentation.view.PageHeader;

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

public final class DiversificationPage implements Page {

    private final DailyTrackingUseCase daily;
    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();

    private final ToggleGroup methodGroup = new ToggleGroup();
    private final ToggleButton arcaRadio = new ToggleButton("ARCA (Primo Rico)");
    private final ToggleButton customRadio = new ToggleButton("Personalizado");

    private final ToggleGroup calculationTypeGroup = new ToggleGroup();
    private final ToggleButton rebalanceByContributionRadio = new ToggleButton("Rebalancear por Aporte");
    private final ToggleButton rebalanceByTargetRadio = new ToggleButton("Patrimônio Alvo");

    private final TextField targetPatrimonyField = new TextField();
    private final VBox customInputsBox = new VBox(12);
    // customInputsBox gets .panel class in buildCustomInputs()
    private final Map<CategoryEnum, TextField> customPercentages = new HashMap<>();

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
        this.daily = dailyTrackingUseCase;

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

        Label arrowLabel = new Label("→");
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
        refreshData();
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
            refreshData();
        });

        Button recalculateBtn = new Button("Recalcular");
        recalculateBtn.getStyleClass().add("button");
        recalculateBtn.setOnAction(e -> refreshData());

        box.getChildren().addAll(title, segCalc, hint, targetBox, recalculateBtn);
        return box;
    }

    private VBox buildMethodSelector() {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");

        Label title = new Label("MÉTODO DE DIVERSIFICAÇÃO");
        title.getStyleClass().add("card-title");

        arcaRadio.setToggleGroup(methodGroup);
        customRadio.setToggleGroup(methodGroup);
        arcaRadio.getStyleClass().add("seg-btn");
        customRadio.getStyleClass().add("seg-btn");
        arcaRadio.setSelected(true);

        HBox segMethod = new HBox(2, arcaRadio, customRadio);
        segMethod.getStyleClass().add("segmented");

        Label arcaHint = new Label("Renda Fixa 40% • Ações 30% • Outros 25% • Cripto 5%");
        arcaHint.getStyleClass().add("text-helper");

        buildCustomInputs();
        customInputsBox.setVisible(false);
        customInputsBox.setManaged(false);

        methodGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) { if (oldVal != null) oldVal.setSelected(true); return; }
            boolean isCustom = newVal == customRadio;
            customInputsBox.setVisible(isCustom);
            customInputsBox.setManaged(isCustom);
            arcaHint.setVisible(!isCustom);
            arcaHint.setManaged(!isCustom);
            refreshData();
        });

        box.getChildren().addAll(title, segMethod, arcaHint, customInputsBox);
        return box;
    }

    private void buildCustomInputs() {
        customInputsBox.getStyleClass().add("panel");

        Label customTitle = new Label("Configure as porcentagens desejadas:");
        customTitle.getStyleClass().addAll("text-bold", "text-sm");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("rate-grid");

        int row = 0;
        for (CategoryEnum cat : CategoryEnum.values()) {
            Circle circle = new Circle(5);
            circle.setFill(Color.web(cat.getColor()));

            Label label = new Label(cat.getDisplayName());

            TextField field = new TextField("0.0");
            field.setPrefWidth(80);
            field.setPromptText("0.0");

            Label percent = new Label("%");

            customPercentages.put(cat, field);

            grid.add(circle, 0, row);
            grid.add(label, 1, row);
            grid.add(field, 2, row);
            grid.add(percent, 3, row);

            row++;
        }

        Label hint = new Label("💡 As porcentagens devem somar 100%");
        hint.getStyleClass().add("text-helper");

        customInputsBox.getChildren().addAll(customTitle, grid, hint);
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

        currentTable.getColumns().setAll(catCol, valueCol, percentCol);
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

        idealTable.getColumns().setAll(catCol, valueCol, percentCol);
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

        suggestionsTable.getColumns().setAll(catCol, actionCol);
        Label suggPh = new Label("Sua carteira está perfeitamente balanceada!");
        suggPh.getStyleClass().add("text-helper");
        suggestionsTable.setPlaceholder(suggPh);

        totalAporteLabel.getStyleClass().addAll("text-helper", "text-bold");
        VBox.setVgrow(suggestionsTable, Priority.ALWAYS);
        box.getChildren().addAll(title, suggestionsTable, totalAporteLabel);
        return box;
    }

    private VBox buildNoInvestmentsPanel() {
        VBox box = new VBox(8);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        Label icon = new Label("📊");
        icon.getStyleClass().add("empty-icon");
        Label title = new Label("Nenhum investimento cadastrado");
        title.getStyleClass().add("empty-title");
        Label hint = new Label("Cadastre seus ativos em \"Meus Investimentos\" para ver a análise de diversificação.");
        hint.getStyleClass().add("empty-hint");
        hint.setWrapText(true);
        box.getChildren().addAll(icon, title, hint);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    private void refreshData() {
        LocalDate today = LocalDate.now();
        List<InvestmentType> investments = daily.listTypes();

        if (investments.isEmpty()) {
            totalPatrimonyLabel.setText("—");
            currentTable.getItems().clear();
            idealTable.getItems().clear();
            suggestionsTable.getItems().clear();
            noInvestmentsPanel.setVisible(true);
            noInvestmentsPanel.setManaged(true);
            return;
        }
        noInvestmentsPanel.setVisible(false);
        noInvestmentsPanel.setManaged(false);

        Map<Long, Long> currentValues = daily.getAllCurrentValues(today);
        long totalPatrimony = daily.getTotalPatrimony(today);

        totalPatrimonyLabel.setText(daily.brl(totalPatrimony));

        DiversificationData currentData = DiversificationCalculator.calculateCurrent(
                investments,
                currentValues
        );

        updateCurrentTable(currentData);

        if (arcaRadio.isSelected()) {
            updateARCAIdeal(totalPatrimony, currentData);
        } else {
            updateCustomIdeal(totalPatrimony, currentData);
        }
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

    private void updateARCAIdeal(long currentPatrimony, DiversificationData currentData) {
        Map<CategoryEnum, Double> profile = ARCADiversificationStrategy.getARCAProfile();

        List<DiversificationSuggestion> suggestions;
        long referencePatrimony;

        // Escolher método de cálculo
        if (rebalanceByTargetRadio.isSelected()) {
            long targetPatrimony = getTargetPatrimony(currentPatrimony);
            referencePatrimony = targetPatrimony;
            suggestions = ARCADiversificationStrategy.calculateSuggestionsByTarget(
                    currentPatrimony, targetPatrimony, currentData.valuesCents(), profile
            );
            impliedTargetBox.setVisible(false);
            impliedTargetBox.setManaged(false);
        } else {
            referencePatrimony = currentPatrimony;
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
            suggestionRows.add(new SuggestionRow(CategoryEnum.RENDA_FIXA, "✅ Carteira balanceada"));
        }

        suggestionsTable.setItems(suggestionRows);

        long totalAporte = suggestions.stream().mapToLong(s -> s.aporteNecessarioCents()).sum();
        totalAporteLabel.setText(totalAporte > 0 ? "Aporte total sugerido: " + daily.brl(totalAporte) : "");
    }

    private void updateCustomIdeal(long currentPatrimony, DiversificationData currentData) {
        double total = 0;
        Map<CategoryEnum, Double> customProfile = new HashMap<>();

        try {
            for (var entry : customPercentages.entrySet()) {
                double value = Double.parseDouble(entry.getValue().getText().replace(",", "."));
                customProfile.put(entry.getKey(), value / 100.0);
                total += value;
            }

            if (Math.abs(total - 100.0) > 0.1) {
                Label errPh1 = new Label("⚠️ As porcentagens devem somar 100%!");
                errPh1.getStyleClass().add("status-warning");
                idealTable.setPlaceholder(errPh1);
                idealTable.getItems().clear();
                suggestionsTable.getItems().clear();
                return;
            }

            List<DiversificationSuggestion> suggestions;
            long referencePatrimony;

            if (rebalanceByTargetRadio.isSelected()) {
                long targetPatrimony = getTargetPatrimony(currentPatrimony);
                referencePatrimony = targetPatrimony;
                suggestions = ARCADiversificationStrategy.calculateSuggestionsByTarget(
                        currentPatrimony, targetPatrimony, currentData.valuesCents(), customProfile
                );
                impliedTargetBox.setVisible(false);
                impliedTargetBox.setManaged(false);
            } else {
                referencePatrimony = currentPatrimony;
                suggestions = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                        currentPatrimony, currentData.valuesCents(), customProfile
                );
                long implied = ARCADiversificationStrategy.calculateImpliedTarget(
                        currentPatrimony, currentData.valuesCents(), customProfile);
                impliedTargetLabel.setText(daily.brl(implied));
                impliedTargetBox.setVisible(true);
                impliedTargetBox.setManaged(true);
            }

            var idealRows = FXCollections.<AllocationRow>observableArrayList();
            for (var sug : suggestions) {
                double targetPct = customProfile.getOrDefault(sug.category(), 0.0) * 100.0;
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
                suggestionRows.add(new SuggestionRow(CategoryEnum.RENDA_FIXA, "✅ Carteira balanceada"));
            }

            suggestionsTable.setItems(suggestionRows);

            long totalAporte = suggestions.stream().mapToLong(s -> s.aporteNecessarioCents()).sum();
            totalAporteLabel.setText(totalAporte > 0 ? "Aporte total sugerido: " + daily.brl(totalAporte) : "");

        } catch (NumberFormatException e) {
            Label errPh2 = new Label("⚠️ Valores inválidos nas porcentagens");
            errPh2.getStyleClass().add("status-warning");
            idealTable.setPlaceholder(errPh2);
            idealTable.getItems().clear();
            suggestionsTable.getItems().clear();
        }
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
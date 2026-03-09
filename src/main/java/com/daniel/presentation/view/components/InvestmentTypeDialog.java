package com.daniel.presentation.view.components;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.Enums.LiquidityEnum;
import com.daniel.core.domain.entity.Enums.InvestmentTypeEnum;
import com.daniel.core.domain.entity.Enums.IndexTypeEnum;
import com.daniel.core.util.Money;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.presentation.view.components.ToastHost;

import com.daniel.presentation.view.util.DialogChrome;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

public final class InvestmentTypeDialog extends Dialog<InvestmentTypeDialog.InvestmentTypeData> {

    private enum RentabilityMode {
        FIXED_RATE("Taxa Fixa (% a.a.)"),
        BENCHMARK_PERCENT("% do Benchmark"),
        HYBRID("Híbrida (Índice + Taxa)");

        private final String display;
        RentabilityMode(String display) { this.display = display; }
        public String getDisplay() { return display; }
    }

    private final TextField nameField = new TextField();
    private final ComboBox<CategoryEnum> categoryCombo = new ComboBox<>();
    private final ComboBox<LiquidityEnum> liquidityCombo = new ComboBox<>();
    private final DatePicker datePicker = new DatePicker();

    private final ComboBox<RentabilityMode> rentabilityModeCombo = new ComboBox<>();
    private RentabilityMode currentRentabilityMode = RentabilityMode.FIXED_RATE;

    private final TextField profitabilityField = new TextField();
    private final ComboBox<String> benchmarkCombo = new ComboBox<>();
    private final TextField benchmarkPercentField = new TextField();
    private final TextField hybridFixedField = new TextField();
    private final TextField hybridIndexField = new TextField();

    private final TextField investedValueField = new TextField();
    private final Label autoFillLabel = new Label("✓ Calculado automaticamente");

    private final ComboBox<InvestmentTypeEnum> typeCombo = new ComboBox<>();
    private final ComboBox<IndexTypeEnum> indexCombo = new ComboBox<>();
    private final TextField indexPercentageField = new TextField();

    private final TickerAutocompleteField tickerField = new TickerAutocompleteField();
    private final TextField purchasePriceField = new TextField();
    private final TextField quantityField = new TextField();

    // Container VBoxes for visibility control
    private VBox indexFieldsBox;
    private VBox rentabilitySection;
    private VBox ativoCard;
    private VBox fixedRateBox;
    private VBox benchmarkBox;
    private VBox hybridBox;

    private Timer debounceTimer;
    private final boolean isEdit;

    public InvestmentTypeDialog(String title, InvestmentTypeData existing) {
        this.isEdit = existing != null;

        setTitle(title);
        setHeaderText(null);

        // Apply dark theme
        getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());
        getDialogPane().getStyleClass().add("dark-dialog");

        ButtonType confirmButton = new ButtonType(isEdit ? "Atualizar" : "Criar", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(confirmButton, ButtonType.CANCEL);

        // Size
        getDialogPane().setMinWidth(680);
        getDialogPane().setPrefWidth(720);
        getDialogPane().setMinHeight(600);
        getDialogPane().setPrefHeight(650);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab tab1 = new Tab("Dados Básicos", buildBasicTab());
        Tab tab2 = new Tab("Tipo & Rentabilidade", buildTypeTab());
        tabPane.getTabs().addAll(tab1, tab2);

        // ── Dialog header with title + close button ──
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title-label");
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("icon-btn");
        closeBtn.setOnAction(e -> close());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(titleLabel, headerSpacer, closeBtn);
        headerRow.getStyleClass().add("dialog-header-row");

        // ── Remove OS chrome, enable drag-to-move via header ──
        DialogChrome.apply(this, headerRow);

        VBox dialogContent = new VBox(0, headerRow, tabPane);
        getDialogPane().setContent(dialogContent);

        // ── Dim overlay lifecycle ──
        showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) ToastHost.showDim();
            else ToastHost.hideDim();
        });

        if (existing != null) {
            fillExistingData(existing);
        } else {
            datePicker.setValue(LocalDate.now());
        }

        // Listeners
        typeCombo.valueProperty().addListener((o, a, b) -> updateTypeVisibility());
        categoryCombo.valueProperty().addListener((o, a, b) -> {
            updateRentabilityVisibility();
            updateTypeComboItems();
        });

        purchasePriceField.textProperty().addListener((obs, oldVal, newVal) -> scheduleAutoFill());
        quantityField.textProperty().addListener((obs, oldVal, newVal) -> scheduleAutoFill());
        purchasePriceField.setOnAction(e -> updateInvestedValueForStock());
        quantityField.setOnAction(e -> updateInvestedValueForStock());

        tickerField.textProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.length() >= 4) {
                loadStockDataFromBrapi(newVal);
            }
        });

        rentabilityModeCombo.valueProperty().addListener((obs, old, newVal) -> {
            currentRentabilityMode = newVal;
            updateRentabilityModeInputs();
        });

        setResultConverter(buttonType -> {
            if (buttonType == confirmButton) return buildResult();
            return null;
        });

        Button btn = (Button) getDialogPane().lookupButton(confirmButton);
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!validate()) event.consume();
        });

        // Style buttons after layout
        Platform.runLater(() -> {
            Button confirmBtn = (Button) getDialogPane().lookupButton(confirmButton);
            if (confirmBtn != null) confirmBtn.getStyleClass().add("primary-btn");
            Button cancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancelBtn != null) cancelBtn.getStyleClass().add("ghost-btn");
        });

        updateTypeVisibility();
        updateRentabilityVisibility();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private VBox buildCard(String title, Node... children) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        if (title != null && !title.isBlank()) {
            Label lbl = new Label(title);
            lbl.getStyleClass().add("card-title");
            card.getChildren().add(lbl);
        }
        card.getChildren().addAll(children);
        return card;
    }

    private ScrollPane wrapInScroll(VBox content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        return sp;
    }

    private void scheduleAutoFill() {
        if (debounceTimer != null) debounceTimer.cancel();
        debounceTimer = new Timer();
        debounceTimer.schedule(new TimerTask() {
            @Override public void run() {
                Platform.runLater(() -> updateInvestedValueForStock());
            }
        }, 500);
    }

    // ── Tab builders ─────────────────────────────────────────────────────────

    private ScrollPane buildBasicTab() {
        // Card 1: Identificação
        Label nameLabel = new Label("Nome do Investimento *");
        nameLabel.getStyleClass().add("form-label");
        nameField.setPromptText("Ex: Tesouro Selic 2027, Ações PETR4...");
        VBox identCard = buildCard("Identificação", nameLabel, nameField);

        // Card 2: Classificação (categoria + liquidez lado a lado)
        categoryCombo.getItems().addAll(CategoryEnum.values());
        categoryCombo.setPromptText("Selecione a categoria");
        categoryCombo.setCellFactory(lv -> new CategoryCell());
        categoryCombo.setButtonCell(new CategoryCell());
        categoryCombo.setMaxWidth(Double.MAX_VALUE);

        liquidityCombo.getItems().addAll(LiquidityEnum.values());
        liquidityCombo.setPromptText("Selecione a liquidez");
        liquidityCombo.setCellFactory(lv -> new LiquidityCell());
        liquidityCombo.setButtonCell(new LiquidityCell());
        liquidityCombo.setMaxWidth(Double.MAX_VALUE);

        Label catLabel2 = new Label("Categoria *");
        catLabel2.getStyleClass().add("form-label");
        Label liqLabel2 = new Label("Liquidez *");
        liqLabel2.getStyleClass().add("form-label");
        VBox catBox = new VBox(4, catLabel2, categoryCombo);
        VBox liqBox = new VBox(4, liqLabel2, liquidityCombo);
        HBox.setHgrow(catBox, Priority.ALWAYS);
        HBox.setHgrow(liqBox, Priority.ALWAYS);
        HBox classifRow = new HBox(12, catBox, liqBox);

        VBox classifCard = buildCard("Classificação", classifRow);

        // Card 3: Data
        Label dateLabel = new Label("Data do Investimento *");
        dateLabel.getStyleClass().add("form-label");
        datePicker.setPromptText("Selecione a data");
        datePicker.setMaxWidth(Double.MAX_VALUE);
        VBox dateCard = buildCard("Data", dateLabel, datePicker);

        VBox content = new VBox(14, identCard, classifCard, dateCard);
        content.setPadding(new Insets(14));

        return wrapInScroll(content);
    }

    private ScrollPane buildTypeTab() {
        // ── Card 1: Tipo de Investimento ──
        typeCombo.getItems().addAll(InvestmentTypeEnum.values());
        typeCombo.setPromptText("Selecione o tipo");
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(InvestmentTypeEnum item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : typeDisplayName(item));
            }
        });
        typeCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(InvestmentTypeEnum item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : typeDisplayName(item));
            }
        });

        // Index fields (visible only for POS_FIXADO / HIBRIDO)
        indexCombo.getItems().addAll(IndexTypeEnum.values());
        indexCombo.setPromptText("CDI, Selic, IPCA");
        indexCombo.setMaxWidth(Double.MAX_VALUE);
        indexPercentageField.setPromptText("Ex: 110 (para 110% do índice)");

        Label indexLabel2 = new Label("Índice");
        indexLabel2.getStyleClass().add("form-label");
        Label indexPctLabel = new Label("Percentual do Índice (%)");
        indexPctLabel.getStyleClass().add("form-label");
        Label indexPctHint = new Label("Informe o percentual em relação ao índice. Ex: 110 = 110% do CDI.");
        indexPctHint.getStyleClass().add("text-helper");
        indexPctHint.setWrapText(true);

        indexFieldsBox = new VBox(8,
                indexLabel2, indexCombo,
                indexPctLabel, indexPercentageField, indexPctHint);
        indexFieldsBox.setVisible(false);
        indexFieldsBox.setManaged(false);

        Label typeLbl = new Label("Tipo *");
        typeLbl.getStyleClass().add("form-label");
        VBox typeCard = buildCard("Tipo de Investimento",
                typeLbl, typeCombo, indexFieldsBox);

        // ── Card 2: Rentabilidade ──
        rentabilityModeCombo.getItems().addAll(RentabilityMode.values());
        rentabilityModeCombo.setValue(RentabilityMode.FIXED_RATE);
        rentabilityModeCombo.setMaxWidth(Double.MAX_VALUE);
        rentabilityModeCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(RentabilityMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplay());
            }
        });
        rentabilityModeCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(RentabilityMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplay());
            }
        });

        // Fixed rate sub-box
        profitabilityField.setPromptText("Ex: 13.75");
        profitabilityField.setTextFormatter(createDecimalFormatter());
        Label rentAbLabel = new Label("Rentabilidade Anual (%)");
        rentAbLabel.getStyleClass().add("form-label");
        fixedRateBox = new VBox(6, rentAbLabel, profitabilityField);

        // Benchmark sub-box
        benchmarkCombo.getItems().addAll("CDI", "SELIC", "IPCA");
        benchmarkCombo.setValue("CDI");
        benchmarkCombo.setMaxWidth(Double.MAX_VALUE);
        benchmarkPercentField.setPromptText("Ex: 110 (= 110% do CDI)");
        Label benchLabel2 = new Label("Benchmark");
        benchLabel2.getStyleClass().add("form-label");
        Label benchPctLabel2 = new Label("Percentual do Benchmark (%)");
        benchPctLabel2.getStyleClass().add("form-label");
        benchmarkBox = new VBox(6,
                benchLabel2, benchmarkCombo,
                benchPctLabel2, benchmarkPercentField);

        // Hybrid sub-box
        hybridFixedField.setPromptText("Ex: 5.0");
        hybridIndexField.setPromptText("Ex: 4.5 (IPCA estimado)");
        Label hybFixLabel = new Label("Taxa Fixa (% a.a.)");
        hybFixLabel.getStyleClass().add("form-label");
        Label hybIdxLabel = new Label("Taxa do Índice (% a.a.)");
        hybIdxLabel.getStyleClass().add("form-label");
        hybridBox = new VBox(6,
                hybFixLabel, hybridFixedField,
                hybIdxLabel, hybridIndexField);

        Label profitHint = new Label("💡 Não aplicável para Ações, FIIs e Criptomoedas.");
        profitHint.getStyleClass().add("text-helper");
        profitHint.setWrapText(true);

        Label modalidadeLabel = new Label("Modalidade:");
        modalidadeLabel.getStyleClass().add("form-label");
        rentabilitySection = new VBox(10,
                modalidadeLabel, rentabilityModeCombo,
                fixedRateBox, benchmarkBox, hybridBox,
                profitHint);

        VBox rentCard = buildCard("Rentabilidade", rentabilitySection);

        // ── Card 3: Ativo (apenas ACAO) ──
        purchasePriceField.setPromptText("R$ 35,50");
        purchasePriceField.setTextFormatter(Money.currencyFormatterEditable());
        quantityField.setPromptText("100");

        Label tickerLbl = new Label("Ticker");
        tickerLbl.getStyleClass().add("form-label");
        Label priceLbl = new Label("Preço de Compra (unitário)");
        priceLbl.getStyleClass().add("form-label");
        Label qtyLbl = new Label("Quantidade");
        qtyLbl.getStyleClass().add("form-label");
        ativoCard = buildCard("Ativo",
                tickerLbl, tickerField,
                priceLbl, purchasePriceField,
                qtyLbl, quantityField);
        ativoCard.setVisible(false);
        ativoCard.setManaged(false);

        // ── Card 4: Valor Investido ──
        investedValueField.setPromptText("R$ 0,00");
        investedValueField.setTextFormatter(Money.currencyFormatterEditable());
        Money.applyFormatOnBlur(investedValueField);

        autoFillLabel.getStyleClass().addAll("text-xs", "state-positive");
        autoFillLabel.setVisible(false);
        autoFillLabel.setManaged(false);

        Label valueHint = new Label("💡 Para ações/FIIs, preenchido automaticamente (Preço × Quantidade)");
        valueHint.getStyleClass().add("text-helper");
        valueHint.setWrapText(true);

        Label valueLbl = new Label("Valor Investido *");
        valueLbl.getStyleClass().add("form-label");
        VBox valueCard = buildCard("Valor Investido",
                valueLbl, investedValueField,
                autoFillLabel, valueHint);

        VBox content = new VBox(14, typeCard, rentCard, ativoCard, valueCard);
        content.setPadding(new Insets(14));

        return wrapInScroll(content);
    }

    // ── Visibility updaters ───────────────────────────────────────────────────

    private void updateTypeVisibility() {
        InvestmentTypeEnum type = typeCombo.getValue();

        setVisible(indexFieldsBox, false);
        setVisible(ativoCard, false);

        if (type == null) return;

        switch (type) {
            case POS_FIXADO, HIBRIDO -> setVisible(indexFieldsBox, true);
            case ACAO -> setVisible(ativoCard, true);
            case PREFIXADO, FUNDO -> { /* no extra fields */ }
        }
    }

    private void updateRentabilityVisibility() {
        CategoryEnum cat = categoryCombo.getValue();
        if (cat == null) return;

        boolean isVariableIncome = cat == CategoryEnum.ACOES ||
                cat == CategoryEnum.FUNDOS_IMOBILIARIOS ||
                cat == CategoryEnum.CRIPTOMOEDAS;

        setVisible(rentabilitySection, !isVariableIncome);

        if (!isVariableIncome) {
            updateRentabilityModeInputs();
        }
    }

    private void updateRentabilityModeInputs() {
        setVisible(fixedRateBox, false);
        setVisible(benchmarkBox, false);
        setVisible(hybridBox, false);

        if (currentRentabilityMode == null) return;

        switch (currentRentabilityMode) {
            case FIXED_RATE     -> setVisible(fixedRateBox, true);
            case BENCHMARK_PERCENT -> setVisible(benchmarkBox, true);
            case HYBRID         -> setVisible(hybridBox, true);
        }
    }

    private static void setVisible(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void updateTypeComboItems() {
        CategoryEnum cat = categoryCombo.getValue();
        InvestmentTypeEnum previousSelection = typeCombo.getValue();
        typeCombo.getItems().clear();

        if (cat == null) {
            typeCombo.getItems().addAll(InvestmentTypeEnum.values());
        } else {
            switch (cat) {
                case RENDA_FIXA, PREVIDENCIA -> typeCombo.getItems().addAll(
                        InvestmentTypeEnum.PREFIXADO, InvestmentTypeEnum.POS_FIXADO, InvestmentTypeEnum.HIBRIDO);
                case ACOES, FUNDOS_IMOBILIARIOS, CRIPTOMOEDAS -> typeCombo.getItems().add(InvestmentTypeEnum.ACAO);
                case FUNDOS -> typeCombo.getItems().add(InvestmentTypeEnum.FUNDO);
                case OUTROS -> typeCombo.getItems().addAll(InvestmentTypeEnum.values());
            }
        }

        if (previousSelection != null && typeCombo.getItems().contains(previousSelection)) {
            typeCombo.setValue(previousSelection);
        } else if (typeCombo.getItems().size() == 1) {
            typeCombo.setValue(typeCombo.getItems().get(0));
        }
    }

    private String typeDisplayName(InvestmentTypeEnum type) {
        return switch (type) {
            case PREFIXADO -> "Prefixado";
            case POS_FIXADO -> "Pós-Fixado";
            case HIBRIDO -> "Híbrido";
            case ACAO -> "Ação";
            case FUNDO -> "Fundo de Investimento";
        };
    }

    // ── Auto-fill ────────────────────────────────────────────────────────────

    private void updateInvestedValueForStock() {
        String priceText = purchasePriceField.getText();
        String qtyText = quantityField.getText();

        if (priceText == null || priceText.isBlank() || priceText.equals("R$ 0,00")) return;
        if (qtyText == null || qtyText.isBlank() || qtyText.equals("0")) return;

        long priceCents = Money.textToCentsOrZero(priceText);
        if (priceCents == 0) return;

        String cleanQty = qtyText.trim().replaceAll("[^0-9]", "");
        if (cleanQty.isEmpty()) return;

        int quantity = Integer.parseInt(cleanQty);
        if (quantity == 0) return;

        long totalCents = priceCents * quantity;
        double totalValue = totalCents / 100.0;
        String formatted = String.format("%.2f", totalValue).replace('.', ',');

        investedValueField.clear();
        Platform.runLater(() -> {
            investedValueField.setText(formatted);
            investedValueField.positionCaret(formatted.length());
            setVisible(autoFillLabel, true);
        });
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
                    if (purchasePriceField.getText().isBlank()) {
                        purchasePriceField.setText(Money.centsToText((long)(data.regularMarketPrice() * 100)));
                    }
                });
            }
        });
    }

    // ── Fill / validate / build result ───────────────────────────────────────

    private void fillExistingData(InvestmentTypeData data) {
        nameField.setText(data.name());

        if (data.category() != null) {
            try { categoryCombo.setValue(CategoryEnum.valueOf(data.category())); } catch (Exception ignored) {}
        }
        if (data.liquidity() != null) {
            try { liquidityCombo.setValue(LiquidityEnum.valueOf(data.liquidity())); } catch (Exception ignored) {}
        }
        if (data.investmentDate() != null) {
            datePicker.setValue(data.investmentDate());
        }
        if (data.profitability() != null) {
            profitabilityField.setText(data.profitability().toString());
        }
        if (data.investedValue() != null) {
            long cents = data.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
            investedValueField.setText(Money.centsToText(cents));
        }
        if (data.typeOfInvestment() != null) {
            try { typeCombo.setValue(InvestmentTypeEnum.valueOf(data.typeOfInvestment())); } catch (Exception ignored) {}
        }
        if (data.indexType() != null) {
            try { indexCombo.setValue(IndexTypeEnum.valueOf(data.indexType())); } catch (Exception ignored) {}
        }
        if (data.indexPercentage() != null) {
            indexPercentageField.setText(data.indexPercentage().toString());
        }
        if (data.ticker() != null) {
            tickerField.setText(data.ticker());
        }
        if (data.purchasePrice() != null) {
            long cents = data.purchasePrice().multiply(BigDecimal.valueOf(100)).longValue();
            purchasePriceField.setText(Money.centsToText(cents));
        }
        if (data.quantity() != null) {
            quantityField.setText(data.quantity().toString());
        }
    }

    private boolean validate() {
        StringBuilder errors = new StringBuilder();

        if (nameField.getText().isBlank()) errors.append("• Nome é obrigatório\n");
        if (categoryCombo.getValue() == null) errors.append("• Categoria é obrigatória\n");
        if (liquidityCombo.getValue() == null) errors.append("• Liquidez é obrigatória\n");
        if (datePicker.getValue() == null) errors.append("• Data é obrigatória\n");

        CategoryEnum selectedCategory = categoryCombo.getValue();
        if (selectedCategory != null) {
            boolean isVariableIncome = selectedCategory == CategoryEnum.ACOES ||
                    selectedCategory == CategoryEnum.FUNDOS_IMOBILIARIOS ||
                    selectedCategory == CategoryEnum.CRIPTOMOEDAS;
            boolean isFunds = selectedCategory == CategoryEnum.FUNDOS;

            if (!isVariableIncome && !isFunds && profitabilityField.getText().isBlank() &&
                    currentRentabilityMode == RentabilityMode.FIXED_RATE) {
                errors.append("• Rentabilidade é obrigatória para ").append(selectedCategory.getDisplayName()).append("\n");
            }
        }

        if (investedValueField.getText().isBlank()) errors.append("• Valor é obrigatório\n");

        if (errors.length() > 0) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Validação");
            alert.setHeaderText("Corrija os erros:");
            alert.setContentText(errors.toString());
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/styles/app.css").toExternalForm());
            alert.getDialogPane().getStyleClass().addAll("dark-dialog", "dark-dialog-simple");
            alert.initStyle(javafx.stage.StageStyle.TRANSPARENT);
            alert.setOnShowing(ev -> {
                javafx.scene.Scene sc = alert.getDialogPane().getScene();
                if (sc != null) sc.setFill(Color.TRANSPARENT);
                ToastHost.showDim();
            });
            alert.setOnHidden(ev -> ToastHost.hideDim());
            alert.showAndWait();
            return false;
        }

        return true;
    }

    private InvestmentTypeData buildResult() {
        String name = nameField.getText().trim();
        String category = categoryCombo.getValue().name();
        String liquidity = liquidityCombo.getValue().name();
        LocalDate date = datePicker.getValue();

        BigDecimal profitability = null;
        if (!profitabilityField.getText().isBlank()) {
            profitability = new BigDecimal(profitabilityField.getText().replace(",", "."));
        }

        long cents = Money.textToCentsOrZero(investedValueField.getText());
        BigDecimal investedValue = BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100));

        String typeOfInv = typeCombo.getValue() != null ? typeCombo.getValue().name() : null;
        String indexType = indexCombo.getValue() != null ? indexCombo.getValue().name() : null;

        BigDecimal indexPerc = null;
        if (!indexPercentageField.getText().isBlank()) {
            indexPerc = new BigDecimal(indexPercentageField.getText().replace(",", "."));
        }

        String ticker = tickerField.getText().isBlank() ? null : tickerField.getText().trim().toUpperCase();

        BigDecimal purchasePrice = null;
        if (!purchasePriceField.getText().isBlank()) {
            long priceCents = Money.textToCentsOrZero(purchasePriceField.getText());
            purchasePrice = BigDecimal.valueOf(priceCents).divide(BigDecimal.valueOf(100));
        }

        Integer quantity = null;
        if (!quantityField.getText().isBlank()) {
            String cleanQty = quantityField.getText().trim().replaceAll("[^0-9]", "");
            if (!cleanQty.isEmpty()) quantity = Integer.parseInt(cleanQty);
        }

        return new InvestmentTypeData(
                name, category, liquidity, date, profitability, investedValue,
                typeOfInv, indexType, indexPerc, ticker, purchasePrice, quantity
        );
    }

    // ── Formatters ───────────────────────────────────────────────────────────

    private TextFormatter<String> createDecimalFormatter() {
        return new TextFormatter<>(change -> {
            String text = change.getControlNewText();
            if (text.matches("\\d*[.,]?\\d{0,2}")) return change;
            return null;
        });
    }

    // ── List cells ───────────────────────────────────────────────────────────

    private static class CategoryCell extends ListCell<CategoryEnum> {
        @Override
        protected void updateItem(CategoryEnum item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null); setText(null);
            } else {
                Circle circle = new Circle(5);
                circle.setFill(Color.web(item.getColor()));
                HBox box = new HBox(8, circle, new Label(item.getDisplayName()));
                box.getStyleClass().add("cell-left");
                setGraphic(box); setText(null);
            }
        }
    }

    private static class LiquidityCell extends ListCell<LiquidityEnum> {
        @Override
        protected void updateItem(LiquidityEnum item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null); setText(null);
            } else {
                Circle circle = new Circle(5);
                circle.setFill(Color.web(item.getColor()));
                HBox box = new HBox(8, circle, new Label(item.getDisplayName()));
                box.getStyleClass().add("cell-left");
                setGraphic(box); setText(null);
            }
        }
    }

    // ── Data record ──────────────────────────────────────────────────────────

    public record InvestmentTypeData(
            String name,
            String category,
            String liquidity,
            LocalDate investmentDate,
            BigDecimal profitability,
            BigDecimal investedValue,
            String typeOfInvestment,
            String indexType,
            BigDecimal indexPercentage,
            String ticker,
            BigDecimal purchasePrice,
            Integer quantity
    ) {}
}

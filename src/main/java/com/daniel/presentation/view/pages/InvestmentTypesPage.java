package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.Enums.LiquidityEnum;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.PageHeader;
import com.daniel.core.util.Money;
import com.daniel.presentation.view.components.ColorBadge;
import com.daniel.presentation.view.components.InvestmentTypeDialog;
import com.daniel.presentation.view.components.InvestmentTypeDialog.InvestmentTypeData;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class InvestmentTypesPage implements Page {

    private final DailyTrackingUseCase daily;
    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();
    private final TableView<InvestmentType> table = new TableView<>();

    private final ObservableList<InvestmentType> allItems = FXCollections.observableArrayList();
    private FilteredList<InvestmentType> filteredItems;

    private final TextField searchField = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();

    // Column refs for adaptive visibility (TASK 29)
    private TableColumn<InvestmentType, String> catColRef;
    private TableColumn<InvestmentType, String> liqColRef;
    private TableColumn<InvestmentType, String> dateColRef;
    private TableColumn<InvestmentType, Void>   actionsColRef;

    // Summary KPI labels
    private final Label kpiTotalValue   = new Label("—");
    private final Label kpiCountValue   = new Label("—");
    private final Label kpiAvgProfValue = new Label("—");

    // Details panel
    private final VBox detailsPanel     = new VBox(12);
    private final Label detailsName     = new Label();
    private final Label detailsTicker   = new Label();
    private final HBox  detailsBadges   = new HBox(6);
    private final Label detailsValue    = new Label();
    private final Label detailsDate     = new Label();
    private final Label detailsProf     = new Label();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public InvestmentTypesPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Meus Investimentos",
                "Gerencie e acompanhe todos os seus ativos");

        // ── Top bar: add button + mini KPI summary ───────────────────────────
        Button add = new Button("Novo Investimento", Icons.plus());
        add.getStyleClass().add("button");
        add.setOnAction(e -> onCreate());

        HBox kpiRow = new HBox(10,
                kpiMiniCard("Total Investido",  kpiTotalValue),
                kpiMiniCard("Ativos",           kpiCountValue),
                kpiMiniCard("Rentab. Média",    kpiAvgProfValue)
        );
        kpiRow.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(12, add, spacer, kpiRow);
        topBar.getStyleClass().add("toolbar");
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── Filter row ────────────────────────────────────────────────────────
        searchField.setPromptText("Buscar por nome ou ticker...");

        // Search icon left + clear button right inside a StackPane overlay
        FontIcon searchIcon = Icons.search();
        searchIcon.setIconSize(13);
        Button clearBtn = new Button(null, Icons.close());
        clearBtn.getStyleClass().add("icon-btn");
        clearBtn.setVisible(false);
        clearBtn.setManaged(false);
        clearBtn.setPadding(new Insets(2));
        clearBtn.setOnAction(e -> searchField.clear());

        searchField.textProperty().addListener((obs, old, val) -> {
            boolean hasText = val != null && !val.isBlank();
            clearBtn.setVisible(hasText);
            clearBtn.setManaged(hasText);
        });

        StackPane searchBox = new StackPane(searchField, searchIcon, clearBtn);
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 10));
        StackPane.setAlignment(clearBtn, Pos.CENTER_RIGHT);
        StackPane.setMargin(clearBtn, new Insets(0, 4, 0, 0));
        searchField.setPadding(new Insets(9, 32, 9, 30));
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        categoryFilter.getItems().add("Todas categorias");
        for (CategoryEnum cat : CategoryEnum.values()) {
            categoryFilter.getItems().add(cat.getDisplayName());
        }
        categoryFilter.setValue("Todas categorias");
        categoryFilter.setMinWidth(170);

        Button clearFiltersBtn = new Button("Limpar filtros");
        clearFiltersBtn.getStyleClass().add("ghost-btn");
        clearFiltersBtn.setVisible(false);
        clearFiltersBtn.setManaged(false);
        clearFiltersBtn.setOnAction(e -> {
            searchField.clear();
            categoryFilter.setValue("Todas categorias");
        });

        // show "Limpar filtros" whenever any filter is active
        searchField.textProperty().addListener((o, a, v) -> updateClearFiltersVisibility(clearFiltersBtn));
        categoryFilter.valueProperty().addListener((o, a, v) -> updateClearFiltersVisibility(clearFiltersBtn));

        FlowPane filterRow = new FlowPane(10, 8);
        filterRow.getStyleClass().add("filter-flow");
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.getChildren().addAll(searchBox, categoryFilter, clearFiltersBtn);

        // ── Filter wiring ─────────────────────────────────────────────────────
        filteredItems = new FilteredList<>(allItems, p -> true);
        table.setItems(filteredItems);

        searchField.textProperty().addListener((obs, old, val) -> applyFilter());
        categoryFilter.valueProperty().addListener((obs, old, val) -> applyFilter());

        // ── Table ────────────────────────────────────────────────────────────
        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox tableCard = new VBox(0);
        tableCard.getStyleClass().add("card");
        tableCard.getChildren().add(table);

        // ── Details panel (hidden until row selected) ─────────────────────
        buildDetailsPanel();

        root.getChildren().addAll(header, topBar, filterRow, tableCard, detailsPanel);

        // Selection listener — animate details panel in
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) {
                detailsPanel.setVisible(false);
                detailsPanel.setManaged(false);
            } else {
                populateDetails(sel);
                if (!detailsPanel.isVisible()) {
                    detailsPanel.setVisible(true);
                    detailsPanel.setManaged(true);
                    Motion.fadeSlideIn(detailsPanel, 180, 10);
                }
            }
        });

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");

        // Adaptive column widths on scene resize (TASK 29)
        scrollPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                applyColumnWidths(scene.getWidth());
                scene.widthProperty().addListener((o2, w1, w2) ->
                        applyColumnWidths(w2.doubleValue()));
            }
        });

        refresh();
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void buildTable() {
        table.getStyleClass().add("table-analytic");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Nome
        TableColumn<InvestmentType, String> nameCol = new TableColumn<>("Nome");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        nameCol.setPrefWidth(200);

        // Categoria com badge
        catColRef = new TableColumn<>("Categoria");
        TableColumn<InvestmentType, String> catCol = catColRef;
        catCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }

                InvestmentType inv = getTableView().getItems().get(getIndex());
                if (inv.category() == null) {
                    setText("—");
                    setGraphic(null);
                } else {
                    try {
                        CategoryEnum cat = CategoryEnum.valueOf(inv.category());
                        setGraphic(createBadge(cat.getDisplayName(), cat.getColor()));
                        setText(null);
                    } catch (Exception e) {
                        setText(inv.category());
                        setGraphic(null);
                    }
                }
            }
        });
        catCol.setPrefWidth(180);

        // Liquidez com badge
        liqColRef = new TableColumn<>("Liquidez");
        TableColumn<InvestmentType, String> liqCol = liqColRef;
        liqCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }

                InvestmentType inv = getTableView().getItems().get(getIndex());
                if (inv.liquidity() == null) {
                    setText("—");
                    setGraphic(null);
                } else {
                    try {
                        LiquidityEnum liq = LiquidityEnum.valueOf(inv.liquidity());
                        setGraphic(createBadge(liq.getDisplayName(), liq.getColor()));
                        setText(null);
                    } catch (Exception e) {
                        setText(inv.liquidity());
                        setGraphic(null);
                    }
                }
            }
        });
        liqCol.setPrefWidth(160);

        // Data
        dateColRef = new TableColumn<>("Data");
        TableColumn<InvestmentType, String> dateCol = dateColRef;
        dateCol.setCellValueFactory(c -> {
            LocalDate date = c.getValue().investmentDate();
            return new javafx.beans.property.SimpleStringProperty(
                    date == null ? "—" : DATE_FMT.format(date)
            );
        });
        dateCol.setPrefWidth(110);

        // Rentabilidade
        TableColumn<InvestmentType, String> profCol = new TableColumn<>("Rentab. Anual");
        profCol.setCellValueFactory(c -> {
            BigDecimal prof = c.getValue().profitability();
            return new javafx.beans.property.SimpleStringProperty(
                    prof == null ? "—" : String.format("%.2f%%", prof)
            );
        });
        profCol.setPrefWidth(120);

        // Valor Investido
        TableColumn<InvestmentType, String> valueCol = new TableColumn<>("Valor Investido");
        valueCol.setCellValueFactory(c -> {
            BigDecimal val = c.getValue().investedValue();
            if (val == null) {
                return new javafx.beans.property.SimpleStringProperty("—");
            }
            long cents = val.multiply(BigDecimal.valueOf(100)).longValue();
            return new javafx.beans.property.SimpleStringProperty(daily.brl(cents));
        });
        valueCol.setPrefWidth(140);

        // Actions column
        TableColumn<InvestmentType, Void> actionsCol = new TableColumn<>("Ações");
        actionsCol.setMinWidth(160);
        actionsCol.setPrefWidth(220);
        actionsCol.setSortable(false);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button("Editar", Icons.edit());
            private final Button sellBtn = new Button("Vender", Icons.sell());
            private final Button delBtn  = new Button("Excluir", Icons.trash());
            private final FlowPane box = new FlowPane(4, 4);
            {
                editBtn.getStyleClass().add("icon-text-btn");
                sellBtn.getStyleClass().addAll("icon-text-btn", "sell-action-btn");
                delBtn.getStyleClass().addAll("icon-text-btn", "danger-action-btn");
                Tooltip.install(editBtn, new Tooltip("Editar investimento"));
                Tooltip.install(sellBtn, new Tooltip("Registrar venda"));
                Tooltip.install(delBtn,  new Tooltip("Excluir investimento"));
                box.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().addAll(editBtn, sellBtn, delBtn);
                editBtn.setOnAction(e -> {
                    table.getSelectionModel().select(getTableRow().getItem());
                    onEdit();
                });
                sellBtn.setOnAction(e -> {
                    table.getSelectionModel().select(getTableRow().getItem());
                    onSell();
                });
                delBtn.setOnAction(e -> {
                    table.getSelectionModel().select(getTableRow().getItem());
                    onDelete();
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
        actionsColRef = actionsCol;

        VBox emptyState = new VBox(8);
        emptyState.getStyleClass().add("empty-state");
        emptyState.setAlignment(Pos.CENTER);
        Label emptyIcon = new Label("📂");
        emptyIcon.getStyleClass().add("empty-icon");
        Label emptyTitle = new Label("Nenhum investimento cadastrado");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyHint = new Label("Clique em \"+ Novo Investimento\" para começar");
        emptyHint.getStyleClass().add("empty-hint");
        emptyState.getChildren().addAll(emptyIcon, emptyTitle, emptyHint);
        table.setPlaceholder(emptyState);

        table.getColumns().setAll(nameCol, catCol, liqCol, dateCol, profCol, valueCol, actionsCol);
    }

    private void updateClearFiltersVisibility(Button clearBtn) {
        boolean active = (searchField.getText() != null && !searchField.getText().isBlank())
                || (categoryFilter.getValue() != null && !categoryFilter.getValue().equals("Todas categorias"));
        clearBtn.setVisible(active);
        clearBtn.setManaged(active);
    }

    private void applyFilter() {
        String search = searchField.getText() == null ? "" : searchField.getText().toLowerCase();
        String catSel = categoryFilter.getValue();
        boolean filterActive = !search.isBlank()
                || (catSel != null && !catSel.equals("Todas categorias"));

        filteredItems.setPredicate(inv -> {
            boolean matchesSearch = search.isBlank()
                    || inv.name().toLowerCase().contains(search)
                    || (inv.ticker() != null && inv.ticker().toLowerCase().contains(search));

            boolean matchesCat;
            if (catSel == null || catSel.equals("Todas categorias")) {
                matchesCat = true;
            } else if (inv.category() == null) {
                matchesCat = false;
            } else {
                try {
                    matchesCat = CategoryEnum.valueOf(inv.category()).getDisplayName().equals(catSel);
                } catch (Exception e) {
                    matchesCat = false;
                }
            }

            return matchesSearch && matchesCat;
        });

        // Update placeholder to distinguish filtered vs empty portfolio
        if (filterActive && filteredItems.isEmpty() && !allItems.isEmpty()) {
            VBox filteredEmpty = new VBox(8);
            filteredEmpty.getStyleClass().add("empty-state");
            filteredEmpty.setAlignment(Pos.CENTER);
            Label fIcon = new Label("🔍");
            fIcon.getStyleClass().add("empty-icon");
            Label fTitle = new Label("Nenhum resultado encontrado");
            fTitle.getStyleClass().add("empty-title");
            Label fHint = new Label("Tente outros termos ou remova os filtros ativos.");
            fHint.getStyleClass().add("empty-hint");
            filteredEmpty.getChildren().addAll(fIcon, fTitle, fHint);
            table.setPlaceholder(filteredEmpty);
        }
    }

    private HBox createBadge(String text, String hexColor) {
        return ColorBadge.create(text, hexColor);
    }

    /** Hides/shows table columns based on available width. */
    private void applyColumnWidths(double width) {
        if (liqColRef  != null) liqColRef.setVisible(width > 900);
        if (dateColRef != null) dateColRef.setVisible(width > 900);
        if (catColRef  != null) catColRef.setVisible(width > 820);
        if (actionsColRef != null) {
            actionsColRef.setMinWidth(160);
            actionsColRef.setPrefWidth(220);
        }
    }

    private void buildDetailsPanel() {
        detailsPanel.getStyleClass().add("details-card");
        detailsPanel.setVisible(false);
        detailsPanel.setManaged(false);

        // Header row: name + ticker
        detailsName.getStyleClass().add("details-name");
        detailsTicker.getStyleClass().add("details-ticker");
        HBox nameRow = new HBox(10, detailsName, detailsTicker);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // Data grid
        VBox grid = new VBox(8);
        grid.getStyleClass().add("details-grid");
        detailsValue.getStyleClass().add("details-value");
        detailsDate.getStyleClass().add("text-helper");
        detailsProf.getStyleClass().add("text-helper");

        HBox valueRow = new HBox(16,
                detailField("Valor Investido", detailsValue),
                detailField("Data",            detailsDate),
                detailField("Rentab. Anual",   detailsProf)
        );

        // Action buttons
        Button editBtn2 = new Button("Editar", Icons.edit());
        editBtn2.getStyleClass().add("button");
        editBtn2.setOnAction(e -> onEdit());

        Button sellBtn2 = new Button("Registrar venda", Icons.sell());
        sellBtn2.getStyleClass().addAll("sell-btn");
        sellBtn2.setOnAction(e -> onSell());

        Button delBtn2 = new Button("Excluir", Icons.trash());
        delBtn2.getStyleClass().add("danger-btn");
        delBtn2.setOnAction(e -> onDelete());

        HBox actions = new HBox(10, editBtn2, sellBtn2, delBtn2);
        actions.getStyleClass().add("details-actions");
        actions.setAlignment(Pos.CENTER_LEFT);

        detailsPanel.getChildren().addAll(nameRow, detailsBadges, valueRow, actions);
    }

    private VBox detailField(String label, Label value) {
        VBox box = new VBox(3);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("kpi-mini-label");
        value.getStyleClass().setAll("details-field-value");
        box.getChildren().addAll(lbl, value);
        return box;
    }

    private void populateDetails(InvestmentType inv) {
        detailsName.setText(inv.name());
        detailsTicker.setText(inv.ticker() != null ? inv.ticker() : "");

        detailsBadges.getChildren().clear();
        if (inv.category() != null) {
            try {
                CategoryEnum cat = CategoryEnum.valueOf(inv.category());
                detailsBadges.getChildren().add(createBadge(cat.getDisplayName(), cat.getColor()));
            } catch (Exception ignored) {}
        }
        if (inv.liquidity() != null) {
            try {
                LiquidityEnum liq = LiquidityEnum.valueOf(inv.liquidity());
                detailsBadges.getChildren().add(createBadge(liq.getDisplayName(), liq.getColor()));
            } catch (Exception ignored) {}
        }

        if (inv.investedValue() != null) {
            long cents = inv.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            detailsValue.setText(daily.brl(cents));
        } else {
            detailsValue.setText("—");
        }
        detailsDate.setText(inv.investmentDate() != null ? DATE_FMT.format(inv.investmentDate()) : "—");
        detailsProf.setText(inv.profitability() != null
                ? String.format("%.2f%%", inv.profitability()) : "—");
    }

    private void refresh() {
        allItems.setAll(daily.listTypes());
        applyFilter();
        updateKpis();
        table.getSelectionModel().clearSelection();
    }

    private void updateKpis() {
        java.util.List<InvestmentType> items = allItems;

        long totalCents = 0;
        double totalProf = 0;
        int profCount = 0;
        for (InvestmentType inv : items) {
            if (inv.investedValue() != null) {
                totalCents += inv.investedValue()
                        .multiply(java.math.BigDecimal.valueOf(100)).longValue();
            }
            if (inv.profitability() != null) {
                totalProf += inv.profitability().doubleValue();
                profCount++;
            }
        }
        Motion.animateLabelChange(kpiCountValue,   String.valueOf(items.size()));
        Motion.animateLabelChange(kpiTotalValue,   daily.brl(totalCents));
        Motion.animateLabelChange(kpiAvgProfValue, profCount > 0
                ? String.format("%.1f%%", totalProf / profCount) : "—");
    }

    private HBox kpiMiniCard(String label, Label value) {
        HBox card = new HBox(8);
        card.getStyleClass().add("kpi-mini-card");
        card.setAlignment(Pos.CENTER_LEFT);

        VBox texts = new VBox(2);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("kpi-mini-label");
        value.getStyleClass().addAll("kpi-mini-value", "num");
        texts.getChildren().addAll(lbl, value);

        card.getChildren().add(texts);
        Motion.hoverLift(card);  // TASK 28
        return card;
    }

    private void onCreate() {
        InvestmentTypeDialog dialog = new InvestmentTypeDialog("Novo Investimento", null);
        Optional<InvestmentTypeData> result = dialog.showAndWait();

        result.ifPresent(data -> {
            try {
                int newId = daily.createTypeFull(
                        data.name(),
                        data.category(),
                        data.liquidity(),
                        data.investmentDate(),
                        data.profitability(),
                        data.investedValue(),
                        data.typeOfInvestment(),
                        data.indexType(),
                        data.indexPercentage(),
                        data.ticker(),
                        data.purchasePrice(),
                        data.quantity()
                );

                if (data.investedValue() != null && data.investedValue().signum() > 0) {
                    long totalCents = data.investedValue().multiply(java.math.BigDecimal.valueOf(100)).longValue();
                    Long unitCents = data.purchasePrice() != null
                            ? data.purchasePrice().multiply(java.math.BigDecimal.valueOf(100)).longValue()
                            : null;
                    java.time.LocalDate txDate = data.investmentDate() != null
                            ? data.investmentDate() : java.time.LocalDate.now();
                    daily.recordBuy(newId, data.name(), data.ticker(),
                            data.quantity(), unitCents, totalCents, txDate);
                }

                daily.takeSnapshotIfNeeded(java.time.LocalDate.now());
                refresh();
                ToastHost.showSuccess("Investimento criado com sucesso!");
            } catch (Exception e) {
                Dialogs.error("Erro: " + e.getMessage());
            }
        });
    }

    private void onEdit() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            ToastHost.showWarn("Selecione um investimento para editar.");
            return;
        }

        InvestmentTypeData existing = new InvestmentTypeData(
                sel.name(),
                sel.category(),
                sel.liquidity(),
                sel.investmentDate(),
                sel.profitability(),
                sel.investedValue(),
                sel.typeOfInvestment(),
                sel.indexType(),
                sel.indexPercentage(),
                sel.ticker(),
                sel.purchasePrice(),
                sel.quantity()
        );

        InvestmentTypeDialog dialog = new InvestmentTypeDialog("Editar Investimento", existing);
        Optional<InvestmentTypeData> result = dialog.showAndWait();

        result.ifPresent(data -> {
            try {
                daily.updateTypeFull(
                        sel.id(),
                        data.name(),
                        data.category(),
                        data.liquidity(),
                        data.investmentDate(),
                        data.profitability(),
                        data.investedValue(),
                        data.typeOfInvestment(),
                        data.indexType(),
                        data.indexPercentage(),
                        data.ticker(),
                        data.purchasePrice(),
                        data.quantity()
                );
                daily.takeSnapshotIfNeeded(java.time.LocalDate.now());
                refresh();
                ToastHost.showSuccess("Investimento atualizado!");
            } catch (Exception e) {
                Dialogs.error("Erro: " + e.getMessage());
            }
        });
    }

    private void onSell() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            ToastHost.showWarn("Selecione um investimento para vender.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Registrar Venda");
        dialog.setHeaderText("Venda de: " + sel.name());
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().addAll("dark-dialog", "dark-dialog-simple");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setOnShowing(e -> {
            javafx.scene.Scene sc = dialog.getDialogPane().getScene();
            if (sc != null) sc.setFill(Color.TRANSPARENT);
            ToastHost.showDim();
        });
        dialog.setOnHidden(e -> ToastHost.hideDim());
        Platform.runLater(() -> {
            Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
            if (okBtn != null) okBtn.getStyleClass().add("primary-btn");
            if (cancelBtn != null) cancelBtn.getStyleClass().add("ghost-btn");
        });

        TextField qtyField = new TextField();
        qtyField.setPromptText("Quantidade");
        if (sel.quantity() != null) {
            qtyField.setText(sel.quantity().toString());
        }

        TextField priceField = new TextField();
        priceField.setPromptText("R$ 0,00");
        Money.applyFormatOnBlur(priceField);
        if (sel.purchasePrice() != null) {
            long cents = sel.purchasePrice().multiply(BigDecimal.valueOf(100)).longValue();
            priceField.setText(Money.centsToText(cents));
        }

        DatePicker datePicker = new DatePicker(LocalDate.now());

        TextField noteField = new TextField();
        noteField.setPromptText("Observação (opcional)");

        Label qtyLabel = new Label("Quantidade:");
        qtyLabel.getStyleClass().add("form-label");
        Label qtyHint = new Label("Deixe em branco para venda total ou informe a quantidade parcial.");
        qtyHint.getStyleClass().add("text-helper");
        qtyHint.setWrapText(true);
        Label priceLabel = new Label("Preço Unitário:");
        priceLabel.getStyleClass().add("form-label");
        Label dateLabel = new Label("Data da Venda:");
        dateLabel.getStyleClass().add("form-label");
        Label noteLabel = new Label("Observação:");
        noteLabel.getStyleClass().add("form-label");

        VBox content = new VBox(8,
                qtyLabel, qtyField, qtyHint,
                priceLabel, priceField,
                dateLabel, datePicker,
                noteLabel, noteField
        );
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                long unitCents = Money.textToCentsOrZero(priceField.getText());
                String cleanQty = qtyField.getText().trim().replaceAll("[^0-9]", "");
                Integer qty = cleanQty.isEmpty() ? null : Integer.parseInt(cleanQty);
                long totalCents = qty != null ? unitCents * qty : unitCents;
                LocalDate sellDate = datePicker.getValue() != null ? datePicker.getValue() : LocalDate.now();
                String note = noteField.getText().isBlank() ? null : noteField.getText().trim();

                daily.recordSell(sel.id(), sel.name(), sel.ticker(),
                        qty, unitCents > 0 ? unitCents : null, totalCents, sellDate, note);
                ToastHost.showSuccess("Venda registrada no extrato!");
            } catch (Exception e) {
                Dialogs.error("Erro ao registrar venda: " + e.getMessage());
            }
        }
    }

    private void onDelete() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            ToastHost.showWarn("Selecione um investimento para excluir.");
            return;
        }

        boolean ok = Dialogs.confirm(
                "Excluir",
                "Excluir \"" + sel.name() + "\"?",
                "Isso apagará TODOS os dados relacionados.\n\nEsta ação não pode ser desfeita."
        );

        if (!ok) return;

        try {
            daily.deleteType(sel.id());
            refresh();
            ToastHost.showSuccess("Investimento excluído.");
        } catch (Exception e) {
            Dialogs.error("Erro: " + e.getMessage());
        }
    }
}

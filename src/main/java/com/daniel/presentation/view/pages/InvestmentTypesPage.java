package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.Enums.LiquidityEnum;
import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.DailyTrackingUseCase.CashStatus;
import com.daniel.core.service.DailyTrackingUseCase.InvestmentStatus;
import com.daniel.core.service.DailyTrackingUseCase.NewInvestment;
import com.daniel.core.service.DailyTrackingUseCase.OpeningSpec;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ColorBadge;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.InvestmentTypeDialog;
import com.daniel.presentation.view.components.InvestmentTypeDialog.InvestmentTypeData;
import com.daniel.presentation.view.components.LedgerRecognitionDialog;
import com.daniel.presentation.view.components.MovementDialog;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.MovementForm;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carteira: cadastro (metadados) + posição/custo/caixa derivados do ledger.
 *
 * <p>Toda leitura/escrita de banco roda no {@link UiExecutor}; a thread JavaFX só pinta.
 * Movimentos (aporte, retirada, compra, venda, ajuste) passam por {@link MovementDialog};
 * cadastro legado sem marco de abertura aparece identificado e só passa a movimentar após
 * reconhecimento explícito ({@link LedgerRecognitionDialog}).
 */
public final class InvestmentTypesPage implements Page {

    private final DailyTrackingUseCase daily;
    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();
    private final TableView<InvestmentType> table = new TableView<>();

    private final ObservableList<InvestmentType> allItems = FXCollections.observableArrayList();
    private FilteredList<InvestmentType> filteredItems;
    private final Map<Long, InvestmentStatus> statusById = new HashMap<>();
    private CashStatus cashStatus;

    private final TextField searchField = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final CheckBox showArchived = new CheckBox("Mostrar arquivados");

    private TableColumn<InvestmentType, String> catColRef;
    private TableColumn<InvestmentType, String> liqColRef;
    private TableColumn<InvestmentType, String> dateColRef;
    private TableColumn<InvestmentType, Void>   actionsColRef;

    // Summary KPI labels
    private final Label kpiTotalValue   = new Label("—");
    private final Label kpiCountValue   = new Label("—");
    private final Label kpiCashValue    = new Label("—");

    // Cash card
    private final Label cashStateLabel = new Label();
    private final Button cashRecognizeBtn = new Button("Reconhecer caixa legado");
    private final Button cashReconcileBtn = new Button("Reconciliar caixa");
    private final Button depositBtn = new Button("Aporte", Icons.plus());
    private final Button withdrawBtn = new Button("Retirada", Icons.arrowRight());
    private final Button adjustCashBtn = new Button("Ajuste de caixa", Icons.edit());

    // Details panel
    private final VBox detailsPanel     = new VBox(12);
    private final Label detailsName     = new Label();
    private final Label detailsTicker   = new Label();
    private final HBox  detailsBadges   = new HBox(6);
    private final Label detailsValue    = new Label();
    private final Label detailsQty      = new Label();
    private final Label detailsDate     = new Label();
    private final Label detailsProf     = new Label();
    private final Label detailsNote     = new Label();
    private final Label detailsLedger   = new Label();
    private final HBox  detailsActions  = new HBox(10);

    private final Label ledgerBanner = new Label();

    private final AtomicLong refreshEpoch = new AtomicLong();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public InvestmentTypesPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Meus Investimentos",
                "Cadastro, posição e caixa — posição e caixa vêm do ledger de movimentos");

        // ── Top bar: add button + mini KPI summary ───────────────────────────
        Button add = new Button("Novo Investimento", Icons.plus());
        add.getStyleClass().add("button");
        add.setOnAction(e -> onCreate());

        HBox kpiRow = new HBox(10,
                kpiMiniCard("Custo das posições", kpiTotalValue),
                kpiMiniCard("Ativos",             kpiCountValue),
                kpiMiniCard("Caixa (ledger)",     kpiCashValue)
        );
        kpiRow.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(12, add, spacer, kpiRow);
        topBar.getStyleClass().add("toolbar");
        topBar.setAlignment(Pos.CENTER_LEFT);

        ledgerBanner.setGraphic(Icons.alertTriangle());
        ledgerBanner.setGraphicTextGap(8);
        ledgerBanner.setWrapText(true);
        ledgerBanner.getStyleClass().add("warning-banner");
        ledgerBanner.setVisible(false);
        ledgerBanner.setManaged(false);

        // ── Filter row ────────────────────────────────────────────────────────
        searchField.setPromptText("Buscar por nome ou ticker...");
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
        for (CategoryEnum cat : CategoryEnum.values()) categoryFilter.getItems().add(cat.getDisplayName());
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
        searchField.textProperty().addListener((o, a, v) -> updateClearFiltersVisibility(clearFiltersBtn));
        categoryFilter.valueProperty().addListener((o, a, v) -> updateClearFiltersVisibility(clearFiltersBtn));
        showArchived.selectedProperty().addListener((o, a, v) -> refresh());

        FlowPane filterRow = new FlowPane(10, 8);
        filterRow.getStyleClass().add("filter-flow");
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.getChildren().addAll(searchBox, categoryFilter, showArchived, clearFiltersBtn);

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

        buildDetailsPanel();
        VBox cashCard = buildCashCard();

        root.getChildren().addAll(header, ledgerBanner, topBar, cashCard, filterRow, tableCard, detailsPanel);

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

        scrollPane.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                applyColumnWidths(scene.getWidth());
                scene.widthProperty().addListener((o2, w1, w2) -> applyColumnWidths(w2.doubleValue()));
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

    // =====================================================================
    // Table
    // =====================================================================

    private void buildTable() {
        table.getStyleClass().add("table-analytic");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<InvestmentType, String> nameCol = new TableColumn<>("Nome");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        nameCol.setPrefWidth(200);

        catColRef = new TableColumn<>("Categoria");
        TableColumn<InvestmentType, String> catCol = catColRef;
        catCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); setText(null); return; }
                InvestmentType inv = getTableView().getItems().get(getIndex());
                if (inv.category() == null) { setText("—"); setGraphic(null); return; }
                try {
                    CategoryEnum cat = CategoryEnum.valueOf(inv.category());
                    setGraphic(createBadge(cat.getDisplayName(), cat.getColor()));
                    setText(null);
                } catch (Exception e) {
                    setText(inv.category()); setGraphic(null);
                }
            }
        });
        catCol.setPrefWidth(180);

        liqColRef = new TableColumn<>("Liquidez");
        TableColumn<InvestmentType, String> liqCol = liqColRef;
        liqCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); setText(null); return; }
                InvestmentType inv = getTableView().getItems().get(getIndex());
                if (inv.liquidity() == null) { setText("—"); setGraphic(null); return; }
                try {
                    LiquidityEnum liq = LiquidityEnum.valueOf(inv.liquidity());
                    setGraphic(createBadge(liq.getDisplayName(), liq.getColor()));
                    setText(null);
                } catch (Exception e) {
                    setText(inv.liquidity()); setGraphic(null);
                }
            }
        });
        liqCol.setPrefWidth(160);

        dateColRef = new TableColumn<>("Data");
        TableColumn<InvestmentType, String> dateCol = dateColRef;
        dateCol.setCellValueFactory(c -> {
            LocalDate date = c.getValue().investmentDate();
            return new javafx.beans.property.SimpleStringProperty(date == null ? "—" : DATE_FMT.format(date));
        });
        dateCol.setPrefWidth(110);

        TableColumn<InvestmentType, String> qtyCol = new TableColumn<>("Qtd.");
        qtyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().quantity() == null ? "—" : c.getValue().quantity().toString()));
        qtyCol.setPrefWidth(70);

        TableColumn<InvestmentType, String> valueCol = new TableColumn<>("Custo / Valor investido");
        valueCol.setCellValueFactory(c -> {
            BigDecimal val = c.getValue().investedValue();
            if (val == null) return new javafx.beans.property.SimpleStringProperty("—");
            long cents = val.multiply(BigDecimal.valueOf(100)).longValue();
            return new javafx.beans.property.SimpleStringProperty(daily.brl(cents));
        });
        valueCol.setPrefWidth(150);

        TableColumn<InvestmentType, String> ledgerCol = new TableColumn<>("Ledger");
        ledgerCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); setText(null); return; }
                InvestmentType inv = getTableView().getItems().get(getIndex());
                setText(null);
                setGraphic(ledgerBadge(statusById.get((long) inv.id())));
            }
        });
        ledgerCol.setPrefWidth(150);

        TableColumn<InvestmentType, Void> actionsCol = new TableColumn<>("Ações");
        actionsCol.setMinWidth(200);
        actionsCol.setPrefWidth(260);
        actionsCol.setSortable(false);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button("Editar", Icons.edit());
            private final Button buyBtn  = new Button("Comprar", Icons.trendingUp());
            private final Button sellBtn = new Button("Vender", Icons.sell());
            private final Button moreBtn = new Button("…");
            private final FlowPane box = new FlowPane(4, 4);
            {
                editBtn.getStyleClass().add("icon-text-btn");
                buyBtn.getStyleClass().add("icon-text-btn");
                sellBtn.getStyleClass().addAll("icon-text-btn", "sell-action-btn");
                moreBtn.getStyleClass().add("icon-text-btn");
                Tooltip.install(editBtn, new Tooltip("Editar cadastro (metadados)"));
                Tooltip.install(buyBtn, new Tooltip("Registrar compra/aplicação (usa caixa)"));
                Tooltip.install(sellBtn, new Tooltip("Registrar venda/resgate (vai para o caixa)"));
                Tooltip.install(moreBtn, new Tooltip("Ajuste, abertura, reconciliação, arquivar, excluir"));
                box.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().addAll(editBtn, buyBtn, sellBtn, moreBtn);
                editBtn.setOnAction(e -> { select(); onEdit(); });
                buyBtn.setOnAction(e -> { select(); onBuy(); });
                sellBtn.setOnAction(e -> { select(); onSell(); });
                moreBtn.setOnAction(e -> { select(); moreMenu().show(moreBtn, javafx.geometry.Side.BOTTOM, 0, 0); });
            }
            private void select() { table.getSelectionModel().select(getTableRow().getItem()); }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
        actionsColRef = actionsCol;

        table.setPlaceholder(EmptyState.of("📂", "Nenhum investimento cadastrado",
                "Cadastre seu primeiro investimento para acompanhar patrimônio, rentabilidade e distribuição."));

        table.getColumns().add(nameCol);
        table.getColumns().add(catCol);
        table.getColumns().add(liqCol);
        table.getColumns().add(dateCol);
        table.getColumns().add(qtyCol);
        table.getColumns().add(valueCol);
        table.getColumns().add(ledgerCol);
        table.getColumns().add(actionsCol);
    }

    private ContextMenu moreMenu() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        InvestmentStatus st = sel == null ? null : statusById.get((long) sel.id());
        ContextMenu menu = new ContextMenu();
        if (st != null && !st.opened()) {
            MenuItem open = new MenuItem("Reconhecer saldo de abertura…");
            open.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.OPENING));
            menu.getItems().add(open);
        }
        if (st != null && st.opened()) {
            MenuItem rec = new MenuItem(st.reconciliationPending() ? "Reconciliar (pendente)…" : "Reconciliar saldo…");
            rec.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.RECONCILIATION));
            MenuItem adj = new MenuItem("Ajuste de posição…");
            adj.setOnAction(e -> onAdjustPosition());
            menu.getItems().addAll(rec, adj);
        }
        menu.getItems().add(new SeparatorMenuItem());
        if (st != null && st.archived()) {
            MenuItem un = new MenuItem("Desarquivar");
            un.setOnAction(e -> onUnarchive());
            menu.getItems().add(un);
        } else {
            MenuItem ar = new MenuItem("Arquivar (preserva histórico)");
            ar.setOnAction(e -> onArchive());
            menu.getItems().add(ar);
        }
        MenuItem del = new MenuItem("Excluir cadastro sem histórico");
        del.setOnAction(e -> onDelete());
        menu.getItems().add(del);
        return menu;
    }

    private HBox ledgerBadge(InvestmentStatus st) {
        if (st == null) return createBadge("—", "#888888");
        if (st.archived()) return createBadge("Arquivado", "#8a8a8a");
        if (!st.opened()) return createBadge(st.hasLegacyData() ? "Legado: reconhecer" : "Sem abertura", "#d9a400");
        if (st.reconciliationPending()) return createBadge("Reconciliação pendente", "#e07a00");
        if (st.closed()) return createBadge("Posição zerada", "#6c8ebf");
        return createBadge(st.mode() == PositionMode.VALUE ? "Ledger · valor" : "Ledger · qtd", "#2e9e5b");
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
        boolean filterActive = !search.isBlank() || (catSel != null && !catSel.equals("Todas categorias"));

        filteredItems.setPredicate(inv -> {
            boolean matchesSearch = search.isBlank()
                    || inv.name().toLowerCase().contains(search)
                    || (inv.ticker() != null && inv.ticker().toLowerCase().contains(search));
            boolean matchesCat;
            if (catSel == null || catSel.equals("Todas categorias")) matchesCat = true;
            else if (inv.category() == null) matchesCat = false;
            else {
                try { matchesCat = CategoryEnum.valueOf(inv.category()).getDisplayName().equals(catSel); }
                catch (Exception e) { matchesCat = false; }
            }
            return matchesSearch && matchesCat;
        });

        if (filterActive && filteredItems.isEmpty() && !allItems.isEmpty()) {
            table.setPlaceholder(EmptyState.of("🔍", "Nenhum resultado encontrado",
                    "Tente outros termos ou remova os filtros ativos."));
        }
    }

    private HBox createBadge(String text, String hexColor) {
        return ColorBadge.create(text, hexColor);
    }

    private void applyColumnWidths(double width) {
        if (liqColRef  != null) liqColRef.setVisible(width > 980);
        if (dateColRef != null) dateColRef.setVisible(width > 980);
        if (catColRef  != null) catColRef.setVisible(width > 860);
        if (actionsColRef != null) {
            actionsColRef.setMinWidth(200);
            actionsColRef.setPrefWidth(260);
        }
    }

    // =====================================================================
    // Cash card
    // =====================================================================

    private VBox buildCashCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label title = new Label("CAIXA");
        title.getStyleClass().add("card-title");
        cashStateLabel.getStyleClass().add("text-helper");
        cashStateLabel.setWrapText(true);

        depositBtn.getStyleClass().add("button");
        withdrawBtn.getStyleClass().add("ghost-btn");
        adjustCashBtn.getStyleClass().add("ghost-btn");
        cashRecognizeBtn.getStyleClass().add("primary-btn");
        cashReconcileBtn.getStyleClass().add("ghost-btn");

        depositBtn.setOnAction(e -> openMovement("Aporte externo", "Dinheiro que entra de fora da carteira. Aumenta o caixa; não é lucro.",
                MovementForm.deposit()));
        withdrawBtn.setOnAction(e -> openMovement("Retirada externa", "Dinheiro que sai da carteira. Reduz o caixa; não é prejuízo.",
                MovementForm.withdrawal()));
        adjustCashBtn.setOnAction(e -> openMovement("Ajuste de caixa", "Correção com classificação e motivo. Rendimento/Despesa afetam o ganho realizado; Correção/Outro não.",
                MovementForm.adjustCash()));
        cashRecognizeBtn.setOnAction(e -> LedgerRecognitionDialog.open(LedgerRecognitionDialog.Mode.OPENING, null, "Caixa", daily, this::refreshAndSnapshot));
        cashReconcileBtn.setOnAction(e -> LedgerRecognitionDialog.open(LedgerRecognitionDialog.Mode.RECONCILIATION, null, "Caixa", daily, this::refreshAndSnapshot));

        HBox actions = new HBox(8, depositBtn, withdrawBtn, adjustCashBtn, cashRecognizeBtn, cashReconcileBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(title, cashStateLabel, actions);
        return card;
    }

    private void applyCashStatus(CashStatus cs) {
        cashStatus = cs;
        if (!daily.hasLedger() || cs == null) {
            cashStateLabel.setText("Ledger indisponível nesta configuração.");
            depositBtn.setDisable(true); withdrawBtn.setDisable(true); adjustCashBtn.setDisable(true);
            cashRecognizeBtn.setVisible(false); cashRecognizeBtn.setManaged(false);
            cashReconcileBtn.setVisible(false); cashReconcileBtn.setManaged(false);
            Motion.animateLabelChange(kpiCashValue, "—");
            return;
        }
        boolean legacyPending = !cs.opened() && cs.legacySnapshotsPresent();
        boolean recPending = cs.reconciliationPending();
        depositBtn.setDisable(cs.blocked());
        withdrawBtn.setDisable(cs.blocked());
        adjustCashBtn.setDisable(cs.blocked());
        cashRecognizeBtn.setVisible(legacyPending); cashRecognizeBtn.setManaged(legacyPending);
        cashReconcileBtn.setVisible(cs.opened()); cashReconcileBtn.setManaged(cs.opened());
        if (legacyPending) {
            cashStateLabel.setText("Saldo legado " + daily.brl(cs.cashCents()) + " (último snapshot manual) ainda não reconhecido pelo ledger. "
                    + "Reconheça para liberar aportes, retiradas e compras.");
            Motion.animateLabelChange(kpiCashValue, daily.brl(cs.cashCents()) + " (legado)");
        } else if (recPending) {
            cashStateLabel.setText("Caixa " + daily.brl(cs.cashCents()) + " com reconciliação pendente: confirme o saldo antes de movimentar.");
            Motion.animateLabelChange(kpiCashValue, daily.brl(cs.cashCents()) + " (pendente)");
        } else if (!cs.opened()) {
            cashStateLabel.setText("Sem caixa registrado. O primeiro aporte abre o caixa em zero e soma o valor informado.");
            Motion.animateLabelChange(kpiCashValue, daily.brl(0));
        } else {
            cashStateLabel.setText("Caixa derivado do ledger (aportes, retiradas, compras, vendas e ajustes).");
            Motion.animateLabelChange(kpiCashValue, daily.brl(cs.cashCents()));
        }
    }

    // =====================================================================
    // Details
    // =====================================================================

    private void buildDetailsPanel() {
        detailsPanel.getStyleClass().add("details-card");
        detailsPanel.setVisible(false);
        detailsPanel.setManaged(false);

        detailsName.getStyleClass().add("details-name");
        detailsTicker.getStyleClass().add("details-ticker");
        HBox nameRow = new HBox(10, detailsName, detailsTicker);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        detailsValue.getStyleClass().add("details-value");
        detailsDate.getStyleClass().add("text-helper");
        detailsProf.getStyleClass().add("text-helper");
        detailsNote.getStyleClass().add("text-helper");
        detailsNote.setWrapText(true);
        detailsLedger.getStyleClass().add("text-helper");
        detailsLedger.setWrapText(true);

        HBox valueRow = new HBox(16,
                detailField("Custo da posição", detailsValue),
                detailField("Quantidade",       detailsQty),
                detailField("Data",             detailsDate),
                detailField("Rentab. Anual",    detailsProf)
        );

        detailsActions.getStyleClass().add("details-actions");
        detailsActions.setAlignment(Pos.CENTER_LEFT);

        detailsPanel.getChildren().addAll(nameRow, detailsBadges, valueRow, detailsLedger, detailsNote, detailsActions);
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
        InvestmentStatus st = statusById.get((long) inv.id());

        detailsBadges.getChildren().clear();
        if (inv.category() != null) {
            try { CategoryEnum cat = CategoryEnum.valueOf(inv.category()); detailsBadges.getChildren().add(createBadge(cat.getDisplayName(), cat.getColor())); } catch (Exception ignored) {}
        }
        if (inv.liquidity() != null) {
            try { LiquidityEnum liq = LiquidityEnum.valueOf(inv.liquidity()); detailsBadges.getChildren().add(createBadge(liq.getDisplayName(), liq.getColor())); } catch (Exception ignored) {}
        }
        detailsBadges.getChildren().add(ledgerBadge(st));

        if (inv.investedValue() != null) {
            long cents = inv.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
            detailsValue.setText(daily.brl(cents));
        } else {
            detailsValue.setText("—");
        }
        detailsQty.setText(inv.quantity() == null ? "—" : inv.quantity().toString());
        detailsDate.setText(inv.investmentDate() != null ? DATE_FMT.format(inv.investmentDate()) : "—");
        detailsProf.setText(inv.profitability() != null ? String.format("%.2f%%", inv.profitability()) : "—");
        detailsNote.setText(st != null && st.note() != null ? "Observação: " + st.note() : "");
        detailsNote.setVisible(!detailsNote.getText().isEmpty());
        detailsNote.setManaged(!detailsNote.getText().isEmpty());

        if (st == null) {
            detailsLedger.setText("");
        } else if (st.archived()) {
            detailsLedger.setText("Arquivado: histórico e snapshots preservados; desarquive para movimentar.");
        } else if (!st.opened()) {
            detailsLedger.setText(st.hasLegacyData()
                    ? "Cadastro legado: quantidade/valor exibidos vêm do cadastro antigo" + (st.legacyTransactionCount() > 0
                        ? " e há " + st.legacyTransactionCount() + " lançamento(s) antigo(s) no extrato (não reproduzidos)." : ".")
                      + " Reconheça o saldo de abertura para passar a movimentar pelo ledger."
                    : "Sem marco de abertura no ledger. Reconheça a abertura para movimentar.");
        } else if (st.reconciliationPending()) {
            detailsLedger.setText("Abertura marcada como incerta: reconcilie (com motivo) antes de comprar/vender.");
        } else {
            detailsLedger.setText(st.mode() == PositionMode.VALUE
                    ? "Investimento por valor: resgates saem ao custo, sem ganho inferido; valor atual estimado pela taxa cadastrada."
                    : "Posição e custo médio derivados do ledger.");
        }

        detailsActions.getChildren().clear();
        Button editBtn2 = new Button("Editar", Icons.edit());
        editBtn2.getStyleClass().add("button");
        editBtn2.setOnAction(e -> onEdit());
        detailsActions.getChildren().add(editBtn2);
        if (st != null && !st.archived()) {
            if (st.opened() && !st.reconciliationPending()) {
                Button buy = new Button("Comprar", Icons.trendingUp());
                buy.getStyleClass().add("button");
                buy.setOnAction(e -> onBuy());
                Button sell = new Button("Vender", Icons.sell());
                sell.getStyleClass().addAll("sell-btn");
                sell.setOnAction(e -> onSell());
                Button adj = new Button("Ajuste", Icons.edit());
                adj.getStyleClass().add("ghost-btn");
                adj.setOnAction(e -> onAdjustPosition());
                detailsActions.getChildren().addAll(buy, sell, adj);
            }
            if (!st.opened()) {
                Button rec = new Button("Reconhecer abertura", Icons.check());
                rec.getStyleClass().add("primary-btn");
                rec.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.OPENING));
                detailsActions.getChildren().add(rec);
            } else {
                Button rec = new Button(st.reconciliationPending() ? "Reconciliar (pendente)" : "Reconciliar", Icons.check());
                rec.getStyleClass().add(st.reconciliationPending() ? "primary-btn" : "ghost-btn");
                rec.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.RECONCILIATION));
                detailsActions.getChildren().add(rec);
            }
            Button arch = new Button("Arquivar", Icons.layers());
            arch.getStyleClass().add("ghost-btn");
            arch.setOnAction(e -> onArchive());
            detailsActions.getChildren().add(arch);
        } else if (st != null) {
            Button un = new Button("Desarquivar", Icons.layers());
            un.getStyleClass().add("button");
            un.setOnAction(e -> onUnarchive());
            detailsActions.getChildren().add(un);
        }
        Button delBtn2 = new Button("Excluir", Icons.trash());
        delBtn2.getStyleClass().add("danger-btn");
        delBtn2.setOnAction(e -> onDelete());
        detailsActions.getChildren().add(delBtn2);
    }

    // =====================================================================
    // Refresh (worker thread) and KPIs
    // =====================================================================

    private record PageData(List<InvestmentType> items, Map<Long, InvestmentStatus> statuses, CashStatus cash,
                            long totalCostCents, int pendingCount, int legacyCount) {}

    private void refresh() {
        final long epoch = refreshEpoch.incrementAndGet();
        final boolean includeArchived = showArchived.isSelected();
        CompletableFuture.supplyAsync(() -> {
            List<InvestmentType> items = daily.listTypes(includeArchived);
            Map<Long, InvestmentStatus> statuses = new HashMap<>();
            CashStatus cash = null;
            int pending = 0, legacy = 0;
            if (daily.hasLedger()) {
                for (InvestmentType t : items) {
                    InvestmentStatus st = daily.investmentStatus(t.id());
                    statuses.put((long) t.id(), st);
                    if (!st.archived() && st.reconciliationPending()) pending++;
                    if (!st.archived() && !st.opened() && st.hasLegacyData()) legacy++;
                }
                cash = daily.cashStatus();
            }
            long total = 0;
            for (InvestmentType inv : items) {
                if (inv.investedValue() != null) total += inv.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
            }
            return new PageData(items, statuses, cash, total, pending, legacy);
        }, UiExecutor.get()).whenComplete((data, err) -> Platform.runLater(() -> {
            if (refreshEpoch.get() != epoch) return;
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                ToastHost.showError("Falha ao carregar carteira: " + c.getMessage());
                return;
            }
            statusById.clear();
            statusById.putAll(data.statuses());
            allItems.setAll(data.items());
            applyFilter();
            table.refresh();
            Motion.animateLabelChange(kpiCountValue, String.valueOf(data.items().size()));
            Motion.animateLabelChange(kpiTotalValue, daily.brl(data.totalCostCents()));
            applyCashStatus(data.cash());
            table.getSelectionModel().clearSelection();

            StringBuilder banner = new StringBuilder();
            if (data.legacyCount() > 0) banner.append(data.legacyCount()).append(" cadastro(s) legado(s) ainda não reconhecido(s) pelo ledger — posição e valor mostrados vêm do cadastro antigo. ");
            if (data.pendingCount() > 0) banner.append(data.pendingCount()).append(" investimento(s) com reconciliação pendente. ");
            if (data.cash() != null && data.cash().blocked()) banner.append("Caixa aguarda reconhecimento/reconciliação.");
            boolean show = banner.length() > 0;
            ledgerBanner.setText(banner.toString().trim());
            ledgerBanner.setVisible(show);
            ledgerBanner.setManaged(show);
        }));
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
        Motion.hoverLift(card);
        return card;
    }

    // =====================================================================
    // Actions
    // =====================================================================

    /** Runs a mutation on the worker and refreshes; shows the rejection without losing state. */
    private void runMutation(Runnable work, String successMessage) {
        CompletableFuture.runAsync(work, UiExecutor.get()).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err == null) {
                ToastHost.showSuccess(successMessage);
                refreshAndSnapshot();
                return;
            }
            Throwable c = err.getCause() != null ? err.getCause() : err;
            if (c instanceof LedgerException le) Dialogs.error("Operação recusada (" + le.field() + "): " + le.getMessage());
            else Dialogs.error("Erro: " + c.getMessage());
        }));
    }

    private void refreshAndSnapshot() {
        UiExecutor.get().execute(() -> {
            try { daily.takeSnapshotIfNeeded(LocalDate.now()); } catch (Exception ignored) {}
        });
        refresh();
    }

    private void onCreate() {
        InvestmentTypeDialog dialog = new InvestmentTypeDialog("Novo Investimento", null);
        Optional<InvestmentTypeData> result = dialog.showAndWait();
        result.ifPresent(data -> {
            NewInvestment inv = new NewInvestment(data.name(), data.category(), data.liquidity(), data.investmentDate(),
                    data.profitability(), data.investedValue(), data.typeOfInvestment(), data.indexType(),
                    data.indexPercentage(), data.ticker(), data.purchasePrice(), data.quantity(), data.note());
            boolean quantityMode = (data.ticker() != null && !data.ticker().isBlank()) || data.quantity() != null;
            PositionMode mode = quantityMode ? PositionMode.QUANTITY : PositionMode.VALUE;
            LocalDate openDate = data.investmentDate() != null ? data.investmentDate() : LocalDate.now();
            String key = UUID.randomUUID().toString();
            OpeningSpec spec;
            if (data.importBalance()) {
                long cost = data.investedValue() == null ? 0L : data.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
                Long qty = quantityMode ? (data.quantity() == null ? 0L : data.quantity().longValue()) : null;
                spec = OpeningSpec.imported(key, openDate, mode, qty, cost, "saldo informado no cadastro");
            } else {
                spec = OpeningSpec.zero(key, openDate, mode);
            }
            runMutation(() -> daily.createInvestment(inv, spec),
                    data.importBalance() ? "Investimento criado com saldo de abertura." : "Investimento criado (posição zero; registre aporte e compra).");
        });
    }

    private void onEdit() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento para editar."); return; }
        InvestmentStatus st = statusById.get((long) sel.id());

        InvestmentTypeData existing = new InvestmentTypeData(sel.name(), sel.category(), sel.liquidity(), sel.investmentDate(),
                sel.profitability(), sel.investedValue(), sel.typeOfInvestment(), sel.indexType(), sel.indexPercentage(),
                sel.ticker(), sel.purchasePrice(), sel.quantity(), st == null ? null : st.note(), false);

        InvestmentTypeDialog dialog = new InvestmentTypeDialog("Editar Investimento", existing);
        Optional<InvestmentTypeData> result = dialog.showAndWait();
        result.ifPresent(data -> {
            final boolean ledgerOwned = st != null && st.opened();
            runMutation(() -> {
                // Metadata only. For a ledger-recognised position the quantity/invested value shown
                // are derived; keep the legacy columns as they were so nothing silently restates them.
                InvestmentType raw = ledgerOwned ? rawRegistration(sel.id()) : null;
                daily.updateTypeFull(sel.id(), data.name(), data.category(), data.liquidity(), data.investmentDate(),
                        data.profitability(),
                        ledgerOwned && raw != null ? raw.investedValue() : data.investedValue(),
                        data.typeOfInvestment(), data.indexType(), data.indexPercentage(), data.ticker(),
                        ledgerOwned && raw != null ? raw.purchasePrice() : data.purchasePrice(),
                        ledgerOwned && raw != null ? raw.quantity() : data.quantity());
                if (daily.hasLedger()) daily.attachNote(sel.id(), data.note());
            }, ledgerOwned ? "Cadastro atualizado (posição/custo continuam no ledger)." : "Investimento atualizado!");
        });
    }

    private InvestmentType rawRegistration(int id) {
        // listTypes(true) overlays ledger values; read the raw row through the status-less path.
        for (InvestmentType t : new com.daniel.infrastructure.persistence.repository.InvestmentTypeRepository().listAll()) {
            if (t.id() == id) return t;
        }
        return null;
    }

    private void openMovement(String title, String subtitle, MovementForm form) {
        MovementDialog d = new MovementDialog(title, subtitle, form, daily, saved -> {
            ToastHost.showSuccess(DailyTrackingUseCase.kindLabel(saved.kind()) + " registrado(a): " + daily.brl(saved.amountCents()));
            refreshAndSnapshot();
        });
        d.showAndWait();
    }

    private boolean requireMovable(InvestmentType sel) {
        InvestmentStatus st = statusById.get((long) sel.id());
        if (st == null) { ToastHost.showWarn("Ledger indisponível."); return false; }
        if (st.archived()) { ToastHost.showWarn("Investimento arquivado: desarquive para movimentar."); return false; }
        if (!st.opened()) { ToastHost.showWarn("Reconheça o saldo de abertura antes de movimentar."); return false; }
        if (st.reconciliationPending()) { ToastHost.showWarn("Reconcilie o saldo pendente antes de movimentar."); return false; }
        if (cashStatus != null && cashStatus.blocked()) { ToastHost.showWarn("Caixa aguarda reconhecimento/reconciliação."); return false; }
        return true;
    }

    private void onBuy() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento."); return; }
        if (!requireMovable(sel)) return;
        InvestmentStatus st = statusById.get((long) sel.id());
        openMovement("Compra — " + sel.name(),
                st.mode() == PositionMode.QUANTITY
                        ? "Usa o caixa disponível e aumenta a posição. Quantidade inteira; preço com as casas que você digitar."
                        : "Aplicação por valor: sai do caixa e soma ao custo.",
                MovementForm.buy(sel.id(), st.mode()));
    }

    private void onSell() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento para vender."); return; }
        if (!requireMovable(sel)) return;
        InvestmentStatus st = statusById.get((long) sel.id());
        openMovement("Venda — " + sel.name(),
                st.mode() == PositionMode.QUANTITY
                        ? "Reduz a posição (máx. " + st.quantity() + " un.) e aumenta o caixa. Ganho realizado = venda − custo médio."
                        : "Resgate ao custo (máx. " + daily.brl(st.costCents()) + "): volta ao caixa sem ganho inferido.",
                MovementForm.sell(sel.id(), st.mode()));
    }

    private void onAdjustPosition() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento."); return; }
        if (!requireMovable(sel)) return;
        InvestmentStatus st = statusById.get((long) sel.id());
        openMovement("Ajuste de posição — " + sel.name(),
                "Exige classificação e motivo. Não mexe no caixa; corrige quantidade e/ou custo.",
                MovementForm.adjustPosition(sel.id(), st.mode()));
    }

    private void onRecognize(LedgerRecognitionDialog.Mode mode) {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento."); return; }
        LedgerRecognitionDialog.open(mode, (long) sel.id(), sel.name(), daily, this::refreshAndSnapshot);
    }

    private void onArchive() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento."); return; }
        boolean ok = Dialogs.confirm("Arquivar", "Arquivar \"" + sel.name() + "\"?",
                "O histórico, os lançamentos e os snapshots são preservados; o investimento sai da lista ativa.\n"
                + "Posições com saldo não podem ser arquivadas (venda ou ajuste antes).");
        if (!ok) return;
        runMutation(() -> daily.archiveType(sel.id()), "Investimento arquivado (histórico preservado).");
    }

    private void onUnarchive() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        runMutation(() -> daily.unarchiveType(sel.id()), "Investimento desarquivado.");
    }

    private void onDelete() {
        InvestmentType sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { ToastHost.showWarn("Selecione um investimento para excluir."); return; }
        boolean ok = Dialogs.confirm("Excluir", "Excluir o cadastro \"" + sel.name() + "\"?",
                "Só cadastros sem qualquer histórico podem ser excluídos. Se houver lançamentos ou snapshots, use Arquivar.");
        if (!ok) return;
        runMutation(() -> daily.deleteType(sel.id()), "Cadastro excluído.");
    }
}

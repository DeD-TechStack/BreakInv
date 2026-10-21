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
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.HeaderActions;
import com.daniel.presentation.view.components.InvestmentTypeDialog;
import com.daniel.presentation.view.components.InvestmentTypeDialog.InvestmentTypeData;
import com.daniel.presentation.view.components.LedgerRecognitionDialog;
import com.daniel.presentation.view.components.MovementDialog;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Dialogs;
import com.daniel.presentation.view.util.Icons;
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
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
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
 *
 * <p>Composição (direção A): cabeçalho com ações, faixa de valores, toolbar de filtros, tabela
 * densa (42 px) com um menu ⋯ por linha e um painel de detalhes contextual — ao lado da tabela
 * no amplo, abaixo dela no médio/estreito. Na tela o vocabulário é do usuário (histórico de
 * movimentações, saldo inicial, revisão de saldo), nunca o da implementação.
 */
public final class InvestmentTypesPage implements ResponsivePage {

    static final double ROW_HEIGHT = 42;
    static final String STAT_CASH = "Caixa";
    static final String STAT_COST = "Custo das posições";
    static final String STAT_COUNT = "Ativos";

    private final DailyTrackingUseCase daily;
    private final VBox root = new VBox(16);
    private final ScrollPane scrollPane = new ScrollPane();
    private final BorderPane body = new BorderPane();
    private final TableView<InvestmentType> table = new TableView<>();
    private final DetailsPane details = new DetailsPane();

    private final ObservableList<InvestmentType> allItems = FXCollections.observableArrayList();
    private FilteredList<InvestmentType> filteredItems;
    private final Map<Long, InvestmentStatus> statusById = new HashMap<>();
    private CashStatus cashStatus;
    private Breakpoint breakpoint = Breakpoint.WIDE;

    private final TextField searchField = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final CheckBox showArchived = new CheckBox("Mostrar arquivados");

    private TableColumn<InvestmentType, String> catColRef;
    private TableColumn<InvestmentType, String> liqColRef;
    private TableColumn<InvestmentType, String> dateColRef;
    private TableColumn<InvestmentType, Void>   actionsColRef;
    private TableColumn<InvestmentType, String> qtyColRef;
    private TableColumn<InvestmentType, String> statusColRef;
    private boolean compactRows;

    private final StatStrip strip = new StatStrip(STAT_CASH, STAT_COST, STAT_COUNT);
    private final Label cashStateLabel = new Label();

    // Header actions
    private final Button addBtn = new Button("Novo ativo", Icons.plus());
    private final Button depositBtn = new Button("Aporte", Icons.plus());
    private final Button withdrawBtn = new Button("Retirada", Icons.arrowRight());
    private final MenuButton cashMoreBtn = new MenuButton(null, Icons.of(Feather.MORE_HORIZONTAL, 14));
    private final MenuItem adjustCashItem = new MenuItem("Ajuste de caixa…");
    private final MenuItem cashRecognizeItem = new MenuItem("Reconhecer saldo inicial do caixa…");
    private final MenuItem cashReconcileItem = new MenuItem("Revisar saldo do caixa…");

    private final Label warningBanner = new Label();

    private final AtomicLong refreshEpoch = new AtomicLong();
    /** True while a refresh swaps the list, so the transient null selection is not a close. */
    private boolean refreshingItems;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public InvestmentTypesPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;

        root.getStyleClass().add("page-root");

        // ── Header + actions ─────────────────────────────────────────────────
        PageHeader header = new PageHeader("Carteira",
                "Posições, custo e caixa calculados a partir do histórico de movimentações");
        addBtn.getStyleClass().add("button");
        addBtn.setOnAction(e -> onCreate());
        depositBtn.getStyleClass().add("ghost-btn");
        withdrawBtn.getStyleClass().add("ghost-btn");
        cashMoreBtn.getStyleClass().addAll("ghost-btn", "more-btn");
        cashMoreBtn.setTooltip(new Tooltip("Mais ações de caixa"));
        cashMoreBtn.setAccessibleText("Mais ações de caixa");
        cashMoreBtn.getItems().addAll(adjustCashItem, cashRecognizeItem, cashReconcileItem);

        depositBtn.setOnAction(e -> openMovement("Aporte externo", "Dinheiro que entra de fora da carteira. Aumenta o caixa; não é lucro.",
                MovementForm.deposit()));
        withdrawBtn.setOnAction(e -> openMovement("Retirada externa", "Dinheiro que sai da carteira. Reduz o caixa; não é prejuízo.",
                MovementForm.withdrawal()));
        adjustCashItem.setOnAction(e -> openMovement("Ajuste de caixa", "Correção com classificação e motivo. Rendimento/Despesa afetam o ganho realizado; Correção/Outro não.",
                MovementForm.adjustCash()));
        cashRecognizeItem.setOnAction(e -> LedgerRecognitionDialog.open(LedgerRecognitionDialog.Mode.OPENING, null, "Caixa", daily, this::refreshAndSnapshot));
        cashReconcileItem.setOnAction(e -> LedgerRecognitionDialog.open(LedgerRecognitionDialog.Mode.RECONCILIATION, null, "Caixa", daily, this::refreshAndSnapshot));

        HeaderActions headerRow = new HeaderActions(header, withdrawBtn, depositBtn, cashMoreBtn, addBtn);

        warningBanner.setGraphic(Icons.alertTriangle());
        warningBanner.setGraphicTextGap(8);
        warningBanner.setWrapText(true);
        warningBanner.getStyleClass().add("warning-banner");
        warningBanner.setVisible(false);
        warningBanner.setManaged(false);

        // ── Value strip + cash explanation ───────────────────────────────────
        cashStateLabel.getStyleClass().add("text-secondary");
        cashStateLabel.setWrapText(true);

        // ── Filter toolbar ───────────────────────────────────────────────────
        searchField.setPromptText("Buscar por nome ou ticker");
        searchField.setAccessibleText("Buscar por nome ou ticker");
        FontIcon searchIcon = Icons.search();
        searchIcon.setIconSize(13);
        Button clearBtn = new Button(null, Icons.close());
        clearBtn.getStyleClass().add("icon-btn");
        clearBtn.setTooltip(new Tooltip("Limpar busca"));
        clearBtn.setAccessibleText("Limpar busca");
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
        searchField.getStyleClass().add("search-field");
        searchBox.setMinWidth(180);
        searchBox.setPrefWidth(320);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        categoryFilter.getItems().add("Todas categorias");
        for (CategoryEnum cat : CategoryEnum.values()) categoryFilter.getItems().add(cat.getDisplayName());
        categoryFilter.setValue("Todas categorias");
        categoryFilter.setMinWidth(150);
        categoryFilter.setAccessibleText("Filtrar por categoria");

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

        // ── Table + details ──────────────────────────────────────────────────
        buildTable();
        table.setMinHeight(280);
        table.setPrefHeight(440);
        VBox tableCard = new VBox(0, table);
        tableCard.getStyleClass().add("table-card");
        VBox.setVgrow(table, Priority.ALWAYS);

        details.setOnClose(() -> {
            table.getSelectionModel().clearSelection();
            table.requestFocus();
        });

        body.setCenter(tableCard);
        body.getStyleClass().add("carteira-body");

        root.getChildren().addAll(headerRow, warningBanner, strip, cashStateLabel, filterRow, body);

        // Selection lost because the DATA changed (filter/refresh): only hide, never touch the focus.
        // The user's own close (Esc / X) goes through details.requestClose() -> onClose above.
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (refreshingItems) return; // refresh re-selects the same registration right after setAll
            if (sel == null) {
                details.hide();
            } else {
                showDetails(sel);
            }
        });
        table.setOnKeyPressed(e -> {
            InvestmentType sel = table.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && sel != null) { showDetails(sel); details.requestFocus(); e.consume(); }
            if (e.getCode() == KeyCode.ESCAPE && details.isVisible()) { details.requestClose(); e.consume(); }
        });

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");

        onBreakpoint(Breakpoint.WIDE);
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

    @Override
    public void onBreakpoint(Breakpoint bp) {
        breakpoint = bp;
        boolean wide = bp == Breakpoint.WIDE;
        boolean narrow = bp == Breakpoint.NARROW;
        if (liqColRef  != null) liqColRef.setVisible(wide);
        if (dateColRef != null) dateColRef.setVisible(wide);
        if (catColRef  != null) catColRef.setVisible(!narrow);
        updateTableColumns();
        strip.setColumns(narrow ? 2 : 3);
        placeDetails();
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    /** Details beside the table when wide, below it otherwise — same node, no re-creation. */
    private void placeDetails() {
        boolean beside = breakpoint == Breakpoint.WIDE;
        body.setRight(null);
        body.setBottom(null);
        if (beside) {
            body.setRight(details);
            BorderPane.setMargin(details, new Insets(0, 0, 0, 16));
            details.setPrefWidth(320);
            details.setMaxWidth(360);
        } else {
            body.setBottom(details);
            BorderPane.setMargin(details, new Insets(16, 0, 0, 0));
            details.setPrefWidth(Region.USE_COMPUTED_SIZE);
            details.setMaxWidth(Double.MAX_VALUE);
        }
    }

    // =====================================================================
    // Table
    // =====================================================================

    private void buildTable() {
        table.getStyleClass().addAll("table-analytic", "table-dense");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(ROW_HEIGHT);

        TableColumn<InvestmentType, String> nameCol = new TableColumn<>("Ativo");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        nameCol.setCellFactory(col -> new TableCell<>() {
            private final Label name = new Label();
            private final Label ticker = new Label();
            private final HBox identity = new HBox(8, name, ticker);
            private final VBox box = new VBox(0, identity);
            {
                name.getStyleClass().add("cell-primary");
                ticker.getStyleClass().add("ticker-badge");
                identity.setAlignment(Pos.CENTER_LEFT);
                box.setAlignment(Pos.CENTER_LEFT);
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                InvestmentType inv = getTableView().getItems().get(getIndex());
                name.setText(inv.name());
                boolean hasTicker = inv.ticker() != null && !inv.ticker().isBlank();
                ticker.setText(hasTicker ? inv.ticker() : "");
                ticker.setVisible(hasTicker && !compactRows); ticker.setManaged(hasTicker && !compactRows);
                box.getChildren().setAll(identity);
                if (compactRows) box.getChildren().add(compactStatusBadge(statusById.get((long) inv.id())));
                setAccessibleText(inv.name() + (hasTicker ? " · " + inv.ticker() : ""));
                setGraphic(box);
                setText(null);
            }
        });
        nameCol.setPrefWidth(240);
        nameCol.setMinWidth(140);

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
        catCol.setPrefWidth(150);

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
        liqCol.setPrefWidth(130);

        dateColRef = new TableColumn<>("Data");
        TableColumn<InvestmentType, String> dateCol = dateColRef;
        dateCol.setCellValueFactory(c -> {
            LocalDate date = c.getValue().investmentDate();
            return new javafx.beans.property.SimpleStringProperty(date == null ? "—" : DATE_FMT.format(date));
        });
        dateCol.setPrefWidth(100);
        dateCol.getStyleClass().add("num-col");

        TableColumn<InvestmentType, String> qtyCol = new TableColumn<>("Qtd.");
        qtyColRef = qtyCol;
        qtyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().quantity() == null ? "—" : c.getValue().quantity().toString()));
        qtyCol.setPrefWidth(70);
        qtyCol.getStyleClass().add("num-col");

        TableColumn<InvestmentType, String> valueCol = new TableColumn<>("Custo");
        valueCol.setCellValueFactory(c -> {
            BigDecimal val = c.getValue().investedValue();
            if (val == null) return new javafx.beans.property.SimpleStringProperty("—");
            long cents = val.multiply(BigDecimal.valueOf(100)).longValue();
            return new javafx.beans.property.SimpleStringProperty(daily.brl(cents));
        });
        valueCol.setPrefWidth(130);
        valueCol.setMinWidth(100);
        valueCol.getStyleClass().add("num-col");

        TableColumn<InvestmentType, String> statusCol = new TableColumn<>("Situação");
        statusColRef = statusCol;
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); setText(null); return; }
                InvestmentType inv = getTableView().getItems().get(getIndex());
                setText(null);
                setGraphic(statusBadge(statusById.get((long) inv.id())));
            }
        });
        statusCol.setPrefWidth(140);
        statusCol.setMinWidth(140);

        TableColumn<InvestmentType, Void> actionsCol = new TableColumn<>("");
        actionsCol.getStyleClass().add("actions-col");
        actionsCol.setMinWidth(48);
        actionsCol.setPrefWidth(48);
        actionsCol.setMaxWidth(56);
        actionsCol.setSortable(false);
        actionsCol.setResizable(false);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final MenuButton more = new MenuButton(null, Icons.of(Feather.MORE_HORIZONTAL, 14));
            {
                more.getStyleClass().addAll("icon-btn", "row-more-btn");
                more.setTooltip(new Tooltip("Ações do ativo"));
                more.setAccessibleText("Ações do ativo");
                more.setOnShowing(e -> {
                    InvestmentType inv = getTableView().getItems().get(getIndex());
                    table.getSelectionModel().select(inv);
                    more.getItems().setAll(rowMenuItems(inv));
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : more);
            }
        });
        actionsColRef = actionsCol;

        table.setPlaceholder(EmptyState.of("📂", "Nenhum investimento cadastrado",
                "Use \"Novo ativo\" para cadastrar o primeiro e acompanhar patrimônio e distribuição."));

        table.getColumns().add(nameCol);
        table.getColumns().add(catCol);
        table.getColumns().add(liqCol);
        table.getColumns().add(dateCol);
        table.getColumns().add(qtyCol);
        table.getColumns().add(valueCol);
        table.getColumns().add(statusCol);
        table.getColumns().add(actionsCol);
        table.widthProperty().addListener((o, a, b) -> updateTableColumns());
    }

    /** Keep status in the row, not in a clipped column, when the actual table is narrow. */
    private void updateTableColumns() {
        if (statusColRef == null || table.getWidth() <= 0) return;
        boolean compact = table.getWidth() < 620;
        boolean changed = compactRows != compact;
        compactRows = compact;
        statusColRef.setVisible(!compact);
        qtyColRef.setVisible(!compact);
        catColRef.setVisible(!compact && breakpoint != Breakpoint.NARROW);
        liqColRef.setVisible(table.getWidth() >= 960 && breakpoint == Breakpoint.WIDE);
        dateColRef.setVisible(table.getWidth() >= 960 && breakpoint == Breakpoint.WIDE);
        if (changed) table.refresh();
    }

    private HBox compactStatusBadge(InvestmentStatus st) {
        HBox badge = statusBadge(st);
        Label label = (Label) badge.getChildren().get(1);
        String full = label.getText();
        label.setText(switch (full) {
            case "Por quantidade" -> "Quantidade";
            case "Por valor" -> "Valor";
            case "Reconhecer saldo" -> "Reconhecer";
            case "Sem saldo inicial" -> "Sem saldo";
            case "Revisão pendente" -> "Revisar saldo";
            case "Posição zerada" -> "Zerada";
            default -> full;
        });
        badge.setAccessibleText(full + ". Abra os detalhes para a situação completa.");
        badge.getStyleClass().add("row-status");
        return badge;
    }

    /** All row actions in one menu; the unavailable ones stay visible but disabled. */
    private List<MenuItem> rowMenuItems(InvestmentType inv) {
        InvestmentStatus st = statusById.get((long) inv.id());
        List<MenuItem> items = new ArrayList<>();
        MenuItem view = new MenuItem("Ver detalhes");
        view.setOnAction(e -> { showDetails(inv); details.requestFocus(); });
        MenuItem edit = new MenuItem("Editar cadastro…");
        edit.setOnAction(e -> onEdit());
        items.add(view);
        items.add(edit);
        items.add(new SeparatorMenuItem());

        String blocked = movementBlockReason(st);
        MenuItem buy = new MenuItem("Comprar…");
        buy.setOnAction(e -> onBuy());
        MenuItem sell = new MenuItem("Vender…");
        sell.setOnAction(e -> onSell());
        MenuItem adj = new MenuItem("Ajuste de posição…");
        adj.setOnAction(e -> onAdjustPosition());
        for (MenuItem m : List.of(buy, sell, adj)) m.setDisable(blocked != null);
        items.add(buy);
        items.add(sell);
        items.add(adj);
        items.add(new SeparatorMenuItem());

        if (st == null || !st.opened()) {
            MenuItem open = new MenuItem("Reconhecer saldo inicial…");
            open.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.OPENING));
            open.setDisable(st == null);
            items.add(open);
        } else {
            MenuItem rec = new MenuItem(st.reconciliationPending() ? "Revisar saldo (pendente)…" : "Revisar saldo…");
            rec.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.RECONCILIATION));
            items.add(rec);
        }
        items.add(new SeparatorMenuItem());
        if (st != null && st.archived()) {
            MenuItem un = new MenuItem("Desarquivar");
            un.setOnAction(e -> onUnarchive());
            items.add(un);
        } else {
            MenuItem ar = new MenuItem("Arquivar (preserva histórico)");
            ar.setOnAction(e -> onArchive());
            items.add(ar);
        }
        MenuItem del = new MenuItem("Excluir cadastro sem histórico");
        del.setOnAction(e -> onDelete());
        items.add(del);
        return items;
    }

    private HBox statusBadge(InvestmentStatus st) {
        if (st == null) return createBadge("—", "#8E99A8");
        if (st.archived()) return createBadge("Arquivado", "#8E99A8");
        if (!st.opened()) return createBadge(st.hasLegacyData() ? "Reconhecer saldo" : "Sem saldo inicial", "#E8B657");
        if (st.reconciliationPending()) return createBadge("Revisão pendente", "#E8B657");
        if (st.closed()) return createBadge("Posição zerada", "#7FB2F0");
        return createBadge(st.mode() == PositionMode.VALUE ? "Por valor" : "Por quantidade", "#4ADE80");
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

    // =====================================================================
    // Cash
    // =====================================================================

    private void applyCashStatus(CashStatus cs) {
        cashStatus = cs;
        if (!daily.hasLedger() || cs == null) {
            cashStateLabel.setText("Histórico de movimentações indisponível nesta configuração.");
            depositBtn.setDisable(true); withdrawBtn.setDisable(true); adjustCashItem.setDisable(true);
            cashRecognizeItem.setVisible(false);
            cashReconcileItem.setVisible(false);
            strip.set(STAT_CASH, "—", "muted");
            return;
        }
        boolean legacyPending = !cs.opened() && cs.legacySnapshotsPresent();
        boolean recPending = cs.reconciliationPending();
        depositBtn.setDisable(cs.blocked());
        withdrawBtn.setDisable(cs.blocked());
        adjustCashItem.setDisable(cs.blocked());
        cashRecognizeItem.setVisible(legacyPending);
        cashReconcileItem.setVisible(cs.opened());
        if (legacyPending) {
            cashStateLabel.setText("Saldo anterior de " + daily.brl(cs.cashCents()) + " (último registro manual) ainda não reconhecido. "
                    + "Reconheça o saldo inicial do caixa (menu ⋯) para liberar aportes, retiradas e compras.");
            strip.set(STAT_CASH, daily.brl(cs.cashCents()), "muted", "a reconhecer");
        } else if (recPending) {
            cashStateLabel.setText("Caixa " + daily.brl(cs.cashCents()) + " com revisão de saldo pendente: confirme o saldo antes de movimentar.");
            strip.set(STAT_CASH, daily.brl(cs.cashCents()), "muted", "revisão pendente");
        } else if (!cs.opened()) {
            cashStateLabel.setText("Sem caixa registrado. O primeiro aporte abre o caixa em zero e soma o valor informado.");
            strip.set(STAT_CASH, daily.brl(0));
        } else {
            cashStateLabel.setText("Caixa calculado pelo histórico: aportes, retiradas, compras, vendas e ajustes.");
            strip.set(STAT_CASH, daily.brl(cs.cashCents()));
        }
    }

    // =====================================================================
    // Details
    // =====================================================================

    /** Fills and shows the details pane for {@code inv}; beside or below the table per breakpoint. */
    void showDetails(InvestmentType inv) {
        InvestmentStatus st = statusById.get((long) inv.id());
        details.setTitle(inv.name(), inv.ticker() != null && !inv.ticker().isBlank() ? inv.ticker() : null);

        List<javafx.scene.Node> badges = new ArrayList<>();
        if (inv.category() != null) {
            try { CategoryEnum cat = CategoryEnum.valueOf(inv.category()); badges.add(createBadge(cat.getDisplayName(), cat.getColor())); } catch (Exception ignored) {}
        }
        badges.add(statusBadge(st));
        details.setBadges(badges.toArray(javafx.scene.Node[]::new));

        long costCents = inv.investedValue() == null ? -1 : inv.investedValue().multiply(BigDecimal.valueOf(100)).longValue();
        String hint;
        if (inv.quantity() != null && inv.purchasePrice() != null) {
            hint = inv.quantity() + " un. × preço médio R$ " + String.format("%.2f", inv.purchasePrice().doubleValue());
        } else {
            hint = "Custo da posição";
        }
        details.setHeadline(costCents < 0 ? "—" : daily.brl(costCents), hint);

        details.clearFields();
        details.addField("Categoria", categoryText(inv));
        details.addField("Liquidez", liquidityText(inv));
        details.addField("Data", inv.investmentDate() != null ? DATE_FMT.format(inv.investmentDate()) : "—");
        details.addField("Quantidade", inv.quantity() == null ? "—" : inv.quantity().toString());
        if (inv.purchasePrice() != null) {
            details.addField("Preço médio", "R$ " + String.format("%.2f", inv.purchasePrice().doubleValue()));
        }
        details.addField("Rentab. anual", inv.profitability() != null ? String.format("%.2f%%", inv.profitability()) : "—");
        details.addField("Situação", statusBadge(st));
        if (inv.typeOfInvestment() != null) details.addField("Tipo", typeText(inv.typeOfInvestment()));

        StringBuilder note = new StringBuilder(statusExplanation(st));
        if (st != null && st.note() != null && !st.note().isBlank()) {
            if (note.length() > 0) note.append("\n");
            note.append("Observação: ").append(st.note());
        }
        details.setNote(note.toString());

        // Primary: Comprar, Vender, Editar — the first two disabled (with the reason) when blocked
        String blocked = movementBlockReason(st);
        Button edit = new Button("Editar", Icons.edit());
        edit.getStyleClass().add("ghost-btn");
        edit.setOnAction(e -> onEdit());
        Button buy = new Button("Comprar", Icons.trendingUp());
        buy.getStyleClass().add("button");
        buy.setOnAction(e -> onBuy());
        Button sell = new Button("Vender", Icons.sell());
        sell.getStyleClass().add("sell-btn");
        sell.setOnAction(e -> onSell());
        for (Button b : List.of(buy, sell)) {
            b.setDisable(blocked != null);
            b.setTooltip(new Tooltip(blocked != null ? blocked : (b == buy
                    ? "Registrar compra/aplicação (usa caixa)" : "Registrar venda/resgate (vai para o caixa)")));
        }
        details.setPrimaryActions(buy, sell, edit);

        List<ButtonBase> advanced = new ArrayList<>();
        Button adj = new Button("Ajuste de posição");
        adj.getStyleClass().add("link-btn");
        adj.setDisable(blocked != null);
        adj.setOnAction(e -> onAdjustPosition());
        advanced.add(adj);
        if (st == null || !st.opened()) {
            Button rec = new Button("Reconhecer saldo inicial", Icons.check());
            rec.getStyleClass().add("link-btn");
            rec.setDisable(st == null);
            rec.setTooltip(new Tooltip(st == null ? "Histórico de movimentações indisponível" : "Informa a posição existente para passar a registrar movimentações"));
            rec.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.OPENING));
            advanced.add(rec);
        } else {
            Button rec = new Button(st.reconciliationPending() ? "Reconciliar saldo (pendente)" : "Reconciliar saldo", Icons.check());
            rec.getStyleClass().add(st.reconciliationPending() ? "link-btn-accent" : "link-btn");
            rec.setTooltip(new Tooltip("Confere a posição real e registra a diferença com motivo"));
            rec.setOnAction(e -> onRecognize(LedgerRecognitionDialog.Mode.RECONCILIATION));
            advanced.add(rec);
        }
        if (st != null && st.archived()) {
            Button un = new Button("Desarquivar", Icons.layers());
            un.getStyleClass().add("link-btn");
            un.setOnAction(e -> onUnarchive());
            advanced.add(un);
        } else {
            Button arch = new Button("Arquivar", Icons.layers());
            arch.getStyleClass().add("link-btn");
            arch.setTooltip(new Tooltip("Sai da lista ativa; histórico preservado"));
            arch.setOnAction(e -> onArchive());
            advanced.add(arch);
        }
        Button del = new Button("Excluir", Icons.trash());
        del.getStyleClass().add("link-btn-danger");
        del.setTooltip(new Tooltip("Só cadastros sem qualquer histórico"));
        del.setOnAction(e -> onDelete());
        advanced.add(del);
        details.setAdvancedActions(advanced.toArray(ButtonBase[]::new));

        placeDetails();
        details.open(breakpoint == Breakpoint.WIDE);
    }

    private static String categoryText(InvestmentType inv) {
        if (inv.category() == null) return "—";
        try { return CategoryEnum.valueOf(inv.category()).getDisplayName(); } catch (Exception e) { return inv.category(); }
    }

    private static String liquidityText(InvestmentType inv) {
        if (inv.liquidity() == null) return "—";
        try { return LiquidityEnum.valueOf(inv.liquidity()).getDisplayName(); } catch (Exception e) { return inv.liquidity(); }
    }

    private static String typeText(String type) {
        return switch (type) {
            case "PREFIXADO" -> "Prefixado";
            case "POS_FIXADO" -> "Pós-fixado";
            case "HIBRIDO" -> "Híbrido";
            case "ACAO" -> "Ação";
            default -> type;
        };
    }

    /** Why buy/sell/adjust are unavailable, in user vocabulary; null when allowed. */
    private String movementBlockReason(InvestmentStatus st) {
        if (st == null) return "Histórico de movimentações indisponível.";
        if (st.archived()) return "Investimento arquivado: desarquive para movimentar.";
        if (!st.opened()) return "Reconheça o saldo inicial antes de movimentar.";
        if (st.reconciliationPending()) return "Revise o saldo pendente antes de movimentar.";
        if (cashStatus != null && cashStatus.blocked()) return "O caixa aguarda reconhecimento ou revisão de saldo.";
        return null;
    }

    private static String statusExplanation(InvestmentStatus st) {
        if (st == null) return "";
        if (st.archived()) return "Arquivado: histórico e registros preservados; desarquive para movimentar.";
        if (!st.opened()) {
            return st.hasLegacyData()
                    ? "Cadastro anterior: quantidade e valor exibidos vêm do cadastro antigo" + (st.legacyTransactionCount() > 0
                        ? " e há " + st.legacyTransactionCount() + " lançamento(s) antigo(s) no extrato (não recalculados)." : ".")
                      + " Reconheça o saldo inicial para passar a registrar movimentações."
                    : "Sem saldo inicial reconhecido. Reconheça-o para movimentar.";
        }
        if (st.reconciliationPending()) return "Saldo inicial marcado como incerto: revise-o (com motivo) antes de comprar ou vender.";
        return st.mode() == PositionMode.VALUE
                ? "Investimento por valor: resgates saem ao custo, sem ganho inferido; valor atual estimado pela taxa cadastrada."
                : "Posição e custo médio calculados pelo histórico de movimentações.";
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
            // keep the selection across a refresh when the same registration is still listed:
            // the pane stays open and is only refilled (no hide + re-animation, no focus change)
            InvestmentType previous = table.getSelectionModel().getSelectedItem();
            statusById.clear();
            statusById.putAll(data.statuses());
            refreshingItems = true;
            try {
                allItems.setAll(data.items());
                applyFilter();
            } finally {
                refreshingItems = false;
            }
            table.refresh();
            strip.set(STAT_COUNT, String.valueOf(data.items().size()));
            strip.set(STAT_COST, daily.brl(data.totalCostCents()));
            applyCashStatus(data.cash());
            if (previous != null) {
                InvestmentType again = filteredItems.stream().filter(i -> i.id() == previous.id()).findFirst().orElse(null);
                if (again != null) {
                    refreshingItems = true;
                    try { table.getSelectionModel().select(again); } finally { refreshingItems = false; }
                    showDetails(again);
                } else {
                    refreshingItems = true;
                    try { table.getSelectionModel().clearSelection(); } finally { refreshingItems = false; }
                    details.hide();
                }
            }

            StringBuilder banner = new StringBuilder();
            if (data.legacyCount() > 0) banner.append(data.legacyCount()).append(" cadastro(s) anterior(es) ainda sem saldo inicial reconhecido — posição e valor mostrados vêm do cadastro antigo. ");
            if (data.pendingCount() > 0) banner.append(data.pendingCount()).append(" investimento(s) com revisão de saldo pendente. ");
            if (data.cash() != null && data.cash().blocked()) banner.append("O caixa aguarda reconhecimento ou revisão de saldo.");
            boolean show = banner.length() > 0;
            warningBanner.setText(banner.toString().trim());
            warningBanner.setVisible(show);
            warningBanner.setManaged(show);
        }));
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
                    data.importBalance() ? "Investimento criado com saldo inicial." : "Investimento criado (posição zero; registre aporte e compra).");
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
            }, ledgerOwned ? "Cadastro atualizado (posição e custo continuam no histórico)." : "Investimento atualizado!");
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
        String reason = movementBlockReason(statusById.get((long) sel.id()));
        if (reason != null) { ToastHost.showWarn(reason); return false; }
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
                "O histórico, os lançamentos e os registros diários são preservados; o investimento sai da lista ativa.\n"
                + "Posições com saldo não podem ser arquivadas (venda ou ajuste antes).", "Arquivar");
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
                "Só cadastros sem qualquer histórico podem ser excluídos. Se houver lançamentos ou registros diários, use Arquivar.", "Excluir");
        if (!ok) return;
        runMutation(() -> daily.deleteType(sel.id()), "Cadastro excluído.");
    }
}

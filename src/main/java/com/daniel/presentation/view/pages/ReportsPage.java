package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.DetailsPane;
import com.daniel.presentation.view.components.HeaderActions;
import com.daniel.presentation.view.components.MovementDialog;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.viewmodel.MovementForm;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Extrato (direction A): the month is the context of the page (navigation in the header), the
 * month's figures are a value strip, flow filters narrow the same list, and each movement is one
 * row in plain language — reversals and reversed originals point at each other by number,
 * records from the old register are marked and say that they do not enter the cash balance.
 *
 * <p>Every movement appears exactly once; a reversal is created from the number of the original
 * row. Nothing here changes how figures are computed: deposits/buys are summed from the entries
 * exactly as before, gain and wealth come from {@link DailyTrackingUseCase}.</p>
 */
public final class ReportsPage implements ResponsivePage {

    private final DailyTrackingUseCase daily;
    private final Executor worker;
    private final Executor ui;

    private final VBox root = new VBox(16);
    private final ScrollPane scrollPane = new ScrollPane();

    private final Button btnPrevMonth = new Button();
    private final Button btnNextMonth = new Button();
    private final Button btnCurrentMonth = new Button("Mês atual");
    private final Label monthLabel = new Label();

    static final String STAT_DEPOSITS = "Aportes no mês";
    static final String STAT_BUYS = "Compras no mês";
    static final String STAT_GAIN = "Ganho";
    static final String STAT_WEALTH = "Patrimônio";
    private final StatStrip strip = new StatStrip(STAT_DEPOSITS, STAT_BUYS, STAT_GAIN, STAT_WEALTH);

    private final TableView<ExtractRow> table = new TableView<>();
    private final DetailsPane details = new DetailsPane();
    private ExtractRow detailedRow;
    private TableColumn<ExtractRow, String> numberColRef;
    private TableColumn<ExtractRow, String> descColRef;
    private TableColumn<ExtractRow, String> typeColRef;
    private TableColumn<ExtractRow, String> dateColRef;
    private TableColumn<ExtractRow, Void> actionColRef;
    private final javafx.beans.property.BooleanProperty compactActions = new javafx.beans.property.SimpleBooleanProperty();
    private final FlowPane filterBar = new FlowPane(6, 6);
    private final ToggleGroup filterGroup = new ToggleGroup();
    private final Label loadingLabel = new Label("Carregando lançamentos\u2026");
    private final Label errorBanner = new Label();
    private final Label legendLabel = new Label(
            "Cada lançamento aparece uma vez. Um lançamento estornado continua visível e aponta para o estorno; "
            + "o estorno aponta de volta para o original. Registros antigos vêm do cadastro anterior e não entram no caixa.");

    private List<ExtractRow> allRows = List.of();
    private YearMonth currentMonth = YearMonth.now();
    private final AtomicLong reloadEpoch = new AtomicLong(0);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Which flows a filter keeps. */
    private enum Flow {
        ALL("Tudo", r -> true),
        CASH("Caixa", r -> !r.legacy() && (r.kind() == MovementKind.DEPOSIT || r.kind() == MovementKind.WITHDRAWAL)),
        TRADES("Compras e vendas", r -> !r.legacy() && (r.kind() == MovementKind.BUY || r.kind() == MovementKind.SELL)),
        ADJUSTMENTS("Ajustes e estornos", r -> !r.legacy() && (r.kind() == MovementKind.ADJUSTMENT || r.kind() == MovementKind.REVERSAL
                || r.kind() == MovementKind.OPENING || r.kind() == MovementKind.RECONCILIATION)),
        LEGACY("Registros antigos", ExtractRow::legacy);

        final String label;
        final Predicate<ExtractRow> keep;
        Flow(String label, Predicate<ExtractRow> keep) { this.label = label; this.keep = keep; }
    }

    public ReportsPage(DailyTrackingUseCase daily) {
        this(daily, UiExecutor.get(), Platform::runLater);
    }

    /** Test seam: the executors the month load runs on (worker) and answers on (ui). */
    ReportsPage(DailyTrackingUseCase daily, Executor worker, Executor ui) {
        this.daily = daily;
        this.worker = worker;
        this.ui = ui;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Extrato", "Todos os lançamentos do mês, um por linha");

        btnPrevMonth.setGraphic(Icons.chevronLeft());
        btnPrevMonth.setText(null);
        btnPrevMonth.getStyleClass().add("icon-btn");
        btnPrevMonth.setTooltip(new Tooltip("Mês anterior"));
        btnPrevMonth.setAccessibleText("Mês anterior");
        btnNextMonth.setGraphic(Icons.chevronRight());
        btnNextMonth.setText(null);
        btnNextMonth.getStyleClass().add("icon-btn");
        btnNextMonth.setTooltip(new Tooltip("Mês seguinte"));
        btnNextMonth.setAccessibleText("Mês seguinte");
        btnCurrentMonth.getStyleClass().add("ghost-btn");
        btnCurrentMonth.setTooltip(new Tooltip("Voltar ao mês de hoje"));

        btnPrevMonth.setOnAction(e -> { currentMonth = currentMonth.minusMonths(1); reload(); });
        btnNextMonth.setOnAction(e -> { currentMonth = currentMonth.plusMonths(1); reload(); });
        btnCurrentMonth.setOnAction(e -> { currentMonth = YearMonth.now(); reload(); });

        monthLabel.getStyleClass().addAll("text-lg", "text-strong", "month-label");
        monthLabel.setText(monthText(currentMonth));
        HBox monthNav = new HBox(6, btnPrevMonth, monthLabel, btnNextMonth);
        monthNav.setAlignment(Pos.CENTER_LEFT);
        HeaderActions headerRow = new HeaderActions(header, monthNav, btnCurrentMonth);

        strip.set(STAT_GAIN, "—", "muted", "realizado + não realizado");

        buildFilters();
        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        loadingLabel.getStyleClass().add("text-helper");
        hide(loadingLabel);
        errorBanner.setGraphic(Icons.alertTriangle());
        errorBanner.setGraphicTextGap(8);
        errorBanner.setWrapText(true);
        errorBanner.getStyleClass().add("warning-banner");
        hide(errorBanner);

        legendLabel.getStyleClass().add("text-helper");
        legendLabel.setWrapText(true);

        Panel listPanel = new Panel("Lançamentos", filterBar, loadingLabel, errorBanner, table, legendLabel);
        VBox.setVgrow(listPanel, Priority.ALWAYS);

        details.setFocusTraversable(true);
        details.setOnClose(() -> {
            detailedRow = null;
            table.requestFocus();
        });
        root.getChildren().addAll(headerRow, strip, details, listPanel);

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
        reload();
    }

    @Override
    public void onBreakpoint(Breakpoint bp) {
        boolean narrow = bp == Breakpoint.NARROW;
        strip.setColumns(narrow ? 2 : 4);
        updateTableColumns();
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    private static void hide(javafx.scene.Node n) { n.setVisible(false); n.setManaged(false); }
    private static void show(javafx.scene.Node n) { n.setVisible(true); n.setManaged(true); }

    private void updateTableColumns() {
        if (table.getWidth() <= 0 || typeColRef == null) return;
        // Reserve scrollbar space even before the skin has materialized its bar. Width is the
        // table's actual space AFTER shell/sidebar, page/panel margins and outer scrolling.
        double scrollbar = 18;
        for (var node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                scrollbar = Math.max(scrollbar, bar.getWidth());
            }
        }
        double usable = table.getWidth() - table.getInsets().getLeft() - table.getInsets().getRight() - scrollbar;
        boolean compact = usable < 96 + 150 + 100;
        compactActions.set(compact);
        dateColRef.setMaxWidth(compact ? 80 : 110);
        dateColRef.setMinWidth(compact ? 64 : 96);
        actionColRef.setMaxWidth(compact ? 44 : 120);
        actionColRef.setMinWidth(compact ? 44 : 100);
        double fullMin = table.getColumns().stream().mapToDouble(TableColumn::getMinWidth).sum();
        boolean full = usable >= fullMin;
        numberColRef.setVisible(full);
        descColRef.setVisible(full);
        typeColRef.setVisible(usable >= fullMin - numberColRef.getMinWidth() - descColRef.getMinWidth());
    }

    private static String monthText(YearMonth month) {
        String s = month.format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR")));
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void buildFilters() {
        filterBar.setAlignment(Pos.CENTER_LEFT);
        for (Flow f : Flow.values()) {
            ToggleButton tb = new ToggleButton(f.label);
            tb.setToggleGroup(filterGroup);
            tb.setUserData(f);
            tb.getStyleClass().add("filter-toggle");
            filterBar.getChildren().add(tb);
            if (f == Flow.ALL) tb.setSelected(true);
        }
        filterGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { if (old != null) filterGroup.selectToggle(old); return; }
            applyFilter();
        });
    }

    private void applyFilter() {
        Toggle t = filterGroup.getSelectedToggle();
        Flow f = t == null ? Flow.ALL : (Flow) t.getUserData();
        ExtractRow selected = table.getSelectionModel().getSelectedItem();
        table.setItems(FXCollections.observableArrayList(allRows.stream().filter(f.keep).toList()));
        if (selected != null && table.getItems().contains(selected)) table.getSelectionModel().select(selected);
        if (detailedRow != null && !table.getItems().contains(detailedRow)) {
            detailedRow = null;
            details.hide(); // filtering/loading must not steal focus or leave a stale financial action
        }
    }

    private void buildTable() {
        table.getStyleClass().add("table-analytic");
        table.setFixedCellSize(42);

        TableColumn<ExtractRow, String> dateCol = new TableColumn<>("Data");
        dateColRef = dateCol;
        // The selected month supplies the year; full date remains in the named Details action.
        dateCol.setCellValueFactory(v -> javafx.beans.binding.Bindings.createStringBinding(
                () -> (compactActions.get() ? DateTimeFormatter.ofPattern("dd/MM") : DATE_FMT)
                        .format(v.getValue().date()), compactActions));
        dateCol.setMinWidth(96);
        dateCol.setMaxWidth(110);
        dateCol.setStyle("-fx-padding: 0 6;");

        TableColumn<ExtractRow, String> numberCol = new TableColumn<>("Nº");
        numberColRef = numberCol;
        numberCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().number()));
        numberCol.setMinWidth(48);
        numberCol.setMaxWidth(64);
        numberCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        numberCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("muted", "num");
                if (empty || item == null) { setText(null); return; }
                setText(item);
                getStyleClass().addAll("num", "muted");
            }
        });

        TableColumn<ExtractRow, String> typeCol = new TableColumn<>("Tipo");
        typeColRef = typeCol;
        typeCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().type()));
        typeCol.setMinWidth(120);
        typeCol.setMaxWidth(190);
        typeCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg", "muted");
                if (empty || item == null) { setText(null); setTooltip(null); return; }
                setText(item);
                ExtractRow r = getTableView().getItems().get(getIndex());
                // Supplemental hover hint; the named Details button is the primary access path.
                setTooltip(new Tooltip(("—".equals(r.number()) ? "Registro antigo" : "Nº " + r.number()) + " · " + r.description()));
                if (r.muted()) getStyleClass().add("muted");
                else if (r.value().startsWith("-")) getStyleClass().add("neg");
                else if (r.value().startsWith("+")) getStyleClass().add("pos");
            }
        });

        TableColumn<ExtractRow, String> descCol = new TableColumn<>("Descrição");
        descColRef = descCol;
        descCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().description()));
        descCol.setMinWidth(180);
        descCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("muted");
                if (empty || item == null) { setText(null); setTooltip(null); return; }
                setText(item);
                setTooltip(new Tooltip(item));
                if (getTableView().getItems().get(getIndex()).muted()) getStyleClass().add("muted");
            }
        });

        TableColumn<ExtractRow, String> valueCol = new TableColumn<>("Valor");
        valueCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().value()));
        valueCol.setMinWidth(150); // "+ R$ 1.234.567,89" at the table font plus cell padding, never cut
        valueCol.setMaxWidth(190);
        valueCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        valueCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg", "muted", "num");
                if (empty || item == null) { setText(null); return; }
                setText(item);
                getStyleClass().add("num");
                ExtractRow r = getTableView().getItems().get(getIndex());
                if (r.muted()) getStyleClass().add("muted");
                else if (item.startsWith("+")) getStyleClass().add("pos");
                else if (item.startsWith("-")) getStyleClass().add("neg");
            }
        });

        TableColumn<ExtractRow, Void> actionCol = new TableColumn<>("");
        actionColRef = actionCol;
        actionCol.setMinWidth(100);
        actionCol.setMaxWidth(120);
        actionCol.setSortable(false);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button detailsBtn = new Button("Detalhes");
            {
                detailsBtn.getStyleClass().add("icon-text-btn");
                detailsBtn.setGraphic(com.daniel.presentation.view.util.Icons.info());
                detailsBtn.setMinSize(24, 24);
                detailsBtn.contentDisplayProperty().bind(javafx.beans.binding.Bindings.when(compactActions)
                        .then(ContentDisplay.GRAPHIC_ONLY).otherwise(ContentDisplay.TEXT_ONLY));
                detailsBtn.styleProperty().bind(javafx.beans.binding.Bindings.when(compactActions)
                        .then("-fx-padding: 3;").otherwise(""));
                detailsBtn.setTooltip(new Tooltip("Ver lançamento completo e ações disponíveis"));
                detailsBtn.setOnAction(e -> {
                    int index = getIndex();
                    if (!isEmpty() && index >= 0 && index < getTableView().getItems().size()) {
                        showDetails(getTableView().getItems().get(index));
                    }
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                ExtractRow r = getTableView().getItems().get(getIndex());
                detailsBtn.setAccessibleText("Detalhes de " + r.type() + " em " + DATE_FMT.format(r.date())
                        + " — " + r.number());
                setGraphic(detailsBtn);
            }
        });

        table.setPlaceholder(EmptyState.of("📋", "Nenhum lançamento neste período",
                "Aportes, compras, vendas, ajustes e estornos registrados neste mês aparecem aqui."));

        table.getColumns().add(dateCol);
        table.getColumns().add(numberCol);
        table.getColumns().add(typeCol);
        table.getColumns().add(descCol);
        table.getColumns().add(valueCol);
        table.getColumns().add(actionCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.widthProperty().addListener((obs, old, width) -> updateTableColumns());
    }

    private record ReloadData(List<StatementEntry> entries, long lucroTotal, Long patrimony) {}

    private void reload() {
        final YearMonth month = currentMonth;
        monthLabel.setText(monthText(month));
        showLoading();

        final boolean isCurrentMonth = month.equals(YearMonth.now());
        final LocalDate refDate = isCurrentMonth ? LocalDate.now() : month.atEndOfMonth();
        final long epoch = reloadEpoch.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            List<StatementEntry> entries = daily.hasLedger() ? daily.statement(month) : legacyOnly(month);
            long lucroTotal = 0;
            Long patrimony = 0L;
            if (!entries.isEmpty()) {
                lucroTotal = daily.getTotalProfit(refDate);
                if (isCurrentMonth) {
                    patrimony = daily.getTotalPatrimony(LocalDate.now());
                } else {
                    TreeMap<LocalDate, Long> snaps = daily.getPortfolioSnapshotSeries(month.atDay(1), month.atEndOfMonth());
                    patrimony = snaps.isEmpty() ? null : snaps.lastEntry().getValue(); // no record → unknown, not zero
                }
            }
            return new ReloadData(entries, lucroTotal, patrimony);
        }, worker).whenComplete((data, err) -> ui.execute(() -> {
            if (reloadEpoch.get() != epoch) return;
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                showFailure("Não foi possível carregar os lançamentos de " + monthText(month) + ": " + c.getMessage());
                return;
            }
            showEntries(data.entries(), data.lucroTotal(), data.patrimony());
        }));
    }

    /** Loading state: the previous month's rows and figures leave the screen before the new label is read with them. */
    void showLoading() {
        allRows = List.of();
        applyFilter();
        hide(errorBanner);
        show(loadingLabel);
        for (String s : List.of(STAT_DEPOSITS, STAT_BUYS, STAT_GAIN, STAT_WEALTH)) strip.set(s, "—", "muted", "carregando…");
    }

    /** Failure state for THIS month: the message stays on the page; no rows and no figures are shown under it. */
    void showFailure(String message) {
        allRows = List.of();
        applyFilter();
        hide(loadingLabel);
        errorBanner.setText(message == null ? "Não foi possível carregar os lançamentos." : message);
        show(errorBanner);
        for (String s : List.of(STAT_DEPOSITS, STAT_BUYS, STAT_GAIN, STAT_WEALTH)) strip.set(s, "—", "muted", "não foi possível carregar");
    }

    /**
     * Renders the month: rows newest first, deposits/buys summed from the entries exactly as
     * before (reversed movements excluded; old BUY records counted as buys), gain and wealth as
     * given. An empty month shows "—" for gain/wealth (unknown), zero for the sums.
     */
    void showEntries(List<StatementEntry> entries, long lucroTotal, long patrimony) {
        showEntries(entries, lucroTotal, Long.valueOf(patrimony));
    }

    /** {@code patrimony == null} = unknown (no record at the end of the month); zero is a known value. */
    void showEntries(List<StatementEntry> entries, long lucroTotal, Long patrimony) {
        hide(loadingLabel);
        hide(errorBanner);
        List<ExtractRow> rows = new ArrayList<>();
        long deposits = 0, buys = 0;
        List<StatementEntry> ordered = new ArrayList<>(entries);
        Collections.reverse(ordered); // newest first
        for (StatementEntry e : ordered) {
            rows.add(toRow(e));
            if (!e.legacy() && !e.reversed()) {
                if (e.kind() == MovementKind.DEPOSIT) deposits += e.amountCents();
                if (e.kind() == MovementKind.BUY) buys += e.amountCents();
            }
            if (e.legacy() && "BUY".equals(e.legacyType())) buys += e.amountCents();
        }
        allRows = rows;
        applyFilter();

        strip.set(STAT_DEPOSITS, daily.brl(deposits), deposits == 0 ? "muted" : null);
        strip.set(STAT_BUYS, daily.brl(buys), buys == 0 ? "muted" : null);
        if (entries.isEmpty()) {
            strip.set(STAT_GAIN, "—", "muted", "sem lançamentos no mês");
            strip.set(STAT_WEALTH, "—", "muted", "sem lançamentos no mês");
        } else {
            String gain = lucroTotal > 0 ? "+ " + daily.brl(lucroTotal) : lucroTotal < 0 ? "- " + daily.brl(Math.abs(lucroTotal)) : daily.brl(0);
            strip.set(STAT_GAIN, gain, lucroTotal > 0 ? "pos" : lucroTotal < 0 ? "neg" : "muted", "realizado + não realizado");
            if (patrimony == null) {
                // Movements exist but no snapshot closed the month: the value is unknown, which is not zero.
                strip.set(STAT_WEALTH, "—", "muted", "sem registro no fim do mês");
            } else {
                strip.set(STAT_WEALTH, daily.brl(patrimony), null, currentMonth.equals(YearMonth.now()) ? "hoje" : "fim do mês");
            }
        }
    }

    private List<StatementEntry> legacyOnly(YearMonth month) {
        List<StatementEntry> out = new ArrayList<>();
        for (var tx : daily.listTransactions(month)) {
            out.add(new StatementEntry(tx.date(), null, tx.type(), null, tx.id(), (long) tx.investmentTypeId(), tx.name(),
                    null, tx.quantity() == null ? null : tx.quantity().longValue(),
                    tx.unitPriceCents() == null ? null : java.math.BigDecimal.valueOf(tx.unitPriceCents(), 2),
                    tx.totalCents(), null, null, tx.note(), null, null, false, true));
        }
        Collections.reverse(out); // listBetween is DESC; statement() convention is ASC
        return out;
    }

    private ExtractRow toRow(StatementEntry e) {
        String who = e.investmentName() != null ? e.investmentName() : "Caixa";
        StringBuilder desc = new StringBuilder();
        String type;
        String value;
        boolean muted = false;
        boolean reversible = false;

        if (e.legacy()) {
            boolean buy = "BUY".equals(e.legacyType());
            type = (buy ? "Compra" : "Venda") + " · registro antigo";
            desc.append(buy ? "Compra" : "Venda").append(" de ").append(who);
            if (e.quantity() != null && e.unitPrice() != null) {
                desc.append(" — ").append(e.quantity()).append(" x ").append(daily.brl(e.unitPrice().movePointRight(2).longValue()));
            }
            desc.append(" | do cadastro anterior, não entra no caixa nem nas posições");
            value = (buy ? "- " : "+ ") + daily.brl(e.amountCents());
            muted = true;
            if (e.note() != null) desc.append(" | ").append(e.note());
            return new ExtractRow(e, e.date(), "—", type, desc.toString(), value, muted, false, true, null);
        }

        type = kindLabel(e.kind());
        switch (e.kind()) {
            case OPENING -> {
                desc.append("Saldo inicial de ").append(who);
                if (e.quantity() != null) desc.append(" — ").append(e.quantity()).append(" un.");
                desc.append(" | não é aporte nem lucro");
                desc.append(e.reconciliationFlag() ? " | saldo pendente de revisão" : "");
                value = daily.brl(e.amountCents()); // the qualifier is in the description, not glued to the number
                muted = true;
            }
            case RECONCILIATION -> {
                desc.append("Revisão de saldo de ").append(who);
                if (e.quantity() != null) desc.append(" — ").append(e.quantity()).append(" un.");
                desc.append(" | saldo confirmado");
                if (e.reason() != null) desc.append(" | motivo: ").append(e.reason());
                value = daily.brl(e.amountCents());
                muted = true;
            }
            case DEPOSIT -> { desc.append("Aporte externo para o caixa"); value = "+ " + daily.brl(e.amountCents()); reversible = true; }
            case WITHDRAWAL -> { desc.append("Retirada externa do caixa"); value = "- " + daily.brl(e.amountCents()); reversible = true; }
            case BUY -> {
                desc.append("Compra de ").append(who);
                if (e.quantity() != null && e.unitPrice() != null) desc.append(" — ").append(e.quantity()).append(" x R$ ").append(e.unitPrice().toPlainString());
                value = "- " + daily.brl(e.amountCents());
                reversible = true;
            }
            case SELL -> {
                desc.append("Venda de ").append(who);
                if (e.quantity() != null && e.unitPrice() != null) desc.append(" — ").append(e.quantity()).append(" x R$ ").append(e.unitPrice().toPlainString());
                else desc.append(" — resgate ao custo");
                value = "+ " + daily.brl(e.amountCents());
                reversible = true;
            }
            case ADJUSTMENT -> {
                desc.append("Ajuste em ").append(who);
                if (e.classification() != null) desc.append(" (").append(classificationLabel(e.classification())).append(")");
                if (e.quantity() != null && e.quantity() != 0) desc.append(" — ").append(e.quantity() > 0 ? "+" : "").append(e.quantity()).append(" un.");
                if (e.reason() != null) desc.append(" | motivo: ").append(e.reason());
                value = (e.amountCents() < 0 ? "- " : "+ ") + daily.brl(Math.abs(e.amountCents()));
                reversible = true;
            }
            case REVERSAL -> {
                desc.append("Estorno do lançamento nº ").append(e.reversesId()).append(" (").append(who).append(")");
                if (e.reason() != null) desc.append(" | motivo: ").append(e.reason());
                value = "↺ " + daily.brl(Math.abs(e.amountCents()));
                muted = true;
            }
            default -> value = daily.brl(e.amountCents());
        }
        if (e.note() != null) desc.append(" | ").append(e.note());
        if (e.reversed()) {
            desc.append(" | estornado pelo lançamento nº ").append(e.reversedById());
            muted = true;
            reversible = false;
        }
        return new ExtractRow(e, e.date(), String.valueOf(e.movementId()), type, desc.toString(), value, muted, reversible, false, e.kind());
    }

    /** User-facing label of a movement kind; opening/review are named by what they mean. */
    private static String kindLabel(MovementKind kind) {
        return switch (kind) {
            case OPENING -> "Saldo inicial";
            case RECONCILIATION -> "Revisão de saldo";
            default -> DailyTrackingUseCase.kindLabel(kind);
        };
    }

    private static String classificationLabel(String c) {
        return switch (c) {
            case "CORRECTION" -> "correção";
            case "INCOME" -> "rendimento";
            case "EXPENSE" -> "despesa";
            default -> "outro";
        };
    }

    private void showDetails(ExtractRow row) {
        if (!table.getItems().contains(row)) return;
        detailedRow = row;
        table.getSelectionModel().select(row);
        StatementEntry entry = row.entry();
        String identification = row.legacy() ? "Registro antigo nº " + entry.legacyTransactionId() : "Lançamento nº " + row.number();
        details.setTitle(row.type(), identification);
        details.setHeadline(row.value(), "Valor do lançamento");
        details.clearFields();
        details.addField("Data", DATE_FMT.format(row.date()));
        details.addField("Identificação", identification);
        details.addField("Ativo", entry.investmentName() == null ? "Caixa" : entry.investmentName());
        if (entry.investmentTypeId() != null) details.addField("Nº do ativo", entry.investmentTypeId().toString());
        if (entry.quantity() != null) details.addField("Quantidade", entry.quantity().toString());
        if (entry.unitPrice() != null) details.addField("Preço unitário", "R$ " + entry.unitPrice().toPlainString());
        if (entry.classification() != null) details.addField("Classificação", classificationLabel(entry.classification()));
        if (entry.reason() != null) details.addField("Motivo", entry.reason());
        if (entry.note() != null) details.addField("Observação", entry.note());
        if (entry.reversesId() != null) details.addField("Estorna nº", entry.reversesId().toString());
        if (entry.reversedById() != null) details.addField("Estornado por nº", entry.reversedById().toString());
        details.setNote(row.description());
        if (row.reversible()) {
            Button reverse = new Button("Estornar");
            reverse.getStyleClass().add("danger-btn");
            reverse.setTooltip(new Tooltip("Pede motivo e confirmação antes de registrar o estorno"));
            reverse.setOnAction(e -> onReverse(row));
            details.setPrimaryActions(reverse);
        } else details.setPrimaryActions();
        details.setAdvancedActions();
        details.open(false);
        details.requestFocus();
        scrollPane.setVvalue(0);
    }

    private void onReverse(ExtractRow row) {
        if (!row.reversible() || !table.getItems().contains(row)) return;
        StatementEntry e = row.entry();
        if (e.movementId() == null) return;
        MovementDialog d = new MovementDialog("Estornar lançamento nº " + e.movementId(),
                "Cria um estorno vinculado que desfaz exatamente os efeitos de \"" + row.description() + "\". O original continua no extrato.",
                MovementForm.reversal(e.movementId()), daily, saved -> {
                    ToastHost.showSuccess("Estorno nº " + saved.id() + " registrado.");
                    UiExecutor.get().execute(() -> { try { daily.takeSnapshotIfNeeded(LocalDate.now()); } catch (Exception ignored) {} });
                    reload();
                });
        d.showAndWait();
    }

    /** One row of the statement as shown; {@code number} is the movement number ("—" for old records). */
    record ExtractRow(StatementEntry entry, LocalDate date, String number, String type, String description, String value,
                      boolean muted, boolean reversible, boolean legacy, MovementKind kind) {}
}

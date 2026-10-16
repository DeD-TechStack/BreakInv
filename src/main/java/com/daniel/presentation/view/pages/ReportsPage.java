package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.StatementEntry;
import com.daniel.core.domain.ledger.MovementKind;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.KpiCard;
import com.daniel.presentation.view.components.MovementDialog;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.view.util.UiSpacer;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Extrato unificado: cada movimento do ledger aparece uma vez (aberturas, reconciliações e
 * estornos identificados e vinculados); lançamentos legados da tabela {@code transactions}
 * aparecem marcados como "legado" e nunca são reproduzidos no ledger. Estorno de um movimento
 * é feito a partir do id da linha original.
 */
public final class ReportsPage implements Page {

    private final DailyTrackingUseCase daily;

    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();

    private final Button btnPrevMonth = new Button();
    private final Button btnNextMonth = new Button();
    private final Button btnCurrentMonth = new Button("Mês Atual");
    private final Label monthLabel = new Label();

    private final Label depositsLabel = new Label("—");
    private final Label buysLabel = new Label("—");
    private final Label accumulatedProfitLabel = new Label("—");
    private final Label patrimonyLabel = new Label("—");

    private final TableView<ExtractRow> table = new TableView<>();
    private final Label legendLabel = new Label(
            "Ledger: cada lançamento aparece uma vez; originais estornados ficam visíveis e apontam para o estorno. "
            + "Linhas \"legado\" vêm do registro antigo e não afetam posição/caixa do ledger.");

    private YearMonth currentMonth = YearMonth.now();
    private final AtomicLong reloadEpoch = new AtomicLong(0);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public ReportsPage(DailyTrackingUseCase daily) {
        this.daily = daily;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Extrato", "Movimentações do ledger e lançamentos legados por período");

        btnPrevMonth.setGraphic(Icons.chevronLeft());
        btnPrevMonth.setText(null);
        btnNextMonth.setGraphic(Icons.chevronRight());
        btnNextMonth.setText(null);
        btnPrevMonth.getStyleClass().add("icon-btn");
        btnNextMonth.getStyleClass().add("icon-btn");
        btnCurrentMonth.getStyleClass().add("ghost-btn");

        btnPrevMonth.setOnAction(e -> { currentMonth = currentMonth.minusMonths(1); reload(); });
        btnNextMonth.setOnAction(e -> { currentMonth = currentMonth.plusMonths(1); reload(); });
        btnCurrentMonth.setOnAction(e -> { currentMonth = YearMonth.now(); reload(); });

        monthLabel.getStyleClass().addAll("text-lg", "text-strong");
        Region spacer = UiSpacer.hGrow();
        HBox navToolbar = new HBox(8, monthLabel, spacer,
                btnPrevMonth, btnNextMonth, new Separator(javafx.geometry.Orientation.VERTICAL), btnCurrentMonth);
        navToolbar.getStyleClass().add("toolbar");
        navToolbar.setAlignment(Pos.CENTER_LEFT);

        HBox kpiRow = new HBox(12,
                KpiCard.compact("Aportes externos no mês", depositsLabel),
                KpiCard.compact("Compras no mês", buysLabel),
                KpiCard.compact("Ganho (realizado + não realizado)", accumulatedProfitLabel),
                KpiCard.compact("Patrimônio", patrimonyLabel)
        );

        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        legendLabel.getStyleClass().add("text-helper");
        legendLabel.setWrapText(true);

        VBox tableCard = new VBox(12, legendLabel, table);
        tableCard.getStyleClass().add("card");
        VBox.setVgrow(tableCard, Priority.ALWAYS);

        root.getChildren().addAll(header, navToolbar, kpiRow, tableCard);

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
        currentMonth = YearMonth.now();
        reload();
    }

    private void buildTable() {
        table.getStyleClass().add("table-analytic");

        TableColumn<ExtractRow, String> dateCol = new TableColumn<>("Data");
        dateCol.setCellValueFactory(v -> new SimpleStringProperty(DATE_FMT.format(v.getValue().date)));
        dateCol.setPrefWidth(110);

        TableColumn<ExtractRow, String> typeCol = new TableColumn<>("Tipo");
        typeCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().type));
        typeCol.setPrefWidth(140);
        typeCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg", "muted");
                if (empty || item == null) { setText(null); return; }
                setText(item);
                ExtractRow r = getTableView().getItems().get(getIndex());
                if (r.muted) getStyleClass().add("muted");
                else if (r.value.startsWith("-")) getStyleClass().add("neg");
                else if (r.value.startsWith("+")) getStyleClass().add("pos");
            }
        });

        TableColumn<ExtractRow, String> descCol = new TableColumn<>("Descrição");
        descCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().description));

        TableColumn<ExtractRow, String> valueCol = new TableColumn<>("Valor");
        valueCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().value));
        valueCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg", "muted");
                if (empty || item == null) { setText(null); return; }
                setText(item);
                ExtractRow r = getTableView().getItems().get(getIndex());
                if (r.muted) getStyleClass().add("muted");
                else if (item.startsWith("+")) getStyleClass().add("pos");
                else if (item.startsWith("-")) getStyleClass().add("neg");
            }
        });
        valueCol.setPrefWidth(150);

        TableColumn<ExtractRow, Void> actionCol = new TableColumn<>("");
        actionCol.setPrefWidth(110);
        actionCol.setSortable(false);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button reverseBtn = new Button("Estornar");
            {
                reverseBtn.getStyleClass().add("icon-text-btn");
                Tooltip.install(reverseBtn, new Tooltip("Registra um estorno vinculado a este lançamento (exige motivo)"));
                reverseBtn.setOnAction(e -> onReverse(getTableView().getItems().get(getIndex())));
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                ExtractRow r = getTableView().getItems().get(getIndex());
                setGraphic(r.reversible ? reverseBtn : null);
            }
        });

        table.setPlaceholder(EmptyState.of("📋", "Nenhum lançamento neste período",
                "Aportes, compras, vendas, ajustes e estornos registrados neste mês aparecerão aqui."));

        table.getColumns().add(dateCol);
        table.getColumns().add(typeCol);
        table.getColumns().add(descCol);
        table.getColumns().add(valueCol);
        table.getColumns().add(actionCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private record ReloadData(List<ExtractRow> rows, long deposits, long buys, boolean empty,
                              long lucroTotal, long patrimony) {}

    private void reload() {
        final YearMonth month = currentMonth;
        monthLabel.setText(month.format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))));

        final boolean isCurrentMonth = month.equals(YearMonth.now());
        final LocalDate refDate = isCurrentMonth ? LocalDate.now() : month.atEndOfMonth();
        final long epoch = reloadEpoch.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            List<StatementEntry> entries = daily.hasLedger() ? daily.statement(month) : legacyOnly(month);
            List<ExtractRow> rows = new ArrayList<>();
            long deposits = 0, buys = 0;
            // newest first, like the previous extract
            List<StatementEntry> ordered = new ArrayList<>(entries);
            Collections.reverse(ordered);
            for (StatementEntry e : ordered) {
                rows.add(toRow(e));
                if (!e.legacy() && !e.reversed()) {
                    if (e.kind() == MovementKind.DEPOSIT) deposits += e.amountCents();
                    if (e.kind() == MovementKind.BUY) buys += e.amountCents();
                }
                if (e.legacy() && "BUY".equals(e.legacyType())) buys += e.amountCents();
            }

            long lucroTotal = 0;
            long patrimony = 0;
            if (!entries.isEmpty()) {
                lucroTotal = daily.getTotalProfit(refDate);
                if (isCurrentMonth) {
                    patrimony = daily.getTotalPatrimony(LocalDate.now());
                } else {
                    TreeMap<LocalDate, Long> snaps = daily.getPortfolioSnapshotSeries(month.atDay(1), month.atEndOfMonth());
                    patrimony = snaps.isEmpty() ? 0 : snaps.lastEntry().getValue();
                }
            }
            return new ReloadData(rows, deposits, buys, entries.isEmpty(), lucroTotal, patrimony);
        }, UiExecutor.get()).whenComplete((data, err) -> Platform.runLater(() -> {
            if (reloadEpoch.get() != epoch) return;
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                ToastHost.showError("Falha ao carregar o extrato: " + c.getMessage());
                return;
            }
            table.setItems(FXCollections.observableArrayList(data.rows()));
            setKpiPositive(depositsLabel, data.deposits());
            setKpiPositive(buysLabel, data.buys());
            if (data.empty()) {
                setKpi(accumulatedProfitLabel, 0, true);
                setKpiPositive(patrimonyLabel, 0);
            } else {
                setKpi(accumulatedProfitLabel, data.lucroTotal(), true);
                setKpiPositive(patrimonyLabel, data.patrimony());
            }
        }));
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
            type = (buy ? "Compra" : "Venda") + " · legado";
            desc.append(buy ? "Compra" : "Venda").append(" de ").append(who);
            if (e.quantity() != null && e.unitPrice() != null) {
                desc.append(" — ").append(e.quantity()).append(" x ").append(daily.brl(e.unitPrice().movePointRight(2).longValue()));
            }
            desc.append(" | registro antigo, não reproduzido no ledger");
            value = (buy ? "- " : "+ ") + daily.brl(e.amountCents());
            muted = true;
        } else {
            type = DailyTrackingUseCase.kindLabel(e.kind());
            switch (e.kind()) {
                case OPENING -> {
                    desc.append("Marco de abertura de ").append(who);
                    if (e.quantity() != null) desc.append(" — ").append(e.quantity()).append(" un.");
                    desc.append(e.reconciliationFlag() ? " | pendente de reconciliação" : "");
                    value = daily.brl(e.amountCents()) + " (capital de abertura)";
                    muted = true;
                }
                case RECONCILIATION -> {
                    desc.append("Reconciliação de ").append(who);
                    if (e.quantity() != null) desc.append(" — ").append(e.quantity()).append(" un.");
                    if (e.reason() != null) desc.append(" | motivo: ").append(e.reason());
                    value = daily.brl(e.amountCents()) + " (saldo restabelecido)";
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
                    if (e.classification() != null) desc.append(" [").append(classificationLabel(e.classification())).append("]");
                    if (e.quantity() != null && e.quantity() != 0) desc.append(" — ").append(e.quantity() > 0 ? "+" : "").append(e.quantity()).append(" un.");
                    if (e.reason() != null) desc.append(" | motivo: ").append(e.reason());
                    value = (e.amountCents() < 0 ? "- " : "+ ") + daily.brl(Math.abs(e.amountCents()));
                    reversible = true;
                }
                case REVERSAL -> {
                    desc.append("Estorno do lançamento #").append(e.reversesId()).append(" (").append(who).append(")");
                    if (e.reason() != null) desc.append(" | motivo: ").append(e.reason());
                    value = "↺ " + daily.brl(Math.abs(e.amountCents()));
                    muted = true;
                }
                default -> value = daily.brl(e.amountCents());
            }
            if (e.note() != null) desc.append(" | ").append(e.note());
            if (e.reversed()) {
                desc.append(" | ESTORNADO pelo lançamento #").append(e.reversedById());
                muted = true;
                reversible = false;
            }
            desc.insert(0, "#" + e.movementId() + " ");
        }
        return new ExtractRow(e, e.date(), type, desc.toString(), value, muted, reversible);
    }

    private static String classificationLabel(String c) {
        return switch (c) {
            case "CORRECTION" -> "correção";
            case "INCOME" -> "rendimento";
            case "EXPENSE" -> "despesa";
            default -> "outro";
        };
    }

    private void onReverse(ExtractRow row) {
        StatementEntry e = row.entry;
        if (e.movementId() == null) return;
        MovementDialog d = new MovementDialog("Estornar lançamento #" + e.movementId(),
                "Cria um estorno vinculado que inverte exatamente os efeitos de \"" + row.description + "\". O original permanece no extrato.",
                MovementForm.reversal(e.movementId()), daily, saved -> {
                    ToastHost.showSuccess("Estorno #" + saved.id() + " registrado.");
                    UiExecutor.get().execute(() -> { try { daily.takeSnapshotIfNeeded(LocalDate.now()); } catch (Exception ignored) {} });
                    reload();
                });
        d.showAndWait();
    }

    /** Exibe valor com sinal (+/−) e cor verde/vermelho, ou "—" se zero. */
    private void setKpi(Label label, long cents, boolean showSign) {
        label.getStyleClass().removeAll("pos", "neg", "muted", "kpi-value");
        if (cents == 0) {
            label.setText("—");
            label.getStyleClass().addAll("kpi-value", "muted");
        } else if (cents > 0) {
            label.setText(showSign ? "+ " + daily.brl(cents) : daily.brl(cents));
            label.getStyleClass().addAll("kpi-value", "pos");
        } else {
            label.setText("- " + daily.brl(Math.abs(cents)));
            label.getStyleClass().addAll("kpi-value", "neg");
        }
    }

    /** Exibe valor sem sinal (sempre positivo, neutro), ou "—" se zero. */
    private void setKpiPositive(Label label, long cents) {
        label.getStyleClass().removeAll("pos", "neg", "muted", "kpi-value");
        if (cents == 0) {
            label.setText("—");
            label.getStyleClass().addAll("kpi-value", "muted");
        } else {
            label.setText(daily.brl(cents));
            label.getStyleClass().addAll("kpi-value");
        }
    }

    private record ExtractRow(StatementEntry entry, LocalDate date, String type, String description, String value,
                              boolean muted, boolean reversible) {}
}

package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.PageHeader;
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
import com.daniel.presentation.view.components.EmptyState;
import com.daniel.presentation.view.components.KpiCard;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.UiExecutor;
import com.daniel.presentation.view.util.UiSpacer;

public final class ReportsPage implements Page {

    private final DailyTrackingUseCase daily;

    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();

    private final Button btnPrevMonth = new Button();
    private final Button btnNextMonth = new Button();
    private final Button btnCurrentMonth = new Button("Mês Atual");
    private final Label monthLabel = new Label();

    private final Label totalComprasLabel = new Label("—");
    private final Label totalVendasLabel = new Label("—");
    private final Label lucroRealizadoLabel = new Label("—");

    private final TableView<ExtractRow> table = new TableView<>();

    private YearMonth currentMonth = YearMonth.now();
    private final AtomicLong reloadEpoch = new AtomicLong(0);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public ReportsPage(DailyTrackingUseCase daily) {
        this.daily = daily;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Extrato", "Registro de compras e vendas por período");

        // ── Month Nav Toolbar ────────────────────────────────────────────────
        btnPrevMonth.setGraphic(Icons.chevronLeft());
        btnPrevMonth.setText(null);
        btnNextMonth.setGraphic(Icons.chevronRight());
        btnNextMonth.setText(null);
        btnPrevMonth.getStyleClass().add("icon-btn");
        btnNextMonth.getStyleClass().add("icon-btn");
        btnCurrentMonth.getStyleClass().add("ghost-btn");

        btnPrevMonth.setOnAction(e -> {
            currentMonth = currentMonth.minusMonths(1);
            reload();
        });
        btnNextMonth.setOnAction(e -> {
            currentMonth = currentMonth.plusMonths(1);
            reload();
        });
        btnCurrentMonth.setOnAction(e -> {
            currentMonth = YearMonth.now();
            reload();
        });

        monthLabel.getStyleClass().addAll("text-lg", "text-strong");

        Region spacer = UiSpacer.hGrow();

        HBox navToolbar = new HBox(8, monthLabel, spacer,
                btnPrevMonth, btnNextMonth, new Separator(javafx.geometry.Orientation.VERTICAL), btnCurrentMonth);
        navToolbar.getStyleClass().add("toolbar");
        navToolbar.setAlignment(Pos.CENTER_LEFT);

        // ── KPI Cards ────────────────────────────────────────────────────────
        HBox kpiRow = new HBox(12,
                KpiCard.compact("Total de aportes", totalComprasLabel),
                KpiCard.compact("Lucro acumulado", totalVendasLabel),
                KpiCard.compact("Patrimônio", lucroRealizadoLabel)
        );

        // ── Table ────────────────────────────────────────────────────────────
        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox tableCard = new VBox(12, table);
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
        dateCol.setPrefWidth(120);

        TableColumn<ExtractRow, String> typeCol = new TableColumn<>("Tipo");
        typeCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().type));
        typeCol.setPrefWidth(100);
        typeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    getStyleClass().add("Compra".equals(item) ? "neg" : "pos");
                }
            }
        });

        TableColumn<ExtractRow, String> descCol = new TableColumn<>("Descrição");
        descCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().description));

        TableColumn<ExtractRow, String> valueCol = new TableColumn<>("Valor");
        valueCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().value));
        valueCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("pos", "neg");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    if (item.startsWith("+")) {
                        getStyleClass().add("pos");
                    } else if (item.startsWith("-")) {
                        getStyleClass().add("neg");
                    }
                }
            }
        });
        valueCol.setPrefWidth(150);

        table.setPlaceholder(EmptyState.of("📋", "Nenhum lançamento neste período",
                "Compras e vendas registradas neste mês aparecerão aqui."));

        table.getColumns().add(dateCol);
        table.getColumns().add(typeCol);
        table.getColumns().add(descCol);
        table.getColumns().add(valueCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private record ReloadData(
            List<ExtractRow> rows,
            long totalCompras,
            boolean empty,
            long lucroTotal,
            long patrimony
    ) {}

    private void reload() {
        final YearMonth month = currentMonth;
        monthLabel.setText(month.format(
                DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))));

        final boolean isCurrentMonth = month.equals(YearMonth.now());
        final LocalDate refDate = isCurrentMonth ? LocalDate.now() : month.atEndOfMonth();
        final long epoch = reloadEpoch.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            List<Transaction> transactions = daily.listTransactions(month);

            List<ExtractRow> rows = new ArrayList<>();
            long totalCompras = 0;

            for (Transaction tx : transactions) {
                boolean isBuy = Transaction.BUY.equals(tx.type());
                String type = isBuy ? "Compra" : "Venda";

                StringBuilder desc = new StringBuilder();
                desc.append(type).append(" de ").append(tx.name());
                if (tx.ticker() != null) {
                    desc.append(" (").append(tx.ticker()).append(")");
                }
                if (tx.quantity() != null && tx.unitPriceCents() != null) {
                    desc.append(" — ").append(tx.quantity()).append(" x ").append(daily.brl(tx.unitPriceCents()));
                }
                if (tx.note() != null) {
                    desc.append(" | ").append(tx.note());
                }

                String value;
                if (isBuy) {
                    value = "- " + daily.brl(tx.totalCents());
                    totalCompras += tx.totalCents();
                } else {
                    value = "+ " + daily.brl(tx.totalCents());
                }

                rows.add(new ExtractRow(tx.date(), type, desc.toString(), value));
            }

            long lucroTotal = 0;
            long patrimony = 0;
            if (!transactions.isEmpty()) {
                lucroTotal = daily.getTotalProfit(refDate);
                if (isCurrentMonth) {
                    patrimony = daily.getTotalPatrimony(LocalDate.now());
                } else {
                    TreeMap<LocalDate, Long> snaps = daily.getPortfolioSnapshotSeries(
                            month.atDay(1), month.atEndOfMonth());
                    patrimony = snaps.isEmpty() ? 0 : snaps.lastEntry().getValue();
                }
            }

            return new ReloadData(rows, totalCompras, transactions.isEmpty(), lucroTotal, patrimony);
        }, UiExecutor.get()).thenAcceptAsync(data -> {
            if (reloadEpoch.get() != epoch) return;
            Platform.runLater(() -> {
                table.setItems(FXCollections.observableArrayList(data.rows()));
                // KPI 1: Total de aportes
                setKpi(totalComprasLabel, data.totalCompras(), false);
                // KPI 2 & 3: never leak global state into an empty period
                if (data.empty()) {
                    setKpi(totalVendasLabel, 0, true);
                    setKpiPositive(lucroRealizadoLabel, 0);
                } else {
                    setKpi(totalVendasLabel, data.lucroTotal(), true);
                    setKpiPositive(lucroRealizadoLabel, data.patrimony());
                }
            });
        });
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

    private record ExtractRow(
            LocalDate date,
            String type,
            String description,
            String value
    ) {}
}

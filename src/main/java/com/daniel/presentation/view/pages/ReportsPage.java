package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.Transaction;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.PageHeader;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ReportsPage implements Page {

    private final DailyTrackingUseCase daily;

    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();

    private final Button btnPrevMonth = new Button("◀");
    private final Button btnNextMonth = new Button("▶");
    private final Button btnCurrentMonth = new Button("Mês Atual");
    private final Label monthLabel = new Label();

    private final Label totalComprasLabel = new Label("—");
    private final Label totalVendasLabel = new Label("—");
    private final Label lucroRealizadoLabel = new Label("—");

    private final TableView<ExtractRow> table = new TableView<>();

    private YearMonth currentMonth = YearMonth.now();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public ReportsPage(DailyTrackingUseCase daily) {
        this.daily = daily;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Extrato", "Registro de compras e vendas por período");

        // ── Month Nav Toolbar ────────────────────────────────────────────────
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox navToolbar = new HBox(8, monthLabel, spacer,
                btnPrevMonth, btnNextMonth, new Separator(javafx.geometry.Orientation.VERTICAL), btnCurrentMonth);
        navToolbar.getStyleClass().add("toolbar");
        navToolbar.setAlignment(Pos.CENTER_LEFT);

        // ── KPI Cards ────────────────────────────────────────────────────────
        HBox kpiRow = new HBox(12,
                kpiCard("Total de aportes", totalComprasLabel),
                kpiCard("Total rendido", totalVendasLabel),
                kpiCard("Patrimônio alcançado no mês", lucroRealizadoLabel)
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

    private VBox kpiCard(String title, Label value) {
        VBox b = new VBox(6);
        b.getStyleClass().add("kpi-card");
        Label t = new Label(title);
        t.getStyleClass().add("kpi-label");
        value.getStyleClass().addAll("kpi-value", "num");
        b.getChildren().addAll(t, value);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
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

        VBox emptyState = new VBox(8);
        emptyState.getStyleClass().add("empty-state");
        emptyState.setAlignment(Pos.CENTER);
        Label emptyIcon = new Label("📋");
        emptyIcon.getStyleClass().add("empty-icon");
        Label emptyTitle = new Label("Nenhum lançamento neste período");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyHint = new Label("Registre compras ou vendas para vê-las aqui");
        emptyHint.getStyleClass().add("empty-hint");
        emptyState.getChildren().addAll(emptyIcon, emptyTitle, emptyHint);
        table.setPlaceholder(emptyState);

        table.getColumns().setAll(dateCol, typeCol, descCol, valueCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void reload() {
        monthLabel.setText(currentMonth.format(
                DateTimeFormatter.ofPattern("MMMM 'de' yyyy", new Locale("pt", "BR"))));

        List<Transaction> transactions = daily.listTransactions(currentMonth);

        List<ExtractRow> rows = new ArrayList<>();
        long totalCompras = 0;
        long totalVendas = 0;

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
                totalVendas += tx.totalCents();
            }

            rows.add(new ExtractRow(tx.date(), type, desc.toString(), value));
        }

        table.setItems(FXCollections.observableArrayList(rows));

        // ── KPI 1: Total de aportes ─────────────────────────────────────────
        // Soma das compras do período (dinheiro investido)
        setKpi(totalComprasLabel, totalCompras, false);

        // ── KPI 2: Valorização da carteira no mês ───────────────────────────
        // Lucro total acumulado = valor de mercado atual - total investido
        // (método mais confiável sem necessidade de snapshots históricos)
        long lucroTotal = daily.getTotalProfit(LocalDate.now());
        setKpi(totalVendasLabel, lucroTotal, true);

        // ── KPI 3: Patrimônio no fim do mês ─────────────────────────────────
        // Para o mês atual: valor de mercado calculado com preços ao vivo.
        // Para meses anteriores: último snapshot disponível do período.
        boolean isCurrentMonth = currentMonth.equals(java.time.YearMonth.now());
        LocalDate refDate = isCurrentMonth
                ? LocalDate.now()
                : currentMonth.atEndOfMonth();
        long patrimonio = daily.getTotalPatrimony(refDate);
        setKpiPositive(lucroRealizadoLabel, patrimonio);
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

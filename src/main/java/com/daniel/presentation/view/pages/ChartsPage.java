package com.daniel.presentation.view.pages;

import com.daniel.core.domain.entity.InvestmentType;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.PageHeader;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public final class ChartsPage implements Page {

    private final DailyTrackingUseCase daily;
    private final VBox root = new VBox(20);
    private final ScrollPane scrollPane = new ScrollPane();

    private final ComboBox<InvestmentType> picker = new ComboBox<>();
    private final ComboBox<Integer> range = new ComboBox<>();
    private VBox noDataOverlay;
    private Label noDataHint;

    private final CategoryAxis x = new CategoryAxis();
    private final NumberAxis y = new NumberAxis();
    private final LineChart<String, Number> chart = new LineChart<>(x, y);

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM");

    public ChartsPage(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;

        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Gráficos", "Acompanhe a evolução do valor de cada ativo");

        // ── Picker Toolbar ────────────────────────────────────────────────────
        picker.setItems(FXCollections.observableArrayList(daily.listTypes()));
        picker.setPromptText("Selecione um investimento...");
        picker.setMaxWidth(Double.MAX_VALUE);

        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(InvestmentType t) {
                return t == null ? "" : t.name();
            }

            @Override
            public InvestmentType fromString(String string) {
                return null;
            }
        });

        picker.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(InvestmentType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });

        picker.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(InvestmentType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });

        range.setItems(FXCollections.observableArrayList(30, 60, 90, 180, 365));
        range.setValue(90);

        Label investLabel = new Label("Ativo:");
        investLabel.getStyleClass().add("form-label");
        Label windowLabel = new Label("Janela (dias):");
        windowLabel.getStyleClass().add("form-label");

        HBox pickerToolbar = new HBox(12);
        pickerToolbar.getStyleClass().add("toolbar");
        pickerToolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(picker, Priority.ALWAYS);

        pickerToolbar.getChildren().addAll(investLabel, picker, windowLabel, range);

        // ── Chart Card ────────────────────────────────────────────────────────
        chart.setAnimated(true);
        chart.setLegendVisible(false);
        chart.setCreateSymbols(true);
        chart.setMinHeight(420);

        // No-data overlay
        noDataOverlay = new VBox(8);
        noDataOverlay.getStyleClass().add("empty-state");
        noDataOverlay.setAlignment(javafx.geometry.Pos.CENTER);
        noDataOverlay.setPickOnBounds(false);
        Label noDataIcon = new Label("📉");
        noDataIcon.getStyleClass().add("empty-icon");
        Label noDataTitle = new Label("Nenhum dado disponível");
        noDataTitle.getStyleClass().add("empty-title");
        noDataHint = new Label("Selecione um ativo com entradas registradas");
        noDataHint.getStyleClass().add("empty-hint");
        noDataOverlay.getChildren().addAll(noDataIcon, noDataTitle, noDataHint);
        noDataOverlay.setVisible(false);
        noDataOverlay.setManaged(false);

        StackPane chartStack = new StackPane(chart, noDataOverlay);
        VBox.setVgrow(chartStack, Priority.ALWAYS);

        VBox chartCard = new VBox(8);
        chartCard.getStyleClass().add("chart-card");

        HBox chartHeader = new HBox();
        chartHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label chartTitle = new Label("EVOLUÇÃO DO VALOR");
        chartTitle.getStyleClass().add("card-title");
        chartHeader.getChildren().add(chartTitle);

        chartCard.getChildren().addAll(chartHeader, chartStack);
        VBox.setVgrow(chartCard, Priority.ALWAYS);

        root.getChildren().addAll(header, pickerToolbar, chartCard);

        scrollPane.setContent(root);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("page-scroll");

        picker.valueProperty().addListener((o, a, b) -> reload());
        range.valueProperty().addListener((o, a, b) -> reload());
    }

    @Override
    public Parent view() {
        return scrollPane;
    }

    @Override
    public void onShow() {
        picker.setItems(FXCollections.observableArrayList(daily.listTypes()));
        if (picker.getItems().isEmpty()) {
            if (noDataHint != null) {
                noDataHint.setText("Cadastre um investimento em \"Meus Investimentos\" para começar");
            }
            setNoDataVisible(true);
        } else {
            if (noDataHint != null) {
                noDataHint.setText("Selecione um ativo com entradas registradas");
            }
            if (picker.getValue() == null) {
                picker.setValue(picker.getItems().get(0));
            }
            reload();
        }
    }

    private void setNoDataVisible(boolean visible) {
        if (noDataOverlay != null) {
            noDataOverlay.setVisible(visible);
            noDataOverlay.setManaged(visible);
        }
    }

    private void reload() {
        InvestmentType t = picker.getValue();
        if (t == null) {
            chart.getData().clear();
            setNoDataVisible(true);
            return;
        }

        var points = daily.seriesForInvestment(t.id());
        int days = range.getValue() == null ? 90 : range.getValue();

        if (points.size() > days) {
            points = new ArrayList<>(points.subList(points.size() - days, points.size()));
        }

        if (points.isEmpty()) {
            chart.getData().clear();
            setNoDataVisible(true);
            return;
        }

        setNoDataVisible(false);
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(t.name());

        for (var p : points) {
            var node = new XYChart.Data<String, Number>(DMY.format(p.date()), p.valueCents() / 100.0);
            s.getData().add(node);
        }

        chart.getData().setAll(s);

        for (var d : s.getData()) {
            d.nodeProperty().addListener((obs, oldN, n) -> {
                if (n != null) {
                    double value = ((Number) d.getYValue()).doubleValue();
                    Tooltip.install(n, new Tooltip(String.format("R$ %.2f", value).replace('.', ',')));
                }
            });
        }
    }
}

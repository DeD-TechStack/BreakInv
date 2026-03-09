package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.BcbClient;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.components.WelcomeOverlay;
import com.daniel.presentation.view.util.Motion;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.awt.Desktop;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

public final class ConfiguracoesPage implements Page {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AppSettingsRepository settings = new AppSettingsRepository();
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(20);

    // Brapi section
    private final TextField tokenField = new TextField();
    private final CheckBox autoUpdateCheckbox = new CheckBox("Atualizar cotações automaticamente ao abrir o app");
    private final Label tokenStatusLabel = new Label();

    // BCB section
    private final Label cdiValueLabel = new Label("—");
    private final Label selicValueLabel = new Label("—");
    private final Label ipcaValueLabel = new Label("—");
    private final Label bcbLastUpdateLabel = new Label("Nunca atualizado");

    public ConfiguracoesPage() {
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Configurações",
                "Gerencie tokens de API e preferências do app");

        root.getChildren().addAll(header, buildBrapiSection(), buildBcbSection(), buildAboutSection());

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
        loadSavedSettings();
    }

    private void loadSavedSettings() {
        String savedToken = settings.get(BrapiClient.SETTINGS_KEY_TOKEN).orElse("");
        tokenField.setText(savedToken);

        String autoUpdate = settings.get("brapi_auto_update").orElse("false");
        autoUpdateCheckbox.setSelected(Boolean.parseBoolean(autoUpdate));

        updateTokenStatus(savedToken);
        loadBcbCachedValues();
    }

    private void updateTokenStatus(String token) {
        tokenStatusLabel.getStyleClass().removeAll("status-success", "status-warning", "status-danger", "status-neutral");
        if (token == null || token.isBlank()) {
            tokenStatusLabel.setText("Token não configurado — cotações de ações usarão preço de compra como referência.");
            tokenStatusLabel.getStyleClass().add("status-warning");
        } else {
            tokenStatusLabel.setText("Token configurado.");
            tokenStatusLabel.getStyleClass().add("status-success");
        }
    }

    private void loadBcbCachedValues() {
        String cdi = settings.get("rate_cdi").orElse(null);
        String selic = settings.get("rate_selic").orElse(null);
        String ipca = settings.get("rate_ipca").orElse(null);
        String lastUpdate = settings.get("rate_last_update").orElse(null);

        cdiValueLabel.setText(cdi != null ? String.format("%.2f%%", Double.parseDouble(cdi) * 100) : "—");
        selicValueLabel.setText(selic != null ? String.format("%.2f%%", Double.parseDouble(selic) * 100) : "—");
        ipcaValueLabel.setText(ipca != null ? String.format("%.2f%%", Double.parseDouble(ipca) * 100) : "—");
        bcbLastUpdateLabel.setText(lastUpdate != null ? "Atualizado em: " + lastUpdate : "Nunca atualizado");
    }

    private VBox buildBrapiSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");

        Label title = new Label("API BRAPI — COTAÇÕES DE AÇÕES");
        title.getStyleClass().add("card-title");

        Label tokenLabel = new Label("Token Brapi");
        tokenLabel.getStyleClass().add("form-label");

        tokenField.setPromptText("Cole seu token aqui...");
        tokenField.getStyleClass().add("code-field");

        Label tokenHintPrefix = new Label("Obtenha seu token gratuito em ");
        tokenHintPrefix.getStyleClass().add("text-helper");

        Hyperlink brapiLink = new Hyperlink("brapi.dev");
        brapiLink.getStyleClass().addAll("text-helper", "text-link");
        brapiLink.setOnAction(e -> openBrapiWebsite());

        HBox tokenHint = new HBox(0, tokenHintPrefix, brapiLink);
        tokenHint.setAlignment(Pos.CENTER_LEFT);

        tokenStatusLabel.setWrapText(true);
        updateTokenStatus(tokenField.getText());

        tokenField.textProperty().addListener((obs, old, newVal) -> updateTokenStatus(newVal));

        Button testBtn = new Button("Testar Token");
        testBtn.getStyleClass().add("secondary-btn");
        testBtn.setOnAction(e -> testToken());

        Button saveBtn = new Button("Salvar Configurações");
        saveBtn.getStyleClass().add("button");
        saveBtn.setOnAction(e -> saveSettings());

        HBox btnRow = new HBox(10, testBtn, saveBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Button showWelcomeBtn = new Button("Mostrar tela de boas-vindas");
        showWelcomeBtn.getStyleClass().add("ghost-btn");
        showWelcomeBtn.setOnAction(e -> {
            WelcomeOverlay.resetShown();
            WelcomeOverlay.requestShow();
            ToastHost.showInfo("Boas-vindas exibidas!");
        });

        Separator sep = new Separator();

        card.getChildren().addAll(
                title,
                new VBox(6, tokenLabel, tokenField, tokenHint),
                tokenStatusLabel,
                sep,
                autoUpdateCheckbox,
                btnRow,
                showWelcomeBtn
        );
        return card;
    }

    private VBox buildBcbSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");

        Label title = new Label("BENCHMARKS — BANCO CENTRAL DO BRASIL");
        title.getStyleClass().add("card-title");

        Label hint = new Label("Taxas oficiais CDI, SELIC e IPCA da API do BCB.");
        hint.getStyleClass().add("text-helper");

        // Rate KPI row
        HBox rateRow = new HBox(12,
                rateKpi("CDI a.a.", cdiValueLabel),
                rateKpi("SELIC a.a.", selicValueLabel),
                rateKpi("IPCA a.a.", ipcaValueLabel)
        );

        bcbLastUpdateLabel.getStyleClass().add("text-helper");

        Button updateBtn = new Button("Atualizar CDI / SELIC / IPCA");
        updateBtn.getStyleClass().add("button");
        updateBtn.setOnAction(e -> fetchBcbRates(updateBtn));

        card.getChildren().addAll(title, hint, rateRow, bcbLastUpdateLabel, updateBtn);
        return card;
    }

    private VBox rateKpi(String label, Label valueLabel) {
        VBox box = new VBox(4);
        box.getStyleClass().add("kpi-card");
        HBox.setHgrow(box, Priority.ALWAYS);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("kpi-label");
        valueLabel.getStyleClass().addAll("kpi-value", "state-positive", "num");
        box.getChildren().addAll(lbl, valueLabel);
        Motion.hoverLift(box);
        return box;
    }

    private VBox buildAboutSection() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        Label title = new Label("SOBRE O APP");
        title.getStyleClass().add("card-title");

        Label version = new Label("Investment Tracker v0.5.0");
        version.getStyleClass().add("text-lg");

        Label apis = new Label("APIs utilizadas: Brapi (brapi.dev) para cotações da B3 e BCB (api.bcb.gov.br) para taxas oficiais.");
        apis.getStyleClass().add("muted");
        apis.setWrapText(true);

        Label privacy = new Label("Todos os dados são armazenados localmente. Nenhum dado pessoal é enviado a servidores externos além das chamadas às APIs acima.");
        privacy.getStyleClass().add("text-helper");
        privacy.setWrapText(true);

        card.getChildren().addAll(title, version, new Separator(), apis, privacy);
        return card;
    }

    private void testToken() {
        String token = tokenField.getText().trim();
        if (token.isBlank()) {
            tokenStatusLabel.setText("Digite um token antes de testar.");
            setTokenStatusClass("status-warning");
            return;
        }

        tokenStatusLabel.setText("Testando conexão...");
        setTokenStatusClass("status-neutral");

        CompletableFuture.supplyAsync(() -> BrapiClient.testConnectionWithToken(token))
                .thenAcceptAsync(ok -> Platform.runLater(() -> {
                    if (ok) {
                        tokenStatusLabel.setText("Token válido! Conexão estabelecida com sucesso.");
                        setTokenStatusClass("status-success");
                        ToastHost.showSuccess("Token Brapi válido!");
                    } else {
                        tokenStatusLabel.setText("Token inválido ou sem conexão com a Brapi.");
                        setTokenStatusClass("status-danger");
                        ToastHost.showError("Token inválido ou sem conexão.");
                    }
                }));
    }

    private void setTokenStatusClass(String cls) {
        tokenStatusLabel.getStyleClass().removeAll("status-success", "status-warning", "status-danger", "status-neutral");
        tokenStatusLabel.getStyleClass().add(cls);
    }

    private void openBrapiWebsite() {
        CompletableFuture.runAsync(() -> {
            try {
                Desktop.getDesktop().browse(new URI("https://brapi.dev/"));
            } catch (Exception ex) {
                Platform.runLater(() ->
                        ToastHost.showError("Não foi possível abrir o navegador."));
            }
        });
    }

    private void saveSettings() {
        String token = tokenField.getText().trim();
        if (token.isBlank()) {
            settings.delete(BrapiClient.SETTINGS_KEY_TOKEN);
            settings.set("brapi_auto_update", String.valueOf(autoUpdateCheckbox.isSelected()));
            updateTokenStatus(token);
            ToastHost.showWarn("Token removido — cotações usarão preço de compra como referência.");
            return;
        }

        settings.set(BrapiClient.SETTINGS_KEY_TOKEN, token);
        settings.set("brapi_auto_update", String.valueOf(autoUpdateCheckbox.isSelected()));

        updateTokenStatus(token);
        ToastHost.showSuccess("Configurações salvas com sucesso!");
    }

    private void fetchBcbRates(Button btn) {
        btn.setDisable(true);
        btn.setText("Atualizando...");

        CompletableFuture.supplyAsync(() -> {
            double cdi = BcbClient.fetchCdi().orElse(-1.0);
            double selic = BcbClient.fetchSelic().orElse(-1.0);
            double ipca = BcbClient.fetchIpca().orElse(-1.0);
            return new double[]{cdi, selic, ipca};
        }).thenAcceptAsync(rates -> Platform.runLater(() -> {
            btn.setDisable(false);
            btn.setText("Atualizar CDI / SELIC / IPCA");

            boolean anySuccess = false;

            if (rates[0] > 0) {
                settings.set("rate_cdi", String.valueOf(rates[0]));
                Motion.animateLabelChange(cdiValueLabel, String.format("%.2f%%", rates[0] * 100));
                anySuccess = true;
            }
            if (rates[1] > 0) {
                settings.set("rate_selic", String.valueOf(rates[1]));
                Motion.animateLabelChange(selicValueLabel, String.format("%.2f%%", rates[1] * 100));
                anySuccess = true;
            }
            if (rates[2] > 0) {
                settings.set("rate_ipca", String.valueOf(rates[2]));
                Motion.animateLabelChange(ipcaValueLabel, String.format("%.2f%%", rates[2] * 100));
                anySuccess = true;
            }

            if (anySuccess) {
                String now = LocalDateTime.now().format(DT_FMT);
                settings.set("rate_last_update", now);
                bcbLastUpdateLabel.setText("Atualizado em: " + now);
                ToastHost.showSuccess("Taxas BCB atualizadas!");
            } else {
                bcbLastUpdateLabel.setText("Falha ao buscar taxas. Verifique sua conexão.");
                ToastHost.showError("Falha ao buscar taxas do BCB.");
            }
        }));
    }
}

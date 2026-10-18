package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.BcbClient;
import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.daniel.presentation.view.PageHeader;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.components.StatStrip;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.components.WelcomeOverlay;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.UiPreferences;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;

import java.awt.Desktop;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Configurações (direction A): three groups in the order the user reasons about them —
 * appearance and behaviour, integrations (stock quotes, reference rates), data and about —
 * as panel blocks. Controls are named, the plan toggles wrap, the rates are a value strip
 * that reflows, and texts speak the user's language.
 */
public final class ConfiguracoesPage implements ResponsivePage {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AppSettingsRepository settings = new AppSettingsRepository();
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox root = new VBox(16);

    public static final String SETTINGS_KEY_BRAPI_DELAY = "brapi_refresh_delay_minutes";

    // Appearance / behaviour
    private final UiPreferences uiPrefs;
    private final CheckBox reduceMotionCheck = new CheckBox("Reduzir animações");
    private final Button showWelcomeBtn = new Button("Mostrar boas-vindas de novo");

    // Quotes (BRAPI)
    private final TextField tokenField = new TextField();
    private final CheckBox autoUpdateCheckbox = new CheckBox("Atualizar cotações automaticamente ao abrir o aplicativo");
    private final Label tokenStatusLabel = new Label();
    private final Button testTokenBtn = new Button("Testar token");
    private final Button saveBtn = new Button("Salvar");
    private final ToggleGroup planToggleGroup = new ToggleGroup();
    private final FlowPane planRow = new FlowPane(8, 8);
    private final TextField customIntervalField = new TextField();
    private final HBox customIntervalBox = new HBox(8);

    // Reference rates (BCB)
    static final String STAT_CDI = "CDI a.a.";
    static final String STAT_SELIC = "SELIC a.a.";
    static final String STAT_IPCA = "IPCA a.a.";
    private final StatStrip ratesStrip = new StatStrip(STAT_CDI, STAT_SELIC, STAT_IPCA);
    private final Label bcbLastUpdateLabel = new Label("Nunca atualizado");
    private final Button updateRatesBtn = new Button("Atualizar CDI, SELIC e IPCA");

    public ConfiguracoesPage() {
        this(UiPreferences.forUser());
    }

    /** The shell injects the app-wide preferences; tests inject an in-memory node. */
    public ConfiguracoesPage(UiPreferences uiPrefs) {
        this.uiPrefs = uiPrefs;
        root.getStyleClass().add("page-root");

        PageHeader header = new PageHeader("Configurações",
                "Aparência, integrações e dados do aplicativo");

        root.getChildren().addAll(header, buildAppearanceSection(), buildBrapiSection(), buildBcbSection(), buildAboutSection());

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
        reduceMotionCheck.setSelected(uiPrefs.reduceMotion());
        loadSavedSettings();
    }

    @Override
    public void onBreakpoint(Breakpoint bp) {
        ratesStrip.setColumns(bp == Breakpoint.NARROW ? 1 : 3);
        root.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        root.getStyleClass().add(bp.styleClass());
    }

    private void loadSavedSettings() {
        String savedToken = settings.get(BrapiClient.SETTINGS_KEY_TOKEN).orElse("");
        tokenField.setText(savedToken);

        String autoUpdate = settings.get("brapi_auto_update").orElse("true");
        autoUpdateCheckbox.setSelected(Boolean.parseBoolean(autoUpdate));

        updateTokenStatus(savedToken);
        loadBcbCachedValues();
        loadPlanSelection();
    }

    private void loadPlanSelection() {
        int savedMinutes = Integer.parseInt(
                settings.get(SETTINGS_KEY_BRAPI_DELAY).orElse("30"));

        boolean matchedPlan = false;
        for (Toggle t : planToggleGroup.getToggles()) {
            int planMinutes = (int) ((ToggleButton) t).getUserData();
            if (planMinutes == savedMinutes) {
                planToggleGroup.selectToggle(t);
                customIntervalBox.setVisible(false);
                customIntervalBox.setManaged(false);
                matchedPlan = true;
                break;
            }
        }

        if (!matchedPlan) {
            // Select "Personalizado" (last toggle, userData == -1)
            Toggle customToggle = planToggleGroup.getToggles().get(planToggleGroup.getToggles().size() - 1);
            planToggleGroup.selectToggle(customToggle);
            customIntervalField.setText(String.valueOf(savedMinutes));
            customIntervalBox.setVisible(true);
            customIntervalBox.setManaged(true);
        }
    }

    private void updateTokenStatus(String token) {
        tokenStatusLabel.getStyleClass().removeAll("status-success", "status-warning", "status-danger", "status-neutral");
        if (token == null || token.isBlank()) {
            tokenStatusLabel.setText("Sem token: as ações são valoradas pelo preço de compra, não pela cotação de mercado.");
            tokenStatusLabel.getStyleClass().add("status-warning");
        } else {
            tokenStatusLabel.setText("Token informado.");
            tokenStatusLabel.getStyleClass().add("status-success");
        }
    }

    private void loadBcbCachedValues() {
        String cdi = settings.get("rate_cdi").orElse(null);
        String selic = settings.get("rate_selic").orElse(null);
        String ipca = settings.get("rate_ipca").orElse(null);
        String lastUpdate = settings.get("rate_last_update").orElse(null);

        setRate(STAT_CDI, cdi);
        setRate(STAT_SELIC, selic);
        setRate(STAT_IPCA, ipca);
        bcbLastUpdateLabel.setText(lastUpdate != null ? "Atualizado em " + lastUpdate : "Nunca atualizado");
    }

    private void setRate(String label, String fraction) {
        if (fraction == null) ratesStrip.set(label, "—", "muted");
        else ratesStrip.set(label, String.format("%.2f%%", Double.parseDouble(fraction) * 100).replace('.', ','));
    }

    private Panel buildAppearanceSection() {
        reduceMotionCheck.setSelected(uiPrefs.reduceMotion());
        reduceMotionCheck.setOnAction(e -> {
            uiPrefs.setReduceMotion(reduceMotionCheck.isSelected());
            ToastHost.showInfo(reduceMotionCheck.isSelected()
                    ? "Animações reduzidas: transições passam a ser imediatas."
                    : "Animações ativadas.");
        });
        Label hint = new Label("Troca de página, painéis e gráficos passam a mudar sem transição. "
                + "Valores e avisos continuam iguais. A preferência fica salva neste computador.");
        hint.getStyleClass().add("text-secondary");
        hint.setWrapText(true);

        showWelcomeBtn.getStyleClass().add("ghost-btn");
        showWelcomeBtn.setTooltip(new Tooltip("Reabre a tela de boas-vindas que aparece na primeira abertura"));
        showWelcomeBtn.setOnAction(e -> {
            WelcomeOverlay.resetShown();
            WelcomeOverlay.requestShow();
        });

        return new Panel("Aparência e comportamento", reduceMotionCheck, hint, showWelcomeBtn);
    }

    private Panel buildBrapiSection() {
        Label tokenLabel = new Label("Token BRAPI");
        tokenLabel.getStyleClass().add("form-label");
        tokenLabel.setLabelFor(tokenField);

        tokenField.setPromptText("Cole aqui o token da sua conta brapi.dev");
        tokenField.setAccessibleText("Token BRAPI");
        tokenField.getStyleClass().add("code-field");

        Label tokenHintPrefix = new Label("Conta gratuita em ");
        tokenHintPrefix.getStyleClass().add("text-helper");

        Hyperlink brapiLink = new Hyperlink("brapi.dev");
        brapiLink.getStyleClass().addAll("text-helper", "text-link");
        brapiLink.setTooltip(new Tooltip("Abre brapi.dev no navegador"));
        brapiLink.setOnAction(e -> openBrapiWebsite());

        HBox tokenHint = new HBox(0, tokenHintPrefix, brapiLink);
        tokenHint.setAlignment(Pos.CENTER_LEFT);

        tokenStatusLabel.setWrapText(true);
        tokenStatusLabel.setMaxWidth(Double.MAX_VALUE);
        updateTokenStatus(tokenField.getText());

        tokenField.textProperty().addListener((obs, old, newVal) -> updateTokenStatus(newVal));

        testTokenBtn.getStyleClass().add("secondary-btn");
        testTokenBtn.setTooltip(new Tooltip("Faz uma consulta de teste à BRAPI com o token informado"));
        testTokenBtn.setOnAction(e -> testToken());

        saveBtn.getStyleClass().add("button");
        saveBtn.setTooltip(new Tooltip("Grava token, atualização automática e intervalo do plano"));
        saveBtn.setOnAction(e -> saveSettings());

        FlowPane btnRow = new FlowPane(10, 8, testTokenBtn, saveBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Plan selector ─────────────────────────────────────────────────
        Label planLabel = new Label("Intervalo de atualização do seu plano");
        planLabel.getStyleClass().add("form-label");

        Label planHint = new Label("Usado só para avisar quando as cotações podem estar desatualizadas; não altera valores.");
        planHint.getStyleClass().add("text-helper");
        planHint.setWrapText(true);

        Map<String, Integer> plans = new LinkedHashMap<>();
        plans.put("Gratuito (~30 min)",  30);
        plans.put("Startup (~15 min)",   15);
        plans.put("Pro (~5 min)",         5);
        plans.put("Personalizado",       -1);

        planRow.setAlignment(Pos.CENTER_LEFT);

        for (Map.Entry<String, Integer> entry : plans.entrySet()) {
            ToggleButton btn = new ToggleButton(entry.getKey());
            btn.setToggleGroup(planToggleGroup);
            btn.getStyleClass().add("filter-toggle");
            btn.setUserData(entry.getValue());
            btn.setOnAction(e -> {
                boolean custom = (int) btn.getUserData() == -1;
                customIntervalBox.setVisible(custom);
                customIntervalBox.setManaged(custom);
            });
            planRow.getChildren().add(btn);
        }
        planToggleGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) planToggleGroup.selectToggle(old);
        });

        customIntervalField.setPromptText("Minutos (1–1440)");
        customIntervalField.setAccessibleText("Intervalo personalizado em minutos");
        customIntervalField.setPrefWidth(160);
        customIntervalField.getStyleClass().add("code-field");
        Label customLbl = new Label("min");
        customLbl.getStyleClass().add("text-helper");
        customIntervalBox.setAlignment(Pos.CENTER_LEFT);
        customIntervalBox.getChildren().addAll(customIntervalField, customLbl);
        customIntervalBox.setVisible(false);
        customIntervalBox.setManaged(false);

        return new Panel("Cotações de ações (BRAPI)",
                new VBox(6, tokenLabel, tokenField, tokenHint),
                tokenStatusLabel,
                autoUpdateCheckbox,
                new VBox(8, planLabel, planHint, planRow, customIntervalBox),
                btnRow)
                .subtitle("Cotações da B3 com atraso de 15–30 min no plano gratuito; sem token, as ações ficam pelo preço de compra.");
    }

    private Panel buildBcbSection() {
        bcbLastUpdateLabel.getStyleClass().add("text-helper");

        updateRatesBtn.getStyleClass().add("button");
        updateRatesBtn.setTooltip(new Tooltip("Consulta as taxas oficiais no Banco Central e guarda neste computador"));
        updateRatesBtn.setOnAction(e -> fetchBcbRates(updateRatesBtn));

        HBox actions = new HBox(12, updateRatesBtn, bcbLastUpdateLabel);
        actions.setAlignment(Pos.CENTER_LEFT);

        return new Panel("Taxas de referência (Banco Central)", ratesStrip, actions)
                .subtitle("CDI, SELIC e IPCA anuais usados como referência na simulação e nos benchmarks.");
    }

    private Panel buildAboutSection() {
        Label version = new Label("BreakInv 0.5.0");
        version.getStyleClass().add("text-lg");

        Label local = new Label("Seus dados ficam só neste computador. A única saída de rede são as consultas às cotações (BRAPI) e às taxas (Banco Central).");
        local.getStyleClass().add("text-secondary");
        local.setWrapText(true);

        return new Panel("Dados e sobre o aplicativo", version, local);
    }

    private void testToken() {
        String token = tokenField.getText().trim();
        if (token.isBlank()) {
            tokenStatusLabel.setText("Informe um token antes de testar.");
            setTokenStatusClass("status-warning");
            return;
        }

        tokenStatusLabel.setText("Testando conexão…");
        setTokenStatusClass("status-neutral");
        testTokenBtn.setDisable(true);

        CompletableFuture.supplyAsync(() -> BrapiClient.testConnectionWithToken(token))
                .thenAcceptAsync(ok -> Platform.runLater(() -> {
                    testTokenBtn.setDisable(false);
                    if (ok) {
                        tokenStatusLabel.setText("Token válido: a BRAPI respondeu.");
                        setTokenStatusClass("status-success");
                    } else {
                        tokenStatusLabel.setText("Token inválido ou sem conexão com a BRAPI.");
                        setTokenStatusClass("status-danger");
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
        } else {
            settings.set(BrapiClient.SETTINGS_KEY_TOKEN, token);
        }

        settings.set("brapi_auto_update", String.valueOf(autoUpdateCheckbox.isSelected()));

        // Persist plan delay
        Toggle selectedToggle = planToggleGroup.getSelectedToggle();
        if (selectedToggle != null) {
            int planMinutes = (int) ((ToggleButton) selectedToggle).getUserData();
            if (planMinutes == -1) {
                // Personalizado: validate custom input
                String raw = customIntervalField.getText().trim();
                try {
                    int custom = Integer.parseInt(raw);
                    if (custom < 1 || custom > 1440) {
                        ToastHost.showError("O intervalo personalizado deve ficar entre 1 e 1440 minutos.");
                        return;
                    }
                    settings.set(SETTINGS_KEY_BRAPI_DELAY, String.valueOf(custom));
                } catch (NumberFormatException ex) {
                    ToastHost.showError("Digite um número válido de minutos.");
                    return;
                }
            } else {
                settings.set(SETTINGS_KEY_BRAPI_DELAY, String.valueOf(planMinutes));
            }
        }

        updateTokenStatus(token);
        if (token.isBlank()) {
            ToastHost.showWarn("Token removido: as ações passam a ser valoradas pelo preço de compra.");
        } else {
            ToastHost.showSuccess("Configurações salvas.");
        }
    }

    private void fetchBcbRates(Button btn) {
        btn.setDisable(true);
        bcbLastUpdateLabel.setText("Consultando o Banco Central…");

        CompletableFuture.supplyAsync(() -> {
            double cdi = BcbClient.fetchCdi().orElse(-1.0);
            double selic = BcbClient.fetchSelic().orElse(-1.0);
            double ipca = BcbClient.fetchIpca().orElse(-1.0);
            return new double[]{cdi, selic, ipca};
        }).thenAcceptAsync(rates -> Platform.runLater(() -> {
            btn.setDisable(false);

            boolean anySuccess = false;

            if (rates[0] > 0) {
                settings.set("rate_cdi", String.valueOf(rates[0]));
                setRate(STAT_CDI, String.valueOf(rates[0]));
                anySuccess = true;
            }
            if (rates[1] > 0) {
                settings.set("rate_selic", String.valueOf(rates[1]));
                setRate(STAT_SELIC, String.valueOf(rates[1]));
                anySuccess = true;
            }
            if (rates[2] > 0) {
                settings.set("rate_ipca", String.valueOf(rates[2]));
                setRate(STAT_IPCA, String.valueOf(rates[2]));
                anySuccess = true;
            }

            if (anySuccess) {
                String now = LocalDateTime.now().format(DT_FMT);
                settings.set("rate_last_update", now);
                bcbLastUpdateLabel.setText("Atualizado em " + now);
                ToastHost.showSuccess("Taxas do Banco Central atualizadas.");
            } else {
                bcbLastUpdateLabel.setText("Não foi possível consultar as taxas. Verifique sua conexão.");
                ToastHost.showError("Falha ao consultar as taxas do Banco Central.");
            }
        }));
    }
}

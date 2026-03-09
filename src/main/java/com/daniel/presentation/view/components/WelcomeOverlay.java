package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Icons;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.prefs.Preferences;

/**
 * Premium welcome overlay shown on first run or on demand.
 * Persistence via java.util.prefs.Preferences.
 * Supports force-show via registerShowCallback / requestShow.
 */
public final class WelcomeOverlay {

    private static final String PREF_NODE = "com/daniel/investmentTracker";
    private static final String PREF_KEY  = "welcomeShown";

    /** Registered by AppShell so Settings can trigger a force-show. */
    private static Runnable showCallback = null;

    private final StackPane overlay = new StackPane();
    private final Runnable onCreateInvestment;
    private final Runnable onConfigure;

    public WelcomeOverlay(Runnable onCreateInvestment, Runnable onConfigure) {
        this.onCreateInvestment = onCreateInvestment;
        this.onConfigure = onConfigure;
        buildOverlay();
    }

    // ── Static API ────────────────────────────────────────────────────────

    /** True when the welcome was never dismissed by the user. */
    public static boolean shouldShow() {
        return !Preferences.userRoot().node(PREF_NODE).getBoolean(PREF_KEY, false);
    }

    /** Marks welcome as "shown" — suppresses automatic show on next launch. */
    public static void markShown() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);
            prefs.putBoolean(PREF_KEY, true);
            prefs.flush();
        } catch (Exception ignored) {}
    }

    /** Clears the shown flag — overlay will show again on next eligible launch. */
    public static void resetShown() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);
            prefs.remove(PREF_KEY);
            prefs.flush();
        } catch (Exception ignored) {}
    }

    /** Called by AppShell so Settings page can trigger a force-show. */
    public static void registerShowCallback(Runnable callback) {
        showCallback = callback;
    }

    /** Shows the overlay on demand (called from Settings). Bypasses portfolio check. */
    public static void requestShow() {
        if (showCallback != null) showCallback.run();
    }

    // ── Instance API ──────────────────────────────────────────────────────

    public StackPane getNode() {
        return overlay;
    }

    public void animateIn() {
        overlay.setOpacity(0);

        FadeTransition fadeDim = new FadeTransition(Duration.millis(200), overlay);
        fadeDim.setFromValue(0);
        fadeDim.setToValue(1);
        fadeDim.setInterpolator(Interpolator.EASE_OUT);

        VBox card = (VBox) overlay.getChildren().get(0);
        card.setScaleX(0.90);
        card.setScaleY(0.90);
        ScaleTransition scaleCard = new ScaleTransition(Duration.millis(240), card);
        scaleCard.setToX(1.0);
        scaleCard.setToY(1.0);
        scaleCard.setInterpolator(Interpolator.EASE_OUT);

        fadeDim.play();
        scaleCard.play();
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void buildOverlay() {
        overlay.getStyleClass().add("welcome-overlay");

        VBox card = new VBox(14);
        card.getStyleClass().add("welcome-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setMaxWidth(500);

        // ── Header row: logo + spacer + X close ──────────────────────────
        FontIcon logoIcon = Icons.of(Feather.TRENDING_UP, 36);
        logoIcon.getStyleClass().add("icon-accent");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, Icons.close());
        closeBtn.getStyleClass().add("icon-btn");
        Tooltip.install(closeBtn, new Tooltip("Fechar"));

        HBox headerRow = new HBox(8, logoIcon, headerSpacer, closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // ── Title + subtitle ─────────────────────────────────────────────
        Label title = new Label("Bem-vindo ao BreakInv");
        title.getStyleClass().add("welcome-title");
        title.setWrapText(true);

        Label subtitle = new Label(
                "Acompanhe seus investimentos, visualize rentabilidade e simule cenários — tudo em um só lugar.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(430);

        // ── Feature bullets ───────────────────────────────────────────────
        VBox features = new VBox(8);
        features.getStyleClass().add("welcome-features");
        features.getChildren().addAll(
                featureRow(Feather.BAR_CHART_2, "Dashboard com KPIs, gráficos e benchmarks"),
                featureRow(Feather.BRIEFCASE,   "Carteira com diversificação e filtros"),
                featureRow(Feather.TRENDING_UP, "Simulador com CDI, SELIC, IPCA e ações")
        );

        // ── CTAs ──────────────────────────────────────────────────────────
        Button createBtn = new Button("Criar primeiro investimento", Icons.plus());
        createBtn.getStyleClass().add("welcome-cta-primary");
        createBtn.setMaxWidth(Double.MAX_VALUE);

        Button configBtn = new Button("Configurar token da Brapi", Icons.settings());
        configBtn.getStyleClass().add("welcome-cta-secondary");
        configBtn.setMaxWidth(Double.MAX_VALUE);

        Button dashBtn = new Button("Ir para o Dashboard", Icons.home());
        dashBtn.getStyleClass().add("welcome-cta-secondary");
        dashBtn.setMaxWidth(Double.MAX_VALUE);

        // ── Don't show again ──────────────────────────────────────────────
        CheckBox noShow = new CheckBox("Não mostrar novamente");
        noShow.getStyleClass().add("welcome-skip");

        // ── Wire actions — persist only if checkbox is checked ────────────
        closeBtn.setOnAction(e -> dismiss(noShow.isSelected(), null));
        createBtn.setOnAction(e -> dismiss(noShow.isSelected(), onCreateInvestment));
        configBtn.setOnAction(e -> dismiss(noShow.isSelected(), onConfigure));
        dashBtn.setOnAction(e -> dismiss(noShow.isSelected(), null));

        card.getChildren().addAll(
                headerRow, title, subtitle,
                new Separator(), features, new Separator(),
                createBtn, configBtn, dashBtn, noShow
        );

        overlay.getChildren().add(card);
        StackPane.setAlignment(card, Pos.CENTER);

        // Click on the dim background closes (respects checkbox)
        overlay.setOnMouseClicked(e -> {
            if (e.getTarget() == overlay) dismiss(noShow.isSelected(), null);
        });
    }

    private HBox featureRow(Feather icon, String text) {
        FontIcon fi = Icons.of(icon, 14);
        fi.getStyleClass().add("icon-accent");
        Label lbl = new Label(text);
        lbl.getStyleClass().add("welcome-feature-text");
        HBox row = new HBox(10, fi, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void dismiss(boolean persist, Runnable callback) {
        if (persist) markShown();

        FadeTransition fade = new FadeTransition(Duration.millis(160), overlay);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_IN);
        fade.setOnFinished(ev -> {
            if (overlay.getParent() instanceof StackPane parent) {
                parent.getChildren().remove(overlay);
            }
            if (callback != null) callback.run();
        });
        fade.play();
    }
}

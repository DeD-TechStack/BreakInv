package com.daniel.presentation.view.components;

import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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
    private final VBox card = new VBox(14);
    private final Runnable onCreateInvestment;
    private final Runnable onConfigure;
    private Button primaryCta;
    private boolean dismissed;

    // ── Focus containment ──
    /** Where the focus was before the welcome took it (null on first run: nothing was focused yet). */
    private Node previousFocus;
    /** Asked when there is no previous focus to return to (the shell names its active nav button). */
    private Supplier<Node> fallbackFocus = () -> null;
    private Scene guardedScene;
    /** While shown, a focus that lands outside the card (programmatic or native Tab) is pulled back in. */
    private final ChangeListener<Node> focusGuard = (obs, was, now) -> {
        if (dismissed || now == null || overlay.getScene() == null) return;
        if (!isInside(now)) focusPrimary();
    };

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

    /** Node that receives the focus when the welcome is dismissed and nothing was focused before it. */
    public void focusAfterDismiss(Supplier<Node> fallback) {
        this.fallbackFocus = fallback == null ? () -> null : fallback;
    }

    /**
     * Entrance through the central Motion: cancellable, and immediate under "Reduzir animações".
     * The focus goes to the primary action — now if the overlay is already in a Scene (reopened
     * from Configurações), or as soon as the Scene exists (first run: shown inside {@code build()}).
     */
    public void animateIn() {
        Motion.fadeSlideIn(overlay, Motion.PANEL_MS, 12);
        if (overlay.getScene() != null) {
            attachTo(overlay.getScene());
            focusPrimary();
        }
        // else: the sceneProperty listener (buildOverlay) takes over when the shell enters its Scene
    }

    private void attachTo(Scene scene) {
        if (scene == null || scene == guardedScene) return;
        detachGuard();
        guardedScene = scene;
        Node owner = scene.getFocusOwner();
        if (previousFocus == null && owner != null && !isInside(owner)) previousFocus = owner;
        scene.focusOwnerProperty().addListener(focusGuard);
    }

    private void detachGuard() {
        if (guardedScene != null) {
            guardedScene.focusOwnerProperty().removeListener(focusGuard);
            guardedScene = null;
        }
    }

    private boolean isInside(Node n) {
        for (Node c = n; c != null; c = c.getParent()) if (c == overlay) return true;
        return false;
    }

    private void focusPrimary() {
        if (primaryCta != null) primaryCta.requestFocus();
    }

    /** Focusable controls of the card, in reading order: the Tab cycle stays inside this list. */
    private List<Control> focusables() {
        List<Control> out = new ArrayList<>();
        collect(card, out);
        return out;
    }

    private static void collect(Parent p, List<Control> out) {
        for (Node c : p.getChildrenUnmodifiable()) {
            if (c instanceof Control ctl && ctl.isFocusTraversable() && !ctl.isDisabled() && ctl.isVisible()) out.add(ctl);
            if (c instanceof Parent pp) collect(pp, out);
        }
    }

    private void cycleFocus(boolean backwards) {
        List<Control> order = focusables();
        if (order.isEmpty()) return;
        Node owner = overlay.getScene() == null ? null : overlay.getScene().getFocusOwner();
        int idx = -1;
        for (int i = 0; i < order.size(); i++) if (order.get(i) == owner) { idx = i; break; }
        int next = idx < 0 ? 0 : backwards ? (idx - 1 + order.size()) % order.size() : (idx + 1) % order.size();
        order.get(next).requestFocus();
    }

    /** After the overlay left the scene graph: back to where the user was, or to the shell's fallback. */
    private void restoreFocus(Scene scene) {
        Node target = previousFocus != null && previousFocus.getScene() == scene ? previousFocus : fallbackFocus.get();
        if (target != null && target.getScene() == scene && target.isVisible() && !target.isDisabled()) target.requestFocus();
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void buildOverlay() {
        overlay.getStyleClass().add("welcome-overlay");

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
        closeBtn.setTooltip(new Tooltip("Fechar (Esc)"));
        closeBtn.setAccessibleText("Fechar boas-vindas");

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
                featureRow(Feather.BAR_CHART_2, "Patrimônio, caixa e ganho em um resumo, com gráfico e benchmarks"),
                featureRow(Feather.BRIEFCASE,   "Carteira com histórico de movimentações, diversificação e extrato"),
                featureRow(Feather.TRENDING_UP, "Simulação de cenários com CDI, SELIC, IPCA e ações")
        );

        // ── CTAs ──────────────────────────────────────────────────────────
        Button createBtn = new Button("Criar primeiro investimento", Icons.plus());
        createBtn.getStyleClass().add("welcome-cta-primary");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        primaryCta = createBtn;

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
        // Esc closes like any other overlay/dialog; Tab/Shift+Tab cycle inside the card only. The
        // focus lives on the card's controls (never on the dim background), so a key pressed with
        // the focus anywhere in the welcome reaches this filter on its way down.
        overlay.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) { dismiss(noShow.isSelected(), null); e.consume(); }
            else if (e.getCode() == KeyCode.TAB && !e.isControlDown() && !e.isAltDown()) { cycleFocus(e.isShiftDown()); e.consume(); }
        });
        // First run: shown before the shell has a Scene → take the focus once it exists.
        overlay.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                if (!dismissed) {
                    attachTo(newScene);
                    Platform.runLater(() -> { if (!dismissed && overlay.getScene() == newScene) focusPrimary(); });
                }
            } else {
                detachGuard();
            }
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
        if (dismissed) return; // a second click/Esc during the exit must not run the action twice
        dismissed = true;
        if (persist) markShown();
        detachGuard();
        // Exit through Motion: cancelling (settle) or finishing both remove the overlay and run the
        // callback exactly once; under "Reduzir animações" this happens at once.
        Motion.fadeOut(overlay, Motion.FEEDBACK_MS, () -> {
            Scene scene = overlay.getScene();
            if (overlay.getParent() instanceof StackPane parent) {
                parent.getChildren().remove(overlay);
            }
            restoreFocus(scene);
            if (callback != null) callback.run();
        });
    }
}

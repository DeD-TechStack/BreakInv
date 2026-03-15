package com.daniel.presentation.view;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.components.WelcomeOverlay;
import com.daniel.presentation.view.pages.*;
import com.daniel.presentation.view.util.Icons;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.Scene;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class AppShell {

    private final DailyTrackingUseCase daily;
    private final StackPane content = new StackPane();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Button> nav = new LinkedHashMap<>();
    private Consumer<String> pageChangeListener;

    public AppShell(DailyTrackingUseCase dailyTrackingUseCase) {
        this.daily = dailyTrackingUseCase;
        pages.put("Dashboard",                 new DashboardPage(dailyTrackingUseCase));
        pages.put("Cadastrar Investimento",    new InvestmentTypesPage(dailyTrackingUseCase));
        pages.put("Diversificação",            new DiversificationPage(dailyTrackingUseCase));
        pages.put("Análise de Ativo",          new AssetAnalysisPage());
        pages.put("Ranking",                   new RankingPage(dailyTrackingUseCase));
        pages.put("Simulação",                 new SimulationPage());
        pages.put("Extrato de Investimentos",  new ReportsPage(dailyTrackingUseCase));
        pages.put("Configurações",             new ConfiguracoesPage());
    }

    public Parent build() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        content.getStyleClass().add("content-host");
        content.setAlignment(Pos.TOP_LEFT);
        root.setLeft(sidebar());
        root.setCenter(content);

        StackPane shell = new StackPane(root);
        shell.getStyleClass().add("app-shell");
        ToastHost.install(shell);

        // Register callback so Settings can force-show the overlay at any time
        WelcomeOverlay.registerShowCallback(() -> showOverlay(shell));

        // Show welcome on first run, but only if portfolio is empty
        if (isPortfolioEmpty() && WelcomeOverlay.shouldShow()) {
            showOverlay(shell);
        } else if (!isPortfolioEmpty() && WelcomeOverlay.shouldShow()) {
            ToastHost.showInfo("Abra Configurações para ver a tela de boas-vindas novamente.");
        }

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                applyBreakpoints(root, newScene.getWidth());
                newScene.widthProperty().addListener((o, old, w) ->
                        applyBreakpoints(root, w.doubleValue()));
            }
        });

        go("Dashboard");
        return shell;
    }

    private boolean isPortfolioEmpty() {
        return daily.listTypes().isEmpty();
    }

    private void showOverlay(StackPane shell) {
        // Guard: prevent stacking duplicate overlays
        boolean alreadyShowing = shell.getChildren().stream()
                .anyMatch(n -> n.getStyleClass().contains("welcome-overlay"));
        if (alreadyShowing) return;

        WelcomeOverlay welcome = new WelcomeOverlay(
                () -> go("Cadastrar Investimento"),
                () -> go("Configurações")
        );
        shell.getChildren().add(welcome.getNode());
        welcome.getNode().toFront();
        welcome.animateIn();
    }

    private static void applyBreakpoints(BorderPane root, double width) {
        root.getStyleClass().removeAll("compact", "bp-md", "bp-sm", "bp-xs", "icon-compact");
        if (width < 850) {
            root.getStyleClass().addAll("compact", "bp-xs", "icon-compact");
        } else if (width < 1100) {
            root.getStyleClass().addAll("compact", "bp-sm");
        } else if (width < 1200) {
            root.getStyleClass().add("bp-md");
        }
    }

    private Parent sidebar() {
        VBox box = new VBox();
        box.getStyleClass().add("sidebar");

        // ── Brand block ──────────────────────────────
        VBox brand = new VBox(4);
        brand.getStyleClass().add("sidebar-brand");

        Label title = new Label("BreakInv");
        title.getStyleClass().add("sidebar-title");

        Label sub = new Label("Controle de investimentos");
        sub.getStyleClass().add("sidebar-sub");

        brand.getChildren().addAll(title, sub);

        // ── Nav section ──────────────────────────────
        VBox navBox = new VBox(2);
        navBox.getStyleClass().add("sidebar-nav");

        navBox.getChildren().add(navSectionLabel("PRINCIPAL"));
        addNavItem(navBox, "Dashboard",              "Dashboard",    Icons.home());
        addNavItem(navBox, "Cadastrar Investimento", "Carteira",     Icons.briefcase());

        navBox.getChildren().add(navSectionLabel("ANÁLISE"));
        addNavItem(navBox, "Diversificação",           "Diversificação", Icons.pieChart());
        addNavItem(navBox, "Análise de Ativo",         "Análise",        Icons.activity());
        addNavItem(navBox, "Ranking",                  "Ranking",        Icons.award());
        addNavItem(navBox, "Simulação",                "Simulação",      Icons.trendingUp());
        addNavItem(navBox, "Extrato de Investimentos", "Extrato",        Icons.fileText());

        // ── Spacer ───────────────────────────────────
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // ── Footer ───────────────────────────────────
        VBox footer = new VBox(4);
        footer.getStyleClass().add("sidebar-footer");

        footer.getChildren().add(navSectionLabel("SISTEMA"));

        Button configBtn = new Button("Configurações", Icons.settings());
        configBtn.getStyleClass().add("nav-btn");
        configBtn.setMaxWidth(Double.MAX_VALUE);
        configBtn.setOnAction(e -> go("Configurações"));
        Tooltip.install(configBtn, new Tooltip("Configurações"));
        nav.put("Configurações", configBtn);

        Label version = new Label("v0.5.0");
        version.getStyleClass().add("sidebar-footer-version");

        footer.getChildren().addAll(configBtn, version);

        box.getChildren().addAll(brand, navBox, spacer, footer);
        return box;
    }

    private Label navSectionLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("sidebar-nav-label");
        lbl.setMaxWidth(Double.MAX_VALUE);
        return lbl;
    }

    private void addNavItem(VBox box, String key, String label, FontIcon icon) {
        Button b = new Button(label, icon);
        b.getStyleClass().add("nav-btn");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> go(key));
        Tooltip.install(b, new Tooltip(label));
        nav.put(key, b);
        box.getChildren().add(b);
    }

    public void setPageChangeListener(Consumer<String> listener) {
        this.pageChangeListener = listener;
    }

    public void go(String key) {
        Page p = pages.get(key);
        if (p == null) return;

        nav.values().forEach(b -> b.getStyleClass().remove("active"));
        if (nav.get(key) != null) nav.get(key).getStyleClass().add("active");
        if (pageChangeListener != null) pageChangeListener.accept(key);

        Parent view = p.view();
        swapWithAnimation(view);

        p.onShow();
    }

    private void swapWithAnimation(Node newNode) {
        if (content.getChildren().isEmpty()) {
            content.getChildren().setAll(newNode);
            return;
        }

        Node old = content.getChildren().get(0);

        FadeTransition fadeOut = new FadeTransition(javafx.util.Duration.millis(100), old);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setInterpolator(Interpolator.EASE_BOTH);

        fadeOut.setOnFinished(ev -> {
            content.getChildren().setAll(newNode);

            newNode.setOpacity(0.0);
            newNode.setTranslateY(8);

            FadeTransition fadeIn = new FadeTransition(javafx.util.Duration.millis(160), newNode);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            fadeIn.setInterpolator(Interpolator.EASE_OUT);

            TranslateTransition slide = new TranslateTransition(javafx.util.Duration.millis(160), newNode);
            slide.setFromY(8);
            slide.setToY(0);
            slide.setInterpolator(Interpolator.EASE_OUT);

            fadeIn.play();
            slide.play();
        });

        fadeOut.play();
    }
}

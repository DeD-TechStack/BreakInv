package com.daniel.presentation.view;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.backup.DataProtection;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.components.WelcomeOverlay;
import com.daniel.presentation.view.pages.*;
import com.daniel.presentation.view.util.Breakpoint;
import com.daniel.presentation.view.util.Icons;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Application frame: sidebar (220 px, collapsible to a 56 px rail) + content host.
 *
 * <ul>
 *   <li>Breakpoints are computed on the usable content width ({@link Breakpoint}) and exposed as
 *       style classes on the frame; pages reflow themselves through {@link ResponsivePage}.</li>
 *   <li>The sidebar collapses automatically below 900 px of scene width; the toggle button
 *       (also reachable by keyboard) overrides that and the choice persists.</li>
 *   <li>Navigation goes through {@link Motion#swap}: the last {@code go()} always wins, with
 *       exactly one page node in the host and no opacity left at zero.</li>
 * </ul>
 */
public final class AppShell {

    public static final double SIDEBAR_WIDTH = 220;
    public static final double RAIL_WIDTH = 56;

    public static final List<String> PAGE_KEYS = List.of(
            "Dashboard", "Cadastrar Investimento", "Diversificação", "Análise de Ativo",
            "Ranking", "Simulação", "Extrato de Investimentos", "Configurações");

    private final DailyTrackingUseCase daily;
    private final UiPreferences prefs;
    private final StackPane content = new StackPane();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Button> nav = new LinkedHashMap<>();
    private final List<Label> sidebarTexts = new ArrayList<>();
    private final List<Node> sidebarGraphics = new ArrayList<>();
    private final VBox sidebar = new VBox();
    private final BorderPane frame = new BorderPane();
    private final Button toggleBtn = new Button();
    private Consumer<String> pageChangeListener;
    private String currentKey;
    private boolean dataBusy;
    private Breakpoint breakpoint;
    private boolean collapsed;
    private double lastSceneWidth = Double.NaN;

    public AppShell(DailyTrackingUseCase dailyTrackingUseCase) {
        this(dailyTrackingUseCase, UiPreferences.forUser());
    }

    /** Production composition: the SAME preferences instance the app gave Motion reaches every page. */
    public AppShell(DailyTrackingUseCase dailyTrackingUseCase, UiPreferences prefs) {
        this(dailyTrackingUseCase, prefs, null);
    }

    /** Production composition with copies and restoration of the local data (BRK-30). */
    public AppShell(DailyTrackingUseCase dailyTrackingUseCase, UiPreferences prefs, DataProtection data) {
        this(dailyTrackingUseCase, defaultPages(dailyTrackingUseCase, prefs, data), prefs);
    }

    /** Test seam: pages and preferences are injected so no database or registry is touched. */
    AppShell(DailyTrackingUseCase dailyTrackingUseCase, Map<String, Page> pages, UiPreferences prefs) {
        this.daily = dailyTrackingUseCase;
        this.prefs = prefs;
        this.pages.putAll(pages);
        // C04: the Dashboard's token warning leads straight to the setting it names
        if (this.pages.get("Dashboard") instanceof DashboardPage dashboard)
            dashboard.setOnConfigureToken(() -> go("Configurações"));
        // BRK-30: after a restore the visible page reads the replaced data at once; while it runs,
        // nothing else in the window can be used (and App refuses to close it)
        for (Page page : this.pages.values()) {
            if (page instanceof DataOperations ops) {
                ops.setOnDataReplaced(this::reloadCurrent);
                ops.setOnDataBusy(this::setDataBusy);
            }
        }
    }

    private static Map<String, Page> defaultPages(DailyTrackingUseCase daily, UiPreferences prefs,
                                                  DataProtection data) {
        Map<String, Page> p = new LinkedHashMap<>();
        p.put("Dashboard",                 new DashboardPage(daily));
        p.put("Cadastrar Investimento",    new InvestmentTypesPage(daily, prefs));
        p.put("Diversificação",            new DiversificationPage(daily));
        p.put("Análise de Ativo",          new AssetAnalysisPage());
        p.put("Ranking",                   new RankingPage(daily));
        p.put("Simulação",                 new SimulationPage());
        p.put("Extrato de Investimentos",  new ReportsPage(daily));
        p.put("Configurações",             new ConfiguracoesPage(prefs, data));
        return p;
    }

    public Parent build() {
        frame.getStyleClass().add("app-root");
        content.getStyleClass().add("content-host");
        content.setAlignment(Pos.TOP_LEFT);
        frame.setLeft(buildSidebar());
        frame.setCenter(content);

        StackPane shell = new StackPane(frame);
        shell.getStyleClass().add("app-shell");
        ToastHost.install(shell);

        WelcomeOverlay.registerShowCallback(() -> showOverlay(shell));
        if (isPortfolioEmpty() && WelcomeOverlay.shouldShow()) {
            showOverlay(shell);
        } else if (!isPortfolioEmpty() && WelcomeOverlay.shouldShow()) {
            ToastHost.showInfo("Abra Configurações para ver a tela de boas-vindas novamente.");
        }

        // Scene width drives the automatic collapse; content width drives the breakpoint.
        shell.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                onSceneWidth(newScene.getWidth());
                applySidebarHeight(newScene.getHeight());
                newScene.heightProperty().addListener((o, a, h) -> applySidebarHeight(h.doubleValue()));
                newScene.widthProperty().addListener((o, a, w) -> onSceneWidth(w.doubleValue()));
            }
        });
        content.widthProperty().addListener((o, a, w) -> applyBreakpoint(w.doubleValue()));
        shell.layoutBoundsProperty().addListener((o, a, b) -> {
            if (shell.getScene() != null) onSceneWidth(shell.getScene().getWidth());
        });

        go("Dashboard");
        return shell;
    }

    private boolean isPortfolioEmpty() {
        try {
            return daily.listTypes().isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void showOverlay(StackPane shell) {
        boolean alreadyShowing = shell.getChildren().stream()
                .anyMatch(n -> n.getStyleClass().contains("welcome-overlay"));
        if (alreadyShowing) return;
        WelcomeOverlay welcome = new WelcomeOverlay(
                () -> go("Cadastrar Investimento"),
                () -> go("Configurações"));
        // First run: nothing was focused before the welcome, so the focus lands on the current page's nav item.
        welcome.focusAfterDismiss(() -> currentKey == null ? toggleBtn : nav.getOrDefault(currentKey, toggleBtn));
        shell.getChildren().add(welcome.getNode());
        welcome.getNode().toFront();
        welcome.animateIn();
    }

    // ── Breakpoints / sidebar ──────────────────────────────────────────────

    private void applySidebarHeight(double height) {
        sidebar.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("short"), height < 560);
    }

    private void onSceneWidth(double sceneWidth) {
        if (!Double.isFinite(sceneWidth) || sceneWidth <= 0) return;
        boolean widthChanged = sceneWidth != lastSceneWidth;
        lastSceneWidth = sceneWidth;
        boolean shouldCollapse = Breakpoint.sidebarCollapsed(sceneWidth, prefs.sidebarCollapsedChoice());
        if (shouldCollapse != collapsed) setCollapsed(shouldCollapse, widthChanged);
        // content width may not have been laid out yet: derive it from the scene
        double navWidth = collapsed ? RAIL_WIDTH : SIDEBAR_WIDTH;
        double measured = content.getWidth();
        applyBreakpoint(measured > 0 ? Math.min(measured, sceneWidth - navWidth) : sceneWidth - navWidth);
    }

    private void applyBreakpoint(double contentWidth) {
        Breakpoint bp = Breakpoint.of(contentWidth);
        if (bp == breakpoint) return;
        breakpoint = bp;
        frame.getStyleClass().removeAll(Breakpoint.allStyleClasses());
        frame.getStyleClass().add(bp.styleClass());
        for (Page p : pages.values()) {
            if (p instanceof ResponsivePage rp) rp.onBreakpoint(bp);
        }
    }

    public Breakpoint currentBreakpoint() {
        return breakpoint == null ? Breakpoint.WIDE : breakpoint;
    }

    private void setCollapsed(boolean value, boolean animate) {
        collapsed = value;
        double target = value ? RAIL_WIDTH : SIDEBAR_WIDTH;
        // A transition still running (the user reversed mid-flight, or the window crossed 900 px
        // twice) is interrupted WHERE IT IS: settling it first would run the previous pin and the
        // new animation would start with min = max = old target, i.e. frozen, then jump.
        Motion.interrupt(sidebar);
        // While the width animates, min/max must span both sizes or BorderPane clamps pref to the
        // target at once and the transition is invisible; they are pinned again at the end state.
        sidebar.setMinWidth(RAIL_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        toggleBtn.setGraphic(Icons.of(value ? Feather.CHEVRONS_RIGHT : Feather.CHEVRONS_LEFT, 14));
        toggleBtn.setTooltip(new Tooltip(value ? "Expandir navegação" : "Recolher navegação"));
        toggleBtn.setAccessibleText(value ? "Expandir navegação" : "Recolher navegação");
        Runnable pin = () -> { sidebar.setMinWidth(target); sidebar.setMaxWidth(target); };
        if (value) {
            // Collapsing: the rail look applies at once, so no text is cut while the width shrinks.
            applyRailLook(true);
            if (animate) Motion.animatePrefWidth(sidebar, target, Motion.NAV_MS, pin);
            else { sidebar.setPrefWidth(target); pin.run(); }
        } else if (animate) {
            // Expanding: texts, logo and labels only come back once there is room for them; the
            // rail look (icons only, centred) stays until the width has arrived.
            Motion.animatePrefWidth(sidebar, target, Motion.NAV_MS, () -> { applyRailLook(false); pin.run(); });
        } else {
            applyRailLook(false);
            sidebar.setPrefWidth(target);
            pin.run();
        }
    }

    /** Rail = icons only, texts/logo hidden, items centred; expanded = everything visible, left aligned. */
    private void applyRailLook(boolean rail) {
        if (rail) { if (!sidebar.getStyleClass().contains("rail")) sidebar.getStyleClass().add("rail"); }
        else sidebar.getStyleClass().remove("rail");
        for (Label l : sidebarTexts) { l.setVisible(!rail); l.setManaged(!rail); }
        for (Node n : sidebarGraphics) { n.setVisible(!rail); n.setManaged(!rail); }
        for (Button b : nav.values()) {
            b.setContentDisplay(rail ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
            b.setAlignment(rail ? Pos.CENTER : Pos.CENTER_LEFT);
        }
    }

    private void onToggleSidebar() {
        boolean next = !collapsed;
        prefs.setSidebarCollapsedChoice(next);
        setCollapsed(next, true);
        if (Double.isFinite(lastSceneWidth)) {
            applyBreakpoint(lastSceneWidth - (next ? RAIL_WIDTH : SIDEBAR_WIDTH));
        }
    }

    // ── Sidebar ────────────────────────────────────────────────────────────

    private Parent buildSidebar() {
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);

        HBox brandRow = new HBox(8);
        brandRow.getStyleClass().add("sidebar-brand");
        brandRow.setAlignment(Pos.CENTER_LEFT);
        var iconStream = getClass().getResourceAsStream("/app/icon.png");
        if (iconStream != null) {
            ImageView logo = new ImageView(new Image(iconStream));
            logo.setFitWidth(24);
            logo.setFitHeight(24);
            logo.setPreserveRatio(true);
            logo.setSmooth(true);
            brandRow.getChildren().add(logo);
            // On the 56 px rail the expand button is the only mouse way out: logo 24 + gaps + a
            // 40 px target do not fit, so the logo yields to the button (the title is hidden too).
            sidebarGraphics.add(logo);
        }
        Label title = new Label("BreakInv");
        title.getStyleClass().add("sidebar-title");
        sidebarTexts.add(title);
        Region brandSpacer = new Region();
        HBox.setHgrow(brandSpacer, Priority.ALWAYS);
        // On the rail the spacer still pushed the button against the divider (r1: x 13–55 of 56);
        // it leaves with the logo and title, so the centred row centres the button on the icons.
        sidebarGraphics.add(brandSpacer);
        toggleBtn.getStyleClass().addAll("icon-btn", "sidebar-toggle");
        toggleBtn.setOnAction(e -> onToggleSidebar());
        brandRow.getChildren().addAll(title, brandSpacer, toggleBtn);

        VBox navBox = new VBox(2);
        navBox.getStyleClass().add("sidebar-nav");
        navBox.getChildren().add(navSectionLabel("Principal"));
        addNavItem(navBox, "Dashboard",              "Dashboard",      Icons.home());
        addNavItem(navBox, "Cadastrar Investimento", "Carteira",       Icons.briefcase());
        navBox.getChildren().add(navSectionLabel("Análise"));
        addNavItem(navBox, "Diversificação",           "Diversificação", Icons.pieChart());
        addNavItem(navBox, "Análise de Ativo",         "Análise",        Icons.activity());
        addNavItem(navBox, "Ranking",                  "Ranking",        Icons.award());
        addNavItem(navBox, "Simulação",                "Simulação",      Icons.trendingUp());
        addNavItem(navBox, "Extrato de Investimentos", "Extrato",        Icons.fileText());

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox footer = new VBox(4);
        footer.getStyleClass().add("sidebar-footer");
        addNavItem(footer, "Configurações", "Configurações", Icons.settings());
        Label version = new Label("v0.5.0");
        version.getStyleClass().add("sidebar-footer-version");
        sidebarTexts.add(version);
        footer.getChildren().add(version);

        sidebar.getChildren().addAll(brandRow, navBox, spacer, footer);

        // apply the persisted user choice before the first layout, without animation
        Boolean choice = prefs.sidebarCollapsedChoice();
        setCollapsed(choice != null && choice, false);
        return sidebar;
    }

    private Label navSectionLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("sidebar-nav-label");
        lbl.setMaxWidth(Double.MAX_VALUE);
        sidebarTexts.add(lbl);
        return lbl;
    }

    private void addNavItem(Pane box, String key, String label, FontIcon icon) {
        Button b = new Button(label, icon);
        b.getStyleClass().add("nav-btn");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setUserData(key);
        b.setId("nav-" + key.replace(' ', '-').toLowerCase());
        b.setOnAction(e -> go(key));
        b.setTooltip(new Tooltip(label));
        b.setAccessibleText(label);
        nav.put(key, b);
        box.getChildren().add(b);
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    public void setPageChangeListener(Consumer<String> listener) {
        this.pageChangeListener = listener;
    }

    public void go(String key) {
        Page p = pages.get(key);
        if (p == null) return;

        nav.values().forEach(b -> b.getStyleClass().remove("active"));
        if (nav.get(key) != null) nav.get(key).getStyleClass().add("active");
        if (pageChangeListener != null) pageChangeListener.accept(key);

        int direction = 0;
        if (currentKey != null && !currentKey.equals(key)) {
            direction = Integer.compare(PAGE_KEYS.indexOf(key), PAGE_KEYS.indexOf(currentKey));
        }
        boolean samePage = key.equals(currentKey);
        currentKey = key;

        Parent view = p.view();
        if (!samePage) Motion.swap(content, view, direction);
        if (p instanceof ResponsivePage rp && breakpoint != null) rp.onBreakpoint(breakpoint);
        p.onShow();
    }

    /** After a restore: the visible page reloads now; the others reload on their next show. */
    private void reloadCurrent() {
        if (currentKey != null) go(currentKey);
    }

    /** True while a restore replaces the data: the window must not close. */
    public boolean isDataBusy() {
        return dataBusy;
    }

    private void setDataBusy(boolean busy) {
        dataBusy = busy;
        frame.setDisable(busy); // no navigation or other action on data that are being replaced
    }
}

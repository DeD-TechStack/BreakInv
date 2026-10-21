package com.daniel.presentation.view.util;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.text.Font;

/** One user-agent theme and bundled typography, shared by pages and dialog roots. */
public final class UiTheme {
    private static boolean installed;
    private static String family = "System";

    private UiTheme() {}

    /** Called on the FX thread before constructing controls; no network or installed-font dependency. */
    public static void install() {
        if (installed) return;
        boolean loaded = true;
        for (String weight : new String[]{"Regular", "Medium", "SemiBold"}) {
            try (var stream = UiTheme.class.getResourceAsStream("/fonts/IBMPlexSans-" + weight + ".ttf")) {
                loaded &= stream != null && Font.loadFont(stream, 14) != null;
            } catch (java.io.IOException | RuntimeException missing) {
                loaded = false;
            }
        }
        family = loaded ? "IBM Plex Sans" : "System";
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        installed = true;
    }

    /** Explicit fallback for a damaged/missing font resource; preserve any caller's inline style. */
    public static void applyFont(Parent root) {
        install();
        String declaration = "-fx-font-family: '" + family + "';";
        if (!root.getStyle().contains(declaration)) root.setStyle(root.getStyle() + ";" + declaration);
    }
}

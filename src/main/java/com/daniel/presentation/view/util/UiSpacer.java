package com.daniel.presentation.view.util;

import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Factory for flexible-grow spacer Regions.
 * Replaces the repeated new-Region + setHgrow/setVgrow(ALWAYS) two-liner.
 */
public final class UiSpacer {

    private UiSpacer() {}

    /** A Region that grows to fill remaining horizontal space in an HBox. */
    public static Region hGrow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** A Region that grows to fill remaining vertical space in a VBox. */
    public static Region vGrow() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }
}

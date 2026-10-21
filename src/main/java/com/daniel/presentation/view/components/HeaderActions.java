package com.daniel.presentation.view.components;

import com.daniel.presentation.view.PageHeader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.Objects;


/**
 * Page header row: title/subtitle on the left, actions on the right. The actions wrap under the
 * title when the row is too narrow, so the primary action is never pushed off-screen.
 */
public final class HeaderActions extends FlowPane {

    private final HBox left;
    private final FlowPane actions = new FlowPane(8, 8) {
        @Override protected double computePrefWidth(double height) {
            // Exactly the length FlowPane's own run compares with its width: each child's bounded
            // size (a minimum above pref wins) snapped on its own, with snapped margins, accumulated
            // child by child as (width + gap) — the same operations in the same order. With a
            // fractional render scale (thirds of a pixel at 150 %) the same sum taken in another
            // order came out one rounding error short and the last action wrapped (R2-10).
            double hgap = snapSpaceX(getHgap());
            double run = 0, line = 0;
            for (Node n : getChildren()) {
                if (!n.isManaged()) continue;
                Insets m = FlowPane.getMargin(n);
                double left = m != null ? snapSpaceX(m.getLeft()) : 0;
                double right = m != null ? snapSpaceX(m.getRight()) : 0;
                double width = left + snapSizeX(bounded(n.minWidth(-1), n.prefWidth(-1), n.maxWidth(-1))) + right;
                line = run + width; // what FlowPane tests against its inside width for this child
                run += (width + hgap);
            }
            // A millionth of a pixel above it: the pane is then snapped up to the device grid and divided
            // back by the scale, which can land one ulp BELOW the run's own floating sum (584 / 1.5 =
            // 389.3333333333333 < 389.33333333333337 in the r2 JAR) and wrap the last action anyway.
            return snappedLeftInset() + snappedRightInset() + line + 1e-6;
        }
    };

    public HeaderActions(PageHeader header, Node... actionNodes) {
        getStyleClass().add("header-actions");
        setHgap(16);
        setVgap(8);
        setAlignment(Pos.CENTER_LEFT);
        setRowValignment(javafx.geometry.VPos.CENTER);
        setMinWidth(0);
        left = new HBox(header);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(0);
        left.maxWidthProperty().bind(widthProperty());
        header.setMinWidth(0);
        actions.setMinWidth(0);
        actions.maxWidthProperty().bind(widthProperty());
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().addAll(actionNodes);
        getChildren().addAll(left, actions);
    }

    /**
     * Keeps the actions flush right on the title row while they fit, wrapped under it otherwise.
     * Recomputed on every layout pass rather than on width changes only, so a chip whose text,
     * visibility or font changes without a resize re-aligns too instead of keeping a margin
     * computed for the old content. Values are set only when they change, so the pass settles.
     */
    @Override protected void layoutChildren() {
        double inside = getWidth() - snappedLeftInset() - snappedRightInset();
        double rest = inside - snapSizeX(bounded(left.minWidth(-1), left.prefWidth(-1), left.maxWidth(-1)))
                - snapSpaceX(getHgap());
        double actionsWidth = snapSizeX(bounded(actions.minWidth(-1), actions.prefWidth(-1), actions.maxWidth(-1)));
        boolean fits = actionsWidth <= rest;
        Pos alignment = fits ? Pos.CENTER_LEFT : Pos.TOP_LEFT;
        if (getAlignment() != alignment) setAlignment(alignment);
        Insets margin = fits ? new Insets(0, 0, 0, floorToPixel(rest - actionsWidth)) : Insets.EMPTY;
        if (!margin.equals(Objects.requireNonNullElse(FlowPane.getMargin(actions), Insets.EMPTY))) {
            FlowPane.setMargin(actions, margin);
        }
        super.layoutChildren();
    }

    /** Min ≤ pref ≤ max, the size a layout pane actually gives a resizable child. */
    private static double bounded(double min, double pref, double max) {
        return Math.min(Math.max(pref, min), Math.max(min, max));
    }

    /** Never round the alignment margin up: half a pixel too much would wrap the actions. */
    private double floorToPixel(double value) {
        double scale = getScene() != null && getScene().getWindow() != null ? getScene().getWindow().getRenderScaleX() : 1.0;
        return Math.max(0, Math.floor(value * scale) / scale);
    }

    public FlowPane actions() {
        return actions;
    }
}

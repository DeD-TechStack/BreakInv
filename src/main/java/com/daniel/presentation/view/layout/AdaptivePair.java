package com.daniel.presentation.view.layout;

import com.daniel.presentation.view.util.Breakpoint;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.DoublePredicate;

/**
 * Two blocks side by side when the breakpoint allows, stacked otherwise. Replaces the fixed
 * {@code HBox(a, b)} pairs that only shrank fonts in narrow windows.
 *
 * <p>The children are re-parented between an internal HBox and VBox so the same nodes (and any
 * listeners/animations on them) survive the reflow.
 */
public final class AdaptivePair extends Pane {

    private final Node first;
    private final Node second;
    private final HBox row = new HBox();
    private final VBox column = new VBox();
    private final Breakpoint stackBelow;
    private final double firstWeight;
    private boolean sideBySide;
    /** Optional (R4-01): decides side by side × stacked from the width the pair is measured and laid out at. */
    private DoublePredicate sideBySideAt;

    /**
     * @param stackBelow  the first breakpoint (inclusive) at which the pair stacks: e.g. MEDIUM
     *                    means MEDIUM and NARROW stack, WIDE sits side by side
     * @param firstWeight share of the width for the first block when side by side (0–1)
     */
    public AdaptivePair(Node first, Node second, Breakpoint stackBelow, double firstWeight, double gap) {
        this.first = first;
        this.second = second;
        this.stackBelow = stackBelow;
        this.firstWeight = firstWeight;
        getStyleClass().add("adaptive-pair");
        row.setSpacing(gap);
        column.setSpacing(gap);
        column.setFillWidth(true);
        HBox.setHgrow(first, Priority.ALWAYS);
        HBox.setHgrow(second, Priority.ALWAYS);
        VBox.setVgrow(first, Priority.NEVER);
        VBox.setVgrow(second, Priority.NEVER);
        sideBySide = true;
        getChildren().setAll(row);
        row.getChildren().setAll(first, second);
        applyWeights();
    }

    public AdaptivePair(Node first, Node second, Breakpoint stackBelow) {
        this(first, second, stackBelow, 0.5, 16);
    }

    public void onBreakpoint(Breakpoint bp) {
        if (sideBySideAt != null) { // the width rule decides: now with the current width, again at the laid-out width
            arrange(sideBySideAt.test(getWidth()));
            requestLayout();
            return;
        }
        arrange(bp.ordinal() > stackBelow.ordinal());
    }

    /**
     * R4-01: lets the pair choose side by side × stacked from the width it is measured and laid out at, so a
     * parent reserves the height of the arrangement that width will show, in the same pass. A width listener
     * decided it too late: the parent had already reserved the other arrangement's height, and a relayout
     * requested during the parent's own layout is not propagated — stacked blocks ran under the next node.
     */
    public void setSideBySideRule(DoublePredicate sideBySideAt) {
        this.sideBySideAt = sideBySideAt;
        requestLayout();
    }

    private void arrange(boolean side) {
        if (side == sideBySide && !getChildren().isEmpty()) return;
        sideBySide = side;
        row.getChildren().clear();
        column.getChildren().clear();
        if (side) {
            row.getChildren().setAll(first, second);
            getChildren().setAll(row);
        } else {
            column.getChildren().setAll(first, second);
            getChildren().setAll(column);
        }
        requestLayout();
    }

    public boolean isSideBySide() {
        return sideBySide;
    }

    public Node first() {
        return first;
    }

    public Node second() {
        return second;
    }

    private void applyWeights() {
        if (first instanceof javafx.scene.layout.Region r1 && second instanceof javafx.scene.layout.Region r2) {
            r1.setMaxWidth(Double.MAX_VALUE);
            r2.setMaxWidth(Double.MAX_VALUE);
        }
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (sideBySideAt != null && w > 0) arrange(sideBySideAt.test(w)); // the height reserved above was this arrangement's
        Node active = getChildren().isEmpty() ? null : getChildren().get(0);
        if (active == null) return;
        if (active == row && first instanceof javafx.scene.layout.Region r1 && second instanceof javafx.scene.layout.Region r2) {
            double gap = row.getSpacing();
            double usable = Math.max(0, w - gap);
            r1.setPrefWidth(usable * firstWeight);
            r2.setPrefWidth(usable * (1 - firstWeight));
        } else if (first instanceof javafx.scene.layout.Region r1 && second instanceof javafx.scene.layout.Region r2) {
            r1.setPrefWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
            r2.setPrefWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        }
        active.resizeRelocate(0, 0, w, h);
    }

    @Override
    protected double computePrefWidth(double height) {
        Node active = getChildren().isEmpty() ? null : getChildren().get(0);
        return active == null ? 0 : active.prefWidth(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        Node active = getChildren().isEmpty() ? null : getChildren().get(0);
        if (active == null) return 0;
        if (sideBySideAt != null && width > 0) {
            boolean side = sideBySideAt.test(width);
            if (side != sideBySide) return arrangedHeight(side, width); // the arrangement this width switches to
        }
        return active.prefHeight(width);
    }

    /** Height of the arrangement not shown yet, measured as layoutChildren will lay it out at this width. */
    private double arrangedHeight(boolean side, double width) {
        if (side) {
            double usable = Math.max(0, width - row.getSpacing());
            return snapSizeY(Math.max(first.prefHeight(usable * firstWeight), second.prefHeight(usable * (1 - firstWeight))));
        }
        return snapSizeY(first.prefHeight(width)) + snapSpaceY(column.getSpacing()) + snapSizeY(second.prefHeight(width));
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }
}

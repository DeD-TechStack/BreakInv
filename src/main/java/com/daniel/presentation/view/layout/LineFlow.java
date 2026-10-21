package com.daniel.presentation.view.layout;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;

/**
 * A row of items that wraps instead of squeezing them. A plain FlowPane asks for its wrap length
 * (400 px) whatever its content, and an HBox shrinks every child below its text until each one is
 * cut. Here the preferred width is the one-line width and the minimum is the widest item, so a
 * parent offers one line when there is room and, when there is not, the items move to another
 * line whole: values, tickers and options keep their text at any width.
 */
public class LineFlow extends FlowPane {

    public LineFlow(double hgap, double vgap, Node... children) {
        super(hgap, vgap, children);
    }

    /** One line: each child's bounded width snapped on its own, plus margins and gaps (as FlowPane lays them out). */
    @Override
    protected double computePrefWidth(double height) {
        double sum = 0;
        int managed = 0;
        for (Node n : getChildren()) {
            if (!n.isManaged()) continue;
            sum += snapSizeX(bounded(n.minWidth(-1), n.prefWidth(-1), n.maxWidth(-1))) + marginX(n);
            managed++;
        }
        return snappedLeftInset() + snappedRightInset() + sum + Math.max(0, managed - 1) * snapSpaceX(getHgap());
    }

    /** The widest item: below it something would be cut, so this is the floor a parent may give. */
    @Override
    protected double computeMinWidth(double height) {
        double widest = 0;
        for (Node n : getChildren()) {
            if (!n.isManaged()) continue;
            widest = Math.max(widest, snapSizeX(bounded(n.minWidth(-1), n.prefWidth(-1), n.maxWidth(-1))) + marginX(n));
        }
        return snappedLeftInset() + snappedRightInset() + widest;
    }

    private double marginX(Node n) {
        Insets m = FlowPane.getMargin(n);
        return m == null ? 0 : snapSpaceX(m.getLeft()) + snapSpaceX(m.getRight());
    }

    /** Min ≤ pref ≤ max, the size a layout pane actually gives a resizable child. */
    private static double bounded(double min, double pref, double max) {
        return Math.min(Math.max(pref, min), Math.max(min, max));
    }
}

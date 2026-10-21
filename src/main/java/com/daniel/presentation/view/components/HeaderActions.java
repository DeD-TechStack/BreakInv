package com.daniel.presentation.view.components;

import com.daniel.presentation.view.PageHeader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;


/**
 * Page header row: title/subtitle on the left, actions on the right. The actions wrap under the
 * title when the row is too narrow, so the primary action is never pushed off-screen.
 */
public final class HeaderActions extends FlowPane {

    private final FlowPane actions = new FlowPane(8, 8) {
        @Override protected double computePrefWidth(double height) {
            // FlowPane snaps each child separately; rounding only the final sum loses pixels.
            return snappedLeftInset() + snappedRightInset()
                    + getChildren().stream().filter(Node::isManaged)
                            .mapToDouble(n -> snapSizeX(n.prefWidth(-1))).sum()
                    + Math.max(0, getManagedChildren().size() - 1) * snapSpaceX(getHgap());
        }
    };

    public HeaderActions(PageHeader header, Node... actionNodes) {
        getStyleClass().add("header-actions");
        setHgap(16);
        setVgap(8);
        setAlignment(Pos.CENTER_LEFT);
        setRowValignment(javafx.geometry.VPos.CENTER);
        setMinWidth(0);
        HBox left = new HBox(header);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(0);
        left.maxWidthProperty().bind(widthProperty());
        header.setMinWidth(0);
        actions.setMinWidth(0);
        actions.maxWidthProperty().bind(widthProperty());
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().addAll(actionNodes);
        getChildren().addAll(left, actions);
        widthProperty().addListener((o, a, w) -> {
            double rest = w.doubleValue() - left.prefWidth(-1) - getHgap();
            // keep actions right-aligned while they fit on the same row
            boolean fits = actions.prefWidth(-1) <= rest;
            setAlignment(fits ? Pos.CENTER_LEFT : Pos.TOP_LEFT);
            FlowPane.setMargin(actions, fits ? new javafx.geometry.Insets(0, 0, 0, Math.max(0, rest - actions.prefWidth(-1))) : javafx.geometry.Insets.EMPTY);
        });
    }

    public FlowPane actions() {
        return actions;
    }
}

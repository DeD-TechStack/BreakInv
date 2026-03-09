package com.daniel.presentation.view.components;

import com.daniel.core.util.Money;
import javafx.application.Platform;
import javafx.scene.control.TableCell;
import javafx.scene.control.TextField;

public final class MoneyEditingCell<S> extends TableCell<S, Long> {

    private final TextField field = new TextField();
    private boolean committing;

    public MoneyEditingCell() {
        field.setTextFormatter(Money.currencyFormatterEditable());
        field.setPromptText("R$ 0,00");
        Money.applyFormatOnBlur(field);

        field.setOnAction(e -> commitIfPossible());
        field.focusedProperty().addListener((obs, oldV, focused) -> {
            if (!focused) commitIfPossible();
        });

        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> cancelEdit();
            }
        });
    }

    @Override
    public void startEdit() {
        super.startEdit();
        if (!isEmpty()) {
            field.setText(Money.centsToText(getItem() == null ? 0L : getItem()));
            setText(null);
            setGraphic(field);
            Platform.runLater(() -> {
                field.requestFocus();
                field.selectAll();
            });
        }
    }

    @Override
    public void cancelEdit() {
        super.cancelEdit();
        setText(Money.centsToText(getItem() == null ? 0L : getItem()));
        setGraphic(null);
    }

    @Override
    protected void updateItem(Long value, boolean empty) {
        super.updateItem(value, empty);
        if (empty) {
            setText(null);
            setGraphic(null);
            return;
        }

        if (isEditing()) {
            setText(null);
            setGraphic(field);
        } else {
            setText(Money.centsToText(value == null ? 0L : value));
            setGraphic(null);
        }
    }

    private void commitIfPossible() {
        if (committing) return;
        committing = true;
        try {
            long cents = Money.textToCentsSafe(field.getText());
            commitEdit(cents);
        } finally {
            committing = false;
        }
    }
}

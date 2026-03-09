package com.daniel.presentation.view.components;

import com.daniel.infrastructure.api.BrapiClient;
import javafx.application.Platform;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class TickerAutocompleteField extends TextField {

    private final ContextMenu suggestionsMenu = new ContextMenu();
    private String lastQuery = "";

    public TickerAutocompleteField() {
        setPromptText("Digite o ticker (ex: PETR4, VALE3)...");

        textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.length() < 2) {
                suggestionsMenu.hide();
                return;
            }

            if (newVal.equals(lastQuery)) {
                return;
            }

            lastQuery = newVal;
            loadSuggestions(newVal);
        });

        focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                suggestionsMenu.hide();
            }
        });

        // Propagate app theme to the popup's own scene (PopupWindow does not inherit parent stylesheets)
        suggestionsMenu.setOnShowing(e -> {
            javafx.scene.Scene popupScene = suggestionsMenu.getScene();
            if (popupScene != null) {
                String css = TickerAutocompleteField.class
                        .getResource("/styles/app.css").toExternalForm();
                if (!popupScene.getStylesheets().contains(css))
                    popupScene.getStylesheets().add(css);
            }
        });
    }

    private void loadSuggestions(String query) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return BrapiClient.searchTickers(query);
            } catch (Exception e) {
                return List.<BrapiClient.TickerSuggestion>of();
            }
        }).thenAcceptAsync(suggestions -> {
            Platform.runLater(() -> updateSuggestionsMenu(suggestions));
        });
    }

    private void updateSuggestionsMenu(List<BrapiClient.TickerSuggestion> suggestions) {
        suggestionsMenu.getItems().clear();

        if (suggestions.isEmpty()) {
            suggestionsMenu.hide();
            return;
        }

        for (BrapiClient.TickerSuggestion suggestion : suggestions) {
            Label label = new Label(suggestion.ticker() + " - " + suggestion.name());
            label.getStyleClass().add("autocomplete-item");
            label.setMaxWidth(Double.MAX_VALUE);

            CustomMenuItem item = new CustomMenuItem(label);
            item.setHideOnClick(true);

            item.setOnAction(e -> {
                setText(suggestion.ticker());
                positionCaret(getText().length());
                suggestionsMenu.hide();
            });

            suggestionsMenu.getItems().add(item);
        }

        if (!suggestionsMenu.isShowing()) {
            suggestionsMenu.show(this, Side.BOTTOM, 0, 0);
        }
    }
}
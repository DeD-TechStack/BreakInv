package com.daniel.presentation.view.components;

import com.daniel.infrastructure.api.BrapiClient;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.stage.Popup;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * TextField com autocomplete de tickers via Brapi.
 *
 * <p>Usa {@link Popup} + {@link ListView} ao invés de {@code ContextMenu} +
 * {@code CustomMenuItem}, evitando o problema do duplo clique: no JavaFX/Windows,
 * popups de {@code ContextMenu} precisam ser "ativados" (ganhar foco) antes de
 * processar cliques, o que exigia dois cliques para selecionar uma sugestão.</p>
 *
 * <p>A seleção é interceptada no evento {@code MOUSE_PRESSED} via
 * {@code addEventFilter}, que dispara no primeiro clique antes de qualquer
 * mudança de foco.</p>
 */
public final class TickerAutocompleteField extends TextField {

    private final Popup popup = new Popup();
    private final ListView<BrapiClient.TickerSuggestion> listView = new ListView<>();
    private String lastQuery = "";

    public TickerAutocompleteField() {
        setPromptText("Digite o ticker (ex: PETR4, VALE3)...");

        // ── Popup setup ──
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.getContent().add(listView);

        listView.setPrefHeight(200);
        listView.getStyleClass().add("autocomplete-list");

        // Propagate app theme to popup scene
        popup.setOnShowing(e -> {
            if (listView.getScene() != null) {
                String css = TickerAutocompleteField.class
                        .getResource("/styles/app.css").toExternalForm();
                if (!listView.getScene().getStylesheets().contains(css)) {
                    listView.getScene().getStylesheets().add(css);
                }
            }
        });

        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(BrapiClient.TickerSuggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().remove("autocomplete-item");
                } else {
                    String display = item.ticker();
                    if (item.name() != null && !item.name().isBlank()) {
                        display += "  —  " + item.name();
                    }
                    setText(display);
                    if (!getStyleClass().contains("autocomplete-item")) {
                        getStyleClass().add("autocomplete-item");
                    }
                }
            }
        });

        // MOUSE_PRESSED via addEventFilter: dispara no primeiro clique, antes de
        // qualquer mudança de foco — resolve o problema do duplo clique.
        listView.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            // Sobe na árvore de nós para encontrar o ListCell clicado
            Node target = (Node) e.getTarget();
            while (target != null && !(target instanceof ListCell)) {
                target = target.getParent();
            }
            if (target instanceof ListCell<?> cell && !cell.isEmpty()) {
                @SuppressWarnings("unchecked")
                BrapiClient.TickerSuggestion item =
                        (BrapiClient.TickerSuggestion) cell.getItem();
                if (item != null) {
                    selectSuggestion(item);
                    e.consume();
                }
            }
        });

        // ── Autocomplete trigger ──
        textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.length() < 2) {
                popup.hide();
                return;
            }
            if (newVal.equals(lastQuery)) return;
            lastQuery = newVal;
            loadSuggestions(newVal);
        });

        // Fecha popup ao perder foco para qualquer coisa que não seja o próprio popup
        focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                // Pequeno delay: garante que um MOUSE_PRESSED no listView já foi
                // processado antes de esconder o popup
                Platform.runLater(() -> {
                    if (!isFocused()) popup.hide();
                });
            }
        });
    }

    private void selectSuggestion(BrapiClient.TickerSuggestion suggestion) {
        lastQuery = suggestion.ticker(); // deve ser antes de setText para bloquear o listener
        setText(suggestion.ticker());
        positionCaret(getText().length());
        popup.hide();
        requestFocus();
    }

    private void loadSuggestions(String rawQuery) {
        String query = rawQuery.trim().toUpperCase();
        CompletableFuture
                .supplyAsync(() -> {
                    try { return BrapiClient.searchTickers(query); }
                    catch (Exception e) { return List.<BrapiClient.TickerSuggestion>of(); }
                })
                .thenComposeAsync(results -> {
                    if (!results.isEmpty()) return CompletableFuture.completedFuture(results);
                    if (query.length() > 4) {
                        String shortQuery = query.substring(0, 4);
                        return CompletableFuture.supplyAsync(() -> {
                            try { return BrapiClient.searchTickers(shortQuery); }
                            catch (Exception e) { return List.<BrapiClient.TickerSuggestion>of(); }
                        });
                    }
                    return CompletableFuture.completedFuture(results);
                })
                .thenAcceptAsync(suggestions -> Platform.runLater(() -> {
                    if (suggestions.isEmpty()) {
                        updateList(List.of(new BrapiClient.TickerSuggestion(
                                query, "Pressione Enter para confirmar diretamente", null)));
                    } else {
                        updateList(suggestions);
                    }
                }));
    }

    private void updateList(List<BrapiClient.TickerSuggestion> suggestions) {
        if (suggestions.isEmpty()) {
            popup.hide();
            return;
        }

        listView.getItems().setAll(suggestions);
        // Ajusta altura proporcional ao número de itens (máx 5 visíveis)
        int visible = Math.min(suggestions.size(), 5);
        listView.setPrefHeight(visible * 36.0 + 2);

        if (!popup.isShowing()) {
            Bounds bounds = localToScreen(getBoundsInLocal());
            if (bounds != null) {
                popup.show(this,
                        bounds.getMinX(),
                        bounds.getMaxY());
                listView.setPrefWidth(bounds.getWidth());
            }
        }
    }
}

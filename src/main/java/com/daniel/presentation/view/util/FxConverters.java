package com.daniel.presentation.view.util;

import com.daniel.core.domain.entity.InvestmentType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.util.StringConverter;

public final class FxConverters {
    private FxConverters() {}

    public static StringConverter<InvestmentType> investmentTypeConverter() {
        return new StringConverter<>() {
            @Override public String toString(InvestmentType t) { return t == null ? "" : t.name(); }
            @Override public InvestmentType fromString(String s) { return null; }
        };
    }

    public static void applyInvestmentTypeRenderer(ComboBox<InvestmentType> combo) {
        combo.setConverter(investmentTypeConverter());
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(InvestmentType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(InvestmentType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
    }
}

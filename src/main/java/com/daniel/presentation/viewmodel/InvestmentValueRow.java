package com.daniel.presentation.viewmodel;

import javafx.beans.property.*;

public final class InvestmentValueRow {

    private final long investmentTypeId;
    private final StringProperty name = new SimpleStringProperty();
    private final LongProperty valueCents = new SimpleLongProperty(0);
    private final LongProperty profitCents = new SimpleLongProperty(0);

    public InvestmentValueRow(long investmentTypeId, String name, long valueCents) {
        this.investmentTypeId = investmentTypeId;
        this.name.set(name);
        this.valueCents.set(valueCents);
    }

    public long getInvestmentTypeId() { return investmentTypeId; }

    public StringProperty nameProperty() { return name; }
    public String getName() { return name.get(); }

    public LongProperty valueCentsProperty() { return valueCents; }
    public long getValueCents() { return valueCents.get(); }
    public void setValueCents(long cents) { valueCents.set(cents); }

    public LongProperty profitCentsProperty() { return profitCents; }
    public long getProfitCents() { return profitCents.get(); }
    public void setProfitCents(long cents) { profitCents.set(cents); }

    public StringProperty profitTextProperty() {
        return new SimpleStringProperty(""); // preenchido pela página (pra manter simples e rápido)
    }
}

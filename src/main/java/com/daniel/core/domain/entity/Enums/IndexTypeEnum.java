package com.daniel.core.domain.entity.Enums;

public enum IndexTypeEnum {
    CDI("CDI", null, true),
    SELIC("Selic", 0.15, false),
    IPCA("IPCA", null, true);

    private final String displayName;
    private final Double fixedRate;
    private final boolean editable;

    IndexTypeEnum(String displayName, Double fixedRate, boolean editable) {
        this.displayName = displayName;
        this.fixedRate = fixedRate;
        this.editable = editable;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Double getFixedRate() {
        return fixedRate;
    }

    public boolean isEditable() {
        return editable;
    }

    public boolean hasFixedRate() {
        return fixedRate != null;
    }

    public String getRateDescription() {
        if (hasFixedRate()) {
            return String.format("%s (%.2f%% fixo)", displayName, fixedRate * 100);
        } else {
            return String.format("%s (taxa variável)", displayName);
        }
    }
}
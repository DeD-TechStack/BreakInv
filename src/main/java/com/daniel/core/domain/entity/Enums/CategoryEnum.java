package com.daniel.core.domain.entity.Enums;

public enum CategoryEnum {
    RENDA_FIXA("Renda Fixa", "#3b82f6"),
    ACOES("Ações", "#22c55e"),
    FUNDOS_IMOBILIARIOS("Fundos Imobiliários", "#14b8a6"), // R3-11: teal, apart from Ações (was #10b981, 18° away)
    FUNDOS("Fundos de Investimento", "#f59e0b"),
    PREVIDENCIA("Previdência", "#8b5cf6"),
    CRIPTOMOEDAS("Criptomoedas", "#ec4899"),
    OUTROS("Outros", "#6b7280");

    private final String displayName;
    private final String color;

    CategoryEnum(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() { return displayName; }
    public String getColor() { return color; }
}


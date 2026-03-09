package com.daniel.core.domain.entity.Enums;

public enum LiquidityEnum {
    MUITO_ALTA("Imediata (D+0)", "#22c55e"),
    ALTA("Alta (D+1 a D+2)", "#84cc16"),
    MEDIA("Média (D+3 a D+30)", "#eab308"),
    BAIXA("Baixa (D+30 a D+90)", "#f97316"),
    MUITO_BAIXA("Muito Baixa (>D+90)", "#ef4444");

    private final String displayName;
    private final String color;

    LiquidityEnum(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() { return displayName; }
    public String getColor() { return color; }
}

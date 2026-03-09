package com.daniel.core.domain.entity;

import com.daniel.core.domain.entity.Enums.InvestmentTypeEnum;
import com.daniel.core.domain.entity.Enums.IndexTypeEnum;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvestmentType(
        int id,
        String name,
        String category,
        String liquidity,
        LocalDate investmentDate,
        BigDecimal profitability,
        BigDecimal investedValue,

        // NOVOS CAMPOS
        String typeOfInvestment,
        String indexType,
        BigDecimal indexPercentage,
        String ticker,
        BigDecimal purchasePrice,
        Integer quantity,
        BigDecimal currentPrice
) {
    // Construtor simplificado (compatibilidade)
    public InvestmentType(int id, String name) {
        this(id, name, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // Construtor básico (compatibilidade)
    public InvestmentType(int id, String name, String category, String liquidity,
                          LocalDate investmentDate, BigDecimal profitability,
                          BigDecimal investedValue) {
        this(id, name, category, liquidity, investmentDate, profitability, investedValue,
                null, null, null, null, null, null, null);
    }

    public InvestmentType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nome não pode ser vazio");
        }
    }

    public boolean hasFullData() {
        return category != null && liquidity != null &&
                investmentDate != null && profitability != null &&
                investedValue != null;
    }

    public boolean hasInvestmentTypeData() {
        return typeOfInvestment != null;
    }

    public InvestmentTypeEnum getInvestmentTypeEnum() {
        if (typeOfInvestment == null) return null;
        try {
            return InvestmentTypeEnum.valueOf(typeOfInvestment);
        } catch (Exception e) {
            return null;
        }
    }

    public IndexTypeEnum getIndexTypeEnum() {
        if (indexType == null) return null;
        try {
            return IndexTypeEnum.valueOf(indexType);
        } catch (Exception e) {
            return null;
        }
    }
}
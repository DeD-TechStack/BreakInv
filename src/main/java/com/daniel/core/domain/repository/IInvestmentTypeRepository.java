package com.daniel.core.domain.repository;

import com.daniel.core.domain.entity.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface IInvestmentTypeRepository {
    List<InvestmentType> listAll();
    void save(String name);
    void rename(int id, String newName);
    void delete(int id);

    default int createFull(String name, String category, String liquidity,
                           LocalDate investmentDate, BigDecimal profitability,
                           BigDecimal investedValue, String typeOfInvestment,
                           String indexType, BigDecimal indexPercentage,
                           String ticker, BigDecimal purchasePrice, Integer quantity) {
        throw new UnsupportedOperationException("Repository não suporta createFull");
    }

    default void updateFull(int id, String name, String category, String liquidity,
                            LocalDate investmentDate, BigDecimal profitability,
                            BigDecimal investedValue, String typeOfInvestment,
                            String indexType, BigDecimal indexPercentage,
                            String ticker, BigDecimal purchasePrice, Integer quantity) {
        throw new UnsupportedOperationException("Repository não suporta updateFull");
    }
}

package com.daniel.core.domain.entity;

import java.time.LocalDate;

public record Transaction(
        long id,
        LocalDate date,
        int investmentTypeId,
        String type,
        String name,
        String ticker,
        Integer quantity,
        Long unitPriceCents,
        long totalCents,
        String note
) {
    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
}

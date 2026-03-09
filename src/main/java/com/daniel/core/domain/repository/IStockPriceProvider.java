package com.daniel.core.domain.repository;

public interface IStockPriceProvider {
    /**
     * Returns the current market price (BRL) for the given ticker,
     * or null if the price is unavailable (network error, invalid ticker, etc.).
     */
    Double fetchPrice(String ticker);
}

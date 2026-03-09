package com.daniel.infrastructure.api;

import com.daniel.core.domain.repository.IStockPriceProvider;

public class BrapiStockPriceProvider implements IStockPriceProvider {

    @Override
    public Double fetchPrice(String ticker) {
        try {
            BrapiClient.StockData data = BrapiClient.fetchStockData(ticker);
            if (data != null && data.isValid()) {
                return data.regularMarketPrice();
            }
        } catch (Exception ignored) {}
        return null;
    }
}

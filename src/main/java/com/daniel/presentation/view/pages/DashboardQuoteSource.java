package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.api.BrapiClient;
import com.daniel.infrastructure.api.BrapiClient.StockData;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * The quotes the Dashboard reads, behind one seam: production delegates to {@link BrapiClient}
 * exactly as before, a test hands the page fixed quotes without touching the network or the
 * token setting.
 */
interface DashboardQuoteSource {

    /** Whether a BRAPI token is configured (the warning line and the data-origin chips follow it). */
    boolean hasToken();

    /** When the last BRAPI fetch succeeded (ms since the epoch), 0 when none did. */
    long lastSuccessfulFetchMs();

    /** The Ibovespa return used as a benchmark; empty when it cannot be obtained. */
    Optional<Double> fetchIbovespaReturn();

    /** Quotes of several tickers at once, keyed by ticker (comma separated in {@code tickers}). */
    Map<String, StockData> fetchMultipleStocks(String tickers) throws IOException;

    /** The quote of one ticker. */
    StockData fetchStockData(String ticker) throws IOException;

    /** The production source: every call goes to {@link BrapiClient}. */
    static DashboardQuoteSource brapi() {
        return new DashboardQuoteSource() {
            @Override public boolean hasToken() { return BrapiClient.hasToken(); }
            @Override public long lastSuccessfulFetchMs() { return BrapiClient.getLastSuccessfulFetchMs(); }
            @Override public Optional<Double> fetchIbovespaReturn() { return BrapiClient.fetchIbovespaReturn(); }
            @Override public Map<String, StockData> fetchMultipleStocks(String tickers) throws IOException {
                return BrapiClient.fetchMultipleStocks(tickers);
            }
            @Override public StockData fetchStockData(String ticker) throws IOException {
                return BrapiClient.fetchStockData(ticker);
            }
        };
    }
}

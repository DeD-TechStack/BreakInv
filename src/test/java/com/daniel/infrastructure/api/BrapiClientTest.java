package com.daniel.infrastructure.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safe tests for BrapiClient — only paths that involve NO HTTP calls:
 *
 *   1. BrapiClient.StockData record logic (hasError, isValid) — pure methods
 *   2. fetchStockDataWithToken() with null/blank ticker — early-return guard
 *   3. searchTickers() with null/blank/short query — early-return guard
 *
 * The following paths are BLOCKED (require live HTTP or mock framework):
 *   - fetchStockData(validTicker)
 *   - fetchMultipleStocks()
 *   - estimateDividends()
 *   - fetchIbovespaReturn()
 *   - testConnection() / testConnectionWithToken()
 *   - hasToken() — reads from AppSettingsRepository → Database.open() → production DB
 */
class BrapiClientTest {

    // ===== StockData.hasError() =====

    @Test
    void stockData_hasError_nullError_returnsFalse() {
        BrapiClient.StockData data = stockDataWithError(null);
        assertFalse(data.hasError());
    }

    @Test
    void stockData_hasError_blankError_returnsFalse() {
        BrapiClient.StockData data = stockDataWithError("   ");
        assertFalse(data.hasError());
    }

    @Test
    void stockData_hasError_emptyError_returnsFalse() {
        BrapiClient.StockData data = stockDataWithError("");
        assertFalse(data.hasError());
    }

    @Test
    void stockData_hasError_withMessage_returnsTrue() {
        BrapiClient.StockData data = stockDataWithError("Ticker inválido");
        assertTrue(data.hasError());
    }

    @Test
    void stockData_hasError_httpErrorMessage_returnsTrue() {
        BrapiClient.StockData data = stockDataWithError("Erro HTTP: 404");
        assertTrue(data.hasError());
    }

    // ===== StockData.isValid() =====

    @Test
    void stockData_isValid_zeroPriceNoError_returnsFalse() {
        // Price 0, no error → not valid
        BrapiClient.StockData data = stockData("PETR4", 0.0, null);
        assertFalse(data.isValid());
    }

    @Test
    void stockData_isValid_positivePriceNoError_returnsTrue() {
        BrapiClient.StockData data = stockData("PETR4", 35.50, null);
        assertTrue(data.isValid());
    }

    @Test
    void stockData_isValid_positivePriceWithError_returnsFalse() {
        // Has error even if price > 0 → not valid
        BrapiClient.StockData data = stockData("PETR4", 35.50, "Algum erro");
        assertFalse(data.isValid());
    }

    @Test
    void stockData_isValid_negativePriceNoError_returnsFalse() {
        // Negative price is not > 0
        BrapiClient.StockData data = stockData("PETR4", -1.0, null);
        assertFalse(data.isValid());
    }

    // ===== fetchStockDataWithToken — early return on null/blank ticker (no HTTP) =====

    @Test
    void fetchStockDataWithToken_nullTicker_returnsErrorWithoutHttp() throws Exception {
        // null ticker hits early-return guard before any HTTP call
        BrapiClient.StockData result = BrapiClient.fetchStockDataWithToken(null, null);
        assertTrue(result.hasError(), "Null ticker should produce error: " + result.error());
        assertFalse(result.isValid());
    }

    @Test
    void fetchStockDataWithToken_blankTicker_returnsErrorWithoutHttp() throws Exception {
        BrapiClient.StockData result = BrapiClient.fetchStockDataWithToken("   ", null);
        assertTrue(result.hasError(), "Blank ticker should produce error: " + result.error());
        assertFalse(result.isValid());
    }

    @Test
    void fetchStockDataWithToken_emptyTicker_returnsErrorWithoutHttp() throws Exception {
        BrapiClient.StockData result = BrapiClient.fetchStockDataWithToken("", "any-token");
        assertTrue(result.hasError());
        assertFalse(result.isValid());
    }

    @Test
    void fetchStockDataWithToken_nullTicker_preservesTickerField() throws Exception {
        BrapiClient.StockData result = BrapiClient.fetchStockDataWithToken(null, null);
        // ticker field should be the original value passed
        assertNull(result.ticker());
    }

    // ===== searchTickers — early return on invalid query (no HTTP) =====

    @Test
    void searchTickers_null_returnsEmptyListWithoutHttp() throws Exception {
        List<BrapiClient.TickerSuggestion> result = BrapiClient.searchTickers(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchTickers_blank_returnsEmptyListWithoutHttp() throws Exception {
        List<BrapiClient.TickerSuggestion> result = BrapiClient.searchTickers("   ");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchTickers_empty_returnsEmptyListWithoutHttp() throws Exception {
        List<BrapiClient.TickerSuggestion> result = BrapiClient.searchTickers("");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchTickers_singleChar_returnsEmptyListWithoutHttp() throws Exception {
        // length < 2 → early return
        List<BrapiClient.TickerSuggestion> result = BrapiClient.searchTickers("P");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ===== TickerSuggestion record =====

    @Test
    void tickerSuggestion_recordFields() {
        BrapiClient.TickerSuggestion s = new BrapiClient.TickerSuggestion("PETR4", "Petrobras", "stock");
        assertEquals("PETR4", s.ticker());
        assertEquals("Petrobras", s.name());
        assertEquals("stock", s.type());
    }

    // ===== Helpers =====

    private static BrapiClient.StockData stockDataWithError(String error) {
        return new BrapiClient.StockData(
                "TEST", null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, 0, error
        );
    }

    private static BrapiClient.StockData stockData(String ticker, double price, String error) {
        return new BrapiClient.StockData(
                ticker, null, null,
                price, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, 0, error
        );
    }
}

package com.daniel.infrastructure.api;

import com.daniel.infrastructure.api.BrapiClient.FailureReason;
import com.daniel.infrastructure.api.BrapiClient.FetchResult;
import com.daniel.infrastructure.api.BrapiClient.StockData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a quote fetch failed, without network: {@code fetchStockDataSafe} and
 * {@code fetchMultipleStocksSafe} against scripted HTTP answers.
 *
 * <p>Each test owns its tickers and runs against {@link FakeBrapiHttp}; the production wiring is
 * restored after every test and checked once more after the class.</p>
 */
class BrapiClientFailureTest {

    private static final String ERROR_TRUE = "{\"error\":true,\"message\":\"x\"}";
    private static final String NOT_FOUND_BODY =
            "{\"error\":true,\"message\":\"Recurso não encontrado\",\"code\":\"NOT_FOUND\"}";
    private static final String QUOTES_EXCEEDED_BODY =
            "{\"error\":true,\"message\":\"Limite de ações por requisição excedido\","
                    + "\"code\":\"QUOTES_PER_REQUEST_EXCEEDED\"}";
    private static final String EMPTY_RESULTS = "{\"results\":[]}";
    private static final String NOT_JSON = "not json";
    private static final String RESULTS_IS_STRING = "{\"results\":\"x\"}";
    private static final String ERROR_OBJECT = "{\"error\":{\"code\":\"X\"}}";
    private static final String ERROR_NULL = "{\"error\":null}";
    private static final String ITEM_NOT_OBJECT = "{\"results\":[\"x\"]}";

    @BeforeEach
    void startsFromProductionState() {
        FakeBrapiHttp.assertProductionState();
    }

    @AfterEach
    void restoreProductionState() {
        FakeBrapiHttp.restore();
    }

    @AfterAll
    static void productionStateIsBack() {
        FakeBrapiHttp.assertProductionState();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String good(String symbol) {
        return "{\"results\":[{\"symbol\":\"" + symbol + "\",\"regularMarketPrice\":10.5}]}";
    }

    private static String zeroPrice(String symbol) {
        return "{\"results\":[{\"symbol\":\"" + symbol + "\",\"regularMarketPrice\":0}]}";
    }

    private static String missingPrice(String symbol) {
        return "{\"results\":[{\"symbol\":\"" + symbol + "\",\"longName\":\"Sem preço\"}]}";
    }

    private static String goodBatch(String... symbols) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < symbols.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"symbol\":\"").append(symbols[i]).append("\",\"regularMarketPrice\":10.5}");
        }
        return sb.append("]}").toString();
    }

    private static FetchResult<StockData> quote(String ticker) {
        return assertDoesNotThrow(() -> {
            return BrapiClient.fetchStockDataSafe(ticker);
        }, "fetchStockDataSafe must never throw");
    }

    private static FetchResult<Map<String, StockData>> batch(String tickers) {
        return assertDoesNotThrow(() -> {
            return BrapiClient.fetchMultipleStocksSafe(tickers);
        }, "fetchMultipleStocksSafe must never throw");
    }

    private static void assertFailure(FetchResult<?> result, FailureReason reason, String detail) {
        assertInstanceOf(FetchResult.Failure.class, result, "expected a failure but got " + result);
        assertEquals(reason, result.getReason());
        if (detail != null) {
            assertEquals(detail, result.getDetail());
        }
    }

    /** Script for a single-ticker batch that fails: the batch call and the one fallback call get the same answer. */
    private static FakeBrapiHttp twice(String token, int code, String body) {
        return new FakeBrapiHttp().enqueue(code, body).enqueue(code, body).install(token);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  fetchStockDataSafe
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void quote_noTokenWith401_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(401, ERROR_TRUE).install(null);

        assertFailure(quote("QF01"), FailureReason.NO_TOKEN, "401");
        assertEquals(1, http.calls());
        assertFalse(http.url(0).contains("token="), "no token configured: nothing to send");
    }

    @Test
    void quote_rejectedTokenWith401_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(401, ERROR_TRUE).install("t-ruim");

        assertFailure(quote("QF02"), FailureReason.NO_TOKEN, "401");
        assertEquals(1, http.calls());
        assertTrue(http.url(0).contains("token=t-ruim"), "the configured token goes in the query");
    }

    @Test
    void quote_tokenWith403_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(403, ERROR_TRUE).install("t-ok");

        assertFailure(quote("QF03"), FailureReason.NO_TOKEN, "403");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_unknownHostWithoutToken_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new UnknownHostException("brapi.dev")).install(null);

        assertFailure(quote("QF04"), FailureReason.NETWORK_ERROR, "brapi.dev");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_unknownHostWithToken_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new UnknownHostException("brapi.dev")).install("t-ok");

        assertFailure(quote("QF05"), FailureReason.NETWORK_ERROR, "brapi.dev");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_socketTimeout_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new SocketTimeoutException("timeout")).install("t-ok");

        assertFailure(quote("QF06"), FailureReason.NETWORK_ERROR, "timeout");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_connectException_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new ConnectException("Connection refused")).install("t-ok");

        assertFailure(quote("QF07"), FailureReason.NETWORK_ERROR, "Connection refused");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_429WithRetryAfterAndToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install("t-ok");

        assertFailure(quote("QF08"), FailureReason.HTTP_ERROR, "429");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_429WithRetryAfterWithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install(null);

        assertFailure(quote("QF09"), FailureReason.HTTP_ERROR, "429");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_500WithToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(500, "").install("t-ok");

        assertFailure(quote("QF10"), FailureReason.HTTP_ERROR, "500");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_500WithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(500, "").install(null);

        assertFailure(quote("QF11"), FailureReason.HTTP_ERROR, "500");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_503WithToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(503, "").install("t-ok");

        assertFailure(quote("QF12"), FailureReason.HTTP_ERROR, "503");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_503WithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(503, "").install(null);

        assertFailure(quote("QF13"), FailureReason.HTTP_ERROR, "503");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_400_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(400, QUOTES_EXCEEDED_BODY).install("t-ok");

        assertFailure(quote("QF14"), FailureReason.HTTP_ERROR, "400");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_404WithErrorBody_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(404, NOT_FOUND_BODY).install("t-ok");

        assertFailure(quote("QF15"), FailureReason.NOT_FOUND, "404");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200EmptyResults_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, EMPTY_RESULTS).install("t-ok");

        assertFailure(quote("QF16"), FailureReason.NOT_FOUND, "Ação não encontrada");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorTrue_isNotFoundWithTrueAsDetail() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ERROR_TRUE).install("t-ok");

        assertFailure(quote("QF17"), FailureReason.NOT_FOUND, "true");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200NotJson_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, NOT_JSON).install("t-ok");

        assertFailure(quote("QF18"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ResultsIsNotAnArray_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, RESULTS_IS_STRING).install("t-ok");

        assertFailure(quote("QF19"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200EmptyBody_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "").install("t-ok");

        assertFailure(quote("QF20"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsAnObject_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ERROR_OBJECT).install("t-ok");

        assertFailure(quote("QF21"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsNull_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ERROR_NULL).install("t-ok");

        assertFailure(quote("QF22"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ResultItemIsNotAnObject_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ITEM_NOT_OBJECT).install("t-ok");

        assertFailure(quote("QF23"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200PriceZero_isNotFoundNeverASuccess() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, zeroPrice("QF24")).install("t-ok");

        assertFailure(quote("QF24"), FailureReason.NOT_FOUND, "Ação sem preço");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200PriceMissing_isNotFoundNeverASuccess() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, missingPrice("QF25")).install("t-ok");

        assertFailure(quote("QF25"), FailureReason.NOT_FOUND, "Ação sem preço");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_nullTicker_isNotFoundWithoutCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");

        assertFailure(quote(null), FailureReason.NOT_FOUND, "Ticker inválido");
        assertEquals(0, http.calls());
    }

    @Test
    void quote_blankTicker_isNotFoundWithoutCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");

        assertFailure(quote("   "), FailureReason.NOT_FOUND, "Ticker inválido");
        assertEquals(0, http.calls());
    }

    @Test
    void quote_validResponse_isSuccessAndSendsTheTokenInTheQuery() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, good("QF26")).install("t-ok");

        FetchResult<StockData> result = quote("qf26");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(10.5, result.getData().regularMarketPrice());
        assertEquals(1, http.calls());
        assertTrue(http.url(0).contains("/quote/QF26"), http.url(0));
        assertTrue(http.url(0).contains("token=t-ok"), http.url(0));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  fetchMultipleStocksSafe: same cases, one ticker (batch call + one fallback call)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void batch_noTokenWith401_isNoToken() {
        FakeBrapiHttp http = twice(null, 401, ERROR_TRUE);

        assertFailure(batch("BF01"), FailureReason.NO_TOKEN, "401");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_rejectedTokenWith401_isNoToken() {
        FakeBrapiHttp http = twice("t-ruim", 401, ERROR_TRUE);

        assertFailure(batch("BF02"), FailureReason.NO_TOKEN, "401");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_tokenWith403_isNoToken() {
        FakeBrapiHttp http = twice("t-ok", 403, ERROR_TRUE);

        assertFailure(batch("BF03"), FailureReason.NO_TOKEN, "403");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_unknownHostWithoutToken_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new UnknownHostException("brapi.dev"))
                .enqueue(new UnknownHostException("brapi.dev"))
                .install(null);

        assertFailure(batch("BF04"), FailureReason.NETWORK_ERROR, "brapi.dev");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_unknownHostWithToken_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new UnknownHostException("brapi.dev"))
                .enqueue(new UnknownHostException("brapi.dev"))
                .install("t-ok");

        assertFailure(batch("BF05"), FailureReason.NETWORK_ERROR, "brapi.dev");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_socketTimeout_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new SocketTimeoutException("timeout"))
                .enqueue(new SocketTimeoutException("timeout"))
                .install("t-ok");

        assertFailure(batch("BF06"), FailureReason.NETWORK_ERROR, "timeout");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_connectException_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new ConnectException("Connection refused"))
                .enqueue(new ConnectException("Connection refused"))
                .install("t-ok");

        assertFailure(batch("BF07"), FailureReason.NETWORK_ERROR, "Connection refused");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_429WithRetryAfterAndToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").enqueue(429, "", "30").install("t-ok");

        assertFailure(batch("BF08"), FailureReason.HTTP_ERROR, "429");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_429WithRetryAfterWithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").enqueue(429, "", "30").install(null);

        assertFailure(batch("BF09"), FailureReason.HTTP_ERROR, "429");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_500WithToken_isHttpError() {
        FakeBrapiHttp http = twice("t-ok", 500, "");

        assertFailure(batch("BF10"), FailureReason.HTTP_ERROR, "500");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_500WithoutToken_isHttpError() {
        FakeBrapiHttp http = twice(null, 500, "");

        assertFailure(batch("BF11"), FailureReason.HTTP_ERROR, "500");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_503WithToken_isHttpError() {
        FakeBrapiHttp http = twice("t-ok", 503, "");

        assertFailure(batch("BF12"), FailureReason.HTTP_ERROR, "503");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_503WithoutToken_isHttpError() {
        FakeBrapiHttp http = twice(null, 503, "");

        assertFailure(batch("BF13"), FailureReason.HTTP_ERROR, "503");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_404WithErrorBody_isNotFound() {
        FakeBrapiHttp http = twice("t-ok", 404, NOT_FOUND_BODY);

        assertFailure(batch("BF14"), FailureReason.NOT_FOUND, "404");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200EmptyResults_isNotFound() {
        FakeBrapiHttp http = twice("t-ok", 200, EMPTY_RESULTS);

        assertFailure(batch("BF15"), FailureReason.NOT_FOUND, "Ação não encontrada");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200ErrorTrue_isNotFound() {
        FakeBrapiHttp http = twice("t-ok", 200, ERROR_TRUE);

        assertFailure(batch("BF16"), FailureReason.NOT_FOUND, "true");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200NotJson_isParseError() {
        FakeBrapiHttp http = twice("t-ok", 200, NOT_JSON);

        assertFailure(batch("BF17"), FailureReason.PARSE_ERROR, null);
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200ResultsIsNotAnArray_isParseError() {
        FakeBrapiHttp http = twice("t-ok", 200, RESULTS_IS_STRING);

        assertFailure(batch("BF18"), FailureReason.PARSE_ERROR, null);
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200EmptyBody_isParseError() {
        FakeBrapiHttp http = twice("t-ok", 200, "");

        assertFailure(batch("BF19"), FailureReason.PARSE_ERROR, null);
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200ErrorIsAnObject_isParseError() {
        FakeBrapiHttp http = twice("t-ok", 200, ERROR_OBJECT);

        assertFailure(batch("BF20"), FailureReason.PARSE_ERROR, null);
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200ErrorIsNull_isParseError() {
        FakeBrapiHttp http = twice("t-ok", 200, ERROR_NULL);

        assertFailure(batch("BF21"), FailureReason.PARSE_ERROR, null);
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200ResultItemIsNotAnObject_isParseError() {
        FakeBrapiHttp http = twice("t-ok", 200, ITEM_NOT_OBJECT);

        assertFailure(batch("BF22"), FailureReason.PARSE_ERROR, null);
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200PriceZero_isNotFoundNeverASuccess() {
        FakeBrapiHttp http = twice("t-ok", 200, zeroPrice("BF23"));

        assertFailure(batch("BF23"), FailureReason.NOT_FOUND, "Ação sem preço");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_200PriceMissing_isNotFoundNeverASuccess() {
        FakeBrapiHttp http = twice("t-ok", 200, missingPrice("BF24"));

        assertFailure(batch("BF24"), FailureReason.NOT_FOUND, "Ação sem preço");
        assertEquals(2, http.calls());
    }

    // ── batch: input, success and partial results ─────────────────────────

    @Test
    void batch_nullTickers_isNotFoundWithoutCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");

        assertFailure(batch(null), FailureReason.NOT_FOUND, "sem tickers");
        assertEquals(0, http.calls());
    }

    @Test
    void batch_blankTickers_isNotFoundWithoutCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");

        assertFailure(batch("  "), FailureReason.NOT_FOUND, "sem tickers");
        assertEquals(0, http.calls());
    }

    @Test
    void batch_allValid_isSuccessWithOneCallAndTheTokenInTheQuery() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, goodBatch("BS01A", "BS01B")).install("t-ok");
        long before = BrapiClient.getLastSuccessfulFetchMs();
        long startedAt = System.currentTimeMillis();

        FetchResult<Map<String, StockData>> result = batch("bs01a,bs01b");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().containsKey("BS01A"));
        assertTrue(result.getData().containsKey("BS01B"));
        assertEquals(1, http.calls());
        assertTrue(http.url(0).contains("/quote/BS01A,BS01B"), http.url(0));
        assertTrue(http.url(0).contains("fundamental=true"), http.url(0));
        assertTrue(http.url(0).contains("token=t-ok"), http.url(0));
        assertTrue(BrapiClient.getLastSuccessfulFetchMs() >= startedAt,
                "a success stamps the last successful fetch (was " + before + ")");
    }

    @Test
    void batch_failure_doesNotStampTheLastSuccessfulFetch() {
        twice("t-ok", 500, "");
        long before = BrapiClient.getLastSuccessfulFetchMs();

        assertFailure(batch("BF25"), FailureReason.HTTP_ERROR, "500");

        assertEquals(before, BrapiClient.getLastSuccessfulFetchMs());
    }

    @Test
    void batch_partialBatchWithAZeroPriceTicker_keepsOnlyTheValidOne() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(200, "{\"results\":[{\"symbol\":\"BP01A\",\"regularMarketPrice\":10.5},"
                        + "{\"symbol\":\"BP01B\",\"regularMarketPrice\":0}]}")
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BP01A,BP01B");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(1, result.getData().size());
        assertTrue(result.getData().containsKey("BP01A"));
        assertFalse(result.getData().containsKey("BP01B"), "a zero price never enters the result");
        assertEquals(1, http.calls(), "a usable batch needs no fallback");
    }

    // ── batch: the 400 + fallback cases ───────────────────────────────────

    @Test
    void batch_400QuotesPerRequestExceeded_fallbackSucceeds_isSuccessWithAll() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(200, good("BQ01A"))
                .enqueue(200, good("BQ01B"))
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BQ01A,BQ01B");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().containsKey("BQ01A"));
        assertTrue(result.getData().containsKey("BQ01B"));
        assertEquals(3, http.calls());
        assertTrue(http.url(1).contains("/quote/BQ01A"), http.url(1));
        assertTrue(http.url(2).contains("/quote/BQ01B"), http.url(2));
    }

    @Test
    void batch_400QuotesPerRequestExceeded_fallbackAll404_isNotFoundAndThe400DoesNotShow() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(404, NOT_FOUND_BODY)
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BQ02A,BQ02B");

        assertFailure(result, FailureReason.NOT_FOUND, "404");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_fallbackOneSucceedsOneFails_isPartialSuccess() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(429, "", "30")
                .enqueue(200, good("BQ03B"))
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BQ03A,BQ03B");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(1, result.getData().size());
        assertTrue(result.getData().containsKey("BQ03B"));
        assertEquals(3, http.calls());
    }

    // ── batch: reason order NO_TOKEN > HTTP_ERROR > NETWORK_ERROR > PARSE_ERROR > NOT_FOUND ──

    @Test
    void batch_order_429ThenNoToken401_isNoTokenWithDetailOfThatTicker() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(429, "", "30")
                .enqueue(401, ERROR_TRUE)
                .install("t-ok");

        assertFailure(batch("BO01A,BO01B"), FailureReason.NO_TOKEN, "401");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_order_503ThenHttp500_isHttpErrorWithDetailOfTheFirstTicker() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(503, "")
                .enqueue(500, "")
                .install("t-ok");

        assertFailure(batch("BO02A,BO02B"), FailureReason.HTTP_ERROR, "503");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_order_timeoutThenNotJson_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(new SocketTimeoutException("timeout"))
                .enqueue(200, NOT_JSON)
                .install("t-ok");

        assertFailure(batch("BO03A,BO03B"), FailureReason.NETWORK_ERROR, "timeout");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_order_notJsonThenTimeout_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(200, NOT_JSON)
                .enqueue(new SocketTimeoutException("timeout"))
                .install("t-ok");

        assertFailure(batch("BO04A,BO04B"), FailureReason.NETWORK_ERROR, "timeout");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_order_notFoundThenHttp429_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(429, "", "30")
                .install("t-ok");

        assertFailure(batch("BO05A,BO05B"), FailureReason.HTTP_ERROR, "429");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_order_notFoundThenNotJson_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(200, NOT_JSON)
                .install("t-ok");

        assertFailure(batch("BO06A,BO06B"), FailureReason.PARSE_ERROR, null);
        assertEquals(3, http.calls());
    }

    @Test
    void batch_order_httpThenHttpOfAnotherCode_detailIsTheFirstTickersCode() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(503, "")
                .enqueue(500, "")
                .install("t-ok");

        assertFailure(batch("BO07A,BO07B,BO07C"), FailureReason.HTTP_ERROR, "503");
        assertEquals(4, http.calls());
    }

    @Test
    void batch_order_allNotFound_detailIsTheFirstTickersDetail() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(200, EMPTY_RESULTS)
                .enqueue(404, NOT_FOUND_BODY)
                .install("t-ok");

        assertFailure(batch("BO08A,BO08B"), FailureReason.NOT_FOUND, "Ação não encontrada");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_fallbackSkipsEmptyTickersAndKeepsListOrder() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(503, "")
                .enqueue(401, ERROR_TRUE)
                .install("t-ok");

        assertFailure(batch("BO09A,, BO09B"), FailureReason.NO_TOKEN, "401");
        assertEquals(3, http.calls(), "one batch call plus one per non-empty ticker");
        assertTrue(http.url(1).contains("/quote/BO09A"), http.url(1));
        assertTrue(http.url(2).contains("/quote/BO09B"), http.url(2));
    }

    @Test
    void batch_batchReasonOnlyCountsWhenTheFallbackTriedNoTicker() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install("t-ok");

        assertFailure(batch(","), FailureReason.HTTP_ERROR, "429");
        assertEquals(1, http.calls(), "nothing to try one by one");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  An unchecked exception while executing the call is a network failure too
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void quote_executeThrowsIllegalStateWithToken_isNetworkErrorWithTheMessage() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new IllegalStateException("boom")).install("t-ok");

        assertFailure(quote("QR01"), FailureReason.NETWORK_ERROR, "boom");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_executeThrowsIllegalStateWithoutToken_isNetworkErrorWithTheMessage() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new IllegalStateException("boom")).install(null);

        assertFailure(quote("QR02"), FailureReason.NETWORK_ERROR, "boom");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_executeThrowsRuntimeWithoutMessage_isNetworkErrorWithTheClassSimpleName() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new UnsupportedOperationException()).install("t-ok");

        assertFailure(quote("QR03"), FailureReason.NETWORK_ERROR, "UnsupportedOperationException");
        assertEquals(1, http.calls());
    }

    @Test
    void batch_batchAndEveryFallbackTickerThrowIllegalState_isNetworkErrorOfTheFirstTicker() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new IllegalStateException("boom-batch"))
                .enqueue(new IllegalStateException("boom-A"))
                .enqueue(new IllegalStateException("boom-B"))
                .install("t-ok");

        assertFailure(batch("BR01A,BR01B"), FailureReason.NETWORK_ERROR, "boom-A");
        assertEquals(3, http.calls());
    }

    @Test
    void batch_batchThrowsIllegalStateAndFallbackSucceeds_isSuccessWithTheTickers() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new IllegalStateException("boom-batch"))
                .enqueue(200, good("BR02A"))
                .enqueue(200, good("BR02B"))
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BR02A,BR02B");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().containsKey("BR02A"));
        assertTrue(result.getData().containsKey("BR02B"));
        assertEquals(3, http.calls());
    }

    @Test
    void batch_singleTickerBatchThrowsWithoutMessage_isNetworkErrorWithTheClassSimpleName() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(new UnsupportedOperationException())
                .enqueue(new UnsupportedOperationException())
                .install(null);

        assertFailure(batch("BR03"), FailureReason.NETWORK_ERROR, "UnsupportedOperationException");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_order_fallbackOfAThrowsIllegalStateAndBGives500_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, QUOTES_EXCEEDED_BODY)
                .enqueue(new IllegalStateException("boom-A"))
                .enqueue(500, "")
                .install("t-ok");

        assertFailure(batch("BR04A,BR04B"), FailureReason.HTTP_ERROR, "500");
        assertEquals(3, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  classify(code, hasToken): the token never changes the answer
    // ══════════════════════════════════════════════════════════════════════

    private static void assertClassified(int code, FailureReason expected) {
        assertEquals(expected, BrapiClient.classify(code, true), "HTTP " + code + " with token");
        assertEquals(expected, BrapiClient.classify(code, false), "HTTP " + code + " without token");
    }

    @Test
    void classify_400_isHttpError() {
        assertClassified(400, FailureReason.HTTP_ERROR);
    }

    @Test
    void classify_401_isNoToken() {
        assertClassified(401, FailureReason.NO_TOKEN);
    }

    @Test
    void classify_403_isNoToken() {
        assertClassified(403, FailureReason.NO_TOKEN);
    }

    @Test
    void classify_404_isNotFound() {
        assertClassified(404, FailureReason.NOT_FOUND);
    }

    @Test
    void classify_429_isHttpError() {
        assertClassified(429, FailureReason.HTTP_ERROR);
    }

    @Test
    void classify_500_isHttpError() {
        assertClassified(500, FailureReason.HTTP_ERROR);
    }

    @Test
    void classify_503_isHttpError() {
        assertClassified(503, FailureReason.HTTP_ERROR);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Reading the body: a RuntimeException is PARSE_ERROR, an IOException is NETWORK_ERROR
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void quote_readThrowsIllegalStateWithToken_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .install("t-ok");

        assertFailure(quote("QB01"), FailureReason.PARSE_ERROR, "synthetic read failure");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_readThrowsIllegalStateWithoutToken_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .install(null);

        assertFailure(quote("QB02"), FailureReason.PARSE_ERROR, "synthetic read failure");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_readThrowsNullPointer_isParseErrorWithTheClassSimpleName() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueueReadFailure(new NullPointerException()).install("t-ok");

        assertFailure(quote("QB03"), FailureReason.PARSE_ERROR, "NullPointerException");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_readThrowsUncheckedIo_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new UncheckedIOException(new IOException("wrapped")))
                .install("t-ok");

        assertFailure(quote("QB04"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_readThrowsIoException_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueueReadFailure(new IOException("read reset")).install("t-ok");

        assertFailure(quote("QB05"), FailureReason.NETWORK_ERROR, "read reset");
        assertEquals(1, http.calls());
    }

    @Test
    void batch_batchAndFallbackReadsThrowIllegalState_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .install("t-ok");

        assertFailure(batch("BB01"), FailureReason.PARSE_ERROR, "synthetic read failure");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_batchReadThrowsIllegalStateAndFallbackSucceeds_isSuccessWithTheTickers() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .enqueue(200, good("BB02A"))
                .enqueue(200, good("BB02B"))
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BB02A,BB02B");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().containsKey("BB02A"));
        assertTrue(result.getData().containsKey("BB02B"));
        assertEquals(3, http.calls());
    }

    @Test
    void batch_batchAndFallbackReadsThrowIoException_isNetworkError() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IOException("read reset"))
                .enqueueReadFailure(new IOException("read reset"))
                .install("t-ok");

        assertFailure(batch("BB03"), FailureReason.NETWORK_ERROR, "read reset");
        assertEquals(2, http.calls());
    }

    // The batch's own reason counts only when the fallback tries no ticker: "tickers" is not blank,
    // but every piece of split(",") is. One call only (the batch).

    @Test
    void batch_separatorsOnly_readThrowsIllegalState_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .install("t-ok");

        assertFailure(batch(","), FailureReason.PARSE_ERROR, "synthetic read failure");
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsWithSpaces_readThrowsNullPointer_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueueReadFailure(new NullPointerException()).install("t-ok");

        assertFailure(batch(" , "), FailureReason.PARSE_ERROR, "NullPointerException");
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_readThrowsIoException_isNetworkErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueueReadFailure(new IOException("read reset")).install("t-ok");

        assertFailure(batch(","), FailureReason.NETWORK_ERROR, "read reset");
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_executeThrowsIllegalState_isNetworkErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new IllegalStateException("boom")).install("t-ok");

        assertFailure(batch(","), FailureReason.NETWORK_ERROR, "boom");
        assertEquals(1, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  The "error" field: valid only as a JSON primitive ("no data" = NOT_FOUND);
    //  an object, null or array is a wrong shape = PARSE_ERROR
    // ══════════════════════════════════════════════════════════════════════

    // ── single quote ──

    @Test
    void quote_200ErrorIsAnArrayWithAString_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":[\"x\"]}").install("t-ok");

        assertFailure(quote("QE01"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsAnArrayWithANumber_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":[1]}").install("t-ok");

        assertFailure(quote("QE02"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsAnArrayWithABoolean_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":[true]}").install("t-ok");

        assertFailure(quote("QE03"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsAnEmptyObject_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":{}}").install("t-ok");

        assertFailure(quote("QE04"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsAnEmptyArray_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":[]}").install("t-ok");

        assertFailure(quote("QE05"), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsBooleanTrueWithMessage_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ERROR_TRUE).install("t-ok");

        assertFailure(quote("QE06"), FailureReason.NOT_FOUND, "true");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsAString_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":\"Ativo sem dados\"}").install("t-ok");

        assertFailure(quote("QE07"), FailureReason.NOT_FOUND, "Ativo sem dados");
        assertEquals(1, http.calls());
    }

    @Test
    void quote_200ErrorIsANumber_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":5}").install("t-ok");

        assertFailure(quote("QE08"), FailureReason.NOT_FOUND, "5");
        assertEquals(1, http.calls());
    }

    // ── batch with no fallback ticker (only separators): the batch's own reason shows ──

    @Test
    void batch_separatorsOnly_errorIsAnEmptyObject_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":{}}").install("t-ok");

        assertFailure(batch(","), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsAnObjectWithFields_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":{\"code\":\"X\"}}").install("t-ok");

        assertFailure(batch(","), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsNull_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":null}").install("t-ok");

        assertFailure(batch(","), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsAnEmptyArray_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":[]}").install("t-ok");

        assertFailure(batch(","), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsAnArrayWithAString_isParseErrorOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":[\"x\"]}").install("t-ok");

        assertFailure(batch(","), FailureReason.PARSE_ERROR, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsBooleanTrueWithMessage_isNotFoundOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ERROR_TRUE).install("t-ok");

        assertFailure(batch(","), FailureReason.NOT_FOUND, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsAString_isNotFoundOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":\"Ativo sem dados\"}").install("t-ok");

        assertFailure(batch(","), FailureReason.NOT_FOUND, null);
        assertEquals(1, http.calls());
    }

    @Test
    void batch_separatorsOnly_errorIsANumber_isNotFoundOfTheBatch() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":5}").install("t-ok");

        assertFailure(batch(","), FailureReason.NOT_FOUND, null);
        assertEquals(1, http.calls());
    }

    // ── batch with a fallback: the D5 priority of the fallback is untouched ──

    @Test
    void batch_errorIsAnObjectInTheBatch_fallback404_isNotFoundWith404() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(200, "{\"error\":{}}")
                .enqueue(404, NOT_FOUND_BODY)
                .install("t-ok");

        assertFailure(batch("BE01"), FailureReason.NOT_FOUND, "404");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_errorIsAnArrayWithAStringInTheBatch_fallbackSucceeds_isSuccessWithTheTicker() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(200, "{\"error\":[\"x\"]}")
                .enqueue(200, good("BE02"))
                .install("t-ok");

        FetchResult<Map<String, StockData>> result = batch("BE02");

        assertInstanceOf(FetchResult.Success.class, result);
        assertEquals(1, result.getData().size());
        assertTrue(result.getData().containsKey("BE02"));
        assertEquals(2, http.calls());
    }

    @Test
    void batch_errorIsNullInTheBatch_fallback401_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(200, "{\"error\":null}")
                .enqueue(401, ERROR_TRUE)
                .install("t-ok");

        assertFailure(batch("BE03"), FailureReason.NO_TOKEN, "401");
        assertEquals(2, http.calls());
    }

    @Test
    void batch_onlyTheBatchReadThrowsIllegalState_fallbackReasonDecides() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueueReadFailure(new IllegalStateException("synthetic read failure"))
                .enqueue(429, "", "30")
                .install("t-ok");

        assertFailure(batch("BB04"), FailureReason.HTTP_ERROR, "429");
        assertEquals(2, http.calls());
    }
}

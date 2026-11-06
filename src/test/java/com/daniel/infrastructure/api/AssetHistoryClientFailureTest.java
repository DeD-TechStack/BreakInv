package com.daniel.infrastructure.api;

import com.daniel.infrastructure.api.AssetHistoryClient.HistoryFetchException;
import com.daniel.infrastructure.api.AssetHistoryClient.HistoryResult;
import com.daniel.infrastructure.api.AssetHistoryClient.Period;
import com.daniel.infrastructure.api.BrapiClient.FailureReason;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a history fetch failed, and when the range cascade of {@code fetchHistory} must stop,
 * without network. Requests use {@link Period#ONE_YEAR}: first range {@code 1y}, then at most
 * {@code 3mo, 1mo, 5d, 1d} (5 calls when every answer is "no data").
 */
class AssetHistoryClientFailureTest {

    private static final String AUTH_MESSAGE =
            "Token inválido ou sem permissão para este período. Verifique o token em Configurações.";
    private static final String NO_DATA_MESSAGE = "Sem dados históricos para este período";

    private static final String NOT_FOUND_BODY =
            "{\"error\":true,\"message\":\"Recurso não encontrado\",\"code\":\"NOT_FOUND\"}";
    private static final String ERROR_TRUE = "{\"error\":true,\"message\":\"x\"}";
    private static final String EMPTY_RESULTS = "{\"results\":[]}";
    private static final String NOT_JSON = "not json";
    private static final String RESULTS_IS_STRING = "{\"results\":\"x\"}";
    private static final String ERROR_OBJECT = "{\"error\":{\"code\":\"X\"}}";
    private static final String ERROR_NULL = "{\"error\":null}";
    private static final String ERROR_ARRAY = "{\"error\":[]}";
    private static final String ALL_POINTS_DISCARDED =
            "{\"results\":[{\"historicalDataPrice\":[{\"date\":1700000000,\"close\":0}]}]}";
    private static final String GOOD_POINTS =
            "{\"results\":[{\"symbol\":\"X\",\"historicalDataPrice\":["
                    + "{\"date\":1700000000,\"close\":10.5},{\"date\":1700086400,\"close\":11.0}]}]}";
    private static final String RANGE_NOT_AVAILABLE =
            "{\"error\":true,\"message\":\"O range \\\"1y\\\" não está disponível no seu plano. "
                    + "Ranges permitidos: 1d, 5d, 1mo, 3mo\"}";
    private static final String BAD_PARAMETER = "{\"error\":true,\"message\":\"Parâmetro inválido\"}";

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

    private static IOException failure(String ticker) {
        return assertThrows(IOException.class,
                () -> AssetHistoryClient.fetchHistory(ticker, Period.ONE_YEAR),
                "fetchHistory must fail with an IOException");
    }

    private static HistoryResult success(String ticker) {
        return assertDoesNotThrow(() -> {
            return AssetHistoryClient.fetchHistory(ticker, Period.ONE_YEAR);
        }, "fetchHistory must succeed");
    }

    private static void assertRange(FakeBrapiHttp http, int call, String range) {
        assertTrue(http.url(call).contains("range=" + range + "&"), "call " + call + " " + http.url(call));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Token refused: stops at once (as today)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_noTokenWith401_stopsWithTheTokenMessage() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(401, ERROR_TRUE).install(null);

        IOException ex = failure("HF01");

        assertEquals(AUTH_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
        assertTrue(!http.url(0).contains("token="), "no token configured: nothing to send");
    }

    @Test
    void history_rejectedTokenWith401_stopsWithTheTokenMessage() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(401, ERROR_TRUE).install("t-ruim");

        IOException ex = failure("HF02");

        assertEquals(AUTH_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
        assertTrue(http.url(0).contains("token=t-ruim"), http.url(0));
    }

    @Test
    void history_tokenWith403_stopsWithTheTokenMessage() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(403, ERROR_TRUE).install("t-ok");

        IOException ex = failure("HF03");

        assertEquals(AUTH_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  No answer: stops after the first call, message of the original exception
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_unknownHostWithoutToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new UnknownHostException("brapi.dev")).install(null);

        IOException ex = failure("HF04");

        assertEquals("brapi.dev", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_unknownHostWithToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new UnknownHostException("brapi.dev")).install("t-ok");

        IOException ex = failure("HF05");

        assertEquals("brapi.dev", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_socketTimeout_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new SocketTimeoutException("timeout")).install("t-ok");

        IOException ex = failure("HF06");

        assertEquals("timeout", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_connectException_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new ConnectException("Connection refused")).install("t-ok");

        IOException ex = failure("HF07");

        assertEquals("Connection refused", ex.getMessage());
        assertEquals(1, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Server said no (429, 5xx): stops after the first call
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_429WithRetryAfterAndToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install("t-ok");

        IOException ex = failure("HF08");

        assertEquals("Dados não disponíveis: HTTP 429", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_429WithRetryAfterWithoutToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install(null);

        IOException ex = failure("HF09");

        assertEquals("Dados não disponíveis: HTTP 429", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_500WithToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(500, "").install("t-ok");

        IOException ex = failure("HF10");

        assertEquals("Dados não disponíveis: HTTP 500", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_500WithoutToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(500, "").install(null);

        IOException ex = failure("HF11");

        assertEquals("Dados não disponíveis: HTTP 500", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_503WithToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(503, "").install("t-ok");

        IOException ex = failure("HF12");

        assertEquals("Dados não disponíveis: HTTP 503", ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_503WithoutToken_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(503, "").install(null);

        IOException ex = failure("HF13");

        assertEquals("Dados não disponíveis: HTTP 503", ex.getMessage());
        assertEquals(1, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Unreadable answer: stops after the first call, same message as today
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_200NotJson_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, NOT_JSON).install("t-ok");

        IOException ex = failure("HF14");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_200ResultsIsNotAnArray_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, RESULTS_IS_STRING).install("t-ok");

        IOException ex = failure("HF15");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_200EmptyBody_stopsAfterOneCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "").install("t-ok");

        IOException ex = failure("HF16");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  No data: the cascade goes on to the end and throws the last failure (as today)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_200EmptyResults_cascadesToTheEnd() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, EMPTY_RESULTS);

        IOException ex = failure("HF17");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(5, http.calls());
    }

    @Test
    void history_200ErrorTrue_cascadesToTheEnd() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_TRUE);

        IOException ex = failure("HF18");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(5, http.calls());
    }

    // An "error" field is valid only as a JSON primitive (string, boolean, number): that is "no data".
    // An object, null or array there is a wrong shape: PARSE_ERROR, no cascade.

    @Test
    void history_200ErrorIsAnObject_stopsAsParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_OBJECT);

        IOException ex = failure("HF19");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_200ErrorIsNull_stopsAsParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_NULL);

        IOException ex = failure("HF20");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_200ErrorIsAnEmptyObject_stopsAsParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, "{\"error\":{}}");

        IOException ex = failure("HF20A");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_200ErrorIsAnArray_stopsAsParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_ARRAY);

        IOException ex = failure("HF20B");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(1, http.calls());
    }

    @Test
    void history_200ErrorIsBooleanTrue_cascadesToTheEnd() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, "{\"error\":true}");

        IOException ex = failure("HF20C");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(5, http.calls());
    }

    @Test
    void history_200ErrorIsAString_cascadesToTheEnd() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, "{\"error\":\"Ativo sem dados\"}");

        IOException ex = failure("HF20D");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(5, http.calls());
    }

    @Test
    void history_200AllPointsDiscarded_cascadesToTheEnd() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ALL_POINTS_DISCARDED);

        IOException ex = failure("HF21");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(5, http.calls());
    }

    @Test
    void history_404WithErrorBody_cascadesToTheEnd() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(404, NOT_FOUND_BODY);

        IOException ex = failure("HF22");

        assertEquals("Dados não disponíveis: Recurso não encontrado", ex.getMessage());
        assertEquals(5, http.calls());
    }

    @Test
    void history_cascadeOfNotFound_runsTheRangesInOrderAndThrowsTheLastFailure() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(404, "{\"message\":\"m1\"}")
                .enqueue(404, "{\"message\":\"m2\"}")
                .enqueue(404, "{\"message\":\"m3\"}")
                .enqueue(404, "{\"message\":\"m4\"}")
                .enqueue(404, "{\"message\":\"m5\"}")
                .install("t-ok");

        IOException ex = failure("HF23");

        assertEquals("Dados não disponíveis: m5", ex.getMessage());
        assertEquals(5, http.calls());
        assertRange(http, 0, "1y");
        assertRange(http, 1, "3mo");
        assertRange(http, 2, "1mo");
        assertRange(http, 3, "5d");
        assertRange(http, 4, "1d");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Cascade with the permitted ranges of the plan
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_400WithPermittedRanges_fallsBackToTheLargestPermittedRange() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, RANGE_NOT_AVAILABLE)
                .enqueue(200, GOOD_POINTS)
                .install("t-ok");

        HistoryResult result = success("HF24");

        assertEquals("3mo", result.effectiveRange());
        assertEquals(Set.of("1d", "5d", "1mo", "3mo"), result.permittedRanges());
        assertTrue(result.usedFallback(Period.ONE_YEAR));
        assertEquals(2, result.points().size());
        assertEquals(2, http.calls());
        assertRange(http, 0, "1y");
        assertRange(http, 1, "3mo");
    }

    @Test
    void history_400WithoutPermittedRanges_triesTheSmallerRangesOneByOne() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, BAD_PARAMETER)
                .enqueue(200, GOOD_POINTS)
                .install("t-ok");

        HistoryResult result = success("HF25");

        assertEquals("3mo", result.effectiveRange());
        assertTrue(result.permittedRanges().isEmpty());
        assertEquals(2, http.calls());
        assertRange(http, 1, "3mo");
    }

    @Test
    void history_400WithoutPermittedRanges_thenEverythingNotFound_throwsTheLastFailure() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(400, "{\"message\":\"first\"}")
                .enqueue(404, "{\"message\":\"second\"}")
                .enqueue(404, "{\"message\":\"third\"}")
                .enqueue(404, "{\"message\":\"fourth\"}")
                .enqueue(404, "{\"message\":\"last\"}")
                .install("t-ok");

        IOException ex = failure("HF26");

        assertEquals("Dados não disponíveis: last", ex.getMessage());
        assertEquals(5, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  The cascade stops on the first failure that is not "no data"
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_cascade404Then429_stopsAtTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(429, "", "30")
                .install("t-ok");

        IOException ex = failure("HF27");

        assertEquals("Dados não disponíveis: HTTP 429", ex.getMessage());
        assertEquals(2, http.calls());
    }

    @Test
    void history_cascadeNoDataThen503_stopsAtTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(200, EMPTY_RESULTS)
                .enqueue(503, "")
                .install("t-ok");

        IOException ex = failure("HF28");

        assertEquals("Dados não disponíveis: HTTP 503", ex.getMessage());
        assertEquals(2, http.calls());
    }

    @Test
    void history_cascade404ThenTimeout_stopsAtTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(new SocketTimeoutException("timeout"))
                .install("t-ok");

        IOException ex = failure("HF29");

        assertEquals("timeout", ex.getMessage());
        assertEquals(2, http.calls());
    }

    @Test
    void history_cascade404ThenNotJson_stopsAtTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(200, NOT_JSON)
                .install("t-ok");

        IOException ex = failure("HF30");

        assertEquals(NO_DATA_MESSAGE, ex.getMessage());
        assertEquals(2, http.calls());
    }

    @Test
    void history_cascade404ThenTokenRefused_stopsAtTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(404, NOT_FOUND_BODY)
                .enqueue(401, ERROR_TRUE)
                .install("t-ok");

        IOException ex = failure("HF31");

        assertEquals(AUTH_MESSAGE, ex.getMessage());
        assertEquals(2, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Success and inputs
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void history_validResponse_isSuccessWithTheRequestedRangeAndTheTokenInTheQuery() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, GOOD_POINTS).install("t-ok");

        HistoryResult result = success("HF32");

        assertEquals("1y", result.effectiveRange());
        assertEquals(2, result.points().size());
        assertTrue(result.permittedRanges().isEmpty());
        assertEquals(1, http.calls());
        assertTrue(http.url(0).contains("/quote/HF32?"), http.url(0));
        assertTrue(http.url(0).contains("range=1y"), http.url(0));
        assertTrue(http.url(0).contains("interval=1d"), http.url(0));
        assertTrue(http.url(0).contains("token=t-ok"), http.url(0));
    }

    @Test
    void history_nullAndBlankTicker_returnEmptyResultWithoutCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");

        assertTrue(success(null).points().isEmpty());
        assertTrue(success("  ").points().isEmpty());
        assertEquals(0, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  fetchHistoryByDateRange keeps answering an empty list
    // ══════════════════════════════════════════════════════════════════════

    private static List<AssetHistoryClient.HistoryPoint> byDateRange(String ticker) {
        return assertDoesNotThrow(() -> {
            return AssetHistoryClient.fetchHistoryByDateRange(
                    ticker, LocalDate.of(2023, 11, 10), LocalDate.of(2023, 11, 20));
        }, "fetchHistoryByDateRange must never throw");
    }

    @Test
    void byDateRange_429_returnsAnEmptyList() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install("t-ok");

        assertTrue(byDateRange("HD01").isEmpty());
        assertEquals(1, http.calls());
    }

    @Test
    void byDateRange_notJson_returnsAnEmptyList() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, NOT_JSON).install("t-ok");

        assertTrue(byDateRange("HD02").isEmpty());
        assertEquals(1, http.calls());
    }

    @Test
    void byDateRange_resultsIsNotAnArray_returnsAnEmptyList() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, RESULTS_IS_STRING).install("t-ok");

        assertTrue(byDateRange("HD03").isEmpty());
        assertEquals(1, http.calls());
    }

    @Test
    void byDateRange_networkFailure_returnsAnEmptyList() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(new UnknownHostException("brapi.dev")).install("t-ok");

        assertTrue(byDateRange("HD04").isEmpty());
        assertEquals(1, http.calls());
    }

    @Test
    void byDateRange_validResponse_returnsThePointsInsideTheInterval() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, GOOD_POINTS).install("t-ok");

        assertEquals(2, byDateRange("HD05").size());
        assertEquals(1, http.calls());
        assertRange(http, 0, "1mo");
    }

    // ── fetchHistoryByDateRange is lenient, exactly as before BRK-25: it keeps the points read ──
    // ── before a malformed item (the moving average uses it). 01/11/2026 .. 05/11/2026 → "5d". ──

    private static final String VALID_FIRST = "{\"date\":\"2026-11-01\",\"close\":10.5}";

    private static String historyWith(String... items) {
        return "{\"results\":[{\"historicalDataPrice\":[" + String.join(",", items) + "]}]}";
    }

    private static List<AssetHistoryClient.HistoryPoint> byNovemberRange(String ticker) {
        return assertDoesNotThrow(() -> {
            return AssetHistoryClient.fetchHistoryByDateRange(
                    ticker, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5));
        }, "fetchHistoryByDateRange must never throw");
    }

    private static void assertKeepsTheValidPrefix(String ticker, String malformedItem) {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, historyWith(VALID_FIRST, malformedItem)).install("t-ok");

        List<AssetHistoryClient.HistoryPoint> points = byNovemberRange(ticker);

        assertEquals(1, points.size(), "the valid point read before the malformed item must be kept");
        assertEquals(10.5, points.get(0).close());
        assertEquals(1, http.calls());
        assertRange(http, 0, "5d");
    }

    @Test
    void byDateRange_validPrefixThenNullItem_keepsTheValidPoint() {
        assertKeepsTheValidPrefix("HL01", "null");
    }

    @Test
    void byDateRange_validPrefixThenNumberItem_keepsTheValidPoint() {
        assertKeepsTheValidPrefix("HL02", "5");
    }

    @Test
    void byDateRange_validPrefixThenStringItem_keepsTheValidPoint() {
        assertKeepsTheValidPrefix("HL03", "\"x\"");
    }

    @Test
    void byDateRange_validPrefixThenArrayItem_keepsTheValidPoint() {
        assertKeepsTheValidPrefix("HL04", "[]");
    }

    @Test
    void byDateRange_zeroCloseInTheMiddle_keepsBothValidPoints() {
        FakeBrapiHttp http = new FakeBrapiHttp()
                .enqueue(200, historyWith(VALID_FIRST,
                        "{\"date\":\"2026-11-02\",\"close\":0}",
                        "{\"date\":\"2026-11-03\",\"close\":11.0}"))
                .install("t-ok");

        List<AssetHistoryClient.HistoryPoint> points = byNovemberRange("HL05");

        assertEquals(2, points.size());
        assertEquals(10.5, points.get(0).close());
        assertEquals(11.0, points.get(1).close());
        assertEquals(1, http.calls());
    }

    @Test
    void byDateRange_errorIsAnEmptyObject_returnsAnEmptyListWithoutThrowing() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "{\"error\":{}}").install("t-ok");

        assertTrue(byNovemberRange("HL06").isEmpty());
        assertEquals(1, http.calls());
    }

    @Test
    void byDateRange_errorIsNull_returnsAnEmptyListWithoutThrowing() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, ERROR_NULL).install("t-ok");

        assertTrue(byNovemberRange("HL07").isEmpty());
        assertEquals(1, http.calls());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  The reason travels with the exception (HistoryFetchException.reason())
    // ══════════════════════════════════════════════════════════════════════

    /** The failure must be a HistoryFetchException with this reason and no cause (only network failures carry one). */
    private static HistoryFetchException assertReason(IOException ex, FailureReason reason) {
        HistoryFetchException typed = assertInstanceOf(HistoryFetchException.class, ex,
                "the failure must say why it happened: " + ex);
        assertEquals(reason, typed.reason());
        return typed;
    }

    private static void assertReasonWithoutCause(IOException ex, FailureReason reason, String message) {
        HistoryFetchException typed = assertReason(ex, reason);
        assertEquals(message, typed.getMessage());
        assertNull(typed.getCause(), "only network failures carry a cause: the screen shows the root cause's text");
    }

    private static void assertNetworkReason(IOException ex, IOException original) {
        HistoryFetchException typed = assertReason(ex, FailureReason.NETWORK_ERROR);
        assertEquals(original.getMessage(), typed.getMessage());
        assertSame(original, typed.getCause());
    }

    @Test
    void historyFetchException_carriesReasonMessageAndCause() {
        IOException root = new IOException("root");

        HistoryFetchException withoutCause = new HistoryFetchException(FailureReason.NOT_FOUND, "m");
        HistoryFetchException withCause = new HistoryFetchException(FailureReason.NETWORK_ERROR, "n", root);

        assertEquals(FailureReason.NOT_FOUND, withoutCause.reason());
        assertEquals("m", withoutCause.getMessage());
        assertNull(withoutCause.getCause());
        assertEquals(FailureReason.NETWORK_ERROR, withCause.reason());
        assertEquals("n", withCause.getMessage());
        assertSame(root, withCause.getCause());
        assertInstanceOf(IOException.class, withoutCause);
    }

    @Test
    void reason_noTokenWith401_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(401, ERROR_TRUE).install(null);

        assertReasonWithoutCause(failure("HR01"), FailureReason.NO_TOKEN, AUTH_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_rejectedTokenWith401_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(401, ERROR_TRUE).install("t-ruim");

        assertReasonWithoutCause(failure("HR02"), FailureReason.NO_TOKEN, AUTH_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_tokenWith403_isNoToken() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(403, ERROR_TRUE).install("t-ok");

        assertReasonWithoutCause(failure("HR03"), FailureReason.NO_TOKEN, AUTH_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_unknownHostWithoutToken_isNetworkErrorWithTheOriginalAsCause() {
        UnknownHostException original = new UnknownHostException("brapi.dev");
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(original).install(null);

        assertNetworkReason(failure("HR04"), original);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_unknownHostWithToken_isNetworkErrorWithTheOriginalAsCause() {
        UnknownHostException original = new UnknownHostException("brapi.dev");
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(original).install("t-ok");

        assertNetworkReason(failure("HR05"), original);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_socketTimeout_isNetworkErrorWithTheOriginalAsCause() {
        SocketTimeoutException original = new SocketTimeoutException("timeout");
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(original).install("t-ok");

        assertNetworkReason(failure("HR06"), original);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_connectException_isNetworkErrorWithTheOriginalAsCause() {
        ConnectException original = new ConnectException("Connection refused");
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(original).install("t-ok");

        assertNetworkReason(failure("HR07"), original);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_429WithRetryAfterAndToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install("t-ok");

        assertReasonWithoutCause(failure("HR08"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 429");
        assertEquals(1, http.calls());
    }

    @Test
    void reason_429WithRetryAfterWithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(429, "", "30").install(null);

        assertReasonWithoutCause(failure("HR09"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 429");
        assertEquals(1, http.calls());
    }

    @Test
    void reason_500WithToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(500, "").install("t-ok");

        assertReasonWithoutCause(failure("HR10"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 500");
        assertEquals(1, http.calls());
    }

    @Test
    void reason_500WithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(500, "").install(null);

        assertReasonWithoutCause(failure("HR11"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 500");
        assertEquals(1, http.calls());
    }

    @Test
    void reason_503WithToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(503, "").install("t-ok");

        assertReasonWithoutCause(failure("HR12"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 503");
        assertEquals(1, http.calls());
    }

    @Test
    void reason_503WithoutToken_isHttpError() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(503, "").install(null);

        assertReasonWithoutCause(failure("HR13"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 503");
        assertEquals(1, http.calls());
    }

    @Test
    void reason_404WithErrorBody_isNotFoundAtTheEndOfTheCascade() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(404, NOT_FOUND_BODY);

        assertReasonWithoutCause(failure("HR14"), FailureReason.NOT_FOUND,
                "Dados não disponíveis: Recurso não encontrado");
        assertEquals(5, http.calls());
    }

    @Test
    void reason_400WithoutPermittedRanges_isNotFoundAtTheEndOfTheCascade() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(400, "{\"message\":\"first\"}").install("t-ok");
        for (int i = 0; i < 4; i++) http.enqueue(400, "{\"message\":\"last\"}");

        assertReasonWithoutCause(failure("HR15"), FailureReason.NOT_FOUND, "Dados não disponíveis: last");
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200EmptyResults_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, EMPTY_RESULTS);

        assertReasonWithoutCause(failure("HR16"), FailureReason.NOT_FOUND, NO_DATA_MESSAGE);
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200ErrorTrue_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_TRUE);

        assertReasonWithoutCause(failure("HR17"), FailureReason.NOT_FOUND, NO_DATA_MESSAGE);
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200ErrorIsAnObject_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_OBJECT);

        assertReasonWithoutCause(failure("HR18"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_200ErrorIsNull_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_NULL);

        assertReasonWithoutCause(failure("HR19"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_200ErrorIsAnArray_isParseError() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ERROR_ARRAY);

        assertReasonWithoutCause(failure("HR19A"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_200ErrorIsBooleanTrue_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, "{\"error\":true}");

        assertReasonWithoutCause(failure("HR19B"), FailureReason.NOT_FOUND, NO_DATA_MESSAGE);
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200ErrorIsAString_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, "{\"error\":\"Ativo sem dados\"}");

        assertReasonWithoutCause(failure("HR19C"), FailureReason.NOT_FOUND, NO_DATA_MESSAGE);
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200ErrorIsANumber_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, "{\"error\":404}");

        assertReasonWithoutCause(failure("HR19D"), FailureReason.NOT_FOUND, NO_DATA_MESSAGE);
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200AllPointsDiscarded_isNotFound() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, ALL_POINTS_DISCARDED);

        assertReasonWithoutCause(failure("HR20"), FailureReason.NOT_FOUND, NO_DATA_MESSAGE);
        assertEquals(5, http.calls());
    }

    @Test
    void reason_200NotJson_isParseErrorWithoutCause() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, NOT_JSON).install("t-ok");

        assertReasonWithoutCause(failure("HR21"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_200ResultsIsNotAnArray_isParseErrorWithoutCause() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, RESULTS_IS_STRING).install("t-ok");

        assertReasonWithoutCause(failure("HR22"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_200EmptyBody_isParseErrorWithoutCause() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, "").install("t-ok");

        assertReasonWithoutCause(failure("HR23"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }

    @Test
    void reason_cascade404Then429_isHttpErrorOfTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(404, NOT_FOUND_BODY).enqueue(429, "", "30").install("t-ok");

        assertReasonWithoutCause(failure("HR24"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 429");
        assertEquals(2, http.calls());
    }

    @Test
    void reason_cascadeNoDataThen503_isHttpErrorOfTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(200, EMPTY_RESULTS).enqueue(503, "").install("t-ok");

        assertReasonWithoutCause(failure("HR25"), FailureReason.HTTP_ERROR, "Dados não disponíveis: HTTP 503");
        assertEquals(2, http.calls());
    }

    @Test
    void reason_cascade404ThenTimeout_isNetworkErrorOfTheSecondCall() {
        SocketTimeoutException original = new SocketTimeoutException("timeout");
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(404, NOT_FOUND_BODY).enqueue(original).install("t-ok");

        assertNetworkReason(failure("HR26"), original);
        assertEquals(2, http.calls());
    }

    @Test
    void reason_cascade404ThenNotJson_isParseErrorOfTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(404, NOT_FOUND_BODY).enqueue(200, NOT_JSON).install("t-ok");

        assertReasonWithoutCause(failure("HR27"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(2, http.calls());
    }

    @Test
    void reason_cascade404ThenTokenRefused_isNoTokenOfTheSecondCall() {
        FakeBrapiHttp http = new FakeBrapiHttp().enqueue(404, NOT_FOUND_BODY).enqueue(403, ERROR_TRUE).install("t-ok");

        assertReasonWithoutCause(failure("HR28"), FailureReason.NO_TOKEN, AUTH_MESSAGE);
        assertEquals(2, http.calls());
    }

    @Test
    void reason_strictPath_validPrefixThenNullItem_isParseErrorWithoutCascade() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");
        for (int i = 0; i < 5; i++) http.enqueue(200, historyWith(VALID_FIRST, "null"));

        assertReasonWithoutCause(failure("HR29"), FailureReason.PARSE_ERROR, NO_DATA_MESSAGE);
        assertEquals(1, http.calls());
    }
}

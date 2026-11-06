package com.daniel.infrastructure.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The failure tests swap static state of the production clients. This class proves the swap is
 * undone: client, history client, token source and quote cache all go back to production values,
 * also when the body of a test throws in the middle.
 */
class BrapiStaticStateRestoredTest {

    private static final String GOOD_QUOTE =
            "{\"results\":[{\"symbol\":\"RST1\",\"regularMarketPrice\":12.5}]}";

    @BeforeEach
    void everyTestStartsFromProductionState() {
        // Proves that the previous test (of any class) left nothing behind.
        FakeBrapiHttp.assertProductionState();
    }

    @AfterEach
    void tearDown() {
        FakeBrapiHttp.restore();
    }

    @Test
    void install_swapsAllThreeSeams() {
        FakeBrapiHttp http = new FakeBrapiHttp().install("t-ok");

        assertNotSame(BrapiClient.DEFAULT_CLIENT, BrapiClient.client);
        assertNotSame(AssetHistoryClient.DEFAULT_HTTP, AssetHistoryClient.HTTP);
        assertNotSame(BrapiClient.SETTINGS_TOKEN, BrapiClient.tokenSource);
        assertEquals("t-ok", BrapiClient.tokenSource.get());
        assertEquals(0, http.calls());
    }

    @Test
    void install_nullToken_meansNoTokenConfigured() {
        new FakeBrapiHttp().install(null);

        assertNull(BrapiClient.tokenSource.get());
    }

    @Test
    void restore_putsBackTheProductionValues() {
        new FakeBrapiHttp().install("t-ok");

        FakeBrapiHttp.restore();

        assertSame(BrapiClient.DEFAULT_CLIENT, BrapiClient.client);
        assertSame(AssetHistoryClient.DEFAULT_HTTP, AssetHistoryClient.HTTP);
        assertSame(BrapiClient.SETTINGS_TOKEN, BrapiClient.tokenSource);
        FakeBrapiHttp.assertProductionState();
    }

    @Test
    void restore_whenTheTestBodyThrowsInTheMiddle_stillPutsBackTheProductionValues() {
        IllegalStateException boom = assertThrows(IllegalStateException.class, () -> {
            try {
                new FakeBrapiHttp().enqueue(200, GOOD_QUOTE).install("t-ok");
                BrapiClient.fetchStockDataSafe("RST2");
                throw new IllegalStateException("test body fails after the install");
            } finally {
                FakeBrapiHttp.restore();
            }
        });

        assertEquals("test body fails after the install", boom.getMessage());
        FakeBrapiHttp.assertProductionState();
    }

    @Test
    void restore_emptiesTheQuoteCache_soACachedTickerCallsHttpAgain() {
        FakeBrapiHttp first = new FakeBrapiHttp().enqueue(200, GOOD_QUOTE).install("t-ok");
        BrapiClient.FetchResult<BrapiClient.StockData> cached = BrapiClient.fetchStockDataSafe("RST3");
        assertInstanceOf(BrapiClient.FetchResult.Success.class, cached, "first call fills the cache");
        BrapiClient.fetchStockDataSafe("RST3");
        assertEquals(1, first.calls(), "the second call is served by the cache");

        FakeBrapiHttp.restore();

        // Wire a new fake by hand, without install(), so only restore() could have emptied the cache.
        FakeBrapiHttp second = new FakeBrapiHttp().enqueue(200, GOOD_QUOTE);
        BrapiClient.client = second.client();
        BrapiClient.tokenSource = () -> "t-ok";
        BrapiClient.fetchStockDataSafe("RST3");

        assertEquals(1, second.calls(), "after restore the ticker is no longer cached");
    }
}

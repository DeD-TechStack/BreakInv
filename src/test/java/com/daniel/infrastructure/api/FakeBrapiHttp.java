package com.daniel.infrastructure.api;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * HTTP double for the BRAPI clients: scripted replies (status, JSON body, optional
 * {@code Retry-After}) or scripted {@link IOException}s, consumed in call order.
 *
 * <p>It is the only application interceptor of the test client and never calls
 * {@code chain.proceed}. A network interceptor that throws {@link AssertionError} proves that
 * nothing reaches the network. A call with no scripted step fails with {@link AssertionError}.</p>
 */
final class FakeBrapiHttp implements Interceptor {

    private static final MediaType JSON = MediaType.parse("application/json");

    private record Reply(int code, String body, String retryAfter) {}

    /** The call answers 200, then reading the body throws {@code failure} (an IOException or a RuntimeException). */
    private record ReadFailure(Exception failure) {}

    private static ResponseBody failingBody(Exception failure) {
        Source source = new Source() {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                if (failure instanceof IOException io) throw io;
                throw (RuntimeException) failure;
            }

            @Override
            public Timeout timeout() {
                return Timeout.NONE;
            }

            @Override
            public void close() {
            }
        };
        BufferedSource buffered = Okio.buffer(source);
        return new ResponseBody() {
            @Override
            public MediaType contentType() {
                return JSON;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public BufferedSource source() {
                return buffered;
            }
        };
    }

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<String> urls = new ArrayList<>();
    private final OkHttpClient client;

    FakeBrapiHttp() {
        this.client = new OkHttpClient.Builder()
                .addInterceptor(this)
                .addNetworkInterceptor(chain -> {
                    throw new AssertionError("network reached");
                })
                .build();
    }

    // ── Script ────────────────────────────────────────────────────────────

    FakeBrapiHttp enqueue(int code, String body) {
        script.add(new Reply(code, body, null));
        return this;
    }

    FakeBrapiHttp enqueue(int code, String body, String retryAfter) {
        script.add(new Reply(code, body, retryAfter));
        return this;
    }

    FakeBrapiHttp enqueue(IOException failure) {
        script.add(failure);
        return this;
    }

    /** The call fails with this unchecked exception, as an interceptor or OkHttp itself could. */
    FakeBrapiHttp enqueue(RuntimeException failure) {
        script.add(failure);
        return this;
    }

    /** HTTP 200 whose body fails with this unchecked exception when it is read. */
    FakeBrapiHttp enqueueReadFailure(RuntimeException failure) {
        script.add(new ReadFailure(failure));
        return this;
    }

    /** HTTP 200 whose body fails with this IOException when it is read. */
    FakeBrapiHttp enqueueReadFailure(IOException failure) {
        script.add(new ReadFailure(failure));
        return this;
    }

    /** The test client (fake as only application interceptor). */
    OkHttpClient client() {
        return client;
    }

    // ── Observation ───────────────────────────────────────────────────────

    int calls() {
        synchronized (this) {
            return urls.size();
        }
    }

    String url(int index) {
        synchronized (this) {
            return urls.get(index);
        }
    }

    List<String> urls() {
        synchronized (this) {
            return List.copyOf(urls);
        }
    }

    // ── Installation ──────────────────────────────────────────────────────

    /** Points both clients at this fake; {@code token} null means "no token configured". */
    FakeBrapiHttp install(String token) {
        BrapiClient.client = client;
        AssetHistoryClient.HTTP = client;
        BrapiClient.tokenSource = () -> token;
        BrapiClient.clearStockCache();
        return this;
    }

    /** Puts back the production wiring and empties the quote cache, whatever happens in between. */
    static void restore() {
        try {
            BrapiClient.client = BrapiClient.DEFAULT_CLIENT;
        } finally {
            try {
                AssetHistoryClient.HTTP = AssetHistoryClient.DEFAULT_HTTP;
            } finally {
                try {
                    BrapiClient.tokenSource = BrapiClient.SETTINGS_TOKEN;
                } finally {
                    BrapiClient.clearStockCache();
                }
            }
        }
    }

    static void assertProductionState() {
        assertSame(BrapiClient.DEFAULT_CLIENT, BrapiClient.client, "BrapiClient.client must be DEFAULT_CLIENT");
        assertSame(AssetHistoryClient.DEFAULT_HTTP, AssetHistoryClient.HTTP, "AssetHistoryClient.HTTP must be DEFAULT_HTTP");
        assertSame(BrapiClient.SETTINGS_TOKEN, BrapiClient.tokenSource, "BrapiClient.tokenSource must be SETTINGS_TOKEN");
    }

    // ── Interceptor ───────────────────────────────────────────────────────

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Object step;
        int number;
        synchronized (this) {
            urls.add(request.url().toString());
            number = urls.size();
            step = script.poll();
        }
        if (step == null) {
            throw new AssertionError("unscripted HTTP call #" + number + ": " + request.url());
        }
        if (step instanceof IOException failure) {
            throw failure;
        }
        if (step instanceof RuntimeException failure) {
            throw failure;
        }
        if (step instanceof ReadFailure readFailure) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("scripted 200, unreadable body")
                    .body(failingBody(readFailure.failure()))
                    .build();
        }
        Reply reply = (Reply) step;
        Response.Builder builder = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code())
                .message("scripted " + reply.code())
                .body(ResponseBody.create(reply.body(), JSON));
        if (reply.retryAfter() != null) {
            builder.header("Retry-After", reply.retryAfter());
        }
        return builder.build();
    }
}

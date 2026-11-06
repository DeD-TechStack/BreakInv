package com.daniel.infrastructure.api;

import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import okhttp3.*;
import com.google.gson.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class BrapiClient {

    private static final Logger LOG = Logger.getLogger(BrapiClient.class.getName());
    private static final String BASE_URL = "https://brapi.dev/api";
    private static final String QUOTE_ENDPOINT = "/quote";

    public static final String SETTINGS_KEY_TOKEN = "brapi_token";

    static final OkHttpClient DEFAULT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // Costura de teste: os testes trocam o cliente HTTP.
    static OkHttpClient client = DEFAULT_CLIENT;

    private static final Gson gson = new Gson();
    private static final AppSettingsRepository settingsRepo = new AppSettingsRepository();

    static final Supplier<String> SETTINGS_TOKEN = () -> settingsRepo.get(SETTINGS_KEY_TOKEN).orElse(null);

    // Costura de teste: os testes trocam a origem do token.
    static Supplier<String> tokenSource = SETTINGS_TOKEN;

    static void clearStockCache() {
        stockCache.clear();
    }

    private static String getToken() {
        return tokenSource.get();
    }

    private static String appendToken(String url, String token) {
        if (token == null || token.isBlank()) return url;
        return url + (url.contains("?") ? "&" : "?") + "token=" + token.trim();
    }

    public record StockData(
            String ticker,
            String logoUrl,
            String longName,
            double regularMarketPrice,
            double regularMarketChange,
            double regularMarketChangePercent,
            double regularMarketOpen,
            double regularMarketDayHigh,
            double regularMarketDayLow,
            long regularMarketVolume,
            double fiftyTwoWeekHigh,
            double fiftyTwoWeekLow,
            double twoHundredDayAverage,
            String currency,
            double dividendYield,
            double fiftyTwoWeekChange,
            String error
    ) {
        public boolean hasError() {
            return error != null && !error.isBlank();
        }

        public boolean isValid() {
            return !hasError() && regularMarketPrice > 0;
        }
    }

    // ── Resultado tipado para fetch com causa de falha ──────────────────────

    public enum FailureReason { NO_TOKEN, HTTP_ERROR, PARSE_ERROR, NETWORK_ERROR, NOT_FOUND }

    public sealed interface FetchResult<T> permits FetchResult.Success, FetchResult.Failure {
        record Success<T>(T data) implements FetchResult<T> {}
        record Failure<T>(FailureReason reason, String detail) implements FetchResult<T> {}

        default boolean isSuccess() { return this instanceof Success<?>; }

        @SuppressWarnings("unchecked")
        default T getData() { return ((Success<T>) this).data(); }

        @SuppressWarnings("unchecked")
        default FailureReason getReason() { return ((Failure<T>) this).reason(); }

        @SuppressWarnings("unchecked")
        default String getDetail() { return ((Failure<T>) this).detail(); }
    }

    // Sugestão de ticker
    public record TickerSuggestion(
            String ticker,
            String name,
            String type  // "stock", "fund", "fii"
    ) {}

    // ── Timestamp do último fetch bem-sucedido ──
    private static volatile long lastSuccessfulFetchMs = 0L;

    /** Retorna o timestamp (ms) do último fetch BRAPI bem-sucedido, ou 0 se nunca ocorreu. */
    public static long getLastSuccessfulFetchMs() {
        return lastSuccessfulFetchMs;
    }

    // ── Cache de cotações (TTL: 5 minutos) ──
    private static final long STOCK_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private record CachedStock(StockData data, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > STOCK_CACHE_TTL_MS;
        }
    }

    private static final ConcurrentHashMap<String, CachedStock> stockCache = new ConcurrentHashMap<>();

    /**
     * Busca dados de uma ação específica, usando cache de 5 minutos.
     */
    public static StockData fetchStockData(String ticker) throws IOException {
        if (ticker != null && !ticker.isBlank()) {
            String key = ticker.toUpperCase().trim();
            CachedStock cached = stockCache.get(key);
            if (cached != null && !cached.isExpired()) {
                return cached.data();
            }
        }
        StockData data = fetchStockDataWithToken(ticker, getToken());
        if (ticker != null && !ticker.isBlank() && data.isValid()) {
            long now = System.currentTimeMillis();
            stockCache.put(ticker.toUpperCase().trim(), new CachedStock(data, now));
            lastSuccessfulFetchMs = now;
        }
        return data;
    }

    /**
     * Versão segura de fetchStockData que nunca lança exceção.
     * Retorna FetchResult.Success ou FetchResult.Failure com o motivo classificado.
     */
    public static FetchResult<StockData> fetchStockDataSafe(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return new FetchResult.Failure<>(FailureReason.NOT_FOUND, "Ticker inválido");
        }
        String key = ticker.toUpperCase().trim();
        CachedStock cached = stockCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return new FetchResult.Success<>(cached.data());
        }

        String token = getToken();
        boolean hasToken = token != null && !token.isBlank();
        String url = appendToken(BASE_URL + QUOTE_ENDPOINT + "/" + key, token);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "Investment-Tracker/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new FetchResult.Failure<>(classify(response.code(), hasToken),
                        String.valueOf(response.code()));
            }
            if (response.body() == null) {
                return new FetchResult.Failure<>(FailureReason.NETWORK_ERROR, "Body nulo");
            }
            // IOException ao ler o corpo é falha de rede e sobe para o catch de fora; uma
            // RuntimeException ao LER ou INTERPRETAR o corpo vira PARSE_ERROR.
            try {
                String body = response.body().string();
                return interpretQuote(key, body);
            } catch (RuntimeException e) {
                return new FetchResult.Failure<>(FailureReason.PARSE_ERROR, describe(e));
            }
        } catch (IOException | RuntimeException e) {
            // Qualquer exceção ao executar a chamada (inclusive uma RuntimeException de um
            // interceptor ou do OkHttp) é falha de rede: nada escapa dos métodos Safe.
            return new FetchResult.Failure<>(FailureReason.NETWORK_ERROR, describe(e));
        }
    }

    /**
     * Motivo de uma resposta HTTP não-2xx. 401/403 → {@link FailureReason#NO_TOKEN};
     * 404 → {@link FailureReason#NOT_FOUND}; o resto (400, 429, 5xx...) → {@link FailureReason#HTTP_ERROR}.
     * A classificação é a mesma com ou sem token ({@code hasToken} não a altera).
     */
    static FailureReason classify(int code, boolean hasToken) {
        return switch (code) {
            case 401, 403 -> FailureReason.NO_TOKEN;
            case 404 -> FailureReason.NOT_FOUND;
            default -> FailureReason.HTTP_ERROR;
        };
    }

    /** Texto de uma exceção (a mensagem, ou o nome simples da classe se não houver), nunca nulo. */
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Campo {@code error} válido é um primitivo JSON (texto, booleano ou número) e significa
     * "sem dados". Objeto, {@code null} ou array é resposta de forma errada: a
     * {@link JsonParseException} sobe para o {@code catch} que a transforma em PARSE_ERROR.
     */
    private static void requirePrimitiveError(JsonObject root) {
        JsonElement error = root.get("error");
        if (!error.isJsonPrimitive()) {
            throw new JsonParseException("campo error com forma inesperada: " + error);
        }
    }

    /**
     * Interpreta o corpo de uma resposta 2xx de uma cotação. Qualquer {@link RuntimeException}
     * (JSON quebrado, corpo vazio, forma inesperada) sobe para quem chama virar PARSE_ERROR.
     */
    private static FetchResult<StockData> interpretQuote(String key, String body) {
        JsonObject root = gson.fromJson(body, JsonObject.class);
        if (root == null) {
            throw new JsonSyntaxException("Resposta vazia");
        }
        if (root.has("error")) {
            requirePrimitiveError(root);
            return new FetchResult.Failure<>(FailureReason.NOT_FOUND, root.get("error").getAsString());
        }
        JsonArray results = root.getAsJsonArray("results");
        if (results == null || results.isEmpty()) {
            return new FetchResult.Failure<>(FailureReason.NOT_FOUND, "Ação não encontrada");
        }
        StockData data = parseStockJson(results.get(0).getAsJsonObject());
        if (!data.isValid()) {
            return new FetchResult.Failure<>(FailureReason.NOT_FOUND, "Ação sem preço");
        }
        long now = System.currentTimeMillis();
        stockCache.put(key, new CachedStock(data, now));
        lastSuccessfulFetchMs = now;
        return new FetchResult.Success<>(data);
    }

    public static StockData fetchStockDataWithToken(String ticker, String token) throws IOException {
        if (ticker == null || ticker.isBlank()) {
            return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, "Ticker inválido");
        }

        String url = appendToken(
                BASE_URL + QUOTE_ENDPOINT + "/" + ticker.toUpperCase().trim(), token);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "Investment-Tracker/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0,
                        "Erro HTTP: " + response.code());
            }

            String jsonResponse = response.body().string();
            JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);

            if (root.has("error")) {
                String errorMsg = root.get("error").getAsString();
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, errorMsg);
            }

            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.size() == 0) {
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0,
                        "Ação não encontrada");
            }

            JsonObject stock = results.get(0).getAsJsonObject();

            return new StockData(
                    getStringOrNull(stock, "symbol"),
                    getStringOrNull(stock, "logourl"),
                    getStringOrNull(stock, "longName"),
                    getDoubleOrZero(stock, "regularMarketPrice"),
                    getDoubleOrZero(stock, "regularMarketChange"),
                    getDoubleOrZero(stock, "regularMarketChangePercent"),
                    getDoubleOrZero(stock, "regularMarketOpen"),
                    getDoubleOrZero(stock, "regularMarketDayHigh"),
                    getDoubleOrZero(stock, "regularMarketDayLow"),
                    getLongOrZero(stock, "regularMarketVolume"),
                    getDoubleOrZero(stock, "fiftyTwoWeekHigh"),
                    getDoubleOrZero(stock, "fiftyTwoWeekLow"),
                    getDoubleOrZero(stock, "twoHundredDayAverage"),
                    getStringOrNull(stock, "currency"),
                    getDoubleOrZero(stock, "dividendYield"),
                    getDoubleOrZero(stock, "fiftyTwoWeekChange"),
                    null
            );

        } catch (JsonSyntaxException e) {
            return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0,
                    "Erro ao parsear JSON: " + e.getMessage());
        } catch (Exception e) {
            return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0,
                    "Erro: " + e.getMessage());
        }
    }

    /**
     * Busca tickers por nome ou código via endpoint /quote/list?search=...
     * Funciona sem token. Retorna até 8 sugestões com ticker + nome.
     * Ex: "petrobras" → [{ticker:"PETR4", name:"PETROBRAS PN", type:"stock"}, ...]
     */
    public static List<TickerSuggestion> searchTickers(String query) {
        if (query == null || query.isBlank() || query.length() < 2) {
            return new ArrayList<>();
        }
        try {
            HttpUrl url = HttpUrl.parse(BASE_URL + "/quote/list")
                    .newBuilder()
                    .addQueryParameter("search", query.trim())
                    .addQueryParameter("limit", "8")
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", "Investment-Tracker/1.0")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return new ArrayList<>();
                String body = response.body().string();
                JsonObject root = gson.fromJson(body, JsonObject.class);
                List<TickerSuggestion> results = new ArrayList<>();
                JsonArray stocks = root.has("stocks") ? root.getAsJsonArray("stocks") : null;
                if (stocks != null) {
                    for (JsonElement el : stocks) {
                        JsonObject obj = el.getAsJsonObject();
                        String ticker = getStringOrNull(obj, "stock");
                        if (ticker == null) ticker = getStringOrNull(obj, "ticker");
                        String name = getStringOrNull(obj, "name");
                        String type = getStringOrNull(obj, "type");
                        if (ticker != null) {
                            results.add(new TickerSuggestion(ticker, name, type));
                        }
                    }
                }
                return results;
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

    }

    /**
     * Busca múltiplas ações de uma vez.
     * Com token: requisição em lote (mais rápido).
     * Sem token ou em caso de falha: fallback individual por ticker.
     */
    public static Map<String, StockData> fetchMultipleStocks(String tickers) throws IOException {
        Map<String, StockData> results = fetchMultipleStocksBatch(tickers, getToken());

        // Fallback individual quando o lote falha (sem token ou erro da API)
        if (results.isEmpty()) {
            for (String ticker : tickers.split(",")) {
                String t = ticker.trim().toUpperCase();
                if (t.isBlank()) continue;
                try {
                    StockData data = fetchStockData(t);
                    if (data.isValid()) results.put(t, data);
                } catch (Exception ignored) {
                    // continua para o próximo ticker
                }
            }
        }
        return results;
    }

    /**
     * Versão segura de fetchMultipleStocks que nunca lança exceção.
     * Classifica a falha em FailureReason para exibição ao usuário.
     */
    public static FetchResult<Map<String, StockData>> fetchMultipleStocksSafe(String tickers) {
        if (tickers == null || tickers.isBlank()) {
            return new FetchResult.Failure<>(FailureReason.NOT_FOUND, "sem tickers");
        }
        String token = getToken();
        boolean hasToken = token != null && !token.isBlank();

        // Motivo do lote: só vale se o fallback não tentar nenhum ticker.
        FailureReason batchReason = FailureReason.NOT_FOUND;
        String batchDetail = "Ação não encontrada";

        Map<String, StockData> results = new HashMap<>();

        // Tentativa em lote
        try {
            String base = BASE_URL + QUOTE_ENDPOINT + "/" + tickers.toUpperCase().trim() + "?fundamental=true";
            String url = appendToken(base, token);
            Request req = new Request.Builder()
                    .url(url).get()
                    .addHeader("User-Agent", "Investment-Tracker/1.0")
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    batchReason = classify(resp.code(), hasToken);
                    batchDetail = String.valueOf(resp.code());
                } else if (resp.body() == null) {
                    batchReason = FailureReason.NETWORK_ERROR;
                    batchDetail = "Body nulo";
                } else {
                    // IOException ao ler o corpo sobe para o catch de fora (NETWORK_ERROR);
                    // RuntimeException ao LER ou INTERPRETAR vira PARSE_ERROR.
                    try {
                        String body = resp.body().string();
                        JsonObject root = gson.fromJson(body, JsonObject.class);
                        if (root == null) {
                            throw new JsonSyntaxException("Resposta vazia");
                        }
                        if (root.has("error")) {
                            requirePrimitiveError(root);
                            batchDetail = String.valueOf(root.get("error"));
                        } else {
                            JsonArray arr = root.getAsJsonArray("results");
                            if (arr != null) {
                                Map<String, StockData> parsed = new HashMap<>();
                                for (JsonElement el : arr) {
                                    JsonObject stock = el.getAsJsonObject();
                                    String sym = getStringOrNull(stock, "symbol");
                                    if (sym == null) continue;
                                    StockData data = parseStockJson(stock);
                                    // Preço 0 ou ausente nunca entra no resultado.
                                    if (data.isValid()) parsed.put(sym, data);
                                }
                                results.putAll(parsed);
                            }
                        }
                    } catch (RuntimeException e) {
                        batchReason = FailureReason.PARSE_ERROR;
                        batchDetail = describe(e);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // O parse tem seu próprio catch (PARSE_ERROR); aqui só chega falha ao executar a chamada.
            batchReason = FailureReason.NETWORK_ERROR;
            batchDetail = describe(e);
        }

        // Fallback individual quando o lote não deu nenhum ticker utilizável.
        // Se nada der certo, o motivo final vem só do fallback: o de maior prioridade
        // (NO_TOKEN > HTTP_ERROR > NETWORK_ERROR > PARSE_ERROR > NOT_FOUND); em empate, o do
        // primeiro ticker da lista.
        FailureReason fallbackReason = null;
        String fallbackDetail = null;
        if (results.isEmpty()) {
            for (String t : tickers.split(",")) {
                String tk = t.trim().toUpperCase();
                if (tk.isBlank()) continue;
                FetchResult<StockData> one = fetchStockDataSafe(tk);
                if (one.isSuccess() && one.getData().isValid()) {
                    results.put(tk, one.getData());
                    continue;
                }
                FailureReason reason = one.isSuccess() ? FailureReason.NOT_FOUND : one.getReason();
                String detail = one.isSuccess() ? "Ação sem preço" : one.getDetail();
                if (fallbackReason == null || priority(reason) < priority(fallbackReason)) {
                    fallbackReason = reason;
                    fallbackDetail = detail;
                }
            }
        }

        if (results.isEmpty()) {
            if (fallbackReason != null) {
                return new FetchResult.Failure<>(fallbackReason, fallbackDetail);
            }
            return new FetchResult.Failure<>(batchReason, batchDetail);
        }
        lastSuccessfulFetchMs = System.currentTimeMillis();
        return new FetchResult.Success<>(results);
    }

    /** Ordem de prioridade do motivo final de um lote que falhou (menor = mais prioritário). */
    private static int priority(FailureReason reason) {
        return switch (reason) {
            case NO_TOKEN -> 0;
            case HTTP_ERROR -> 1;
            case NETWORK_ERROR -> 2;
            case PARSE_ERROR -> 3;
            case NOT_FOUND -> 4;
        };
    }

    /** Busca em lote — uma requisição para todos os tickers. */
    private static Map<String, StockData> fetchMultipleStocksBatch(String tickers, String token) {
        Map<String, StockData> results = new HashMap<>();
        try {
            String base = BASE_URL + QUOTE_ENDPOINT + "/" + tickers.toUpperCase().trim() + "?fundamental=true";
            String url  = appendToken(base, token);

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", "Investment-Tracker/1.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return results;

                JsonObject root = gson.fromJson(response.body().string(), JsonObject.class);
                if (root.has("error")) return results;

                JsonArray resultsArray = root.getAsJsonArray("results");
                if (resultsArray == null) return results;

                for (JsonElement element : resultsArray) {
                    JsonObject stock = element.getAsJsonObject();
                    String symbol = getStringOrNull(stock, "symbol");
                    if (symbol != null) {
                        results.put(symbol, new StockData(
                                symbol,
                                getStringOrNull(stock, "logourl"),
                                getStringOrNull(stock, "longName"),
                                getDoubleOrZero(stock, "regularMarketPrice"),
                                getDoubleOrZero(stock, "regularMarketChange"),
                                getDoubleOrZero(stock, "regularMarketChangePercent"),
                                getDoubleOrZero(stock, "regularMarketOpen"),
                                getDoubleOrZero(stock, "regularMarketDayHigh"),
                                getDoubleOrZero(stock, "regularMarketDayLow"),
                                getLongOrZero(stock, "regularMarketVolume"),
                                getDoubleOrZero(stock, "fiftyTwoWeekHigh"),
                                getDoubleOrZero(stock, "fiftyTwoWeekLow"),
                                getDoubleOrZero(stock, "twoHundredDayAverage"),
                                getStringOrNull(stock, "currency"),
                                getDoubleOrZero(stock, "dividendYield"),
                                getDoubleOrZero(stock, "fiftyTwoWeekChange"),
                                null
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
            // retorna o que tiver
        }
        return results;
    }

    // Helper methods

    private static StockData parseStockJson(JsonObject stock) {
        return new StockData(
                getStringOrNull(stock, "symbol"),
                getStringOrNull(stock, "logourl"),
                getStringOrNull(stock, "longName"),
                getDoubleOrZero(stock, "regularMarketPrice"),
                getDoubleOrZero(stock, "regularMarketChange"),
                getDoubleOrZero(stock, "regularMarketChangePercent"),
                getDoubleOrZero(stock, "regularMarketOpen"),
                getDoubleOrZero(stock, "regularMarketDayHigh"),
                getDoubleOrZero(stock, "regularMarketDayLow"),
                getLongOrZero(stock, "regularMarketVolume"),
                getDoubleOrZero(stock, "fiftyTwoWeekHigh"),
                getDoubleOrZero(stock, "fiftyTwoWeekLow"),
                getDoubleOrZero(stock, "twoHundredDayAverage"),
                getStringOrNull(stock, "currency"),
                getDoubleOrZero(stock, "dividendYield"),
                getDoubleOrZero(stock, "fiftyTwoWeekChange"),
                null
        );
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static double getDoubleOrZero(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return 0.0;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static long getLongOrZero(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ── Cache para retorno anualizado do IBOVESPA ──
    private static final long IBOV_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1);
    private static volatile double cachedIbovReturn = Double.NaN;
    private static volatile long cachedIbovTimestamp = 0;

    /**
     * Retorno do IBOVESPA nos últimos 12 meses via dados históricos da Brapi.
     * Requer token configurado. Retorna decimal anual (ex: 0.15 = 15%).
     */
    public static Optional<Double> fetchIbovespaReturn() {
        if (!Double.isNaN(cachedIbovReturn)
                && System.currentTimeMillis() - cachedIbovTimestamp < IBOV_CACHE_TTL_MS) {
            return Optional.of(cachedIbovReturn);
        }

        String token = getToken();
        if (token == null || token.isBlank()) {
            LOG.warning("[IBOV] Token não encontrado");
            return Optional.empty();
        }

        // Tentar histórico 3mo (máximo do plano gratuito)
        Optional<Double> hist = fetchIbovFromHistorical(token);
        if (hist.isPresent()) {
            cachedIbovReturn = hist.get();
            cachedIbovTimestamp = System.currentTimeMillis();
            return hist;
        }

        // Fallback diário intencionalmente omitido: regularMarketChangePercent é a variação de
        // um único pregão e não pode ser anualizado como retorno de benchmark de 12 meses —
        // um dia de +1,5% anualizado em 252 pregões produziria ~4100%, um valor absurdo.
        LOG.info("[IBOV] Histórico indisponível — benchmark IBOVESPA não exibido.");
        return Optional.empty();
    }

    private static Optional<Double> fetchIbovFromHistorical(String token) {
        try {
            HttpUrl httpUrl = HttpUrl.parse(BASE_URL + QUOTE_ENDPOINT + "/%5EBVSP")
                    .newBuilder()
                    .addQueryParameter("range", "3mo")
                    .addQueryParameter("interval", "1mo")
                    .addQueryParameter("token", token.trim())
                    .build();

            LOG.fine("[IBOV] URL: " + httpUrl.toString().replaceAll("token=.*", "token=***"));

            Request request = new Request.Builder()
                    .url(httpUrl)
                    .get()
                    .addHeader("User-Agent", "Investment-Tracker/1.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.body() == null) {
                    LOG.warning("[IBOV] body nulo, HTTP " + response.code());
                    return Optional.empty();
                }

                String jsonResponse = response.body().string();

                if (!response.isSuccessful()) {
                    LOG.warning("[IBOV] HTTP " + response.code() + " body: " + jsonResponse);
                    return Optional.empty();
                }

                JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);

                // Checar campo error (pode ser boolean true ou string)
                if (root.has("error")) {
                    JsonElement errEl = root.get("error");
                    if (errEl.isJsonPrimitive()) {
                        JsonPrimitive ep = errEl.getAsJsonPrimitive();
                        if ((ep.isBoolean() && ep.getAsBoolean()) || ep.isString()) {
                            LOG.warning("[IBOV] error: " + errEl);
                            return Optional.empty();
                        }
                    } else {
                        LOG.warning("[IBOV] error (objeto): " + errEl);
                        return Optional.empty();
                    }
                }

                JsonArray results = root.getAsJsonArray("results");
                if (results == null || results.isEmpty()) {
                    LOG.warning("[IBOV] results vazio/nulo");
                    return Optional.empty();
                }

                JsonObject result = results.get(0).getAsJsonObject();

                JsonArray hist = result.getAsJsonArray("historicalDataPrice");
                if (hist == null || hist.isEmpty()) {
                    LOG.warning("[IBOV] historicalDataPrice nulo/vazio");
                    return Optional.empty();
                }

                // Extrair preço: close → adjustedClose → open (fallback)
                double firstPrice = extractPrice(hist.get(0).getAsJsonObject());
                double lastPrice = extractPrice(hist.get(hist.size() - 1).getAsJsonObject());

                // Se último preço é 0 (mês incompleto), pegar o penúltimo
                if (lastPrice <= 0 && hist.size() > 2) {
                    lastPrice = extractPrice(hist.get(hist.size() - 2).getAsJsonObject());
                }

                if (firstPrice <= 0 || lastPrice <= 0) {
                    LOG.warning("[IBOV] preço inválido — first=" + firstPrice + " last=" + lastPrice);
                    return Optional.empty();
                }

                double retornoPeriodo = (lastPrice / firstPrice) - 1;
                int meses = Math.max(hist.size() - 1, 1);
                double retornoAnual = Math.pow(1 + retornoPeriodo, 12.0 / meses) - 1;

                LOG.fine(String.format("[IBOV] Retorno %dm: %.2f%% → anual: %.2f%%",
                        meses, retornoPeriodo * 100, retornoAnual * 100));
                return Optional.of(retornoAnual);
            }
        } catch (Exception e) {
            LOG.warning("[IBOV] Exceção hist: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static double extractPrice(JsonObject entry) {
        double v = getDoubleOrZero(entry, "close");
        if (v <= 0) v = getDoubleOrZero(entry, "adjustedClose");
        if (v <= 0) v = getDoubleOrZero(entry, "open");
        return v;
    }

    public static boolean testConnectionWithToken(String token) {
        try {
            StockData test = fetchStockDataWithToken("PETR4", token);
            return test.isValid();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasToken() {
        String token = getToken();
        return token != null && !token.isBlank();
    }
}
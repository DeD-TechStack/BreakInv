package com.daniel.infrastructure.api;

import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import okhttp3.*;
import com.google.gson.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class BrapiClient {

    private static final String BASE_URL = "https://brapi.dev/api";
    private static final String QUOTE_ENDPOINT = "/quote";
    private static final String AVAILABLE_ENDPOINT = "/available";

    public static final String SETTINGS_KEY_TOKEN = "brapi_token";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final Gson gson = new Gson();
    private static final AppSettingsRepository settingsRepo = new AppSettingsRepository();

    private static String getToken() {
        return settingsRepo.get(SETTINGS_KEY_TOKEN).orElse(null);
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
            String error
    ) {
        public boolean hasError() {
            return error != null && !error.isBlank();
        }

        public boolean isValid() {
            return !hasError() && regularMarketPrice > 0;
        }
    }

    // Sugestão de ticker
    public record TickerSuggestion(
            String ticker,
            String name,
            String type  // "stock", "fund", "fii"
    ) {}

    // Histórico de dividendos
    public record DividendHistory(
            String ticker,
            double averageYield,  // Média de dividend yield
            double lastYearTotal,  // Total pago no último ano
            List<DividendPayment> payments
    ) {}

    public record DividendPayment(
            String date,
            double value
    ) {}

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
            stockCache.put(ticker.toUpperCase().trim(), new CachedStock(data, System.currentTimeMillis()));
        }
        return data;
    }

    public static StockData fetchStockDataWithToken(String ticker, String token) throws IOException {
        if (ticker == null || ticker.isBlank()) {
            return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, "Ticker inválido");
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
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0,
                        "Erro HTTP: " + response.code());
            }

            String jsonResponse = response.body().string();
            JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);

            if (root.has("error")) {
                String errorMsg = root.get("error").getAsString();
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, errorMsg);
            }

            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.size() == 0) {
                return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0,
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
                    null
            );

        } catch (JsonSyntaxException e) {
            return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0,
                    "Erro ao parsear JSON: " + e.getMessage());
        } catch (Exception e) {
            return new StockData(ticker, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0,
                    "Erro: " + e.getMessage());
        }
    }

    /**
     * Busca tickers por nome/código (autocomplete)
     */
    public static List<TickerSuggestion> searchTickers(String query) throws IOException {
        if (query == null || query.isBlank() || query.length() < 2) {
            return new ArrayList<>();
        }

        String url = BASE_URL + AVAILABLE_ENDPOINT;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "Investment-Tracker/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new ArrayList<>();
            }

            String jsonResponse = response.body().string();
            JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);

            List<TickerSuggestion> suggestions = new ArrayList<>();

            if (root.has("stocks")) {
                JsonArray stocks = root.getAsJsonArray("stocks");
                for (JsonElement element : stocks) {
                    String ticker = element.getAsString();
                    if (ticker.toLowerCase().contains(query.toLowerCase())) {
                        suggestions.add(new TickerSuggestion(ticker, ticker, "stock"));
                    }
                }
            }

            // Limitar a 10 sugestões
            return suggestions.size() > 10 ? suggestions.subList(0, 10) : suggestions;

        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Calcula média de dividendos com base no dividend yield
     */
    public static DividendHistory estimateDividends(String ticker) throws IOException {
        StockData data = fetchStockData(ticker);

        if (!data.isValid() || data.dividendYield() <= 0) {
            return new DividendHistory(ticker, 0, 0, new ArrayList<>());
        }

        // Estimar dividendos anuais baseado no yield e preço atual
        double estimatedAnnualDividend = data.regularMarketPrice() * (data.dividendYield() / 100.0);

        return new DividendHistory(
                ticker,
                data.dividendYield(),
                estimatedAnnualDividend,
                new ArrayList<>()  // API pública não retorna histórico detalhado
        );
    }

    /**
     * Busca múltiplas ações de uma vez
     */
    public static Map<String, StockData> fetchMultipleStocks(String tickers) throws IOException {
        Map<String, StockData> results = new HashMap<>();

        String url = appendToken(
                BASE_URL + QUOTE_ENDPOINT + "/" + tickers.toUpperCase().trim(), getToken());

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "Investment-Tracker/1.0")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return results;
            }

            String jsonResponse = response.body().string();
            JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);

            if (root.has("error")) {
                return results;
            }

            JsonArray resultsArray = root.getAsJsonArray("results");
            if (resultsArray == null) {
                return results;
            }

            for (JsonElement element : resultsArray) {
                JsonObject stock = element.getAsJsonObject();
                String symbol = getStringOrNull(stock, "symbol");

                if (symbol != null) {
                    StockData data = new StockData(
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
                            null
                    );

                    results.put(symbol, data);
                }
            }

            return results;

        } catch (Exception e) {
            return results;
        }
    }

    // Helper methods

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
            System.err.println("[IBOV] Token não encontrado");
            return Optional.empty();
        }

        // Tentar histórico 3mo (máximo do plano gratuito)
        Optional<Double> hist = fetchIbovFromHistorical(token);
        if (hist.isPresent()) {
            cachedIbovReturn = hist.get();
            cachedIbovTimestamp = System.currentTimeMillis();
            return hist;
        }

        // Fallback: quote básico — usa regularMarketChangePercent (variação diária)
        // anualizado como estimativa grosseira
        System.err.println("[IBOV] Histórico falhou, tentando quote básico...");
        try {
            StockData data = fetchStockData("^BVSP");
            if (data != null && data.isValid()) {
                double dailyPct = data.regularMarketChangePercent() / 100.0;
                double annualized = Math.pow(1 + dailyPct, 252) - 1;
                cachedIbovReturn = annualized;
                cachedIbovTimestamp = System.currentTimeMillis();
                System.err.println("[IBOV] Fallback quote: diário="
                        + String.format("%.4f%%", dailyPct * 100)
                        + " anualizado=" + String.format("%.2f%%", annualized * 100));
                return Optional.of(annualized);
            }
        } catch (Exception e) {
            System.err.println("[IBOV] Fallback quote falhou: " + e.getMessage());
        }

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

            System.err.println("[IBOV] URL: " + httpUrl.toString().replaceAll("token=.*", "token=***"));

            Request request = new Request.Builder()
                    .url(httpUrl)
                    .get()
                    .addHeader("User-Agent", "Investment-Tracker/1.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.body() == null) {
                    System.err.println("[IBOV] body nulo, HTTP " + response.code());
                    return Optional.empty();
                }

                String jsonResponse = response.body().string();

                if (!response.isSuccessful()) {
                    System.err.println("[IBOV] HTTP " + response.code() + " body: " + jsonResponse);
                    return Optional.empty();
                }

                System.err.println("[IBOV] Resposta (500 chars): "
                        + jsonResponse.substring(0, Math.min(jsonResponse.length(), 500)));

                JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);

                // Checar campo error (pode ser boolean true ou string)
                if (root.has("error")) {
                    JsonElement errEl = root.get("error");
                    if (errEl.isJsonPrimitive()) {
                        JsonPrimitive ep = errEl.getAsJsonPrimitive();
                        if ((ep.isBoolean() && ep.getAsBoolean()) || ep.isString()) {
                            System.err.println("[IBOV] error: " + errEl);
                            return Optional.empty();
                        }
                    } else {
                        System.err.println("[IBOV] error (objeto): " + errEl);
                        return Optional.empty();
                    }
                }

                JsonArray results = root.getAsJsonArray("results");
                if (results == null || results.isEmpty()) {
                    System.err.println("[IBOV] results vazio/nulo");
                    return Optional.empty();
                }

                JsonObject result = results.get(0).getAsJsonObject();
                System.err.println("[IBOV] Result keys: " + result.keySet());

                JsonArray hist = result.getAsJsonArray("historicalDataPrice");
                if (hist == null || hist.isEmpty()) {
                    System.err.println("[IBOV] historicalDataPrice nulo/vazio");
                    return Optional.empty();
                }

                System.err.println("[IBOV] hist entries=" + hist.size()
                        + " primeira=" + hist.get(0)
                        + " última=" + hist.get(hist.size() - 1));

                // Extrair preço: close → adjustedClose → open (fallback)
                double firstPrice = extractPrice(hist.get(0).getAsJsonObject());
                double lastPrice = extractPrice(hist.get(hist.size() - 1).getAsJsonObject());

                // Se último preço é 0 (mês incompleto), pegar o penúltimo
                if (lastPrice <= 0 && hist.size() > 2) {
                    lastPrice = extractPrice(hist.get(hist.size() - 2).getAsJsonObject());
                }

                System.err.println("[IBOV] firstPrice=" + firstPrice + " lastPrice=" + lastPrice);

                if (firstPrice <= 0 || lastPrice <= 0) {
                    System.err.println("[IBOV] preço inválido");
                    return Optional.empty();
                }

                double retornoPeriodo = (lastPrice / firstPrice) - 1;
                int meses = Math.max(hist.size() - 1, 1);
                double retornoAnual = Math.pow(1 + retornoPeriodo, 12.0 / meses) - 1;

                System.err.println("[IBOV] Retorno " + meses + "m: "
                        + String.format("%.2f%%", retornoPeriodo * 100)
                        + " → anual: " + String.format("%.2f%%", retornoAnual * 100));
                return Optional.of(retornoAnual);
            }
        } catch (Exception e) {
            System.err.println("[IBOV] Exceção hist: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static double extractPrice(JsonObject entry) {
        double v = getDoubleOrZero(entry, "close");
        if (v <= 0) v = getDoubleOrZero(entry, "adjustedClose");
        if (v <= 0) v = getDoubleOrZero(entry, "open");
        return v;
    }

    public static boolean testConnection() {
        try {
            StockData test = fetchStockData("PETR4");
            return test.isValid();
        } catch (Exception e) {
            return false;
        }
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
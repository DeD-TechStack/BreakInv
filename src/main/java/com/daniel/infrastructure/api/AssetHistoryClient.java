package com.daniel.infrastructure.api;

import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Busca histórico de preços de um ativo via Brapi.
 * Requer token configurado para períodos longos (>1mo).
 */
public final class AssetHistoryClient {

    private static final Logger LOG = Logger.getLogger(AssetHistoryClient.class.getName());
    private static final String BASE_URL = "https://brapi.dev/api";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final Gson GSON = new Gson();
    private static final AppSettingsRepository SETTINGS = new AppSettingsRepository();

    public record HistoryPoint(LocalDate date, double close) {}

    public enum Period {
        ONE_WEEK  ("5d",  "1d",  "1 Semana"),
        ONE_MONTH ("1mo", "1d",  "1 Mês"),
        THREE_MONTHS("3mo","1wk","3 Meses"),
        ONE_YEAR  ("1y",  "1mo", "1 Ano");

        public final String range;
        public final String interval;
        public final String label;

        Period(String range, String interval, String label) {
            this.range    = range;
            this.interval = interval;
            this.label    = label;
        }

        @Override
        public String toString() { return label; }
    }

    /**
     * Retorna histórico de preços para o ticker no período solicitado.
     */
    public static List<HistoryPoint> fetchHistory(String ticker, Period period) throws IOException {
        if (ticker == null || ticker.isBlank()) return new ArrayList<>();

        String token = SETTINGS.get(BrapiClient.SETTINGS_KEY_TOKEN).orElse(null);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/quote/" + ticker.toUpperCase().trim())
                .newBuilder()
                .addQueryParameter("range", period.range)
                .addQueryParameter("interval", period.interval);

        if (token != null && !token.isBlank()) {
            urlBuilder.addQueryParameter("token", token.trim());
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("User-Agent", "BreakInv/1.0")
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (response.body() == null) return new ArrayList<>();

            String body = response.body().string();

            if (!response.isSuccessful()) {
                LOG.warning("[AssetHistory] HTTP " + response.code() + " for " + ticker);
                return new ArrayList<>();
            }

            return parseHistory(body);
        } catch (Exception e) {
            LOG.warning("[AssetHistory] " + ticker + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<HistoryPoint> parseHistory(String json) {
        List<HistoryPoint> points = new ArrayList<>();
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root.has("error")) return points;

            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.isEmpty()) return points;

            JsonObject result = results.get(0).getAsJsonObject();
            JsonArray hist = result.getAsJsonArray("historicalDataPrice");
            if (hist == null) return points;

            for (JsonElement el : hist) {
                JsonObject entry = el.getAsJsonObject();

                double close = getDoubleOrZero(entry, "close");
                if (close <= 0) close = getDoubleOrZero(entry, "adjustedClose");
                if (close <= 0) continue;

                LocalDate date = parseEntryDate(entry);
                if (date == null) continue;

                points.add(new HistoryPoint(date, close));
            }
        } catch (Exception e) {
            LOG.warning("[AssetHistory] parse error: " + e.getMessage());
        }
        return points;
    }

    private static LocalDate parseEntryDate(JsonObject entry) {
        if (!entry.has("date") || entry.get("date").isJsonNull()) return null;
        JsonElement dateEl = entry.get("date");
        try {
            if (dateEl.isJsonPrimitive()) {
                JsonPrimitive prim = dateEl.getAsJsonPrimitive();
                if (prim.isNumber()) {
                    long raw = prim.getAsLong();
                    // Epoch em ms (>1tri) → converter para segundos
                    if (raw > 9_999_999_999L) raw = raw / 1000;
                    return Instant.ofEpochSecond(raw)
                            .atZone(ZoneId.of("America/Sao_Paulo"))
                            .toLocalDate();
                } else {
                    // String: "2025-02-10" ou "2025-02-10T00:00:00.000Z"
                    String s = prim.getAsString().substring(0, 10);
                    return LocalDate.parse(s, DATE_FMT);
                }
            }
        } catch (Exception e) {
            LOG.fine("[AssetHistory] date parse failed: " + dateEl + " → " + e.getMessage());
        }
        return null;
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        if (el.isJsonPrimitive()) return el.getAsString();
        return null;
    }

    private static double getDoubleOrZero(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0.0;
        try { return obj.get(key).getAsDouble(); }
        catch (Exception e) { return 0.0; }
    }
}

package com.daniel.infrastructure.api;

import com.daniel.infrastructure.persistence.repository.AppSettingsRepository;
import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

    public record HistoryPoint(LocalDateTime dateTime, double close) {}

    public enum Period {
        ONE_DAY     ("1d",  "60m", "1 Dia",    "HH:mm"),
        ONE_WEEK    ("5d",  "1d",  "1 Semana", "dd/MM"),
        ONE_MONTH   ("1mo", "1d",  "1 Mês",    "dd/MM"),
        THREE_MONTHS("3mo", "1wk", "3 Meses",  "dd/MM"),
        ONE_YEAR    ("1y",  "1mo", "1 Ano",     "MM/yy"),
        FIVE_YEARS  ("5y",  "1mo", "5 Anos",    "MM/yy"),
        TEN_YEARS   ("10y", "3mo", "10 Anos",   "MM/yyyy"),
        MAX         ("max", "3mo", "Máx",       "MM/yyyy");

        public final String range;
        public final String interval;
        public final String label;
        public final String datePattern;

        Period(String range, String interval, String label, String datePattern) {
            this.range       = range;
            this.interval    = interval;
            this.label       = label;
            this.datePattern = datePattern;
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

    /**
     * Busca histórico de preços para o ticker no intervalo de datas informado.
     * Seleciona automaticamente o range e intervalo ideais para cobrir o período,
     * e filtra os resultados para o intervalo exato solicitado.
     *
     * @param ticker símbolo do ativo (ex: PETR4)
     * @param from   data de início (inclusive)
     * @param to     data de fim (inclusive)
     * @return lista de pontos históricos ordenada cronologicamente
     */
    public static List<HistoryPoint> fetchHistoryByDateRange(
            String ticker, LocalDate from, LocalDate to) throws IOException {
        if (ticker == null || ticker.isBlank()) return new ArrayList<>();

        long days = ChronoUnit.DAYS.between(from, to);

        // Seleciona range/interval que cobre o período com a melhor granularidade
        String range;
        String interval;
        if (days <= 7)           { range = "5d";  interval = "1d"; }
        else if (days <= 31)     { range = "1mo"; interval = "1d"; }
        else if (days <= 93)     { range = "3mo"; interval = "1d"; }
        else if (days <= 366)    { range = "1y";  interval = "1d"; }
        else if (days <= 5 * 366){ range = "5y";  interval = "1wk"; }
        else                     { range = "max"; interval = "1mo"; }

        String token = SETTINGS.get(BrapiClient.SETTINGS_KEY_TOKEN).orElse(null);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/quote/" + ticker.toUpperCase().trim())
                .newBuilder()
                .addQueryParameter("range", range)
                .addQueryParameter("interval", interval);

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
            return parseHistory(body).stream()
                    .filter(p -> {
                        LocalDate d = p.dateTime().toLocalDate();
                        return !d.isBefore(from) && !d.isAfter(to);
                    })
                    .sorted(Comparator.comparing(HistoryPoint::dateTime))
                    .collect(Collectors.toList());
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

                LocalDateTime dateTime = parseEntryDateTime(entry);
                if (dateTime == null) continue;

                points.add(new HistoryPoint(dateTime, close));
            }
        } catch (Exception e) {
            LOG.warning("[AssetHistory] parse error: " + e.getMessage());
        }
        return points;
    }

    private static LocalDateTime parseEntryDateTime(JsonObject entry) {
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
                            .toLocalDateTime();
                } else {
                    // String: "2025-02-10" ou "2025-02-10T00:00:00.000Z"
                    String s = prim.getAsString();
                    if (s.length() > 10) {
                        // ISO com hora: trunca ao minuto
                        return LocalDateTime.parse(s.substring(0, 16),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                    }
                    return LocalDate.parse(s.substring(0, 10), DATE_FMT).atStartOfDay();
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

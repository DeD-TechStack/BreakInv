package com.daniel.infrastructure.api;

import okhttp3.*;
import com.google.gson.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class BcbClient {

    private static final String BASE_URL = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.";
    private static final String SUFFIX = "/dados/ultimos/1?formato=json";

    // SELIC (432): taxa anual em % → decimal anual (ex: 13.25 → 0.1325)
    // CDI   (12):  taxa DIÁRIA em % → convertida para decimal anual
    // IPCA  (433): variação MENSAL em % → convertida para decimal anual
    private static final int SERIES_SELIC = 432;
    private static final int SERIES_CDI = 12;
    private static final int SERIES_IPCA = 433;

    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1);

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final Gson gson = new Gson();

    private record CachedRate(double value, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static final Map<Integer, CachedRate> cache = new ConcurrentHashMap<>();

    public static Optional<Double> fetchSelic() {
        return fetchRate(SERIES_SELIC);
    }

    public static Optional<Double> fetchCdi() {
        return fetchRate(SERIES_CDI);
    }

    public static Optional<Double> fetchIpca() {
        return fetchRate(SERIES_IPCA);
    }

    private static Optional<Double> fetchRate(int seriesId) {
        CachedRate cached = cache.get(seriesId);
        if (cached != null && !cached.isExpired()) {
            return Optional.of(cached.value);
        }

        try {
            String url = BASE_URL + seriesId + SUFFIX;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", "Investment-Tracker/1.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return Optional.empty();
                }

                String json = response.body().string();
                JsonArray array = gson.fromJson(json, JsonArray.class);

                if (array == null || array.isEmpty()) {
                    return Optional.empty();
                }

                JsonObject entry = array.get(0).getAsJsonObject();
                String valorStr = entry.get("valor").getAsString();
                double percent = Double.parseDouble(valorStr.replace(",", "."));

                // Normalizar para decimal anual
                double decimal;
                if (seriesId == SERIES_CDI) {
                    // CDI retorna taxa DIÁRIA em % → converter para anual
                    double dailyDecimal = percent / 100.0;
                    decimal = Math.pow(1 + dailyDecimal, 252) - 1;
                } else if (seriesId == SERIES_IPCA) {
                    // IPCA retorna variação MENSAL em % → converter para anual
                    double monthlyDecimal = percent / 100.0;
                    decimal = Math.pow(1 + monthlyDecimal, 12) - 1;
                } else {
                    // SELIC retorna taxa ANUAL em % → apenas dividir por 100
                    decimal = percent / 100.0;
                }

                cache.put(seriesId, new CachedRate(decimal, System.currentTimeMillis()));
                return Optional.of(decimal);
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

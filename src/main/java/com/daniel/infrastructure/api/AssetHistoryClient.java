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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Busca histórico de preços de um ativo via Brapi.
 *
 * <p>A Brapi restringe os ranges disponíveis por plano. Quando um range solicitado não
 * está disponível, o método {@link #fetchHistory} usa um fallback inteligente: extrai a
 * lista de ranges permitidos diretamente da mensagem de erro da API e tenta o maior
 * range disponível, evitando chamadas desnecessárias.</p>
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

    /**
     * Resultado de uma busca de histórico.
     *
     * @param points          pontos históricos retornados (já na granularidade do effectiveRange)
     * @param effectiveRange  range que foi efetivamente buscado (pode diferir do solicitado)
     * @param permittedRanges ranges confirmados pelo plano/token via resposta da Brapi;
     *                        vazio quando a requisição primária teve sucesso (sem limite descoberto)
     */
    public record HistoryResult(
            List<HistoryPoint> points,
            String effectiveRange,
            Set<String> permittedRanges) {

        /** true quando o range efetivo difere do solicitado (houve fallback). */
        public boolean usedFallback(Period requested) {
            return !requested.range.equals(effectiveRange);
        }
    }

    public enum Period {
        // interval=1d é o único confiável no plano básico da Brapi.
        // ONE_DAY ocultado na UI (intraday requer plano pago).
        ONE_DAY     ("5d",  "1d",  "1 Dia",    "dd/MM"),
        ONE_WEEK    ("5d",  "1d",  "1 Semana", "dd/MM"),
        ONE_MONTH   ("1mo", "1d",  "1 Mês",    "dd/MM"),
        THREE_MONTHS("3mo", "1d",  "3 Meses",  "dd/MM"),
        ONE_YEAR    ("1y",  "1d",  "1 Ano",    "MM/yy"),
        FIVE_YEARS  ("5y",  "1mo", "5 Anos",   "MM/yy"),
        TEN_YEARS   ("10y", "1mo", "10 Anos",  "MM/yyyy"),
        MAX         ("max", "1mo", "Máx",      "MM/yyyy");

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

        @Override public String toString() { return label; }
    }

    /**
     * Ordem decrescente de cobertura histórica — usada para determinar qual range
     * tentar após uma falha.
     */
    private static final List<String> RANGE_ORDER =
            List.of("max", "10y", "5y", "1y", "3mo", "1mo", "5d", "1d");

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Retorna histórico de preços para o ticker no período solicitado.
     *
     * <p>Estratégia de fallback:
     * <ol>
     *   <li>Tenta o range/interval do período solicitado.</li>
     *   <li>Se a API retornar "range não disponível" com lista de ranges permitidos,
     *       seleciona o maior permitido e tenta uma segunda vez com interval=1d.</li>
     *   <li>Se ainda falhar (ex: ativo sem dados naquele range), tenta ranges menores
     *       em ordem decrescente até encontrar dados.</li>
     * </ol>
     * Máximo de chamadas HTTP: 2 na maioria dos casos; até N se o ativo tiver dados
     * escassos dentro dos ranges permitidos.</p>
     *
     * @throws IOException se nenhum range retornar dados válidos
     */
    public static HistoryResult fetchHistory(String ticker, Period period) throws IOException {
        if (ticker == null || ticker.isBlank())
            return new HistoryResult(List.of(), period.range, Set.of());

        // Tentativa 1: range/interval naturais do período
        try {
            List<HistoryPoint> pts = doFetch(ticker, period.range, period.interval);
            // Sucesso direto — não sabemos se ranges maiores existem, logo permittedRanges vazio
            return new HistoryResult(pts, period.range, Set.of());
        } catch (IOException primary) {
            if (isAuthError(primary)) throw primary;
            LOG.info("[AssetHistory] range=" + period.range + " falhou para " + ticker
                    + ": " + primary.getMessage());

            // Extrai ranges permitidos da mensagem de erro da Brapi (se disponível)
            List<String> permitted = parsePermittedRanges(primary.getMessage());
            Set<String> permittedSet = permitted.isEmpty() ? Set.of() : new HashSet<>(permitted);
            List<String[]> candidates = buildCandidates(period.range, period.interval, permitted);

            IOException lastErr = primary;
            for (String[] ri : candidates) {
                try {
                    List<HistoryPoint> pts = doFetch(ticker, ri[0], ri[1]);
                    LOG.info("[AssetHistory] fallback OK: " + ticker
                            + " range=" + ri[0] + " interval=" + ri[1]);
                    return new HistoryResult(pts, ri[0], permittedSet);
                } catch (IOException e) {
                    if (isAuthError(e)) throw e;
                    lastErr = e;
                    LOG.fine("[AssetHistory] fallback " + ri[0] + " falhou: " + e.getMessage());
                }
            }
            throw lastErr;
        }
    }

    /**
     * Busca histórico de preços para o ticker no intervalo de datas informado.
     * Seleciona automaticamente o range e intervalo ideais para cobrir o período,
     * e filtra os resultados para o intervalo exato solicitado.
     */
    public static List<HistoryPoint> fetchHistoryByDateRange(
            String ticker, LocalDate from, LocalDate to) throws IOException {
        if (ticker == null || ticker.isBlank()) return List.of();

        long days = ChronoUnit.DAYS.between(from, to);
        // interval=1d é o único confiável; ranges maiores que 3mo exigem plano específico
        String range;
        if      (days <= 7)   range = "5d";
        else if (days <= 31)  range = "1mo";
        else                  range = "3mo";

        try (Response response = HTTP.newCall(buildRequest(ticker, range, "1d")).execute()) {
            if (response.body() == null) return List.of();
            String body = response.body().string();
            if (!response.isSuccessful()) {
                LOG.warning("[AssetHistory] HTTP " + response.code() + " for " + ticker);
                return List.of();
            }
            return parseHistory(ticker, body).stream()
                    .filter(p -> {
                        LocalDate d = p.dateTime().toLocalDate();
                        return !d.isBefore(from) && !d.isAfter(to);
                    })
                    .sorted(Comparator.comparing(HistoryPoint::dateTime))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.warning("[AssetHistory] fetchByDateRange " + ticker + ": " + e.getMessage());
            return List.of();
        }
    }

    // ── Fallback helpers ──────────────────────────────────────────────────

    /**
     * Constrói a lista ordenada de candidatos a tentar após a falha do range primário.
     *
     * <p>Se a Brapi informou os ranges permitidos, usa apenas esses (maior → menor,
     * com interval=1d para granularidade máxima). Caso contrário, usa todos os ranges
     * menores que o solicitado em ordem decrescente.</p>
     */
    private static List<String[]> buildCandidates(
            String requestedRange, String requestedInterval, List<String> permitted) {

        int reqIdx = RANGE_ORDER.indexOf(requestedRange);
        if (reqIdx < 0) reqIdx = 0;

        List<String[]> result = new ArrayList<>();

        if (!permitted.isEmpty()) {
            // Usa os ranges que a Brapi confirmou como disponíveis (maiores primeiro)
            for (String r : RANGE_ORDER) {
                if (!permitted.contains(r)) continue;
                int rIdx = RANGE_ORDER.indexOf(r);
                if (rIdx <= reqIdx) continue; // não regride para ranges maiores ou iguais
                // Mantém interval original se for o mesmo range, senão usa 1d
                String iv = r.equals(requestedRange) ? requestedInterval : "1d";
                result.add(new String[]{r, iv});
            }
        } else {
            // Sem info dos ranges permitidos: tenta todos menores em cascade
            for (int i = reqIdx + 1; i < RANGE_ORDER.size(); i++) {
                result.add(new String[]{RANGE_ORDER.get(i), "1d"});
            }
        }
        return result;
    }

    /**
     * Extrai a lista de ranges permitidos da mensagem de erro da Brapi.
     *
     * <p>Exemplo: "O range \"5y\" não está disponível no seu plano.
     * Ranges permitidos: 1d, 5d, 1mo, 3mo" → ["1d", "5d", "1mo", "3mo"]</p>
     */
    private static List<String> parsePermittedRanges(String errorMsg) {
        if (errorMsg == null) return List.of();
        // Brapi usa "Ranges permitidos:" (pt) ou pode variar; toleramos maiúsculas/minúsculas
        int idx = errorMsg.indexOf("Ranges permitidos:");
        if (idx < 0) idx = errorMsg.indexOf("ranges permitidos:");
        if (idx < 0) return List.of();

        String rest = errorMsg.substring(idx + "Ranges permitidos:".length()).trim();
        // Pega tokens até encontrar ponto final, nova linha ou fim de string
        String[] parts = rest.split("[,\\s\\.\\n;]+");
        List<String> found = new ArrayList<>();
        for (String p : parts) {
            p = p.trim().replaceAll("[^a-zA-Z0-9]", "");
            if (!p.isEmpty() && RANGE_ORDER.contains(p)) found.add(p);
        }
        return found;
    }

    private static boolean isAuthError(IOException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("Token inválido") || msg.contains("sem permissão"));
    }

    // ── HTTP / Parse ──────────────────────────────────────────────────────

    private static List<HistoryPoint> doFetch(String ticker, String range, String interval)
            throws IOException {
        try (Response response = HTTP.newCall(buildRequest(ticker, range, interval)).execute()) {
            if (response.body() == null) throw new IOException("Resposta vazia da API");

            String body = response.body().string();

            if (!response.isSuccessful()) {
                String apiMsg = tryParseErrorMessage(body);
                String detail = apiMsg != null ? apiMsg : "HTTP " + response.code();
                LOG.warning("[AssetHistory] " + response.code() + " for " + ticker
                        + " range=" + range + ": " + detail);
                if (response.code() == 401 || response.code() == 403) {
                    throw new IOException(
                            "Token inválido ou sem permissão para este período. Verifique o token em Configurações.");
                }
                throw new IOException("Dados não disponíveis: " + detail);
            }

            List<HistoryPoint> points = parseHistory(ticker, body);
            if (points.isEmpty()) {
                throw new IOException("Sem dados históricos para este período");
            }
            return points;
        }
    }

    private static Request buildRequest(String ticker, String range, String interval) {
        String token = SETTINGS.get(BrapiClient.SETTINGS_KEY_TOKEN).orElse(null);
        HttpUrl.Builder url = HttpUrl.parse(BASE_URL + "/quote/" + ticker.toUpperCase().trim())
                .newBuilder()
                .addQueryParameter("range", range)
                .addQueryParameter("interval", interval);
        if (token != null && !token.isBlank()) url.addQueryParameter("token", token.trim());
        return new Request.Builder()
                .url(url.build())
                .get()
                .addHeader("User-Agent", "BreakInv/1.0")
                .build();
    }

    private static String tryParseErrorMessage(String body) {
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            if (root.has("message") && !root.get("message").isJsonNull())
                return root.get("message").getAsString();
            if (root.has("error") && root.get("error").isJsonPrimitive()
                    && root.get("error").getAsJsonPrimitive().isString())
                return root.get("error").getAsString();
        } catch (Exception ignored) {}
        return null;
    }

    private static List<HistoryPoint> parseHistory(String ticker, String json) {
        List<HistoryPoint> points = new ArrayList<>();
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);

            if (root.has("error")) {
                String errMsg = tryParseErrorMessage(json);
                LOG.warning("[AssetHistory] error field for " + ticker + ": "
                        + (errMsg != null ? errMsg : json.substring(0, Math.min(json.length(), 200))));
                return points;
            }

            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.isEmpty()) {
                LOG.warning("[AssetHistory] results vazio para " + ticker
                        + " — body: " + json.substring(0, Math.min(json.length(), 200)));
                return points;
            }

            JsonObject result = results.get(0).getAsJsonObject();
            JsonArray hist = result.getAsJsonArray("historicalDataPrice");
            if (hist == null || hist.isEmpty()) {
                LOG.warning("[AssetHistory] historicalDataPrice vazio para " + ticker
                        + " — chaves: " + result.keySet());
                return points;
            }

            int skippedClose = 0, skippedDate = 0;
            for (JsonElement el : hist) {
                JsonObject entry = el.getAsJsonObject();
                double close = getDoubleOrZero(entry, "close");
                if (close <= 0) close = getDoubleOrZero(entry, "adjustedClose");
                if (close <= 0) { skippedClose++; continue; }

                LocalDateTime dt = parseEntryDateTime(entry);
                if (dt == null) { skippedDate++; continue; }

                points.add(new HistoryPoint(dt, close));
            }

            if (skippedClose > 0 || skippedDate > 0) {
                LOG.warning("[AssetHistory] " + ticker + ": " + hist.size() + " entradas, "
                        + skippedClose + " sem close, " + skippedDate + " sem data, "
                        + points.size() + " válidas. Primeira: " + hist.get(0));
            }
        } catch (Exception e) {
            LOG.warning("[AssetHistory] parse error " + ticker + ": " + e.getMessage());
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
                    if (raw > 9_999_999_999L) raw = raw / 1000; // ms → s
                    return Instant.ofEpochSecond(raw)
                            .atZone(ZoneId.of("America/Sao_Paulo"))
                            .toLocalDateTime();
                } else {
                    String s = prim.getAsString();
                    if (s.length() > 10) {
                        return LocalDateTime.parse(s.substring(0, 16),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                    }
                    return LocalDate.parse(s.substring(0, 10), DATE_FMT).atStartOfDay();
                }
            }
        } catch (Exception e) {
            LOG.fine("[AssetHistory] date parse failed: " + dateEl + " — " + e.getMessage());
        }
        return null;
    }

    private static double getDoubleOrZero(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0.0;
        try { return obj.get(key).getAsDouble(); }
        catch (Exception e) { return 0.0; }
    }
}

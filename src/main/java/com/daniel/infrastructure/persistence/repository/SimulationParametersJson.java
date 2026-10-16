package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.Kind;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/**
 * Explicit JSON mapping for {@link SimulationParameters} (column
 * {@code simulation_scenario.parameters_json}). Decimals are written as strings
 * so {@link BigDecimal} values round-trip exactly; unknown keys are ignored.
 *
 * <p>Decoding is strict: integer keys must be exact JSON integers of the right
 * width (no truncation, no wrap, no numeric strings), the keys {@link #toJson}
 * always writes are mandatory, text keys must be JSON strings, {@code rates}
 * must be an object when present and {@code calculationVersion} must be present
 * and equal to the stored column. Any deviation is an
 * {@link IllegalArgumentException} naming the key, which the repository reports
 * as a corrupt row instead of loading a silently different scenario.</p>
 */
final class SimulationParametersJson {

    private SimulationParametersJson() {}

    static String toJson(SimulationParameters p) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", p.kind().name());
        o.addProperty("capitalCents", p.capitalCents());
        o.addProperty("months", p.months());
        putDecimal(o, "annualRate", p.annualRate());
        if (p.benchmark() != null) o.addProperty("benchmark", p.benchmark().name());
        putDecimal(o, "benchmarkPercent", p.benchmarkPercent());
        putDecimal(o, "indexRate", p.indexRate());
        if (p.rates() != null) {
            JsonObject r = new JsonObject();
            putDecimal(r, "cdi", p.rates().cdi());
            putDecimal(r, "selic", p.rates().selic());
            putDecimal(r, "ipca", p.rates().ipca());
            r.addProperty("origin", p.rates().origin());
            if (p.rates().referenceDate() != null) r.addProperty("referenceDate", p.rates().referenceDate());
            o.add("rates", r);
        }
        if (p.ticker() != null) o.addProperty("ticker", p.ticker());
        o.addProperty("purchasePriceCents", p.purchasePriceCents());
        o.addProperty("quantity", p.quantity());
        o.addProperty("currentPriceCents", p.currentPriceCents());
        o.addProperty("dividendPerShareCents", p.dividendPerShareCents());
        putDecimal(o, "priceVariationPercent", p.priceVariationPercent());
        if (p.priceOrigin() != null) o.addProperty("priceOrigin", p.priceOrigin());
        if (p.priceReferenceDate() != null) o.addProperty("priceReferenceDate", p.priceReferenceDate());
        o.addProperty("calculationVersion", p.calculationVersion());
        return o.toString();
    }

    static SimulationParameters fromJson(String json, int storedVersion) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException("parâmetros não são um objeto JSON");
        JsonObject o = root.getAsJsonObject();
        RateSnapshot rates = null;
        if (o.has("rates") && !o.get("rates").isJsonNull()) {
            if (!o.get("rates").isJsonObject()) throw new IllegalArgumentException("chave rates não é um objeto");
            JsonObject r = o.getAsJsonObject("rates");
            rates = new RateSnapshot(decimal(r, "cdi"), decimal(r, "selic"), decimal(r, "ipca"),
                    string(r, "origin"), string(r, "referenceDate"));
        }
        int version = requiredInt(o, "calculationVersion");
        if (version != storedVersion) {
            throw new IllegalArgumentException("calculationVersion " + version + " no JSON difere da coluna (" + storedVersion + ")");
        }
        return new SimulationParameters(
                enumOf(o, "kind", Kind.class),
                requiredLong(o, "capitalCents"),
                requiredInt(o, "months"),
                decimal(o, "annualRate"),
                o.has("benchmark") && !o.get("benchmark").isJsonNull() ? enumOf(o, "benchmark", Benchmark.class) : null,
                decimal(o, "benchmarkPercent"),
                decimal(o, "indexRate"),
                rates,
                string(o, "ticker"),
                requiredLong(o, "purchasePriceCents"),
                requiredInt(o, "quantity"),
                requiredLong(o, "currentPriceCents"),
                requiredLong(o, "dividendPerShareCents"),
                decimal(o, "priceVariationPercent"),
                string(o, "priceOrigin"),
                string(o, "priceReferenceDate"),
                version);
    }

    private static void putDecimal(JsonObject o, String key, BigDecimal v) {
        if (v != null) o.addProperty(key, v.toPlainString());
    }

    /** Decimals are the string contract: a JSON number here is a foreign writer, refused. */
    private static BigDecimal decimal(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        String text = stringPrimitive(e, key);
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("chave " + key + " não é um decimal válido: " + text);
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : stringPrimitive(e, key);
    }

    private static <E extends Enum<E>> E enumOf(JsonObject o, String key, Class<E> type) {
        String name = string(o, key);
        if (name == null) throw new IllegalArgumentException("chave " + key + " ausente");
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("chave " + key + " com valor desconhecido: " + name);
        }
    }

    private static String stringPrimitive(JsonElement e, String key) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("chave " + key + " não é um texto JSON");
        }
        return e.getAsString();
    }

    private static long requiredLong(JsonObject o, String key) {
        try {
            return exactInteger(o, key).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("chave " + key + " não é um inteiro exato dentro do intervalo");
        }
    }

    private static int requiredInt(JsonObject o, String key) {
        try {
            return exactInteger(o, key).intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("chave " + key + " não é um inteiro exato dentro do intervalo");
        }
    }

    /** Present, a JSON number (not a string), parsed by BigDecimal so the exact checks see every digit. */
    private static BigDecimal exactInteger(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new IllegalArgumentException("chave " + key + " ausente");
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("chave " + key + " não é um número JSON");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) throw new IllegalArgumentException("chave " + key + " não é um número JSON");
        try {
            return new BigDecimal(p.getAsString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("chave " + key + " não é um número válido: " + p.getAsString());
        }
    }
}

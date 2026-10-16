package com.daniel.core.domain.entity;

import com.daniel.core.domain.entity.Enums.CategoryEnum;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Persisted allocation plan (RF10 extension). Weights are basis points
 * (1 bp = 0.01%); every {@link CategoryEnum} must be declared explicitly
 * (zero is allowed) and the total must be exactly {@link #TOTAL_BPS} (RN09).
 *
 * @param id        database id, {@code null} while unsaved
 * @param name      unique, non-blank, trimmed
 * @param weightsBps unmodifiable map containing every category
 * @param updatedAt ISO-8601 instant text as persisted, {@code null} while unsaved
 */
public record AllocationPlan(Long id, String name, Map<CategoryEnum, Integer> weightsBps, String updatedAt) {

    public static final int TOTAL_BPS = 10_000;

    public AllocationPlan {
        name = validateName(name);
        weightsBps = validateWeights(weightsBps);
    }

    /** Builds an unsaved plan, validating name and weights. */
    public static AllocationPlan create(String name, Map<CategoryEnum, Integer> weightsBps) {
        return new AllocationPlan(null, name, weightsBps, null);
    }

    public int totalBps() {
        return weightsBps.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Weights as fractions (0.40 for 4000 bps), the shape consumed by the diversification strategy. */
    public Map<CategoryEnum, Double> toProfile() {
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        weightsBps.forEach((cat, bps) -> profile.put(cat, bps / (double) TOTAL_BPS));
        return profile;
    }

    public AllocationPlan withName(String newName) {
        return new AllocationPlan(id, newName, weightsBps, updatedAt);
    }

    public AllocationPlan withWeights(Map<CategoryEnum, Integer> newWeights) {
        return new AllocationPlan(id, name, newWeights, updatedAt);
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Informe um nome para o plano.");
        }
        return name.trim();
    }

    private static Map<CategoryEnum, Integer> validateWeights(Map<CategoryEnum, Integer> weights) {
        if (weights == null) throw new IllegalArgumentException("Pesos do plano ausentes.");
        Map<CategoryEnum, Integer> copy = new EnumMap<>(CategoryEnum.class);
        int total = 0;
        for (CategoryEnum cat : CategoryEnum.values()) {
            Integer bps = weights.get(cat);
            if (bps == null) {
                throw new IllegalArgumentException("Categoria sem peso: " + cat.getDisplayName());
            }
            if (bps < 0 || bps > TOTAL_BPS) {
                throw new IllegalArgumentException("Peso inválido para " + cat.getDisplayName()
                        + ": " + formatPercent(bps) + " (use entre 0% e 100%).");
            }
            copy.put(cat, bps);
            total += bps;
        }
        if (total != TOTAL_BPS) {
            throw new IllegalArgumentException("Os pesos devem somar 100,00% (soma atual: "
                    + formatPercent(total) + ").");
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Formats basis points as a pt-BR percentage with two decimals, e.g. 4000 → "40,00%". */
    public static String formatPercent(int bps) {
        boolean neg = bps < 0;
        int abs = Math.abs(bps);
        return (neg ? "-" : "") + (abs / 100) + "," + String.format("%02d", abs % 100) + "%";
    }
}

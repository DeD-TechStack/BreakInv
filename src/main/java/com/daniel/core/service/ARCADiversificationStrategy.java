package com.daniel.core.service;

import com.daniel.core.domain.entity.Enums.CategoryEnum;

import java.util.*;

public final class ARCADiversificationStrategy {

    private static final Map<CategoryEnum, Double> ARCA_PROFILE = Map.of(
            CategoryEnum.RENDA_FIXA, 0.40,
            CategoryEnum.ACOES, 0.30,
            CategoryEnum.OUTROS, 0.25,
            CategoryEnum.CRIPTOMOEDAS, 0.05
    );

    public record DiversificationSuggestion(
            CategoryEnum category,
            long currentCents,
            long idealCents,
            long differenceCents,
            long aporteNecessarioCents
    ) {}

    /**
     * Calcula sugestões baseadas no metodo ARCA (comportamento legado).
     * Usa patrimônio atual como base ideal — útil para ver a distribuição ideal
     * do que já está investido.
     */
    public static List<DiversificationSuggestion> calculateSuggestions(
            long totalPatrimonyCents,
            Map<CategoryEnum, Long> currentAllocation
    ) {
        List<DiversificationSuggestion> suggestions = new ArrayList<>();

        for (CategoryEnum category : CategoryEnum.values()) {
            long currentCents = currentAllocation.getOrDefault(category, 0L);
            double targetPercentage = ARCA_PROFILE.getOrDefault(category, 0.0);
            long idealCents = Math.round(totalPatrimonyCents * targetPercentage);
            long difference = idealCents - currentCents;
            long aporteNecessario = Math.max(0, difference);
            suggestions.add(new DiversificationSuggestion(
                    category, currentCents, idealCents, difference, aporteNecessario
            ));
        }

        suggestions.sort((a, b) -> Long.compare(b.aporteNecessarioCents(), a.aporteNecessarioCents()));
        return suggestions;
    }

    /**
     * Calcula quanto aportar em cada categoria para equilibrar a carteira.
     * NÃO vende posições — apenas sugere novos aportes.
     *
     * Lógica:
     * 1. Deriva o patrimônio alvo implícito a partir da categoria mais pesada:
     *    para cada categoria com valor > 0, calcula quanto valeria o patrimônio total
     *    se aquela categoria já estivesse no percentual correto. Usa o maior valor.
     * 2. Calcula o ideal de cada categoria com base no alvo implícito.
     * 3. Aporte = max(0, ideal - atual) — nunca vende.
     */
    public static List<DiversificationSuggestion> calculateSuggestionsByContribution(
            long currentPatrimonyCents,
            Map<CategoryEnum, Long> currentAllocation,
            Map<CategoryEnum, Double> targetProfile
    ) {
        long impliedTarget = calculateImpliedTarget(currentPatrimonyCents, currentAllocation, targetProfile);

        List<DiversificationSuggestion> suggestions = new ArrayList<>();
        for (CategoryEnum category : CategoryEnum.values()) {
            long currentCents = currentAllocation.getOrDefault(category, 0L);
            double targetPct  = targetProfile.getOrDefault(category, 0.0);
            long idealCents   = Math.round(impliedTarget * targetPct);
            long diff         = idealCents - currentCents;
            long aporte       = Math.max(0, diff); // nunca vende
            suggestions.add(new DiversificationSuggestion(
                    category, currentCents, idealCents, diff, aporte
            ));
        }
        suggestions.sort((a, b) -> Long.compare(b.aporteNecessarioCents(), a.aporteNecessarioCents()));
        return suggestions;
    }

    /**
     * Deriva o patrimônio alvo implícito a partir da categoria mais pesada da carteira.
     * Para cada categoria com valor > 0 e target% > 0, calcula: valor / target%.
     * Retorna o maior candidato (ou currentPatrimonyCents como fallback).
     */
    public static long calculateImpliedTarget(
            long currentPatrimonyCents,
            Map<CategoryEnum, Long> currentAllocation,
            Map<CategoryEnum, Double> targetProfile
    ) {
        long impliedTarget = currentPatrimonyCents;
        for (CategoryEnum cat : CategoryEnum.values()) {
            long current   = currentAllocation.getOrDefault(cat, 0L);
            double targetPct = targetProfile.getOrDefault(cat, 0.0);
            if (current > 0 && targetPct > 0) {
                long candidate = Math.round(current / targetPct);
                if (candidate > impliedTarget) impliedTarget = candidate;
            }
        }
        return impliedTarget;
    }

    /**
     * Calcula sugestões baseadas em PATRIMÔNIO ALVO
     */
    public static List<DiversificationSuggestion> calculateSuggestionsByTarget(
            long currentPatrimonyCents,
            long targetPatrimonyCents,
            Map<CategoryEnum, Long> currentAllocation,
            Map<CategoryEnum, Double> targetProfile
    ) {
        List<DiversificationSuggestion> suggestions = new ArrayList<>();

        // Calcular quanto aportar no total
        long totalAporteNecessario = targetPatrimonyCents - currentPatrimonyCents;

        if (totalAporteNecessario <= 0) {
            // Se já atingiu o alvo, não precisa aportar
            return calculateSuggestionsByContribution(currentPatrimonyCents, currentAllocation, targetProfile);
        }

        for (CategoryEnum category : CategoryEnum.values()) {
            long currentCents = currentAllocation.getOrDefault(category, 0L);
            double targetPercentage = targetProfile.getOrDefault(category, 0.0);

            // Calcular o valor ideal NO PATRIMÔNIO ALVO
            long idealTargetCents = Math.round(targetPatrimonyCents * targetPercentage);

            // Quanto precisa aportar nesta categoria
            long aporteNecessario = Math.max(0, idealTargetCents - currentCents);

            suggestions.add(new DiversificationSuggestion(
                    category,
                    currentCents,
                    idealTargetCents,
                    idealTargetCents - currentCents,
                    aporteNecessario
            ));
        }

        // Ordenar por aporte necessário
        suggestions.sort((a, b) -> Long.compare(b.aporteNecessarioCents(), a.aporteNecessarioCents()));

        return suggestions;
    }

    public static Map<CategoryEnum, Double> getARCAProfile() {
        return new HashMap<>(ARCA_PROFILE);
    }

    public static boolean isValidProfile(Map<CategoryEnum, Double> profile) {
        double total = profile.values().stream().mapToDouble(Double::doubleValue).sum();
        return Math.abs(total - 1.0) < 0.001;
    }
}
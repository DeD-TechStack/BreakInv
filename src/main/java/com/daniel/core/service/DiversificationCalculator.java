package com.daniel.core.service;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.InvestmentType;

import java.util.*;

public final class DiversificationCalculator {

    public record CategoryAllocation(
            CategoryEnum category,
            long valueCents,
            double percentage
    ) {}

    public record DiversificationData(
            long totalCents,
            Map<CategoryEnum, Long> valuesCents,
            Map<CategoryEnum, Double> percentages,
            List<CategoryAllocation> allocations
    ) {}

    /**
     * Calcula a diversificação atual da carteira
     *
     * @param investments Lista de tipos de investimento
     * @param currentValues Mapa com valores atuais (investmentTypeId → valueCents)
     * @return DiversificationData com distribuição por categoria
     */
    public static DiversificationData calculateCurrent(
            List<InvestmentType> investments,
            Map<Long, Long> currentValues
    ) {
        Map<CategoryEnum, Long> valuesByCategory = new HashMap<>();
        long total = 0L;

        // Agrupar valores por categoria
        for (InvestmentType inv : investments) {
            if (inv.category() == null) continue;

            try {
                CategoryEnum cat = CategoryEnum.valueOf(inv.category());
                long value = currentValues.getOrDefault((long) inv.id(), 0L);

                valuesByCategory.merge(cat, value, Long::sum);
                total += value;
            } catch (IllegalArgumentException e) {
                // Ignorar categorias inválidas
            }
        }

        // Calcular porcentagens
        Map<CategoryEnum, Double> percentages = new HashMap<>();
        List<CategoryAllocation> allocations = new ArrayList<>();

        for (var entry : valuesByCategory.entrySet()) {
            CategoryEnum cat = entry.getKey();
            long value = entry.getValue();
            double percentage = total > 0 ? (value * 100.0 / total) : 0.0;

            percentages.put(cat, percentage);
            allocations.add(new CategoryAllocation(cat, value, percentage));
        }

        // Ordenar por valor (maior primeiro)
        allocations.sort((a, b) -> Long.compare(b.valueCents(), a.valueCents()));

        return new DiversificationData(total, valuesByCategory, percentages, allocations);
    }

    public record CDIComparison(
            long portfolioInitialCents,
            long portfolioCurrentCents,
            long portfolioProfitCents,
            double portfolioRate,
            long cdiProjectedCents,
            long cdiProfitCents,
            double cdiRate,
            double difference,
            boolean outperformsCDI
    ) {}

    /**
     * Compara rentabilidade da carteira com o CDI em um período
     *
     * @param initialValueCents Valor inicial da carteira
     * @param currentValueCents Valor atual da carteira
     * @param months Período em meses
     * @param cdiAnnualRate Taxa anual do CDI (ex: 0.135 = 13.5%)
     * @return CDIComparison com comparação detalhada
     */
    public static CDIComparison compareWithCDI(
            long initialValueCents,
            long currentValueCents,
            int months,
            double cdiAnnualRate
    ) {
        // Calcular rentabilidade da carteira
        long portfolioProfit = currentValueCents - initialValueCents;
        double portfolioRate = initialValueCents > 0
                ? (portfolioProfit * 100.0 / initialValueCents)
                : 0.0;

        // Calcular projeção CDI
        double monthlyRate = Math.pow(1 + cdiAnnualRate, 1.0 / 12) - 1;
        double cdiMultiplier = Math.pow(1 + monthlyRate, months);
        long cdiProjected = Math.round(initialValueCents * cdiMultiplier);
        long cdiProfit = cdiProjected - initialValueCents;
        double cdiRate = initialValueCents > 0
                ? (cdiProfit * 100.0 / initialValueCents)
                : 0.0;

        double difference = portfolioRate - cdiRate;
        boolean outperforms = difference > 0;

        return new CDIComparison(
                initialValueCents,
                currentValueCents,
                portfolioProfit,
                portfolioRate,
                cdiProjected,
                cdiProfit,
                cdiRate,
                difference,
                outperforms
        );
    }

    /**
     * Calcula o patrimônio total somando cash + investimentos
     */
    public static long calculateTotalPatrimony(long cashCents, Map<Long, Long> investmentValues) {
        long total = cashCents;
        for (long value : investmentValues.values()) {
            total += value;
        }
        return total;
    }

    /**
     * Formata porcentagem para exibição
     */
    public static String formatPercentage(double percentage) {
        return String.format("%.1f%%", percentage);
    }
}
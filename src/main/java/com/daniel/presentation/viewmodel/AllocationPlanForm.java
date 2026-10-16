package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Text ↔ domain conversion for the allocation-plan editor. Percentages are typed
 * with up to two decimals (pt-BR comma or dot) and stored as basis points, so the
 * declared precision is 0,01% and rounding never happens silently. No JavaFX here.
 */
public final class AllocationPlanForm {

    private AllocationPlanForm() {}

    /** "40,5" → 4050; blank/null → 0. Rejects more than two decimals or non-numeric text. */
    public static int parsePercentToBps(String text) {
        if (text == null) return 0;
        String s = text.replace("%", "").replace(" ", "").replace("\u00A0", "").trim();
        if (s.isEmpty()) return 0;
        if (s.chars().filter(ch -> ch == ',' || ch == '.').count() > 1) {
            throw new IllegalArgumentException("Percentual inválido: " + text.trim());
        }
        s = s.replace(',', '.');
        BigDecimal value;
        try {
            value = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Percentual inválido: " + text.trim());
        }
        if (value.scale() > 2) {
            throw new IllegalArgumentException("Use no máximo duas casas decimais: " + text.trim());
        }
        return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
    }

    /** 4050 → "40,50". */
    public static String formatBps(int bps) {
        return BigDecimal.valueOf(bps).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString().replace('.', ',');
    }

    /** Builds a validated plan; messages name the offending category or the current total. */
    public static AllocationPlan toPlan(Long id, String name, Map<CategoryEnum, String> texts) {
        Map<CategoryEnum, Integer> weights = new EnumMap<>(CategoryEnum.class);
        for (CategoryEnum cat : CategoryEnum.values()) {
            try {
                weights.put(cat, parsePercentToBps(texts.get(cat)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(cat.getDisplayName() + ": " + e.getMessage());
            }
        }
        return new AllocationPlan(id, name, weights, null);
    }

    /** Live total for the editor footer; unparseable fields count as 0 instead of failing. */
    public static int sumBps(Map<CategoryEnum, String> texts) {
        int total = 0;
        for (String t : texts.values()) {
            try {
                total += parsePercentToBps(t);
            } catch (IllegalArgumentException ignored) {
                // preview only
            }
        }
        return total;
    }
}

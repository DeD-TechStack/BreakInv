package com.daniel.core.service;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ARCADiversificationStrategyTest {

    // ===== getARCAProfile =====

    @Test
    void arcaProfile_sumsToOne() {
        Map<CategoryEnum, Double> profile = ARCADiversificationStrategy.getARCAProfile();
        assertTrue(ARCADiversificationStrategy.isValidProfile(profile));
    }

    @Test
    void arcaProfile_containsExpectedCategories() {
        Map<CategoryEnum, Double> profile = ARCADiversificationStrategy.getARCAProfile();
        assertTrue(profile.containsKey(CategoryEnum.RENDA_FIXA));
        assertTrue(profile.containsKey(CategoryEnum.ACOES));
        assertTrue(profile.containsKey(CategoryEnum.OUTROS));
        assertTrue(profile.containsKey(CategoryEnum.CRIPTOMOEDAS));
    }

    @Test
    void arcaProfile_rendaFixa_isFortyPercent() {
        Map<CategoryEnum, Double> profile = ARCADiversificationStrategy.getARCAProfile();
        assertEquals(0.40, profile.get(CategoryEnum.RENDA_FIXA), 0.001);
    }

    // ===== isValidProfile =====

    @Test
    void isValidProfile_exactlyOne_returnsTrue() {
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 0.60);
        profile.put(CategoryEnum.ACOES, 0.40);
        assertTrue(ARCADiversificationStrategy.isValidProfile(profile));
    }

    @Test
    void isValidProfile_tooHigh_returnsFalse() {
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 0.60);
        profile.put(CategoryEnum.ACOES, 0.50);
        assertFalse(ARCADiversificationStrategy.isValidProfile(profile));
    }

    @Test
    void isValidProfile_tooLow_returnsFalse() {
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 0.30);
        profile.put(CategoryEnum.ACOES, 0.20);
        assertFalse(ARCADiversificationStrategy.isValidProfile(profile));
    }

    @Test
    void isValidProfile_withinToleranceOfOneThousandth_returnsTrue() {
        // sum = 0.9995 — inside the 0.001 tolerance
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 0.5995);
        profile.put(CategoryEnum.ACOES, 0.40);
        assertTrue(ARCADiversificationStrategy.isValidProfile(profile));
    }

    // ===== calculateSuggestions (ARCA default profile) =====

    @Test
    void calculateSuggestions_noAllocation_allCategoriesNeedContribution() {
        var suggestions = ARCADiversificationStrategy.calculateSuggestions(100000L, Map.of());

        var rf = findCategory(suggestions, CategoryEnum.RENDA_FIXA);
        assertNotNull(rf);
        assertEquals(40000L, rf.aporteNecessarioCents()); // 40% of 100000

        var ac = findCategory(suggestions, CategoryEnum.ACOES);
        assertNotNull(ac);
        assertEquals(30000L, ac.aporteNecessarioCents()); // 30% of 100000
    }

    @Test
    void calculateSuggestions_sortedByAporteDescending() {
        var suggestions = ARCADiversificationStrategy.calculateSuggestions(100000L, Map.of());
        for (int i = 0; i < suggestions.size() - 1; i++) {
            assertTrue(
                    suggestions.get(i).aporteNecessarioCents() >= suggestions.get(i + 1).aporteNecessarioCents(),
                    "Suggestions not sorted by aporte descending"
            );
        }
    }

    @Test
    void calculateSuggestions_categoryAtIdeal_zeroAporte() {
        // RENDA_FIXA already at exactly 40%
        Map<CategoryEnum, Long> alloc = new EnumMap<>(CategoryEnum.class);
        alloc.put(CategoryEnum.RENDA_FIXA, 40000L);

        var suggestions = ARCADiversificationStrategy.calculateSuggestions(100000L, alloc);

        var rf = findCategory(suggestions, CategoryEnum.RENDA_FIXA);
        assertNotNull(rf);
        assertEquals(0L, rf.aporteNecessarioCents());
    }

    @Test
    void calculateSuggestions_categoryAboveIdeal_zeroAporte() {
        // RENDA_FIXA at 80% — above ideal 40%, should not need negative rebalance
        Map<CategoryEnum, Long> alloc = new EnumMap<>(CategoryEnum.class);
        alloc.put(CategoryEnum.RENDA_FIXA, 80000L);

        var suggestions = ARCADiversificationStrategy.calculateSuggestions(100000L, alloc);

        var rf = findCategory(suggestions, CategoryEnum.RENDA_FIXA);
        assertNotNull(rf);
        assertEquals(0L, rf.aporteNecessarioCents());
    }

    // ===== calculateSuggestionsByTarget =====

    @Test
    void calculateSuggestionsByTarget_belowTarget_calculatesAportePerCategory() {
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 1.0);

        Map<CategoryEnum, Long> current = Map.of();
        var suggestions = ARCADiversificationStrategy.calculateSuggestionsByTarget(
                50000L, 100000L, current, profile
        );

        var rf = findCategory(suggestions, CategoryEnum.RENDA_FIXA);
        assertNotNull(rf);
        // Ideal at target = 100000 * 1.0 = 100000; current = 0; aporte = 100000
        assertEquals(100000L, rf.aporteNecessarioCents());
    }

    @Test
    void calculateSuggestionsByTarget_alreadyAtTarget_delegatesToContribution() {
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 1.0);

        Map<CategoryEnum, Long> alloc = new EnumMap<>(CategoryEnum.class);
        alloc.put(CategoryEnum.RENDA_FIXA, 200000L);

        // current >= target → delegates to contribution (aporte = 0)
        var suggestions = ARCADiversificationStrategy.calculateSuggestionsByTarget(
                200000L, 100000L, alloc, profile
        );

        suggestions.forEach(s ->
                assertEquals(0L, s.aporteNecessarioCents(),
                        "Expected 0 aporte for " + s.category())
        );
    }

    // ===== calculateSuggestionsByContribution =====

    @Test
    void calculateSuggestionsByContribution_unbalanced_suggestsDeficitCategory() {
        // 100k patrimony: all in RF, nothing in ACOES. Profile 50/50.
        // Should suggest aportar ~75k in ACOES and 0 in RF.
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.ACOES, 0.50);
        profile.put(CategoryEnum.RENDA_FIXA, 0.50);

        Map<CategoryEnum, Long> alloc = new EnumMap<>(CategoryEnum.class);
        alloc.put(CategoryEnum.RENDA_FIXA, 100000L);
        alloc.put(CategoryEnum.ACOES, 0L);

        var suggestions = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                100000L, alloc, profile
        );

        var rf  = findCategory(suggestions, CategoryEnum.RENDA_FIXA);
        var ac  = findCategory(suggestions, CategoryEnum.ACOES);
        assertNotNull(rf);
        assertNotNull(ac);
        assertEquals(0L, rf.aporteNecessarioCents()); // RF is above ideal, no aporte
        assertTrue(ac.aporteNecessarioCents() > 0,    // ACOES is below ideal, needs aporte
                "Expected positive aporte for ACOES");
    }

    @Test
    void calculateSuggestionsByContribution_noNegativeAportes() {
        // RF is way above ideal; aportes should never be negative
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.RENDA_FIXA, 0.10);

        Map<CategoryEnum, Long> alloc = new EnumMap<>(CategoryEnum.class);
        alloc.put(CategoryEnum.RENDA_FIXA, 90000L); // way above 10%

        var suggestions = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                100000L, alloc, profile
        );

        suggestions.forEach(s ->
                assertTrue(s.aporteNecessarioCents() >= 0,
                        "Negative aporte for " + s.category())
        );
    }

    @Test
    void calculateSuggestionsByContribution_balanced_yieldsZeroAportes() {
        // Portfolio perfectly balanced 50/50 with 100k → no aportes needed
        Map<CategoryEnum, Double> profile = new EnumMap<>(CategoryEnum.class);
        profile.put(CategoryEnum.ACOES, 0.50);
        profile.put(CategoryEnum.RENDA_FIXA, 0.50);

        Map<CategoryEnum, Long> alloc = new EnumMap<>(CategoryEnum.class);
        alloc.put(CategoryEnum.ACOES, 50000L);
        alloc.put(CategoryEnum.RENDA_FIXA, 50000L);

        var suggestions = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                100000L, alloc, profile
        );

        long totalAporte = suggestions.stream().mapToLong(s -> s.aporteNecessarioCents()).sum();
        assertEquals(0L, totalAporte, "Balanced portfolio should need no aporte");
    }

    // ===== Helper =====

    private ARCADiversificationStrategy.DiversificationSuggestion findCategory(
            List<ARCADiversificationStrategy.DiversificationSuggestion> list,
            CategoryEnum category
    ) {
        return list.stream()
                .filter(s -> s.category() == category)
                .findFirst()
                .orElse(null);
    }
}

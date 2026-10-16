package com.daniel.core.domain.entity;

import com.daniel.core.domain.entity.Enums.CategoryEnum;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain rules for a persisted allocation plan (RF10 extension, RN09):
 * every category is declared explicitly (0 allowed), weights are basis points
 * in 0..10000 and the plan totals exactly 10000 bps (100.00%).
 */
class AllocationPlanTest {

    static Map<CategoryEnum, Integer> arcaWeights() {
        Map<CategoryEnum, Integer> w = new EnumMap<>(CategoryEnum.class);
        for (CategoryEnum c : CategoryEnum.values()) w.put(c, 0);
        w.put(CategoryEnum.RENDA_FIXA, 4000);
        w.put(CategoryEnum.ACOES, 3000);
        w.put(CategoryEnum.OUTROS, 2500);
        w.put(CategoryEnum.CRIPTOMOEDAS, 500);
        return w;
    }

    @Test
    void newPlan_allCategoriesTotalling10000_isValidAndUnsaved() {
        AllocationPlan plan = AllocationPlan.create("Meu ARCA", arcaWeights());

        assertNull(plan.id(), "unsaved plan has no id");
        assertEquals("Meu ARCA", plan.name());
        assertEquals(7, plan.weightsBps().size());
        assertEquals(10000, plan.totalBps());
        assertEquals(0, plan.weightsBps().get(CategoryEnum.FUNDOS));
    }

    @Test
    void toProfile_convertsBasisPointsToFractions() {
        AllocationPlan plan = AllocationPlan.create("ARCA", arcaWeights());

        Map<CategoryEnum, Double> profile = plan.toProfile();

        assertEquals(0.40, profile.get(CategoryEnum.RENDA_FIXA), 1e-12);
        assertEquals(0.05, profile.get(CategoryEnum.CRIPTOMOEDAS), 1e-12);
        assertEquals(0.0, profile.get(CategoryEnum.PREVIDENCIA), 1e-12);
        assertEquals(1.0, profile.values().stream().mapToDouble(d -> d).sum(), 1e-12);
    }

    @Test
    void create_missingCategory_rejected() {
        Map<CategoryEnum, Integer> w = arcaWeights();
        w.remove(CategoryEnum.FUNDOS);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AllocationPlan.create("Incompleto", w));
        assertTrue(ex.getMessage().contains("Fundos de Investimento"), ex.getMessage());
    }

    @Test
    void create_totalNot10000_rejected() {
        Map<CategoryEnum, Integer> w = arcaWeights();
        w.put(CategoryEnum.CRIPTOMOEDAS, 600); // 10100

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AllocationPlan.create("Errado", w));
        assertTrue(ex.getMessage().contains("101"), ex.getMessage());
    }

    @Test
    void create_negativeWeight_rejected() {
        Map<CategoryEnum, Integer> w = arcaWeights();
        w.put(CategoryEnum.RENDA_FIXA, 4500);
        w.put(CategoryEnum.CRIPTOMOEDAS, 0);
        w.put(CategoryEnum.FUNDOS, -500); // still totals 10000

        assertThrows(IllegalArgumentException.class, () -> AllocationPlan.create("Negativo", w));
    }

    @Test
    void create_blankName_rejected() {
        assertThrows(IllegalArgumentException.class, () -> AllocationPlan.create("   ", arcaWeights()));
        assertThrows(IllegalArgumentException.class, () -> AllocationPlan.create(null, arcaWeights()));
    }

    @Test
    void create_trimsNameAndCopiesWeights() {
        Map<CategoryEnum, Integer> w = arcaWeights();
        AllocationPlan plan = AllocationPlan.create("  Conservador  ", w);
        w.put(CategoryEnum.RENDA_FIXA, 0); // mutate source after creation

        assertEquals("Conservador", plan.name());
        assertEquals(4000, plan.weightsBps().get(CategoryEnum.RENDA_FIXA));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.weightsBps().put(CategoryEnum.RENDA_FIXA, 1));
    }

    @Test
    void withName_keepsIdAndWeights() {
        AllocationPlan saved = new AllocationPlan(7L, "A", arcaWeights(), "2026-10-15T10:00:00Z");
        AllocationPlan renamed = saved.withName("B");

        assertEquals(7L, renamed.id());
        assertEquals("B", renamed.name());
        assertEquals(saved.weightsBps(), renamed.weightsBps());
    }
}

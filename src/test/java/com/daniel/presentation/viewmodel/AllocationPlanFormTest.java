package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Converts the plan editor's percentage text fields (pt-BR, two decimals) to an
 * {@link AllocationPlan} and back, without touching JavaFX.
 */
class AllocationPlanFormTest {

    static Map<CategoryEnum, String> texts(String rf, String acoes, String fii, String fundos, String prev, String cripto, String outros) {
        Map<CategoryEnum, String> t = new EnumMap<>(CategoryEnum.class);
        t.put(CategoryEnum.RENDA_FIXA, rf);
        t.put(CategoryEnum.ACOES, acoes);
        t.put(CategoryEnum.FUNDOS_IMOBILIARIOS, fii);
        t.put(CategoryEnum.FUNDOS, fundos);
        t.put(CategoryEnum.PREVIDENCIA, prev);
        t.put(CategoryEnum.CRIPTOMOEDAS, cripto);
        t.put(CategoryEnum.OUTROS, outros);
        return t;
    }

    @Test
    void parsePercent_acceptsCommaDotAndBlank() {
        assertEquals(4000, AllocationPlanForm.parsePercentToBps("40"));
        assertEquals(4050, AllocationPlanForm.parsePercentToBps("40,5"));
        assertEquals(4050, AllocationPlanForm.parsePercentToBps("40.50"));
        assertEquals(0, AllocationPlanForm.parsePercentToBps(""));
        assertEquals(0, AllocationPlanForm.parsePercentToBps(null));
        assertEquals(1234, AllocationPlanForm.parsePercentToBps(" 12,34 % "));
    }

    @Test
    void parsePercent_rejectsMoreThanTwoDecimalsAndGarbage() {
        assertThrows(IllegalArgumentException.class, () -> AllocationPlanForm.parsePercentToBps("12,345"));
        assertThrows(IllegalArgumentException.class, () -> AllocationPlanForm.parsePercentToBps("abc"));
        assertThrows(IllegalArgumentException.class, () -> AllocationPlanForm.parsePercentToBps("1,2,3"));
    }

    @Test
    void toPlan_buildsValidPlanFromTexts() {
        AllocationPlan plan = AllocationPlanForm.toPlan(null, "Equilibrado",
                texts("40", "30", "10", "5", "5", "5", "5"));

        assertEquals("Equilibrado", plan.name());
        assertEquals(4000, plan.weightsBps().get(CategoryEnum.RENDA_FIXA));
        assertEquals(500, plan.weightsBps().get(CategoryEnum.OUTROS));
        assertNull(plan.id());
    }

    @Test
    void toPlan_keepsIdWhenEditing() {
        AllocationPlan plan = AllocationPlanForm.toPlan(9L, "Editado", texts("100", "", "", "", "", "", ""));
        assertEquals(9L, plan.id());
    }

    @Test
    void toPlan_invalidTotal_reportsFieldAwareMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AllocationPlanForm.toPlan(null, "Errado", texts("40", "30", "10", "5", "5", "5", "0")));
        assertTrue(ex.getMessage().contains("95,00%"), ex.getMessage());
    }

    @Test
    void toPlan_invalidNumber_namesTheCategory() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AllocationPlanForm.toPlan(null, "Errado", texts("40", "x", "10", "5", "5", "5", "35")));
        assertTrue(ex.getMessage().contains("Ações"), ex.getMessage());
    }

    @Test
    void formatBps_rendersTwoDecimalsPtBr() {
        assertEquals("40,00", AllocationPlanForm.formatBps(4000));
        assertEquals("0,50", AllocationPlanForm.formatBps(50));
        assertEquals("12,34", AllocationPlanForm.formatBps(1234));
    }

    @Test
    void sumBps_toleratesInvalidFieldsForLivePreview() {
        assertEquals(7000, AllocationPlanForm.sumBps(texts("40", "30", "x", "", "", "", "")));
    }
}

package com.daniel.core.service;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.service.ARCADiversificationStrategy.DiversificationSuggestion;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A persisted plan's profile drives the existing diversification strategy exactly like
 * the built-in ARCA profile, so two different saved plans yield two different analyses
 * (RF10 extension acceptance), and the ARCA plan expressed in bps reproduces ARCA.
 */
class AllocationPlanStrategyTest {

    static Map<CategoryEnum, Integer> weights(int rf, int acoes, int fii, int fundos, int prev, int cripto, int outros) {
        Map<CategoryEnum, Integer> w = new EnumMap<>(CategoryEnum.class);
        w.put(CategoryEnum.RENDA_FIXA, rf);
        w.put(CategoryEnum.ACOES, acoes);
        w.put(CategoryEnum.FUNDOS_IMOBILIARIOS, fii);
        w.put(CategoryEnum.FUNDOS, fundos);
        w.put(CategoryEnum.PREVIDENCIA, prev);
        w.put(CategoryEnum.CRIPTOMOEDAS, cripto);
        w.put(CategoryEnum.OUTROS, outros);
        return w;
    }

    static Map<CategoryEnum, Long> portfolio() {
        Map<CategoryEnum, Long> current = new EnumMap<>(CategoryEnum.class);
        current.put(CategoryEnum.RENDA_FIXA, 60_000_00L);
        current.put(CategoryEnum.ACOES, 40_000_00L);
        return current;
    }

    static long aporteFor(List<DiversificationSuggestion> s, CategoryEnum c) {
        return s.stream().filter(x -> x.category() == c).findFirst().orElseThrow().aporteNecessarioCents();
    }

    @Test
    void arcaPlanInBps_reproducesBuiltInArcaSuggestions() {
        AllocationPlan arca = AllocationPlan.create("ARCA", weights(4000, 3000, 0, 0, 0, 500, 2500));

        List<DiversificationSuggestion> fromPlan = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                100_000_00L, portfolio(), arca.toProfile());
        List<DiversificationSuggestion> builtIn = ARCADiversificationStrategy.calculateSuggestionsByContribution(
                100_000_00L, portfolio(), ARCADiversificationStrategy.getARCAProfile());

        assertEquals(builtIn, fromPlan);
        assertTrue(ARCADiversificationStrategy.isValidProfile(arca.toProfile()));
    }

    @Test
    void twoDifferentPlans_yieldDifferentSuggestions() {
        AllocationPlan conservador = AllocationPlan.create("Conservador", weights(8000, 2000, 0, 0, 0, 0, 0));
        AllocationPlan arrojado = AllocationPlan.create("Arrojado", weights(2000, 8000, 0, 0, 0, 0, 0));

        var c = ARCADiversificationStrategy.calculateSuggestionsByContribution(100_000_00L, portfolio(), conservador.toProfile());
        var a = ARCADiversificationStrategy.calculateSuggestionsByContribution(100_000_00L, portfolio(), arrojado.toProfile());

        // Conservador: ações 40k implies target 200k → RF ideal 160k → aportar 100k em RF, 0 em ações
        assertEquals(100_000_00L, aporteFor(c, CategoryEnum.RENDA_FIXA));
        assertEquals(0L, aporteFor(c, CategoryEnum.ACOES));
        // Arrojado: RF 60k implies target 300k → ações ideal 240k → aportar 200k em ações, 0 em RF
        assertEquals(0L, aporteFor(a, CategoryEnum.RENDA_FIXA));
        assertEquals(200_000_00L, aporteFor(a, CategoryEnum.ACOES));
    }

    @Test
    void suggestionsAreInformativeOnly_neverNegativeContributions() {
        AllocationPlan plan = AllocationPlan.create("Só RF", weights(10000, 0, 0, 0, 0, 0, 0));

        var s = ARCADiversificationStrategy.calculateSuggestionsByContribution(100_000_00L, portfolio(), plan.toProfile());

        assertTrue(s.stream().allMatch(x -> x.aporteNecessarioCents() >= 0), "no sell suggestions (RN10)");
    }
}

package com.daniel.core.service;

import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.domain.entity.InvestmentType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiversificationCalculatorTest {

    // ===== calculateCurrent =====

    @Test
    void calculateCurrent_singleCategory_hundredPercent() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro Selic", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        Map<Long, Long> values = Map.of(1L, 100000L);

        var data = DiversificationCalculator.calculateCurrent(List.of(inv), values);

        assertEquals(100000L, data.totalCents());
        assertEquals(1, data.allocations().size());
        assertEquals(100.0, data.percentages().get(CategoryEnum.RENDA_FIXA), 0.001);
    }

    @Test
    void calculateCurrent_twoCategories_correctSplit() {
        InvestmentType rf = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        InvestmentType ac = new InvestmentType(
                2, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.of(2023, 1, 1), BigDecimal.valueOf(0.05), BigDecimal.valueOf(500)
        );
        Map<Long, Long> values = Map.of(1L, 60000L, 2L, 40000L); // 60/40

        var data = DiversificationCalculator.calculateCurrent(List.of(rf, ac), values);

        assertEquals(100000L, data.totalCents());
        assertEquals(60.0, data.percentages().get(CategoryEnum.RENDA_FIXA), 0.001);
        assertEquals(40.0, data.percentages().get(CategoryEnum.ACOES), 0.001);
    }

    @Test
    void calculateCurrent_nullCategory_skipped() {
        InvestmentType inv = new InvestmentType(1, "Unknown");
        Map<Long, Long> values = Map.of(1L, 50000L);

        var data = DiversificationCalculator.calculateCurrent(List.of(inv), values);

        assertEquals(0L, data.totalCents()); // nothing counted
        assertTrue(data.allocations().isEmpty());
    }

    @Test
    void calculateCurrent_invalidCategoryString_skipped() {
        InvestmentType inv = new InvestmentType(
                1, "Bad", "NOT_A_CATEGORY", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.10), BigDecimal.valueOf(1000)
        );
        Map<Long, Long> values = Map.of(1L, 50000L);

        var data = DiversificationCalculator.calculateCurrent(List.of(inv), values);

        assertEquals(0L, data.totalCents());
    }

    @Test
    void calculateCurrent_zeroTotal_zeroPercent() {
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        Map<Long, Long> values = Map.of(1L, 0L);

        var data = DiversificationCalculator.calculateCurrent(List.of(inv), values);

        assertEquals(0L, data.totalCents());
        assertEquals(0.0, data.percentages().getOrDefault(CategoryEnum.RENDA_FIXA, 0.0), 0.001);
    }

    @Test
    void calculateCurrent_allocations_sortedByValueDescending() {
        InvestmentType rf = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        InvestmentType ac = new InvestmentType(
                2, "PETR4", "ACOES", "MUITO_ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.05), BigDecimal.valueOf(500)
        );
        Map<Long, Long> values = Map.of(1L, 30000L, 2L, 70000L); // ACOES is bigger

        var data = DiversificationCalculator.calculateCurrent(List.of(rf, ac), values);

        assertEquals(CategoryEnum.ACOES, data.allocations().get(0).category());
        assertEquals(CategoryEnum.RENDA_FIXA, data.allocations().get(1).category());
    }

    // ===== compareWithCDI =====

    @Test
    void compareWithCDI_portfolioOutperforms() {
        // Portfolio: +20%, CDI 13.5% annual for 12 months — portfolio wins
        var result = DiversificationCalculator.compareWithCDI(100000L, 120000L, 12, 0.135);
        assertTrue(result.outperformsCDI());
        assertEquals(20000L, result.portfolioProfitCents());
        assertEquals(20.0, result.portfolioRate(), 0.001);
    }

    @Test
    void compareWithCDI_portfolioUnderperforms() {
        // Portfolio: +5%, CDI 13.5% — CDI wins
        var result = DiversificationCalculator.compareWithCDI(100000L, 105000L, 12, 0.135);
        assertFalse(result.outperformsCDI());
        assertTrue(result.difference() < 0);
    }

    @Test
    void compareWithCDI_zeroInitialValue_noDivision() {
        // Should not throw — portfolioRate and cdiRate both default to 0
        var result = DiversificationCalculator.compareWithCDI(0L, 0L, 12, 0.135);
        assertEquals(0.0, result.portfolioRate(), 0.001);
        assertEquals(0.0, result.cdiRate(), 0.001);
    }

    @Test
    void compareWithCDI_zeroMonths_cdiMultiplierOne() {
        // 0 months → CDI projected = initial (no growth)
        var result = DiversificationCalculator.compareWithCDI(100000L, 105000L, 0, 0.135);
        assertEquals(100000L, result.cdiProjectedCents());
        assertEquals(0L, result.cdiProfitCents());
    }

    // ===== calculateCurrent — additional edge cases =====

    @Test
    void calculateCurrent_emptyInvestmentList_zeroTotal() {
        var data = DiversificationCalculator.calculateCurrent(List.of(), Map.of());
        assertEquals(0L, data.totalCents());
        assertTrue(data.allocations().isEmpty());
        assertTrue(data.percentages().isEmpty());
    }

    @Test
    void calculateCurrent_twoInvestmentsSameCategory_aggregated() {
        InvestmentType t1 = new InvestmentType(
                1, "Tesouro Selic", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        InvestmentType t2 = new InvestmentType(
                2, "LCI", "RENDA_FIXA", "MEDIA",
                LocalDate.now(), BigDecimal.valueOf(0.10), BigDecimal.valueOf(500)
        );
        Map<Long, Long> values = Map.of(1L, 40000L, 2L, 60000L);

        var data = DiversificationCalculator.calculateCurrent(List.of(t1, t2), values);

        // Both RENDA_FIXA → aggregated to 100000
        assertEquals(100000L, data.totalCents());
        assertEquals(1, data.allocations().size());
        assertEquals(100000L, data.valuesCents().get(CategoryEnum.RENDA_FIXA));
        assertEquals(100.0, data.percentages().get(CategoryEnum.RENDA_FIXA), 0.001);
    }

    @Test
    void calculateCurrent_missingInMapDefaultsToZero() {
        // Investment exists but has no entry in currentValues map
        InvestmentType inv = new InvestmentType(
                1, "Tesouro", "RENDA_FIXA", "ALTA",
                LocalDate.now(), BigDecimal.valueOf(0.12), BigDecimal.valueOf(1000)
        );
        // Empty values map — investment contributes 0
        var data = DiversificationCalculator.calculateCurrent(List.of(inv), Map.of());

        assertEquals(0L, data.totalCents());
    }

    // ===== compareWithCDI — additional cases =====

    @Test
    void compareWithCDI_exactParity_notOutperforming() {
        // Portfolio and CDI at exact same rate for 12 months
        double annualRate = 0.135;
        long initial = 100000L;
        // Calculate exactly what CDI would produce
        double monthlyRate = Math.pow(1 + annualRate, 1.0 / 12) - 1;
        long cdiProjected = Math.round(initial * Math.pow(1 + monthlyRate, 12));

        var result = DiversificationCalculator.compareWithCDI(initial, cdiProjected, 12, annualRate);

        // difference should be ~0, not outperforming
        assertEquals(0.0, result.difference(), 0.1);
        assertFalse(result.outperformsCDI());
    }

    @Test
    void compareWithCDI_singleMonth_cdiCalculation() {
        // 1 month at 1% effective monthly rate
        double annualRate = Math.pow(1.01, 12) - 1; // ~12.68% annual
        long initial = 100000L;
        var result = DiversificationCalculator.compareWithCDI(initial, initial, 1, annualRate);

        // CDI projected ≈ 101000 for 1 month
        assertTrue(result.cdiProjectedCents() > initial);
        assertTrue(result.cdiProfitCents() > 0);
    }

}

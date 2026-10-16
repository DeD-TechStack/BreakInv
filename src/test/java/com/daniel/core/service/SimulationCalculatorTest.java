package com.daniel.core.service;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.daniel.core.service.SimulationCalculator.Projection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic projection from persisted parameters only (RF13, RN12).
 * Money is integer cents; rates are fractions. No network, no clock.
 */
class SimulationCalculatorTest {

    static RateSnapshot frozen(String cdi) {
        return new RateSnapshot(new BigDecimal(cdi), new BigDecimal("0.15"), new BigDecimal("0.045"), "teste", "2026-01-01");
    }

    @Test
    void fixedRate_12monthsAt12percent_returnsCapitalTimesOnePointTwelve() {
        SimulationParameters p = SimulationParameters.fixedRate(1_000_000L, 12, new BigDecimal("0.12"));

        Projection r = SimulationCalculator.project(p);

        // (1+0.12)^(12/12) = 1.12 → R$ 11.200,00
        assertEquals(1_120_000L, r.finalCents());
        assertEquals(120_000L, r.profitCents());
        assertEquals(1_000_000L, r.investedCents());
        assertEquals(12.0, r.returnPercent(), 1e-9);
        assertEquals(13, r.seriesCents().size(), "month 0..12");
        assertEquals(1_000_000L, r.seriesCents().get(0));
        assertEquals(1_120_000L, r.seriesCents().get(12));
    }

    @Test
    void fixedRate_6monthsAt12percent_usesMonthlyCompounding() {
        SimulationParameters p = SimulationParameters.fixedRate(1_000_000L, 6, new BigDecimal("0.12"));

        Projection r = SimulationCalculator.project(p);

        // 1.12^(0.5) = 1.058300524… → 1.058.301 cents (rounded half up)
        assertEquals(1_058_301L, r.finalCents());
    }

    @Test
    void benchmark_usesFrozenSnapshotNotCurrentRate() {
        SimulationParameters p = SimulationParameters.benchmark(1_000_000L, 12, Benchmark.CDI,
                new BigDecimal("1.10"), frozen("0.10"));

        Projection r = SimulationCalculator.project(p);

        // 110% of 10% = 11% a.a. → R$ 11.100,00 regardless of any "today" rate
        assertEquals(1_110_000L, r.finalCents());
        assertEquals(new BigDecimal("0.11").doubleValue(), r.effectiveAnnualRate(), 1e-12);
    }

    @Test
    void hybrid_compoundsIndexAndFixedRate() {
        SimulationParameters p = SimulationParameters.hybrid(1_000_000L, 12, new BigDecimal("0.05"), new BigDecimal("0.04"));

        Projection r = SimulationCalculator.project(p);

        // (1.04 × 1.05) − 1 = 0.092 → R$ 10.920,00
        assertEquals(1_092_000L, r.finalCents());
    }

    @Test
    void stock_dividendsPerShareAreMultipliedByQuantity() {
        // 100 shares bought at R$ 35,50; current price R$ 40,00; dividends R$ 2,50 per share; no variation
        SimulationParameters p = SimulationParameters.stock("PETR4", 3550L, 100, 4000L, 250L,
                BigDecimal.ZERO, "manual", null);

        Projection r = SimulationCalculator.project(p);

        assertEquals(355_000L, r.investedCents());
        assertEquals(400_000L, r.finalCents(), "position value without dividends");
        assertEquals(25_000L, r.dividendsCents(), "R$ 2,50 × 100 — not R$ 2,50");
        assertEquals(400_000L + 25_000L - 355_000L, r.profitCents(), "total profit includes dividends × qty");
        assertEquals((45_000L + 25_000L) * 100.0 / 355_000L, r.returnPercent(), 1e-9);
        assertEquals(2, r.seriesCents().size());
        assertEquals(355_000L, r.seriesCents().get(0));
        assertEquals(425_000L, r.seriesCents().get(1));
    }

    @Test
    void stock_priceVariationAppliedToCurrentPrice() {
        SimulationParameters p = SimulationParameters.stock("X", 1000L, 10, 2000L, 0L,
                new BigDecimal("-10"), "manual", null);

        Projection r = SimulationCalculator.project(p);

        // 20,00 × 0.9 = 18,00 × 10 = 180,00
        assertEquals(18_000L, r.finalCents());
        assertEquals(8_000L, r.profitCents());
    }

    @Test
    void stock_withoutCurrentPrice_fallsBackToPurchasePrice() {
        SimulationParameters p = SimulationParameters.stock("X", 1000L, 10, 0L, 0L, BigDecimal.ZERO, "manual", null);

        Projection r = SimulationCalculator.project(p);

        assertEquals(10_000L, r.finalCents());
        assertEquals(0L, r.profitCents());
        assertEquals(0.0, r.returnPercent(), 1e-12);
    }

    @Test
    void sameParameters_alwaysSameResult() {
        SimulationParameters p = SimulationParameters.benchmark(123_456L, 37, Benchmark.IPCA,
                new BigDecimal("0.95"), frozen("0.1365"));

        assertEquals(SimulationCalculator.project(p), SimulationCalculator.project(p));
    }

    @Test
    void unknownCalculationVersion_isRefusedInsteadOfSilentlyUsingV1Formulas() {
        SimulationParameters v1 = SimulationParameters.fixedRate(1_000_000L, 12, new BigDecimal("0.12"));
        SimulationParameters futureVersion = new SimulationParameters(v1.kind(), v1.capitalCents(), v1.months(),
                v1.annualRate(), v1.benchmark(), v1.benchmarkPercent(), v1.indexRate(), v1.rates(), v1.ticker(),
                v1.purchasePriceCents(), v1.quantity(), v1.currentPriceCents(), v1.dividendPerShareCents(),
                v1.priceVariationPercent(), v1.priceOrigin(), v1.priceReferenceDate(),
                SimulationParameters.CALCULATION_VERSION + 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SimulationCalculator.project(futureVersion));

        assertTrue(ex.getMessage().contains(String.valueOf(SimulationParameters.CALCULATION_VERSION + 1)), ex.getMessage());
        assertDoesNotThrow(() -> SimulationCalculator.project(v1));
    }

    @Test
    void overflow_isRejectedNotSilentlyWrapped() {
        SimulationParameters p = SimulationParameters.fixedRate(Long.MAX_VALUE / 2, 120, new BigDecimal("5"));

        assertThrows(ArithmeticException.class, () -> SimulationCalculator.project(p));
    }
}

package com.daniel.core.domain.entity;

import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.Kind;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameters of a simulation scenario (RF13 extension, RN04, RN12). The DTO must
 * carry everything needed to reproduce a result without consulting the network:
 * kind, money in cents, period, rates and the frozen benchmark snapshot.
 */
class SimulationParametersTest {

    static RateSnapshot bcbSnapshot() {
        return new RateSnapshot(new BigDecimal("0.1365"), new BigDecimal("0.15"), new BigDecimal("0.045"),
                "BCB/SGS", "2026-10-15");
    }

    @Test
    void fixedRate_validParameters_roundTripFields() {
        SimulationParameters p = SimulationParameters.fixedRate(1_000_000L, 12, new BigDecimal("0.125"));

        assertEquals(Kind.FIXED_RATE, p.kind());
        assertEquals(1_000_000L, p.capitalCents());
        assertEquals(12, p.months());
        assertEquals(new BigDecimal("0.125"), p.annualRate());
        assertNull(p.rates(), "fixed rate needs no benchmark snapshot");
        assertEquals(SimulationParameters.CALCULATION_VERSION, p.calculationVersion());
    }

    @Test
    void benchmark_validParameters_keepsFrozenSnapshot() {
        SimulationParameters p = SimulationParameters.benchmark(500_000L, 24, Benchmark.CDI,
                new BigDecimal("1.10"), bcbSnapshot());

        assertEquals(Kind.BENCHMARK_PERCENT, p.kind());
        assertEquals(Benchmark.CDI, p.benchmark());
        assertEquals(new BigDecimal("1.10"), p.benchmarkPercent());
        assertEquals(new BigDecimal("0.1365"), p.rates().cdi());
        assertEquals("BCB/SGS", p.rates().origin());
        assertEquals("2026-10-15", p.rates().referenceDate());
    }

    @Test
    void hybrid_validParameters() {
        SimulationParameters p = SimulationParameters.hybrid(100_000L, 36, new BigDecimal("0.05"), new BigDecimal("0.045"));

        assertEquals(Kind.HYBRID, p.kind());
        assertEquals(new BigDecimal("0.05"), p.annualRate());
        assertEquals(new BigDecimal("0.045"), p.indexRate());
    }

    @Test
    void stock_validParameters() {
        SimulationParameters p = SimulationParameters.stock("PETR4", 3550L, 100, 4000L, 250L,
                new BigDecimal("-5.0"), "manual", "2026-10-15");

        assertEquals(Kind.STOCK, p.kind());
        assertEquals("PETR4", p.ticker());
        assertEquals(3550L, p.purchasePriceCents());
        assertEquals(100, p.quantity());
        assertEquals(4000L, p.currentPriceCents());
        assertEquals(250L, p.dividendPerShareCents());
        assertEquals(new BigDecimal("-5.0"), p.priceVariationPercent());
        assertEquals("manual", p.priceOrigin());
        assertEquals("2026-10-15", p.priceReferenceDate());
    }

    @Test
    void capitalMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.fixedRate(0L, 12, new BigDecimal("0.1")));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.fixedRate(-1L, 12, new BigDecimal("0.1")));
    }

    @Test
    void monthsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.fixedRate(100L, 0, new BigDecimal("0.1")));
    }

    @Test
    void ratesMustBeFiniteNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.fixedRate(100L, 12, null));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.benchmark(100L, 12, Benchmark.CDI, new BigDecimal("1.1"),
                        new RateSnapshot(null, BigDecimal.ONE, BigDecimal.ONE, "BCB", "2026-01-01")));
    }

    @Test
    void stock_quantityMustBePositiveInteger_andPricesPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.stock("X", 100L, 0, 100L, 0L, BigDecimal.ZERO, "manual", null));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.stock("X", 0L, 1, 100L, 0L, BigDecimal.ZERO, "manual", null));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationParameters.stock("X", 100L, 1, 100L, -1L, BigDecimal.ZERO, "manual", null));
    }

    @Test
    void stock_currentPriceZeroMeansUsePurchasePrice() {
        SimulationParameters p = SimulationParameters.stock("X", 100L, 1, 0L, 0L, BigDecimal.ZERO, "manual", null);
        assertEquals(100L, p.effectiveCurrentPriceCents());
    }
}

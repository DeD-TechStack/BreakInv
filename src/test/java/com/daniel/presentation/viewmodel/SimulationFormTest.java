package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.Kind;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Converts the simulation page's raw field texts into {@link SimulationParameters},
 * freezing the benchmark snapshot at that moment. No JavaFX, no network.
 */
class SimulationFormTest {

    static RateSnapshot snapshot() {
        return new RateSnapshot(new BigDecimal("0.1365"), new BigDecimal("0.15"), new BigDecimal("0.045"), "BCB/SGS", "2026-10-15");
    }

    @Test
    void fixedRate_fromTexts() {
        SimulationParameters p = SimulationForm.fixedRate("R$ 10.000,00", 12, "12,5");

        assertEquals(Kind.FIXED_RATE, p.kind());
        assertEquals(1_000_000L, p.capitalCents());
        assertEquals(new BigDecimal("0.125"), p.annualRate());
    }

    @Test
    void benchmark_fromTexts_freezesSnapshot() {
        SimulationParameters p = SimulationForm.benchmark("5000", 24, "CDI", "110", snapshot());

        assertEquals(Kind.BENCHMARK_PERCENT, p.kind());
        assertEquals(500_000L, p.capitalCents());
        assertEquals(Benchmark.CDI, p.benchmark());
        assertEquals(0, new BigDecimal("1.1").compareTo(p.benchmarkPercent()), "110 → 1.10");
        assertEquals(snapshot(), p.rates(), "snapshot passed in is stored unchanged");
    }

    @Test
    void hybrid_fromTexts() {
        SimulationParameters p = SimulationForm.hybrid("1.000,00", 36, "5", "4,5");

        assertEquals(Kind.HYBRID, p.kind());
        assertEquals(new BigDecimal("0.05"), p.annualRate());
        assertEquals(new BigDecimal("0.045"), p.indexRate());
    }

    @Test
    void stock_fromTexts_manualOrigin() {
        SimulationParameters p = SimulationForm.stock("petr4", "R$ 35,50", "100", "R$ 40,00", "R$ 2,50", -5.0, "manual", null);

        assertEquals(Kind.STOCK, p.kind());
        assertEquals("PETR4", p.ticker());
        assertEquals(3550L, p.purchasePriceCents());
        assertEquals(100, p.quantity());
        assertEquals(4000L, p.currentPriceCents());
        assertEquals(250L, p.dividendPerShareCents());
        assertEquals(new BigDecimal("-5.0"), p.priceVariationPercent());
        assertEquals("manual", p.priceOrigin());
    }

    @Test
    void stock_blankCurrentPriceAndDividends_meanZero() {
        SimulationParameters p = SimulationForm.stock("X", "10,00", "1", "", "", 0.0, "manual", null);
        assertEquals(0L, p.currentPriceCents());
        assertEquals(0L, p.dividendPerShareCents());
    }

    @Test
    void invalidTexts_produceFieldSpecificMessages() {
        IllegalArgumentException qty = assertThrows(IllegalArgumentException.class,
                () -> SimulationForm.stock("X", "10,00", "1,5", "", "", 0.0, "manual", null));
        assertTrue(qty.getMessage().contains("Quantidade"), qty.getMessage());

        IllegalArgumentException rate = assertThrows(IllegalArgumentException.class,
                () -> SimulationForm.fixedRate("100", 12, "abc"));
        assertTrue(rate.getMessage().contains("Taxa"), rate.getMessage());

        IllegalArgumentException capital = assertThrows(IllegalArgumentException.class,
                () -> SimulationForm.fixedRate("", 12, "10"));
        assertTrue(capital.getMessage().contains("Valor inicial"), capital.getMessage());

        IllegalArgumentException benchmark = assertThrows(IllegalArgumentException.class,
                () -> SimulationForm.benchmark("100", 12, "DÓLAR", "100", snapshot()));
        assertTrue(benchmark.getMessage().contains("Benchmark"), benchmark.getMessage());
    }

    @Test
    void snapshotFromDoubles_keepsProvenance() {
        RateSnapshot s = SimulationForm.snapshot(0.1365, 0.15, 0.045, "estimativa padrão", null);
        assertEquals(new BigDecimal("0.1365"), s.cdi());
        assertEquals("estimativa padrão", s.origin());
        assertNull(s.referenceDate());
    }

    @Test
    void snapshotFromDoubles_rejectsNonFinite() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationForm.snapshot(Double.NaN, 0.15, 0.045, "x", null));
    }
}

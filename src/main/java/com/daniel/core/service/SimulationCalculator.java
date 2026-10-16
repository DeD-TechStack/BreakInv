package com.daniel.core.service;

import com.daniel.core.domain.entity.SimulationParameters;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure projection of a {@link SimulationParameters} (RF13 extension, RN12).
 *
 * <p>Inputs are integer cents and {@link BigDecimal} fractions; outputs are
 * integer cents rounded HALF_UP, so the same parameters always reproduce the same
 * result. Arithmetic that would overflow {@code long} throws
 * {@link ArithmeticException} instead of wrapping silently.</p>
 *
 * <p>Formulas (calculation version 1):</p>
 * <ul>
 *   <li>fixed/benchmark/hybrid: {@code final = capital × (1 + annual)^(months/12)} with
 *       monthly compounding {@code (1+annual)^(1/12)}; series is month 0..n.</li>
 *   <li>benchmark: {@code annual = benchmarkRate(frozen) × benchmarkPercent}.</li>
 *   <li>hybrid: {@code annual = (1 + index) × (1 + fixed) − 1}.</li>
 *   <li>stock: {@code adjustedPrice = currentPrice × (1 + variation/100)};
 *       {@code final = adjustedPrice × qty}; {@code dividends = dividendPerShare × qty}
 *       (the per-share dividend is multiplied by the quantity — the previous page
 *       code added it once); {@code profit = final + dividends − invested}.</li>
 * </ul>
 */
public final class SimulationCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SimulationCalculator() {}

    /**
     * @param investedCents       capital (fixed income) or purchase price × qty (stock)
     * @param finalCents          projected value at the end (stock: position value without dividends)
     * @param dividendsCents      stock only: dividend per share × qty; 0 otherwise
     * @param profitCents         finalCents + dividendsCents − investedCents
     * @param returnPercent       profitCents / investedCents × 100
     * @param effectiveAnnualRate fixed income: annual rate actually used; stock: NaN
     * @param seriesCents         value per month (0..months) or [invested, final+dividends] for stock
     */
    public record Projection(
            long investedCents,
            long finalCents,
            long dividendsCents,
            long profitCents,
            double returnPercent,
            double effectiveAnnualRate,
            List<Long> seriesCents
    ) {}

    /**
     * @throws IllegalArgumentException when {@code p.calculationVersion()} is not the
     *         version implemented here: a scenario saved by another build must not be
     *         silently recomputed with these formulas (the domain only checks {@code > 0}).
     */
    public static Projection project(SimulationParameters p) {
        if (p.calculationVersion() != SimulationParameters.CALCULATION_VERSION) {
            throw new IllegalArgumentException("Versão de cálculo " + p.calculationVersion()
                    + " não é suportada por esta versão do aplicativo (suportada: "
                    + SimulationParameters.CALCULATION_VERSION + "). O cenário não foi recalculado.");
        }
        return switch (p.kind()) {
            case FIXED_RATE -> fixedIncome(p, p.annualRate());
            case BENCHMARK_PERCENT -> fixedIncome(p, p.rates().rateFor(p.benchmark()).multiply(p.benchmarkPercent(), MC));
            case HYBRID -> fixedIncome(p, BigDecimal.ONE.add(p.indexRate(), MC)
                    .multiply(BigDecimal.ONE.add(p.annualRate(), MC), MC)
                    .subtract(BigDecimal.ONE, MC));
            case STOCK -> stock(p);
        };
    }

    private static Projection fixedIncome(SimulationParameters p, BigDecimal annualRate) {
        double monthlyFactor = Math.pow(1.0 + annualRate.doubleValue(), 1.0 / 12.0);
        if (!Double.isFinite(monthlyFactor) || monthlyFactor <= 0) {
            throw new ArithmeticException("Taxa anual fora do intervalo calculável.");
        }
        BigDecimal capital = BigDecimal.valueOf(p.capitalCents());
        List<Long> series = new ArrayList<>(p.months() + 1);
        long last = p.capitalCents();
        for (int m = 0; m <= p.months(); m++) {
            double factor = Math.pow(monthlyFactor, m);
            if (!Double.isFinite(factor)) throw new ArithmeticException("Projeção excede o intervalo calculável.");
            last = toCents(capital.multiply(BigDecimal.valueOf(factor), MC));
            series.add(last);
        }
        long profit = Math.subtractExact(last, p.capitalCents());
        return new Projection(p.capitalCents(), last, 0L, profit,
                percent(profit, p.capitalCents()), annualRate.doubleValue(), Collections.unmodifiableList(series));
    }

    private static Projection stock(SimulationParameters p) {
        long invested = Math.multiplyExact(p.purchasePriceCents(), (long) p.quantity());
        BigDecimal adjustedPrice = BigDecimal.valueOf(p.effectiveCurrentPriceCents())
                .multiply(BigDecimal.ONE.add(p.priceVariationPercent().divide(HUNDRED, MC), MC), MC);
        long finalCents = toCents(adjustedPrice.multiply(BigDecimal.valueOf(p.quantity()), MC));
        long dividends = Math.multiplyExact(p.dividendPerShareCents(), (long) p.quantity());
        long profit = Math.subtractExact(Math.addExact(finalCents, dividends), invested);
        List<Long> series = List.of(invested, Math.addExact(finalCents, dividends));
        return new Projection(invested, finalCents, dividends, profit, percent(profit, invested), Double.NaN, series);
    }

    private static long toCents(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0.0 : part * 100.0 / whole;
    }
}

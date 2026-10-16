package com.daniel.core.domain.entity;

import java.math.BigDecimal;

/**
 * Complete, self-contained parameters of a simulation (RF13 extension).
 *
 * <p>Everything needed to reproduce the projection is here: kind, money in integer
 * cents, period, user rates and — for benchmark scenarios — the frozen rate snapshot
 * with its origin and reference date. A saved scenario therefore never depends on
 * the "current" rate of the day it is reopened (RN12). Projections are estimates;
 * they never touch the real portfolio.</p>
 *
 * <p>Percentages are stored as fractions: {@code 0.125} = 12.5% a.a.;
 * {@code benchmarkPercent 1.10} = 110% of the benchmark. The stock price variation
 * is kept as a percentage ({@code -5.0} = −5%) because that is how the UI slider exposes it.</p>
 */
public record SimulationParameters(
        Kind kind,
        long capitalCents,
        int months,
        BigDecimal annualRate,
        Benchmark benchmark,
        BigDecimal benchmarkPercent,
        BigDecimal indexRate,
        RateSnapshot rates,
        String ticker,
        long purchasePriceCents,
        int quantity,
        long currentPriceCents,
        long dividendPerShareCents,
        BigDecimal priceVariationPercent,
        String priceOrigin,
        String priceReferenceDate,
        int calculationVersion
) {

    /** Bump when the formulas in SimulationCalculator change in a way that alters results. */
    public static final int CALCULATION_VERSION = 1;

    public enum Kind { FIXED_RATE, BENCHMARK_PERCENT, HYBRID, STOCK }

    public enum Benchmark { CDI, SELIC, IPCA }

    /**
     * Annual benchmark rates frozen at save time, as fractions, plus provenance.
     *
     * @param origin        e.g. "BCB/SGS", "estimativa padrão" or "manual"
     * @param referenceDate ISO date the rates refer to (may be null when unknown)
     */
    public record RateSnapshot(BigDecimal cdi, BigDecimal selic, BigDecimal ipca, String origin, String referenceDate) {
        public RateSnapshot {
            requireFinite(cdi, "CDI");
            requireFinite(selic, "SELIC");
            requireFinite(ipca, "IPCA");
            if (origin == null || origin.isBlank()) throw new IllegalArgumentException("Origem das taxas ausente.");
        }

        public BigDecimal rateFor(Benchmark b) {
            return switch (b) {
                case CDI -> cdi;
                case SELIC -> selic;
                case IPCA -> ipca;
            };
        }
    }

    public SimulationParameters {
        if (kind == null) throw new IllegalArgumentException("Tipo de simulação ausente.");
        if (calculationVersion <= 0) throw new IllegalArgumentException("Versão de cálculo inválida.");
        switch (kind) {
            case FIXED_RATE -> {
                requirePositiveMoney(capitalCents, "Valor inicial");
                requirePositive(months, "Período (meses)");
                requireFinite(annualRate, "Taxa anual");
            }
            case BENCHMARK_PERCENT -> {
                requirePositiveMoney(capitalCents, "Valor inicial");
                requirePositive(months, "Período (meses)");
                if (benchmark == null) throw new IllegalArgumentException("Benchmark ausente.");
                requireFinite(benchmarkPercent, "Percentual do benchmark");
                if (rates == null) throw new IllegalArgumentException("Taxas de referência ausentes.");
            }
            case HYBRID -> {
                requirePositiveMoney(capitalCents, "Valor inicial");
                requirePositive(months, "Período (meses)");
                requireFinite(annualRate, "Taxa fixa");
                requireFinite(indexRate, "Taxa do índice");
            }
            case STOCK -> {
                requirePositiveMoney(purchasePriceCents, "Preço de compra");
                requirePositive(quantity, "Quantidade");
                if (currentPriceCents < 0) throw new IllegalArgumentException("Preço atual não pode ser negativo.");
                if (dividendPerShareCents < 0) throw new IllegalArgumentException("Dividendos não podem ser negativos.");
                requireFinite(priceVariationPercent, "Variação de preço");
                if (priceOrigin == null || priceOrigin.isBlank()) throw new IllegalArgumentException("Origem do preço ausente.");
            }
        }
    }

    // ----- factories -----

    public static SimulationParameters fixedRate(long capitalCents, int months, BigDecimal annualRate) {
        return new SimulationParameters(Kind.FIXED_RATE, capitalCents, months, annualRate, null, null, null, null,
                null, 0, 0, 0, 0, null, null, null, CALCULATION_VERSION);
    }

    public static SimulationParameters benchmark(long capitalCents, int months, Benchmark benchmark,
                                                 BigDecimal benchmarkPercent, RateSnapshot rates) {
        return new SimulationParameters(Kind.BENCHMARK_PERCENT, capitalCents, months, null, benchmark, benchmarkPercent,
                null, rates, null, 0, 0, 0, 0, null, null, null, CALCULATION_VERSION);
    }

    public static SimulationParameters hybrid(long capitalCents, int months, BigDecimal fixedRate, BigDecimal indexRate) {
        return new SimulationParameters(Kind.HYBRID, capitalCents, months, fixedRate, null, null, indexRate, null,
                null, 0, 0, 0, 0, null, null, null, CALCULATION_VERSION);
    }

    public static SimulationParameters stock(String ticker, long purchasePriceCents, int quantity, long currentPriceCents,
                                             long dividendPerShareCents, BigDecimal priceVariationPercent,
                                             String priceOrigin, String priceReferenceDate) {
        return new SimulationParameters(Kind.STOCK, 0, 0, null, null, null, null, null,
                ticker == null ? null : ticker.trim().toUpperCase(), purchasePriceCents, quantity, currentPriceCents,
                dividendPerShareCents, priceVariationPercent, priceOrigin, priceReferenceDate, CALCULATION_VERSION);
    }

    /** Stock: a zero current price means "no quote available — use the purchase price". */
    public long effectiveCurrentPriceCents() {
        return currentPriceCents > 0 ? currentPriceCents : purchasePriceCents;
    }

    // ----- validation helpers -----

    private static void requirePositiveMoney(long cents, String field) {
        if (cents <= 0) throw new IllegalArgumentException(field + " deve ser maior que zero.");
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " deve ser maior que zero.");
    }

    private static void requireFinite(BigDecimal value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " ausente ou inválida.");
        // BigDecimal is always finite; the check guards against callers converting NaN/Infinity upstream.
    }
}

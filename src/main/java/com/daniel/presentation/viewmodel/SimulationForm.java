package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationParameters.Benchmark;
import com.daniel.core.domain.entity.SimulationParameters.RateSnapshot;
import com.daniel.core.util.Money;

import java.math.BigDecimal;

/**
 * Converts the simulation page's raw texts into validated {@link SimulationParameters}.
 * Money texts use the existing {@link Money} parser (cents); percentage texts accept
 * comma or dot and become fractions ({@code "12,5"} → {@code 0.125}). Errors name the field.
 * No JavaFX, no network.
 */
public final class SimulationForm {

    private SimulationForm() {}

    public static SimulationParameters fixedRate(String capitalText, int months, String annualRateText) {
        return SimulationParameters.fixedRate(money(capitalText, "Valor inicial"), months,
                percentToFraction(annualRateText, "Taxa anual"));
    }

    public static SimulationParameters benchmark(String capitalText, int months, String benchmarkName,
                                                 String benchmarkPercentText, RateSnapshot frozen) {
        Benchmark b;
        try {
            b = Benchmark.valueOf(benchmarkName == null ? "" : benchmarkName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Benchmark inválido: " + benchmarkName);
        }
        return SimulationParameters.benchmark(money(capitalText, "Valor inicial"), months, b,
                percentToFraction(benchmarkPercentText, "Percentual do benchmark"), frozen);
    }

    public static SimulationParameters hybrid(String capitalText, int months, String fixedRateText, String indexRateText) {
        return SimulationParameters.hybrid(money(capitalText, "Valor inicial"), months,
                percentToFraction(fixedRateText, "Taxa fixa"),
                percentToFraction(indexRateText, "Taxa do índice"));
    }

    public static SimulationParameters stock(String ticker, String purchasePriceText, String quantityText,
                                             String currentPriceText, String dividendPerShareText,
                                             double priceVariationPercent, String priceOrigin, String priceReferenceDate) {
        int qty;
        try {
            qty = Integer.parseInt(quantityText == null ? "" : quantityText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Quantidade deve ser um número inteiro.");
        }
        if (!Double.isFinite(priceVariationPercent)) throw new IllegalArgumentException("Variação de preço inválida.");
        return SimulationParameters.stock(ticker,
                money(purchasePriceText, "Preço de compra"), qty,
                moneyOrZero(currentPriceText, "Preço atual"),
                moneyOrZero(dividendPerShareText, "Dividendos"),
                BigDecimal.valueOf(priceVariationPercent), priceOrigin, priceReferenceDate);
    }

    /** Freezes the page's current rates (fractions) with provenance; rejects NaN/Infinity. */
    public static RateSnapshot snapshot(double cdi, double selic, double ipca, String origin, String referenceDate) {
        return new RateSnapshot(finite(cdi, "CDI"), finite(selic, "SELIC"), finite(ipca, "IPCA"), origin, referenceDate);
    }

    // ----- helpers -----

    private static long money(String text, String field) {
        long cents = moneyOrZero(text, field);
        if (cents <= 0) throw new IllegalArgumentException(field + " deve ser maior que zero.");
        return cents;
    }

    private static long moneyOrZero(String text, String field) {
        try {
            return Money.textToCentsOrZero(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " inválido: " + text);
        }
    }

    private static BigDecimal percentToFraction(String text, String field) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(field + " ausente.");
        try {
            return new BigDecimal(text.trim().replace("%", "").replace(',', '.')).movePointLeft(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " inválida: " + text.trim());
        }
    }

    private static BigDecimal finite(double v, String field) {
        if (!Double.isFinite(v)) throw new IllegalArgumentException("Taxa " + field + " inválida.");
        return BigDecimal.valueOf(v);
    }
}

package com.daniel.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvestmentCalculatorTest {

    // ===== calculatePrefixado =====

    @Test
    void prefixado_singlePeriodTenPercent() {
        double result = InvestmentCalculator.calculatePrefixado(1000.0, 0.10, 1);
        assertEquals(1100.0, result, 0.001);
    }

    @Test
    void prefixado_zeroPeriods_returnsCapital() {
        double result = InvestmentCalculator.calculatePrefixado(5000.0, 0.12, 0);
        assertEquals(5000.0, result, 0.001);
    }

    @Test
    void prefixado_multiPeriodCompound() {
        // 1000 * (1.10)^3 = 1331
        double result = InvestmentCalculator.calculatePrefixado(1000.0, 0.10, 3);
        assertEquals(1331.0, result, 0.001);
    }

    @Test
    void prefixado_zeroRate_returnsCapital() {
        double result = InvestmentCalculator.calculatePrefixado(2500.0, 0.0, 5);
        assertEquals(2500.0, result, 0.001);
    }

    // ===== calculatePosfixado =====

    @Test
    void posfixado_hundredPercentOfIndex() {
        // 100% of CDI 13.5% = effective 13.5%
        double result = InvestmentCalculator.calculatePosfixado(1000.0, 1.0, 0.135, 1);
        assertEquals(1135.0, result, 0.001);
    }

    @Test
    void posfixado_ninetyPercentOfIndex() {
        // 90% of 10% = effective 9%
        double result = InvestmentCalculator.calculatePosfixado(1000.0, 0.90, 0.10, 1);
        assertEquals(1090.0, result, 0.001);
    }

    @Test
    void posfixado_multiPeriod() {
        // 100% of 10% for 2 periods = 1000 * 1.1^2 = 1210
        double result = InvestmentCalculator.calculatePosfixado(1000.0, 1.0, 0.10, 2);
        assertEquals(1210.0, result, 0.001);
    }

    // ===== calculateHibrido =====

    @Test
    void hibrido_equalFixedAndInflation() {
        // (1 + 0.05) * (1 + 0.05) - 1 = 0.1025; 1000 * 1.1025 = 1102.5
        double result = InvestmentCalculator.calculateHibrido(1000.0, 0.05, 0.05, 1);
        assertEquals(1102.5, result, 0.001);
    }

    @Test
    void hibrido_zeroInflation_actsLikePrefixado() {
        double hybrid = InvestmentCalculator.calculateHibrido(1000.0, 0.10, 0.0, 1);
        double prefixado = InvestmentCalculator.calculatePrefixado(1000.0, 0.10, 1);
        assertEquals(prefixado, hybrid, 0.001);
    }

    @Test
    void hibrido_zeroFixedRate_actsLikeInflation() {
        // (1 + 0.05) * (1 + 0) - 1 = 0.05; 1000 * 1.05 = 1050
        double result = InvestmentCalculator.calculateHibrido(1000.0, 0.0, 0.05, 1);
        assertEquals(1050.0, result, 0.001);
    }

    // ===== calculateAcao =====

    @Test
    void acao_basicProfitAndRentability() {
        var calc = InvestmentCalculator.calculateAcao(10.0, 100, 15.0, 0.0);
        assertEquals(1000.0, calc.valorInvestido(), 0.001);
        assertEquals(1500.0, calc.valorAtual(), 0.001);
        assertEquals(500.0, calc.lucro(), 0.001);
        assertEquals(0.5, calc.rentabilidade(), 0.001);
        assertEquals(500.0, calc.lucroTotal(), 0.001);
    }

    @Test
    void acao_withDividends_addedToLucroTotal() {
        var calc = InvestmentCalculator.calculateAcao(10.0, 100, 15.0, 50.0);
        assertEquals(550.0, calc.lucroTotal(), 0.001); // (1500 + 50) - 1000
        assertEquals(500.0, calc.lucro(), 0.001);      // dividends don't affect lucro
    }

    @Test
    void acao_loss_negativeValues() {
        var calc = InvestmentCalculator.calculateAcao(20.0, 10, 15.0, 0.0);
        assertEquals(200.0, calc.valorInvestido(), 0.001);
        assertEquals(150.0, calc.valorAtual(), 0.001);
        assertEquals(-50.0, calc.lucro(), 0.001);
        assertEquals(-0.25, calc.rentabilidade(), 0.001);
    }

    @Test
    void acao_breakEven_zeroProfit() {
        var calc = InvestmentCalculator.calculateAcao(10.0, 50, 10.0, 0.0);
        assertEquals(0.0, calc.lucro(), 0.001);
        assertEquals(0.0, calc.rentabilidade(), 0.001);
    }

    @Test
    void acao_singleShare_smallPosition() {
        var calc = InvestmentCalculator.calculateAcao(50.0, 1, 55.0, 0.0);
        assertEquals(50.0, calc.valorInvestido(), 0.001);
        assertEquals(55.0, calc.valorAtual(), 0.001);
        assertEquals(5.0, calc.lucro(), 0.001);
        assertEquals(0.10, calc.rentabilidade(), 0.001);
    }

    @Test
    void acao_largeQuantity_correctScale() {
        // 10000 shares * R$30 bought, sold at R$33
        var calc = InvestmentCalculator.calculateAcao(30.0, 10000, 33.0, 0.0);
        assertEquals(300000.0, calc.valorInvestido(), 0.01);
        assertEquals(330000.0, calc.valorAtual(), 0.01);
        assertEquals(30000.0, calc.lucro(), 0.01);
    }

    // ===== calculatePrefixado — additional edge cases =====

    @Test
    void prefixado_hundredPercentRate_doubles() {
        // 100% annual rate for 1 period → value doubles
        double result = InvestmentCalculator.calculatePrefixado(1000.0, 1.0, 1);
        assertEquals(2000.0, result, 0.001);
    }

    @Test
    void prefixado_fractionalPeriod_handledByMath() {
        // 10% for 0.5 periods → 1000 * (1.1)^0.5 ≈ 1048.8
        double result = InvestmentCalculator.calculatePrefixado(1000.0, 0.10, 0);
        // 0 periods returns exactly the capital (identity)
        assertEquals(1000.0, result, 0.001);
    }

    // ===== calculatePosfixado — additional edge cases =====

    @Test
    void posfixado_zeroIndexRate_returnsCapital() {
        // 100% of 0% index = no growth
        double result = InvestmentCalculator.calculatePosfixado(1000.0, 1.0, 0.0, 5);
        assertEquals(1000.0, result, 0.001);
    }

    @Test
    void posfixado_zeroPercentage_returnsCapital() {
        // 0% of any index = no growth
        double result = InvestmentCalculator.calculatePosfixado(1000.0, 0.0, 0.135, 3);
        assertEquals(1000.0, result, 0.001);
    }

    @Test
    void posfixado_hundredTwentyPercent_exceedsIndex() {
        // 120% of CDI 10% = effective 12%
        double result = InvestmentCalculator.calculatePosfixado(1000.0, 1.2, 0.10, 1);
        assertEquals(1120.0, result, 0.001);
    }

    // ===== calculateHibrido — additional edge cases =====

    @Test
    void hibrido_bothRatesHigh_combinedGrowth() {
        // IPCA 10% + fixed 5%: (1.10 * 1.05) - 1 = 0.155 → 1000 * 1.155 = 1155
        double result = InvestmentCalculator.calculateHibrido(1000.0, 0.05, 0.10, 1);
        assertEquals(1155.0, result, 0.001);
    }

    @Test
    void hibrido_multiPeriod_compoundsCorrectly() {
        // IPCA 5% + fixed 5%, 2 periods: (1.1025)^2 * 1000 ≈ 1215.5
        double result = InvestmentCalculator.calculateHibrido(1000.0, 0.05, 0.05, 2);
        assertEquals(1215.5, result, 0.1);
    }
}

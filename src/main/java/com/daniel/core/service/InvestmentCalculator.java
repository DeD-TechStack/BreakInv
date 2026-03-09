package com.daniel.core.service;

public final class InvestmentCalculator {

    // 1. Prefixado
    public static double calculatePrefixado(double capitalInicial,
                                            double taxaFixa,
                                            int tempo) {
        return capitalInicial * Math.pow(1 + taxaFixa, tempo);
    }

    // 2. Pós-fixado
    public static double calculatePosfixado(double capitalInicial,
                                            double percentualIndice,
                                            double taxaIndice,
                                            int tempo) {
        double taxaEfetiva = taxaIndice * percentualIndice;
        return capitalInicial * Math.pow(1 + taxaEfetiva, tempo);
    }

    // 3. Híbrido (Inflação)
    public static double calculateHibrido(double capitalInicial,
                                          double taxaFixa,
                                          double indiceInflacao,
                                          int tempo) {
        double taxaFinal = (1 + indiceInflacao) * (1 + taxaFixa) - 1;
        return capitalInicial * Math.pow(1 + taxaFinal, tempo);
    }

    // 4. Ações
    public record StockCalculation(
            double valorInvestido,
            double valorAtual,
            double lucro,
            double rentabilidade,
            double lucroTotal
    ) {}

    public static StockCalculation calculateAcao(
            double precoCompra,
            int quantidade,
            double precoAtual,
            double dividendos
    ) {
        double valorInvestido = precoCompra * quantidade;
        double valorAtual = precoAtual * quantidade;
        double lucro = valorAtual - valorInvestido;
        double rentabilidade = lucro / valorInvestido;
        double lucroTotal = (valorAtual + dividendos) - valorInvestido;

        return new StockCalculation(
                valorInvestido, valorAtual, lucro, rentabilidade, lucroTotal
        );
    }
}

package com.daniel.presentation.viewmodel;

import com.daniel.infrastructure.api.BrapiClient.StockData;
import com.daniel.presentation.view.util.UiNumbers;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * View-model para a página de Ranking de Ativos.
 * Transforma um mapa de StockData em listas ordenadas por variação.
 */
public final class RankingViewModel {

    private RankingViewModel() {}

    public record RankingRow(
            String ticker,
            String name,
            double price,
            double change,
            double changePct
    ) {}

    /**
     * Retorna os maiores ganhos do dia (variação MOSTRADA acima de zero, ordenados desc). O valor mostrado é o de duas
     * casas, HALF_UP ({@link UiNumbers#tone}): um ativo em +0,004% lê 0,00% e não é "alta"; +0,005% lê +0,01% e é.
     */
    public static List<RankingRow> topGainers(Map<String, StockData> stocks) {
        return stocks.values().stream()
                .filter(StockData::isValid)
                .filter(s -> UiNumbers.POS.equals(UiNumbers.tone(s.regularMarketChangePercent(), 2)))
                .sorted(Comparator.comparingDouble(StockData::regularMarketChangePercent).reversed())
                .map(RankingViewModel::toRow)
                .toList();
    }

    /**
     * Retorna as maiores quedas do dia (variação MOSTRADA abaixo de zero, ordenados asc). Um valor mostrado zero
     * ({@code -0,004%} lê 0,00%) não entra em nenhuma das duas listas.
     */
    public static List<RankingRow> topLosers(Map<String, StockData> stocks) {
        return stocks.values().stream()
                .filter(StockData::isValid)
                .filter(s -> UiNumbers.NEG.equals(UiNumbers.tone(s.regularMarketChangePercent(), 2)))
                .sorted(Comparator.comparingDouble(StockData::regularMarketChangePercent))
                .map(RankingViewModel::toRow)
                .toList();
    }

    private static RankingRow toRow(StockData s) {
        String name = s.longName() != null ? s.longName() : s.ticker();
        return new RankingRow(s.ticker(), name,
                s.regularMarketPrice(),
                s.regularMarketChange(),
                s.regularMarketChangePercent());
    }
}

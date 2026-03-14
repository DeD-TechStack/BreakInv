package com.daniel.presentation.viewmodel;

import com.daniel.infrastructure.api.BrapiClient.StockData;

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
     * Retorna os maiores ganhos do dia (variação positiva, ordenados desc).
     */
    public static List<RankingRow> topGainers(Map<String, StockData> stocks) {
        return stocks.values().stream()
                .filter(StockData::isValid)
                .filter(s -> s.regularMarketChangePercent() > 0)
                .sorted(Comparator.comparingDouble(StockData::regularMarketChangePercent).reversed())
                .map(RankingViewModel::toRow)
                .toList();
    }

    /**
     * Retorna as maiores quedas do dia (variação negativa, ordenados asc).
     */
    public static List<RankingRow> topLosers(Map<String, StockData> stocks) {
        return stocks.values().stream()
                .filter(StockData::isValid)
                .filter(s -> s.regularMarketChangePercent() < 0)
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

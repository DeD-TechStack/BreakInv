package com.daniel.presentation.view.util;

import javafx.application.Platform;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.text.Text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Utilitário para controle inteligente de densidade de labels no eixo X (CategoryAxis).
 *
 * <p>Problema: JavaFX {@code CategoryAxis} não ajusta automaticamente a visibilidade
 * dos tick labels ao redimensionar, causando sobreposição de datas.</p>
 *
 * <p>Solução: após o layout do eixo, os nós {@code Text} dos labels são manipulados
 * diretamente — labels intermediários são ocultados com base na largura disponível.
 * Um listener de {@code widthProperty} reaplica a lógica automaticamente no resize.</p>
 *
 * <p>Uso:</p>
 * <pre>
 *   // No setup do gráfico (uma vez):
 *   ChartAxisUtils.installSmartAxis(xAxis, myChart);
 *
 *   // Após popular os dados do gráfico:
 *   Platform.runLater(() -> ChartAxisUtils.refreshLabels(xAxis, myChart.getWidth()));
 * </pre>
 */
public final class ChartAxisUtils {

    /** Largura mínima em pixels reservada por label visível. */
    private static final double MIN_LABEL_PX = 58.0;

    private ChartAxisUtils() {}

    /**
     * Instala um listener de largura no gráfico para reaplicar a densidade de labels
     * automaticamente ao redimensionar. Deve ser chamado uma única vez no setup.
     */
    public static void installSmartAxis(CategoryAxis axis, XYChart<String, Number> chart) {
        chart.widthProperty().addListener((obs, old, newW) -> {
            double w = newW.doubleValue();
            List<String> cats = axis.getCategories();
            if (w > 0 && cats != null && !cats.isEmpty()) {
                Platform.runLater(() -> refreshLabels(axis, w));
            }
        });
    }

    /**
     * Recalcula quais tick labels são visíveis com base na largura disponível e oculta
     * os labels intermediários diretamente nos nós {@code Text} filhos do eixo.
     *
     * <p>Sempre exibe o primeiro e o último label; os intermediários aparecem
     * em intervalo calculado para evitar sobreposição.</p>
     *
     * @param axis       eixo de categorias do gráfico
     * @param chartWidth largura atual do gráfico em pixels
     */
    public static void refreshLabels(CategoryAxis axis, double chartWidth) {
        List<String> cats = axis.getCategories();
        if (cats == null || cats.isEmpty()) return;

        int total = cats.size();
        // ~78% da largura total é área útil do plot
        double plotWidth = Math.max(chartWidth * 0.78, 80.0);
        int maxVisible = Math.max(2, (int) (plotWidth / MIN_LABEL_PX));
        int step = Math.max(1, (int) Math.ceil((double) total / maxVisible));

        // Conjunto de labels que devem aparecer (primeiro, último e múltiplos do step)
        Set<String> show = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            if (i == 0 || i == total - 1 || i % step == 0) show.add(cats.get(i));
        }

        // Oculta/exibe os nós Text filhos do eixo correspondentes a cada label
        for (javafx.scene.Node n : axis.getChildrenUnmodifiable()) {
            if (n instanceof Text t) {
                String text = t.getText();
                if (text != null && !text.isEmpty()) {
                    t.setVisible(show.contains(text));
                }
            }
        }

        // Rotação adaptativa baseada na quantidade de labels visíveis
        axis.setTickLabelRotation(show.size() > 6 ? -45 : show.size() > 3 ? -30 : 0);
    }
}

package com.daniel.presentation.view.util;

import javafx.application.Platform;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Utilitário para controle inteligente de densidade de labels no eixo X.
 *
 * <h3>CategoryAxis (BarChart, etc.)</h3>
 * <p>Oculta labels intermediários via manipulação dos nós {@code Text} do eixo.</p>
 *
 * <h3>NumberAxis temporal (AreaChart / LineChart com datas)</h3>
 * <p>Usa epoch seconds (via {@link LocalDateTime#toEpochSecond(ZoneOffset)}) como valores X.
 * Calcula automaticamente o tick unit ideal para a quantidade de pontos e largura disponível,
 * garantindo que a escala temporal seja proporcional ao intervalo real entre os dados.</p>
 *
 * <h4>Uso (temporal):</h4>
 * <pre>
 *   // No setup do gráfico (uma vez):
 *   ChartAxisUtils.installTemporalAxis(xAxis, myChart, () -> currentFmt, () -> lastEpochSecs);
 *
 *   // Após popular os dados:
 *   Platform.runLater(() ->
 *       ChartAxisUtils.refreshTemporalAxis(xAxis, lastEpochSecs, myChart.getWidth()));
 * </pre>
 */
public final class ChartAxisUtils {

    /** Largura mínima em pixels reservada por label visível. */
    private static final double MIN_LABEL_PX = 58.0;

    private ChartAxisUtils() {}

    /**
     * R3-08: a value axis that reads — a step of 1, 2, 2.5 or 5 × 10ⁿ from a multiple of that step, a margin
     * around the data and three to nine intervals (±5 % with a sixth of the range as the step gave true but
     * unreadable ticks: 34.704 / 33.856 / …). Non-negative data never gets a negative bound.
     */
    public static void roundValueAxis(NumberAxis axis, double min, double max) {
        double lo = Math.min(min, max), hi = Math.max(min, max);
        double span = hi - lo;
        double pad = span > 0 ? span * 0.05 : (hi != 0 ? Math.abs(hi) * 0.02 : 1);
        double from = lo - pad, to = hi + pad;
        if (lo >= 0) from = Math.max(0, from); // prices: no negative bound, and the steps are chosen for that range
        double raw = (to - from) / 6;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double unit = magnitude * 10;
        for (double m : new double[]{1, 2, 2.5, 5}) {
            if (magnitude * m >= raw * (1 - 1e-12)) { unit = magnitude * m; break; }
        }
        double lower = clean(Math.floor(from / unit) * unit, unit);
        double upper = clean(Math.ceil(to / unit) * unit, unit);
        if (lo >= 0 && lower < 0) lower = 0;
        axis.setAutoRanging(false);
        axis.setLowerBound(lower);
        axis.setUpperBound(upper);
        axis.setTickUnit(unit);
    }

    /** Drops the floating residue of a multiplication (29.000000000000004) at the step's precision. */
    private static double clean(double value, double unit) {
        int decimals = Math.max(0, (int) Math.ceil(-Math.log10(unit)) + 1);
        return java.math.BigDecimal.valueOf(value).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    // ── CategoryAxis (BarChart, etc.) ─────────────────────────────────────────

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
     * Recalcula quais tick labels são visíveis com base na largura disponível.
     * Sempre exibe o primeiro e o último; os intermediários aparecem em intervalo
     * calculado para evitar sobreposição.
     */
    public static void refreshLabels(CategoryAxis axis, double chartWidth) {
        List<String> cats = axis.getCategories();
        if (cats == null || cats.isEmpty()) return;

        int total = cats.size();
        double plotWidth = Math.max(chartWidth * 0.78, 80.0);
        int maxVisible = Math.max(2, (int) (plotWidth / MIN_LABEL_PX));
        int step = Math.max(1, (int) Math.ceil((double) total / maxVisible));

        Set<String> show = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            if (i == 0 || i == total - 1 || i % step == 0) show.add(cats.get(i));
        }

        for (javafx.scene.Node n : axis.getChildrenUnmodifiable()) {
            // Pula o Text do título do eixo (CSS class "axis-label") — só manipula tick labels
            if (n instanceof Text t && !t.getStyleClass().contains("axis-label")) {
                String text = t.getText();
                if (text != null && !text.isEmpty()) {
                    t.setVisible(show.contains(text));
                }
            }
        }

        axis.setTickLabelRotation(show.size() > 6 ? -45 : show.size() > 3 ? -30 : 0);
    }

    // ── NumberAxis temporal (AreaChart / LineChart com datas) ─────────────────

    /**
     * Configura um {@link NumberAxis} para uso temporal e instala um listener de
     * largura que recalcula a densidade de ticks automaticamente ao redimensionar.
     *
     * <p>Os valores X dos pontos de dados devem ser epoch seconds obtidos via
     * {@link LocalDateTime#toEpochSecond(ZoneOffset)} com {@link ZoneOffset#UTC}.</p>
     *
     * @param xAxis           o eixo a configurar
     * @param chart           o gráfico que usa este eixo
     * @param fmtSource       supplier do formatter atual (lido a cada render para refletir
     *                        mudanças de período dinamicamente)
     * @param epochSecsSource supplier da lista de epoch-seconds dos pontos atuais (ordenada)
     */
    public static void installTemporalAxis(NumberAxis xAxis,
                                            XYChart<Number, Number> chart,
                                            Supplier<DateTimeFormatter> fmtSource,
                                            Supplier<List<Long>> epochSecsSource) {
        xAxis.setAutoRanging(false);
        xAxis.setMinorTickCount(0);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) {
                try {
                    return LocalDateTime.ofEpochSecond(n.longValue(), 0, ZoneOffset.UTC)
                            .format(fmtSource.get());
                } catch (Exception e) { return ""; }
            }
            @Override public Number fromString(String s) { return null; }
        });

        chart.widthProperty().addListener((obs, old, newW) -> {
            double w = newW.doubleValue();
            if (w > 0) {
                List<Long> secs = epochSecsSource.get();
                if (secs != null && !secs.isEmpty()) {
                    Platform.runLater(() -> refreshTemporalAxis(xAxis, secs, w));
                }
            }
        });
    }

    /**
     * Recalcula os bounds e o tick unit de um eixo temporal com base na largura
     * disponível e no range de datas dos dados.
     *
     * <p>Escolhe automaticamente um intervalo "agradável" (ex: 1h, 6h, 1d, 1 semana,
     * 1 mês) que evite sobreposição de labels e distribua os ticks proporcionalmente.</p>
     *
     * @param xAxis      eixo temporal a atualizar
     * @param epochSecs  lista ordenada de epoch-second valores dos pontos atuais
     * @param chartWidth largura atual do gráfico em pixels
     */
    public static void refreshTemporalAxis(NumberAxis xAxis, List<Long> epochSecs, double chartWidth) {
        if (epochSecs == null || epochSecs.isEmpty()) return;

        long minSec = epochSecs.get(0);
        long maxSec = epochSecs.get(epochSecs.size() - 1);
        long range  = maxSec - minSec;

        if (range <= 0) {
            xAxis.setLowerBound(minSec - 3600);
            xAxis.setUpperBound(maxSec + 3600);
            xAxis.setTickUnit(3600);
            return;
        }

        double plotWidth = Math.max(chartWidth * 0.78, 80.0);
        int    maxTicks  = Math.max(2, (int) (plotWidth / MIN_LABEL_PX));

        long rawUnit  = Math.max(1L, (long) Math.ceil((double) range / maxTicks));
        long tickUnit = roundToNiceSeconds(rawUnit);

        // Adiciona 5% de padding nas bordas para evitar que primeiro/último ponto
        // fiquem colados à borda do gráfico
        long padding = tickUnit / 2;
        xAxis.setLowerBound(minSec - padding);
        xAxis.setUpperBound(maxSec + padding);
        xAxis.setTickUnit(tickUnit);

        long tickCount = range / tickUnit + 1;
        xAxis.setTickLabelRotation(tickCount > 6 ? -45 : tickCount > 3 ? -30 : 0);
    }

    /**
     * Arredonda um intervalo em segundos para o próximo valor "agradável",
     * cobrindo desde minutos até anos.
     */
    private static long roundToNiceSeconds(long seconds) {
        long[] nice = {
            60,         // 1 min
            300,        // 5 min
            600,        // 10 min
            1_800,      // 30 min
            3_600,      // 1 hora
            7_200,      // 2 horas
            14_400,     // 4 horas
            21_600,     // 6 horas
            43_200,     // 12 horas
            86_400,     // 1 dia
            172_800,    // 2 dias
            259_200,    // 3 dias
            432_000,    // 5 dias
            604_800,    // 1 semana
            1_209_600,  // 2 semanas
            2_592_000,  // 1 mês (~30 dias)
            5_184_000,  // 2 meses
            7_776_000,  // 3 meses
            15_552_000, // 6 meses
            31_536_000, // 1 ano
        };
        for (long n : nice) {
            if (n >= seconds) return n;
        }
        return seconds;
    }
}

package com.daniel.presentation.view.util;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.util.List;
import java.util.function.Function;

/**
 * Adiciona crosshair interativo (linhas guia verticais + horizontais) a gráficos JavaFX.
 *
 * <h3>Uso para gráficos com CategoryAxis (ex: BarChart de rentabilidade):</h3>
 * <pre>
 *   StackPane wrapper = ChartCrosshair.install(myLineChart,
 *       y -> String.format("%.2f%%", y));
 *   vbox.getChildren().add(wrapper);
 * </pre>
 *
 * <h3>Uso para gráficos com eixo temporal (NumberAxis com epoch seconds):</h3>
 * <pre>
 *   StackPane wrapper = ChartCrosshair.installTemporal(myAreaChart,
 *       epochSec -> LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC).format(fmt),
 *       y -> "R$ " + String.format("%.2f", y).replace('.', ','));
 * </pre>
 *
 * <h3>Uso para gráficos com NumberAxis em ambos os eixos (ex: Simulação):</h3>
 * <pre>
 *   StackPane wrapper = ChartCrosshair.installNumeric(projectionChart,
 *       x -> "Mês " + x,
 *       y -> "R$ " + String.format("%.2f", y).replace('.', ','));
 * </pre>
 */
public final class ChartCrosshair {

    private ChartCrosshair() {}

    // ── API pública ──────────────────────────────────────────────────────────

    /**
     * Instala crosshair em chart com CategoryAxis (eixo X de Strings — nomes de ativos, etc.).
     * Séries com nome {@code "—"} são ignoradas (usadas como linhas de referência).
     *
     * @param chart      gráfico alvo (LineChart, AreaChart)
     * @param yFormatter formata o valor do eixo Y para o tooltip
     * @return StackPane contendo o chart + overlay de crosshair
     */
    public static StackPane install(XYChart<String, Number> chart,
                                    Function<Double, String> yFormatter) {
        CrosshairOverlay o = new CrosshairOverlay();

        StackPane container = new StackPane(chart, o.pane);

        container.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            Node plotBg = chart.lookup(".chart-plot-background");
            if (plotBg == null || chart.getData().isEmpty()) { o.hide(); return; }

            Point2D mp = plotBg.sceneToLocal(event.getSceneX(), event.getSceneY());
            Bounds  pb = plotBg.getBoundsInLocal();
            if (outOfPlot(mp, pb)) { o.hide(); return; }

            CategoryAxis xAxis = (CategoryAxis) chart.getXAxis();
            NumberAxis   yAxis = (NumberAxis)   chart.getYAxis();

            List<String> cats = xAxis.getCategories();
            if (cats == null || cats.isEmpty()) { o.hide(); return; }

            // Categoria mais próxima ao cursor
            String nearest  = null;
            double minDist  = Double.MAX_VALUE;
            for (String cat : cats) {
                double dist = Math.abs(xAxis.getDisplayPosition(cat) - mp.getX());
                if (dist < minDist) { minDist = dist; nearest = cat; }
            }
            if (nearest == null) { o.hide(); return; }

            // Converte coordenadas do plot para o container
            Bounds plotInScene     = plotBg.localToScene(pb);
            Bounds plotInContainer = container.sceneToLocal(plotInScene);
            double plotX = plotInContainer.getMinX() + xAxis.getDisplayPosition(nearest);

            // Coleta valores de todas as séries (ignora série de referência "—")
            final String cat = nearest;
            Double firstY = null;
            StringBuilder sb = new StringBuilder(cat);
            for (XYChart.Series<String, Number> ser : chart.getData()) {
                if ("—".equals(ser.getName())) continue;
                for (XYChart.Data<String, Number> d : ser.getData()) {
                    if (cat.equals(d.getXValue())) {
                        double yVal = d.getYValue().doubleValue();
                        if (firstY == null) firstY = yVal;
                        sb.append("\n").append(ser.getName()).append(": ")
                          .append(yFormatter.apply(yVal));
                        break;
                    }
                }
            }
            if (firstY == null) { o.hide(); return; }

            double plotY = plotInContainer.getMinY() + yAxis.getDisplayPosition(firstY);
            o.update(plotX, plotY, plotInContainer, sb.toString(), container.getWidth(), container.getHeight());
        });

        container.addEventFilter(MouseEvent.MOUSE_EXITED, e -> o.hide());
        return container;
    }

    /**
     * Instala crosshair em chart com NumberAxis em ambos os eixos (ex: gráfico de Simulação).
     *
     * @param chart      gráfico alvo (LineChart&lt;Number,Number&gt;)
     * @param xFormatter formata o valor do eixo X (ex: "Mês 12")
     * @param yFormatter formata o valor do eixo Y (ex: "R$ 1.200,00")
     * @return StackPane contendo o chart + overlay de crosshair
     */
    public static StackPane installNumeric(XYChart<Number, Number> chart,
                                           Function<Integer, String> xFormatter,
                                           Function<Double, String> yFormatter) {
        CrosshairOverlay o = new CrosshairOverlay();

        StackPane container = new StackPane(chart, o.pane);

        container.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            Node plotBg = chart.lookup(".chart-plot-background");
            if (plotBg == null || chart.getData().isEmpty()) { o.hide(); return; }

            Point2D mp = plotBg.sceneToLocal(event.getSceneX(), event.getSceneY());
            Bounds  pb = plotBg.getBoundsInLocal();
            if (outOfPlot(mp, pb)) { o.hide(); return; }

            NumberAxis xAxis = (NumberAxis) chart.getXAxis();
            NumberAxis yAxis = (NumberAxis) chart.getYAxis();

            int xVal = (int) Math.round(xAxis.getValueForDisplay(mp.getX()).doubleValue());

            Bounds plotInScene     = plotBg.localToScene(pb);
            Bounds plotInContainer = container.sceneToLocal(plotInScene);
            double plotX = plotInContainer.getMinX() + xAxis.getDisplayPosition(xVal);

            Double firstY = null;
            StringBuilder sb = new StringBuilder(xFormatter.apply(xVal));
            for (XYChart.Series<Number, Number> ser : chart.getData()) {
                for (XYChart.Data<Number, Number> d : ser.getData()) {
                    if (d.getXValue().intValue() == xVal) {
                        double yVal = d.getYValue().doubleValue();
                        if (firstY == null) firstY = yVal;
                        sb.append("\n").append(ser.getName()).append(": ")
                          .append(yFormatter.apply(yVal));
                        break;
                    }
                }
            }
            if (firstY == null) { o.hide(); return; }

            double plotY = plotInContainer.getMinY() + yAxis.getDisplayPosition(firstY);
            o.update(plotX, plotY, plotInContainer, sb.toString(), container.getWidth(), container.getHeight());
        });

        container.addEventFilter(MouseEvent.MOUSE_EXITED, e -> o.hide());
        return container;
    }

    /**
     * Instala crosshair em chart com {@link NumberAxis} temporal (epoch seconds).
     *
     * <p>Usa busca de vizinho mais próximo no eixo X — o ponto de dado com epoch second
     * mais próximo ao cursor é selecionado, garantindo que o crosshair sempre se encaixe
     * num ponto real mesmo que o cursor esteja entre dois pontos de datas diferentes.</p>
     *
     * <p>Séries com nome {@code "—"} são ignoradas (linhas de referência).</p>
     *
     * @param chart      gráfico alvo (AreaChart ou LineChart&lt;Number,Number&gt; com eixo temporal)
     * @param xFormatter converte epoch second (Long) em label de data para o tooltip
     * @param yFormatter formata o valor do eixo Y para o tooltip
     * @return StackPane contendo o chart + overlay de crosshair
     */
    public static StackPane installTemporal(XYChart<Number, Number> chart,
                                             Function<Long, String> xFormatter,
                                             Function<Double, String> yFormatter) {
        CrosshairOverlay o = new CrosshairOverlay();

        StackPane container = new StackPane(chart, o.pane);

        container.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            Node plotBg = chart.lookup(".chart-plot-background");
            if (plotBg == null || chart.getData().isEmpty()) { o.hide(); return; }

            Point2D mp = plotBg.sceneToLocal(event.getSceneX(), event.getSceneY());
            Bounds  pb = plotBg.getBoundsInLocal();
            if (outOfPlot(mp, pb)) { o.hide(); return; }

            NumberAxis xAxis = (NumberAxis) chart.getXAxis();
            NumberAxis yAxis = (NumberAxis) chart.getYAxis();

            double xValue = xAxis.getValueForDisplay(mp.getX()).doubleValue();

            // Encontra o epoch second mais próximo ao cursor em todas as séries
            long   nearestEpoch = Long.MIN_VALUE;
            double minDist      = Double.MAX_VALUE;
            for (XYChart.Series<Number, Number> ser : chart.getData()) {
                if ("—".equals(ser.getName())) continue;
                for (XYChart.Data<Number, Number> d : ser.getData()) {
                    double dist = Math.abs(d.getXValue().doubleValue() - xValue);
                    if (dist < minDist) {
                        minDist = dist;
                        nearestEpoch = d.getXValue().longValue();
                    }
                }
            }
            if (nearestEpoch == Long.MIN_VALUE) { o.hide(); return; }

            Bounds plotInScene     = plotBg.localToScene(pb);
            Bounds plotInContainer = container.sceneToLocal(plotInScene);
            double plotX = plotInContainer.getMinX() + xAxis.getDisplayPosition(nearestEpoch);

            final long finalEpoch = nearestEpoch;
            Double firstY = null;
            StringBuilder sb = new StringBuilder(xFormatter.apply(finalEpoch));
            for (XYChart.Series<Number, Number> ser : chart.getData()) {
                if ("—".equals(ser.getName())) continue;
                for (XYChart.Data<Number, Number> d : ser.getData()) {
                    if (d.getXValue().longValue() == finalEpoch) {
                        double yVal = d.getYValue().doubleValue();
                        if (firstY == null) firstY = yVal;
                        sb.append("\n").append(ser.getName()).append(": ")
                          .append(yFormatter.apply(yVal));
                        break;
                    }
                }
            }
            if (firstY == null) { o.hide(); return; }

            double plotY = plotInContainer.getMinY() + yAxis.getDisplayPosition(firstY);
            o.update(plotX, plotY, plotInContainer, sb.toString(), container.getWidth(), container.getHeight());
        });

        container.addEventFilter(MouseEvent.MOUSE_EXITED, e -> o.hide());
        return container;
    }

    // ── Overlay interno ──────────────────────────────────────────────────────

    private static final class CrosshairOverlay {
        final Pane   pane = new Pane();
        final Line   vLine;
        final Line   hLine;
        final Circle dot;
        final Label  tip;

        CrosshairOverlay() {
            vLine = makeLine();
            hLine = makeLine();
            dot   = makeDot();
            tip   = makeTip();
            pane.setMouseTransparent(true);
            pane.setPickOnBounds(false);
            pane.getChildren().addAll(hLine, vLine, dot, tip);
        }

        void update(double plotX, double plotY, Bounds plot,
                    String text, double containerW, double containerH) {
            vLine.setStartX(plotX); vLine.setEndX(plotX);
            vLine.setStartY(plot.getMinY()); vLine.setEndY(plot.getMaxY());

            hLine.setStartX(plot.getMinX()); hLine.setEndX(plot.getMaxX());
            hLine.setStartY(plotY); hLine.setEndY(plotY);

            dot.setCenterX(plotX);
            dot.setCenterY(plotY);

            tip.setText(text);

            // Posiciona o tooltip evitando overflow
            double tipX = plotX + 14;
            double tipY = plotY - 50;
            if (tipX + 140 > containerW) tipX = plotX - 150;
            if (tipY < plot.getMinY())   tipY = plotY + 8;
            tip.setLayoutX(tipX);
            tip.setLayoutY(tipY);

            vLine.setVisible(true); hLine.setVisible(true);
            dot.setVisible(true); tip.setVisible(true);
        }

        void hide() {
            vLine.setVisible(false); hLine.setVisible(false);
            dot.setVisible(false); tip.setVisible(false);
        }
    }

    // ── Fábricas ─────────────────────────────────────────────────────────────

    private static Line makeLine() {
        Line l = new Line();
        l.setStroke(Color.rgb(180, 190, 210, 0.40));
        l.setStrokeWidth(1.0);
        l.getStrokeDashArray().addAll(4.0, 3.0);
        l.setMouseTransparent(true);
        l.setVisible(false);
        return l;
    }

    private static Circle makeDot() {
        Circle c = new Circle(4.5);
        c.setFill(Color.rgb(226, 232, 240, 0.95));
        c.setStroke(Color.WHITE);
        c.setStrokeWidth(1.5);
        c.setMouseTransparent(true);
        c.setVisible(false);
        return c;
    }

    private static Label makeTip() {
        Label l = new Label();
        l.getStyleClass().add("crosshair-tooltip");
        l.setMouseTransparent(true);
        l.setVisible(false);
        return l;
    }

    private static boolean outOfPlot(Point2D mp, Bounds pb) {
        return mp.getX() < 0 || mp.getX() > pb.getWidth()
            || mp.getY() < 0 || mp.getY() > pb.getHeight();
    }
}

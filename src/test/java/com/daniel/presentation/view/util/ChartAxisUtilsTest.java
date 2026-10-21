package com.daniel.presentation.view.util;

import com.daniel.presentation.view.FxTestSupport;
import javafx.scene.chart.NumberAxis;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-08: price axes (Análise, média móvel) were bounded at ±5 % of the range with a sixth of it as
 * the step — 34.704 / 33.856 / … and 29.006 / 30.384 / …: true values, unreadable ticks. A value axis
 * steps by 1, 2, 2.5 or 5 × 10ⁿ from a multiple of that step, keeps a margin around the data and
 * shows three to nine intervals, whatever the price range (flat series included).
 */
class ChartAxisUtilsTest {

    @Test
    void valueAxisStepsRoundlyAroundEveryValue() {
        FxTestSupport.runAndWait(() -> {
            List<String> problems = new ArrayList<>();
            double[][] ranges = {{29.617, 34.704}, {29.006, 37.276}, {0.512, 0.519}, {38.52, 38.52}, {0, 0},
                    {1_234.56, 98_765.43}, {-3.2, 14.9}, {0.0001, 0.0009}};
            for (double[] r : ranges) {
                NumberAxis y = new NumberAxis();
                y.setAutoRanging(true);
                ChartAxisUtils.roundValueAxis(y, r[0], r[1]);
                String where = r[0] + ".." + r[1] + " → " + y.getLowerBound() + ".." + y.getUpperBound() + " step " + y.getTickUnit();
                double unit = y.getTickUnit();
                if (y.isAutoRanging()) problems.add(where + ": still auto-ranging");
                if (!(unit > 0)) { problems.add(where + ": no step"); continue; }
                double mantissa = unit / Math.pow(10, Math.floor(Math.log10(unit)));
                if (java.util.stream.DoubleStream.of(1, 2, 2.5, 5).noneMatch(m -> Math.abs(m - mantissa) < 1e-9))
                    problems.add(where + ": step not round");
                for (double bound : new double[]{y.getLowerBound(), y.getUpperBound()}) {
                    double steps = bound / unit;
                    if (Math.abs(steps - Math.rint(steps)) > 1e-6) problems.add(where + ": bound off the step");
                }
                if (y.getLowerBound() > r[0] || y.getUpperBound() < r[1]) problems.add(where + ": a value falls outside");
                if (r[1] > r[0] && (y.getLowerBound() >= r[0] || y.getUpperBound() <= r[1])) problems.add(where + ": no margin around the data");
                double intervals = (y.getUpperBound() - y.getLowerBound()) / unit;
                if (intervals < 3 - 1e-9 || intervals > 9 + 1e-9) problems.add(where + ": " + intervals + " intervals");
            }
            assertEquals(List.of(), problems);
        });
    }
}

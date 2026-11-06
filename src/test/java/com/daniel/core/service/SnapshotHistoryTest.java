package com.daniel.core.service;

import com.daniel.core.domain.entity.DailySummary;
import com.daniel.core.service.DailyTrackingUseCase.SeriesPoint;
import com.daniel.core.service.HistoryFixtures.Fixture;
import com.daniel.infrastructure.persistence.config.Database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.daniel.core.service.HistoryFixtures.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-32 S1: what the readers of the financial history return today (with the stored zero of the ended
 * positions) and what changes if that zero did not exist (S1:RF-03). Everything runs on synthetic test databases
 * (a folder the test owns); no real data or preference is opened.
 */
class SnapshotHistoryTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        Database.close();
    }

    private Fixture built(String name) throws Exception {
        return HistoryFixtures.build(name, Files.createDirectories(tmp.resolve(name)));
    }

    // =====================================================================
    // S1:RF-03.1 — today's behaviour, values computed by hand from the fixture
    // =====================================================================

    /**
     * H1 by hand. Quotes: A 20.00, B 50.00. Day 1: deposit 1 000 000; buy A 10 x 20.00 = 20 000, buy B 10 x 50.00 =
     * 50 000; cash 930 000. Day 3: A sold whole, 10 x 22.00 = 22 000; cash 952 000, A is zero.
     * A per day: d1 10 x 20.00 = 20 000, d2 20 000, d3 to d5 0 (the explicit zero).
     * Investments (A + B): d1, d2 = 20 000 + 50 000 = 70 000; d3 to d5 = 0 + 50 000 = 50 000.
     * Portfolio (investments + cash): d1, d2 = 70 000 + 930 000 = 1 000 000; d3 to d5 = 50 000 + 952 000 = 1 002 000.
     */
    @Test
    void h1_todaysValues_seriesPerAsset_andAggregates() throws Exception {
        Fixture f = built("H1");
        f.open();
        DailyTrackingUseCase uc = HistoryFixtures.useCase();
        assertEquals(List.of(
                new SeriesPoint(d(1), 20_000L), new SeriesPoint(d(2), 20_000L), new SeriesPoint(d(3), 0L),
                new SeriesPoint(d(4), 0L), new SeriesPoint(d(5), 0L)), uc.seriesForInvestment((int) f.id("A")));
        assertEquals(List.of(
                new SeriesPoint(d(1), 50_000L), new SeriesPoint(d(2), 50_000L), new SeriesPoint(d(3), 50_000L),
                new SeriesPoint(d(4), 50_000L), new SeriesPoint(d(5), 50_000L)), uc.seriesForInvestment((int) f.id("B")));
        assertEquals(Map.of(d(1), 70_000L, d(2), 70_000L, d(3), 50_000L, d(4), 50_000L, d(5), 50_000L),
                uc.getInvestmentSnapshotSeries(d(1), d(5)));
        assertEquals(Map.of(d(1), 1_000_000L, d(2), 1_000_000L, d(3), 1_002_000L, d(4), 1_002_000L, d(5), 1_002_000L),
                uc.getPortfolioSnapshotSeries(d(1), d(5)));
    }

    /**
     * H2 by hand: H1, then day 6 A is bought again, 5 x 20.00 = 10 000; cash 952 000 - 10 000 = 942 000.
     * summaryFor(d6): cash 942 000 (yesterday 952 000, delta -10 000); A today 5 x 20.00 = 10 000 and yesterday the
     * stored zero, so A profit = 10 000 - 0 = 10 000; B 50 000 - 50 000 = 0. Total = 942 000 + 10 000 + 50 000 =
     * 1 002 000; total profit = 10 000.
     */
    @Test
    void h2_todaysValues_summaryOnReopeningDay() throws Exception {
        Fixture f = built("H2");
        f.open();
        DailyTrackingUseCase uc = HistoryFixtures.useCase();
        long a = f.id("A");
        long b = f.id("B");
        DailySummary expected = new DailySummary(d(6), 1_002_000L, 10_000L, 942_000L, -10_000L,
                Map.of(a, 10_000L, b, 50_000L), Map.of(a, 10_000L, b, 0L));
        assertEquals(expected, uc.summaryFor(d(6)));
        assertEquals(List.of(
                new SeriesPoint(d(1), 20_000L), new SeriesPoint(d(2), 20_000L), new SeriesPoint(d(3), 0L),
                new SeriesPoint(d(4), 0L), new SeriesPoint(d(5), 0L), new SeriesPoint(d(6), 10_000L)),
                uc.seriesForInvestment((int) a));
    }

    /**
     * H4 by hand. Day 1: deposit 1 000 000; A 10 x 20.00 = 20 000 and B 4 x 50.00 = 20 000 bought; cash 960 000.
     * Day 2: A sold 10 x 22.00 = 22 000 and B sold 4 x 55.00 = 22 000; cash 960 000 + 44 000 = 1 004 000, both are zero.
     * Investments: d1 = 20 000 + 20 000 = 40 000; d2 to d4 = 0 (the zero is stored).
     * Portfolio: d1 = 40 000 + 960 000 = 1 000 000; d2 to d4 = 0 + 1 004 000 = 1 004 000 (days with cash only).
     * The month's wealth in the reports is the last entry: d4 = 1 004 000.
     */
    @Test
    void h4_todaysValues_monthWealthAndCashOnlyDay() throws Exception {
        Fixture f = built("H4");
        f.open();
        DailyTrackingUseCase uc = HistoryFixtures.useCase();
        LocalDate monthStart = LocalDate.of(2026, 3, 1);
        LocalDate monthEnd = LocalDate.of(2026, 3, 31);
        assertEquals(Map.of(d(1), 40_000L, d(2), 0L, d(3), 0L, d(4), 0L), uc.getInvestmentSnapshotSeries(monthStart, monthEnd));
        TreeMap<LocalDate, Long> portfolio = uc.getPortfolioSnapshotSeries(monthStart, monthEnd);
        assertEquals(Map.of(d(1), 1_000_000L, d(2), 1_004_000L, d(3), 1_004_000L, d(4), 1_004_000L), portfolio);
        assertTrue(portfolio.containsKey(d(2)), "the day with cash only (d2) is in the series");
        assertEquals(d(4), portfolio.lastKey());
        assertEquals(1_004_000L, portfolio.lastEntry().getValue());
    }

    // =====================================================================
    // S1:RF-03.2 — the effect of taking the stored zero away, per reader and fixture
    // =====================================================================

    /** Predictions asserted (inventory 3.2, composition of the Terms): "fixture|reader" to "changes". */
    private static final Map<String, Boolean> PREDICTED = new LinkedHashMap<>();

    static {
        PREDICTED.put("H1|seriesForInvestment(A)", true);
        PREDICTED.put("H1|getInvestmentSnapshotSeries", false);
        PREDICTED.put("H1|getPortfolioSnapshotSeries", false);
        PREDICTED.put("H1|summaryFor", false);
        PREDICTED.put("H1|loadEntry", false);
        PREDICTED.put("H2|summaryFor", true);
        PREDICTED.put("H2|seriesForInvestment(A)", true);
        PREDICTED.put("H4|getPortfolioSnapshotSeries", true);
        PREDICTED.put("H4|getInvestmentSnapshotSeries", true);
    }

    /**
     * On a COPY of each fixture's database, deletes only the stored zero of the ended positions and compares every
     * reader with the original database. Prints one line per reader and fixture; asserts only {@link #PREDICTED}.
     * H3, H5, H6 and H7 are recorded, not asserted.
     */
    @Test
    void withoutTheStoredZero_whichReadersChange() throws Exception {
        List<String> contradicted = new ArrayList<>();
        for (String name : HistoryFixtures.NAMES) {
            Fixture f = built(name);
            Path folder = f.dbFile.getParent();
            f.open();
            Map<String, String> before = HistoryFixtures.readAll(f);
            f.close();

            Fixture copy = HistoryFixtures.copyOf(f, folder, name + "-sem-zero");
            long expectedRows = HistoryFixtures.countEndedZeros(copy.dbFile);
            int deleted = HistoryFixtures.deleteEndedZeros(copy.dbFile);
            assertTrue(deleted > 0, name + ": the fixture must hold stored zeros of ended positions");
            assertEquals(expectedRows, deleted, name + ": only the stored zeros are deleted");

            copy.open();
            Map<String, String> after = HistoryFixtures.readAll(copy);
            copy.close();

            for (Map.Entry<String, String> e : before.entrySet()) {
                boolean changed = !e.getValue().equals(after.get(e.getKey()));
                System.out.println("BRK32S1 SEM_ZERO " + name + " " + e.getKey() + " " + (changed ? "MUDA" : "IGUAL"));
                Boolean predicted = PREDICTED.get(name + "|" + e.getKey());
                if (predicted != null && predicted != changed) {
                    contradicted.add(e.getKey() + " em " + name + ": previsto " + (predicted ? "MUDA" : "IGUAL"));
                }
            }
        }
        assertEquals(List.of(), contradicted, "predictions contradicted by the observed result");
    }
}

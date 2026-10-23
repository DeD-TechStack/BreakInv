package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.backup.SpaceEstimate.Estimate;
import com.daniel.infrastructure.persistence.backup.SpaceEstimate.Place;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static com.daniel.infrastructure.persistence.backup.SpaceEstimate.*;
import static org.junit.jupiter.api.Assertions.*;

/** BRK-31 u2b: additional peak by operation, phase and volume (DESENHO-BRK31 §5.2), as pure arithmetic. */
class SpaceEstimateTest {

    private static final long MIB = 1L << 20;
    private static final long A = ALLOWANCE;

    private static Map<Place, Object> allOn(Object volume) {
        Map<Place, Object> m = new EnumMap<>(Place.class);
        for (Place p : Place.values()) m.put(p, volume);
        return m;
    }

    private static Map<Place, Object> eachOnItsOwn() {
        Map<Place, Object> m = new EnumMap<>(Place.class);
        for (Place p : Place.values()) m.put(p, p.name());
        return m;
    }

    @Test
    void bounds_areExplicit_andSaturate() {
        assertEquals(10 * MIB + A, copyBound(10 * MIB));
        assertEquals(32 * MIB + MIB + A, journalBound(32 * MIB), "páginas + 8 bytes cada (página ≥ 512) + cabeçalhos");
        assertEquals(Long.MAX_VALUE, copyBound(Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, journalBound(Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, sortBound(Long.MAX_VALUE / 2));
    }

    @Test
    void verifiedCopy_growsOnlyTheTarget_plusTheSortTemp() {
        Estimate e = verifiedCopy(10 * MIB, Place.COPIES);

        assertTrue(e.known());
        assertEquals(copyBound(10 * MIB), e.peak(Place.COPIES));
        assertEquals(0, e.peak(Place.DATA));
        assertEquals(sortBound(10 * MIB), e.peak(Place.TEMP));
        assertEquals(0, e.peak(Place.DESTINATION));
        assertEquals(copyBound(10 * MIB), verifiedCopy(10 * MIB, Place.DESTINATION).peak(Place.DESTINATION));
    }

    @Test
    void restore_withSmallerCopy_journalsTheCurrentData_andDoesNotGrow() {
        long c = 4 * MIB, d = 12 * MIB;
        Estimate e = restore(c, d, false, false, false);

        assertEquals(journalBound(d), e.peak(Place.DATA), "journal das páginas atuais; o arquivo não cresce");
        assertEquals(c + A + copyBound(d), e.peak(Place.COPIES), "preparado + cópia pré-restauração");
    }

    @Test
    void restore_withLargerCopy_addsTheGrowth_andTheWayBackJournalsTheCopy() {
        long c = 12 * MIB, d = 4 * MIB;
        Estimate e = restore(c, d, false, false, false);

        assertEquals((c - d) + journalBound(c), e.peak(Place.DATA), "a volta registra as páginas de C");
        assertTrue(e.phases().stream().anyMatch(p -> p.name().startsWith("volta")));
    }

    @Test
    void whatIsAlreadyOnDisk_isNotCountedAgain() {
        long c = 4 * MIB, d = 12 * MIB;
        Estimate staged = restore(c, d, true, false, false);
        Estimate reused = restore(c, d, true, true, false);

        assertEquals(copyBound(d), staged.peak(Place.COPIES), "o preparado já ocupa o disco");
        assertEquals(0, reused.peak(Place.COPIES), "a cópia aproveitada também");
        assertEquals(journalBound(d), reused.peak(Place.DATA));
    }

    @Test
    void olderCopy_addsThePreMigrationCopy_ofTheRestoredData() {
        long c = 4 * MIB, d = 12 * MIB;
        Estimate e = restore(c, d, true, true, true);

        assertEquals(copyBound(c), e.peak(Place.COPIES));
    }

    @Test
    void restoreOverDamaged_keepsTheRawFile_andCountsTheFailedAttemptOnlyOnce() {
        long c = 4 * MIB, d = 12 * MIB;
        Estimate e = restoreOverDamaged(c, d, false);

        assertEquals(c + A + copyBound(d), e.peak(Place.COPIES), "tentativa removida antes do arquivo bruto, que tem D exato");
        assertEquals(journalBound(d), e.peak(Place.DATA));
    }

    @Test
    void maintenance_isOnlyACalculation_withTempAndJournal() {
        long d = 12 * MIB;
        Estimate e = maintenance(d, false);

        assertEquals(copyBound(d), e.peak(Place.COPIES));
        assertEquals(journalBound(d), e.peak(Place.DATA));
        assertEquals(Math.max(copyBound(d), sortBound(d)), e.peak(Place.TEMP));
    }

    @Test
    void sharedVolume_sumsThePlacesWithinAPhase_beforeTakingTheMaximum() {
        long c = 4 * MIB, d = 12 * MIB;
        Estimate e = restore(c, d, false, false, false);

        Map<Object, Long> shared = e.peakPerVolume(allOn("C:"));
        Map<Object, Long> apart = e.peakPerVolume(eachOnItsOwn());

        long replacing = (c + A + copyBound(d)) + journalBound(d);
        long phaseMax = e.phases().stream()
                .mapToLong(p -> p.live().values().stream().mapToLong(Long::longValue).sum()).max().orElseThrow();
        assertEquals(phaseMax, shared.get("C:"), "soma dentro de cada fase, depois o máximo");
        assertTrue(shared.get("C:") >= replacing, "substituição: cópias + journal no mesmo disco");
        assertTrue(shared.get("C:") < e.peak(Place.COPIES) + e.peak(Place.DATA) + e.peak(Place.TEMP),
                "não soma picos de fases diferentes");
        assertEquals(e.peak(Place.DATA), apart.get("DATA"));
        assertEquals(e.peak(Place.COPIES), apart.get("COPIES"));
    }

    @Test
    void sharedVolume_withTheV1Hypothesis_isNotAbove_C_plus_D_plus_D_plus_growth_byMoreThanTheMargins() {
        long c = 12 * MIB, d = 4 * MIB;
        long v1 = c + d + d + Math.max(0, c - d);
        long model = restore(c, d, false, false, false).peakPerVolume(allOn("C:")).get("C:");

        assertTrue(model >= v1, "o modelo cobre a hipótese do v1");
    }

    @Test
    void unknownSizes_giveAnUnknownEstimate_neverZero() {
        Estimate e = restore(-1, 12 * MIB, false, false, false);

        assertFalse(e.known());
        assertFalse(e.unknownReason().isBlank());
        assertEquals(-1, e.peak(Place.COPIES));
        assertThrows(IllegalStateException.class, () -> e.peakPerVolume(allOn("C:")));
        assertFalse(verifiedCopy(-1, Place.COPIES).known());
    }

    @Test
    void aPlaceInUse_withoutAVolume_isRejected() {
        Map<Place, Object> missing = new EnumMap<>(allOn("C:"));
        missing.remove(Place.DATA);

        assertThrows(NullPointerException.class,
                () -> restore(MIB, MIB, false, false, false).peakPerVolume(missing));
    }
}

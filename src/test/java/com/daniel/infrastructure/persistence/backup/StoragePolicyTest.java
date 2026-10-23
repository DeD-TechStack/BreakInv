package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.backup.StoragePolicy.Evaluation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static com.daniel.infrastructure.persistence.backup.StoragePolicy.Level.*;
import static org.junit.jupiter.api.Assertions.*;

/** BRK-31 u2a: the three levels and their exact boundaries (DESENHO-BRK31 §5.1, binary units). */
class StoragePolicyTest {

    private static final long MIB = 1L << 20;
    private static final StorageLimits DEFAULTS = StorageLimits.defaults();

    private static SpaceProbe.Space free(long bytes) {
        return new SpaceProbe.Measured(bytes, Path.of("."), "VOL");
    }

    @Test
    void defaults_areTheBrk29Values_inBinaryUnits() {
        assertEquals(new StorageLimits(100 * MIB, 64 * MIB, 64 * MIB), DEFAULTS);
    }

    @Test
    void databaseWarning_startsAtExactly100MiB() {
        assertFalse(StoragePolicy.evaluate(100 * MIB - 1, free(1L << 40), DEFAULTS).is(DB_WARNING));
        assertTrue(StoragePolicy.evaluate(100 * MIB, free(1L << 40), DEFAULTS).is(DB_WARNING));
    }

    @Test
    void reserve_isStrictlyBelowTwiceTheDatabasePlusTheFloor() {
        long d = 10 * MIB;
        long target = 2 * d + 64 * MIB;
        Evaluation at = StoragePolicy.evaluate(d, free(target), DEFAULTS);
        Evaluation below = StoragePolicy.evaluate(d, free(target - 1), DEFAULTS);

        assertFalse(at.is(RESERVE));
        assertTrue(below.is(RESERVE));
        assertEquals(1, below.shortfall(RESERVE), "diz quanto falta");
        assertEquals(target, at.reserveTargetBytes());
    }

    @Test
    void writeLimit_isStrictlyBelow64MiB() {
        assertFalse(StoragePolicy.evaluate(MIB, free(64 * MIB), DEFAULTS).is(WRITE_LIMIT));
        Evaluation below = StoragePolicy.evaluate(MIB, free(64 * MIB - 1), DEFAULTS);
        assertTrue(below.is(WRITE_LIMIT));
        assertEquals(1, below.shortfall(WRITE_LIMIT));
    }

    @Test
    void measuredZero_isTheWorstCase_notUnknown() {
        Evaluation e = StoragePolicy.evaluate(MIB, free(0), DEFAULTS);

        assertTrue(e.is(WRITE_LIMIT));
        assertTrue(e.is(RESERVE));
        assertTrue(e.undetermined().isEmpty());
    }

    @Test
    void hugeDatabase_saturatesInsteadOfOverflowing() {
        Evaluation e = StoragePolicy.evaluate(Long.MAX_VALUE / 2 + 1, free(Long.MAX_VALUE - 1), DEFAULTS);

        assertEquals(Long.MAX_VALUE, e.reserveTargetBytes());
        assertTrue(e.is(RESERVE));
        assertTrue(e.is(DB_WARNING));
    }

    @Test
    void unavailableOrFailedSpace_leavesTheSpaceLevelsUndetermined_neverInactive() {
        for (SpaceProbe.Space s : new SpaceProbe.Space[]{new SpaceProbe.Unavailable("sem volume"),
                new SpaceProbe.Failed("IOException")}) {
            Evaluation e = StoragePolicy.evaluate(200 * MIB, s, DEFAULTS);

            assertEquals(Set.of(DB_WARNING), e.active(), s.toString());
            assertEquals(Set.of(RESERVE, WRITE_LIMIT), e.undetermined(), s.toString());
            assertEquals(-1, e.freeBytes());
            assertEquals(0, e.shortfall(RESERVE));
        }
    }

    @Test
    void unknownDatabaseSize_leavesWarningAndReserveUndetermined() {
        Evaluation e = StoragePolicy.evaluate(-1, free(10 * MIB), DEFAULTS);

        assertEquals(Set.of(WRITE_LIMIT), e.active());
        assertEquals(Set.of(DB_WARNING, RESERVE), e.undetermined());
        assertEquals(-1, e.reserveTargetBytes());
    }

    @Test
    void configuredLimits_areUsed() {
        StorageLimits custom = new StorageLimits(MIB, 16 * MIB, 32 * MIB);
        Evaluation e = StoragePolicy.evaluate(MIB, free(20 * MIB), custom);

        assertTrue(e.is(DB_WARNING));
        assertTrue(e.is(WRITE_LIMIT), "20 MiB < 32 MiB");
        assertFalse(e.is(RESERVE), "20 MiB ≥ 2 × 1 MiB + 16 MiB");
    }
}

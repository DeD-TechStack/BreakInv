package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u2a: the limits live in the user's preferences, apart from the database (DESENHO-BRK31 D-Q1). An
 * invalid stored value falls back to the safe default, and a change that could not be flushed is reported as
 * not saved, with the previous limits still in effect.
 */
class StoragePreferencesTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    @Test
    void emptyNode_usesTheDefaults() {
        StoragePreferences.Loaded loaded = new StoragePreferences(new MemoryPreferences()).load();

        assertEquals(StorageLimits.defaults(), loaded.limits());
        assertTrue(loaded.corrected().isEmpty());
    }

    @Test
    void save_thenLoad_roundTrips_andFlushesOnce() {
        MemoryPreferences node = new MemoryPreferences();
        StoragePreferences prefs = new StoragePreferences(node);
        StorageLimits custom = new StorageLimits(200 * MIB, 128 * MIB, 96 * MIB);

        StoragePreferences.SaveResult r = prefs.save(custom);

        assertEquals(new StoragePreferences.Saved(custom), r);
        assertEquals(1, node.flushes);
        assertEquals(custom, new StoragePreferences(node).load().limits());
        assertEquals(Set.of("warnDbBytes", "reserveFloorBytes", "writeLimitBytes"), node.values().keySet(),
                "só os três limites; nada de token");
    }

    @Test
    void invalidStoredValues_fallBackToTheDefault_andSaySo() {
        MemoryPreferences node = new MemoryPreferences();
        node.put("warnDbBytes", "abc");
        node.put("reserveFloorBytes", "-5");
        node.put("writeLimitBytes", String.valueOf(64 * GIB)); // above the range

        StoragePreferences.Loaded loaded = new StoragePreferences(node).load();

        assertEquals(StorageLimits.defaults(), loaded.limits());
        assertEquals(3, loaded.corrected().size(), loaded.corrected().toString());
    }

    @Test
    void outOfRange_isRejected_andNothingIsWritten() {
        MemoryPreferences node = new MemoryPreferences();
        StoragePreferences prefs = new StoragePreferences(node);

        StoragePreferences.SaveResult r = prefs.save(new StorageLimits(100 * MIB, 64 * MIB, MIB));

        StoragePreferences.Rejected rejected = assertInstanceOf(StoragePreferences.Rejected.class, r);
        assertFalse(rejected.reason().isBlank());
        assertEquals(Map.of(), node.values());
        assertEquals(0, node.flushes);
    }

    @Test
    void failedFlush_isNotSaved_andThePreviousLimitsStayInEffect() {
        MemoryPreferences node = new MemoryPreferences();
        StoragePreferences prefs = new StoragePreferences(node);
        StorageLimits before = new StorageLimits(200 * MIB, 128 * MIB, 96 * MIB);
        assertInstanceOf(StoragePreferences.Saved.class, prefs.save(before));
        node.failFlush = true;

        StoragePreferences.SaveResult r = prefs.save(new StorageLimits(300 * MIB, 256 * MIB, 128 * MIB));

        StoragePreferences.NotSaved notSaved = assertInstanceOf(StoragePreferences.NotSaved.class, r);
        assertEquals(before, notSaved.stillInEffect());
        assertFalse(notSaved.reason().isBlank());
        assertEquals(before, prefs.load().limits(), "a mudança só em memória não fica valendo");
    }

    @Test
    void ranges_haveExplicitBoundaries() {
        assertNull(new StorageLimits(MIB, 16 * MIB, 16 * MIB).problem());
        assertNull(new StorageLimits(1024 * GIB, 16 * GIB, 16 * GIB).problem());
        assertNotNull(new StorageLimits(MIB - 1, 16 * MIB, 16 * MIB).problem());
        assertNotNull(new StorageLimits(1024 * GIB + 1, 16 * MIB, 16 * MIB).problem());
        assertNotNull(new StorageLimits(MIB, 16 * MIB - 1, 16 * MIB).problem());
        assertNotNull(new StorageLimits(MIB, 16 * GIB + 1, 16 * MIB).problem());
        assertNotNull(new StorageLimits(MIB, 16 * MIB, 16 * MIB - 1).problem());
        assertNotNull(new StorageLimits(MIB, 16 * MIB, 16 * GIB + 1).problem());
    }
}

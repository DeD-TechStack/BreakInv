package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u5 F1, RF-02 and cases 5 and 10: the read-only need per place of {@link StorageGuard#need}. It uses the
 * same place assembly and the same probe as {@code operation}, groups the places by measured volume, and never
 * decides, refuses, or touches the guard's state.
 */
class StorageGuardNeedTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;
    private static final long D = 10 * MIB;

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    Path sqliteTemp;

    @BeforeEach
    void setUp() {
        db = tempDir.resolve("dados").resolve("breakinv.db");
        folder = tempDir.resolve("dados").resolve("backups");
        sqliteTemp = tempDir.resolve("tmp");
    }

    StorageGuard guardOn(SpaceProbe probe) {
        return new StorageGuard(db, sqliteTemp, probe, StorageLimits::defaults, () -> 0L);
    }

    static SpaceEstimate.Estimate copy() {
        return SpaceEstimate.verifiedCopy(D, SpaceEstimate.Place.COPIES);
    }

    StorageGuard.Need need(StorageGuard guard, SpaceEstimate.Estimate estimate, Map<SpaceEstimate.Place, Path> places) {
        return assertDoesNotThrow(() -> guard.need(estimate, places), "a necessidade por lugar é só leitura e não lança");
    }

    StorageGuard.NeedItem itemWith(StorageGuard.Need need, SpaceEstimate.Place place) {
        List<StorageGuard.NeedItem> found = need.items().stream().filter(i -> i.places().contains(place)).toList();
        assertEquals(1, found.size(), "um item só tem o lugar " + place + ": " + need.items());
        return found.get(0);
    }

    // ---- case 4 / F1:RF-02: the calculation, per volume ------------------------------------------------------

    @Test
    void sharedVolume_isOneItem_withTheSummedPeak_andTheMeasuredFree() {
        StorageGuard.Need n = need(guardOn(FakeSpaceProbe.all(GIB)), copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertTrue(n.estimated());
        assertNull(n.unknownReason());
        assertEquals("cópia conferida", n.operation());
        assertEquals(1, n.items().size(), n.items().toString());
        StorageGuard.NeedItem item = n.items().get(0);
        assertEquals(Set.of(SpaceEstimate.Place.COPIES, SpaceEstimate.Place.TEMP), item.places());
        assertEquals(SpaceEstimate.copyBound(D) + SpaceEstimate.sortBound(D), item.neededBytes());
        assertEquals(GIB, item.freeBytes());
        assertTrue(item.measured());
        assertNull(item.unmeasuredReason());
        assertEquals(sqliteTemp, item.shownAs(), "a pasta do primeiro lugar daquele volume, na ordem das fases");
    }

    @Test
    void separateVolumes_areOneItemPerVolume_eachWithItsOwnNeedAndFree() {
        FakeSpaceProbe apart = FakeSpaceProbe.all(0)
                .on(folder, new SpaceProbe.Measured(SpaceEstimate.copyBound(D) + 5, folder, "CÓPIAS"))
                .on(sqliteTemp, new SpaceProbe.Measured(SpaceEstimate.sortBound(D) + 7, sqliteTemp, "TEMP"));

        StorageGuard.Need n = need(guardOn(apart), copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertEquals(2, n.items().size(), n.items().toString());
        StorageGuard.NeedItem copies = itemWith(n, SpaceEstimate.Place.COPIES);
        StorageGuard.NeedItem temp = itemWith(n, SpaceEstimate.Place.TEMP);
        assertEquals(Set.of(SpaceEstimate.Place.COPIES), copies.places());
        assertEquals(Set.of(SpaceEstimate.Place.TEMP), temp.places());
        assertEquals(SpaceEstimate.copyBound(D), copies.neededBytes());
        assertEquals(SpaceEstimate.copyBound(D) + 5, copies.freeBytes());
        assertEquals(folder, copies.shownAs());
        assertEquals(SpaceEstimate.sortBound(D), temp.neededBytes());
        assertEquals(SpaceEstimate.sortBound(D) + 7, temp.freeBytes());
        assertEquals(sqliteTemp, temp.shownAs());
        assertSame(temp, n.items().get(0), "the order of the map peakPerVolume returns: the first phase uses TEMP");
    }

    @Test
    void placesOverrideTheDefaults_forDataAndTemp() {
        Path elsewhere = tempDir.resolve("outro tmp");
        FakeSpaceProbe probe = FakeSpaceProbe.all(0)
                .on(elsewhere, new SpaceProbe.Measured(GIB, elsewhere, "OUTRO"))
                .on(folder, new SpaceProbe.Measured(GIB, folder, "CÓPIAS"));

        StorageGuard.Need n = need(guardOn(probe), copy(),
                Map.of(SpaceEstimate.Place.COPIES, folder, SpaceEstimate.Place.TEMP, elsewhere));

        assertEquals(elsewhere, itemWith(n, SpaceEstimate.Place.TEMP).shownAs());
        assertTrue(itemWith(n, SpaceEstimate.Place.TEMP).measured(), "the TEMP place given is the one measured");
    }

    // ---- case 10: a place without a measure, and a size of -1 ------------------------------------------------

    @Test
    void aPlaceWithoutAMeasure_isAnItemWithTheReason_andDoesNotJoinAnyVolume() {
        FakeSpaceProbe probe = FakeSpaceProbe.all(GIB).on(folder, new SpaceProbe.Failed("falha do teste"));

        StorageGuard.Need n = need(guardOn(probe), copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertTrue(n.estimated(), "the estimate exists; only a measure is missing");
        assertEquals(2, n.items().size(), n.items().toString());
        StorageGuard.NeedItem copies = itemWith(n, SpaceEstimate.Place.COPIES);
        assertFalse(copies.measured());
        assertEquals("falha do teste", copies.unmeasuredReason());
        assertEquals(-1, copies.freeBytes());
        assertEquals(Set.of(SpaceEstimate.Place.COPIES), copies.places());
        assertEquals(SpaceEstimate.copyBound(D), copies.neededBytes(), "alone: no sum with the temp phase");
        assertEquals(folder, copies.shownAs());
        StorageGuard.NeedItem temp = itemWith(n, SpaceEstimate.Place.TEMP);
        assertTrue(temp.measured());
        assertEquals(SpaceEstimate.sortBound(D), temp.neededBytes());
    }

    @Test
    void unmeasuredReasons_followTheTextOfTheGuard() {
        StorageGuard.Need unavailable = need(guardOn(FakeSpaceProbe.all(GIB).on(folder, new SpaceProbe.Unavailable("sem pasta"))),
                copy(), Map.of(SpaceEstimate.Place.COPIES, folder));
        assertEquals("sem pasta", itemWith(unavailable, SpaceEstimate.Place.COPIES).unmeasuredReason());

        StorageGuard.Need noVolume = need(guardOn(FakeSpaceProbe.all(GIB).on(folder, new SpaceProbe.Measured(GIB, folder, null))),
                copy(), Map.of(SpaceEstimate.Place.COPIES, folder));
        StorageGuard.NeedItem item = itemWith(noVolume, SpaceEstimate.Place.COPIES);
        assertFalse(item.measured(), "a measure without a volume identity is no measure");
        assertEquals("sem medida", item.unmeasuredReason());

        StorageGuard.Need notGiven = need(guardOn(FakeSpaceProbe.all(GIB)),
                SpaceEstimate.verifiedCopy(D, SpaceEstimate.Place.DESTINATION), Map.of());
        StorageGuard.NeedItem destination = itemWith(notGiven, SpaceEstimate.Place.DESTINATION);
        assertFalse(destination.measured());
        assertEquals("pasta não informada", destination.unmeasuredReason());
    }

    @Test
    void twoPlacesWithoutAMeasure_stayTwoItems() {
        StorageGuard.Need n = need(guardOn(FakeSpaceProbe.none()), copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertEquals(2, n.items().size(), "an unknown is never presumed to be the same volume: " + n.items());
        assertTrue(n.items().stream().noneMatch(StorageGuard.NeedItem::measured));
    }

    @Test
    void anUnknownSize_isNoEstimate_withoutItems_andDoesNotThrow() {
        SpaceEstimate.Estimate unknown = SpaceEstimate.verifiedCopy(-1, SpaceEstimate.Place.COPIES);

        StorageGuard.Need n = need(guardOn(FakeSpaceProbe.all(GIB)), unknown, Map.of(SpaceEstimate.Place.COPIES, folder));

        assertFalse(n.estimated());
        assertEquals(unknown.unknownReason(), n.unknownReason());
        assertNotNull(n.unknownReason());
        assertEquals(List.of(), n.items());
        assertEquals(unknown.operation(), n.operation());
    }

    @Test
    void anUnexpectedException_isNotSwallowed() {
        SpaceProbe boom = path -> {
            throw new IllegalArgumentException("boom");
        };

        assertThrows(IllegalArgumentException.class,
                () -> guardOn(boom).need(copy(), Map.of(SpaceEstimate.Place.COPIES, folder)));
    }

    // ---- case 5: reading changes nothing in the guard --------------------------------------------------------

    @Test
    void regression_userWriteWithoutAMeasure_setsTheWarning_andOperationDecidesTheSameTwice() {
        StorageGuard guard = guardOn(FakeSpaceProbe.none());
        assertEquals(Optional.empty(), guard.lastWarning());

        assertInstanceOf(StorageGuard.Unmeasured.class, guard.userWrite());
        assertTrue(guard.lastWarning().isPresent());
        StorageGuard.Decision first = guard.operation(copy(), Map.of(SpaceEstimate.Place.COPIES, folder));
        StorageGuard.Decision second = guard.operation(copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertInstanceOf(StorageGuard.Unmeasured.class, first);
        assertEquals(first, second);
    }

    @Test
    void need_withAnEmptyWarning_leavesItEmpty_andTheNextOperationDecidesAsBefore() {
        StorageGuard guard = guardOn(FakeSpaceProbe.none());
        StorageGuard.Decision before = guard.operation(copy(), Map.of(SpaceEstimate.Place.COPIES, folder));
        assertEquals(Optional.empty(), guard.lastWarning());

        need(guard, copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertEquals(Optional.empty(), guard.lastWarning(), "reading must not record a warning");
        assertEquals(before, guard.operation(copy(), Map.of(SpaceEstimate.Place.COPIES, folder)));
    }

    @Test
    void need_withAFilledWarning_keepsIt_andDoesNotRefreshTheCachedMeasure() {
        AtomicReference<SpaceProbe> current = new AtomicReference<>(FakeSpaceProbe.none());
        StorageGuard guard = guardOn(path -> current.get().usableSpace(path));
        StorageGuard.Decision unmeasured = guard.userWrite();
        assertInstanceOf(StorageGuard.Unmeasured.class, unmeasured);
        Optional<String> warning = guard.lastWarning();
        assertTrue(warning.isPresent());
        StorageGuard.Decision operationBefore = guard.operation(copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        // the volume answers now; a decision would clear the warning and refresh the measure, a reading must not
        current.set(FakeSpaceProbe.all(GIB));
        StorageGuard.Need measured = need(guard, copy(), Map.of(SpaceEstimate.Place.COPIES, folder));
        current.set(FakeSpaceProbe.none());
        StorageGuard.Need notMeasured = need(guard, copy(), Map.of(SpaceEstimate.Place.COPIES, folder));

        assertTrue(measured.items().stream().allMatch(StorageGuard.NeedItem::measured));
        assertFalse(notMeasured.items().stream().anyMatch(StorageGuard.NeedItem::measured));
        assertEquals(warning, guard.lastWarning(), "reading must not clear nor change the warning");
        assertEquals(operationBefore, guard.operation(copy(), Map.of(SpaceEstimate.Place.COPIES, folder)));
        current.set(FakeSpaceProbe.all(GIB));
        assertEquals(unmeasured, guard.userWrite(), "the measure cached by userWrite is still the one in use (fixed clock)");
    }
}

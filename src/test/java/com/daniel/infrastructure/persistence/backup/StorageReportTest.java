package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u5 F1, RF-01 and RF-03 (restoreNeed): the reading of the data space that the screen will show, and the
 * need of a restore. Synthetic database in a temp folder, a fake probe and a throwaway preferences node.
 */
class StorageReportTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    Path sqliteTemp;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-23T06:00:00Z"));
    BackupService backups;
    Preferences node;
    StoragePreferences prefs;

    @BeforeEach
    void setUp() throws Exception {
        db = Files.createDirectories(tempDir.resolve("dados")).resolve("breakinv.db");
        sqliteTemp = Files.createDirectories(tempDir.resolve("tmp"));
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        seed("a");
        backups = service(db, folder, clock);
        node = Preferences.userRoot().node("/com/daniel/breakinv-test/" + UUID.randomUUID());
        prefs = new StoragePreferences(node);
    }

    @AfterEach
    void tearDown() throws Exception {
        Database.setPreMigrationHook(null);
        Database.close();
        try {
            node.removeNode();
        } catch (IllegalStateException alreadyRemoved) {
            // the test removed it
        }
    }

    StorageReport reportOn(SpaceProbe probe) {
        return new StorageReport(db, folder, sqliteTemp, probe, prefs, backups);
    }

    StorageGuard guardOn(SpaceProbe probe) {
        return new StorageGuard(db, sqliteTemp, probe, StorageLimits::defaults, System::nanoTime);
    }

    StorageReport.Reading read(StorageReport report) {
        return assertDoesNotThrow(report::read, "a medida que falha não faz a leitura lançar");
    }

    long dataBytes() throws Exception {
        long d = Files.size(db);
        assertTrue(d > 0, "the seeded database has a size");
        return d;
    }

    // ---- case 1: the reading with room, and at each level ----------------------------------------------------

    @Test
    void withRoomToSpare_isNormal_withNoActiveLevel() throws Exception {
        long d = dataBytes();

        StorageReport.Reading r = read(reportOn(FakeSpaceProbe.all(GIB)));

        assertEquals(d, r.snapshot().databaseBytes());
        assertFalse(r.snapshot().backupFolderExists(), "a copies folder that does not exist yet is just reported");
        assertInstanceOf(SpaceProbe.Measured.class, r.snapshot().space(StorageInventory.Role.DATA));
        assertEquals(java.util.Set.of(), r.evaluation().active());
        assertEquals(java.util.Set.of(), r.evaluation().undetermined());
        assertEquals(GIB, r.evaluation().freeBytes());
        assertEquals(StorageLimits.defaults(), r.limits());
        assertEquals(java.util.List.of(), r.corrected());
        assertFalse(r.automaticPostponed());
        assertNull(r.postponedMessage());
        assertEquals(StoragePolicy.evaluate(d, r.snapshot().space(StorageInventory.Role.DATA), r.limits()), r.evaluation());
    }

    @Test
    void theReserveLevel_isActive_onlyBecauseDIsTheRealSizeOfTheSameSnapshot() throws Exception {
        long d = dataBytes();
        StorageLimits limits = new StorageLimits(StorageLimits.defaults().warnDbBytes(), StorageLimits.MIN_FLOOR,
                StorageLimits.MIN_FLOOR);
        assertInstanceOf(StoragePreferences.Saved.class, prefs.save(limits));
        // 2 x D + floor is the target: this free space is below it only with the real D, with -1 it is undetermined
        long free = StorageLimits.MIN_FLOOR + d;

        StorageReport.Reading r = read(reportOn(FakeSpaceProbe.all(free)));

        assertEquals(java.util.Set.of(StoragePolicy.Level.RESERVE), r.evaluation().active());
        assertEquals(java.util.Set.of(), r.evaluation().undetermined());
        assertEquals(2 * d + StorageLimits.MIN_FLOOR, r.evaluation().reserveTargetBytes());
        assertEquals(free, r.evaluation().freeBytes());
        assertEquals(StoragePolicy.evaluate(d, r.snapshot().space(StorageInventory.Role.DATA), limits), r.evaluation());
        assertNotEquals(StoragePolicy.evaluate(-1, r.snapshot().space(StorageInventory.Role.DATA), limits),
                r.evaluation(), "D is never -1 in a reading");
    }

    @Test
    void theWriteLimitLevel_isActive_belowTheLimit() throws Exception {
        long d = dataBytes();
        StorageLimits limits = new StorageLimits(StorageLimits.defaults().warnDbBytes(), StorageLimits.MIN_FLOOR,
                StorageLimits.MIN_FLOOR);
        assertInstanceOf(StoragePreferences.Saved.class, prefs.save(limits));

        StorageReport.Reading r = read(reportOn(FakeSpaceProbe.all(StorageLimits.MIN_FLOOR - 1)));

        assertTrue(r.evaluation().is(StoragePolicy.Level.WRITE_LIMIT));
        assertEquals(1, r.evaluation().shortfall(StoragePolicy.Level.WRITE_LIMIT));
        assertEquals(StoragePolicy.evaluate(d, r.snapshot().space(StorageInventory.Role.DATA), limits), r.evaluation());
    }

    @Test
    void whenTheDataVolumeMeasureFails_theReadingDoesNotThrow_andSaysWhy() throws Exception {
        long d = dataBytes();
        SpaceProbe.Failed failed = new SpaceProbe.Failed("falha do teste");
        FakeSpaceProbe probe = FakeSpaceProbe.all(GIB).on(db.getParent(), failed).on(folder, failed);

        StorageReport.Reading r = read(reportOn(probe));

        assertEquals(failed, r.snapshot().space(StorageInventory.Role.DATA));
        assertFalse(r.evaluation().undetermined().isEmpty());
        assertTrue(r.evaluation().undetermined().contains(StoragePolicy.Level.WRITE_LIMIT));
        assertEquals(-1, r.evaluation().freeBytes());
        assertEquals(StoragePolicy.evaluate(d, failed, r.limits()), r.evaluation());
        StorageGuard.NeedItem copies = r.copyNeed().items().stream()
                .filter(i -> i.places().contains(SpaceEstimate.Place.COPIES)).findFirst().orElseThrow();
        assertFalse(copies.measured());
        assertEquals("falha do teste", copies.unmeasuredReason());
    }

    // ---- case 2: corrected limits ---------------------------------------------------------------------------

    @Test
    void correctedLimits_appearInTheReading() {
        node.put(StoragePreferences.KEY_WRITE, "lixo");
        StoragePreferences.Loaded loaded = prefs.load();
        assertFalse(loaded.corrected().isEmpty(), "fixture: a saved value that is not a number is corrected");

        StorageReport.Reading r = read(reportOn(FakeSpaceProbe.all(GIB)));

        assertEquals(loaded.corrected(), r.corrected());
        assertEquals("limite de gravação: valor salvo inválido; usando o padrão", r.corrected().get(0));
        assertEquals(loaded.limits(), r.limits());
        assertEquals(StorageLimits.defaults().writeLimitBytes(), r.limits().writeLimitBytes());
    }

    // ---- case 3: the automatic copy postponed ---------------------------------------------------------------

    @Test
    void aPostponedAutomaticCopy_isReported_withTheMessage_andNoDate() {
        StorageReport report = reportOn(FakeSpaceProbe.all(GIB));
        assertFalse(read(report).automaticPostponed(), "nothing automatic ran yet");

        backups.spaceCheck(guardOn(FakeSpaceProbe.none())::operation);
        BackupService.Result postponed = backups.dailyIfNeeded();
        assertEquals(BackupService.Outcome.POSTPONED, postponed.outcome(), postponed.message());

        StorageReport.Reading r = read(report);
        assertTrue(r.automaticPostponed());
        assertEquals(postponed.message(), r.postponedMessage());
        assertTrue(r.postponedMessage().contains("adiada"), r.postponedMessage());
        assertTrue(Arrays.stream(StorageReport.Reading.class.getRecordComponents())
                        .noneMatch(c -> c.getType() == Instant.class),
                "the postponed result carries no date (createdAt is null), and the reading adds none");

        backups.spaceCheck(guardOn(FakeSpaceProbe.all(GIB))::operation);
        assertEquals(BackupService.Outcome.CREATED, backups.dailyIfNeeded().outcome());
        StorageReport.Reading after = read(report);
        assertFalse(after.automaticPostponed(), "the last automatic result is no longer a postponement");
        assertNull(after.postponedMessage());
    }

    // ---- case 4: the need of the copy is the one the BackupService uses -------------------------------------

    @Test
    void theCopyNeed_isTheOneTheBackupServiceAsksTheGuard_onTheSameSituation() throws Exception {
        AtomicReference<SpaceEstimate.Estimate> estimate = new AtomicReference<>();
        AtomicReference<Map<SpaceEstimate.Place, Path>> places = new AtomicReference<>();
        backups.spaceCheck((e, p) -> {
            estimate.set(e);
            places.set(p);
            return new StorageGuard.Allowed(-1);
        });
        assertEquals(BackupService.Outcome.CREATED, backups.createManual().outcome());
        assertNotNull(estimate.get(), "the BackupService asked for the space of its copy");
        SpaceProbe probe = FakeSpaceProbe.all(GIB);

        StorageReport.Reading r = read(reportOn(probe));

        assertEquals(guardOn(probe).need(estimate.get(), places.get()), r.copyNeed());
        assertEquals(SpaceEstimate.verifiedCopy(r.snapshot().databaseBytes(), SpaceEstimate.Place.COPIES), estimate.get());
    }

    @Test
    void theCopyNeed_isTheHandCalculatedPeak_onOneVolume() throws Exception {
        long d = dataBytes();

        StorageReport.Reading r = read(reportOn(FakeSpaceProbe.all(GIB)));

        assertTrue(r.copyNeed().estimated());
        assertEquals(1, r.copyNeed().items().size(), r.copyNeed().items().toString());
        StorageGuard.NeedItem item = r.copyNeed().items().get(0);
        assertEquals(SpaceEstimate.copyBound(d) + SpaceEstimate.sortBound(d), item.neededBytes());
        assertEquals(GIB, item.freeBytes());
        assertEquals(java.util.Set.of(SpaceEstimate.Place.COPIES, SpaceEstimate.Place.TEMP), item.places());
    }

    @Test
    void theCopyNeed_isOneItemPerVolume_whenCopiesAndTempAreOnDifferentVolumes() throws Exception {
        long d = dataBytes();
        FakeSpaceProbe probe = FakeSpaceProbe.all(GIB)
                .on(folder, new SpaceProbe.Measured(11 * MIB, folder, "CÓPIAS"))
                .on(sqliteTemp, new SpaceProbe.Measured(22 * MIB, sqliteTemp, "TEMP"));

        StorageReport.Reading r = read(reportOn(probe));

        assertEquals(2, r.copyNeed().items().size(), r.copyNeed().items().toString());
        StorageGuard.NeedItem copies = r.copyNeed().items().stream()
                .filter(i -> i.places().contains(SpaceEstimate.Place.COPIES)).findFirst().orElseThrow();
        StorageGuard.NeedItem temp = r.copyNeed().items().stream()
                .filter(i -> i.places().contains(SpaceEstimate.Place.TEMP)).findFirst().orElseThrow();
        assertEquals(SpaceEstimate.copyBound(d), copies.neededBytes());
        assertEquals(11 * MIB, copies.freeBytes());
        assertEquals(SpaceEstimate.sortBound(d), temp.neededBytes());
        assertEquals(22 * MIB, temp.freeBytes());
    }

    @Test
    void anUnreadableDatabaseSize_givesACopyNeedWithoutEstimate_andDoesNotThrow() {
        StorageReport missing = new StorageReport(tempDir.resolve("nao existe").resolve("breakinv.db"),
                tempDir.resolve("nao existe").resolve("backups"), sqliteTemp, FakeSpaceProbe.all(GIB), prefs, backups);

        StorageReport.Reading r = read(missing);

        assertEquals(-1, r.snapshot().databaseBytes());
        assertFalse(r.copyNeed().estimated());
        assertNotNull(r.copyNeed().unknownReason());
        assertEquals(java.util.List.of(), r.copyNeed().items());
    }

    // ---- case 9: the need of a restore ----------------------------------------------------------------------

    private RestoreService.Preview preview(Path staged, int schemaVersion) {
        DatabaseFingerprint fp = new DatabaseFingerprint(schemaVersion, new TreeMap<>(), "abc");
        BackupVerifier.Report report = new BackupVerifier.Report(true, true, true, "ok", fp, 0, null);
        return new RestoreService.Preview(folder.resolve("breakinv-manual-20261022-061500-a1b2c3.db"), staged, report,
                Instant.parse("2026-10-22T06:15:00Z"), true, 0);
    }

    private Path staged(int bytes) throws Exception {
        return Files.write(tempDir.resolve("staged.partial"), new byte[bytes]);
    }

    @Test
    void restoreNeed_followsTheTwoEstimatesOfTheRestore() throws Exception {
        long d = dataBytes();
        int c = 300_000;
        Path staged = staged(c);
        SpaceProbe probe = FakeSpaceProbe.all(GIB);
        Map<SpaceEstimate.Place, Path> where = Map.of(SpaceEstimate.Place.DATA, db.getParent(),
                SpaceEstimate.Place.COPIES, folder);
        StorageReport report = reportOn(probe);

        for (int version : new int[]{Schema.CURRENT_VERSION, 1}) {
            RestoreService.Preview preview = preview(staged, version);
            boolean older = version < Schema.CURRENT_VERSION;
            assertEquals(older, preview.olderVersion(), "fixture");

            StorageGuard.Need normal = assertDoesNotThrow(() -> report.restoreNeed(preview, false),
                    "the need of a restore is only a reading");
            StorageGuard.Need damaged = assertDoesNotThrow(() -> report.restoreNeed(preview, true));

            SpaceEstimate.Estimate expectedNormal = SpaceEstimate.restore(c, d, true, false, older);
            SpaceEstimate.Estimate expectedDamaged = SpaceEstimate.restoreOverDamaged(c, d, true);
            assertEquals(guardOn(probe).need(expectedNormal, where), normal, "overDamaged false, older " + older);
            assertEquals(guardOn(probe).need(expectedDamaged, where), damaged, "overDamaged true, older " + older);
            assertEquals("restauração", normal.operation());
            assertEquals("restauração sobre dados danificados", damaged.operation());
            assertTrue(normal.estimated());
            assertTrue(damaged.estimated());
            assertNotEquals(normal, damaged);
        }
        assertNotEquals(SpaceEstimate.restore(c, d, true, false, true), SpaceEstimate.restore(c, d, true, false, false),
                "fixture: the older version changes the estimate");
    }

    @Test
    void restoreNeed_withoutTheStagedFile_orWithoutTheData_isNoEstimate() throws Exception {
        long d = dataBytes();
        Path gone = tempDir.resolve("nao-preparado.partial");
        RestoreService.Preview noStaged = preview(gone, Schema.CURRENT_VERSION);
        StorageReport report = reportOn(FakeSpaceProbe.all(GIB));

        StorageGuard.Need absentStaged = assertDoesNotThrow(() -> report.restoreNeed(noStaged, false));
        assertFalse(absentStaged.estimated());
        assertNotNull(absentStaged.unknownReason());
        assertEquals(java.util.List.of(), absentStaged.items());
        assertFalse(assertDoesNotThrow(() -> report.restoreNeed(noStaged, true)).estimated());

        RestoreService.Preview withStaged = preview(staged(1000), Schema.CURRENT_VERSION);
        StorageReport noData = new StorageReport(tempDir.resolve("vazio").resolve("breakinv.db"), folder, sqliteTemp,
                FakeSpaceProbe.all(GIB), prefs, backups);
        StorageGuard.Need absentData = assertDoesNotThrow(() -> noData.restoreNeed(withStaged, false));
        assertFalse(absentData.estimated());
        assertNotNull(absentData.unknownReason());
        assertEquals(java.util.List.of(), absentData.items());
        assertFalse(assertDoesNotThrow(() -> noData.restoreNeed(withStaged, true)).estimated());
        assertTrue(d > 0);
    }
}

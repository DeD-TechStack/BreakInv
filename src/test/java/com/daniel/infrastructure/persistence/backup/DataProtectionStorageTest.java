package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u5 F1, RF-03 and RF-04 (cases 6, 7 and 8): what the screen may ask of {@link DataProtection} about the
 * data space, with the real services, a fake probe and throwaway preferences nodes.
 */
class DataProtectionStorageTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    Path other;
    Path sqliteTemp;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-23T06:00:00Z"));
    BackupService backups;
    final List<Preferences> nodes = new ArrayList<>();
    Preferences node;
    StoragePreferences prefs;

    @BeforeEach
    void setUp() throws Exception {
        db = Files.createDirectories(tempDir.resolve("dados")).resolve("breakinv.db");
        other = Files.createDirectories(tempDir.resolve("outra pasta"));
        sqliteTemp = Files.createDirectories(tempDir.resolve("tmp"));
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        seed("a");
        backups = service(db, folder, clock);
        node = newNode();
        prefs = new StoragePreferences(node);
    }

    @AfterEach
    void tearDown() throws Exception {
        Database.setPreMigrationHook(null);
        Database.close();
        for (Preferences n : nodes) {
            try {
                n.removeNode();
            } catch (IllegalStateException alreadyRemoved) {
                // the test removed it
            }
        }
    }

    Preferences newNode() {
        Preferences n = Preferences.userRoot().node("/com/daniel/breakinv-test/" + UUID.randomUUID());
        nodes.add(n);
        return n;
    }

    StorageReport reportOn(SpaceProbe probe, StoragePreferences preferences) {
        return new StorageReport(db, folder, sqliteTemp, probe, preferences, backups);
    }

    DataProtection protection(SpaceProbe probe, StoragePreferences preferences) {
        return DataProtection.of(backups, new RestoreService(backups, InstanceGuard.UNVERIFIABLE),
                reportOn(probe, preferences), preferences);
    }

    StorageGuard guardOn(SpaceProbe probe) {
        return new StorageGuard(db, sqliteTemp, probe, StorageLimits::defaults, System::nanoTime);
    }

    // ---- RF-03: the new overload implements storage(); the old one keeps its shape ---------------------------

    @Test
    void storage_throughTheNewOf_isTheReadingOfTheReport() {
        SpaceProbe probe = FakeSpaceProbe.all(GIB);
        DataProtection protection = protection(probe, prefs);

        StorageReport.Reading reading = assertDoesNotThrow(protection::storage, "storage() reads through the report");

        assertNotNull(reading);
        assertEquals(reportOn(probe, prefs).read(), reading);
        assertEquals(StorageLimits.defaults(), reading.limits());
    }

    @Test
    void theOldOf_keepsTheSevenMethods_andTheNewOnesThrowUnsupported() {
        DataProtection old = DataProtection.of(backups, new RestoreService(backups, InstanceGuard.UNVERIFIABLE));

        assertNotNull(old.status());
        assertThrows(UnsupportedOperationException.class, old::storage);
        assertThrows(UnsupportedOperationException.class, () -> old.saveLimits(StorageLimits.defaults()));
        assertThrows(UnsupportedOperationException.class, () -> old.createManual(true));
        assertThrows(UnsupportedOperationException.class, () -> old.createIn(other, true));
        assertThrows(UnsupportedOperationException.class, () -> old.restoreNeed(null, false));
    }

    // ---- case 6: saveLimits, the text of today ---------------------------------------------------------------

    @Test
    void regression_saved_rejected_notSaved_keepTheirTextAndShape() {
        StorageLimits wanted = new StorageLimits(200 * MIB, 128 * MIB, 96 * MIB);
        assertEquals(new StoragePreferences.Saved(wanted), prefs.save(wanted));
        assertEquals(wanted, prefs.load().limits());

        StorageLimits invalid = new StorageLimits(0, 0, 0);
        StoragePreferences.Rejected rejected = assertInstanceOf(StoragePreferences.Rejected.class, prefs.save(invalid));
        assertEquals(invalid.problem(), rejected.reason());
        assertTrue(rejected.reason().startsWith("O aviso do tamanho do banco precisa ficar entre "), rejected.reason());
        assertEquals(wanted, prefs.load().limits(), "nothing was written");

        Preferences gone = newNode();
        StoragePreferences removed = new StoragePreferences(gone);
        assertDoesNotThrow(() -> gone.removeNode());
        StoragePreferences.NotSaved notSaved = assertInstanceOf(StoragePreferences.NotSaved.class, removed.save(wanted));
        assertEquals("Não foi possível salvar os limites nas preferências deste computador (IllegalStateException). "
                + "Os limites anteriores continuam valendo.", notSaved.reason());
        assertEquals(StorageLimits.defaults(), notSaved.stillInEffect());
    }

    @Test
    void saveLimits_returnsTheResultOfTheStoreUnchanged_forSaved_rejected_andNotSaved() {
        DataProtection protection = protection(FakeSpaceProbe.all(GIB), prefs);
        StorageLimits wanted = new StorageLimits(200 * MIB, 128 * MIB, 96 * MIB);

        StoragePreferences.SaveResult saved = assertDoesNotThrow(() -> protection.saveLimits(wanted),
                "saveLimits goes to the preferences");
        assertEquals(new StoragePreferences.Saved(wanted), saved);
        assertEquals(wanted, prefs.load().limits());

        StorageLimits invalid = new StorageLimits(0, 0, 0);
        StoragePreferences.SaveResult rejected = assertDoesNotThrow(() -> protection.saveLimits(invalid));
        assertEquals(new StoragePreferences.Rejected(invalid.problem()), rejected);

        Preferences gone = newNode();
        StoragePreferences removed = new StoragePreferences(gone);
        assertDoesNotThrow(() -> gone.removeNode());
        DataProtection onRemoved = protection(FakeSpaceProbe.all(GIB), removed);
        StoragePreferences.SaveResult notSaved = assertDoesNotThrow(() -> onRemoved.saveLimits(wanted));
        assertEquals(removed.save(wanted), notSaved, "the same NotSaved, with its text, as the store gives");
        assertInstanceOf(StoragePreferences.NotSaved.class, notSaved);
    }

    // ---- case 7: creating a copy confirming that it may run without a measure --------------------------------

    @Test
    void regression_createManual_withoutAMeasure_isUnmeasured_untilConfirmed() throws Exception {
        backups.spaceCheck(guardOn(FakeSpaceProbe.none())::operation);

        BackupService.Result asked = backups.createManual(false);

        assertEquals(BackupService.Outcome.UNMEASURED, asked.outcome(), asked.message());
        assertEquals(BackupService.Outcome.UNMEASURED, backups.createManual().outcome());
        assertEquals(List.of(), copies(folder));
    }

    @Test
    void createManual_confirmed_createsTheCopy_andNotConfirmed_staysUnmeasured() throws Exception {
        backups.spaceCheck(guardOn(FakeSpaceProbe.none())::operation);
        DataProtection protection = protection(FakeSpaceProbe.none(), prefs);

        BackupService.Result asked = assertDoesNotThrow(() -> protection.createManual(false), "createManual(false) delegates");
        assertEquals(BackupService.Outcome.UNMEASURED, asked.outcome(), asked.message());
        assertEquals(BackupService.Outcome.UNMEASURED, protection.createManual().outcome(),
                "createManual() stays the same as false");
        assertEquals(List.of(), copies(folder));

        BackupService.Result confirmed = assertDoesNotThrow(() -> protection.createManual(true), "createManual(true) delegates");
        assertEquals(BackupService.Outcome.CREATED, confirmed.outcome(), confirmed.message());
        assertEquals(1, copies(folder).size());
    }

    @Test
    void createIn_confirmed_createsTheCopyInTheChosenFolder() throws Exception {
        backups.spaceCheck(guardOn(FakeSpaceProbe.none())::operation);
        assertEquals(BackupService.Outcome.UNMEASURED, backups.createIn(other, false).outcome());
        DataProtection protection = protection(FakeSpaceProbe.none(), prefs);

        assertEquals(BackupService.Outcome.UNMEASURED,
                assertDoesNotThrow(() -> protection.createIn(other, false)).outcome());
        assertEquals(BackupService.Outcome.UNMEASURED, protection.createIn(other).outcome());
        BackupService.Result confirmed = assertDoesNotThrow(() -> protection.createIn(other, true));

        assertEquals(BackupService.Outcome.CREATED, confirmed.outcome(), confirmed.message());
        assertEquals(1, copies(other).size());
    }

    // ---- case 8: a saved limit counts at the next decision, with no new guard --------------------------------

    @Test
    void aSavedLimit_countsAtTheNextDecisionOfTheSameGuard() {
        SpaceProbe probe = FakeSpaceProbe.all(100 * MIB);
        StorageGuard guard = new StorageGuard(db, sqliteTemp, probe, () -> prefs.load().limits(), System::nanoTime);
        DataProtection protection = protection(probe, prefs);
        assertEquals(64 * MIB, prefs.load().limits().writeLimitBytes(), "fixture: the default write limit is 64 MiB");

        StorageGuard.Allowed allowed = assertInstanceOf(StorageGuard.Allowed.class, guard.userWrite());
        assertEquals(100 * MIB, allowed.freeBytes());

        StorageLimits defaults = StorageLimits.defaults();
        StorageLimits stricter = new StorageLimits(defaults.warnDbBytes(), defaults.reserveFloorBytes(), 128 * MIB);
        StoragePreferences.SaveResult saved = assertDoesNotThrow(() -> protection.saveLimits(stricter));
        assertEquals(new StoragePreferences.Saved(stricter), saved);

        StorageGuard.Refused refused = assertInstanceOf(StorageGuard.Refused.class, guard.userWrite(),
                "the limit saved through the DataProtection is the one the guard reads next");
        assertEquals(28 * MIB, refused.shortfallBytes());
        assertEquals(stricter, assertDoesNotThrow(protection::storage).limits());
    }
}

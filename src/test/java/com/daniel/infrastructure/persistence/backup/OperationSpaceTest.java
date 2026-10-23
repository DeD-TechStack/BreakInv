package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** BRK-31 u3b: the operations against their estimate (DESENHO-BRK31 §5.2 and the §5.3 table). */
class OperationSpaceTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;
    /** Below any estimate: the fixed allowance alone is 64 KiB. */
    private static final long TINY = 16 * 1024;

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    Path other;
    Path sqliteTemp;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-23T06:00:00Z"));
    BackupService backups;
    com.daniel.core.service.DailyTrackingUseCase uc;

    @BeforeEach
    void setUp() throws Exception {
        db = Files.createDirectories(tempDir.resolve("dados")).resolve("breakinv.db");
        other = Files.createDirectories(tempDir.resolve("outra pasta"));
        sqliteTemp = Files.createDirectories(tempDir.resolve("tmp"));
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        uc = seed("a");
        backups = service(db, folder, clock);
    }

    @AfterEach
    void tearDown() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    StorageGuard guardOn(SpaceProbe probe, Path databaseFile) {
        return new StorageGuard(databaseFile, sqliteTemp, probe, StorageLimits::defaults, System::nanoTime);
    }

    void room(BackupService svc, SpaceProbe probe) {
        svc.spaceCheck(guardOn(probe, svc.databaseFile())::operation);
    }

    @Test
    void sharedVolume_sumsCopyAndTemp_separateVolumes_eachNeedOnlyTheirOwn() {
        long d = 10 * MIB;
        SpaceEstimate.Estimate need = SpaceEstimate.verifiedCopy(d, SpaceEstimate.Place.COPIES);
        long both = SpaceEstimate.copyBound(d) + SpaceEstimate.sortBound(d);

        StorageGuard.Refused r = assertInstanceOf(StorageGuard.Refused.class,
                guardOn(FakeSpaceProbe.all(both - 1), db).operation(need, Map.of(SpaceEstimate.Place.COPIES, folder)));
        assertEquals(1, r.shortfallBytes());
        assertInstanceOf(StorageGuard.Allowed.class,
                guardOn(FakeSpaceProbe.all(both), db).operation(need, Map.of(SpaceEstimate.Place.COPIES, folder)));

        FakeSpaceProbe apart = FakeSpaceProbe.all(0)
                .on(folder, new SpaceProbe.Measured(SpaceEstimate.copyBound(d), folder, "CÓPIAS"))
                .on(sqliteTemp, new SpaceProbe.Measured(SpaceEstimate.sortBound(d), sqliteTemp, "TEMP"));
        assertInstanceOf(StorageGuard.Allowed.class,
                guardOn(apart, db).operation(need, Map.of(SpaceEstimate.Place.COPIES, folder)));
        assertInstanceOf(StorageGuard.Unmeasured.class,
                guardOn(FakeSpaceProbe.none(), db).operation(need, Map.of(SpaceEstimate.Place.COPIES, folder)));
    }

    @Test
    void manualCopy_belowTheEstimate_isRefused_andNothingIsCopied() throws Exception {
        room(backups, FakeSpaceProbe.all(TINY));

        BackupService.Result r = backups.createManual();

        assertEquals(BackupService.Outcome.NO_SPACE, r.outcome(), r.message());
        assertTrue(r.message().contains("Libere pelo menos") && r.message().contains("Nenhuma cópia foi criada"),
                r.message());
        assertEquals(List.of(), copies(folder));
    }

    @Test
    void manualCopy_withoutAMeasure_waitsForAnExplicitConfirmation() throws Exception {
        room(backups, FakeSpaceProbe.none());

        BackupService.Result asked = backups.createManual();
        assertEquals(BackupService.Outcome.UNMEASURED, asked.outcome(), asked.message());
        assertEquals(List.of(), copies(folder));

        BackupService.Result confirmed = backups.createManual(true);
        assertEquals(BackupService.Outcome.CREATED, confirmed.outcome(), confirmed.message());
    }

    @Test
    void dailyCopy_isPostponed_belowTheEstimate_orWithoutAMeasure() throws Exception {
        room(backups, FakeSpaceProbe.all(TINY));
        assertEquals(BackupService.Outcome.POSTPONED, backups.dailyIfNeeded().outcome());
        room(backups, FakeSpaceProbe.none());
        BackupService.Result r = backups.dailyIfNeeded();
        assertEquals(BackupService.Outcome.POSTPONED, r.outcome());
        assertTrue(r.message().contains("nada automático roda sem medida"), r.message());
        assertEquals(List.of(), copies(folder));

        room(backups, FakeSpaceProbe.all(GIB));
        assertEquals(BackupService.Outcome.CREATED, backups.dailyIfNeeded().outcome());
    }

    @Test
    void copyToAnotherFolder_checksTheVolumeOfThatFolder() throws Exception {
        room(backups, FakeSpaceProbe.all(GIB).on(other, new SpaceProbe.Measured(TINY, other, "OUTRO")));

        BackupService.Result r = backups.createIn(other);

        assertEquals(BackupService.Outcome.NO_SPACE, r.outcome(), r.message());
        assertTrue(r.message().contains(other.toString()), r.message());
        try (var files = Files.list(other)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void preMigrationCopy_belowTheEstimate_blocksTheMigration_andLeavesTheFileUntouched() throws Exception {
        Database.close();
        Path legacy = tempDir.resolve("legado").resolve("breakinv.db");
        Files.createDirectories(legacy.getParent());
        PreMigrationBackupTest.legacy(legacy, true);
        DatabaseFingerprint before;
        try (Connection c = DriverManager.getConnection(url(legacy))) {
            before = DatabaseFingerprint.of(c);
        }
        BackupService svc = service(legacy, BackupService.defaultFolder(legacy), clock);
        room(svc, FakeSpaceProbe.all(TINY));
        Database.setPreMigrationHook((candidate, file) -> svc.beforeMigration(candidate));
        Database.configure(url(legacy));

        RuntimeException e = assertThrows(RuntimeException.class, Database::open);

        assertTrue(e.getMessage().contains("bloqueada antes de começar"), e.getMessage());
        try (Connection c = DriverManager.getConnection(url(legacy))) {
            assertEquals(before, DatabaseFingerprint.of(c), "a migração não rodou");
        }
        room(svc, FakeSpaceProbe.none());
        Database.open();
        assertTrue(copies(BackupService.defaultFolder(legacy)).get(0).startsWith("breakinv-pre-migracao-"),
                "sem medida a cópia é tentada e conferida, e então a migração segue");
    }

    @Test
    void restore_belowTheEstimate_orWithoutAMeasure_isRefusedBeforeStaging() throws Exception {
        BackupService.Result copy = backups.createManual();
        assertEquals(BackupService.Outcome.CREATED, copy.outcome(), copy.message());
        RestoreService svc = new RestoreService(backups, InstanceGuard.SOLE);

        room(backups, FakeSpaceProbe.all(TINY));
        String below = svc.prepare(copy.file()).refusal();
        room(backups, FakeSpaceProbe.none());
        String unmeasured = svc.prepare(copy.file()).refusal();

        assertTrue(below.contains("Libere pelo menos") && below.contains("Nada foi alterado"), below);
        assertTrue(unmeasured.contains("só começa com o espaço livre medido"), unmeasured);
        assertTrue(names(folder).stream().noneMatch(BackupNames::isTemp), "nada preparado");
    }

    @Test
    void restore_whenTheSpaceIsGoneAfterThePreview_isRefusedBeforeTouchingTheData() throws Exception {
        BackupService.Result copy = backups.createManual();
        clock.advance(java.time.Duration.ofMinutes(5));
        change(uc, "b-1");
        DatabaseFingerprint current = liveFingerprint();
        RestoreService svc = new RestoreService(backups, InstanceGuard.SOLE);
        room(backups, FakeSpaceProbe.all(GIB));
        RestoreService.Preview preview = svc.prepare(copy.file()).preview();

        room(backups, FakeSpaceProbe.all(TINY));
        RestoreService.Result r = svc.restore(preview);

        assertEquals(RestoreService.Outcome.REFUSED, r.outcome(), r.message());
        assertTrue(r.message().contains("Nada foi alterado"), r.message());
        assertEquals(current, liveFingerprint());
        assertFalse(Files.exists(preview.staged()), "o preparado sai, para não ocupar o espaço que falta");
        assertEquals(1, copies(folder).size(), "nenhuma cópia pré-restauração");
    }
}

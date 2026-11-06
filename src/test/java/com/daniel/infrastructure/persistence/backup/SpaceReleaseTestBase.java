package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-32 S2: a temp-folder database with the ledger of the BRK-30 fixtures, free pages made through the product's own
 * path (saving and deleting plans), and a {@link Rig} that wires a {@link SpaceRelease} with spies on each
 * collaborator. Nothing here opens the personal database or the real preferences.
 */
abstract class SpaceReleaseTestBase {

    static final long MIB = 1L << 20;

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    Path sqliteTemp;
    MutableClock clock = new MutableClock(Instant.parse("2026-11-05T06:00:00Z"));
    BackupService backups;
    DailyTrackingUseCase uc;
    Rig rig;

    @BeforeEach
    void setUpBase() throws Exception {
        Database.setWriteGate(null);
        db = Files.createDirectories(tempDir.resolve("dados")).resolve("breakinv.db");
        sqliteTemp = Files.createDirectories(tempDir.resolve("tmp"));
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        uc = seed("a");
        backups = service(db, folder, clock);
        rig = new Rig(backups, db, folder);
    }

    @AfterEach
    void tearDownBase() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    // ---- free pages through the product's path ----------------------------------------------------------------

    /**
     * Saves {@code total} plans with long names and deletes the first {@code delete} of them, each through
     * {@link SavedAnalysisRepository}: the pages of the deleted plans (rows and unique-name index) go to the freelist.
     */
    static void fragment(int total, int delete, int nameChars) {
        SavedAnalysisRepository repo = SavedAnalysisRepository.forApplicationDatabase();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            ids.add(repo.savePlan(plan(String.format("plano-%04d-", i) + "x".repeat(nameChars))).id());
        }
        for (int i = 0; i < delete; i++) repo.deletePlan(ids.get(i));
    }

    /** About 5 MiB of data, of which about 3.8 MiB are reusable pages. */
    static void fragmentedFixture() {
        fragment(20, 14, 128 * 1024);
    }

    static AllocationPlan plan(String name) {
        Map<CategoryEnum, Integer> weights = new EnumMap<>(CategoryEnum.class);
        for (CategoryEnum c : CategoryEnum.values()) weights.put(c, 0);
        weights.put(CategoryEnum.RENDA_FIXA, 6000);
        weights.put(CategoryEnum.ACOES, 4000);
        return AllocationPlan.create(name, weights);
    }

    // ---- direct readings, independent of the code under test --------------------------------------------------

    /** page_size, page_count and freelist_count straight from the file, on a connection of the test. */
    static long[] pragmas(Path file) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(url(file)); Statement st = c.createStatement()) {
            return new long[]{pragma(st, "page_size"), pragma(st, "page_count"), pragma(st, "freelist_count")};
        }
    }

    static long pragma(Statement st, String name) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    long counter() throws Exception {
        return BackupService.changeCounter(db);
    }

    /** The PRE_MAINTENANCE copies in the managed folder (names only). */
    List<String> preMaintenanceCopies() throws Exception {
        return copies(folder).stream()
                .filter(n -> BackupNames.kindOfCopy(n).orElse(null) == BackupKind.PRE_MAINTENANCE).toList();
    }

    /** Every exit gives the instance guard and the busy lock back. */
    void assertReleased(String where) {
        assertEquals(rig.guard.acquired, rig.guard.released, "guarda de instância devolvida: " + where);
        assertFalse(rig.guard.held(), "guarda de instância não pode ficar presa: " + where);
        assertFalse(backups.busyLock().isLocked(), "trava busy devolvida: " + where);
    }

    // ---- the rig ----------------------------------------------------------------------------------------------

    /** Instance guard that counts, and that can refuse. */
    static final class CountingGuard implements InstanceGuard {
        boolean allow = true;
        int acquired;
        int released;

        @Override
        public boolean tryAcquireExclusive() {
            if (!allow) return false;
            acquired++;
            return true;
        }

        @Override
        public void releaseExclusive() {
            released++;
        }

        @Override
        public String refusal() {
            return "outra janela de teste está aberta";
        }

        boolean held() {
            return acquired > released;
        }
    }

    /** A {@link SpaceRelease} on production collaborators, each replaceable, with a spy around each one. */
    static final class Rig {
        final BackupService backups;
        final Path folder;
        final CountingGuard guard = new CountingGuard();
        final AtomicInteger compactions = new AtomicInteger();
        final AtomicInteger verifications = new AtomicInteger();
        final AtomicInteger wayBacks = new AtomicInteger();
        final List<Path> wayBackFrom = new CopyOnWriteArrayList<>();
        final List<Integer> copiesAtCompaction = new CopyOnWriteArrayList<>();

        SpaceRelease.Pages pages;
        SpaceRelease.Compactor compactor = SpaceRelease::vacuum;
        SpaceRelease.Verifier verifier = BackupVerifier::verify;
        SpaceRelease.WayBack wayBack = SpaceRelease::restoreFromCopy;
        SpaceRelease.Exclusive exclusive = Database::runExclusive;

        Rig(BackupService backups, Path db, Path folder) {
            this.backups = backups;
            this.folder = folder;
            this.pages = new DatabasePages(db)::read;
        }

        SpaceRelease build() {
            return new SpaceRelease(backups, guard, () -> pages.read(),
                    conn -> {
                        copiesAtCompaction.add(preMaintenanceCopiesNow());
                        compactions.incrementAndGet();
                        compactor.compact(conn);
                    },
                    conn -> {
                        verifications.incrementAndGet();
                        return verifier.verify(conn);
                    },
                    (conn, copy) -> {
                        wayBacks.incrementAndGet();
                        wayBackFrom.add(copy);
                        wayBack.restore(conn, copy);
                    },
                    body -> exclusive.run(body));
        }

        SpaceRelease.Result release() {
            return build().release();
        }

        private int preMaintenanceCopiesNow() {
            try {
                return (int) copies(folder).stream()
                        .filter(n -> BackupNames.kindOfCopy(n).orElse(null) == BackupKind.PRE_MAINTENANCE).count();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A report that cannot match any copy: readable and intact, with another content digest. */
    static BackupVerifier.Report divergentReport() {
        return new BackupVerifier.Report(true, true, true, "ok",
                new DatabaseFingerprint(1, new java.util.TreeMap<>(), "00-conteudo-diferente"), 0, null);
    }
}

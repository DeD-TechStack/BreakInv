package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.repository.DatabaseLedgerAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-36, unit 2, with the BRK-30 restore contract: a portfolio read in progress makes a restore end
 * explicitly as REFUSED, without waiting forever and without changing any data; once the read is
 * over, the same restore succeeds and the next read sees the restored data.
 */
class RestoreDuringPortfolioReadTest {

    @TempDir
    Path tempDir;
    Path db;
    BackupService backups;
    DailyTrackingUseCase uc;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = tempDir.resolve("dados");
        Files.createDirectories(dir);
        db = dir.resolve("breakinv.db");
        Database.configure(url(db));
        Database.open();
        uc = seed("a");
        backups = service(db, BackupService.defaultFolder(db), new MutableClock(Instant.parse("2026-10-22T06:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    @Test
    void restoreDuringARead_isRefused_thenWorksAfterIt() throws Exception {
        BackupService.Result copy = backups.createManual();
        assertEquals(BackupService.Outcome.CREATED, copy.outcome(), copy.message());
        List<Movement> stateA = PortfolioService.onDatabase().history();
        change(uc, "b-1");
        List<Movement> stateB = PortfolioService.onDatabase().history();
        assertEquals(stateA.size() + 1, stateB.size());

        DatabaseLedgerAccess access = new DatabaseLedgerAccess();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> reader = CompletableFuture.supplyAsync(() -> access.read((repo, regs) -> {
            regs.listAll();
            int n = repo.listAll().size();
            inside.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return n;
        }));
        assertTrue(inside.await(10, TimeUnit.SECONDS));

        RestoreService svc = new RestoreService(backups, InstanceGuard.SOLE);
        long t0 = System.nanoTime();
        RestoreService.Result during = svc.restore(svc.prepare(copy.file()).preview());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();

        assertEquals(RestoreService.Outcome.REFUSED, during.outcome(), during.message());
        assertTrue(elapsedMs < 10_000, "ended explicitly, without hanging (" + elapsedMs + " ms)");
        assertEquals(stateB.size(), reader.get(10, TimeUnit.SECONDS), "the read saw state B");
        assertEquals(stateB, PortfolioService.onDatabase().history(), "nothing changed");

        RestoreService.Result after = svc.restore(svc.prepare(copy.file()).preview());
        assertEquals(RestoreService.Outcome.RESTORED, after.outcome(), after.message());
        int restored = access.read((repo, regs) -> repo.listAll().size());
        assertEquals(stateA.size(), restored, "the next read sees the restored data");
    }
}

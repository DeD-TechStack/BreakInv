package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * U4A:RF-06 and U4A:RN-01 (b): a real manual copy into the managed folder fails with a real
 * SQLITE_FULL (a page limit, not a physically full disk) thrown from the copy's test seam.
 */
class BackupFullDiskTest {

    static final String SECRET = "VALOR-SECRETO-4471";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Database.close();
    }

    @Test
    void manualCopy_realSqliteFull_failsAsFullDisk_leavesNoPartialFile_andNoRegistryEntry() throws Exception {
        Path dir = tempDir.resolve("dados");
        Files.createDirectories(dir);
        Path db = dir.resolve("breakinv.db");
        Path folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        DailyTrackingUseCase uc = useCase();
        uc.createInvestment(new DailyTrackingUseCase.NewInvestment("ATIVO CHEIO", "ACOES", "ALTA", DAY0, null, null,
                        "ACAO", null, null, "FICTC1", null, null, SECRET),
                DailyTrackingUseCase.OpeningSpec.zero("full-open", DAY0, PositionMode.QUANTITY));
        try (Connection c = Database.newConnection(); java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM investment_type WHERE note = '" + SECRET + "'")) {
            assertTrue(rs.next() && rs.getInt(1) == 1, "the investment with the marked note is in the source");
        }
        BackupService svc = BackupTestSupport.service(db, folder, new MutableClock(Instant.parse("2026-11-03T06:00:00Z")));
        Path limited = tempDir.resolve("limite.db");
        AtomicReference<SQLException> thrown = new AtomicReference<>();
        svc.hooks(new BackupService.Hooks() {
            @Override
            public void afterSourceSnapshot() throws SQLException {
                try (Connection other = DriverManager.getConnection(url(limited))) {
                    thrown.set(SqliteErrorsRealTest.fillUntilFull(other));
                }
                throw thrown.get();
            }
        });

        BackupService.Result r = svc.createManual();

        SQLException real = thrown.get();
        assertNotNull(real, "the seam ran");
        assertEquals(SQLiteErrorCode.SQLITE_FULL, assertInstanceOf(SQLiteException.class, real).getResultCode());
        assertEquals(BackupService.Outcome.FAILED, r.outcome(), r.message());
        assertTrue(r.message().contains("o disco está cheio"), r.message());
        assertFalse(r.message().contains(SECRET), r.message());
        assertTrue(names(folder).stream().noneMatch(n -> n.endsWith(".partial") || n.endsWith("-journal")),
                names(folder).toString());
        assertEquals(java.util.List.of(), copies(folder));
        assertTrue(BackupManifest.load(folder).entries().isEmpty(), "no entry of the copy in progress");
    }
}

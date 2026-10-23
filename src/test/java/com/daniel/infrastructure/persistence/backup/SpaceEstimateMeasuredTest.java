package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.backup.SpaceEstimate.Estimate;
import com.daniel.infrastructure.persistence.backup.SpaceEstimate.Place;
import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-31 u2b: the estimate against the files of real operations on synthetic databases (DESENHO-BRK31 §6 u2). A
 * sampler lists the database folder (files only), the copies folder and SQLite's temp folder in a tight loop; the
 * observed peak is a floor of the real one, so the estimate must never be below it. Volumes are simulated by place:
 * each place apart (separate volumes) and all places summed per sample (one shared volume). SQLite's temp folder is
 * pointed at the test folder so its files can be seen; the amount written does not depend on where it goes.
 * Prints "MEDIDA" lines for the evidence.
 */
class SpaceEstimateMeasuredTest {

    @TempDir
    Path tempDir;
    Path data;
    Path db;
    Path folder;
    Path sqliteTemp;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-23T06:00:00Z"));
    BackupService backups;
    com.daniel.core.service.DailyTrackingUseCase uc;

    @BeforeEach
    void setUp() throws Exception {
        data = Files.createDirectories(tempDir.resolve("dados"));
        sqliteTemp = Files.createDirectories(tempDir.resolve("sqlite-temp"));
        db = data.resolve("breakinv.db");
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        uc = seed("a");
        backups = service(db, folder, clock);
        sqliteTempDirectory(sqliteTemp);
    }

    @AfterEach
    void tearDown() throws Exception {
        sqliteTempDirectory(null);
        Database.setPreMigrationHook(null);
        Database.close();
    }

    /** Process-wide SQLite setting; null restores the default (the system temp folder). */
    static void sqliteTempDirectory(Path dir) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement st = c.createStatement()) {
            st.execute("PRAGMA temp_store_directory = '" + (dir == null ? "" : dir.toString().replace("'", "''")) + "'");
        }
    }

    /** Size multiplier; the suite runs at 1 (about 15 MB), the evidence also at 6 (-Dbrk31.escala=6, about 80 MB). */
    static final int SCALE = Integer.getInteger("brk31.escala", 1);

    /** Adds rows the app ignores: big rows (one per page) and many small rows, so sorts and journals are real. */
    static void inflate(int bigRows, int smallRows) throws SQLException {
        bigRows *= SCALE;
        smallRows *= SCALE;
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS medicao_lastro (id INTEGER PRIMARY KEY, b BLOB)");
            st.execute("CREATE TABLE IF NOT EXISTS medicao_linhas (id INTEGER PRIMARY KEY, t TEXT, v REAL, n INTEGER)");
            st.execute("BEGIN");
            st.execute("WITH RECURSIVE k(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM k WHERE i < " + bigRows
                    + ") INSERT INTO medicao_lastro(b) SELECT randomblob(3000) FROM k");
            st.execute("WITH RECURSIVE k(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM k WHERE i < " + smallRows
                    + ") INSERT INTO medicao_linhas(t, v, n) SELECT hex(randomblob(12)), i * 1.5, i FROM k");
            st.execute("COMMIT");
        }
    }

    static void shrink() throws SQLException {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM medicao_lastro");
            st.execute("DELETE FROM medicao_linhas");
            st.execute("VACUUM");
        }
    }

    record Observed(long data, long copies, long temp, long shared, long samples) {
    }

    /** Samples the three folders until closed; deltas against the sizes at construction. */
    static final class Sampler {
        private final Path[] dirs;
        private final long[] base = new long[3];
        private final long[] max = new long[3];
        private long shared;
        private long samples;
        private volatile boolean stop;
        private final Thread thread;

        Sampler(Path data, Path copies, Path temp) {
            dirs = new Path[]{data, copies, temp};
            for (int i = 0; i < 3; i++) base[i] = filesIn(dirs[i]);
            thread = new Thread(() -> {
                while (!stop) sample();
                sample();
            }, "amostrador-brk31");
            thread.setDaemon(true);
            thread.start();
        }

        private void sample() {
            long sum = 0;
            for (int i = 0; i < 3; i++) {
                long delta = filesIn(dirs[i]) - base[i];
                max[i] = Math.max(max[i], delta);
                sum += delta;
            }
            shared = Math.max(shared, sum);
            samples++;
        }

        Observed stop() throws InterruptedException {
            stop = true;
            thread.join();
            return new Observed(max[0], max[1], max[2], shared, samples);
        }

        static long filesIn(Path dir) {
            long total = 0;
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    try {
                        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total += Files.size(p);
                    } catch (IOException gone) {
                        // deleted between listing and size
                    }
                }
            } catch (IOException e) {
                return total;
            }
            return total;
        }
    }

    Sampler sampler() {
        return new Sampler(data, folder, sqliteTemp);
    }

    static void check(String label, Estimate e, Observed o) {
        Map<Place, Object> one = new EnumMap<>(Place.class);
        for (Place p : Place.values()) one.put(p, "um");
        long eData = e.peak(Place.DATA), eCopies = e.peak(Place.COPIES), eTemp = e.peak(Place.TEMP);
        long eShared = e.peakPerVolume(one).getOrDefault("um", 0L);
        System.out.printf(Locale.ROOT, "MEDIDA %s amostras=%d dados=%d/%d copias=%d/%d temp=%d/%d mesmo_volume=%d/%d%n",
                label, o.samples(), o.data(), eData, o.copies(), eCopies, o.temp(), eTemp, o.shared(), eShared);
        assertAll(label,
                () -> assertTrue(o.data() <= eData, "dados: observado " + o.data() + " > estimado " + eData),
                () -> assertTrue(o.copies() <= eCopies, "cópias: observado " + o.copies() + " > estimado " + eCopies),
                () -> assertTrue(o.temp() <= eTemp, "temp: observado " + o.temp() + " > estimado " + eTemp),
                () -> assertTrue(o.shared() <= eShared, "mesmo volume: observado " + o.shared() + " > estimado " + eShared),
                () -> assertTrue(o.samples() > 10, "o amostrador rodou"));
    }

    @Test
    void verifiedCopy_staysWithinTheEstimate() throws Exception {
        inflate(2500, 60_000);
        long d = Files.size(db);

        Sampler s = sampler();
        BackupService.Result r = backups.createManual();
        Observed o = s.stop();

        assertEquals(BackupService.Outcome.CREATED, r.outcome(), r.message());
        assertTrue(o.copies() >= Files.size(r.file()), "o amostrador viu a cópia");
        check("copia d=" + d, SpaceEstimate.verifiedCopy(d, Place.COPIES), o);
    }

    @Test
    void restore_ofASmallerCopy_staysWithinTheEstimate() throws Exception {
        inflate(400, 10_000);
        BackupService.Result copy = backups.createManual();
        assertTrue(copy.ok(), copy.message());
        clock.advance(java.time.Duration.ofMinutes(5));
        inflate(2500, 60_000);
        change(uc, "b-1");
        long c = Files.size(copy.file()), d = Files.size(db);

        Sampler s = sampler();
        RestoreService svc = new RestoreService(backups, InstanceGuard.SOLE);
        RestoreService.Prepared prepared = svc.prepare(copy.file());
        assertTrue(prepared.ok(), prepared.refusal());
        RestoreService.Result r = svc.restore(prepared.preview());
        Observed o = s.stop();

        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        assertTrue(c < d);
        assertTrue(o.data() > 0, "o amostrador viu o journal");
        check("restauracao c=" + c + " d=" + d, SpaceEstimate.restore(c, d, false, false, false), o);
    }

    @Test
    void restore_ofALargerCopy_staysWithinTheEstimate() throws Exception {
        inflate(2500, 60_000);
        BackupService.Result copy = backups.createManual();
        assertTrue(copy.ok(), copy.message());
        clock.advance(java.time.Duration.ofMinutes(5));
        shrink();
        change(uc, "b-1");
        long c = Files.size(copy.file()), d = Files.size(db);

        Sampler s = sampler();
        RestoreService svc = new RestoreService(backups, InstanceGuard.SOLE);
        RestoreService.Prepared prepared = svc.prepare(copy.file());
        assertTrue(prepared.ok(), prepared.refusal());
        RestoreService.Result r = svc.restore(prepared.preview());
        Observed o = s.stop();

        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        assertTrue(c > d);
        assertTrue(o.data() >= c - d, "o arquivo cresceu até C");
        check("restauracao c=" + c + " d=" + d, SpaceEstimate.restore(c, d, false, false, false), o);
    }

    @Test
    void restoreOverDamagedData_staysWithinTheEstimate() throws Exception {
        inflate(400, 10_000);
        BackupService.Result copy = backups.createManual();
        assertTrue(copy.ok(), copy.message());
        clock.advance(java.time.Duration.ofMinutes(5));
        inflate(2500, 60_000);
        Database.close();
        corruptTable(db, "portfolio_movement");
        Database.configure(url(db));
        Database.open();
        long c = Files.size(copy.file()), d = Files.size(db);

        Sampler s = sampler();
        RestoreService svc = new RestoreService(backups, InstanceGuard.SOLE);
        RestoreService.Preview preview = svc.prepare(copy.file()).preview();
        RestoreService.Result first = svc.restore(preview);
        RestoreService.Result r = svc.restoreOverDamaged(preview);
        Observed o = s.stop();

        assertEquals(RestoreService.Outcome.CURRENT_DAMAGED, first.outcome(), first.message());
        assertEquals(RestoreService.Outcome.RESTORED, r.outcome(), r.message());
        assertTrue(o.copies() >= d, "o amostrador viu o arquivo danificado guardado");
        check("danificados c=" + c + " d=" + d, SpaceEstimate.restoreOverDamaged(c, d, false), o);
    }

    @Test
    void maintenanceVacuum_staysWithinTheCalculation() throws Exception {
        inflate(2500, 60_000);
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM medicao_lastro WHERE id % 2 = 0");
        }
        long d = Files.size(db);

        Sampler s = sampler();
        try (Connection c = Database.newConnection(); Statement st = c.createStatement()) {
            st.execute("VACUUM");
        }
        Observed o = s.stop();

        assertTrue(o.temp() > 0 || o.data() > 0, "o amostrador viu a compactação");
        check("manutencao d=" + d, SpaceEstimate.maintenance(d, true), o);
    }
}

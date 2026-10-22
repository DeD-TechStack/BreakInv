package com.daniel.infrastructure.persistence.backup;

import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verified copies on temp-file databases (DECISAO-ARMAZENAMENTO §5.1/§5.3): publication, no copy
 * without change, interrupted temps, concurrency, permission, retention limited to registered files.
 */
class BackupServiceTest {

    @TempDir
    Path tempDir;
    Path db;
    Path folder;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-22T06:00:00Z"));

    @AfterEach
    void tearDown() {
        Database.setPreMigrationHook(null);
        Database.close();
    }

    DailyTrackingUseCase open(String dirName) throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);
        db = dir.resolve("breakinv.db");
        folder = BackupService.defaultFolder(db);
        Database.configure(url(db));
        Database.open();
        return useCase();
    }

    BackupService service() {
        return BackupTestSupport.service(db, folder, clock);
    }

    @Test
    void manualCopy_isPublishedVerifiedAndRegistered() throws Exception {
        open("dados");
        seed("m");
        BackupService svc = service();

        BackupService.Result r = svc.createManual();

        assertEquals(BackupService.Outcome.CREATED, r.outcome(), r.message());
        assertEquals(List.of(r.file().getFileName().toString()), copies(folder));
        assertTrue(r.file().getFileName().toString().startsWith("breakinv-manual-20261022-060000-"));
        BackupVerifier.Report copy = BackupVerifier.verify(r.file());
        assertTrue(copy.faithfulTo(liveFingerprint()), "copy holds exactly the live content");
        assertTrue(copy.healthWarnings().isEmpty(), copy.healthWarnings().toString());
        BackupManifest.Loaded m = BackupManifest.load(folder);
        assertEquals(BackupManifest.Status.VALID, m.status());
        BackupManifest.Entry e = m.entries().get(0);
        assertEquals("manual", e.kind());
        assertEquals(BackupService.changeCounter(db), e.changeCounter());
        assertEquals(BackupService.sha256(r.file()), e.sha256());
        assertEquals(liveFingerprint().contentSha256(), e.fingerprint());
        assertFalse(Files.readString(BackupManifest.pathIn(folder)).contains("FICTA1"), "no row values in the registry");
        assertTrue(names(folder).stream().noneMatch(n -> n.endsWith(".partial")), names(folder).toString());
    }

    @Test
    void emptyOrNewDatabase_getsNoCopy() throws Exception {
        open("vazio");
        BackupService svc = service();
        assertEquals(BackupService.Outcome.EMPTY, svc.createManual().outcome());
        assertEquals(BackupService.Outcome.EMPTY, svc.dailyIfNeeded().outcome());
        assertEquals(List.of(), copies(folder));
    }

    @Test
    void noCopyWithoutChange_manualAndDaily_andOneDailyPerDay() throws Exception {
        DailyTrackingUseCase uc = open("mudanca");
        seed("c");
        BackupService svc = service();
        assertEquals(BackupService.Outcome.CREATED, svc.createManual().outcome());

        assertEquals(BackupService.Outcome.UNCHANGED, svc.createManual().outcome(), "manual without change");
        assertEquals(BackupService.Outcome.UNCHANGED, svc.dailyIfNeeded().outcome(), "daily without change");
        assertEquals(1, copies(folder).size());

        change(uc, "c-change-1");
        assertEquals(BackupService.Outcome.CREATED, svc.dailyIfNeeded().outcome());
        change(uc, "c-change-2");
        assertEquals(BackupService.Outcome.ALREADY_TODAY, svc.dailyIfNeeded().outcome(), "one daily copy per day");
        clock.advance(Duration.ofDays(1));
        assertEquals(BackupService.Outcome.CREATED, svc.dailyIfNeeded().outcome());
        assertEquals(3, copies(folder).size());
    }

    @Test
    void pathWithSpacesAccentsAndApostrophe() throws Exception {
        open("dados d'água ação 1");
        seed("p");
        BackupService svc = service();
        BackupService.Result r = svc.createManual();
        assertEquals(BackupService.Outcome.CREATED, r.outcome(), r.message());
        assertTrue(BackupVerifier.verify(r.file()).faithfulTo(liveFingerprint()));

        Path other = tempDir.resolve("pen drive d'outro");
        Files.createDirectories(other);
        BackupService.Result elsewhere = svc.createIn(other);
        assertEquals(BackupService.Outcome.CREATED, elsewhere.outcome(), elsewhere.message());
        assertEquals(other, elsewhere.file().getParent());
        assertFalse(Files.exists(BackupManifest.pathIn(other)), "a folder chosen by the user is not managed");
    }

    @Test
    void failureBeforePublishing_leavesNothingPublished() throws Exception {
        open("parcial");
        seed("f");
        BackupService svc = service();
        svc.hooks(new BackupService.Hooks() {
            @Override
            public void beforePublish(Path verifiedTemp) throws IOException {
                throw new IOException("queda simulada");
            }
        });

        BackupService.Result r = svc.createManual();

        assertEquals(BackupService.Outcome.FAILED, r.outcome());
        assertEquals(List.of(), copies(folder));
        assertTrue(names(folder).stream().noneMatch(n -> n.contains(".partial")), names(folder).toString());
        assertTrue(BackupManifest.load(folder).entries().isEmpty(), "pending registration removed");
    }

    @Test
    void interruptedTemp_isNeverPublished_andIsRemovedOnNextStart() throws Exception {
        open("queda");
        seed("q");
        Files.createDirectories(folder);
        // state left by a process that died during VACUUM INTO: registered temp, nothing published
        String temp = BackupNames.tempName(false, java.time.LocalDateTime.of(2026, 10, 21, 23, 0), "0a0b0c");
        Files.writeString(folder.resolve(temp), "metade de um arquivo");
        Files.writeString(folder.resolve(temp + "-journal"), "x");
        BackupManifest.save(folder, new ArrayList<>(List.of(new BackupManifest.Entry(temp, "diaria",
                BackupManifest.IN_PROGRESS, "2026-10-21T23:00:00Z", 0, -1, 0, null, null, Map.of(), null))));
        Path unrelated = folder.resolve("breakinv-copia-20261021-230000-ffffff.db.partial"); // not registered
        Files.writeString(unrelated, "não é deste registro");

        assertEquals(1, service().cleanupInterrupted());

        assertEquals(java.util.stream.Stream.of(BackupNames.MANIFEST, unrelated.getFileName().toString()).sorted().toList(),
                names(folder));
        assertTrue(BackupManifest.load(folder).entries().isEmpty());
        assertEquals(List.of(), copies(folder));
    }

    @Test
    void writeFromAnotherConnectionDuringTheCopy_isDetected() throws Exception {
        open("concorrencia");
        seed("w");
        BackupService svc = service();
        svc.hooks(new BackupService.Hooks() {
            @Override
            public void afterSourceSnapshot() {
                try (Connection other = DriverManager.getConnection(url(db)); Statement st = other.createStatement()) {
                    st.execute("INSERT INTO app_settings(key, value) VALUES ('teste_concorrencia', '1')");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        BackupService.Result r = svc.createManual();

        assertEquals(BackupService.Outcome.FAILED, r.outcome());
        assertTrue(r.message().contains("mudaram durante a cópia"), r.message());
        assertEquals(List.of(), copies(folder));
    }

    @Test
    void secondRequestWhileOneIsRunning_isBusy() throws Exception {
        open("ocupado");
        seed("b");
        BackupService svc = service();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            svc.busyLock().lock();
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                svc.busyLock().unlock();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try {
            assertEquals(BackupService.Outcome.BUSY, svc.createManual().outcome());
            assertEquals(BackupService.Outcome.BUSY, svc.createIn(tempDir).outcome());
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertNull(failure.get());
        assertEquals(BackupService.Outcome.CREATED, svc.createManual().outcome());
    }

    @Test
    void permissionDenied_isReported_andNothingIsWritten() throws Exception {
        open("permissao");
        seed("d");
        Path denied = tempDir.resolve("sem permissão");
        Files.createDirectories(denied);
        AclFileAttributeView view = Files.getFileAttributeView(denied, AclFileAttributeView.class);
        assumeTrue(view != null, "ACL view not available on this file system");
        UserPrincipal me = denied.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> original = view.getAcl();
        List<AclEntry> acl = new ArrayList<>(original);
        acl.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me)
                .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA).build());
        view.setAcl(acl);
        try {
            BackupService svc = new BackupService(db, denied, BackupService.SourceAccess.opening(Database::newConnection),
                    clock, RetentionPolicy.defaults());
            BackupService.Result r = svc.createManual();
            assertEquals(BackupService.Outcome.FAILED, r.outcome());
            assertTrue(r.message().contains("permissão"), r.message());
            assertEquals(List.of(), names(denied));
            assertEquals(BackupService.Outcome.FAILED, svc.createIn(denied).outcome());
            assertEquals(List.of(), names(denied));
        } finally {
            view.setAcl(original);
        }
    }

    @Test
    void neverReplacesAnExistingFile() throws Exception {
        open("nome");
        seed("n");
        Clock fixed = Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
        BackupService svc = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection),
                fixed, RetentionPolicy.defaults(), suffixes("aaaaaa", "aaaaaa", "bbbbbb"));
        Files.createDirectories(folder);
        Path taken = folder.resolve("breakinv-manual-20261022-060000-aaaaaa.db");
        Files.writeString(taken, "arquivo do usuário com o mesmo nome");

        BackupService.Result r = svc.createManual();

        assertEquals(BackupService.Outcome.CREATED, r.outcome(), r.message());
        assertEquals("breakinv-manual-20261022-060000-bbbbbb.db", r.file().getFileName().toString());
        assertEquals("arquivo do usuário com o mesmo nome", Files.readString(taken));
    }

    @Test
    void rotation_keepsTenAutomaticPlusProtected_andTouchesOnlyRegisteredFiles() throws Exception {
        DailyTrackingUseCase uc = open("rotacao");
        seed("r");
        BackupService svc = service();
        assertEquals(BackupService.Outcome.CREATED, svc.create(BackupKind.PRE_MIGRATION,
                BackupService.SourceAccess.opening(Database::newConnection)).outcome());
        clock.advance(Duration.ofHours(1));
        change(uc, "r-manual");
        assertEquals(BackupService.Outcome.CREATED, svc.createManual().outcome());
        String oldManual = copies(folder).stream().filter(n -> n.contains("-manual-")).findFirst().orElseThrow();
        String preMigration = copies(folder).stream().filter(n -> n.contains("-pre-migracao-")).findFirst().orElseThrow();

        Files.createDirectories(folder);
        List<String> decoys = List.of("breakinv-diaria-20200101-000000-abcdef.db", "notas.txt",
                "breakinv-manual-20200101-000000-abcdef.db");
        for (String d : decoys) Files.writeString(folder.resolve(d), "do usuário");

        List<String> dailies = new ArrayList<>();
        for (int day = 1; day <= 12; day++) {
            clock.advance(Duration.ofDays(1));
            change(uc, "r-day-" + day);
            BackupService.Result r = svc.dailyIfNeeded();
            assertEquals(BackupService.Outcome.CREATED, r.outcome(), "day " + day + ": " + r.message());
            dailies.add(r.file().getFileName().toString());
        }

        List<String> present = names(folder);
        for (String d : decoys) assertTrue(present.contains(d), "unregistered file untouched: " + d);
        assertTrue(present.contains(oldManual), "manual copy kept");
        assertTrue(present.contains(preMigration), "latest pre-migration copy kept");
        assertFalse(present.contains(dailies.get(0)), "oldest daily rotated");
        assertFalse(present.contains(dailies.get(1)), "second oldest daily rotated");
        for (String d : dailies.subList(2, 12)) assertTrue(present.contains(d), "recent daily kept: " + d);
        assertEquals(12, BackupManifest.load(folder).entries().size(), "10 daily + pre-migration + manual");
    }

    @Test
    void budgetConflict_isShown_andProtectedCopiesStay() throws Exception {
        DailyTrackingUseCase uc = open("orcamento");
        seed("o");
        BackupService svc = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection),
                clock, new RetentionPolicy(10, 1));
        assertEquals(BackupService.Outcome.CREATED, svc.createManual().outcome());
        clock.advance(Duration.ofDays(1));
        change(uc, "o-1");
        BackupService.Result daily = svc.dailyIfNeeded();
        assertEquals(BackupService.Outcome.CREATED, daily.outcome());

        assertEquals(2, copies(folder).size(), "manual and latest are protected even over budget");
        assertTrue(daily.warnings().stream().anyMatch(w -> w.contains("acima do limite")), daily.warnings().toString());
        assertNotNull(svc.status().notice());
    }

    @Test
    void unreadableRegistry_isKeptAside_andOlderCopiesAreNeverDeleted() throws Exception {
        DailyTrackingUseCase uc = open("registro");
        seed("i");
        Files.createDirectories(folder);
        Files.writeString(BackupManifest.pathIn(folder), "{ isto não é json", StandardCharsets.UTF_8);
        String older = "breakinv-diaria-20261001-060000-123456.db";
        Files.writeString(folder.resolve(older), "cópia anterior");
        BackupService svc = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection),
                clock, new RetentionPolicy(1, 1L << 30));

        assertNotNull(svc.status().notice(), "the screen says the registry is unreadable");
        BackupService.Result first = svc.createManual();
        assertEquals(BackupService.Outcome.CREATED, first.outcome());
        assertTrue(first.warnings().stream().anyMatch(w -> w.contains("estava ilegível")), first.warnings().toString());
        assertTrue(svc.status().notice().contains("estava ilegível"), "still on the screen after the copy");
        clock.advance(Duration.ofDays(1));
        change(uc, "i-1");
        BackupService.Result daily = svc.dailyIfNeeded();
        assertEquals(BackupService.Outcome.CREATED, daily.outcome());
        assertTrue(daily.warnings().stream().noneMatch(w -> w.contains("estava ilegível")), "reported once per copy");
        assertTrue(svc.status().notice().contains("estava ilegível"), "and still on the screen");

        List<String> present = names(folder);
        assertTrue(present.contains(older), "copy from the unreadable registry is never deleted");
        assertTrue(present.stream().anyMatch(n -> n.startsWith("breakinv-copias.invalido-")), present.toString());
        assertEquals(BackupManifest.Status.VALID, BackupManifest.load(folder).status());
    }

    @Test
    void oldCopyThatCannotBeDeleted_isAWarning_notAFailedCopy_evenBeforeAMigration() throws Exception {
        DailyTrackingUseCase uc = open("somente-leitura");
        seed("s");
        BackupService svc = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection),
                clock, new RetentionPolicy(1, 1L << 30));
        BackupService.Result first = svc.dailyIfNeeded();
        assertEquals(BackupService.Outcome.CREATED, first.outcome());
        Path old = first.file();
        assumeTrue(Files.getFileStore(old).supportsFileAttributeView("dos"), "DOS attributes not available");
        Files.setAttribute(old, "dos:readonly", true);
        try {
            clock.advance(Duration.ofDays(1));
            change(uc, "s-1");
            BackupService.Result second = svc.dailyIfNeeded();
            assertEquals(BackupService.Outcome.CREATED, second.outcome(), second.message());
            assertTrue(second.warnings().stream().anyMatch(w -> w.contains("não foi possível apagar a cópia antiga")),
                    second.warnings().toString());
            assertTrue(Files.exists(old));
            assertTrue(BackupManifest.load(folder).entries().stream()
                    .anyMatch(e -> e.file().equals(old.getFileName().toString())), "kept registered: tried again later");

            clock.advance(Duration.ofDays(1));
            change(uc, "s-2");
            BackupService.Result pre = svc.create(BackupKind.PRE_MIGRATION,
                    BackupService.SourceAccess.opening(Database::newConnection));
            assertEquals(BackupService.Outcome.CREATED, pre.outcome(), "the migration may proceed: the copy exists");
        } finally {
            Files.setAttribute(old, "dos:readonly", false);
        }
    }

    @Test
    void budgetCut_saysHowManyCopiesWentToFit() throws Exception {
        DailyTrackingUseCase uc = open("orcamento-corte");
        seed("k");
        BackupService.Result d1 = service().dailyIfNeeded();
        assertEquals(BackupService.Outcome.CREATED, d1.outcome());
        long size = Files.size(d1.file());
        BackupService svc = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection),
                clock, new RetentionPolicy(10, size * 5 / 2));
        clock.advance(Duration.ofDays(1));
        change(uc, "k-1");
        assertEquals(BackupService.Outcome.CREATED, svc.dailyIfNeeded().outcome());
        clock.advance(Duration.ofDays(1));
        change(uc, "k-2");
        BackupService.Result d3 = svc.dailyIfNeeded();

        assertEquals(BackupService.Outcome.CREATED, d3.outcome());
        assertFalse(Files.exists(d1.file()), "the oldest automatic copy went to fit 2.5 copies");
        assertTrue(d3.warnings().stream().anyMatch(w -> w.contains("para caber no limite") && w.contains("1 cópia(s)")),
                d3.warnings().toString());
        assertNull(svc.status().notice(), "a cut that fits is not a conflict");
    }

    @Test
    void managedFolderBehindAJunction_neverDeletesCopies() throws Exception {
        DailyTrackingUseCase uc = open("juncao");
        seed("j");
        Path real = tempDir.resolve("pasta-real");
        Files.createDirectories(real);
        Path link = tempDir.resolve("atalho-copias");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), real.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.isDirectory(link),
                "directory junctions not available here");
        BackupService svc = new BackupService(db, link, BackupService.SourceAccess.opening(Database::newConnection),
                clock, new RetentionPolicy(1, 1L << 30));
        assertEquals(BackupService.Outcome.CREATED, svc.dailyIfNeeded().outcome());
        clock.advance(Duration.ofDays(1));
        change(uc, "j-1");

        BackupService.Result second = svc.dailyIfNeeded();

        assertEquals(BackupService.Outcome.CREATED, second.outcome(), second.message());
        assertTrue(second.warnings().stream().anyMatch(w -> w.contains("atalho")), second.warnings().toString());
        assertEquals(2, copies(real).size(), "nothing deleted through the link");
    }

    @Test
    void registeredTempThatIsADirectory_isNeverDeleted() throws Exception {
        open("diretorio");
        seed("t");
        Files.createDirectories(folder);
        String temp = BackupNames.tempName(false, java.time.LocalDateTime.of(2026, 10, 21, 23, 0), "0d0e0f");
        Files.createDirectories(folder.resolve(temp));
        BackupManifest.save(folder, new ArrayList<>(List.of(new BackupManifest.Entry(temp, "diaria",
                BackupManifest.IN_PROGRESS, "2026-10-21T23:00:00Z", 0, -1, 0, null, null, Map.of(), null))));

        assertEquals(0, service().cleanupInterrupted());

        assertTrue(Files.isDirectory(folder.resolve(temp)), "only regular files of this feature are deleted");
        assertEquals(1, BackupManifest.load(folder).entries().size());
    }

    @Test
    void registryNamingAFileOutsideThePattern_isRefused_andThatFileStays() throws Exception {
        DailyTrackingUseCase uc = open("nome-fora");
        seed("x");
        Files.createDirectories(folder);
        Path users = folder.resolve("minha-planilha.db");
        Files.writeString(users, "do usuário");
        BackupManifest.save(folder, List.of(new BackupManifest.Entry("minha-planilha.db", "diaria",
                BackupManifest.VALID, "2026-10-01T06:00:00Z", 3, 1, 10, "sha", "fp", Map.of(), "ok")));
        assertEquals(BackupManifest.Status.INVALID, BackupManifest.load(folder).status());
        BackupService svc = new BackupService(db, folder, BackupService.SourceAccess.opening(Database::newConnection),
                clock, new RetentionPolicy(1, 1L << 30));

        for (int day = 1; day <= 3; day++) {
            clock.advance(Duration.ofDays(1));
            change(uc, "x-" + day);
            assertEquals(BackupService.Outcome.CREATED, svc.dailyIfNeeded().outcome());
        }

        assertEquals("do usuário", Files.readString(users));
        assertTrue(names(folder).stream().noneMatch(n -> n.startsWith(BackupNames.MANIFEST + ".")
                && n.endsWith(BackupNames.PARTIAL)), "no registry temp left behind");
    }

    @Test
    void reusesTheLastCopyBeforeRestoring_whenNothingChanged_unlessItsFileChanged() throws Exception {
        open("reuso");
        seed("u");
        BackupService svc = service();
        BackupService.Result manual = svc.createManual();
        BackupService.Result pre = svc.ensureCurrentCopy(BackupKind.PRE_RESTORE);
        assertEquals(BackupService.Outcome.REUSED, pre.outcome());
        assertEquals(manual.file(), pre.file());
        assertEquals(manual.fingerprint(), pre.fingerprint());

        Files.writeString(manual.file(), "corrompido", java.nio.file.StandardOpenOption.APPEND);
        BackupService.Result fresh = svc.ensureCurrentCopy(BackupKind.PRE_RESTORE);
        assertEquals(BackupService.Outcome.CREATED, fresh.outcome(), "a changed file is not trusted");
    }

    // ------------------------------------------------------------------
    // Consultant, BRK-30 r4: going back to a damaged file is judged on the header as well
    // ------------------------------------------------------------------

    /**
     * Header bytes a restore writes for the destination (sqlite.org/fileformat2.html §1.3; backup.c):
     * file change counter (24), schema cookie (40), version-valid-for (92), SQLITE_VERSION_NUMBER (96).
     * Written here on purpose, apart from the production list.
     */
    static final Set<Integer> RESTORE_STAMPS = IntStream.range(0, 100)
            .filter(i -> i / 4 == 6 || i / 4 == 10 || i / 4 == 23 || i / 4 == 24)
            .boxed().collect(Collectors.toUnmodifiableSet());

    Path closedSample() throws Exception {
        open("amostra");
        seed("a");
        Database.close();
        return db;
    }

    Path withBytes(Path source, int offset, byte... bytes) throws IOException {
        byte[] all = Files.readAllBytes(source);
        System.arraycopy(bytes, 0, all, offset, bytes.length);
        return Files.write(tempDir.resolve("alterado.db"), all);
    }

    static int int32(byte[] b, int offset) {
        return java.nio.ByteBuffer.wrap(b, offset, 4).getInt();
    }

    /**
     * Characterization of sqlite-jdbc 3.51.2.0, without the production comparator: a file restored
     * over a live database comes back with every byte after the header as in the file, and only the
     * restore stamps changed in the header.
     */
    @Test
    void driverRestore_changesOnlyTheStampsOfTheHeader_characterization() throws Exception {
        DailyTrackingUseCase uc = open("carimbos");
        seed("c");
        Path original = tempDir.resolve("original.db");
        Files.copy(db, original); // idle connection, rollback journal: the file is whole
        change(uc, "c-depois");
        Database.close();
        try (Connection c = DriverManager.getConnection(url(db))) {
            assertEquals(0, c.unwrap(org.sqlite.SQLiteConnection.class).getDatabase()
                    .restore("main", original.toString(), (a, b) -> { }));
        }

        byte[] was = Files.readAllBytes(original);
        byte[] now = Files.readAllBytes(db);
        assertEquals(was.length, now.length);
        assertArrayEquals(Arrays.copyOfRange(was, 100, was.length), Arrays.copyOfRange(now, 100, now.length));
        Set<Integer> differ = new TreeSet<>();
        for (int i = 0; i < 100; i++) if (was[i] != now[i]) differ.add(i);
        assertTrue(RESTORE_STAMPS.containsAll(differ), "header bytes the restore changed: " + differ);
        assertNotEquals(int32(was, 24), int32(now, 24), "change counter of the destination");
        assertNotEquals(int32(was, 40), int32(now, 40), "schema cookie of the destination");
        assertTrue(BackupService.samePagesAsRestored(original, db), "what a restore really leaves is accepted");
    }

    @Test
    void restoredComparison_rejectsAnotherPageSizeOrTextEncoding() throws Exception {
        Path a = closedSample();
        byte[] h = Files.readAllBytes(a);
        int pageSize = ((h[16] & 0xFF) << 8) | (h[17] & 0xFF);
        int other = pageSize == 4096 ? 8192 : 4096;
        assertEquals(1, int32(h, 56), "the sample is UTF-8");

        assertFalse(BackupService.samePagesAsRestored(a, withBytes(a, 16, (byte) (other >> 8), (byte) other)),
                "page size " + pageSize + " -> " + other);
        assertFalse(BackupService.samePagesAsRestored(a, withBytes(a, 56, (byte) 0, (byte) 0, (byte) 0, (byte) 2)),
                "text encoding UTF-8 -> UTF-16le");
    }

    @Test
    void restoredComparison_rejectsAChangeInAnyHeaderByteARestoreDoesNotWrite() throws Exception {
        Path a = closedSample();
        byte[] h = Files.readAllBytes(a);
        List<Integer> unnoticed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            if (RESTORE_STAMPS.contains(i)) continue;
            if (BackupService.samePagesAsRestored(a, withBytes(a, i, (byte) (h[i] ^ 0x01)))) unnoticed.add(i);
        }
        assertEquals(List.of(), unnoticed, "header bytes whose change went unnoticed");
    }

    @Test
    void restoredComparison_acceptsOnlyTheStamps_andNoOtherByte() throws Exception {
        Path a = closedSample();
        byte[] b = Files.readAllBytes(a);
        for (int i : RESTORE_STAMPS) b[i] ^= 0x5A;
        Path stamped = Files.write(tempDir.resolve("carimbado.db"), b);
        assertTrue(BackupService.samePagesAsRestored(a, stamped), "only the four stamps differ");

        b[100] ^= 0x01;
        assertFalse(BackupService.samePagesAsRestored(a, Files.write(stamped, b)), "first byte after the header");
        b[100] ^= 0x01;
        b[b.length - 1] ^= 0x01;
        assertFalse(BackupService.samePagesAsRestored(a, Files.write(stamped, b)), "last byte of the file");
        assertFalse(BackupService.samePagesAsRestored(a,
                Files.write(stamped, Arrays.copyOf(Files.readAllBytes(a), b.length + 1))), "one more byte");
    }
}

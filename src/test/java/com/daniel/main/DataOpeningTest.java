package com.daniel.main;

import com.daniel.infrastructure.config.AppConfig;
import com.daniel.infrastructure.persistence.backup.FileInstanceLock;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.presentation.view.util.FailureReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U4C:RF-01 (the attempt) and U4C:RF-05 items 1 to 3 (real failures in a throwaway folder). Nothing here opens the
 * user's real data file: every test points {@code Database} at a file in its own {@code @TempDir}.
 */
class DataOpeningTest {

    private static final String WARNING_TEXT = "data opening failed";
    private static final String LOCK_WARNING = "instance lock unavailable; restoring will be refused";

    @TempDir
    Path tempDir;
    final OpeningFixture fixture = new OpeningFixture();

    @AfterEach
    void tearDown() {
        fixture.restore();
    }

    private static DataOpening.Attempt attemptAndLog(DataOpening opening) {
        return assertDoesNotThrow(opening::attempt, "a failed opening is an outcome, not an exception");
    }

    private static void assertInstanceLockFree(Path dataFile) throws Exception {
        OpeningFixture.assertInstanceLockFree(dataFile);
    }

    /** No connection is left open on the data file: on Windows an open file cannot be renamed. */
    private static void assertFileCanBeRenamedAndBack(Path dataFile) {
        Path moved = dataFile.resolveSibling(dataFile.getFileName() + ".movido");
        assertDoesNotThrow(() -> Files.move(dataFile, moved), "the failed attempt must have closed its connection");
        assertDoesNotThrow(() -> Files.move(moved, dataFile));
    }

    private static void printEvidence(int caseNumber, String what, RuntimeException failure) {
        System.out.println("U4C-RF05 caso " + caseNumber + " (" + what + ") | " + OpeningFixture.describe(failure)
                + " | motivo=" + FailureReason.forOpening(failure));
    }

    // ── U4C:RF-01 ───────────────────────────────────────────────────────────

    @Test
    void anAttemptOnANewFileOpensWithTheConnectionOfTheAttempt() throws Exception {
        Path db = tempDir.resolve("novo.db");
        Database.configure(OpeningFixture.url(db));

        try (LogCapture configLog = new LogCapture(AppConfig.class)) {
            DataOpening.Attempt attempt = attemptAndLog(new DataOpening((candidate, file) -> { }));

            assertTrue(attempt.opened());
            assertNull(attempt.failure());
            assertNotNull(attempt.connection());
            assertFalse(attempt.connection().isClosed());
            assertSame(attempt.config().getConnection(), attempt.connection());
            assertTrue(configLog.of(Level.WARNING, LOCK_WARNING).isEmpty(), "an opened attempt holds the instance lock");
            assertThrows(OverlappingFileLockException.class,
                    () -> FileInstanceLock.holdShared(db.resolveSibling(FileInstanceLock.FILE_NAME)),
                    "the instance lock is held while the data is open");
            attempt.release();
        }
    }

    @Test
    void releasingAnOpenedAttemptClosesTheConnectionAndLetsGoOfTheLock() throws Exception {
        Path db = tempDir.resolve("novo.db");
        Database.configure(OpeningFixture.url(db));
        DataOpening.Attempt attempt = attemptAndLog(new DataOpening((candidate, file) -> { }));
        assertTrue(attempt.opened());

        attempt.release();

        assertTrue(attempt.connection().isClosed());
        assertInstanceLockFree(db);
        assertFileCanBeRenamedAndBack(db);
    }

    @Test
    void noFileIsAFailureOfTheOpeningToo() {
        Database.configure("jdbc:sqlite::memory:");

        try (LogCapture appLog = new LogCapture(App.class)) {
            DataOpening.Attempt attempt = attemptAndLog(new DataOpening());

            assertFalse(attempt.opened());
            assertNull(attempt.config());
            assertNotNull(attempt.failure());
            assertEquals("o banco de dados precisa de um arquivo", attempt.failure().getMessage());
            assertEquals("o banco de dados precisa de um arquivo", FailureReason.forOpening(attempt.failure()));
            assertEquals(1, appLog.of(Level.WARNING, WARNING_TEXT).size());
        }
    }

    @Test
    void everyFailureWritesAWarningOnTheAppLoggerWithTheException() throws Exception {
        Path db = tempDir.resolve("pasta.db");
        Files.createDirectory(db);
        Database.configure(OpeningFixture.url(db));

        try (LogCapture appLog = new LogCapture(App.class)) {
            DataOpening.Attempt attempt = attemptAndLog(new DataOpening());

            assertFalse(attempt.opened());
            var records = appLog.of(Level.WARNING, WARNING_TEXT);
            assertEquals(1, records.size());
            assertSame(attempt.failure(), records.get(0).getThrown());
        }
    }

    @Test
    void anErrorIsNotCaughtByTheAttemptAndTheInstanceLockIsLetGo() throws Exception {
        Path db = tempDir.resolve("legado.db");
        OpeningFixture.legacy(db);
        Database.configure(OpeningFixture.url(db));
        class OutOfMemoryLike extends Error { }
        DataOpening opening = new DataOpening((candidate, file) -> {
            candidate.close();
            throw new OutOfMemoryLike();
        });

        assertThrows(OutOfMemoryLike.class, opening::attempt);

        assertInstanceLockFree(db);
    }

    // ── U4C:RF-05 item 1: the path of the data file is a folder ─────────────

    @Test
    void realFailure1_aFolderInPlaceOfTheDataFile_cannotBeOpenedAndNothingIsCreatedBesideIt() throws Exception {
        Path db = tempDir.resolve("dados.db");
        Files.createDirectory(db);
        Database.configure(OpeningFixture.url(db));

        DataOpening.Attempt attempt = attemptAndLog(new DataOpening());

        assertFalse(attempt.opened());
        printEvidence(1, "caminho do arquivo de dados é uma pasta", attempt.failure());
        assertEquals("arquivo de dados inacessível", FailureReason.forOpening(attempt.failure()));
        assertTrue(Files.isDirectory(db));
        try (var inside = Files.list(db)) {
            assertEquals(List.of(), inside.toList(), "the folder stays empty");
        }
        try (var beside = Files.list(tempDir)) {
            List<String> names = beside.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(names.stream().noneMatch(n -> !n.equals("dados.db") && n.startsWith("dados.db")),
                    "no data file or its journal is created beside the folder: " + names);
            assertTrue(names.stream().allMatch(n -> n.equals("dados.db") || n.equals(FileInstanceLock.FILE_NAME)
                    || n.equals("backups")), "only the folder, the lock file or the copies folder: " + names);
        }
        assertInstanceLockFree(db);
    }

    // ── U4C:RF-05 item 2: a data file that is not a database ────────────────

    @Test
    void realFailure2_aFileThatIsNotADatabase_failsAsDamagedAndIsNotChanged() throws Exception {
        Path db = tempDir.resolve("danificado.db");
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 'x');
        System.arraycopy("NAO E UM BANCO!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 0, 16);
        Files.write(db, bytes);
        String before = OpeningFixture.sha256(db);
        Database.configure(OpeningFixture.url(db));

        DataOpening.Attempt attempt = attemptAndLog(new DataOpening());

        assertFalse(attempt.opened());
        printEvidence(2, "arquivo de 4096 bytes que não é um banco", attempt.failure());
        assertTrue(FailureReason.forOpening(attempt.failure()).contains("danificado"),
                FailureReason.forOpening(attempt.failure()));
        assertEquals(before, OpeningFixture.sha256(db), "the bytes of the data file are the same");
        assertEquals(4096, Files.size(db));
        assertInstanceLockFree(db);
        assertFileCanBeRenamedAndBack(db);
    }

    // ── U4C:RF-05 item 3: the copy before the migration is refused ──────────

    @Test
    void realFailure3_theCopyBeforeTheMigrationIsRefused_thenTheLockAndTheFileAreFree_andANewAttemptOpensWithTheLock()
            throws Exception {
        Path db = tempDir.resolve("legado.db");
        OpeningFixture.legacy(db);
        String before = OpeningFixture.sha256(db);
        Database.configure(OpeningFixture.url(db));
        DataOpening refusing = new DataOpening((candidate, file) -> {
            throw new IllegalStateException("sem espaço para a cópia antes da migração");
        });

        DataOpening.Attempt failed = attemptAndLog(refusing);

        assertFalse(failed.opened());
        printEvidence(3, "cópia antes da migração recusada", failed.failure());
        assertEquals("sem espaço para a cópia antes da migração", FailureReason.forOpening(failed.failure()));
        assertEquals(before, OpeningFixture.sha256(db), "the bytes of the data file are the same");
        // U4C:RF-01 item 2 (i): this JVM can take the instance lock; (ii): the file can be renamed and back
        assertInstanceLockFree(db);
        assertFileCanBeRenamedAndBack(db);

        // the cause is removed (the copy step does nothing now): a new attempt opens, and with the lock
        try (LogCapture configLog = new LogCapture(AppConfig.class)) {
            DataOpening.Attempt opened = attemptAndLog(new DataOpening((candidate, file) -> { }));

            assertTrue(opened.opened(), String.valueOf(opened.failure()));
            assertTrue(configLog.of(Level.WARNING, LOCK_WARNING).isEmpty(),
                    "the new attempt must not fall into the branch without the instance lock");
            assertThrows(OverlappingFileLockException.class,
                    () -> FileInstanceLock.holdShared(db.resolveSibling(FileInstanceLock.FILE_NAME)));
            opened.release();
        }
    }
}

package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.daniel.infrastructure.persistence.backup.BackupTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-32 S2:RF-06 case 4, robustness: a child process releases the space of a test database of at least 20 MiB with
 * free pages, and the parent kills it. After reopening, {@code integrity_check} is ok and the content fingerprint is
 * the one from before. That holds when the child is killed before, during or after the compaction; the test records
 * where the child was and does not require an exact point. Everything runs on files in the test's temp folder.
 */
class SpaceReleaseInterruptedTest extends SpaceReleaseTestBase {

    @Test
    void afterKillingTheChild_beforeDuringAndAfterTheCompaction_theDataAreIntact() throws Exception {
        fragment(80, 40, 128 * 1024);
        long d = Files.size(db);
        long[] pagesBefore = pragmas(db);
        assertTrue(d >= 20 * MIB, "fixture: pelo menos 20 MiB, tem " + d);
        assertTrue(pagesBefore[2] > 0, "fixture: com páginas livres");
        DatabaseFingerprint before = liveFingerprint();
        Database.close();

        // the point the test aims at is "durante"; the other two show the property holds around it
        round("durante", "durante", 30, true, d, before);
        round("antes", "antes", 5, false, d, before);
        round("depois", "depois", 0, false, d, before);
    }

    /**
     * @param name   folder of the round (a copy of the data of the test)
     * @param waitFor the line of the child's phase file that the parent waits for before counting the delay
     * @param primary prints the line that the evidence reads
     */
    private void round(String name, String waitFor, long delayMs, boolean primary, long d, DatabaseFingerprint before)
            throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("rodada-" + name));
        Path roundDb = dir.resolve("breakinv.db");
        Path marker = dir.resolve("fase.txt");
        Path childLog = dir.resolve("filho.log");
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.copy(db, roundDb);

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        ProcessBuilder builder = new ProcessBuilder(java, "-Duser.home=" + home, "-Djava.io.tmpdir=" + tmp, "-cp",
                System.getProperty("java.class.path"), SpaceReleaseChildMain.class.getName(), roundDb.toString(),
                marker.toString())
                .redirectErrorStream(true)
                .redirectOutput(childLog.toFile());
        // the temp files of SQLite (the VACUUM) stay inside the test folder as well
        builder.environment().put("TMP", tmp.toString());
        builder.environment().put("TEMP", tmp.toString());
        Process child = builder.start();
        String point;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
            while (!phases(marker).contains(waitFor) && System.nanoTime() < deadline && child.isAlive()) {
                Thread.sleep(2);
            }
            assertTrue(phases(marker).contains(waitFor), "o filho não chegou à fase " + waitFor + "; fases="
                    + phases(marker) + "; log=" + read(childLog));
            if (delayMs > 0) Thread.sleep(delayMs);
        } finally {
            child.destroyForcibly();
        }
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "o filho morreu");
        List<String> phases = phases(marker);
        point = phases.get(phases.size() - 1);

        boolean journalExisted = Files.exists(roundDb.resolveSibling("breakinv.db-journal"));
        long sizeAfterKill = Files.size(roundDb);
        // reopening on a plain read-write connection: a hot journal, if any, is rolled back by SQLite itself
        BackupVerifier.Report report;
        try (Connection c = DriverManager.getConnection(url(roundDb))) {
            report = BackupVerifier.verify(c);
        }

        assertTrue(report.integrityOk(), "integrity_check ok depois de matar o filho em '" + point + "': "
                + report.integrityDetail());
        assertNotNull(report.fingerprint(), "o arquivo abre e é lido depois de matar o filho em '" + point + "'");
        assertEquals(before.contentSha256(), report.fingerprint().contentSha256(),
                "a impressão digital é a de antes, matando o filho em '" + point + "'");
        assertEquals(before, report.fingerprint());

        String detail = "alvo=" + name + " atraso_ms=" + delayMs + " fases=" + phases + " tamanho_antes=" + d
                + " tamanho_apos_a_morte=" + sizeAfterKill + " journal_existia=" + journalExisted + " integridade=ok";
        System.out.println((primary ? "BRK32S2 INTERROMPIDO" : "BRK32S2 PONTO-EXTRA") + " ponto=" + point + " " + detail);
    }

    private static List<String> phases(Path marker) throws IOException {
        if (!Files.exists(marker)) return List.of();
        try {
            return Files.readAllLines(marker).stream().filter(s -> !s.isBlank()).toList();
        } catch (IOException busyWriting) {
            return List.of();
        }
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "(sem log)";
        } catch (IOException e) {
            return "(log ilegível: " + e.getMessage() + ")";
        }
    }
}

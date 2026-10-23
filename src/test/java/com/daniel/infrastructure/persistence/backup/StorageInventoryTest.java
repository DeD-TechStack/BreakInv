package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.backup.StorageInventory.FileItem;
import com.daniel.infrastructure.persistence.backup.StorageInventory.Group;
import com.daniel.infrastructure.persistence.backup.StorageInventory.Role;
import com.daniel.infrastructure.persistence.backup.StorageInventory.Snapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.daniel.infrastructure.persistence.backup.StorageInventory.Group.*;
import static org.junit.jupiter.api.Assertions.*;

/** BRK-31 u1: what the data folder holds, measured without changing anything (DESENHO-BRK31 §3, §5.4, D-Q4). */
class StorageInventoryTest {

    @TempDir
    Path home;

    private Path db;
    private Path backups;
    private Path temp;

    private void layout() throws IOException {
        Path data = Files.createDirectories(home.resolve("BreakInv"));
        db = data.resolve("breakinv.db");
        Files.write(db, new byte[4096]);
        Files.write(data.resolve("breakinv.db-journal"), new byte[512]);
        backups = Files.createDirectories(data.resolve("backups"));
        temp = Files.createDirectories(home.resolve("tmp"));
    }

    private static BackupManifest.Entry entry(String file, String kind, long size) {
        return new BackupManifest.Entry(file, kind, BackupManifest.VALID, "2026-10-20T10:00:00Z", 3, 1, size, null,
                null, Map.of(), "ok");
    }

    /** Every path under {@code dir} with its size, so a test proves nothing was created, moved or deleted. */
    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.map(p -> dir.relativize(p) + ":" + sizeOrDir(p)).sorted().toList();
        }
    }

    private static String sizeOrDir(Path p) {
        try {
            return Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "d" : String.valueOf(Files.size(p));
        } catch (IOException e) {
            return "?";
        }
    }

    private static void assertGroup(Snapshot s, Group g, int files, long knownBytes, int unknownSizes) {
        StorageInventory.GroupTotal t = s.total(g);
        assertEquals(files, t.files(), g + ": quantidade");
        assertEquals(knownBytes, t.knownBytes(), g + ": bytes");
        assertEquals(unknownSizes, t.unknownSizes(), g + ": tamanhos não medidos");
    }

    private static List<String> names(Snapshot s, Group g) {
        return s.backupFiles().stream().filter(f -> f.group() == g).map(FileItem::name).sorted().toList();
    }

    @Test
    void databaseSizeIsTheMainFileOnly_auxiliaryFilesApart() throws IOException {
        layout();
        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(10_000_000L));

        assertEquals(4096, s.databaseBytes(), "D é só o arquivo principal");
        assertEquals(List.of("breakinv.db-journal"), s.auxiliary().stream().map(FileItem::name).toList());
        assertEquals(512, s.auxiliaryBytes());
    }

    @Test
    void backupFolder_everyFileInExactlyOneGroup_andNothingChanges() throws IOException {
        layout();
        String manual = "breakinv-manual-20261020-100000-aaaaaa.db";
        String daily = "breakinv-diaria-20261021-100000-bbbbbb.db";
        String orphan = "breakinv-diaria-20261019-100000-cccccc.db"; // the app's name, but not registered
        String gone = "breakinv-diaria-20261017-100000-999999.db"; // registered, but not on disk
        Files.write(backups.resolve(manual), new byte[1000]);
        Files.write(backups.resolve(daily), new byte[2000]);
        Files.write(backups.resolve(orphan), new byte[300]);
        BackupManifest.save(backups, List.of(entry(manual, "manual", 1000), entry(daily, "diaria", 2000),
                entry(gone, "diaria", 5000)));
        Files.write(backups.resolve("breakinv-danificado-20261018-090000-dddddd.db"), new byte[700]);
        Files.write(backups.resolve("breakinv-danificado-20261022-090000-eeeeee.db"), new byte[800]);
        Files.write(backups.resolve("breakinv-copia-20261022-120000-ffffff.db.partial"), new byte[50]);
        Files.write(backups.resolve("breakinv-copia-20261022-120000-ffffff.db.partial-journal"), new byte[5]);
        Files.writeString(backups.resolve("breakinv-danificado-copia.db"), "fora do padrão");
        Files.writeString(backups.resolve("notas.txt"), "minha anotação");
        Files.createDirectories(backups.resolve("antigas"));
        List<String> before = listing(home);

        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(10_000_000L));

        assertEquals(before, listing(home), "o inventário não cria, move nem apaga nada");
        assertEquals(BackupManifest.Status.VALID, s.manifestStatus());
        assertGroup(s, MANAGED, 2, 3000, 0);
        assertGroup(s, UNREGISTERED_COPY, 1, 300, 0);
        assertGroup(s, DAMAGED, 2, 1500, 0);
        assertGroup(s, TEMP, 2, 55, 0);
        assertGroup(s, UNKNOWN, 2, "fora do padrão".getBytes(StandardCharsets.UTF_8).length
                + "minha anotação".getBytes(StandardCharsets.UTF_8).length, 0);
        assertGroup(s, DIRECTORY, 1, 0, 1);
        assertEquals(1, s.total(MANIFEST).files());
        assertEquals(List.of("breakinv-danificado-20261018-090000-dddddd.db",
                "breakinv-danificado-20261022-090000-eeeeee.db"), names(s, DAMAGED));
        assertEquals(List.of("breakinv-danificado-copia.db", "notas.txt"), names(s, UNKNOWN));
        assertEquals(List.of(gone), s.manifestMissing(), "registro sem arquivo vira diagnóstico");
        assertTrue(s.backupFiles().stream().filter(f -> f.group() == DAMAGED).allMatch(f -> f.modified() != null),
                "danificados com data");
        assertEquals(11, s.backupFiles().size(), "cada arquivo da pasta aparece uma única vez");
    }

    @Test
    void invalidManifest_copiesAreUnregistered_andNothingChanges() throws IOException {
        layout();
        String daily = "breakinv-diaria-20261021-100000-bbbbbb.db";
        Files.write(backups.resolve(daily), new byte[2000]);
        Files.writeString(backups.resolve("breakinv-copias.json"), "{ quebrado");
        List<String> before = listing(home);

        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(1L));

        assertEquals(before, listing(home));
        assertEquals(BackupManifest.Status.INVALID, s.manifestStatus());
        assertGroup(s, MANAGED, 0, 0, 0);
        assertGroup(s, UNREGISTERED_COPY, 1, 2000, 0);
        assertEquals(1, s.total(MANIFEST).files());
    }

    @Test
    void missingBackupFolder_isEmpty_andIsNotCreated() throws IOException {
        layout();
        Files.delete(backups);

        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(1L));

        assertFalse(s.backupFolderExists());
        assertTrue(s.backupFiles().isEmpty());
        assertEquals(BackupManifest.Status.MISSING, s.manifestStatus());
        assertFalse(Files.exists(backups), "medir não cria a pasta");
    }

    @Test
    void missingDatabase_isUnknownNotZero() throws IOException {
        layout();
        Files.delete(db);

        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(1L));

        assertEquals(-1, s.databaseBytes(), "indisponível, nunca zero");
        assertFalse(s.complete());
    }

    @Test
    void symbolicLinkInBackupFolder_isListedButNotFollowed() throws IOException {
        layout();
        Path outside = Files.createDirectories(home.resolve("fora"));
        Files.write(outside.resolve("grande.db"), new byte[9000]);
        Path link = backups.resolve("atalho");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("sem permissão para criar link simbólico: " + e);
        }

        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(1L));

        assertGroup(s, LINK, 1, 0, 1);
        assertEquals(4096 + 512, s.knownTotalBytes(), "o conteúdo apontado pelo link não entra na conta");
        assertTrue(Files.exists(outside.resolve("grande.db")));
    }

    @Test
    void junctionInBackupFolder_isListedButNotFollowed() throws IOException, InterruptedException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "junção só existe no Windows");
        layout();
        Path outside = Files.createDirectories(home.resolve("fora"));
        Files.write(outside.resolve("grande.db"), new byte[9000]);
        Path junction = backups.resolve("juncao");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "mklink /J indisponível");

        Snapshot s = StorageInventory.take(db, backups, temp, FakeSpaceProbe.all(1L));

        assertGroup(s, LINK, 1, 0, 1);
        assertEquals(4096 + 512, s.knownTotalBytes());
        assertTrue(Files.exists(outside.resolve("grande.db")));
    }

    @Test
    void spacePerRole_unavailableAndFailedNeverBecomeZero() throws IOException {
        layout();
        SpaceProbe.Measured realZero = new SpaceProbe.Measured(0, db.getParent(), "C");
        FakeSpaceProbe probe = FakeSpaceProbe.none()
                .on(db.getParent(), realZero)
                .on(temp, new SpaceProbe.Failed("IOException: dispositivo não está pronto"));

        Snapshot s = StorageInventory.take(db, backups, temp, probe);

        assertEquals(realZero, s.space(Role.DATA), "zero medido continua zero");
        assertInstanceOf(SpaceProbe.Failed.class, s.space(Role.TEMP));
        assertInstanceOf(SpaceProbe.Unavailable.class, s.space(Role.BACKUPS));
    }

    @Test
    void sameVolume_comesFromTheProbe_andIsFalseWithoutAMeasure() throws IOException {
        layout();
        FakeSpaceProbe probe = FakeSpaceProbe.none()
                .on(db.getParent(), new SpaceProbe.Measured(100, db.getParent(), "C"))
                .on(backups, new SpaceProbe.Measured(100, backups, "C"))
                .on(temp, new SpaceProbe.Measured(900, temp, "D"));

        Snapshot s = StorageInventory.take(db, backups, temp, probe);

        assertTrue(s.sameVolume(Role.DATA, Role.BACKUPS));
        assertFalse(s.sameVolume(Role.DATA, Role.TEMP));

        Snapshot unknown = StorageInventory.take(db, backups, temp, FakeSpaceProbe.none());
        assertFalse(unknown.sameVolume(Role.DATA, Role.BACKUPS), "sem medida não se presume o mesmo volume");
    }
}

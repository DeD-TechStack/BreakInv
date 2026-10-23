package com.daniel.infrastructure.persistence.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the data folder holds, measured without creating, moving or deleting anything (DESENHO-BRK31 §3, §5.4).
 * D is the main database file only; journal, WAL and SHM, the copies, the damaged files and the temporaries are
 * reported apart. A size that cannot be read is -1 ("indisponível"), never zero. Links and junctions are listed
 * and never followed, and unknown files only feed the diagnosis: nothing here is a list of things to delete.
 */
public final class StorageInventory {

    public enum Group { MANAGED, UNREGISTERED_COPY, DAMAGED, TEMP, MANIFEST, UNKNOWN, LINK, DIRECTORY }

    public enum Role { DATA, BACKUPS, TEMP }

    static final List<String> AUXILIARY_SUFFIXES = List.of("-journal", "-wal", "-shm");
    private static final Pattern MANIFEST_ASIDE = Pattern.compile("breakinv-copias\\.invalido-.+\\.json");
    private static final Pattern MANIFEST_TEMP = Pattern.compile("breakinv-copias\\.json\\.[0-9a-f]{6}\\.partial");

    /** @param bytes -1 when not measured: unreadable, a link, or a folder whose content is not walked */
    public record FileItem(String name, Group group, long bytes, Instant modified) {
    }

    public record GroupTotal(int files, long knownBytes, int unknownSizes) {
    }

    public record Snapshot(Path databaseFile, long databaseBytes, List<FileItem> auxiliary, Path backupFolder,
                           boolean backupFolderExists, BackupManifest.Status manifestStatus,
                           List<String> manifestMissing, List<FileItem> backupFiles, String backupListingProblem,
                           Map<Role, SpaceProbe.Space> spaceByRole) {

        public GroupTotal total(Group group) {
            int files = 0;
            int unknown = 0;
            long known = 0;
            for (FileItem f : backupFiles) {
                if (f.group() != group) continue;
                files++;
                if (f.bytes() < 0) unknown++;
                else known = Math.addExact(known, f.bytes());
            }
            return new GroupTotal(files, known, unknown);
        }

        /** Journal, WAL and SHM that exist next to the database, known sizes only. */
        public long auxiliaryBytes() {
            return auxiliary.stream().filter(f -> f.bytes() >= 0).mapToLong(FileItem::bytes).reduce(0, Math::addExact);
        }

        public SpaceProbe.Space space(Role role) {
            return spaceByRole.get(role);
        }

        /** True only when both roles were measured on the same volume; an unknown is never presumed equal. */
        public boolean sameVolume(Role a, Role b) {
            return space(a) instanceof SpaceProbe.Measured ma && space(b) instanceof SpaceProbe.Measured mb
                    && ma.volume() != null && Objects.equals(ma.volume(), mb.volume());
        }

        /** D, the auxiliary files and the copies folder, adding only the sizes that were read. */
        public long knownTotalBytes() {
            long total = Math.max(0, databaseBytes);
            total = Math.addExact(total, auxiliaryBytes());
            for (FileItem f : backupFiles) {
                if (f.bytes() >= 0) total = Math.addExact(total, f.bytes());
            }
            return total;
        }

        /** Every size was read; otherwise {@link #knownTotalBytes} is a lower bound. */
        public boolean complete() {
            return databaseBytes >= 0 && backupListingProblem == null
                    && auxiliary.stream().allMatch(f -> f.bytes() >= 0)
                    && backupFiles.stream().allMatch(f -> f.bytes() >= 0);
        }
    }

    private StorageInventory() {
    }

    public static Snapshot take(Path databaseFile, Path backupFolder, Path tempDir, SpaceProbe probe) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(backupFolder, "backupFolder");
        Objects.requireNonNull(probe, "probe");
        Path db = databaseFile.toAbsolutePath().normalize();
        Path folder = backupFolder.toAbsolutePath().normalize();

        long dbBytes = regularFileSize(db);
        List<FileItem> auxiliary = new ArrayList<>();
        for (String suffix : AUXILIARY_SUFFIXES) {
            Path p = db.resolveSibling(db.getFileName() + suffix);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                auxiliary.add(new FileItem(p.getFileName().toString(), Group.UNKNOWN, regularFileSize(p), modified(p)));
            }
        }

        boolean folderExists = Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS);
        BackupManifest.Loaded manifest = BackupManifest.load(folder);
        Set<String> registered = new HashSet<>();
        if (manifest.status() == BackupManifest.Status.VALID) {
            for (BackupManifest.Entry e : manifest.entries()) {
                if (e.valid()) registered.add(e.file());
            }
        }

        List<FileItem> files = new ArrayList<>();
        String listingProblem = null;
        if (folderExists) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path p : entries) files.add(classify(p, registered));
            } catch (IOException | SecurityException e) {
                listingProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        files.sort(Comparator.comparing(FileItem::name));

        List<String> missing = new ArrayList<>();
        for (String name : registered) {
            if (!Files.isRegularFile(folder.resolve(name), LinkOption.NOFOLLOW_LINKS)) missing.add(name);
        }
        Collections.sort(missing);

        Map<Role, SpaceProbe.Space> space = new EnumMap<>(Role.class);
        space.put(Role.DATA, probe.usableSpace(db.getParent() != null ? db.getParent() : db));
        space.put(Role.BACKUPS, probe.usableSpace(folder));
        space.put(Role.TEMP, probe.usableSpace(tempDir));

        return new Snapshot(db, dbBytes, List.copyOf(auxiliary), folder, folderExists, manifest.status(),
                List.copyOf(missing), List.copyOf(files), listingProblem, Collections.unmodifiableMap(space));
    }

    private static FileItem classify(Path p, Set<String> registered) {
        String name = p.getFileName().toString();
        BasicFileAttributes a;
        try {
            a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException e) {
            return new FileItem(name, Group.UNKNOWN, -1, null);
        }
        Instant when = a.lastModifiedTime().toInstant();
        // same test the retention uses: links and junctions (reparse points) are never followed
        if (a.isSymbolicLink() || a.isOther()) return new FileItem(name, Group.LINK, -1, when);
        if (a.isDirectory()) return new FileItem(name, Group.DIRECTORY, -1, when);
        if (!a.isRegularFile()) return new FileItem(name, Group.UNKNOWN, -1, when);
        return new FileItem(name, groupOf(name, registered), a.size(), when);
    }

    private static Group groupOf(String name, Set<String> registered) {
        if (BackupNames.MANIFEST.equals(name) || MANIFEST_ASIDE.matcher(name).matches()) return Group.MANIFEST;
        if (BackupNames.kindOfCopy(name).isPresent()) {
            return registered.contains(name) ? Group.MANAGED : Group.UNREGISTERED_COPY;
        }
        if (BackupNames.isDamaged(name)) return Group.DAMAGED;
        if (BackupNames.isTemp(name) || MANIFEST_TEMP.matcher(name).matches()) return Group.TEMP;
        if (name.endsWith("-journal") && BackupNames.isTemp(name.substring(0, name.length() - "-journal".length()))) {
            return Group.TEMP;
        }
        return Group.UNKNOWN;
    }

    private static long regularFileSize(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isRegularFile() && !a.isSymbolicLink() && !a.isOther() ? a.size() : -1;
        } catch (IOException | SecurityException e) {
            return -1;
        }
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}

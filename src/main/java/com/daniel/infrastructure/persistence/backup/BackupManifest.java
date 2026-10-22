package com.daniel.infrastructure.persistence.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registry of the copies this feature created in the managed folder. Only files listed here (and
 * matching {@link BackupNames}) may ever be deleted by the application. Written atomically
 * (temp + force + atomic move). Carries no token and no financial values.
 */
final class BackupManifest {

    static final int FORMAT = 1;
    static final String IN_PROGRESS = "em-andamento";
    static final String VALID = "valida";

    /**
     * @param file          published copy (VALID) or the temp being written (IN_PROGRESS)
     * @param kind          {@link BackupKind#tag()} or {@link BackupNames#RESTORE_SOURCE_TAG}
     * @param createdAt     ISO-8601 instant
     * @param changeCounter SQLite header change counter of the source when copied
     * @param fingerprint   {@link DatabaseFingerprint#contentSha256()}
     * @param health        "ok" or the warnings shared with the source
     */
    record Entry(String file, String kind, String state, String createdAt, int schemaVersion, long changeCounter,
                 long sizeBytes, String sha256, String fingerprint, Map<String, Long> rowCounts, String health) {

        boolean valid() {
            return VALID.equals(state);
        }

        boolean inProgress() {
            return IN_PROGRESS.equals(state);
        }

        Instant created() {
            return Instant.parse(createdAt);
        }

        boolean manual() {
            return BackupKind.MANUAL.tag().equals(kind);
        }
    }

    enum Status { MISSING, VALID, INVALID }

    record Loaded(Status status, List<Entry> entries, String problem) {
    }

    private record Document(int format, List<Entry> copies) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BackupManifest() {
    }

    static Path pathIn(Path folder) {
        return folder.resolve(BackupNames.MANIFEST);
    }

    static Loaded load(Path folder) {
        Path file = pathIn(folder);
        if (!Files.exists(file)) return new Loaded(Status.MISSING, List.of(), null);
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Document doc = GSON.fromJson(json, Document.class);
            if (doc == null || doc.format() != FORMAT || doc.copies() == null) {
                return new Loaded(Status.INVALID, List.of(), "formato desconhecido");
            }
            List<Entry> out = new ArrayList<>();
            for (Entry e : doc.copies()) {
                String problem = problemOf(e);
                if (problem != null) return new Loaded(Status.INVALID, List.of(), problem);
                out.add(e);
            }
            return new Loaded(Status.VALID, List.copyOf(out), null);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return new Loaded(Status.INVALID, List.of(), "registro ilegível");
        }
    }

    private static String problemOf(Entry e) {
        if (e == null || e.file() == null || e.kind() == null || e.state() == null || e.createdAt() == null) {
            return "registro incompleto";
        }
        try {
            Instant.parse(e.createdAt());
        } catch (DateTimeParseException bad) {
            return "data inválida no registro";
        }
        if (e.valid()) {
            if (BackupNames.kindOfCopy(e.file()).map(BackupKind::tag).filter(e.kind()::equals).isEmpty()) {
                return "nome de cópia fora do padrão no registro";
            }
            return null;
        }
        if (e.inProgress()) return BackupNames.isTemp(e.file()) ? null : "temporário fora do padrão no registro";
        return "estado desconhecido no registro";
    }

    /**
     * Atomic replace: write a sibling temp with a unique name (two windows saving at once never
     * interleave in one temp), force it, then move it over the manifest.
     */
    static void save(Path folder, List<Entry> entries) throws IOException {
        Path target = pathIn(folder);
        String unique = String.format(java.util.Locale.ROOT, "%06x",
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24));
        Path tmp = folder.resolve(BackupNames.MANIFEST + "." + unique + BackupNames.PARTIAL);
        byte[] bytes = GSON.toJson(new Document(FORMAT, List.copyOf(entries))).getBytes(StandardCharsets.UTF_8);
        boolean moved = false;
        try {
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    /** Keeps an unreadable manifest under a new name; never deletes it. */
    static Path setAside(Path folder, String stamp) throws IOException {
        Path aside = folder.resolve("breakinv-copias.invalido-" + stamp + ".json");
        Files.move(pathIn(folder), aside);
        return aside;
    }
}

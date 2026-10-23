package com.daniel.infrastructure.persistence.backup;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of the files this feature creates. Anything that does not match these patterns is never
 * deleted by the application, even inside the managed folder.
 */
final class BackupNames {

    static final String MANIFEST = "breakinv-copias.json";
    static final String PARTIAL = ".partial";
    /** Marks an in-progress temp that receives a copy of a file chosen for restoration. */
    static final String RESTORE_SOURCE_TAG = "restauracao";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern COPY = Pattern.compile(
            "breakinv-(manual|diaria|pre-migracao|pre-restauracao|pre-manutencao)-\\d{8}-\\d{6}-[0-9a-f]{6}\\.db");
    private static final Pattern TEMP = Pattern.compile(
            "breakinv-(copia|restauracao)-\\d{8}-\\d{6}-[0-9a-f]{6}\\.db\\.partial");
    private static final Pattern DAMAGED = Pattern.compile("breakinv-danificado-\\d{8}-\\d{6}-[0-9a-f]{6}\\.db");
    private static final Pattern SUFFIX = Pattern.compile("[0-9a-f]{6}");

    private BackupNames() {
    }

    static String copyName(BackupKind kind, LocalDateTime when, String suffix) {
        return "breakinv-" + kind.tag() + "-" + when.format(STAMP) + "-" + requireSuffix(suffix) + ".db";
    }

    /**
     * Byte copy of damaged current data kept before a restore over them. Deliberately outside
     * {@link #kindOfCopy} and {@link #isTemp}: never registered, never deleted by the application.
     */
    static String damagedName(LocalDateTime when, String suffix) {
        return "breakinv-danificado-" + when.format(STAMP) + "-" + requireSuffix(suffix) + ".db";
    }

    /** Temp for a copy being written ({@code restoreSource=false}) or for a file chosen to restore. */
    static String tempName(boolean restoreSource, LocalDateTime when, String suffix) {
        return "breakinv-" + (restoreSource ? RESTORE_SOURCE_TAG : "copia") + "-" + when.format(STAMP) + "-"
                + requireSuffix(suffix) + ".db" + PARTIAL;
    }

    static Optional<BackupKind> kindOfCopy(String fileName) {
        Matcher m = COPY.matcher(fileName == null ? "" : fileName);
        return m.matches() ? BackupKind.fromTag(m.group(1)) : Optional.empty();
    }

    static boolean isTemp(String fileName) {
        return fileName != null && TEMP.matcher(fileName).matches();
    }

    /** A name {@link #damagedName} produces: inventoried and counted, never deleted by the application. */
    static boolean isDamaged(String fileName) {
        return fileName != null && DAMAGED.matcher(fileName).matches();
    }

    private static String requireSuffix(String suffix) {
        if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
            throw new IllegalArgumentException("suffix must be 6 lowercase hex digits");
        }
        return suffix;
    }
}

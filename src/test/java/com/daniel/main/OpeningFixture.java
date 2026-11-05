package com.daniel.main;

import com.daniel.infrastructure.persistence.backup.FileInstanceLock;
import com.daniel.infrastructure.persistence.config.Database;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests only: the throwaway databases of the U4C tests and the global state they touch. Every test works in its
 * own {@code @TempDir}; {@link #restore()} puts back the URL, the write gate, the pre-migration hook and the
 * shared connection.
 */
final class OpeningFixture {

    private final String previousUrl = Database.currentUrl();

    static String url(Path db) {
        return "jdbc:sqlite:" + db.toAbsolutePath().toString().replace("\\", "/");
    }

    /** Pre-versioning database as in {@code PreMigrationBackupTest.legacy}: it needs a migration and has rows. */
    static void legacy(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection(url(db)); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE investment_type (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, category TEXT)");
            st.execute("CREATE TABLE app_settings (key TEXT PRIMARY KEY, value TEXT)");
            st.execute("INSERT INTO investment_type (id, name, category) VALUES (7, 'Tesouro legado', 'RENDA_FIXA')");
            st.execute("INSERT INTO investment_type (id, name, category) VALUES (9, 'Ação legada', 'ACOES')");
        }
    }

    /** The lock file next to the data file is free: this JVM can hold it now (no OverlappingFileLockException). */
    static void assertInstanceLockFree(Path dataFile) throws Exception {
        Path lockFile = dataFile.resolveSibling(FileInstanceLock.FILE_NAME);
        FileInstanceLock probe = assertDoesNotThrow(() -> FileInstanceLock.holdShared(lockFile),
                "the failed attempt must have released the instance lock");
        probe.close();
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** The exception as the RF-05 evidence line shows it: class, first line of the message, SQLite codes if any. */
    static String describe(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().lines().findFirst().orElse("");
        StringBuilder out = new StringBuilder(failure.getClass().getName()).append(" | ").append(message);
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLiteException se) {
                out.append(" | SQLiteException resultCode=").append(se.getResultCode())
                        .append(" errorCode=").append(se.getErrorCode());
                break;
            }
        }
        return out.toString();
    }

    void restore() {
        Database.setWriteGate(null);
        Database.setPreMigrationHook(null);
        Database.close();
        Database.configure(previousUrl);
    }
}

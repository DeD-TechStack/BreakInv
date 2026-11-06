package com.daniel.infrastructure.persistence.backup;

import com.daniel.infrastructure.persistence.config.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;

/**
 * Child process of {@link SpaceReleaseInterruptedTest}: releases the space of the database file in {@code args[0]} and
 * writes the phase it reached, one line at a time, to the file in {@code args[1]}. The parent kills it. It touches only
 * the files it was given; the personal database is never opened.
 */
public final class SpaceReleaseChildMain {

    private SpaceReleaseChildMain() {
    }

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]);
        Path marker = Path.of(args[1]);
        Database.configure("jdbc:sqlite:" + db.toString().replace("\\", "/"));
        Database.open();
        BackupService backups = new BackupService(db, BackupService.defaultFolder(db),
                BackupService.SourceAccess.opening(Database::newConnection), Clock.systemDefaultZone(),
                RetentionPolicy.defaults());
        phase(marker, "antes");
        SpaceRelease release = new SpaceRelease(backups, InstanceGuard.SOLE, new DatabasePages(db)::read,
                conn -> {
                    mark(marker, "durante");
                    SpaceRelease.vacuum(conn);
                    mark(marker, "depois");
                },
                BackupVerifier::verify, SpaceRelease::restoreFromCopy, Database::runExclusive);
        SpaceRelease.Result result = release.release();
        phase(marker, "fim:" + result.outcome());
    }

    private static void phase(Path marker, String name) throws IOException {
        Files.writeString(marker, name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void mark(Path marker, String name) {
        try {
            phase(marker, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

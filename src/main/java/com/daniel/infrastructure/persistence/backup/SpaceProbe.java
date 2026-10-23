package com.daniel.infrastructure.persistence.backup;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Usable space of the volume that holds a path (DESENHO-BRK31 D-Q4). Injectable, so tests decide the
 * answer and never fill a real disk. A measure is a snapshot, not a reservation: another program can use
 * the space right after it, so every write still handles a real I/O error.
 */
public interface SpaceProbe {

    sealed interface Space permits Measured, Unavailable, Failed {
    }

    /**
     * What the volume API returned. Zero is a real zero, never a stand-in for "unknown".
     *
     * @param measuredAt the existing path that was asked: the target itself or its nearest existing parent
     * @param volume     identity of the volume; two measures on the same volume have equal values
     */
    record Measured(long usableBytes, Path measuredAt, Object volume) implements Space {
    }

    /** No measure exists: no path, or nothing of it exists on any volume. */
    record Unavailable(String reason) implements Space {
    }

    /** The volume API was asked and failed. */
    record Failed(String error) implements Space {
    }

    Space usableSpace(Path path);

    /** The real probe: {@link FileStore#getUsableSpace()} of the volume. */
    static SpaceProbe fileStores() {
        return FileStoreProbe.INSTANCE;
    }

    final class FileStoreProbe implements SpaceProbe {

        static final FileStoreProbe INSTANCE = new FileStoreProbe();

        private FileStoreProbe() {
        }

        @Override
        public Space usableSpace(Path path) {
            if (path == null) return new Unavailable("caminho não informado");
            Path existing = path.toAbsolutePath().normalize();
            // a folder that does not exist yet will be created on its parent's volume
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) return new Unavailable("caminho inexistente: " + path);
            try {
                FileStore store = Files.getFileStore(existing);
                long usable = store.getUsableSpace();
                if (usable < 0) return new Unavailable("o sistema não informou o espaço do volume");
                return new Measured(usable, existing, store);
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                return new Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}

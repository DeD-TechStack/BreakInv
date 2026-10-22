package com.daniel.infrastructure.persistence.backup;

import java.nio.file.Files;
import java.nio.file.Path;

/** Child process for {@link FileInstanceLockTest}: holds the shared lock until its stdin closes. */
public final class LockHolderMain {

    private LockHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        try (FileInstanceLock lock = FileInstanceLock.holdShared(Path.of(args[0]))) {
            Files.writeString(Path.of(args[1]), "pronto");
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.in.read() != -1 && System.currentTimeMillis() < deadline) {
                // wait for the parent to close stdin
            }
        }
    }
}

package com.daniel.infrastructure.persistence.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The restore proceeds only when no other BreakInv process holds the data folder. */
class FileInstanceLockTest {

    @TempDir
    Path tempDir;

    @Test
    void aloneTheInstanceCanTakeAndGiveBackExclusivity() throws Exception {
        try (FileInstanceLock mine = FileInstanceLock.holdShared(tempDir.resolve(FileInstanceLock.FILE_NAME))) {
            assertTrue(mine.tryAcquireExclusive());
            assertTrue(mine.holdsExclusive());
            mine.releaseExclusive();
            assertFalse(mine.holdsExclusive());
        }
    }

    @Test
    void anotherProcessHoldingTheFolder_preventsExclusivity_untilItCloses() throws Exception {
        Path lockFile = tempDir.resolve(FileInstanceLock.FILE_NAME);
        Path ready = tempDir.resolve("pronto");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolderMain.class.getName(), lockFile.toString(), ready.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(ready) && System.nanoTime() < deadline && child.isAlive()) Thread.sleep(50);
            assertTrue(Files.exists(ready), "child process did not take the lock");

            try (FileInstanceLock mine = FileInstanceLock.holdShared(lockFile)) {
                assertFalse(mine.tryAcquireExclusive(), "another instance is open");
                assertFalse(mine.holdsExclusive());

                child.getOutputStream().close();
                assertTrue(child.waitFor(20, TimeUnit.SECONDS));

                assertTrue(mine.tryAcquireExclusive(), "alone now");
                mine.releaseExclusive();
            }
        } finally {
            child.destroyForcibly();
        }
    }
}

package com.daniel.infrastructure.persistence.backup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Operating-system lock on a small file next to the database ({@code breakinv-instancia.lock}).
 * Every running instance holds bytes [1, end) SHARED for its whole life; a restore upgrades them to
 * EXCLUSIVE only when no other instance holds them. Byte 0 serializes the upgrade: an instance takes
 * it EXCLUSIVE before letting go of its shared bytes, so of two windows asking at once neither can
 * win while the other is open; it is held again on the way back to shared, so a window that was
 * waiting to open cannot upgrade in between. The file is empty and is never deleted (deleting it would not release
 * anyone's lock).
 */
public final class FileInstanceLock implements InstanceGuard, AutoCloseable {

    public static final String FILE_NAME = "breakinv-instancia.lock";
    private static final Logger LOG = Logger.getLogger(FileInstanceLock.class.getName());
    private static final long PROMOTION = 0;
    private static final long INSTANCES = 1;
    private static final long SPAN = Long.MAX_VALUE - INSTANCES;

    private final FileChannel channel;
    private FileLock lock;

    private FileInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Holds the shared lock (waits while another instance is restoring). */
    public static FileInstanceLock holdShared(Path lockFile) throws IOException {
        FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            return new FileInstanceLock(ch, ch.lock(INSTANCES, SPAN, true));
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    @Override
    public synchronized boolean tryAcquireExclusive() {
        FileLock promotion;
        try {
            promotion = channel.tryLock(PROMOTION, 1, false);
        } catch (OverlappingFileLockException | IOException busy) {
            promotion = null;
        }
        if (promotion == null) return false; // another window is asking right now, so it is open
        try {
            if (lock != null && lock.isValid()) lock.release();
            lock = null;
            FileLock exclusive;
            try {
                exclusive = channel.tryLock(INSTANCES, SPAN, false);
            } catch (OverlappingFileLockException | IOException busy) {
                exclusive = null;
            }
            if (exclusive != null) {
                lock = exclusive;
                return true;
            }
            lock = channel.lock(INSTANCES, SPAN, true);
            return false;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "instance lock unavailable", e);
            return false;
        } finally {
            try {
                promotion.release();
            } catch (IOException e) {
                LOG.log(Level.FINE, "promotion byte release", e);
            }
        }
    }

    @Override
    public synchronized void releaseExclusive() {
        if (lock == null || !lock.isValid() || lock.isShared()) return;
        // Byte 0 is held while going back to shared, so a window that was waiting to open cannot
        // upgrade in between and clean up what this restore still needs. tryLock, never a wait:
        // no other window can hold byte 0 while this one holds the instances exclusively.
        FileLock promotion = null;
        try {
            try {
                promotion = channel.tryLock(PROMOTION, 1, false);
            } catch (OverlappingFileLockException | IOException busy) {
                promotion = null;
            }
            lock.release();
            lock = channel.lock(INSTANCES, SPAN, true);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not return to the shared instance lock", e);
        } finally {
            if (promotion != null) {
                try {
                    promotion.release();
                } catch (IOException e) {
                    LOG.log(Level.FINE, "promotion byte release", e);
                }
            }
        }
    }

    synchronized boolean holdsExclusive() {
        return lock != null && lock.isValid() && !lock.isShared();
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }
}

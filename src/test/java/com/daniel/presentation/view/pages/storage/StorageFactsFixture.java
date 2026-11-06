package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StorageFacts.Group;
import com.daniel.presentation.view.pages.storage.StorageFacts.Need;

import java.util.List;
import java.util.function.Consumer;

/**
 * BRK-31 u5 F2: facts for the presenter tests. {@link #facts(Consumer)} starts from a healthy "normal" reading (20 MB of
 * data, 50 GB free, nothing active, every volume the same) and lets each test change only what it is about.
 */
final class StorageFactsFixture {

    private StorageFactsFixture() {
    }

    static long kb(long n) {
        return n * 1024L;
    }

    static long mb(long n) {
        return n * (1L << 20);
    }

    static long gb(long n) {
        return n * (1L << 30);
    }

    /** Every field of {@link StorageFacts}, mutable, with the normal reading as default. */
    static final class B {
        long databaseBytes = mb(20);
        long auxiliaryBytes = 0;
        Group managed = new Group(3, mb(3), 0);
        Group damaged = new Group(0, 0, 0);
        Group others = new Group(0, 0, 0);
        long knownTotalBytes = mb(23);
        boolean complete = true;
        long freeData = gb(50);
        long freeCopies = gb(50);
        long freeTemp = gb(50);
        String freeDataReason = null;
        boolean copiesOnDataDisk = true;
        boolean tempOnDataDisk = true;
        boolean warnActive = false;
        boolean reserveActive = false;
        boolean writeLimitActive = false;
        boolean warnUndetermined = false;
        boolean reserveUndetermined = false;
        boolean writeLimitUndetermined = false;
        long reserveTargetBytes = mb(104);
        long warnBytes = mb(100);
        long reserveFloorBytes = mb(64);
        long writeLimitBytes = mb(64);
        List<String> corrected = List.of();
        String postponedMessage = null;
        Need copyNeed = new Need(List.of(), null);

        StorageFacts build() {
            return new StorageFacts(databaseBytes, auxiliaryBytes, managed, damaged, others, knownTotalBytes, complete,
                    freeData, freeCopies, freeTemp, freeDataReason, copiesOnDataDisk, tempOnDataDisk, warnActive,
                    reserveActive, writeLimitActive, warnUndetermined, reserveUndetermined, writeLimitUndetermined,
                    reserveTargetBytes, warnBytes, reserveFloorBytes, writeLimitBytes, corrected, postponedMessage,
                    copyNeed);
        }
    }

    static StorageFacts facts(Consumer<B> change) {
        B b = new B();
        change.accept(b);
        return b.build();
    }

    static StorageFacts normal() {
        return facts(b -> { });
    }

    /** DESENHO §2 example 2: 20 MB of data, 90 MB free, RESERVE active (target 104 MB). */
    static StorageFacts reserve() {
        return facts(b -> {
            b.freeData = mb(90);
            b.reserveActive = true;
        });
    }

    /** DESENHO §2 example 3: 150 MB of data, 40 MB free, DB_WARNING + RESERVE + WRITE_LIMIT active. */
    static StorageFacts critical() {
        return facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = mb(40);
            b.warnActive = true;
            b.reserveActive = true;
            b.writeLimitActive = true;
        });
    }

    /** DESENHO §2 example 4: 150 MB of data, 10 GB free, only DB_WARNING active. */
    static StorageFacts dbWarning() {
        return facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = gb(10);
            b.warnActive = true;
        });
    }

    /** DESENHO §2 example 5: free space could not be measured, the warn is active. */
    static StorageFacts unmeasured() {
        return facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = -1;
            b.freeDataReason = "falha ao ler o volume";
            b.warnActive = true;
            b.reserveUndetermined = true;
            b.writeLimitUndetermined = true;
        });
    }

    /** The size of the data is unknown: warn and reserve cannot be computed. */
    static StorageFacts dbUnknown() {
        return facts(b -> {
            b.databaseBytes = -1;
            b.warnUndetermined = true;
            b.reserveUndetermined = true;
        });
    }
}

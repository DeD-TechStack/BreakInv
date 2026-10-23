package com.daniel.infrastructure.persistence.backup;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The three levels of DESENHO-BRK31 §5.1 on the data volume. A level that needs a measure that is missing is
 * reported as undetermined, never as inactive: an unknown free space does not mean there is room. The general
 * reserve is a reference only; each operation checks its own estimate (§5.2).
 */
public final class StoragePolicy {

    public enum Level {
        /** D ≥ the warning size (inclusive). */
        DB_WARNING,
        /** L < 2 × D + the reserve floor (strict). */
        RESERVE,
        /** L < the write limit (strict). */
        WRITE_LIMIT
    }

    /**
     * @param reserveTargetBytes 2 × D + floor, saturated at {@link Long#MAX_VALUE}; -1 when D is unknown
     * @param freeBytes          the measured free space; -1 when there is no measure
     */
    public record Evaluation(Set<Level> active, Set<Level> undetermined, long reserveTargetBytes, long freeBytes,
                             StorageLimits limits) {

        public boolean is(Level level) {
            return active.contains(level);
        }

        /** Bytes that must be freed to leave an active space level; 0 when it is inactive or undetermined. */
        public long shortfall(Level level) {
            if (!is(level) || freeBytes < 0) return 0;
            return switch (level) {
                case RESERVE -> reserveTargetBytes - freeBytes;
                case WRITE_LIMIT -> limits.writeLimitBytes() - freeBytes;
                case DB_WARNING -> 0;
            };
        }
    }

    private StoragePolicy() {
    }

    /**
     * @param dbBytes   D, the main database file only; negative when unknown
     * @param dataSpace the free space of the volume that holds the database
     */
    public static Evaluation evaluate(long dbBytes, SpaceProbe.Space dataSpace, StorageLimits limits) {
        Set<Level> active = EnumSet.noneOf(Level.class);
        Set<Level> undetermined = EnumSet.noneOf(Level.class);
        long free = dataSpace instanceof SpaceProbe.Measured m ? m.usableBytes() : -1;
        long target = dbBytes < 0 ? -1 : reserveTarget(dbBytes, limits.reserveFloorBytes());

        if (dbBytes < 0) undetermined.add(Level.DB_WARNING);
        else if (dbBytes >= limits.warnDbBytes()) active.add(Level.DB_WARNING);

        if (free < 0 || target < 0) undetermined.add(Level.RESERVE);
        else if (free < target) active.add(Level.RESERVE);

        if (free < 0) undetermined.add(Level.WRITE_LIMIT);
        else if (free < limits.writeLimitBytes()) active.add(Level.WRITE_LIMIT);

        return new Evaluation(Collections.unmodifiableSet(active), Collections.unmodifiableSet(undetermined), target,
                free, limits);
    }

    /** 2 × D + floor, saturated instead of overflowing. */
    static long reserveTarget(long dbBytes, long floor) {
        if (dbBytes > (Long.MAX_VALUE - floor) / 2) return Long.MAX_VALUE;
        return 2 * dbBytes + floor;
    }
}

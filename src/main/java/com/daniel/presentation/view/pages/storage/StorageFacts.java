package com.daniel.presentation.view.pages.storage;

import java.util.List;
import java.util.Set;

/**
 * BRK-31 u5 F2: the values the "Dados e armazenamento" section shows, and nothing else. Plain values only ({@code long},
 * {@code int}, {@code boolean}, {@code String}, lists of them and the types of this record); no type of the
 * {@code backup} package, no JavaFX. The screen (F3) converts a {@code StorageReport.Reading} into one of these, from a
 * single snapshot.
 *
 * <p>{@code -1} in any byte field means "not measured"; the texts show it as "—".</p>
 *
 * @param databaseBytes          D, the size of {@code breakinv.db}
 * @param auxiliaryBytes         J, the temporary files of the database
 * @param managed                the copies the BreakInv keeps
 * @param damaged                the copies that could not be verified
 * @param others                 other files in the copies folder (unregistered copy, unknown, link, directory)
 * @param knownTotalBytes        the total of the data folder, as far as it could be read
 * @param complete               false when some size could not be read, so the total is a minimum
 * @param freeData               L, free space of the volume of the data, or -1
 * @param freeCopies             free space of the volume of the copies folder, or -1
 * @param freeTemp               free space of the volume of the temporary files, or -1
 * @param freeDataReason         why {@code freeData} has no measure, or {@code null}
 * @param copiesOnDataDisk       the copies folder is on the same volume as the data
 * @param tempOnDataDisk         the temporary files are on the same volume as the data
 * @param warnActive             the database size passed the warning limit
 * @param reserveActive          free space is below the restore reserve
 * @param writeLimitActive       free space is below the write limit
 * @param warnUndetermined       the warning could not be evaluated
 * @param reserveUndetermined    the reserve could not be evaluated
 * @param writeLimitUndetermined the write limit could not be evaluated
 * @param reserveTargetBytes     what the reserve asks for (2 × D + floor)
 * @param warnBytes              the warning limit in effect
 * @param reserveFloorBytes      the reserve margin in effect
 * @param writeLimitBytes        the write limit in effect
 * @param corrected              the saved limits that were invalid and went back to the default (each a full sentence)
 * @param postponedMessage       why the last automatic copy was postponed, or {@code null} when it was not
 * @param copyNeed               what a new copy needs, per volume
 */
public record StorageFacts(
        long databaseBytes,
        long auxiliaryBytes,
        Group managed,
        Group damaged,
        Group others,
        long knownTotalBytes,
        boolean complete,
        long freeData,
        long freeCopies,
        long freeTemp,
        String freeDataReason,
        boolean copiesOnDataDisk,
        boolean tempOnDataDisk,
        boolean warnActive,
        boolean reserveActive,
        boolean writeLimitActive,
        boolean warnUndetermined,
        boolean reserveUndetermined,
        boolean writeLimitUndetermined,
        long reserveTargetBytes,
        long warnBytes,
        long reserveFloorBytes,
        long writeLimitBytes,
        List<String> corrected,
        String postponedMessage,
        Need copyNeed) {

    public StorageFacts {
        if (managed == null) managed = new Group(0, 0, 0);
        if (damaged == null) damaged = new Group(0, 0, 0);
        if (others == null) others = new Group(0, 0, 0);
        corrected = corrected == null ? List.of() : List.copyOf(corrected);
        if (copyNeed == null) copyNeed = new Need(List.of(), null);
    }

    /**
     * A group of files of the copies folder.
     *
     * @param files        how many files
     * @param knownBytes   the sum of the sizes that could be read
     * @param unknownSizes how many files had no readable size
     */
    public record Group(int files, long knownBytes, int unknownSizes) {
    }

    /** The volumes of {@link NeedLine#disks()}: the places a space estimate puts on a volume. */
    public enum Disk { DATA, COPIES, TEMP, DESTINATION }

    /**
     * What an operation needs, one line per volume.
     *
     * @param lines         the volumes, in the order to show them
     * @param unknownReason why no estimate could be made (used only when there is no line), or {@code null}
     */
    public record Need(List<NeedLine> lines, String unknownReason) {
        public Need {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * One volume of a {@link Need}.
     *
     * @param disks            the places of the estimate that are on this volume
     * @param neededBytes      the peak the operation needs there
     * @param freeBytes        what is free there, or -1
     * @param unmeasuredReason why the free space has no measure, or {@code null}
     */
    public record NeedLine(Set<Disk> disks, long neededBytes, long freeBytes, String unmeasuredReason) {
        public NeedLine {
            disks = disks == null ? Set.of() : Set.copyOf(disks);
        }
    }
}

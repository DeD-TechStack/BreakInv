package com.daniel.infrastructure.persistence.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reading of the data space that the screen shows (BRK-31 u5 F1): the inventory, the levels, the limits in
 * force, the need of a verified copy and whether the automatic copy was postponed. It only joins what the classes
 * of today already compute, in one measure of the volumes per reading, and decides nothing. A measure that fails
 * does not throw: the volume comes back as "sem medida" with its reason. Any other exception goes up to the caller.
 */
public final class StorageReport {

    /**
     * @param snapshot          the inventory, with the measure of each volume taken for this reading
     * @param evaluation        the levels, with D and the free space of the data volume of the same snapshot
     * @param limits            the limits in force
     * @param corrected         what was missing or invalid in the preferences and fell back to the default
     * @param copyNeed          the need of a verified copy into the copies folder, as the BackupService asks it
     * @param postponedMessage  why the last automatic copy was postponed; null when it was not (no date: the
     *                          postponed result carries none)
     */
    public record Reading(StorageInventory.Snapshot snapshot, StoragePolicy.Evaluation evaluation,
                          StorageLimits limits, List<String> corrected, StorageGuard.Need copyNeed,
                          boolean automaticPostponed, String postponedMessage) {
    }

    private final Path databaseFile;
    private final Path backupFolder;
    private final Path tempFolder;
    private final SpaceProbe probe;
    private final StoragePreferences preferences;
    private final BackupService backups;
    /** Used only for {@link StorageGuard#need}, which reads and decides nothing. */
    private final StorageGuard guard;

    public StorageReport(Path databaseFile, Path backupFolder, Path tempFolder, SpaceProbe probe,
                         StoragePreferences preferences, BackupService backups) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.backupFolder = backupFolder.toAbsolutePath().normalize();
        this.tempFolder = tempFolder;
        this.probe = probe;
        this.preferences = preferences;
        this.backups = backups;
        this.guard = new StorageGuard(databaseFile, tempFolder, probe, () -> preferences.load().limits(),
                System::nanoTime);
    }

    public Reading read() {
        StorageInventory.Snapshot snapshot = StorageInventory.take(databaseFile, backupFolder, tempFolder, probe);
        StoragePreferences.Loaded loaded = preferences.load();
        SpaceProbe.Space dataSpace = snapshot.space(StorageInventory.Role.DATA);
        StoragePolicy.Evaluation evaluation =
                StoragePolicy.evaluate(snapshot.databaseBytes(), dataSpace, loaded.limits());
        // the same places the BackupService passes when it creates a copy in its folder
        StorageGuard.Need copyNeed = guard.need(
                SpaceEstimate.verifiedCopy(snapshot.databaseBytes(), SpaceEstimate.Place.COPIES),
                Map.of(SpaceEstimate.Place.DATA, databaseFile.getParent(), SpaceEstimate.Place.COPIES, backupFolder));
        Optional<BackupService.Result> last = backups.lastAutomatic();
        boolean postponed = last.isPresent() && last.get().outcome() == BackupService.Outcome.POSTPONED;
        return new Reading(snapshot, evaluation, loaded.limits(), loaded.corrected(), copyNeed, postponed,
                postponed ? last.get().message() : null);
    }

    /**
     * What a restore needs per volume, with the same estimate and the same places as the restore itself
     * ({@code RestoreService.run} and {@code spaceRefusal}). A staged file or data file that cannot be read has size
     * -1, which the estimates answer with "sem estimativa".
     */
    public StorageGuard.Need restoreNeed(RestoreService.Preview preview, boolean overDamaged) {
        long c = size(preview.staged());
        long d = size(databaseFile);
        SpaceEstimate.Estimate estimate = overDamaged
                ? SpaceEstimate.restoreOverDamaged(c, d, true)
                : SpaceEstimate.restore(c, d, true, false, preview.olderVersion());
        return guard.need(estimate,
                Map.of(SpaceEstimate.Place.DATA, databaseFile.getParent(), SpaceEstimate.Place.COPIES, backupFolder));
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}

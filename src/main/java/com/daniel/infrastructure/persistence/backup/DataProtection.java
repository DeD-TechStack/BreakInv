package com.daniel.infrastructure.persistence.backup;

import java.nio.file.Path;

/** What the screen may ask about copies and restoration. Every call may block: run it off the FX thread. */
public interface DataProtection {

    BackupService.Status status();

    BackupService.Result createManual();

    BackupService.Result createIn(Path folder);

    RestoreService.Prepared prepareRestore(Path chosen);

    RestoreService.Result restore(RestoreService.Preview preview);

    /** Only after {@link RestoreService.Outcome#CURRENT_DAMAGED} and the user's second confirmation. */
    RestoreService.Result restoreOverDamaged(RestoreService.Preview preview);

    void discard(RestoreService.Preview preview);

    /** BRK-31 u5: the reading of the data space. Only {@link #of(BackupService, RestoreService, StorageReport, StoragePreferences)} implements it. */
    default StorageReport.Reading storage() {
        throw new UnsupportedOperationException("storage() is not wired");
    }

    /** BRK-31 u5: saves the limits; the result of {@link StoragePreferences#save} unchanged. */
    default StoragePreferences.SaveResult saveLimits(StorageLimits limits) {
        throw new UnsupportedOperationException("saveLimits() is not wired");
    }

    /** BRK-31 u5: a manual copy, after the user confirmed that it may run without a measure of the free space. */
    default BackupService.Result createManual(boolean confirmedWithoutMeasure) {
        throw new UnsupportedOperationException("createManual(boolean) is not wired");
    }

    /** BRK-31 u5: a copy in another folder, after the same confirmation. */
    default BackupService.Result createIn(Path folder, boolean confirmedWithoutMeasure) {
        throw new UnsupportedOperationException("createIn(Path, boolean) is not wired");
    }

    /** BRK-31 u5: what a restore needs per volume, with the choice of the estimate the restore itself makes. */
    default StorageGuard.Need restoreNeed(RestoreService.Preview preview, boolean overDamaged) {
        throw new UnsupportedOperationException("restoreNeed() is not wired");
    }

    /**
     * Wires the real services for copies and restoration only: the data-space methods of BRK-31 u5 are not wired and
     * throw {@link UnsupportedOperationException}.
     */
    static DataProtection of(BackupService backups, RestoreService restore) {
        return new DataProtection() {
            @Override
            public BackupService.Status status() {
                return backups.status();
            }

            @Override
            public BackupService.Result createManual() {
                return backups.createManual();
            }

            @Override
            public BackupService.Result createIn(Path folder) {
                return backups.createIn(folder);
            }

            @Override
            public RestoreService.Prepared prepareRestore(Path chosen) {
                return restore.prepare(chosen);
            }

            @Override
            public RestoreService.Result restore(RestoreService.Preview preview) {
                return restore.restore(preview);
            }

            @Override
            public RestoreService.Result restoreOverDamaged(RestoreService.Preview preview) {
                return restore.restoreOverDamaged(preview);
            }

            @Override
            public void discard(RestoreService.Preview preview) {
                restore.discard(preview);
            }
        };
    }

    /** Wires the real services, including the data-space reading and the limits (BRK-31 u5). */
    static DataProtection of(BackupService backups, RestoreService restore, StorageReport report,
                             StoragePreferences preferences) {
        return new DataProtection() {
            @Override
            public BackupService.Status status() {
                return backups.status();
            }

            @Override
            public BackupService.Result createManual() {
                return backups.createManual();
            }

            @Override
            public BackupService.Result createIn(Path folder) {
                return backups.createIn(folder);
            }

            @Override
            public RestoreService.Prepared prepareRestore(Path chosen) {
                return restore.prepare(chosen);
            }

            @Override
            public RestoreService.Result restore(RestoreService.Preview preview) {
                return restore.restore(preview);
            }

            @Override
            public RestoreService.Result restoreOverDamaged(RestoreService.Preview preview) {
                return restore.restoreOverDamaged(preview);
            }

            @Override
            public void discard(RestoreService.Preview preview) {
                restore.discard(preview);
            }

            @Override
            public StorageReport.Reading storage() {
                return report.read();
            }

            @Override
            public StoragePreferences.SaveResult saveLimits(StorageLimits limits) {
                return preferences.save(limits);
            }

            @Override
            public BackupService.Result createManual(boolean confirmedWithoutMeasure) {
                return backups.createManual(confirmedWithoutMeasure);
            }

            @Override
            public BackupService.Result createIn(Path folder, boolean confirmedWithoutMeasure) {
                return backups.createIn(folder, confirmedWithoutMeasure);
            }

            @Override
            public StorageGuard.Need restoreNeed(RestoreService.Preview preview, boolean overDamaged) {
                return report.restoreNeed(preview, overDamaged);
            }
        };
    }
}

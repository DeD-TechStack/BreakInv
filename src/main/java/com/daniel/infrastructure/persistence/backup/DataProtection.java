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

    /** Wires the real services. */
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
}

package com.daniel.main;

import com.daniel.infrastructure.config.AppConfig;
import com.daniel.infrastructure.persistence.config.Database;

import java.sql.Connection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One attempt to open the data (BRK-31 u4c, U4C:RF-01): everything the program does before the main window to reach
 * the database, that is, building the {@link AppConfig} (which takes the instance lock, installs the copy before the
 * migration and the write gate, and opens the connection). A failure of the attempt is an outcome, not an exception.
 */
class DataOpening {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    /** The outcome of one attempt: opened ({@code config}) or failed ({@code failure}). */
    record Attempt(AppConfig config, RuntimeException failure) {

        static Attempt opened(AppConfig config) {
            return new Attempt(config, null);
        }

        static Attempt failed(RuntimeException failure) {
            return new Attempt(null, failure);
        }

        boolean opened() {
            return config != null;
        }

        /** The connection of this attempt. */
        Connection connection() {
            return config.getConnection();
        }

        /** Lets go of what an attempt that opened holds, when nobody is going to use it: connection and lock. */
        void release() {
            if (config == null) return;
            Database.close();
            config.close();
        }
    }

    private final Database.PreMigrationHook beforeMigration;

    /** The real attempt: the copy before the migration is the real one. */
    DataOpening() {
        this(null);
    }

    /** @param beforeMigration only for tests: the action before the migration in place of the real copy (null: real) */
    DataOpening(Database.PreMigrationHook beforeMigration) {
        this.beforeMigration = beforeMigration;
    }

    /**
     * Runs one attempt. A {@link RuntimeException} is a failure of the opening: it is logged and returned, after the
     * attempt let go of what it took. An {@link Error} is not caught.
     */
    Attempt attempt() {
        try {
            return Attempt.opened(new AppConfig(beforeMigration));
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "data opening failed", failure);
            return Attempt.failed(failure);
        }
    }
}

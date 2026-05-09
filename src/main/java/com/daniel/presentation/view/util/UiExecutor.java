package com.daniel.presentation.view.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-threaded daemon executor for all UI background tasks.
 * Single-threaded ensures at most one background task accesses the shared SQLite
 * connection at a time, preventing concurrent JDBC use on the same Connection object.
 */
public final class UiExecutor {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ui-bg");
        t.setDaemon(true);
        return t;
    });

    private UiExecutor() {}

    public static ExecutorService get() {
        return EXEC;
    }

    public static void shutdown() {
        EXEC.shutdownNow();
    }
}

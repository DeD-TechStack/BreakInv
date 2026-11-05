package com.daniel.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Tests only: collects what one logger writes while it is open; {@link #close()} removes its handler. */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    public LogCapture(Class<?> owner) {
        this(owner.getName());
    }

    public LogCapture(String loggerName) {
        this.logger = Logger.getLogger(loggerName);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    public List<LogRecord> records() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    /** The records of this level whose message is exactly {@code message}. */
    public List<LogRecord> of(Level level, String message) {
        return records().stream().filter(r -> r.getLevel() == level && message.equals(r.getMessage())).toList();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}

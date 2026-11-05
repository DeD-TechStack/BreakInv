package com.daniel.presentation.view.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U4C:RF-04: the notice of a failed daily snapshot, the same text and the same log for the three callers. */
class DailySnapshotNoticeTest {

    @Test
    void messageNamesTheReasonInParentheses() {
        assertEquals("Não foi possível registrar o valor da carteira de hoje (limite de gravação atingido). "
                        + "O BreakInv tenta de novo na próxima alteração da carteira ou na próxima abertura de hoje.",
                DailySnapshotNotice.message(new IllegalStateException("limite de gravação atingido.")));
    }

    @Test
    void messageWithoutTextSaysUnexpectedError() {
        assertEquals("Não foi possível registrar o valor da carteira de hoje (erro inesperado). "
                        + "O BreakInv tenta de novo na próxima alteração da carteira ou na próxima abertura de hoje.",
                DailySnapshotNotice.message(new IllegalStateException()));
    }

    @Test
    void reportLogsAWarningWithTheFailureAndShowsTheNoticeOnTheUiExecutor() {
        Logger log = Logger.getLogger("test.daily.snapshot.notice");
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        log.addHandler(handler);
        Deque<Runnable> ui = new ArrayDeque<>();
        List<String> shown = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("sem espaço");
        try {
            DailySnapshotNotice.report(log, failure, ui::add, shown::add);
        } finally {
            log.removeHandler(handler);
        }

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals("daily snapshot failed", records.get(0).getMessage());
        assertSame(failure, records.get(0).getThrown());
        assertTrue(shown.isEmpty(), "the notice is shown by the ui executor, not by the caller's thread");
        assertEquals(1, ui.size());
        ui.poll().run();
        assertEquals(List.of(DailySnapshotNotice.message(failure)), shown);
    }
}

package com.daniel.infrastructure.persistence.config;

import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.infrastructure.persistence.backup.SqliteErrors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4B:RF-01 on {@link Database#inTransaction}, with failures the SQLite really gives, on a disposable file in the
 * test's temp folder: the state of the undoing is read from the exception that is thrown, and that exception is the
 * one of always. The cases that cannot be produced for real (ROLLBACK answering BUSY or IOERR, a closing that fails)
 * are in {@link RollbackDecisionTest} and in {@link #closingFailureAfterTheCommit_isLoggedAndTheResultIsReturned}.
 */
class DatabaseRollbackStateTest {

    @TempDir
    Path temp;

    Path file;

    static String url(Path p) {
        return "jdbc:sqlite:" + p.toString().replace("\\", "/");
    }

    @BeforeEach
    void setUp() {
        file = temp.resolve("estado.db");
        Database.configure(url(file));
        Database.open();
    }

    @AfterEach
    void tearDown() {
        Database.setWriteGate(null);
        Database.close();
        Database.configure(null);
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private boolean tableExists(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(file)); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='" + name + "'")) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }

    private static RollbackState stateOf(Throwable thrown) {
        Optional<RollbackState> state = Database.rollbackStateOf(thrown);
        assertTrue(state.isPresent(), "the failure of a ledger write has a state: " + thrown);
        return state.get();
    }

    private static boolean suppressedMentions(Throwable thrown, String text) {
        return Arrays.stream(thrown.getSuppressed()).anyMatch(s -> String.valueOf(s.getMessage()).contains(text));
    }

    @Test
    void success_returnsTheResultOfTheBody_andCommits() throws Exception {
        Integer result = Database.inTransaction(conn -> {
            exec(conn, "CREATE TABLE gravado(v INTEGER)");
            return 7;
        });
        assertEquals(7, result);
        assertTrue(tableExists("gravado"));
    }

    // ---- rule 2: the ROLLBACK ends without error

    @Test
    void ledgerExceptionInTheBody_isTheSameObject_andConfirmed() throws Exception {
        LedgerException refusal = new LedgerException("amount", "valor inválido");
        LedgerException thrown = assertThrows(LedgerException.class, () -> Database.inTransaction(conn -> {
            exec(conn, "CREATE TABLE meio(v INTEGER)");
            throw refusal;
        }));
        assertSame(refusal, thrown);
        assertEquals("valor inválido", thrown.getMessage());
        assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
        assertEquals(Optional.of(2), Database.rollbackRuleOf(thrown));
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(tableExists("meio"));
    }

    @Test
    void checkedExceptionInTheBody_isWrappedAsAlways_andConfirmed() throws Exception {
        IOException cause = new IOException("falhou");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            throw cause;
        }));
        assertEquals(RuntimeException.class, thrown.getClass());
        assertEquals("falhou", thrown.getMessage());
        assertSame(cause, thrown.getCause());
        assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
    }

    @Test
    void sqliteFullInTheBody_isSpace_andConfirmed_withNothingLeft() throws Exception {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE enchendo(v BLOB)");
                long pages;
                try (ResultSet rs = st.executeQuery("PRAGMA page_count")) {
                    rs.next();
                    pages = rs.getLong(1);
                }
                st.execute("PRAGMA max_page_count = " + pages);
                for (int i = 0; i < 1000; i++) st.execute("INSERT INTO enchendo(v) VALUES (zeroblob(8192))");
            }
            return null;
        }));
        assertEquals(SqliteErrors.Category.SPACE, SqliteErrors.ofChain(thrown));
        assertTrue(thrown.getCause() instanceof SQLException);
        assertEquals(thrown.getCause().getMessage(), thrown.getMessage());
        assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
        assertFalse(tableExists("enchendo"));
    }

    @Test
    void commitRefusedByADeferredForeignKey_rollbackOk_isConfirmed_withNothingLeft() throws Exception {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            exec(conn, "CREATE TABLE pai(id INTEGER PRIMARY KEY)");
            exec(conn, "CREATE TABLE filho(pid INTEGER REFERENCES pai(id))");
            exec(conn, "PRAGMA defer_foreign_keys = ON");
            exec(conn, "INSERT INTO filho(pid) VALUES (1)");
            return null;
        }));
        assertTrue(String.valueOf(thrown.getMessage()).toLowerCase().contains("foreign key"), thrown.getMessage());
        assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
        assertEquals(Optional.of(2), Database.rollbackRuleOf(thrown));
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(tableExists("pai"));
        assertFalse(tableExists("filho"));
    }

    // ---- rule 3: "no transaction is active"

    @Test
    void noTransactionActive_afterAFailureInTheBody_isConfirmed_andTheAnswerStaysSuppressed() throws Exception {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            exec(conn, "CREATE TABLE desfeito(v INTEGER)");
            exec(conn, "ROLLBACK");
            throw new IllegalStateException("depois do rollback do próprio corpo");
        }));
        assertEquals(IllegalStateException.class, thrown.getClass());
        assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
        assertEquals(Optional.of(3), Database.rollbackRuleOf(thrown));
        assertTrue(suppressedMentions(thrown, "no transaction is active"), Arrays.toString(thrown.getSuppressed()));
        assertFalse(tableExists("desfeito"));
    }

    @Test
    void noTransactionActive_afterAFailureInTheCommit_isUnconfirmed() throws Exception {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            exec(conn, "CREATE TABLE talvez(v INTEGER)");
            exec(conn, "COMMIT"); // the body ends the transaction itself: the commit of the driver then fails
            return null;
        }));
        assertTrue(String.valueOf(thrown.getMessage()).contains("no transaction is active"), thrown.getMessage());
        assertEquals(RollbackState.UNCONFIRMED, stateOf(thrown));
        assertEquals(Optional.of(3), Database.rollbackRuleOf(thrown));
        assertTrue(suppressedMentions(thrown, "no transaction is active"), Arrays.toString(thrown.getSuppressed()));
        assertTrue(tableExists("talvez"), "the commit that failed had in fact written");
    }

    // ---- rule 4: any other result of the ROLLBACK

    @Test
    void unusableConnection_afterAFailureInTheBody_isUnconfirmed_andTheExceptionIsTheSame() {
        LedgerException refusal = new LedgerException("amount", "valor inválido");
        LedgerException thrown = assertThrows(LedgerException.class, () -> Database.inTransaction(conn -> {
            conn.close();
            throw refusal;
        }));
        assertSame(refusal, thrown);
        assertEquals(RollbackState.UNCONFIRMED, stateOf(thrown));
        assertEquals(Optional.of(4), Database.rollbackRuleOf(thrown));
        assertEquals(1, thrown.getSuppressed().length);
    }

    @Test
    void unusableConnection_afterAFailureInTheCommit_isUnconfirmed() {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            conn.close();
            return null;
        }));
        assertEquals(RollbackState.UNCONFIRMED, stateOf(thrown));
        assertEquals(1, thrown.getSuppressed().length);
    }

    // ---- rule 1: the body never started

    @Test
    void busyInTheBegin_isConfirmed_thebodyDoesNotRun_andTheTextIsTheOneOfAlways() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        try (Connection other = DriverManager.getConnection(url(file)); Statement st = other.createStatement()) {
            st.execute("BEGIN EXCLUSIVE");
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
                    ran.set(true);
                    return null;
                }));
                assertFalse(ran.get());
                assertTrue(thrown.getMessage().startsWith("Erro de transação: "), thrown.getMessage());
                assertTrue(thrown.getCause() instanceof SQLException);
                assertEquals(SqliteErrors.Category.BUSY, SqliteErrors.ofChain(thrown));
                assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
                assertEquals(Optional.of(1), Database.rollbackRuleOf(thrown));
                assertEquals(0, thrown.getSuppressed().length, "the ROLLBACK is not executed");
            } finally {
                st.execute("ROLLBACK");
            }
        }
    }

    @Test
    void failureOpeningTheConnection_isConfirmed_thebodyDoesNotRun_andTheTextIsTheOneOfAlways() {
        Database.configure(url(temp.resolve("nao-existe").resolve("x.db")));
        AtomicBoolean ran = new AtomicBoolean();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(conn -> {
            ran.set(true);
            return null;
        }));
        assertFalse(ran.get());
        assertTrue(thrown.getMessage().startsWith("Erro ao conectar ao banco: "), thrown.getMessage());
        assertEquals(RollbackState.CONFIRMED, stateOf(thrown));
    }

    @Test
    void refusalOfTheGate_hasNoState_andKeepsTheTextOfAlways() {
        Database.setWriteGate(() -> "recusado pelo portão");
        AtomicBoolean ran = new AtomicBoolean();
        StorageRefusedException thrown = assertThrows(StorageRefusedException.class, () -> Database.inTransaction(conn -> {
            ran.set(true);
            return null;
        }));
        assertFalse(ran.get());
        assertEquals("recusado pelo portão", thrown.getMessage());
        assertEquals(Optional.empty(), Database.rollbackStateOf(thrown));
    }

    // ---- rule 5: only the closing failed

    @Test
    void closingFailureAfterTheCommit_isLoggedAndTheResultIsReturned() {
        Logger log = Logger.getLogger(Database.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        log.addHandler(handler);
        try {
            SQLException closing = new SQLException("falha ao fechar");
            String result = assertDoesNotThrow(() -> Database.committed("gravado", closing));
            assertEquals("gravado", result);
            assertEquals(1, records.size(), records.toString());
            assertEquals(Level.WARNING, records.get(0).getLevel());
            assertSame(closing, records.get(0).getThrown());
            records.clear();
            assertEquals("sem falha", assertDoesNotThrow(() -> Database.committed("sem falha", null)));
            assertTrue(records.isEmpty());
        } finally {
            log.removeHandler(handler);
        }
    }

    // ---- reading the state

    @Test
    void theStateIsReadThroughTheWrappersOfTheCall() {
        LedgerException refusal = new LedgerException("amount", "valor inválido");
        LedgerException thrown = assertThrows(LedgerException.class, () -> Database.inTransaction(conn -> {
            throw refusal;
        }));
        assertEquals(Optional.of(RollbackState.CONFIRMED), Database.rollbackStateOf(new CompletionException(thrown)));
        assertEquals(Optional.of(RollbackState.CONFIRMED), Database.rollbackStateOf(new RuntimeException("x", thrown)));
    }

    @Test
    void anExceptionOfOtherOrigin_hasNoState() {
        assertEquals(Optional.empty(), Database.rollbackStateOf(new RuntimeException("x", new IOException("y"))));
        assertEquals(Optional.empty(), Database.rollbackStateOf(null));
    }
}

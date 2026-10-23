package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.Movement;
import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.infrastructure.persistence.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRK-36, unit 2: {@link DatabaseLedgerAccess#read} runs the ledger and the registrations on one
 * connection, in one DEFERRED read transaction, with {@code query_only}. On a temp file (not
 * {@code :memory:}), so the locks are SQLite's real file locks in journal mode DELETE.
 */
class DatabaseLedgerAccessReadTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @TempDir
    Path tempDir;
    DatabaseLedgerAccess access;
    long investmentId;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + tempDir.resolve("leitura.db").toString().replace("\\", "/"));
        Database.open();
        access = new DatabaseLedgerAccess();
        DailyTrackingUseCase uc = new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(),
                new SnapshotRepository(), new TransactionRepository(), ticker -> null, access);
        uc.movement(MovementRequest.deposit("dep", DAY, 100_000L, null));
        investmentId = uc.createInvestment(new DailyTrackingUseCase.NewInvestment("ATIVO", "ACOES", "ALTA", DAY, null,
                        null, "ACAO", null, null, "ATV1", null, null, null),
                DailyTrackingUseCase.OpeningSpec.zero("open", DAY, PositionMode.QUANTITY));
    }

    @AfterEach
    void tearDown() {
        Database.close();
    }

    /** A second, independent writer that gives up after 200 ms instead of the app's 5 s. */
    static Connection impatientWriter() throws SQLException {
        Connection w = Database.newConnection();
        try (Statement st = w.createStatement()) {
            st.execute("PRAGMA busy_timeout = 200");
        }
        return w;
    }

    static void insertRegistration(Connection w, String name) throws SQLException {
        try (Statement st = w.createStatement()) {
            st.executeUpdate("INSERT INTO investment_type (name) VALUES ('" + name + "')");
        }
    }

    static int primaryCode(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) return e.getErrorCode() & 0xFF;
        }
        return -1;
    }

    static int registrations() throws SQLException {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM investment_type")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * (1) Between the SELECTs of one read nothing can be committed: a writer that tries during the read
     * gets SQLITE_BUSY, the read keeps seeing one state, and the same commit passes right after it.
     */
    @Test
    void commitDuringTheRead_waitsForIt_thenPasses() throws Exception {
        int before = registrations();
        try (Connection w = impatientWriter()) {
            List<Integer> seen = access.read((repo, regs) -> {
                int types = regs.listAll().size();
                List<Movement> movements = repo.listAll();
                SQLException refused = assertThrows(SQLException.class, () -> insertRegistration(w, "ESCRITOR"),
                        "a commit cannot land in the middle of a read");
                assertEquals(5, primaryCode(refused), "SQLITE_BUSY");
                int infos = repo.listInvestments(true).size();
                return List.of(types, movements.size(), infos, regs.listAll().size());
            });
            assertEquals(before, seen.get(0));
            assertEquals(seen.get(0), seen.get(2), "registrations and ledger infos from the same state");
            assertEquals(seen.get(0), seen.get(3), "same registrations at the start and at the end of the read");

            long t0 = System.nanoTime();
            insertRegistration(w, "ESCRITOR");
            assertTrue(System.nanoTime() - t0 < 150_000_000L, "no lock left after the read");
        }
        assertEquals(before + 1, registrations());
    }

    /**
     * Not BEGIN IMMEDIATE: a read starts and finishes while another connection holds the write lock
     * (RESERVED) with an uncommitted row, and it does not see that row. The writer commits afterwards.
     */
    @Test
    void readDoesNotTakeTheWriteLock() throws Exception {
        int before = registrations();
        try (Connection w = impatientWriter()) {
            w.setAutoCommit(false);
            insertRegistration(w, "PENDENTE");
            long t0 = System.nanoTime();
            // a read that asked for the write lock would wait the 5 s busy timeout and fail with SQLITE_BUSY
            int seen = assertDoesNotThrow(() -> access.read((repo, regs) -> {
                repo.listAll();
                return regs.listAll().size();
            }), "a read must neither take nor wait for the write lock");
            long elapsed = System.nanoTime() - t0;
            assertEquals(before, seen, "uncommitted rows are not visible");
            assertTrue(elapsed < 2_000_000_000L, "the read did not wait for the writer (" + elapsed / 1_000_000 + " ms)");
            w.commit();
        }
        assertEquals(before + 1, registrations());
    }

    /** (2) A failure inside the work rolls back, closes, keeps the original exception and leaves no lock. */
    @Test
    void failureInsideTheWork_isRethrown_andLeavesNoLock() throws Exception {
        IllegalStateException original = new IllegalStateException("falha de teste dentro da leitura");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> access.read((repo, regs) -> {
            repo.listAll();
            regs.listAll();
            throw original;
        }));
        assertSame(original, thrown);
        try (Connection w = impatientWriter()) {
            long t0 = System.nanoTime();
            insertRegistration(w, "DEPOIS DA FALHA");
            assertTrue(System.nanoTime() - t0 < 150_000_000L, "no lock left after a failed read");
        }
    }

    /** (3) Nothing can be written through the read: query_only refuses it (SQLITE_READONLY) and no row lands. */
    @Test
    void writeThroughTheRead_isRefused() throws Exception {
        int before = registrations();
        access.read((repo, regs) -> {
            RuntimeException viaRegistrations = assertThrows(RuntimeException.class, () -> regs.save("NAO GRAVA"));
            assertEquals(8, primaryCode(viaRegistrations), "SQLITE_READONLY");
            RuntimeException viaLedger = assertThrows(RuntimeException.class,
                    () -> repo.setNote(investmentId, "nao grava"));
            assertEquals(8, primaryCode(viaLedger), "SQLITE_READONLY");
            return null;
        });
        assertEquals(before, registrations());
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT note FROM investment_type WHERE id = " + investmentId)) {
            assertTrue(rs.next());
            assertNull(rs.getString(1));
        }
    }
}

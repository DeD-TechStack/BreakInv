package com.daniel.infrastructure.persistence.repository;

import com.daniel.core.domain.ledger.MovementRequest;
import com.daniel.core.domain.ledger.PortfolioState;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.core.service.DailyTrackingUseCase;
import com.daniel.core.service.PortfolioService;
import com.daniel.infrastructure.persistence.backup.SqliteErrors;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.RollbackState;
import com.daniel.infrastructure.persistence.repository.LedgerWriteOracle.Financial;
import com.daniel.presentation.view.util.WriteFailureText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import static com.daniel.infrastructure.persistence.repository.LedgerWriteOracle.beforePlusOperation;
import static com.daniel.infrastructure.persistence.repository.LedgerWriteOracle.count;
import static com.daniel.infrastructure.persistence.repository.LedgerWriteOracle.equalToBefore;
import static com.daniel.infrastructure.persistence.repository.LedgerWriteOracle.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * U4B:RF-04: no write is left half done, with failures the SQLite really gives, on a disposable file in the test's
 * temp folder (never the personal database). Each injection prints a line {@code U4B-RF04 ...} with the real code
 * ({@code getResultCode()} extended name and {@code getErrorCode()}), the rule of U4B:RF-01 that decided, the state
 * of the undoing and, in the first, the number k of deposits written before the failure.
 *
 * <p>The whole operation is described beforehand and apart from the path that failed: the new rows column by column
 * (written by hand from the rule of the domain, except {@code payload_hash}, which comes from a twin database made
 * with the same base and the same operation without injection) and the {@link PortfolioState} that follows from it.
 */
class LedgerWriteFailureInjectionTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    static final LocalDate NEXT_DAY = LocalDate.of(2026, 10, 2);

    @TempDir
    Path tempDir;

    Path file;
    String url;
    DailyTrackingUseCase uc;
    DatabaseLedgerAccess access;

    static String urlOf(Path p) {
        return "jdbc:sqlite:" + p.toString().replace("\\", "/");
    }

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("injecao.db");
        url = urlOf(file);
        Database.configure(url);
        Database.open();
        access = new DatabaseLedgerAccess();
        uc = new DailyTrackingUseCase(new FlowRepository(), new InvestmentTypeRepository(), new SnapshotRepository(),
                new TransactionRepository(), ticker -> null, access);
        buildBase();
    }

    /** 2 investments opened at zero on 01/10/2026, the cash opened and 5 movements, on the configured database. */
    private void buildBase() {
        int a = uc.createInvestment(new DailyTrackingUseCase.NewInvestment("ATIVO A", "ACOES", "ALTA", DAY, null, null,
                        "ACAO", null, null, "ATVA1", null, null, null),
                DailyTrackingUseCase.OpeningSpec.zero("abertura-a", DAY, PositionMode.QUANTITY));
        int b = uc.createInvestment(new DailyTrackingUseCase.NewInvestment("ATIVO B", "ACOES", "ALTA", DAY, null, null,
                        "ACAO", null, null, "ATVB1", null, null, null),
                DailyTrackingUseCase.OpeningSpec.zero("abertura-b", DAY, PositionMode.QUANTITY));
        uc.movement(MovementRequest.deposit("base-aporte", DAY, 100_000L, null));
        uc.movement(MovementRequest.buy("base-compra-a", DAY, a, 10, new BigDecimal("10.00"), null));
        uc.movement(MovementRequest.buy("base-compra-b", DAY, b, 5, new BigDecimal("20.00"), null));
        uc.movement(MovementRequest.sell("base-venda-a", DAY, a, 4, new BigDecimal("12.00"), null));
        uc.movement(MovementRequest.withdrawal("base-retirada", DAY, 1_000L, null));
    }

    @AfterEach
    void tearDown() {
        Database.close();
        Database.configure(null);
    }

    // ---- the operation expected, apart from the path that failed

    /**
     * The new rows of deposits {@code keys} of {@code cents} each on {@code date}: every column written by hand,
     * except {@code payload_hash}, taken from a twin database (another file, same base, same deposits, no
     * injection). Leaves the main database configured again.
     */
    private List<Map<String, Object>> expectedDeposits(LocalDate date, long cents, List<String> keys) throws Exception {
        Database.configure(urlOf(tempDir.resolve("gemea-" + System.nanoTime() + ".db")));
        Database.open();
        buildBase();
        for (String key : keys) uc.movement(MovementRequest.deposit(key, date, cents, null));
        Financial twin = read();
        Database.close();
        Database.configure(url);
        List<Map<String, Object>> expected = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> row = twin.movements().stream().filter(r -> key.equals(r.get("intent_key")))
                    .findFirst().orElseThrow(() -> new AssertionError("twin without " + key));
            expected.add(LedgerWriteOracle.deposit(key, (String) row.get("payload_hash"), date, cents));
        }
        return expected;
    }

    private static long total(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(r -> (Long) r.get("amount_cents")).sum();
    }

    private String integrityCheck() throws SQLException {
        try (Connection c = Database.newConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** {@code Database.close()}, {@code Database.configure(<same URL>)} and a new reading. */
    private void restart() {
        Database.close();
        Database.configure(url);
    }

    // ---- what each injection must keep

    private static SQLiteException driverFailure(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException se) return se;
        }
        return null;
    }

    private static String observed(int item, Throwable thrown, String extra) {
        if (thrown == null) return "U4B-RF04 item=" + item + " sem falha " + extra;
        SQLiteException se = driverFailure(thrown);
        String code = se == null ? "-" : (se.getResultCode() == null ? "?" : se.getResultCode().name());
        String errorCode = se == null ? "-" : String.valueOf(se.getErrorCode());
        return "U4B-RF04 item=" + item + " resultCode=" + code + " errorCode=" + errorCode
                + " regra=" + Database.rollbackRuleOf(thrown).map(String::valueOf).orElse("-")
                + " estado=" + Database.rollbackStateOf(thrown).map(Enum::name).orElse("sem estado")
                + " " + extra;
    }

    /**
     * The invariants of U4B:RF-04 after the failure {@code thrown} of the operation {@code expectedNew}
     * ({@code afterFailure} is the reading right after it). Then the restart (equal to before, or before plus the
     * whole operation, never in between), the integrity check and the repetition of {@code repeat}, the operation
     * with the key {@code repeatKey} and no injection, judged on the whole state.
     */
    private void checkInvariants(Financial before, Throwable thrown, Financial afterFailure,
                                 List<Map<String, Object>> expectedNew, String repeatKey, Callable<?> repeat) throws Exception {
        Optional<RollbackState> state = thrown == null ? Optional.empty() : Database.rollbackStateOf(thrown);
        if (thrown != null) assertTrue(state.isPresent(), "a failure of a ledger write has a state");
        boolean confirmed = state.isPresent() && state.get() == RollbackState.CONFIRMED;
        if (confirmed) assertTrue(equalToBefore(before, afterFailure), "CONFIRMED: equal to before right after the failure");

        PortfolioState stateWithOperation = LedgerWriteOracle.withDeposits(before.state(), total(expectedNew));
        restart();
        Financial afterRestart = read();
        if (confirmed) {
            assertTrue(equalToBefore(before, afterRestart), "CONFIRMED: equal to before after the restart");
        } else {
            assertTrue(equalToBefore(before, afterRestart)
                            || beforePlusOperation(before, afterRestart, expectedNew, stateWithOperation),
                    "after the restart: equal to before or before plus the whole operation, never in between");
        }
        assertEquals("ok", integrityCheck());

        boolean recorded = count(afterRestart, repeatKey) > 0;
        repeat.call();
        Financial afterRepeat = read();
        assertEquals(1, count(afterRepeat, repeatKey), "the operation repeated with the same key is written once");
        if (recorded) {
            assertTrue(equalToBefore(afterRestart, afterRepeat), "already recorded: the repetition changes nothing");
        } else {
            List<Map<String, Object>> repeated = expectedNew.stream()
                    .filter(r -> repeatKey.equals(r.get("intent_key"))).toList();
            assertEquals(1, repeated.size());
            PortfolioState expectedState = LedgerWriteOracle.withDeposits(afterRestart.state(), total(repeated));
            assertTrue(beforePlusOperation(afterRestart, afterRepeat, repeated, expectedState),
                    "not recorded: after the restart plus the repeated operation; expected rows " + repeated
                            + " state " + expectedState + "; read state " + afterRepeat.state() + " last row "
                            + afterRepeat.movements().get(afterRepeat.movements().size() - 1));
        }
        assertEquals("ok", integrityCheck());
    }

    // ---- 1. SPACE in the middle of one transaction

    @Test
    void injection1_spaceInTheMiddleOfOneTransaction_isConfirmed_andNothingStays() throws Exception {
        Financial before = read();
        int[] k = {0};
        Throwable thrown = null;
        try {
            Database.inTransaction(conn -> {
                long pages;
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA page_count")) {
                    rs.next();
                    pages = rs.getLong(1);
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA max_page_count = " + pages);
                }
                PortfolioService service = new PortfolioService(new DatabaseLedgerAccess.ParticipatingStore(conn));
                for (int i = 1; i <= 10_000; i++) {
                    service.executeMovement(MovementRequest.deposit("aporte-" + i, NEXT_DAY, 100L, null));
                    k[0]++;
                }
                throw new AssertionError("no SQLITE_FULL in 10000 deposits");
            });
        } catch (RuntimeException failure) {
            thrown = failure;
        }
        assertNotNull(thrown, "the injection must fail");
        System.out.println(observed(1, thrown, "k=" + k[0]));
        assertTrue(k[0] >= 1, "at least one deposit written before the failure");
        assertEquals(SqliteErrors.Category.SPACE, SqliteErrors.ofChain(thrown));
        assertEquals(Optional.of(RollbackState.CONFIRMED), Database.rollbackStateOf(thrown));

        Financial afterFailure = read();
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= k[0]; i++) keys.add("aporte-" + i);
        List<Map<String, Object>> expected = expectedDeposits(NEXT_DAY, 100L, keys);
        checkInvariants(before, thrown, afterFailure, expected, "aporte-1",
                () -> uc.movement(MovementRequest.deposit("aporte-1", NEXT_DAY, 100L, null)));
    }

    // ---- 2. a journal that cannot be created

    @Test
    void injection2_journalThatCannotBeCreated_isConfirmed_andNothingStays() throws Exception {
        Financial before = read();
        Path journal = tempDir.resolve(file.getFileName() + "-journal");
        Files.createDirectory(journal);
        Throwable thrown = null;
        try {
            uc.movement(MovementRequest.deposit("jornal-1", NEXT_DAY, 500L, null));
        } catch (RuntimeException failure) {
            thrown = failure;
        } finally {
            Files.deleteIfExists(journal); // the folder leaves before the state is read
        }
        assertNotNull(thrown, "the injection must fail");
        System.out.println(observed(2, thrown, ""));
        assertEquals(Optional.of(RollbackState.CONFIRMED), Database.rollbackStateOf(thrown));
        assertTrue(Database.rollbackRuleOf(thrown).orElse(0) <= 3);

        Financial afterFailure = read();
        List<Map<String, Object>> expected = expectedDeposits(NEXT_DAY, 500L, List.of("jornal-1"));
        checkInvariants(before, thrown, afterFailure, expected, "jornal-1",
                () -> uc.movement(MovementRequest.deposit("jornal-1", NEXT_DAY, 500L, null)));
    }

    // ---- 3. a journal held open at the commit (Windows only)

    @Test
    void injection3_journalHeldOpenAtTheCommit_keepsTheInvariants_whateverTheState() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "an open file cannot be deleted only on Windows");
        Financial before = read();
        Path journal = tempDir.resolve(file.getFileName() + "-journal");
        FileInputStream[] held = new FileInputStream[1];
        Throwable thrown = null;
        Financial afterFailure;
        Optional<RollbackState> state;
        try {
            try {
                access.transact(service -> {
                    service.executeMovement(MovementRequest.deposit("preso-1", NEXT_DAY, 700L, null));
                    try {
                        held[0] = new FileInputStream(journal.toFile());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                thrown = failure;
            }
            state = thrown == null ? Optional.empty() : Database.rollbackStateOf(thrown); // read before the file is closed
        } finally {
            if (held[0] != null) held[0].close();
        }
        afterFailure = read();
        System.out.println(observed(3, thrown, ""));
        if (thrown != null) {
            assertTrue(state.isPresent());
            Optional<String> text = WriteFailureText.compose(WriteFailureText.Screen.MOVEMENT_DIALOG, thrown);
            assertTrue(text.isPresent());
            if (state.get() == RollbackState.CONFIRMED) {
                assertTrue(text.get().endsWith(". Nada foi gravado."), text.get());
            } else {
                assertTrue(text.get().endsWith("confira o extrato antes de repetir."), text.get());
            }
            System.out.println("U4B-RF04 item=3 texto=" + text.get());
        }
        List<Map<String, Object>> expected = expectedDeposits(NEXT_DAY, 700L, List.of("preso-1"));
        checkInvariants(before, thrown, afterFailure, expected, "preso-1",
                () -> uc.movement(MovementRequest.deposit("preso-1", NEXT_DAY, 700L, null)));
    }
}

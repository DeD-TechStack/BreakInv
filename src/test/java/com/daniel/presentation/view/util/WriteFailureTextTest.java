package com.daniel.presentation.view.util;

import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.RollbackState;
import com.daniel.presentation.view.util.WriteFailureText.Screen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4B:RF-03: the text of each screen by state and kind of failure, with no JavaFX. The failures come out of a real
 * {@link Database#inTransaction} on a disposable file: a body that throws gives {@code CONFIRMED}; a body that
 * closes its own connection before throwing makes the ROLLBACK fail and gives {@code UNCONFIRMED}.
 */
class WriteFailureTextTest {

    // literals on purpose: the test must not read the texts from the code it checks
    private static final String NOTHING_WRITTEN = ". Nada foi gravado.";
    private static final String NOT_CONFIRMED =
            ". Não foi possível confirmar se a gravação foi desfeita; confira o extrato antes de repetir.";

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        Database.configure("jdbc:sqlite:" + temp.resolve("texto.db").toString().replace("\\", "/"));
        Database.open();
    }

    @AfterEach
    void tearDown() {
        Database.close();
        Database.configure(null);
    }

    /** The exception {@code inTransaction} throws for a body that throws {@code failure}; the undoing is confirmed. */
    private static Throwable confirmed(Exception failure) {
        Throwable thrown = fail(failure, false);
        assertEquals(Optional.of(RollbackState.CONFIRMED), Database.rollbackStateOf(thrown));
        return thrown;
    }

    /** Same, with the connection closed by the body: the ROLLBACK fails and the undoing is not confirmed. */
    private static Throwable unconfirmed(Exception failure) {
        Throwable thrown = fail(failure, true);
        assertEquals(Optional.of(RollbackState.UNCONFIRMED), Database.rollbackStateOf(thrown));
        return thrown;
    }

    private static Throwable fail(Exception failure, boolean closeFirst) {
        try {
            Database.inTransaction(conn -> {
                if (closeFirst) conn.close();
                throw failure;
            });
        } catch (RuntimeException thrown) {
            return thrown;
        }
        throw new AssertionError("the body did not fail");
    }

    private static SQLiteException driver(SQLiteErrorCode code, String message) {
        return new SQLiteException("[" + code.name() + "] " + message, code);
    }

    private static String text(Screen screen, Throwable cause) {
        return WriteFailureText.compose(screen, cause).orElseThrow(() -> new AssertionError("expected a composed text"));
    }

    // ---- the motive by category, on each screen

    private static Map<SQLiteErrorCode, String> reasons() {
        Map<SQLiteErrorCode, String> reasons = new LinkedHashMap<>();
        reasons.put(SQLiteErrorCode.SQLITE_FULL, "disco cheio");
        reasons.put(SQLiteErrorCode.SQLITE_IOERR, "erro de leitura ou gravação no disco");
        reasons.put(SQLiteErrorCode.SQLITE_CANTOPEN, "arquivo de dados inacessível");
        reasons.put(SQLiteErrorCode.SQLITE_READONLY, "sem permissão de gravação no arquivo de dados");
        reasons.put(SQLiteErrorCode.SQLITE_BUSY, "dados em uso por outra operação");
        reasons.put(SQLiteErrorCode.SQLITE_CORRUPT, "arquivo de dados danificado");
        return reasons;
    }

    @Test
    void confirmed_eachCategory_showsItsReasonAndNothingWasWritten_onEachScreen() {
        reasons().forEach((code, reason) -> {
            Throwable cause = confirmed(driver(code, "mensagem do driver"));
            assertEquals("Falha ao gravar: " + reason + NOTHING_WRITTEN, text(Screen.MOVEMENT_DIALOG, cause), code.name());
            assertEquals("Falha ao gravar: " + reason + NOTHING_WRITTEN, text(Screen.LEDGER_RECOGNITION_DIALOG, cause), code.name());
            assertEquals("Erro: " + reason + NOTHING_WRITTEN, text(Screen.INVESTMENT_TYPES_PAGE, cause), code.name());
        });
    }

    @Test
    void unconfirmed_eachCategory_showsItsReasonAndAsksToCheck_onEachScreen() {
        reasons().forEach((code, reason) -> {
            Throwable cause = unconfirmed(driver(code, "mensagem do driver"));
            assertEquals("Falha ao gravar: " + reason + NOT_CONFIRMED, text(Screen.MOVEMENT_DIALOG, cause), code.name());
            assertEquals("Falha ao gravar: " + reason + NOT_CONFIRMED, text(Screen.LEDGER_RECOGNITION_DIALOG, cause), code.name());
            assertEquals("Erro: " + reason + NOT_CONFIRMED, text(Screen.INVESTMENT_TYPES_PAGE, cause), code.name());
        });
    }

    // ---- OTHER: the message of today, with the final stop handled

    @Test
    void other_usesTheMessageOfToday_withoutDoubleFinalStop() {
        Throwable plain = confirmed(new IllegalStateException("cadastro sem id gerado"));
        assertEquals("Falha ao gravar: cadastro sem id gerado" + NOTHING_WRITTEN, text(Screen.MOVEMENT_DIALOG, plain));

        Throwable stop = confirmed(new IllegalStateException("cadastro sem id gerado."));
        assertEquals("Falha ao gravar: cadastro sem id gerado" + NOTHING_WRITTEN, text(Screen.MOVEMENT_DIALOG, stop));

        Throwable spaces = unconfirmed(new IllegalStateException("cadastro sem id gerado.  \t"));
        assertEquals("Erro: cadastro sem id gerado" + NOT_CONFIRMED, text(Screen.INVESTMENT_TYPES_PAGE, spaces));
        assertTrue(text(Screen.INVESTMENT_TYPES_PAGE, spaces).indexOf("..") < 0);
    }

    @Test
    void other_severalLinesStayWhole_andANullMessageShowsAsToday() {
        Throwable lines = confirmed(new IllegalStateException("linha 1\nlinha 2"));
        assertEquals("Falha ao gravar: linha 1\nlinha 2" + NOTHING_WRITTEN, text(Screen.LEDGER_RECOGNITION_DIALOG, lines));

        Throwable nullMessage = confirmed(new IllegalStateException((String) null));
        assertEquals("Falha ao gravar: null" + NOTHING_WRITTEN, text(Screen.MOVEMENT_DIALOG, nullMessage));
    }

    // ---- LedgerException

    @Test
    void ledgerException_confirmed_keepsTheTextOfToday_onEveryScreen() {
        Throwable refusal = confirmed(new LedgerException("amount", "valor inválido"));
        for (Screen screen : Screen.values()) {
            assertEquals(Optional.empty(), WriteFailureText.compose(screen, refusal), screen.name());
        }
        assertEquals("Operação recusada (amount): valor inválido", WriteFailureText.investmentTypes(refusal, false));
    }

    @Test
    void ledgerException_unconfirmed_usesThePrefixOfTheScreen_andItsOwnMessage() {
        Throwable refusal = unconfirmed(new LedgerException("amount", "valor inválido"));
        assertEquals("Falha ao gravar: valor inválido" + NOT_CONFIRMED, text(Screen.MOVEMENT_DIALOG, refusal));
        assertEquals("Falha ao gravar: valor inválido" + NOT_CONFIRMED, text(Screen.LEDGER_RECOGNITION_DIALOG, refusal));
        assertEquals("Erro: valor inválido" + NOT_CONFIRMED, text(Screen.INVESTMENT_TYPES_PAGE, refusal));
        assertEquals("Erro: valor inválido" + NOT_CONFIRMED, WriteFailureText.investmentTypes(refusal, false));
    }

    // ---- no state: the text of today

    @Test
    void failureWithoutState_keepsTheTextOfToday() {
        Throwable noState = new IllegalStateException("falha de leitura");
        for (Screen screen : Screen.values()) {
            assertEquals(Optional.empty(), WriteFailureText.compose(screen, noState), screen.name());
        }
        assertEquals("Erro: falha de leitura", WriteFailureText.investmentTypes(noState, false));
        assertEquals("Operação recusada (history): sem histórico",
                WriteFailureText.investmentTypes(new LedgerException("history", "sem histórico"), false));
    }

    // ---- the edition of an investment keeps the text of today in any failure

    @Test
    void edition_twoPaths_keepsTheTextOfToday_whateverTheState() {
        Throwable full = confirmed(driver(SQLiteErrorCode.SQLITE_FULL, "database or disk is full"));
        assertEquals("Erro: " + full.getMessage(), WriteFailureText.investmentTypes(full, true));

        Throwable full2 = unconfirmed(driver(SQLiteErrorCode.SQLITE_FULL, "database or disk is full"));
        assertEquals("Erro: " + full2.getMessage(), WriteFailureText.investmentTypes(full2, true));

        Throwable refusal = unconfirmed(new LedgerException("name", "nome obrigatório"));
        assertEquals("Operação recusada (name): nome obrigatório", WriteFailureText.investmentTypes(refusal, true));

        Throwable refusalConfirmed = confirmed(new LedgerException("name", "nome obrigatório"));
        assertEquals("Operação recusada (name): nome obrigatório", WriteFailureText.investmentTypes(refusalConfirmed, true));
    }

    @Test
    void investmentTypes_otherOperations_composeTheStateText() {
        Throwable full = confirmed(driver(SQLiteErrorCode.SQLITE_FULL, "database or disk is full"));
        assertEquals("Erro: disco cheio" + NOTHING_WRITTEN, WriteFailureText.investmentTypes(full, false));
    }
}

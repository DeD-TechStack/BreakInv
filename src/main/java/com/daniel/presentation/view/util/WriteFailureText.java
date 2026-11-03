package com.daniel.presentation.view.util;

import com.daniel.core.domain.ledger.LedgerException;
import com.daniel.infrastructure.persistence.backup.WriteFailureReason;
import com.daniel.infrastructure.persistence.config.Database;
import com.daniel.infrastructure.persistence.config.RollbackState;

import java.util.Optional;

/**
 * The text a screen shows when a ledger write fails (BRK-31 u4b, U4B:RF-03): {@code <prefix><body><suffix>}.
 * "Nada foi gravado." appears only when the undoing was confirmed; otherwise the user is told to check
 * before repeating. No JavaFX here, so every screen's text is testable alone.
 */
public final class WriteFailureText {

    /** The screens that show the failure of a ledger write, each with its prefix. */
    public enum Screen {
        MOVEMENT_DIALOG("Falha ao gravar: "),
        LEDGER_RECOGNITION_DIALOG("Falha ao gravar: "),
        INVESTMENT_TYPES_PAGE("Erro: ");

        private final String prefix;

        Screen(String prefix) {
            this.prefix = prefix;
        }

        String prefix() {
            return prefix;
        }
    }

    static final String CONFIRMED_SUFFIX = ". Nada foi gravado.";
    static final String UNCONFIRMED_SUFFIX = ". Não foi possível confirmar se a gravação foi desfeita; confira o extrato antes de repetir.";

    private WriteFailureText() {
    }

    /**
     * The composed text of {@code cause} (already unwrapped from a {@code CompletionException}) on
     * {@code screen}, or empty when the screen keeps the text it showed before: the failure has no state, or it
     * is a {@link LedgerException} whose undoing was confirmed (a refusal of the domain, shown as always).
     */
    public static Optional<String> compose(Screen screen, Throwable cause) {
        Optional<RollbackState> state = Database.rollbackStateOf(cause);
        if (state.isEmpty()) return Optional.empty();
        boolean refusal = cause instanceof LedgerException;
        if (refusal && state.get() == RollbackState.CONFIRMED) return Optional.empty();
        String today = String.valueOf(cause.getMessage());
        String body = refusal ? today : WriteFailureReason.of(cause, today);
        String suffix = state.get() == RollbackState.CONFIRMED ? CONFIRMED_SUFFIX : UNCONFIRMED_SUFFIX;
        return Optional.of(screen.prefix() + withoutFinalStop(body) + suffix);
    }

    /** The body loses its trailing spaces and then one final full stop, so the suffix never makes "..". */
    private static String withoutFinalStop(String body) {
        String s = body.stripTrailing();
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The text of {@code InvestmentTypesPage.runMutation}. {@code twoPaths} is the edition of an investment,
     * which writes by two paths: "Nada foi gravado." could be false there, so it keeps the text it had.
     */
    public static String investmentTypes(Throwable cause, boolean twoPaths) {
        if (!twoPaths) {
            Optional<String> composed = compose(Screen.INVESTMENT_TYPES_PAGE, cause);
            if (composed.isPresent()) return composed.get();
        }
        if (cause instanceof LedgerException le) return "Operação recusada (" + le.field() + "): " + le.getMessage();
        return "Erro: " + cause.getMessage();
    }
}

package com.daniel.presentation.view.components;

import com.daniel.core.domain.ledger.OpeningRequest;
import com.daniel.core.domain.ledger.PositionMode;
import com.daniel.presentation.view.FxStubs;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.viewmodel.MovementForm;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dialogs speak the user's language: no "ledger", "replay", "schema", no intention keys or hex
 * fragments on screen. The financial meaning (opening is not a deposit, review clears a pending
 * flag, a reversal is linked) stays explicit.
 */
class DialogVocabularyTest {

    static final Pattern HEX8 = Pattern.compile("\\b[0-9a-f]{8}\\b");

    static List<Node> flatten(Node n) {
        List<Node> out = new ArrayList<>();
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(flatten(c));
        if (n instanceof ScrollPane sp && sp.getContent() != null) out.addAll(flatten(sp.getContent()));
        if (n instanceof TabPane tp) for (Tab t : tp.getTabs()) { out.add(new javafx.scene.control.Label(t.getText())); if (t.getContent() != null) out.addAll(flatten(t.getContent())); }
        return out;
    }

    static List<String> texts(Dialog<?> d) {
        List<String> texts = new ArrayList<>();
        for (Node n : flatten(d.getDialogPane())) {
            if (n instanceof Labeled l && l.getText() != null) texts.add(l.getText());
            if (n instanceof Control c && c.getTooltip() != null) texts.add(c.getTooltip().getText());
        }
        return texts;
    }

    static void assertUserVocabulary(List<String> texts) {
        List<String> bad = texts.stream().filter(t -> {
            String s = t.toLowerCase();
            return s.contains("ledger") || s.contains("replay") || s.contains("schema") || s.contains("intenção") || HEX8.matcher(s).find();
        }).toList();
        assertTrue(bad.isEmpty(), bad.toString());
    }

    @Test
    void movementDialog_noIntentKeyOrLedgerOnScreen_butTheOneClickOneEntryPromiseStays() {
        FxTestSupport.runAndWait(() -> {
            MovementDialog d = new MovementDialog("Aporte externo", "Dinheiro que entra de fora.", MovementForm.deposit(),
                    FxStubs.emptyDaily(), m -> {}, Runnable::run, Runnable::run);
            List<String> t = texts(d);
            assertUserVocabulary(t);
            assertTrue(t.stream().anyMatch(s -> s.toLowerCase().contains("não duplica")), t.toString());
        });
    }

    @Test
    void recognitionDialog_explainsTheMilestoneWithoutLedger_andKeepsTheRisk() {
        FxTestSupport.runAndWait(() -> {
            OpeningRequest proposal = OpeningRequest.cash("k", LocalDate.now(), 80_000L, true, "saldo manual anterior");
            LedgerRecognitionDialog d = LedgerRecognitionDialog.forTest(LedgerRecognitionDialog.Mode.OPENING, null, "Caixa",
                    null, proposal, FxStubs.emptyDaily(), Runnable::run, Runnable::run, () -> {});
            List<String> t = texts(d);
            assertUserVocabulary(t);
            assertTrue(t.stream().anyMatch(s -> s.contains("não é aporte nem lucro")), "risk/meaning kept: " + t);
            assertTrue(t.stream().anyMatch(s -> s.toLowerCase().contains("pendente")), "pending flag still announced: " + t);
            assertTrue(t.stream().anyMatch(s -> s.contains("Reconhecer saldo")), t.toString());
            LedgerRecognitionDialog r = LedgerRecognitionDialog.forTest(LedgerRecognitionDialog.Mode.RECONCILIATION, 7L, "Ativo",
                    PositionMode.QUANTITY, OpeningRequest.existingPosition("k2", LocalDate.now(), 7L, PositionMode.QUANTITY, 6L, 12_000L, false, null),
                    FxStubs.emptyDaily(), Runnable::run, Runnable::run, () -> {});
            assertUserVocabulary(texts(r));
            assertTrue(texts(r).stream().anyMatch(s -> s.contains("Revisar saldo")), texts(r).toString());
        });
    }

    @Test
    void investmentTypeDialog_openingTab_speaksOfInitialBalance_notLedger() {
        FxTestSupport.runAndWait(() -> {
            InvestmentTypeDialog d = new InvestmentTypeDialog("Novo Investimento", null);
            List<String> t = texts(d);
            assertUserVocabulary(t);
            assertTrue(t.stream().anyMatch(s -> s.contains("Saldo inicial")), t.toString());
            assertTrue(t.stream().anyMatch(s -> s.contains("não é aporte nem lucro")), "meaning kept: " + t);
        });
    }
}

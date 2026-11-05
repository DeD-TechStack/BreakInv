package com.daniel.presentation.view;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U4C:RF-02: the content of the failure window, built without showing any window. */
class OpeningFailurePaneTest {

    private static final Path FILE = Path.of("pasta de dados", "breakinv.db").toAbsolutePath();
    private static final Path COPIES = Path.of("pasta de dados", "backups").toAbsolutePath();

    private static String text(OpeningFailurePane pane, String id) {
        Node node = pane.lookup("#" + id);
        assertNotNull(node, id);
        return ((Label) node).getText();
    }

    private static Button button(OpeningFailurePane pane, String id) {
        Node node = pane.lookup("#" + id);
        assertNotNull(node, id);
        return (Button) node;
    }

    @Test
    void showsTheFixedTextsTheReasonTheFileAndTheCopiesFolder() {
        FxTestSupport.runAndWait(() -> {
            OpeningFailurePane pane = new OpeningFailurePane("arquivo de dados inacessível", FILE, COPIES);

            assertEquals("Não foi possível abrir os dados do BreakInv", text(pane, "opening-failure-title"));
            assertEquals("Motivo: arquivo de dados inacessível.", text(pane, "opening-failure-reason"));
            assertEquals("Arquivo de dados: " + FILE, text(pane, "opening-failure-file"));
            assertEquals("Cópias de segurança: " + COPIES, text(pane, "opening-failure-copies"));
            assertEquals("Os dados não foram apagados nem substituídos. Corrija o motivo e tente de novo.",
                    text(pane, "opening-failure-guidance"));
            assertEquals("Tentar de novo", button(pane, "opening-failure-retry").getText());
            assertEquals("Fechar", button(pane, "opening-failure-close").getText());
            assertEquals(520, pane.getPrefWidth());
        });
    }

    @Test
    void retryIsTheDefaultButtonAndCloseIsTheCancelButton() {
        FxTestSupport.runAndWait(() -> {
            OpeningFailurePane pane = new OpeningFailurePane("x", FILE, COPIES);

            assertTrue(button(pane, "opening-failure-retry").isDefaultButton());
            assertFalse(button(pane, "opening-failure-close").isDefaultButton());
            assertTrue(button(pane, "opening-failure-close").isCancelButton());
            assertFalse(button(pane, "opening-failure-retry").isCancelButton());
        });
    }

    @Test
    void theButtonsCallTheirActions() {
        FxTestSupport.runAndWait(() -> {
            OpeningFailurePane pane = new OpeningFailurePane("x", FILE, COPIES);
            AtomicInteger retries = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            pane.setOnRetry(retries::incrementAndGet);
            pane.setOnClose(closes::incrementAndGet);

            button(pane, "opening-failure-retry").fire();
            assertEquals(1, retries.get());
            assertEquals(0, closes.get());
            button(pane, "opening-failure-close").fire();
            assertEquals(1, retries.get());
            assertEquals(1, closes.get());
        });
    }

    @Test
    void theReasonGetsAFinalDotOnlyWhenItDoesNotEndInPunctuation() {
        FxTestSupport.runAndWait(() -> {
            assertEquals("Motivo: disco cheio.", text(new OpeningFailurePane("disco cheio", FILE, COPIES), "opening-failure-reason"));
            assertEquals("Motivo: disco cheio.", text(new OpeningFailurePane("disco cheio.", FILE, COPIES), "opening-failure-reason"));
            assertEquals("Motivo: disco cheio!", text(new OpeningFailurePane("disco cheio!", FILE, COPIES), "opening-failure-reason"));
            assertEquals("Motivo: disco cheio?", text(new OpeningFailurePane("disco cheio?", FILE, COPIES), "opening-failure-reason"));
            assertEquals("Motivo: disco cheio…", text(new OpeningFailurePane("disco cheio…", FILE, COPIES), "opening-failure-reason"));
        });
    }

    @Test
    void aRelativePathIsShownAbsolute() {
        FxTestSupport.runAndWait(() -> {
            Path relativeFile = Path.of("dados", "breakinv.db");
            Path relativeCopies = Path.of("dados", "backups");
            OpeningFailurePane pane = new OpeningFailurePane("x", relativeFile, relativeCopies);

            assertEquals("Arquivo de dados: " + relativeFile.toAbsolutePath(), text(pane, "opening-failure-file"));
            assertEquals("Cópias de segurança: " + relativeCopies.toAbsolutePath(), text(pane, "opening-failure-copies"));
            assertTrue(text(pane, "opening-failure-file").contains(relativeFile.toAbsolutePath().getParent().toString()));
        });
    }

    @Test
    void withoutADataFileTheLineSaysNotDefinedAndThereIsNoCopiesLine() {
        FxTestSupport.runAndWait(() -> {
            OpeningFailurePane pane = new OpeningFailurePane("o banco de dados precisa de um arquivo", null, null);

            assertEquals("Arquivo de dados: (não definido)", text(pane, "opening-failure-file"));
            assertNull(pane.lookup("#opening-failure-copies"));
            assertEquals("Motivo: o banco de dados precisa de um arquivo.", text(pane, "opening-failure-reason"));
            assertEquals("Os dados não foram apagados nem substituídos. Corrija o motivo e tente de novo.",
                    text(pane, "opening-failure-guidance"));
            assertEquals("Tentar de novo", button(pane, "opening-failure-retry").getText());
            assertEquals("Fechar", button(pane, "opening-failure-close").getText());
        });
    }

    @Test
    void duringAnAttemptTheReasonLineSaysOpeningAndOnlyRetryIsDisabled() {
        FxTestSupport.runAndWait(() -> {
            OpeningFailurePane pane = new OpeningFailurePane("x", FILE, COPIES);
            assertFalse(button(pane, "opening-failure-retry").isDisable());

            pane.showTrying();

            assertEquals("Abrindo os dados…", text(pane, "opening-failure-reason"));
            assertTrue(button(pane, "opening-failure-retry").isDisable());
            assertFalse(button(pane, "opening-failure-close").isDisable());
            assertEquals("Arquivo de dados: " + FILE, text(pane, "opening-failure-file"));
        });
    }

    @Test
    void aNewFailureReplacesTheReasonAndEnablesRetryAgain() {
        FxTestSupport.runAndWait(() -> {
            OpeningFailurePane pane = new OpeningFailurePane("primeiro", FILE, COPIES);
            pane.showTrying();

            pane.showReason("segundo");

            assertEquals("Motivo: segundo.", text(pane, "opening-failure-reason"));
            assertFalse(button(pane, "opening-failure-retry").isDisable());
            assertFalse(button(pane, "opening-failure-close").isDisable());
        });
    }
}

package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.persistence.backup.BackupKind;
import com.daniel.infrastructure.persistence.backup.BackupService;
import com.daniel.infrastructure.persistence.backup.BackupVerifier;
import com.daniel.infrastructure.persistence.backup.DataProtection;
import com.daniel.infrastructure.persistence.backup.DatabaseFingerprint;
import com.daniel.infrastructure.persistence.backup.RestoreService;
import com.daniel.infrastructure.persistence.backup.RetentionPolicy;
import com.daniel.presentation.view.FxTestSupport;
import com.daniel.presentation.view.components.Panel;
import com.daniel.presentation.view.util.Motion;
import com.daniel.presentation.view.util.UiPreferences;
import com.daniel.presentation.view.window.MemoryPreferences;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Cópias de segurança" (BRK-30): data calls run on the worker, buttons stay disabled until the
 * result, an unchanged database offers another location, restoring shows a preview and asks first,
 * and the shell reloads after a restore.
 */
class BackupSectionTest {

    static final Instant AT = Instant.parse("2026-10-22T06:15:00Z");
    static final Path FOLDER = Path.of("C:/dados/BreakInv/backups");

    final Deque<Runnable> worker = new ArrayDeque<>();
    final List<String> calls = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> confirmations = new ArrayList<>();
    BackupService.Result createResult = result(BackupService.Outcome.CREATED);
    BackupService.Result createInResult = result(BackupService.Outcome.CREATED);
    RestoreService.Prepared prepared;
    RestoreService.Result restoreResult = new RestoreService.Result(RestoreService.Outcome.RESTORED, "dados restaurados", null);
    RestoreService.Result overDamagedResult = new RestoreService.Result(RestoreService.Outcome.RESTORED,
            "dados restaurados", FOLDER.resolve("breakinv-danificado-20261022-061500-a1b2c3.db"), false);
    RuntimeException createFailure;
    Error createError;
    boolean answer = true;
    boolean answerDamaged = true;
    final List<Boolean> busyStates = new ArrayList<>();
    Boolean busyDuringRestore;
    Path chosenFolder = Path.of("E:/pendrive");
    Path chosenFile = FOLDER.resolve("breakinv-manual-20261022-061500-a1b2c3.db");

    @AfterEach
    void tearDown() {
        Motion.configure(null);
    }

    static BackupService.Result result(BackupService.Outcome o) {
        return new BackupService.Result(o, BackupKind.MANUAL, FOLDER.resolve("x.db"), AT, "fp", 7, "mensagem", List.of());
    }

    static RestoreService.Preview preview(int schemaVersion) {
        TreeMap<String, Long> counts = new TreeMap<>();
        counts.put("investment_type", 2L);
        counts.put("portfolio_movement", 6L);
        counts.put("simulation_scenario", 1L);
        counts.put("allocation_plan", 0L);
        return preview(schemaVersion, counts);
    }

    static RestoreService.Preview preview(int schemaVersion, TreeMap<String, Long> counts) {
        DatabaseFingerprint fp = new DatabaseFingerprint(schemaVersion, counts, "abc");
        BackupVerifier.Report report = new BackupVerifier.Report(true, true, true, "ok", fp, 0, null);
        return new RestoreService.Preview(FOLDER.resolve("breakinv-manual-20261022-061500-a1b2c3.db"),
                FOLDER.resolve("staged.partial"), report, AT, true, 4096);
    }

    DataProtection fake() {
        return new DataProtection() {
            @Override
            public BackupService.Status status() {
                calls.add("status");
                return new BackupService.Status(FOLDER, Optional.of(new BackupService.CopyInfo(
                        "breakinv-diaria-20261022-061500-a1b2c3.db", BackupKind.DAILY, AT, 12_897_484L, true)),
                        3, 30_000_000L, RetentionPolicy.defaults(), null, Optional.empty());
            }

            @Override
            public BackupService.Result createManual() {
                calls.add("createManual");
                if (createFailure != null) throw createFailure;
                if (createError != null) throw createError;
                return createResult;
            }

            @Override
            public BackupService.Result createIn(Path folder) {
                calls.add("createIn " + folder);
                return createInResult;
            }

            @Override
            public RestoreService.Prepared prepareRestore(Path chosen) {
                calls.add("prepare " + chosen.getFileName());
                return prepared;
            }

            @Override
            public RestoreService.Result restore(RestoreService.Preview preview) {
                calls.add("restore");
                busyDuringRestore = busyStates.isEmpty() ? null : busyStates.get(busyStates.size() - 1);
                return restoreResult;
            }

            @Override
            public RestoreService.Result restoreOverDamaged(RestoreService.Preview preview) {
                calls.add("restoreOverDamaged");
                return overDamagedResult;
            }

            @Override
            public void discard(RestoreService.Preview preview) {
                calls.add("discard");
            }
        };
    }

    BackupSection section(DataProtection data) {
        BackupSection.Pickers pickers = new BackupSection.Pickers() {
            @Override
            public Path chooseFolder(Window owner, Path initial) {
                return chosenFolder;
            }

            @Override
            public Path chooseFile(Window owner, Path initial) {
                return chosenFile;
            }
        };
        BackupSection.Prompts prompts = new BackupSection.Prompts() {
            @Override
            public boolean confirmRestore(String header, String message) {
                confirmations.add(header + "\n" + message);
                return answer;
            }

            @Override
            public boolean confirmOverDamaged(String header, String message) {
                confirmations.add(header + "\n" + message);
                return answerDamaged;
            }

            @Override
            public boolean offerOtherLocation(String message) {
                confirmations.add(message);
                return answer;
            }

            @Override
            public void error(String message) {
                errors.add(message);
            }
        };
        BackupSection s = new BackupSection(data, worker::add, Runnable::run, pickers, prompts, ZoneOffset.UTC);
        s.setOnDataBusy(busyStates::add);
        return s;
    }

    void drain() {
        while (!worker.isEmpty()) worker.poll().run();
    }

    @Test
    void statusShowsLastCopyFolderAndLimits_inPlainWords() {
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.refresh();
            assertEquals(List.of(), calls, "nothing read on the FX thread");
            drain();
            assertEquals("Última cópia: 22/10/2026 06:15 · automática do dia · 12,3 MB", s.statusLabel.getText());
            assertTrue(s.summaryLabel.getText().contains("3, somando 28,6 MB (limite de 1 GB)"), s.summaryLabel.getText());
            assertTrue(s.summaryLabel.getText().contains("as manuais nunca são apagadas automaticamente"));
            assertEquals("Pasta: " + FOLDER, s.folderLabel.getText());
            assertFalse(s.noticeLabel.isVisible());
            String all = String.join(" ", FxTestSupportTexts.of(s.view()));
            assertTrue(all.contains("não protege contra falha do disco"), all);
            assertTrue(all.contains("sem criptografia"), all);
            assertFalse(all.toLowerCase().contains("vacuum") || all.toLowerCase().contains("pragma"), all);
        });
    }

    @Test
    void createRunsOnTheWorker_andKeepsButtonsDisabledUntilTheResult() {
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.createBtn.fire();
            assertTrue(s.createBtn.isDisabled() && s.restoreBtn.isDisabled() && s.otherPlaceBtn.isDisabled());
            assertTrue(s.progressLabel.isVisible());
            assertEquals(List.of(), calls, "the copy is not made on the FX thread");
            s.createBtn.fire(); // ignored while running
            worker.poll().run();
            assertEquals(List.of("createManual"), calls);
            assertFalse(s.createBtn.isDisabled());
            assertFalse(s.progressLabel.isVisible());
            drain();
            assertEquals(List.of("createManual", "status"), calls, "status refreshed after the copy");
        });
    }

    @Test
    void unchangedData_offersAnotherLocation_andCopiesThereWhenAccepted() {
        createResult = result(BackupService.Outcome.UNCHANGED);
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.createBtn.fire();
            drain();
            assertTrue(confirmations.get(0).contains("Nada mudou desde a última cópia (22/10/2026 06:15)"),
                    confirmations.toString());
            assertTrue(calls.contains("createIn " + chosenFolder), calls.toString());
        });
    }

    @Test
    void failedCopy_showsTheReason() {
        createResult = new BackupService.Result(BackupService.Outcome.FAILED, BackupKind.MANUAL, null, null, null, -1,
                "o disco está cheio", List.of());
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.createBtn.fire();
            drain();
            assertEquals(List.of("Não foi possível criar a cópia: o disco está cheio."), errors);
        });
    }

    @Test
    void anErrorOnTheWorker_neverLeavesTheSectionDisabled() {
        createError = new AssertionError("falha grave simulada");
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.createBtn.fire();
            drain();
            assertFalse(s.createBtn.isDisabled() || s.restoreBtn.isDisabled() || s.otherPlaceBtn.isDisabled());
            assertFalse(s.progressLabel.isVisible());
            assertEquals(List.of("Não foi possível criar a cópia: falha grave simulada."), errors);
        });
    }

    @Test
    void otherLocationThatIsTheManagedFolder_unchanged_isInformation_notAnError() {
        createInResult = result(BackupService.Outcome.UNCHANGED);
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.otherPlaceBtn.fire();
            drain();
            assertEquals(List.of(), errors);
            assertTrue(calls.contains("createIn " + chosenFolder), calls.toString());
        });
    }

    @Test
    void restore_showsPreviewAsksFirst_thenReloadsTheShell() {
        prepared = new RestoreService.Prepared(preview(3), null);
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            List<String> reloads = new ArrayList<>();
            s.setOnDataReplaced(() -> reloads.add("reload"));
            s.restoreBtn.fire();
            drain();
            assertEquals(List.of("prepare breakinv-manual-20261022-061500-a1b2c3.db", "restore", "status"), calls);
            String asked = confirmations.get(0);
            assertTrue(asked.startsWith("Substituir os dados atuais por esta cópia?"), asked);
            assertTrue(asked.contains("Criada em: 22/10/2026 06:15"), asked);
            assertTrue(asked.contains("2 investimento(s), 6 movimentação(ões), 1 cenário(s) e 0 plano(s)"), asked);
            assertTrue(asked.contains("Os dados atuais serão substituídos"), asked);
            assertTrue(asked.contains("Feche outras janelas do BreakInv"), asked);
            assertEquals(List.of("reload"), reloads);
            assertEquals(List.of(), errors);
            assertEquals(Boolean.TRUE, busyDuringRestore, "the shell is blocked while the data are replaced");
            assertEquals(List.of(true, false), busyStates, "and released afterwards");
        });
    }

    @Test
    void damagedCurrentData_askAgain_thenTheFileIsKeptAsItIsAndRestored() {
        prepared = new RestoreService.Prepared(preview(3), null);
        restoreResult = new RestoreService.Result(RestoreService.Outcome.CURRENT_DAMAGED,
                "os dados atuais estão danificados e não puderam ser copiados com conferência (database disk image "
                        + "is malformed)", null);
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            List<String> reloads = new ArrayList<>();
            s.setOnDataReplaced(() -> reloads.add("reload"));
            s.restoreBtn.fire();
            drain();
            assertEquals(List.of("prepare breakinv-manual-20261022-061500-a1b2c3.db", "restore", "restoreOverDamaged",
                    "status"), calls);
            String second = confirmations.get(1);
            assertTrue(second.startsWith("Os dados atuais estão danificados"), second);
            assertTrue(second.contains("como está, sem conferência"), second);
            assertTrue(second.contains("nunca é apagado"), second);
            assertEquals(List.of("reload"), reloads);
            assertEquals(List.of(true, false, true, false), busyStates);
            assertEquals(List.of(), errors);
        });
    }

    @Test
    void damagedCurrentData_declined_discardsThePreparedCopy() {
        prepared = new RestoreService.Prepared(preview(3), null);
        restoreResult = new RestoreService.Result(RestoreService.Outcome.CURRENT_DAMAGED, "danificados", null);
        answerDamaged = false;
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            s.restoreBtn.fire();
            drain();
            assertEquals(List.of("prepare breakinv-manual-20261022-061500-a1b2c3.db", "restore", "discard", "status"),
                    calls);
            assertFalse(s.restoreBtn.isDisabled());
            assertEquals(List.of(true, false), busyStates);
        });
    }

    @Test
    void copyFromBeforeTheLedger_previewCountsTheEarlierTables() {
        TreeMap<String, Long> counts = new TreeMap<>();
        counts.put("investment_type", 4L);
        counts.put("transactions", 12L);
        counts.put("investment_snapshots", 30L);
        prepared = new RestoreService.Prepared(preview(1, counts), null);
        answer = false;
        FxTestSupport.runAndWait(() -> {
            section(fake()).restoreBtn.fire();
            drain();
            String asked = confirmations.get(0);
            assertTrue(asked.contains("4 investimento(s), 12 transação(ões) e 30 registro(s) diário(s), no formato anterior"),
                    asked);
            assertFalse(asked.contains("0 movimentação"), asked);
        });
    }

    @Test
    void olderCopy_saysItWillBeUpdated() {
        prepared = new RestoreService.Prepared(preview(1), null);
        FxTestSupport.runAndWait(() -> {
            section(fake()).restoreBtn.fire();
            drain();
            assertTrue(confirmations.get(0).contains("versão anterior do BreakInv e será atualizada"), confirmations.toString());
        });
    }

    @Test
    void cancelledRestore_discardsTheStagedCopy_andChangesNothing() {
        prepared = new RestoreService.Prepared(preview(3), null);
        answer = false;
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            List<String> reloads = new ArrayList<>();
            s.setOnDataReplaced(() -> reloads.add("reload"));
            s.restoreBtn.fire();
            drain();
            assertEquals(List.of("prepare breakinv-manual-20261022-061500-a1b2c3.db", "discard"), calls);
            assertEquals(List.of(), reloads);
            assertFalse(s.restoreBtn.isDisabled());
        });
    }

    @Test
    void refusedFile_andFailedRestore_explainWithoutReloading() {
        prepared = new RestoreService.Prepared(null, "o arquivo não é um banco de dados do BreakInv");
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            List<String> reloads = new ArrayList<>();
            s.setOnDataReplaced(() -> reloads.add("reload"));
            s.restoreBtn.fire();
            drain();
            assertEquals(List.of("Este arquivo não pode ser restaurado: o arquivo não é um banco de dados do BreakInv."), errors);

            prepared = new RestoreService.Prepared(preview(3), null);
            restoreResult = new RestoreService.Result(RestoreService.Outcome.REFUSED,
                    "outra janela do BreakInv está aberta. Feche-a e tente de novo; nada foi alterado.", null);
            s.restoreBtn.fire();
            drain();
            assertTrue(errors.get(1).startsWith("Não foi possível restaurar: outra janela"), errors.toString());
            assertEquals(List.of(), reloads);
        });
    }

    @Test
    void withoutDataProtection_theBlockSaysSoAndOffersNoAction() {
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(null);
            s.refresh();
            s.createBtn.fire();
            assertTrue(s.createBtn.isDisabled() && s.restoreBtn.isDisabled() && s.otherPlaceBtn.isDisabled());
            assertTrue(s.statusLabel.getText().contains("indisponíveis"));
            assertTrue(worker.isEmpty());
        });
    }

    @Test
    void settingsPage_placesTheBlockInTheDataPanel_andReloadsOnShow() {
        UiPreferences prefs = new UiPreferences(new MemoryPreferences());
        Motion.configure(prefs);
        com.daniel.infrastructure.persistence.config.Database.configure("jdbc:sqlite::memory:");
        try {
            settingsPageBody(prefs);
        } finally {
            com.daniel.infrastructure.persistence.config.Database.close();
            com.daniel.infrastructure.persistence.config.Database.configure(null);
        }
    }

    private void settingsPageBody(UiPreferences prefs) {
        FxTestSupport.runAndWait(() -> {
            BackupSection s = section(fake());
            ConfiguracoesPage page = new ConfiguracoesPage(prefs, s);
            Parent root = (Parent) ((ScrollPane) page.view()).getContent();
            List<Panel> panels = ConfiguracoesPageLayoutTest.flatten(root).stream()
                    .filter(Panel.class::isInstance).map(Panel.class::cast).distinct().toList();
            Panel data = panels.get(panels.size() - 1);
            assertEquals("Dados e sobre o aplicativo", ((Label) data.lookup(".panel-title")).getText());
            assertTrue(ConfiguracoesPageLayoutTest.flatten(data).contains(s.createBtn));
            assertTrue(ConfiguracoesPageLayoutTest.flatten(data).contains(s.restoreBtn));
            // the copies are set apart from the version and privacy text below them (BRK-30 capture:
            // "BreakInv 0.5.0" read as part of the copies block)
            List<Node> siblings = s.view().getParent().getChildrenUnmodifiable();
            int at = siblings.indexOf(s.view());
            assertTrue(siblings.get(at + 1) instanceof javafx.scene.control.Separator, "a divider follows the copies: " + siblings);
            assertEquals("BreakInv 0.5.0", ((Label) siblings.get(at + 2)).getText());
            page.onShow();
            drain();
            assertTrue(calls.contains("status"), "opening the page reads the copies in the background");
        });
    }

    /** Visible label texts of a subtree. */
    static final class FxTestSupportTexts {
        static List<String> of(Node n) {
            List<String> out = new ArrayList<>();
            for (Node c : ConfiguracoesPageLayoutTest.flatten(n)) {
                if (c instanceof Label l && l.getText() != null) out.add(l.getText());
            }
            return out;
        }
    }
}

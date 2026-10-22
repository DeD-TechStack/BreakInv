package com.daniel.presentation.view.pages;

import com.daniel.infrastructure.persistence.backup.BackupService;
import com.daniel.infrastructure.persistence.backup.DataProtection;
import com.daniel.infrastructure.persistence.backup.RestoreService;
import com.daniel.infrastructure.persistence.backup.Sizes;
import com.daniel.presentation.view.components.ToastHost;
import com.daniel.presentation.view.util.Dialogs;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * "Cópias de segurança" inside Configurações (BRK-30): last copy, managed folder, create a verified
 * copy, save one elsewhere and restore one. Every data call runs on the worker executor; results come
 * back on the UI executor. Buttons stay disabled while an operation runs.
 */
final class BackupSection {

    private static final Logger LOG = Logger.getLogger(BackupSection.class.getName());
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Native choosers (production) or fixed answers (tests). Null = cancelled. */
    interface Pickers {
        Path chooseFolder(Window owner, Path initial);

        Path chooseFile(Window owner, Path initial);
    }

    /** Questions and errors (production: the shared dialogs). */
    interface Prompts {
        boolean confirmRestore(String header, String message);

        /** Second question when the current data are too damaged for a verified copy. */
        boolean confirmOverDamaged(String header, String message);

        boolean offerOtherLocation(String message);

        void error(String message);
    }

    private final DataProtection data;
    private final Executor worker;
    private final Executor ui;
    private final Pickers pickers;
    private final Prompts prompts;
    private final ZoneId zone;
    private Runnable onDataReplaced = () -> { };
    private Consumer<Boolean> onDataBusy = busy -> { };
    private Path folder;
    private boolean running;

    final VBox root = new VBox(8);
    final Label statusLabel = new Label();
    final Label summaryLabel = new Label();
    final Label folderLabel = new Label();
    final Label noticeLabel = new Label();
    final Label progressLabel = new Label();
    final Hyperlink openFolderLink = new Hyperlink("Abrir pasta");
    final Button createBtn = new Button("Criar cópia de segurança");
    final Button otherPlaceBtn = new Button("Salvar cópia em outro local…");
    final Button restoreBtn = new Button("Restaurar…");

    BackupSection(DataProtection data, Executor worker, Executor ui, Pickers pickers, Prompts prompts, ZoneId zone) {
        this.data = data;
        this.worker = worker;
        this.ui = ui;
        this.pickers = pickers;
        this.prompts = prompts;
        this.zone = zone;
        build();
    }

    /** Production wiring: native choosers and the shared dialogs. */
    static BackupSection standard(DataProtection data, Executor worker, Executor ui) {
        return new BackupSection(data, worker, ui, nativePickers(), dialogPrompts(), ZoneId.systemDefault());
    }

    VBox view() {
        return root;
    }

    void setOnDataReplaced(Runnable r) {
        this.onDataReplaced = r == null ? () -> { } : r;
    }

    /** The shell blocks itself while a restore replaces the data (true at the start, false at the end). */
    void setOnDataBusy(Consumer<Boolean> c) {
        this.onDataBusy = c == null ? busy -> { } : c;
    }

    private void build() {
        root.getStyleClass().add("backup-section");
        Label title = new Label("Cópias de segurança");
        title.getStyleClass().add("form-label");

        for (Label l : new Label[]{statusLabel, summaryLabel, folderLabel, noticeLabel, progressLabel}) {
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            l.setMinHeight(Region.USE_PREF_SIZE);
        }
        statusLabel.getStyleClass().add("text-secondary");
        summaryLabel.getStyleClass().add("text-helper");
        folderLabel.getStyleClass().add("text-helper");
        noticeLabel.getStyleClass().add("status-warning");
        progressLabel.getStyleClass().add("text-helper");
        show(noticeLabel, false);
        show(progressLabel, false);

        Label risk = new Label("A pasta fica no mesmo disco dos dados e não protege contra falha do disco: para isso, "
                + "salve também uma cópia em outro local, como outro disco ou um pen drive. As cópias contêm todos os "
                + "dados, inclusive o token da BRAPI, sem criptografia.");
        risk.getStyleClass().add("text-helper");
        risk.setWrapText(true);
        risk.setMaxWidth(Double.MAX_VALUE);
        risk.setMinHeight(Region.USE_PREF_SIZE);

        openFolderLink.getStyleClass().addAll("text-helper", "text-link");
        openFolderLink.setTooltip(new Tooltip("Abre a pasta das cópias no explorador de arquivos"));
        openFolderLink.setOnAction(e -> openFolder());
        FlowPane folderRow = new FlowPane(8, 4, folderLabel, openFolderLink);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        folderLabel.maxWidthProperty().bind(folderRow.widthProperty());

        createBtn.getStyleClass().add("button");
        createBtn.setTooltip(new Tooltip("Cria uma cópia dos dados nesta pasta e confere se ela abre igual aos dados atuais"));
        createBtn.setOnAction(e -> create());
        otherPlaceBtn.getStyleClass().add("secondary-btn");
        otherPlaceBtn.setTooltip(new Tooltip("Cria uma cópia conferida numa pasta escolhida por você; ela nunca é apagada pelo BreakInv"));
        otherPlaceBtn.setOnAction(e -> createElsewhere());
        restoreBtn.getStyleClass().add("ghost-btn");
        restoreBtn.setTooltip(new Tooltip("Substitui os dados atuais por uma cópia escolhida, depois de guardar os atuais"));
        restoreBtn.setOnAction(e -> restore());
        FlowPane actions = new FlowPane(10, 8, createBtn, otherPlaceBtn, restoreBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(title, statusLabel, summaryLabel, folderRow, risk, noticeLabel, actions, progressLabel);
        if (data == null) {
            statusLabel.setText("Cópias de segurança indisponíveis nesta janela.");
            setButtonsDisabled(true);
            openFolderLink.setDisable(true);
        } else {
            statusLabel.setText("Carregando as cópias…");
        }
    }

    // ── Status ──────────────────────────────────────────────────────────

    void refresh() {
        if (data == null) return;
        worker.execute(() -> {
            BackupService.Status s;
            try {
                s = data.status();
            } catch (RuntimeException e) {
                ui.execute(() -> statusLabel.setText("Não foi possível ler as cópias: " + e.getMessage()));
                return;
            }
            ui.execute(() -> render(s));
        });
    }

    void render(BackupService.Status s) {
        folder = s.folder();
        statusLabel.setText(s.lastValid()
                .map(c -> "Última cópia: " + when(c.createdAt()) + " · " + c.kind().label() + " · " + Sizes.text(c.sizeBytes())
                        + (c.healthy() ? "" : " · com avisos"))
                .orElse("Nenhuma cópia ainda."));
        summaryLabel.setText("Cópias guardadas pelo BreakInv: " + s.managedCopies() + ", somando "
                + Sizes.text(s.managedBytes()) + " (limite de " + Sizes.text(s.policy().budgetBytes()) + "). Ficam as "
                + s.policy().maxAutomatic() + " automáticas mais recentes; as manuais nunca são apagadas automaticamente.");
        folderLabel.setText("Pasta: " + s.folder());
        String notice = s.notice();
        Optional<BackupService.Result> auto = s.lastAutomatic();
        if (notice == null && auto.isPresent() && auto.get().outcome() == BackupService.Outcome.FAILED) {
            notice = "A última cópia automática falhou: " + auto.get().message() + ".";
        }
        noticeLabel.setText(notice == null ? "" : notice);
        show(noticeLabel, notice != null);
    }

    // ── Actions ─────────────────────────────────────────────────────────

    void create() {
        if (data == null || running) return;
        begin("Criando e conferindo a cópia…");
        worker.execute(() -> {
            BackupService.Result r = call(data::createManual);
            ui.execute(() -> {
                end();
                switch (r.outcome()) {
                    case CREATED -> created(r, "Cópia criada e conferida.");
                    case UNCHANGED -> {
                        String at = r.createdAt() == null ? "" : " (" + when(r.createdAt()) + ")";
                        if (prompts.offerOtherLocation("Nada mudou desde a última cópia" + at + ", que continua válida. "
                                + "Quer salvar uma cópia em outro local, como outro disco ou um pen drive?")) {
                            createElsewhere();
                        }
                    }
                    case EMPTY -> ToastHost.showInfo("Ainda não há dados para copiar.");
                    case BUSY -> ToastHost.showWarn("Outra operação com os dados está em andamento.");
                    default -> prompts.error("Não foi possível criar a cópia: " + r.message() + ".");
                }
                refresh();
            });
        });
    }

    void createElsewhere() {
        if (data == null || running) return;
        Path chosen = pickers.chooseFolder(window(), null);
        if (chosen == null) return;
        begin("Criando e conferindo a cópia em " + chosen + "…");
        worker.execute(() -> {
            BackupService.Result r = call(() -> data.createIn(chosen));
            ui.execute(() -> {
                end();
                switch (r.outcome()) {
                    case CREATED -> created(r, "Cópia salva e conferida em " + r.file() + ".");
                    case UNCHANGED -> ToastHost.showInfo("Esta é a pasta das cópias do BreakInv e nada mudou desde a "
                            + "última cópia, que continua válida.");
                    case EMPTY -> ToastHost.showInfo("Ainda não há dados para copiar.");
                    case BUSY -> ToastHost.showWarn("Outra operação com os dados está em andamento.");
                    default -> prompts.error("Não foi possível salvar a cópia: " + r.message() + ".");
                }
                refresh();
            });
        });
    }

    void restore() {
        if (data == null || running) return;
        Path chosen = pickers.chooseFile(window(), folder);
        if (chosen == null) return;
        begin("Conferindo o arquivo escolhido…");
        worker.execute(() -> {
            RestoreService.Prepared p = callPrepared(() -> data.prepareRestore(chosen));
            ui.execute(() -> {
                end();
                if (!p.ok()) {
                    prompts.error("Este arquivo não pode ser restaurado: " + p.refusal() + ".");
                    return;
                }
                RestoreService.Preview preview = p.preview();
                if (!prompts.confirmRestore("Substituir os dados atuais por esta cópia?", describe(preview))) {
                    worker.execute(() -> data.discard(preview));
                    return;
                }
                replace(preview, false);
            });
        });
    }

    /** Runs the replacement on the worker while the shell is blocked; then reloads or explains. */
    private void replace(RestoreService.Preview preview, boolean overDamaged) {
        begin(overDamaged ? "Guardando o arquivo atual e restaurando…" : "Restaurando…");
        onDataBusy.accept(true);
        worker.execute(() -> {
            RestoreService.Result r = callRestore(() -> overDamaged ? data.restoreOverDamaged(preview) : data.restore(preview));
            ui.execute(() -> {
                onDataBusy.accept(false);
                end();
                switch (r.outcome()) {
                    case RESTORED -> {
                        ToastHost.showSuccess(r.safetyVerified()
                                ? "Dados restaurados. Os dados anteriores ficaram guardados numa cópia."
                                : "Dados restaurados. O arquivo anterior, danificado, foi guardado como estava em "
                                + fileName(r.safetyCopy()) + ".");
                        onDataReplaced.run();
                    }
                    case CURRENT_DAMAGED -> {
                        if (!overDamaged && prompts.confirmOverDamaged("Os dados atuais estão danificados",
                                damagedMessage(r))) {
                            replace(preview, true);
                            return;
                        }
                        worker.execute(() -> data.discard(preview));
                    }
                    default -> prompts.error("Não foi possível restaurar: " + r.message());
                }
                refresh();
            });
        });
    }

    static String damagedMessage(RestoreService.Result r) {
        return "O BreakInv não conseguiu guardar uma cópia conferida antes de restaurar porque " + r.message() + ".\n\n"
                + "Se continuar, o BreakInv guarda o arquivo atual como está, sem conferência, na pasta das cópias "
                + "(com o nome breakinv-danificado-…), e a cópia escolhida substitui os dados. Esse arquivo nunca é "
                + "apagado pelo BreakInv.";
    }

    String describe(RestoreService.Preview p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Arquivo: ").append(p.chosen().getFileName()).append('\n');
        sb.append(p.fromRegistry() ? "Criada em: " : "Modificado em: ").append(when(p.createdAt())).append('\n');
        if (p.report().fingerprint().rowCounts().containsKey("portfolio_movement")) {
            sb.append("Conteúdo: ").append(p.count("investment_type")).append(" investimento(s), ")
                    .append(p.count("portfolio_movement")).append(" movimentação(ões), ")
                    .append(p.count("simulation_scenario")).append(" cenário(s) e ")
                    .append(p.count("allocation_plan")).append(" plano(s).\n");
        } else {
            // copies from before the ledger keep their history in the earlier tables
            sb.append("Conteúdo: ").append(p.count("investment_type")).append(" investimento(s), ")
                    .append(p.count("transactions")).append(" transação(ões) e ")
                    .append(p.count("investment_snapshots")).append(" registro(s) diário(s), no formato anterior.\n");
        }
        if (p.olderVersion()) {
            sb.append("A cópia é de uma versão anterior do BreakInv e será atualizada depois de restaurada.\n");
        }
        sb.append("\nOs dados atuais serão substituídos. Antes, o BreakInv guarda uma cópia deles na pasta das cópias. ")
                .append("Feche outras janelas do BreakInv antes de continuar.");
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void created(BackupService.Result r, String success) {
        ToastHost.showSuccess(success);
        if (!r.warnings().isEmpty()) ToastHost.showWarn(String.join(" ", r.warnings()));
    }

    private void begin(String text) {
        running = true;
        setButtonsDisabled(true);
        progressLabel.setText(text);
        show(progressLabel, true);
    }

    private void end() {
        running = false;
        setButtonsDisabled(false);
        progressLabel.setText("");
        show(progressLabel, false);
    }

    boolean running() {
        return running;
    }

    private void setButtonsDisabled(boolean disabled) {
        createBtn.setDisable(disabled);
        otherPlaceBtn.setDisable(disabled);
        restoreBtn.setDisable(disabled);
    }

    private static void show(Label l, boolean visible) {
        l.setVisible(visible);
        l.setManaged(visible);
    }

    private String when(Instant i) {
        return i == null ? "data desconhecida" : WHEN.format(i.atZone(zone));
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private void openFolder() {
        Path f = folder;
        if (f == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(f);
                Desktop.getDesktop().open(f.toFile());
            } catch (Exception ex) {
                ui.execute(() -> ToastHost.showError("Não foi possível abrir a pasta das cópias."));
            }
        });
    }

    private static String fileName(Path p) {
        return p == null ? "a pasta das cópias" : String.valueOf(p.getFileName());
    }

    // Any Throwable becomes a failure result, so the section never stays disabled (or the shell blocked).

    private static BackupService.Result call(Supplier<BackupService.Result> s) {
        try {
            return s.get();
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "backup action failed", e);
            return new BackupService.Result(BackupService.Outcome.FAILED, null, null, null, null, -1,
                    String.valueOf(e.getMessage()), java.util.List.of());
        }
    }

    private static RestoreService.Prepared callPrepared(Supplier<RestoreService.Prepared> s) {
        try {
            return s.get();
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "restore preparation failed", e);
            return new RestoreService.Prepared(null, String.valueOf(e.getMessage()));
        }
    }

    private static RestoreService.Result callRestore(Supplier<RestoreService.Result> s) {
        try {
            return s.get();
        } catch (Throwable e) {
            LOG.log(Level.SEVERE, "restore failed", e);
            return new RestoreService.Result(RestoreService.Outcome.FAILED_CRITICAL, String.valueOf(e.getMessage())
                    + ". Os dados anteriores ficam na pasta das cópias", null);
        }
    }


    static Pickers nativePickers() {
        return new Pickers() {
            @Override
            public Path chooseFolder(Window owner, Path initial) {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("Escolher pasta para a cópia");
                if (initial != null && Files.isDirectory(initial)) dc.setInitialDirectory(initial.toFile());
                File f = dc.showDialog(owner);
                return f == null ? null : f.toPath();
            }

            @Override
            public Path chooseFile(Window owner, Path initial) {
                FileChooser fc = new FileChooser();
                fc.setTitle("Escolher cópia para restaurar");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Cópias do BreakInv (*.db)", "*.db"));
                if (initial != null && Files.isDirectory(initial)) fc.setInitialDirectory(initial.toFile());
                File f = fc.showOpenDialog(owner);
                return f == null ? null : f.toPath();
            }
        };
    }

    static Prompts dialogPrompts() {
        return new Prompts() {
            @Override
            public boolean confirmRestore(String header, String message) {
                return Dialogs.confirm("Restaurar cópia", header, message, "Restaurar");
            }

            @Override
            public boolean confirmOverDamaged(String header, String message) {
                return Dialogs.confirm("Restaurar cópia", header, message, "Guardar e restaurar");
            }

            @Override
            public boolean offerOtherLocation(String message) {
                return Dialogs.confirm("Cópia de segurança", null, message, "Salvar em outro local…");
            }

            @Override
            public void error(String message) {
                Dialogs.error(message);
            }
        };
    }
}

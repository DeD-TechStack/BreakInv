package com.daniel.presentation.view.pages.storage;

import com.daniel.infrastructure.persistence.backup.Sizes;
import com.daniel.presentation.view.pages.storage.StorageFacts.Disk;
import com.daniel.presentation.view.pages.storage.StorageFacts.Group;
import com.daniel.presentation.view.pages.storage.StorageFacts.Need;
import com.daniel.presentation.view.pages.storage.StorageFacts.NeedLine;
import com.daniel.presentation.view.util.UiNumbers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BRK-31 u5 F2: decides the state of the "Dados e armazenamento" section and writes every text of it, word by word
 * (DESENHO-BRK31-CONFIG §1 and §2, SPEC-BRK31-U5-F2 RF-02 to RF-06b). Pure: values in, text out, no JavaFX, no disk,
 * no preferences. The screen (F3) only binds these texts to controls: a {@link Tone} to a style class and an
 * {@link Icon} to a Feather icon.
 *
 * <p>A size is always {@link Sizes#text(long)} ("1,5 MB"), a count always {@link UiNumbers#integer(long)}, and a size
 * that was not measured ({@code -1}) is {@link UiNumbers#DASH}.</p>
 */
public final class StoragePresenter {

    public static final String SECTION_TITLE = "Dados e armazenamento";
    public static final String ABOUT_TITLE = "Sobre o aplicativo";
    public static final String BLOCK_TITLE = "Espaço";

    public static final String MEASURE_AGAIN = "Medir de novo";
    public static final String SAVE_LIMITS = "Salvar limites";
    public static final String CREATE_ANYWAY = "Criar mesmo assim";

    public static final String WARN_LABEL = "Avisar quando os dados passarem de";
    public static final String WARN_HELP = "É só um aviso; nada muda no uso.";
    public static final String FLOOR_LABEL = "Margem da reserva";
    public static final String FLOOR_HELP = "A reserva é 2 × o tamanho dos dados + esta margem.";
    public static final String WRITE_LABEL = "Limite de gravação";
    public static final String WRITE_HELP = "Abaixo deste espaço livre, novas gravações são recusadas.";
    public static final String UNIT = "MB";
    public static final String LIMITS_NOTE = "Os limites ficam neste computador, fora dos dados: restaurar uma cópia "
            + "não os muda, e eles podem ser salvos mesmo quando os dados não aceitam gravações.";

    private static final String HELP_TEXT = "MB e GB aqui são binários (1 MB = 1.048.576 bytes), como no Windows.";

    /** The level of the section, most serious first (the order is the priority of {@link #state}). */
    public enum SpaceState { UNMEASURED, CRITICAL, DB_UNKNOWN, RESERVE, DB_WARNING, NORMAL }

    /** The style class family of a line: {@code text-helper}, {@code text-secondary}, {@code status-warning}, {@code status-danger}. */
    public enum Tone { HELPER, SECONDARY, WARNING, DANGER }

    /** The Feather icon of a line, if any. */
    public enum Icon { NONE, ALERT_TRIANGLE, ALERT_OCTAGON }

    /** One line of text with its tone and icon. */
    public record Line(String text, Tone tone, Icon icon) {
    }

    /** One row of the measures grid: a label and its value. */
    public record Row(String label, String value) {
    }

    /** The order that names the disk of a {@link NeedLine}: the first of its {@code disks} in this order. */
    private static final List<Disk> DISK_NAME_ORDER = List.of(Disk.DATA, Disk.COPIES, Disk.DESTINATION, Disk.TEMP);

    private StoragePresenter() {
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String size(long bytes) {
        return bytes < 0 ? UiNumbers.DASH : Sizes.text(bytes);
    }

    /**
     * A reason (SPEC RF-03 {@code freeDataReason}, RF-06 {@code unmeasuredReason} and {@code unknownReason}) exists
     * whenever it is not {@code null}: {@code ""}, {@code " "} and a tab are reasons and come out as they came.
     */
    private static boolean hasReason(String reason) {
        return reason != null;
    }

    /**
     * A message (SPEC RF-03 and §4 {@code postponedMessage}, RF-04 {@code readFailed} and {@code saveCrashed}) exists
     * when it is neither {@code null} nor empty. A message of only spaces or tabs is not empty and comes out as it came.
     */
    private static boolean hasMessage(String message) {
        return message != null && !message.isEmpty();
    }

    private static Line line(String text, Tone tone) {
        return new Line(text, tone, Icon.NONE);
    }

    // ── state and level line (RF-02, RF-03) ────────────────────────────────

    /** The state of the section: the most serious signal that is on, an undetermined write limit first. */
    public static SpaceState state(StorageFacts facts) {
        if (facts.writeLimitUndetermined()) return SpaceState.UNMEASURED;
        if (facts.writeLimitActive()) return SpaceState.CRITICAL;
        if (facts.warnUndetermined() || facts.reserveUndetermined()) return SpaceState.DB_UNKNOWN;
        if (facts.reserveActive()) return SpaceState.RESERVE;
        if (facts.warnActive()) return SpaceState.DB_WARNING;
        return SpaceState.NORMAL;
    }

    /** The one line of the current level, with the extra sentences (database warning, postponed copy) when they apply. */
    public static Line levelLine(StorageFacts f) {
        SpaceState state = state(f);
        String text;
        Tone tone = Tone.WARNING;
        Icon icon = Icon.ALERT_TRIANGLE;
        switch (state) {
            case UNMEASURED -> text = "Não foi possível medir o espaço livre do disco dos dados"
                    + (hasReason(f.freeDataReason()) ? " (" + f.freeDataReason() + ")" : "")
                    + ". As gravações continuam; a cópia manual pede confirmação; a restauração e a cópia "
                    + "automática esperam a medida.";
            case CRITICAL -> {
                text = "Espaço livre abaixo do limite de gravação: há " + size(f.freeData()) + " e o mínimo é "
                        + size(f.writeLimitBytes()) + ". Novas gravações estão recusadas até liberar pelo menos "
                        + shortfall(f) + ". Leitura, exportação e cópia para outro disco continuam.";
                tone = Tone.DANGER;
                icon = Icon.ALERT_OCTAGON;
            }
            case RESERVE -> text = "Pouco espaço para restaurar: há " + size(f.freeData())
                    + " livres e a reserva pede " + size(f.reserveTargetBytes())
                    + " (2 × dados + margem). As gravações continuam; a cópia automática pode ser adiada.";
            case DB_WARNING -> text = "Os dados passaram de " + size(f.warnBytes()) + " (estão com "
                    + size(f.databaseBytes()) + "). Nada muda no uso; é um sinal de crescimento.";
            case DB_UNKNOWN -> text = "Não foi possível ler o tamanho dos dados; o aviso e a reserva ficam sem "
                    + "conta até a próxima medida.";
            default -> {
                text = "Espaço suficiente: há " + size(f.freeData()) + " livres no disco dos dados.";
                tone = Tone.SECONDARY;
                icon = Icon.NONE;
            }
        }
        StringBuilder out = new StringBuilder(text);
        if (f.warnActive() && state != SpaceState.DB_WARNING) {
            out.append(" Os dados também passaram de ").append(size(f.warnBytes())).append(" (estão com ")
                    .append(size(f.databaseBytes())).append(").");
        }
        if (hasMessage(f.postponedMessage())) out.append(' ').append(f.postponedMessage());
        return new Line(out.toString(), tone, icon);
    }

    /** Z of the critical text: what has to be freed, the limit minus the free space; a dash when either is unknown. */
    private static String shortfall(StorageFacts f) {
        if (f.freeData() < 0 || f.writeLimitBytes() < 0) return UiNumbers.DASH;
        return Sizes.text(Math.max(0, f.writeLimitBytes() - f.freeData()));
    }

    // ── transient states (RF-04) ───────────────────────────────────────────

    public static Line measuring() {
        return line("Medindo o espaço dos dados…", Tone.HELPER);
    }

    public static Line readFailed(String message) {
        return line("Não foi possível ler o espaço dos dados" + (hasMessage(message) ? ": " + message : "") + ".",
                Tone.DANGER);
    }

    public static Line unavailable() {
        return line("Espaço dos dados indisponível nesta janela.", Tone.SECONDARY);
    }

    public static Line saving() {
        return line("Salvando os limites…", Tone.HELPER);
    }

    public static Line saved() {
        return line("Limites salvos.", Tone.SECONDARY);
    }

    /** The reason of a save that did not happen, followed by the limits that stay in effect. */
    public static Line saveFailed(String reason, long warn, long floor, long write) {
        return line(reason + " Valendo agora: aviso " + size(warn) + " · margem " + size(floor) + " · limite "
                + size(write) + ".", Tone.DANGER);
    }

    /** For the worker that threw: the saved limits did not change. */
    public static Line saveCrashed(String message) {
        return line("Não foi possível salvar os limites" + (hasMessage(message) ? " (" + message + ")" : "")
                + ". Os limites anteriores continuam valendo.", Tone.DANGER);
    }

    /** The text of {@code StorageLimits.problem()}, as it came. */
    public static Line rejected(String problem) {
        return line(problem == null ? "" : problem, Tone.DANGER);
    }

    public static Line notANumber() {
        return line("Digite um número inteiro de MB, sem pontos.", Tone.DANGER);
    }

    /** One line per corrected item of {@code StoragePreferences.Loaded}: the item with a capital letter and a full stop. */
    public static List<Line> corrected(List<String> items) {
        List<Line> lines = new ArrayList<>();
        if (items == null) return lines;
        for (String item : items) {
            String text = item == null ? "" : item;
            if (!text.isEmpty()) text = text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
            lines.add(line(text + ".", Tone.WARNING));
        }
        return lines;
    }

    // ── measures (RF-05) ───────────────────────────────────────────────────

    private static final String LABEL_DATA = "Dados (breakinv.db)";
    private static final String LABEL_TEMP_FILES = "Arquivos temporários do banco";
    private static final String LABEL_COPIES_FOLDER = "Pasta das cópias";
    private static final String LABEL_TOTAL = "Total da pasta de dados";
    private static final String LABEL_FREE_DATA = "Livre no disco dos dados";
    private static final String LABEL_FREE_COPIES = "Livre no disco das cópias";
    private static final String LABEL_FREE_TEMP = "Livre no disco temporário";

    /** The rows of the measures grid, in order: five on one volume, plus one per volume that is not the data's. */
    public static List<Row> measureRows(StorageFacts f) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(LABEL_DATA, size(f.databaseBytes())));
        rows.add(new Row(LABEL_TEMP_FILES, f.auxiliaryBytes() == 0 ? "nenhum" : size(f.auxiliaryBytes())));
        rows.add(new Row(LABEL_COPIES_FOLDER, copiesFolder(f)));
        rows.add(new Row(LABEL_TOTAL, size(f.knownTotalBytes())
                + (f.complete() ? "" : " (mínimo: alguns tamanhos não puderam ser lidos)")));
        rows.add(new Row(LABEL_FREE_DATA, size(f.freeData())));
        if (!f.copiesOnDataDisk()) rows.add(new Row(LABEL_FREE_COPIES, size(f.freeCopies())));
        if (!f.tempOnDataDisk()) rows.add(new Row(LABEL_FREE_TEMP, size(f.freeTemp())));
        return rows;
    }

    /** The same five rows of the one-volume case with every value "—", while the reading is in progress. */
    public static List<Row> measuringRows() {
        return List.of(
                new Row(LABEL_DATA, UiNumbers.DASH),
                new Row(LABEL_TEMP_FILES, UiNumbers.DASH),
                new Row(LABEL_COPIES_FOLDER, UiNumbers.DASH),
                new Row(LABEL_TOTAL, UiNumbers.DASH),
                new Row(LABEL_FREE_DATA, UiNumbers.DASH));
    }

    public static Line helpLine() {
        return line(HELP_TEXT, Tone.HELPER);
    }

    private static String copiesFolder(StorageFacts f) {
        return group(f.managed(), "cópia", "cópias", "", "")
                + " · " + group(f.damaged(), "danificada", "danificadas", "", "")
                + " · " + group(f.others(), "outro arquivo", "outros arquivos",
                        "; nunca apagado pelo BreakInv", "; nunca apagados pelo BreakInv");
    }

    /** "<n> cópias (<tamanho>)": no parenthesis for an empty group; unknown sizes are counted inside it. */
    private static String group(Group g, String singular, String plural, String singularNote, String pluralNote) {
        String head = UiNumbers.integer(g.files()) + " " + (g.files() == 1 ? singular : plural);
        if (g.files() <= 0) return head;
        String inside = size(g.knownBytes());
        if (g.unknownSizes() > 0) inside += ", " + UiNumbers.integer(g.unknownSizes()) + " sem tamanho lido";
        return head + " (" + inside + (g.files() == 1 ? singularNote : pluralNote) + ")";
    }

    // ── estimates (RF-06) ──────────────────────────────────────────────────

    private static final String COPY_PREFIX = "Uma cópia nova precisa de até ";
    private static final String RESTORE_PREFIX = "Espaço: a restauração precisa de até ";

    /** One secondary line per volume of what a new copy needs; one line saying why there is no estimate. */
    public static List<Line> copyNeedLines(Need need) {
        List<Line> lines = new ArrayList<>();
        for (String text : needTexts(need, COPY_PREFIX, "Não foi possível estimar o espaço da cópia (")) {
            lines.add(line(text, Tone.SECONDARY));
        }
        return lines;
    }

    /** The "Espaço: ..." paragraph of the restore dialog: one line per volume, joined by line breaks. */
    public static String restoreParagraph(Need need) {
        return String.join("\n", needTexts(need, RESTORE_PREFIX,
                "Espaço: não foi possível estimar o espaço da restauração ("));
    }

    /**
     * The text of the confirmation of a copy whose free space could not be measured: the result text, then the most the
     * copy may need (the largest of the volumes) and what happens when the space is short.
     */
    public static String unmeasuredCopyConfirmation(String resultText, Need need) {
        String tail = " Se faltar espaço, ela falha e o arquivo incompleto é apagado.";
        if (need == null || need.lines().isEmpty()) {
            return resultText + " O tamanho da cópia não pôde ser estimado." + tail;
        }
        long largest = need.lines().stream().mapToLong(NeedLine::neededBytes).max().orElse(-1);
        return resultText + " A cópia precisa de até " + size(largest) + "." + tail;
    }

    private static List<String> needTexts(Need need, String prefix, String noEstimatePrefix) {
        List<String> texts = new ArrayList<>();
        if (need == null) return texts;
        for (NeedLine l : need.lines()) {
            String head = prefix + size(l.neededBytes()) + " livres no " + diskName(l);
            texts.add(hasReason(l.unmeasuredReason())
                    ? head + "; o espaço livre não pôde ser medido (" + l.unmeasuredReason() + ")."
                    : head + "; há " + size(l.freeBytes()) + ".");
        }
        if (texts.isEmpty() && hasReason(need.unknownReason())) {
            texts.add(noEstimatePrefix + need.unknownReason() + ").");
        }
        return texts;
    }

    /** The disk of a line, named by the first of its places in the order data, copies, destination, temporary. */
    private static String diskName(NeedLine line) {
        for (Disk disk : DISK_NAME_ORDER) {
            if (line.disks().contains(disk)) {
                return switch (disk) {
                    case DATA -> "disco dos dados";
                    case COPIES -> "disco das cópias";
                    case DESTINATION -> "disco de destino";
                    case TEMP -> "disco temporário";
                };
            }
        }
        return "disco dos dados";
    }
}

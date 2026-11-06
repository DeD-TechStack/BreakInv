package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StorageFacts.Disk;
import com.daniel.presentation.view.pages.storage.StorageFacts.Need;
import com.daniel.presentation.view.pages.storage.StorageFacts.NeedLine;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Icon;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Line;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Tone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.facts;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BRK-31 u5 F2, review R1: the spec has two rules for an absent text, and a blank text is not an absent one.
 *
 * <ul>
 *   <li><b>A reason</b> (RF-03 {@code freeDataReason}, RF-06 {@code unmeasuredReason} and {@code unknownReason}): only
 *       {@code null} means "no reason". {@code ""}, {@code " "} and a tab are reasons and come out as they came, between
 *       the parentheses.</li>
 *   <li><b>A message</b> (RF-03 and §4 {@code postponedMessage}, RF-04 {@code readFailed} and {@code saveCrashed}):
 *       {@code null} or {@code ""} means "no message". {@code " "} and a tab are messages and come out as they came.</li>
 * </ul>
 *
 * Each of the eight paths is covered with the five inputs {@code null}, {@code ""}, {@code " "}, {@code "\t"} and an
 * ordinary text, with the whole text, the tone and the icon.
 */
class StoragePresenterNullEmptyBlankTest {

    private static final String TAB = "\t";
    private static final String ORDINARY = "acesso negado";

    private static void assertLine(String text, Tone tone, Icon icon, Line actual) {
        assertEquals(text, actual.text());
        assertEquals(tone, actual.tone());
        assertEquals(icon, actual.icon());
    }

    // ── freeDataReason: RF-03, levelLine in UNMEASURED (a reason: only null leaves the parenthesis out) ──────────

    private static final String UNMEASURED_HEAD = "Não foi possível medir o espaço livre do disco dos dados";
    private static final String UNMEASURED_TAIL = ". As gravações continuam; a cópia manual pede confirmação; a "
            + "restauração e a cópia automática esperam a medida.";

    private static Line unmeasured(String reason) {
        return StoragePresenter.levelLine(facts(b -> {
            b.freeData = -1;
            b.writeLimitUndetermined = true;
            b.freeDataReason = reason;
        }));
    }

    @Test
    void freeDataReason_null() {
        assertLine(UNMEASURED_HEAD + UNMEASURED_TAIL, Tone.WARNING, Icon.ALERT_TRIANGLE, unmeasured(null));
    }

    @Test
    void freeDataReason_empty() {
        assertLine(UNMEASURED_HEAD + " ()" + UNMEASURED_TAIL, Tone.WARNING, Icon.ALERT_TRIANGLE, unmeasured(""));
    }

    @Test
    void freeDataReason_space() {
        assertLine(UNMEASURED_HEAD + " ( )" + UNMEASURED_TAIL, Tone.WARNING, Icon.ALERT_TRIANGLE, unmeasured(" "));
    }

    @Test
    void freeDataReason_tab() {
        assertLine(UNMEASURED_HEAD + " (" + TAB + ")" + UNMEASURED_TAIL, Tone.WARNING, Icon.ALERT_TRIANGLE,
                unmeasured(TAB));
    }

    @Test
    void freeDataReason_ordinaryText() {
        assertLine(UNMEASURED_HEAD + " (" + ORDINARY + ")" + UNMEASURED_TAIL, Tone.WARNING, Icon.ALERT_TRIANGLE,
                unmeasured(ORDINARY));
    }

    // ── unmeasuredReason: RF-06, copyNeedLines (a reason: only null goes to "há Y.") ─────────────────────────────

    private static Need unmeasuredNeed(String reason) {
        return new Need(List.of(new NeedLine(Set.of(Disk.COPIES), mb(40), -1, reason)), null);
    }

    private static void assertCopyLine(String expectedText, String reason) {
        List<Line> lines = StoragePresenter.copyNeedLines(unmeasuredNeed(reason));
        assertEquals(1, lines.size());
        assertLine(expectedText, Tone.SECONDARY, Icon.NONE, lines.get(0));
    }

    private static final String COPY_HEAD = "Uma cópia nova precisa de até 40 MB livres no disco das cópias";
    private static final String COPY_UNMEASURED = "; o espaço livre não pôde ser medido (";

    @Test
    void unmeasuredReason_copyNeedLines_null() {
        assertCopyLine(COPY_HEAD + "; há —.", null);
    }

    @Test
    void unmeasuredReason_copyNeedLines_empty() {
        assertCopyLine(COPY_HEAD + COPY_UNMEASURED + ").", "");
    }

    @Test
    void unmeasuredReason_copyNeedLines_space() {
        assertCopyLine(COPY_HEAD + COPY_UNMEASURED + " ).", " ");
    }

    @Test
    void unmeasuredReason_copyNeedLines_tab() {
        assertCopyLine(COPY_HEAD + COPY_UNMEASURED + TAB + ").", TAB);
    }

    @Test
    void unmeasuredReason_copyNeedLines_ordinaryText() {
        assertCopyLine(COPY_HEAD + COPY_UNMEASURED + ORDINARY + ").", ORDINARY);
    }

    // ── unmeasuredReason: RF-06, restoreParagraph ────────────────────────────────────────────────────────────

    private static final String RESTORE_HEAD = "Espaço: a restauração precisa de até 40 MB livres no disco das cópias";

    @Test
    void unmeasuredReason_restoreParagraph_null() {
        assertEquals(RESTORE_HEAD + "; há —.", StoragePresenter.restoreParagraph(unmeasuredNeed(null)));
    }

    @Test
    void unmeasuredReason_restoreParagraph_empty() {
        assertEquals(RESTORE_HEAD + COPY_UNMEASURED + ").", StoragePresenter.restoreParagraph(unmeasuredNeed("")));
    }

    @Test
    void unmeasuredReason_restoreParagraph_space() {
        assertEquals(RESTORE_HEAD + COPY_UNMEASURED + " ).", StoragePresenter.restoreParagraph(unmeasuredNeed(" ")));
    }

    @Test
    void unmeasuredReason_restoreParagraph_tab() {
        assertEquals(RESTORE_HEAD + COPY_UNMEASURED + TAB + ").",
                StoragePresenter.restoreParagraph(unmeasuredNeed(TAB)));
    }

    @Test
    void unmeasuredReason_restoreParagraph_ordinaryText() {
        assertEquals(RESTORE_HEAD + COPY_UNMEASURED + ORDINARY + ").",
                StoragePresenter.restoreParagraph(unmeasuredNeed(ORDINARY)));
    }

    // ── unknownReason: RF-06, copyNeedLines (a reason: only null, with no line, gives the empty list) ────────────

    private static Need noEstimate(String reason) {
        return new Need(List.of(), reason);
    }

    private static void assertNoEstimateLine(String expectedText, String reason) {
        List<Line> lines = StoragePresenter.copyNeedLines(noEstimate(reason));
        assertEquals(1, lines.size());
        assertLine(expectedText, Tone.SECONDARY, Icon.NONE, lines.get(0));
    }

    @Test
    void unknownReason_copyNeedLines_null() {
        assertTrue(StoragePresenter.copyNeedLines(noEstimate(null)).isEmpty());
    }

    @Test
    void unknownReason_copyNeedLines_empty() {
        assertNoEstimateLine("Não foi possível estimar o espaço da cópia ().", "");
    }

    @Test
    void unknownReason_copyNeedLines_space() {
        assertNoEstimateLine("Não foi possível estimar o espaço da cópia ( ).", " ");
    }

    @Test
    void unknownReason_copyNeedLines_tab() {
        assertNoEstimateLine("Não foi possível estimar o espaço da cópia (" + TAB + ").", TAB);
    }

    @Test
    void unknownReason_copyNeedLines_ordinaryText() {
        assertNoEstimateLine("Não foi possível estimar o espaço da cópia (" + ORDINARY + ").", ORDINARY);
    }

    // ── unknownReason: RF-06, restoreParagraph (only null, with no line, gives the empty text) ───────────────────

    @Test
    void unknownReason_restoreParagraph_null() {
        assertEquals("", StoragePresenter.restoreParagraph(noEstimate(null)));
    }

    @Test
    void unknownReason_restoreParagraph_empty() {
        assertEquals("Espaço: não foi possível estimar o espaço da restauração ().",
                StoragePresenter.restoreParagraph(noEstimate("")));
    }

    @Test
    void unknownReason_restoreParagraph_space() {
        assertEquals("Espaço: não foi possível estimar o espaço da restauração ( ).",
                StoragePresenter.restoreParagraph(noEstimate(" ")));
    }

    @Test
    void unknownReason_restoreParagraph_tab() {
        assertEquals("Espaço: não foi possível estimar o espaço da restauração (" + TAB + ").",
                StoragePresenter.restoreParagraph(noEstimate(TAB)));
    }

    @Test
    void unknownReason_restoreParagraph_ordinaryText() {
        assertEquals("Espaço: não foi possível estimar o espaço da restauração (" + ORDINARY + ").",
                StoragePresenter.restoreParagraph(noEstimate(ORDINARY)));
    }

    // ── postponedMessage: RF-03 and §4 (a message: null or "" adds nothing; anything else is added after one space) ──

    private static final String NORMAL_TEXT = "Espaço suficiente: há 50 GB livres no disco dos dados.";

    private static Line normalWithPostponed(String message) {
        return StoragePresenter.levelLine(facts(b -> b.postponedMessage = message));
    }

    @Test
    void postponedMessage_null() {
        assertLine(NORMAL_TEXT, Tone.SECONDARY, Icon.NONE, normalWithPostponed(null));
    }

    @Test
    void postponedMessage_empty() {
        assertLine(NORMAL_TEXT, Tone.SECONDARY, Icon.NONE, normalWithPostponed(""));
    }

    @Test
    void postponedMessage_space() {
        assertLine(NORMAL_TEXT + " " + " ", Tone.SECONDARY, Icon.NONE, normalWithPostponed(" "));
    }

    @Test
    void postponedMessage_tab() {
        assertLine(NORMAL_TEXT + " " + TAB, Tone.SECONDARY, Icon.NONE, normalWithPostponed(TAB));
    }

    @Test
    void postponedMessage_ordinaryText() {
        assertLine(NORMAL_TEXT + " " + ORDINARY, Tone.SECONDARY, Icon.NONE, normalWithPostponed(ORDINARY));
    }

    // ── readFailed: RF-04 (a message: null or "" has no ": <message>") ──────────────────────────────────────────

    @Test
    void readFailed_null() {
        assertLine("Não foi possível ler o espaço dos dados.", Tone.DANGER, Icon.NONE,
                StoragePresenter.readFailed(null));
    }

    @Test
    void readFailed_empty() {
        assertLine("Não foi possível ler o espaço dos dados.", Tone.DANGER, Icon.NONE,
                StoragePresenter.readFailed(""));
    }

    @Test
    void readFailed_space() {
        assertLine("Não foi possível ler o espaço dos dados:  .", Tone.DANGER, Icon.NONE,
                StoragePresenter.readFailed(" "));
    }

    @Test
    void readFailed_tab() {
        assertLine("Não foi possível ler o espaço dos dados: " + TAB + ".", Tone.DANGER, Icon.NONE,
                StoragePresenter.readFailed(TAB));
    }

    @Test
    void readFailed_ordinaryText() {
        assertLine("Não foi possível ler o espaço dos dados: " + ORDINARY + ".", Tone.DANGER, Icon.NONE,
                StoragePresenter.readFailed(ORDINARY));
    }

    // ── saveCrashed: RF-04 (a message: null or "" has no parenthesis) ───────────────────────────────────────────

    private static final String SAVE_CRASHED_TAIL = ". Os limites anteriores continuam valendo.";

    @Test
    void saveCrashed_null() {
        assertLine("Não foi possível salvar os limites" + SAVE_CRASHED_TAIL, Tone.DANGER, Icon.NONE,
                StoragePresenter.saveCrashed(null));
    }

    @Test
    void saveCrashed_empty() {
        assertLine("Não foi possível salvar os limites" + SAVE_CRASHED_TAIL, Tone.DANGER, Icon.NONE,
                StoragePresenter.saveCrashed(""));
    }

    @Test
    void saveCrashed_space() {
        assertLine("Não foi possível salvar os limites ( )" + SAVE_CRASHED_TAIL, Tone.DANGER, Icon.NONE,
                StoragePresenter.saveCrashed(" "));
    }

    @Test
    void saveCrashed_tab() {
        assertLine("Não foi possível salvar os limites (" + TAB + ")" + SAVE_CRASHED_TAIL, Tone.DANGER, Icon.NONE,
                StoragePresenter.saveCrashed(TAB));
    }

    @Test
    void saveCrashed_ordinaryText() {
        assertLine("Não foi possível salvar os limites (" + ORDINARY + ")" + SAVE_CRASHED_TAIL, Tone.DANGER,
                Icon.NONE, StoragePresenter.saveCrashed(ORDINARY));
    }
}

package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StorageFacts.Disk;
import com.daniel.presentation.view.pages.storage.StorageFacts.Need;
import com.daniel.presentation.view.pages.storage.StorageFacts.NeedLine;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Icon;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Line;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Tone;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BRK-31 u5 F2, RF-06 and RF-08 case 5: the estimate texts of the copy, the restore and the unmeasured copy. */
class StoragePresenterNeedTextsTest {

    private static final String UNMEASURED_RESULT = "Não foi possível medir o espaço livre do disco das cópias.";

    private static Need need(NeedLine... lines) {
        return new Need(List.of(lines), null);
    }

    private static NeedLine line(Set<Disk> disks, long needed, long free) {
        return new NeedLine(disks, needed, free, null);
    }

    private static List<String> texts(List<Line> lines) {
        return lines.stream().map(Line::text).toList();
    }

    // ── copyNeedLines ──────────────────────────────────────────────────────

    @Test
    void copyWithAMeasure() {
        List<Line> lines = StoragePresenter.copyNeedLines(need(line(Set.of(Disk.COPIES), mb(40), mb(90))));
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco das cópias; há 90 MB."), texts(lines));
        assertEquals(Tone.SECONDARY, lines.get(0).tone());
        assertEquals(Icon.NONE, lines.get(0).icon());
    }

    @Test
    void copyWithoutAMeasureOnOneLine() {
        Need n = need(new NeedLine(Set.of(Disk.COPIES), mb(40), -1, "falha ao ler o volume"));
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco das cópias; o espaço livre não "
                + "pôde ser medido (falha ao ler o volume)."), texts(StoragePresenter.copyNeedLines(n)));
    }

    @Test
    void copyWithoutAnEstimate() {
        List<Line> lines = StoragePresenter.copyNeedLines(new Need(List.of(), "pasta ilegível"));
        assertEquals(List.of("Não foi possível estimar o espaço da cópia (pasta ilegível)."), texts(lines));
        assertEquals(Tone.SECONDARY, lines.get(0).tone());
        assertEquals(Icon.NONE, lines.get(0).icon());
    }

    @Test
    void copyWithTwoLinesOfDifferentDisks() {
        Need n = need(line(Set.of(Disk.DATA), mb(40), mb(90)), line(Set.of(Disk.COPIES), mb(70), mb(500)));
        List<Line> lines = StoragePresenter.copyNeedLines(n);
        assertEquals(List.of(
                "Uma cópia nova precisa de até 40 MB livres no disco dos dados; há 90 MB.",
                "Uma cópia nova precisa de até 70 MB livres no disco das cópias; há 500 MB."), texts(lines));
        assertTrue(lines.stream().allMatch(l -> l.tone() == Tone.SECONDARY && l.icon() == Icon.NONE));
    }

    @Test
    void copyWithCopiesAndTempOnTheSameVolumeNamesTheCopiesDisk() {
        Need n = need(line(EnumSet.of(Disk.COPIES, Disk.TEMP), mb(40), mb(90)));
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco das cópias; há 90 MB."),
                texts(StoragePresenter.copyNeedLines(n)));
    }

    @Test
    void copyToADestinationNamesTheDestinationDisk() {
        Need n = need(line(Set.of(Disk.DESTINATION), mb(40), mb(90)));
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco de destino; há 90 MB."),
                texts(StoragePresenter.copyNeedLines(n)));
    }

    @Test
    void theTempDiskAloneAndTheOrderOfTheDiskNames() {
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco temporário; há 90 MB."),
                texts(StoragePresenter.copyNeedLines(need(line(Set.of(Disk.TEMP), mb(40), mb(90))))));
        // DATA, COPIES, DESTINATION, TEMP: the first of the set in this order names the disk
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco dos dados; há 90 MB."),
                texts(StoragePresenter.copyNeedLines(need(line(EnumSet.allOf(Disk.class), mb(40), mb(90))))));
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco de destino; há 90 MB."),
                texts(StoragePresenter.copyNeedLines(
                        need(line(EnumSet.of(Disk.TEMP, Disk.DESTINATION), mb(40), mb(90))))));
    }

    @Test
    void theUnknownReasonIsIgnoredWhenThereAreLines() {
        Need n = new Need(List.of(line(Set.of(Disk.COPIES), mb(40), mb(90))), "pasta ilegível");
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco das cópias; há 90 MB."),
                texts(StoragePresenter.copyNeedLines(n)));
    }

    @Test
    void anUnknownFreeSizeWithoutAReasonIsADash() {
        assertEquals(List.of("Uma cópia nova precisa de até 40 MB livres no disco das cópias; há —."),
                texts(StoragePresenter.copyNeedLines(need(line(Set.of(Disk.COPIES), mb(40), -1)))));
    }

    @Test
    void copyWithNothingToSayIsEmpty() {
        assertTrue(StoragePresenter.copyNeedLines(new Need(List.of(), null)).isEmpty());
    }

    // ── restoreParagraph ───────────────────────────────────────────────────

    @Test
    void restoreWithAMeasure() {
        assertEquals("Espaço: a restauração precisa de até 40 MB livres no disco dos dados; há 90 MB.",
                StoragePresenter.restoreParagraph(need(line(Set.of(Disk.DATA), mb(40), mb(90)))));
    }

    @Test
    void restoreWithoutAMeasureOnOneLine() {
        Need n = need(new NeedLine(Set.of(Disk.DATA), mb(40), -1, "volume sumiu"));
        assertEquals("Espaço: a restauração precisa de até 40 MB livres no disco dos dados; o espaço livre não pôde "
                + "ser medido (volume sumiu).", StoragePresenter.restoreParagraph(n));
    }

    @Test
    void restoreWithoutAnEstimate() {
        assertEquals("Espaço: não foi possível estimar o espaço da restauração (cópia ilegível).",
                StoragePresenter.restoreParagraph(new Need(List.of(), "cópia ilegível")));
    }

    @Test
    void restoreWithTwoDisksJoinsTheLinesWithALineBreak() {
        Need n = need(line(Set.of(Disk.DATA), mb(40), mb(90)), line(Set.of(Disk.TEMP), mb(80), mb(120)));
        assertEquals("Espaço: a restauração precisa de até 40 MB livres no disco dos dados; há 90 MB.\n"
                + "Espaço: a restauração precisa de até 80 MB livres no disco temporário; há 120 MB.",
                StoragePresenter.restoreParagraph(n));
    }

    @Test
    void restoreWithDataAndTempOnTheSameVolumeNamesTheDataDisk() {
        assertEquals("Espaço: a restauração precisa de até 40 MB livres no disco dos dados; há 90 MB.",
                StoragePresenter.restoreParagraph(need(line(EnumSet.of(Disk.DATA, Disk.TEMP), mb(40), mb(90)))));
    }

    @Test
    void restoreToADestinationDisk() {
        assertEquals("Espaço: a restauração precisa de até 40 MB livres no disco de destino; há 90 MB.",
                StoragePresenter.restoreParagraph(need(line(Set.of(Disk.DESTINATION), mb(40), mb(90)))));
    }

    @Test
    void restoreWithNothingToSayIsEmpty() {
        assertEquals("", StoragePresenter.restoreParagraph(new Need(List.of(), null)));
    }

    // ── unmeasuredCopyConfirmation ─────────────────────────────────────────

    @Test
    void confirmationNamesTheLargestNeed() {
        Need n = need(line(Set.of(Disk.DATA), mb(40), mb(90)), line(Set.of(Disk.COPIES), mb(70), mb(500)));
        assertEquals(UNMEASURED_RESULT + " A cópia precisa de até 70 MB. Se faltar espaço, ela falha e o arquivo "
                + "incompleto é apagado.", StoragePresenter.unmeasuredCopyConfirmation(UNMEASURED_RESULT, n));
    }

    @Test
    void confirmationWithTheLargestNeedFirst() {
        Need n = need(line(Set.of(Disk.DATA), mb(70), mb(90)), line(Set.of(Disk.COPIES), mb(40), mb(500)));
        assertEquals(UNMEASURED_RESULT + " A cópia precisa de até 70 MB. Se faltar espaço, ela falha e o arquivo "
                + "incompleto é apagado.", StoragePresenter.unmeasuredCopyConfirmation(UNMEASURED_RESULT, n));
    }

    @Test
    void confirmationForADestinationCopy() {
        Need n = need(new NeedLine(Set.of(Disk.DESTINATION, Disk.TEMP), mb(25), -1, "volume sumiu"));
        assertEquals("Não foi possível medir o espaço do destino. A cópia precisa de até 25 MB. Se faltar espaço, ela "
                + "falha e o arquivo incompleto é apagado.",
                StoragePresenter.unmeasuredCopyConfirmation("Não foi possível medir o espaço do destino.", n));
    }

    @Test
    void confirmationWithoutAnEstimate() {
        assertEquals(UNMEASURED_RESULT + " O tamanho da cópia não pôde ser estimado. Se faltar espaço, ela falha e o "
                + "arquivo incompleto é apagado.",
                StoragePresenter.unmeasuredCopyConfirmation(UNMEASURED_RESULT, new Need(List.of(), "pasta ilegível")));
    }
}

package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StorageFacts.Group;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Icon;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Line;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Row;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Tone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.facts;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.gb;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.kb;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.normal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** BRK-31 u5 F2, RF-05 and RF-08 case 4: the measure rows, the measuring rows and the help line. */
class StoragePresenterMeasureRowsTest {

    private static final String DATA = "Dados (breakinv.db)";
    private static final String TEMP_FILES = "Arquivos temporários do banco";
    private static final String COPIES_FOLDER = "Pasta das cópias";
    private static final String TOTAL = "Total da pasta de dados";
    private static final String FREE_DATA = "Livre no disco dos dados";
    private static final String FREE_COPIES = "Livre no disco das cópias";
    private static final String FREE_TEMP = "Livre no disco temporário";

    private static String value(List<Row> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + label + " in " + rows)).value();
    }

    private static String folder(Group managed, Group damaged, Group others) {
        return value(StoragePresenter.measureRows(facts(b -> {
            b.managed = managed;
            b.damaged = damaged;
            b.others = others;
        })), COPIES_FOLDER);
    }

    private static List<String> labels(List<Row> rows) {
        return rows.stream().map(Row::label).toList();
    }

    // ── how many rows, by volume ───────────────────────────────────────────

    @Test
    void sameVolumeIsFiveRowsInOrder() {
        assertEquals(List.of(
                new Row(DATA, "20 MB"),
                new Row(TEMP_FILES, "nenhum"),
                new Row(COPIES_FOLDER, "3 cópias (3 MB) · 0 danificadas · 0 outros arquivos"),
                new Row(TOTAL, "23 MB"),
                new Row(FREE_DATA, "50 GB")), StoragePresenter.measureRows(normal()));
    }

    @Test
    void onlyTheCopiesOnAnotherVolumeIsSixRowsWithTheCopiesLabel() {
        List<Row> rows = StoragePresenter.measureRows(facts(b -> {
            b.copiesOnDataDisk = false;
            b.freeCopies = gb(200);
            b.freeTemp = gb(7);
        }));
        assertEquals(List.of(DATA, TEMP_FILES, COPIES_FOLDER, TOTAL, FREE_DATA, FREE_COPIES), labels(rows));
        assertEquals("200 GB", value(rows, FREE_COPIES));
    }

    @Test
    void onlyTheTempOnAnotherVolumeIsSixRowsWithTheTempLabel() {
        List<Row> rows = StoragePresenter.measureRows(facts(b -> {
            b.tempOnDataDisk = false;
            b.freeCopies = gb(200);
            b.freeTemp = gb(7);
        }));
        assertEquals(List.of(DATA, TEMP_FILES, COPIES_FOLDER, TOTAL, FREE_DATA, FREE_TEMP), labels(rows));
        assertEquals("7 GB", value(rows, FREE_TEMP));
    }

    @Test
    void bothOnOtherVolumesIsSevenRowsCopiesThenTemp() {
        List<Row> rows = StoragePresenter.measureRows(facts(b -> {
            b.copiesOnDataDisk = false;
            b.tempOnDataDisk = false;
            b.freeCopies = gb(200);
            b.freeTemp = gb(7);
        }));
        assertEquals(List.of(DATA, TEMP_FILES, COPIES_FOLDER, TOTAL, FREE_DATA, FREE_COPIES, FREE_TEMP), labels(rows));
        assertEquals("200 GB", value(rows, FREE_COPIES));
        assertEquals("7 GB", value(rows, FREE_TEMP));
    }

    @Test
    void theSameVolumeNeverShowsTheCopiesOrTheTempRow() {
        List<Row> rows = StoragePresenter.measureRows(normal());
        assertEquals(5, rows.size());
        assertFalse(labels(rows).contains(FREE_COPIES));
        assertFalse(labels(rows).contains(FREE_TEMP));
    }

    // ── the measuring rows and the help line ───────────────────────────────

    @Test
    void measuringRowsAreTheFiveSameVolumeRowsWithDashes() {
        assertEquals(List.of(
                new Row(DATA, "—"),
                new Row(TEMP_FILES, "—"),
                new Row(COPIES_FOLDER, "—"),
                new Row(TOTAL, "—"),
                new Row(FREE_DATA, "—")), StoragePresenter.measuringRows());
    }

    @Test
    void helpLine() {
        Line line = StoragePresenter.helpLine();
        assertEquals("MB e GB aqui são binários (1 MB = 1.048.576 bytes), como no Windows.", line.text());
        assertEquals(Tone.HELPER, line.tone());
        assertEquals(Icon.NONE, line.icon());
    }

    // ── the temporary files ────────────────────────────────────────────────

    @Test
    void zeroTemporaryBytesIsNenhum() {
        assertEquals("nenhum", value(StoragePresenter.measureRows(facts(b -> b.auxiliaryBytes = 0)), TEMP_FILES));
    }

    @Test
    void temporaryBytesAreShownAsASize() {
        assertEquals("1,5 MB", value(StoragePresenter.measureRows(facts(b -> b.auxiliaryBytes = mb(1) + kb(512))),
                TEMP_FILES));
        assertEquals("1 byte", value(StoragePresenter.measureRows(facts(b -> b.auxiliaryBytes = 1)), TEMP_FILES));
    }

    // ── the copies folder, group by group ──────────────────────────────────

    @Test
    void managedCopiesZeroOneAndTwo() {
        Group none = new Group(0, 0, 0);
        assertEquals("0 cópias · 0 danificadas · 0 outros arquivos", folder(none, none, none));
        assertEquals("1 cópia (1,5 MB) · 0 danificadas · 0 outros arquivos",
                folder(new Group(1, mb(1) + kb(512), 0), none, none));
        assertEquals("2 cópias (3 MB) · 0 danificadas · 0 outros arquivos", folder(new Group(2, mb(3), 0), none, none));
    }

    @Test
    void damagedCopiesZeroOneAndTwo() {
        Group none = new Group(0, 0, 0);
        assertEquals("0 cópias · 0 danificadas · 0 outros arquivos", folder(none, none, none));
        assertEquals("0 cópias · 1 danificada (10 KB) · 0 outros arquivos",
                folder(none, new Group(1, kb(10), 0), none));
        assertEquals("0 cópias · 2 danificadas (20 KB) · 0 outros arquivos",
                folder(none, new Group(2, kb(20), 0), none));
    }

    @Test
    void otherFilesZeroOneAndTwo() {
        Group none = new Group(0, 0, 0);
        assertEquals("0 cópias · 0 danificadas · 0 outros arquivos", folder(none, none, none));
        assertEquals("0 cópias · 0 danificadas · 1 outro arquivo (2 KB; nunca apagado pelo BreakInv)",
                folder(none, none, new Group(1, kb(2), 0)));
        assertEquals("0 cópias · 0 danificadas · 2 outros arquivos (3 KB; nunca apagados pelo BreakInv)",
                folder(none, none, new Group(2, kb(3), 0)));
    }

    @Test
    void allThreeGroupsTogether() {
        assertEquals("2 cópias (3 MB) · 1 danificada (10 KB) · 2 outros arquivos (3 KB; nunca apagados pelo BreakInv)",
                folder(new Group(2, mb(3), 0), new Group(1, kb(10), 0), new Group(2, kb(3), 0)));
    }

    @Test
    void countsUseTheThousandsSeparatorOfTheScreens() {
        Group none = new Group(0, 0, 0);
        assertEquals("1.500 cópias (1 GB) · 0 danificadas · 0 outros arquivos",
                folder(new Group(1500, gb(1), 0), none, none));
    }

    @Test
    void unknownSizesAreCountedInsideTheParenthesis() {
        Group none = new Group(0, 0, 0);
        assertEquals("3 cópias (3 MB, 1 sem tamanho lido) · 0 danificadas · 0 outros arquivos",
                folder(new Group(3, mb(3), 1), none, none));
        assertEquals("0 cópias · 1 danificada (10 KB, 1 sem tamanho lido) · 0 outros arquivos",
                folder(none, new Group(1, kb(10), 1), none));
        assertEquals("0 cópias · 0 danificadas · 2 outros arquivos (2 KB, 2 sem tamanho lido; nunca apagados pelo BreakInv)",
                folder(none, none, new Group(2, kb(2), 2)));
    }

    // ── the total ──────────────────────────────────────────────────────────

    @Test
    void anIncompleteTotalIsAMinimum() {
        assertEquals("23 MB (mínimo: alguns tamanhos não puderam ser lidos)",
                value(StoragePresenter.measureRows(facts(b -> b.complete = false)), TOTAL));
        assertEquals("23 MB", value(StoragePresenter.measureRows(facts(b -> b.complete = true)), TOTAL));
    }

    // ── unknown bytes ──────────────────────────────────────────────────────

    @Test
    void everyUnknownByteCountIsADash() {
        List<Row> rows = StoragePresenter.measureRows(facts(b -> {
            b.databaseBytes = -1;
            b.auxiliaryBytes = -1;
            b.managed = new Group(3, -1, 0);
            b.knownTotalBytes = -1;
            b.freeData = -1;
            b.copiesOnDataDisk = false;
            b.tempOnDataDisk = false;
            b.freeCopies = -1;
            b.freeTemp = -1;
        }));
        assertEquals(List.of(
                new Row(DATA, "—"),
                new Row(TEMP_FILES, "—"),
                new Row(COPIES_FOLDER, "3 cópias (—) · 0 danificadas · 0 outros arquivos"),
                new Row(TOTAL, "—"),
                new Row(FREE_DATA, "—"),
                new Row(FREE_COPIES, "—"),
                new Row(FREE_TEMP, "—")), rows);
    }
}

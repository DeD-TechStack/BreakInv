package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StorageFacts.Disk;
import com.daniel.presentation.view.pages.storage.StorageFacts.Group;
import com.daniel.presentation.view.pages.storage.StorageFacts.Need;
import com.daniel.presentation.view.pages.storage.StorageFacts.NeedLine;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Line;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.critical;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.dbUnknown;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.dbWarning;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.facts;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.normal;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.reserve;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.unmeasured;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BRK-31 u5 F2, RF-08: the table "method → exact text" for the reviewer. One example of each method and constant of
 * {@link StoragePresenter}, with the example facts of the spec. Each row is printed as
 * {@code U5F2-TEXT<TAB>method<TAB>text} (a line break inside a text is written as the two characters {@code \n} so
 * that one row stays one line) and checked with {@code assertEquals}.
 */
class StoragePresenterTextTableTest {

    private final Map<String, String> expected = new LinkedHashMap<>();
    private final Map<String, String> actual = new LinkedHashMap<>();

    private void check(String method, String expectedText, String actualText) {
        expected.put(method, expectedText);
        actual.put(method, actualText);
    }

    private static String line(Line l) {
        return l.text() + "  [" + l.tone() + ", " + l.icon() + "]";
    }

    private static String lineExpected(String text, StoragePresenter.Tone tone, StoragePresenter.Icon icon) {
        return text + "  [" + tone + ", " + icon + "]";
    }

    private static String rows(List<Row> rows) {
        List<String> parts = new ArrayList<>();
        for (Row r : rows) parts.add(r.label() + " = " + r.value());
        return String.join(" | ", parts);
    }

    private static String texts(List<Line> lines) {
        List<String> parts = new ArrayList<>();
        for (Line l : lines) parts.add(l.text());
        return String.join(" | ", parts);
    }

    @Test
    void everyMethodAndConstantWithItsExactText() {
        // fixed texts
        check("SECTION_TITLE", "Dados e armazenamento", StoragePresenter.SECTION_TITLE);
        check("ABOUT_TITLE", "Sobre o aplicativo", StoragePresenter.ABOUT_TITLE);
        check("BLOCK_TITLE", "Espaço", StoragePresenter.BLOCK_TITLE);
        check("MEASURE_AGAIN", "Medir de novo", StoragePresenter.MEASURE_AGAIN);
        check("SAVE_LIMITS", "Salvar limites", StoragePresenter.SAVE_LIMITS);
        check("CREATE_ANYWAY", "Criar mesmo assim", StoragePresenter.CREATE_ANYWAY);
        check("WARN_LABEL", "Avisar quando os dados passarem de", StoragePresenter.WARN_LABEL);
        check("WARN_HELP", "É só um aviso; nada muda no uso.", StoragePresenter.WARN_HELP);
        check("FLOOR_LABEL", "Margem da reserva", StoragePresenter.FLOOR_LABEL);
        check("FLOOR_HELP", "A reserva é 2 × o tamanho dos dados + esta margem.", StoragePresenter.FLOOR_HELP);
        check("WRITE_LABEL", "Limite de gravação", StoragePresenter.WRITE_LABEL);
        check("WRITE_HELP", "Abaixo deste espaço livre, novas gravações são recusadas.", StoragePresenter.WRITE_HELP);
        check("UNIT", "MB", StoragePresenter.UNIT);
        check("LIMITS_NOTE", "Os limites ficam neste computador, fora dos dados: restaurar uma cópia não os muda, e "
                + "eles podem ser salvos mesmo quando os dados não aceitam gravações.", StoragePresenter.LIMITS_NOTE);

        // levelLine, one per state
        check("levelLine(UNMEASURED)", lineExpected("Não foi possível medir o espaço livre do disco dos dados (falha ao "
                        + "ler o volume). As gravações continuam; a cópia manual pede confirmação; a restauração e a "
                        + "cópia automática esperam a medida. Os dados também passaram de 100 MB (estão com 150 MB).",
                        StoragePresenter.Tone.WARNING, StoragePresenter.Icon.ALERT_TRIANGLE),
                line(StoragePresenter.levelLine(unmeasured())));
        check("levelLine(CRITICAL)", lineExpected("Espaço livre abaixo do limite de gravação: há 40 MB e o mínimo é 64 MB. "
                        + "Novas gravações estão recusadas até liberar pelo menos 24 MB. Leitura, exportação e cópia "
                        + "para outro disco continuam. Os dados também passaram de 100 MB (estão com 150 MB).",
                        StoragePresenter.Tone.DANGER, StoragePresenter.Icon.ALERT_OCTAGON),
                line(StoragePresenter.levelLine(critical())));
        check("levelLine(DB_UNKNOWN)", lineExpected("Não foi possível ler o tamanho dos dados; o aviso e a reserva ficam "
                        + "sem conta até a próxima medida.",
                        StoragePresenter.Tone.WARNING, StoragePresenter.Icon.ALERT_TRIANGLE),
                line(StoragePresenter.levelLine(dbUnknown())));
        check("levelLine(RESERVE)", lineExpected("Pouco espaço para restaurar: há 90 MB livres e a reserva pede 104 MB "
                        + "(2 × dados + margem). As gravações continuam; a cópia automática pode ser adiada.",
                        StoragePresenter.Tone.WARNING, StoragePresenter.Icon.ALERT_TRIANGLE),
                line(StoragePresenter.levelLine(reserve())));
        check("levelLine(DB_WARNING)", lineExpected("Os dados passaram de 100 MB (estão com 150 MB). Nada muda no uso; é "
                        + "um sinal de crescimento.",
                        StoragePresenter.Tone.WARNING, StoragePresenter.Icon.ALERT_TRIANGLE),
                line(StoragePresenter.levelLine(dbWarning())));
        check("levelLine(NORMAL)", lineExpected("Espaço suficiente: há 50 GB livres no disco dos dados.",
                        StoragePresenter.Tone.SECONDARY, StoragePresenter.Icon.NONE),
                line(StoragePresenter.levelLine(normal())));
        check("levelLine(NORMAL + postponed)", lineExpected("Espaço suficiente: há 50 GB livres no disco dos dados. "
                        + "Cópia automática não feita: falta espaço. A cópia automática foi adiada…",
                        StoragePresenter.Tone.SECONDARY, StoragePresenter.Icon.NONE),
                line(StoragePresenter.levelLine(facts(b -> b.postponedMessage = "Cópia automática não feita: falta "
                        + "espaço. A cópia automática foi adiada…"))));

        // transient lines
        check("measuring()", lineExpected("Medindo o espaço dos dados…", StoragePresenter.Tone.HELPER,
                StoragePresenter.Icon.NONE), line(StoragePresenter.measuring()));
        check("readFailed(\"acesso negado\")", lineExpected("Não foi possível ler o espaço dos dados: acesso negado.",
                StoragePresenter.Tone.DANGER, StoragePresenter.Icon.NONE),
                line(StoragePresenter.readFailed("acesso negado")));
        check("readFailed(null)", lineExpected("Não foi possível ler o espaço dos dados.",
                StoragePresenter.Tone.DANGER, StoragePresenter.Icon.NONE), line(StoragePresenter.readFailed(null)));
        check("unavailable()", lineExpected("Espaço dos dados indisponível nesta janela.",
                StoragePresenter.Tone.SECONDARY, StoragePresenter.Icon.NONE), line(StoragePresenter.unavailable()));
        check("saving()", lineExpected("Salvando os limites…", StoragePresenter.Tone.HELPER,
                StoragePresenter.Icon.NONE), line(StoragePresenter.saving()));
        check("saved()", lineExpected("Limites salvos.", StoragePresenter.Tone.SECONDARY,
                StoragePresenter.Icon.NONE), line(StoragePresenter.saved()));
        check("saveFailed(reason, 100 MB, 64 MB, 64 MB)", lineExpected("Não foi possível salvar os limites nas "
                        + "preferências deste computador (BackingStoreException). Os limites anteriores continuam "
                        + "valendo. Valendo agora: aviso 100 MB · margem 64 MB · limite 64 MB.",
                        StoragePresenter.Tone.DANGER, StoragePresenter.Icon.NONE),
                line(StoragePresenter.saveFailed("Não foi possível salvar os limites nas preferências deste "
                        + "computador (BackingStoreException). Os limites anteriores continuam valendo.",
                        mb(100), mb(64), mb(64))));
        check("saveCrashed(\"disco cheio\")", lineExpected("Não foi possível salvar os limites (disco cheio). Os "
                        + "limites anteriores continuam valendo.", StoragePresenter.Tone.DANGER,
                        StoragePresenter.Icon.NONE), line(StoragePresenter.saveCrashed("disco cheio")));
        check("saveCrashed(null)", lineExpected("Não foi possível salvar os limites. Os limites anteriores continuam "
                        + "valendo.", StoragePresenter.Tone.DANGER, StoragePresenter.Icon.NONE),
                line(StoragePresenter.saveCrashed(null)));
        check("rejected(problem)", lineExpected("O limite de gravação precisa ficar entre 16 MB e 16 GB.",
                StoragePresenter.Tone.DANGER, StoragePresenter.Icon.NONE),
                line(StoragePresenter.rejected("O limite de gravação precisa ficar entre 16 MB e 16 GB.")));
        check("notANumber()", lineExpected("Digite um número inteiro de MB, sem pontos.",
                StoragePresenter.Tone.DANGER, StoragePresenter.Icon.NONE), line(StoragePresenter.notANumber()));
        check("corrected(2 items)", "Margem da reserva: valor salvo inválido; usando o padrão. | Limite de gravação: "
                        + "preferências indisponíveis; usando o padrão.",
                texts(StoragePresenter.corrected(List.of(
                        "margem da reserva: valor salvo inválido; usando o padrão",
                        "limite de gravação: preferências indisponíveis; usando o padrão"))));

        // measures
        check("measureRows(same volume)", "Dados (breakinv.db) = 20 MB | Arquivos temporários do banco = nenhum | "
                        + "Pasta das cópias = 3 cópias (3 MB) · 0 danificadas · 0 outros arquivos | "
                        + "Total da pasta de dados = 23 MB | Livre no disco dos dados = 50 GB",
                rows(StoragePresenter.measureRows(normal())));
        check("measureRows(both on other volumes, 1 group of each)", "Dados (breakinv.db) = 20 MB | Arquivos "
                        + "temporários do banco = 1,5 MB | Pasta das cópias = 1 cópia (3 MB) · 1 danificada (10 KB, 1 "
                        + "sem tamanho lido) · 2 outros arquivos (3 KB; nunca apagados pelo BreakInv) | Total da "
                        + "pasta de dados = 23 MB (mínimo: alguns tamanhos não puderam ser lidos) | Livre no disco "
                        + "dos dados = 50 GB | Livre no disco das cópias = 200 GB | Livre no disco temporário = 7 GB",
                rows(StoragePresenter.measureRows(facts(b -> {
                    b.auxiliaryBytes = mb(1) + 512 * 1024L;
                    b.managed = new Group(1, mb(3), 0);
                    b.damaged = new Group(1, 10 * 1024L, 1);
                    b.others = new Group(2, 3 * 1024L, 0);
                    b.complete = false;
                    b.copiesOnDataDisk = false;
                    b.tempOnDataDisk = false;
                    b.freeCopies = 200L * (1L << 30);
                    b.freeTemp = 7L * (1L << 30);
                }))));
        check("measuringRows()", "Dados (breakinv.db) = — | Arquivos temporários do banco = — | Pasta das cópias = — | "
                        + "Total da pasta de dados = — | Livre no disco dos dados = —",
                rows(StoragePresenter.measuringRows()));
        check("helpLine()", lineExpected("MB e GB aqui são binários (1 MB = 1.048.576 bytes), como no Windows.",
                StoragePresenter.Tone.HELPER, StoragePresenter.Icon.NONE), line(StoragePresenter.helpLine()));

        // estimates
        Need copyNeed = new Need(List.of(
                new NeedLine(Set.of(Disk.DATA), mb(40), mb(90), null),
                new NeedLine(Set.of(Disk.COPIES), mb(70), -1, "falha ao ler o volume")), null);
        Need noEstimate = new Need(List.of(), "pasta ilegível");
        check("copyNeedLines(2 lines, 1 unmeasured)", "Uma cópia nova precisa de até 40 MB livres no disco dos dados; "
                        + "há 90 MB. | Uma cópia nova precisa de até 70 MB livres no disco das cópias; o espaço livre "
                        + "não pôde ser medido (falha ao ler o volume).",
                texts(StoragePresenter.copyNeedLines(copyNeed)));
        check("copyNeedLines(no estimate)", "Não foi possível estimar o espaço da cópia (pasta ilegível).",
                texts(StoragePresenter.copyNeedLines(noEstimate)));
        check("restoreParagraph(2 lines, 1 unmeasured)", "Espaço: a restauração precisa de até 40 MB livres no disco "
                        + "dos dados; há 90 MB.\\nEspaço: a restauração precisa de até 70 MB livres no disco das "
                        + "cópias; o espaço livre não pôde ser medido (falha ao ler o volume).",
                StoragePresenter.restoreParagraph(copyNeed).replace("\n", "\\n"));
        check("restoreParagraph(no estimate)", "Espaço: não foi possível estimar o espaço da restauração (pasta "
                + "ilegível).", StoragePresenter.restoreParagraph(noEstimate));
        check("unmeasuredCopyConfirmation(result, 2 lines)", "Não foi possível medir o espaço livre do disco das "
                        + "cópias. A cópia precisa de até 70 MB. Se faltar espaço, ela falha e o arquivo incompleto é "
                        + "apagado.",
                StoragePresenter.unmeasuredCopyConfirmation("Não foi possível medir o espaço livre do disco das "
                        + "cópias.", copyNeed));
        check("unmeasuredCopyConfirmation(result, no estimate)", "Não foi possível medir o espaço livre do disco das "
                        + "cópias. O tamanho da cópia não pôde ser estimado. Se faltar espaço, ela falha e o arquivo "
                        + "incompleto é apagado.",
                StoragePresenter.unmeasuredCopyConfirmation("Não foi possível medir o espaço livre do disco das "
                        + "cópias.", noEstimate));

        for (Map.Entry<String, String> row : actual.entrySet()) {
            System.out.println("U5F2-TEXT\t" + row.getKey() + "\t" + row.getValue());
        }
        for (Map.Entry<String, String> row : expected.entrySet()) {
            assertEquals(row.getValue(), actual.get(row.getKey()), row.getKey());
        }
    }
}

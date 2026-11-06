package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StoragePresenter.Icon;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Line;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Tone;
import org.junit.jupiter.api.Test;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.critical;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.dbUnknown;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.dbWarning;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.facts;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.gb;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.normal;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.reserve;
import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.unmeasured;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** BRK-31 u5 F2, RF-03 and RF-08 case 2: the one line of the current level, word by word. */
class StoragePresenterLevelLineTest {

    static final String UNMEASURED_TAIL = " As gravações continuam; a cópia manual pede confirmação; a restauração e a "
            + "cópia automática esperam a medida.";
    static final String CRITICAL_TEXT = "Espaço livre abaixo do limite de gravação: há 40 MB e o mínimo é 64 MB. Novas "
            + "gravações estão recusadas até liberar pelo menos 24 MB. Leitura, exportação e cópia para outro disco "
            + "continuam.";
    static final String ALSO_WARN = " Os dados também passaram de 100 MB (estão com 150 MB).";
    static final String POSTPONED = "Cópia automática não feita: falta espaço. A cópia automática foi adiada…";

    private static void assertLine(String text, Tone tone, Icon icon, Line actual) {
        assertEquals(text, actual.text());
        assertEquals(tone, actual.tone());
        assertEquals(icon, actual.icon());
    }

    // ── the six states ─────────────────────────────────────────────────────

    @Test
    void unmeasuredWithTheReasonInParentheses() {
        StorageFacts f = facts(b -> {
            b.freeData = -1;
            b.freeDataReason = "falha ao ler o volume";
            b.writeLimitUndetermined = true;
        });
        assertLine("Não foi possível medir o espaço livre do disco dos dados (falha ao ler o volume)." + UNMEASURED_TAIL,
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void unmeasuredWithoutAReasonHasNoParenthesis() {
        StorageFacts f = facts(b -> {
            b.freeData = -1;
            b.writeLimitUndetermined = true;
        });
        assertLine("Não foi possível medir o espaço livre do disco dos dados." + UNMEASURED_TAIL,
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void criticalNamesTheFreeSpaceTheMinimumAndTheShortfall() {
        StorageFacts f = facts(b -> {
            b.freeData = mb(40);
            b.writeLimitActive = true;
        });
        assertLine(CRITICAL_TEXT, Tone.DANGER, Icon.ALERT_OCTAGON, StoragePresenter.levelLine(f));
    }

    @Test
    void criticalShortfallIsTheMinimumMinusTheFreeSpace() {
        // 1,5 GB free against a 2 GB minimum: Z = 512 MB, which is neither X nor Y
        StorageFacts f = facts(b -> {
            b.freeData = mb(1536);
            b.writeLimitBytes = gb(2);
            b.writeLimitActive = true;
        });
        assertLine("Espaço livre abaixo do limite de gravação: há 1,5 GB e o mínimo é 2 GB. Novas gravações estão "
                + "recusadas até liberar pelo menos 512 MB. Leitura, exportação e cópia para outro disco continuam.",
                Tone.DANGER, Icon.ALERT_OCTAGON, StoragePresenter.levelLine(f));
    }

    @Test
    void reserveNamesTheFreeSpaceAndTheTarget() {
        assertLine("Pouco espaço para restaurar: há 90 MB livres e a reserva pede 104 MB (2 × dados + margem). As "
                + "gravações continuam; a cópia automática pode ser adiada.",
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(reserve()));
    }

    @Test
    void dbWarningNamesTheLimitAndTheSizeOfTheData() {
        assertLine("Os dados passaram de 100 MB (estão com 150 MB). Nada muda no uso; é um sinal de crescimento.",
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(dbWarning()));
    }

    @Test
    void dbUnknownSaysTheSizeCouldNotBeRead() {
        assertLine("Não foi possível ler o tamanho dos dados; o aviso e a reserva ficam sem conta até a próxima "
                + "medida.", Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(dbUnknown()));
    }

    @Test
    void normalIsSecondaryWithoutAnIcon() {
        assertLine("Espaço suficiente: há 50 GB livres no disco dos dados.",
                Tone.SECONDARY, Icon.NONE, StoragePresenter.levelLine(normal()));
    }

    // ── the extra sentences ────────────────────────────────────────────────

    @Test
    void criticalCarriesTheDbWarningSentence() {
        assertLine(CRITICAL_TEXT + ALSO_WARN, Tone.DANGER, Icon.ALERT_OCTAGON,
                StoragePresenter.levelLine(critical()));
    }

    @Test
    void unmeasuredWithTheDbWarningAndNoReason() {
        StorageFacts f = facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = -1;
            b.writeLimitUndetermined = true;
            b.warnActive = true;
        });
        assertLine("Não foi possível medir o espaço livre do disco dos dados." + UNMEASURED_TAIL + ALSO_WARN,
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void unmeasuredExampleRowFiveCarriesTheDbWarningSentence() {
        assertLine("Não foi possível medir o espaço livre do disco dos dados (falha ao ler o volume)." + UNMEASURED_TAIL
                + ALSO_WARN, Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(unmeasured()));
    }

    @Test
    void reserveWithTheDbWarningGetsTheSentence() {
        StorageFacts f = facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = mb(90);
            b.reserveActive = true;
            b.warnActive = true;
        });
        assertLine("Pouco espaço para restaurar: há 90 MB livres e a reserva pede 104 MB (2 × dados + margem). As "
                + "gravações continuam; a cópia automática pode ser adiada." + ALSO_WARN,
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void dbWarningStateDoesNotRepeatItsOwnSentence() {
        Line line = StoragePresenter.levelLine(dbWarning());
        assertEquals("Os dados passaram de 100 MB (estão com 150 MB). Nada muda no uso; é um sinal de crescimento.",
                line.text());
    }

    @Test
    void bothExtraSentencesInOrderWarnThenPostponed() {
        StorageFacts f = facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = mb(90);
            b.reserveActive = true;
            b.warnActive = true;
            b.postponedMessage = POSTPONED;
        });
        assertLine("Pouco espaço para restaurar: há 90 MB livres e a reserva pede 104 MB (2 × dados + margem). As "
                + "gravações continuam; a cópia automática pode ser adiada." + ALSO_WARN + " " + POSTPONED,
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void theExtraSentencesNeverChangeTheToneOrTheIcon() {
        StorageFacts f = facts(b -> {
            b.databaseBytes = mb(150);
            b.freeData = mb(40);
            b.writeLimitActive = true;
            b.warnActive = true;
            b.postponedMessage = POSTPONED;
        });
        Line line = StoragePresenter.levelLine(f);
        assertEquals(CRITICAL_TEXT + ALSO_WARN + " " + POSTPONED, line.text());
        assertEquals(Tone.DANGER, line.tone());
        assertEquals(Icon.ALERT_OCTAGON, line.icon());
    }

    @Test
    void anEmptyPostponedMessageAddsNothing() {
        StorageFacts f = facts(b -> {
            b.freeData = mb(90);
            b.reserveActive = true;
            b.postponedMessage = "";
        });
        assertEquals(StoragePresenter.levelLine(reserve()).text(), StoragePresenter.levelLine(f).text());
        assertEquals("Pouco espaço para restaurar: há 90 MB livres e a reserva pede 104 MB (2 × dados + margem). As "
                + "gravações continuam; a cópia automática pode ser adiada.", StoragePresenter.levelLine(f).text());
    }

    @Test
    void aPostponedMessageWithTheNormalState() {
        StorageFacts f = facts(b -> b.postponedMessage = POSTPONED);
        assertLine("Espaço suficiente: há 50 GB livres no disco dos dados. " + POSTPONED,
                Tone.SECONDARY, Icon.NONE, StoragePresenter.levelLine(f));
    }

    @Test
    void aPostponedMessageWithTheDbWarningState() {
        StorageFacts f = facts(b -> {
            b.databaseBytes = mb(150);
            b.warnActive = true;
            b.postponedMessage = POSTPONED;
        });
        assertLine("Os dados passaram de 100 MB (estão com 150 MB). Nada muda no uso; é um sinal de crescimento. "
                + POSTPONED, Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void aPostponedMessageWithTheCriticalState() {
        StorageFacts f = facts(b -> {
            b.freeData = mb(40);
            b.writeLimitActive = true;
            b.postponedMessage = POSTPONED;
        });
        assertLine(CRITICAL_TEXT + " " + POSTPONED, Tone.DANGER, Icon.ALERT_OCTAGON, StoragePresenter.levelLine(f));
    }

    // ── unknown numbers ────────────────────────────────────────────────────

    @Test
    void anUnknownFreeSpaceIsADashInTheReserveText() {
        StorageFacts f = facts(b -> {
            b.freeData = -1;
            b.reserveActive = true;
        });
        assertLine("Pouco espaço para restaurar: há — livres e a reserva pede 104 MB (2 × dados + margem). As "
                + "gravações continuam; a cópia automática pode ser adiada.",
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void anUnknownFreeSpaceIsADashInTheCriticalTextAndSoIsTheShortfall() {
        StorageFacts f = facts(b -> {
            b.freeData = -1;
            b.writeLimitActive = true;
        });
        assertLine("Espaço livre abaixo do limite de gravação: há — e o mínimo é 64 MB. Novas gravações estão "
                + "recusadas até liberar pelo menos —. Leitura, exportação e cópia para outro disco continuam.",
                Tone.DANGER, Icon.ALERT_OCTAGON, StoragePresenter.levelLine(f));
    }

    @Test
    void anUnknownFreeSpaceIsADashInTheNormalText() {
        assertLine("Espaço suficiente: há — livres no disco dos dados.", Tone.SECONDARY, Icon.NONE,
                StoragePresenter.levelLine(facts(b -> b.freeData = -1)));
    }

    @Test
    void anUnknownSizeOfTheDataIsADashInTheDbWarningText() {
        StorageFacts f = facts(b -> {
            b.databaseBytes = -1;
            b.warnActive = true;
        });
        assertLine("Os dados passaram de 100 MB (estão com —). Nada muda no uso; é um sinal de crescimento.",
                Tone.WARNING, Icon.ALERT_TRIANGLE, StoragePresenter.levelLine(f));
    }

    @Test
    void anUnknownLimitIsADashToo() {
        StorageFacts f = facts(b -> {
            b.freeData = mb(40);
            b.writeLimitBytes = -1;
            b.writeLimitActive = true;
        });
        assertEquals("Espaço livre abaixo do limite de gravação: há 40 MB e o mínimo é —. Novas gravações estão "
                + "recusadas até liberar pelo menos —. Leitura, exportação e cópia para outro disco continuam.",
                StoragePresenter.levelLine(f).text());
    }
}

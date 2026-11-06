package com.daniel.presentation.view.pages.storage;

import com.daniel.presentation.view.pages.storage.StoragePresenter.Icon;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Line;
import com.daniel.presentation.view.pages.storage.StoragePresenter.Tone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.daniel.presentation.view.pages.storage.StorageFactsFixture.mb;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BRK-31 u5 F2, RF-04 and RF-08 case 3: the transient states, one line each. */
class StoragePresenterTransientTest {

    private static void assertLine(String text, Tone tone, Line actual) {
        assertEquals(text, actual.text());
        assertEquals(tone, actual.tone());
        assertEquals(Icon.NONE, actual.icon());
    }

    @Test
    void measuring() {
        assertLine("Medindo o espaço dos dados…", Tone.HELPER, StoragePresenter.measuring());
    }

    @Test
    void readFailedWithAMessage() {
        assertLine("Não foi possível ler o espaço dos dados: acesso negado.", Tone.DANGER,
                StoragePresenter.readFailed("acesso negado"));
    }

    @Test
    void readFailedWithoutAMessage() {
        assertLine("Não foi possível ler o espaço dos dados.", Tone.DANGER, StoragePresenter.readFailed(null));
        assertLine("Não foi possível ler o espaço dos dados.", Tone.DANGER, StoragePresenter.readFailed(""));
    }

    @Test
    void unavailable() {
        assertLine("Espaço dos dados indisponível nesta janela.", Tone.SECONDARY, StoragePresenter.unavailable());
    }

    @Test
    void saving() {
        assertLine("Salvando os limites…", Tone.HELPER, StoragePresenter.saving());
    }

    @Test
    void saved() {
        assertLine("Limites salvos.", Tone.SECONDARY, StoragePresenter.saved());
    }

    @Test
    void saveFailedAppendsTheLimitsInEffect() {
        String reason = "Não foi possível salvar os limites nas preferências deste computador "
                + "(BackingStoreException). Os limites anteriores continuam valendo.";
        assertLine(reason + " Valendo agora: aviso 100 MB · margem 64 MB · limite 64 MB.", Tone.DANGER,
                StoragePresenter.saveFailed(reason, mb(100), mb(64), mb(64)));
    }

    @Test
    void saveFailedWritesAnUnknownLimitAsADash() {
        assertLine("Falhou. Valendo agora: aviso — · margem 1,5 GB · limite —.", Tone.DANGER,
                StoragePresenter.saveFailed("Falhou.", -1, mb(1536), -1));
    }

    @Test
    void saveCrashedWithAMessage() {
        assertLine("Não foi possível salvar os limites (disco cheio). Os limites anteriores continuam valendo.",
                Tone.DANGER, StoragePresenter.saveCrashed("disco cheio"));
    }

    @Test
    void saveCrashedWithoutAMessageHasNoParenthesis() {
        String expected = "Não foi possível salvar os limites. Os limites anteriores continuam valendo.";
        assertLine(expected, Tone.DANGER, StoragePresenter.saveCrashed(null));
        assertLine(expected, Tone.DANGER, StoragePresenter.saveCrashed(""));
    }

    @Test
    void rejectedShowsTheProblemAsItCame() {
        String problem = "O limite de gravação precisa ficar entre 16 MB e 16 GB.";
        assertLine(problem, Tone.DANGER, StoragePresenter.rejected(problem));
    }

    @Test
    void notANumber() {
        assertLine("Digite um número inteiro de MB, sem pontos.", Tone.DANGER, StoragePresenter.notANumber());
    }

    @Test
    void correctedWithTheTwoKindsOfSentenceOfStoragePreferences() {
        List<Line> lines = StoragePresenter.corrected(List.of(
                "margem da reserva: valor salvo inválido; usando o padrão",
                "limite de gravação: preferências indisponíveis; usando o padrão"));
        assertEquals(2, lines.size());
        assertLine("Margem da reserva: valor salvo inválido; usando o padrão.", Tone.WARNING, lines.get(0));
        assertLine("Limite de gravação: preferências indisponíveis; usando o padrão.", Tone.WARNING, lines.get(1));
    }

    @Test
    void correctedWithOneItemAndInTheGivenOrder() {
        List<Line> one = StoragePresenter.corrected(List.of("aviso do tamanho do banco: valor salvo inválido; usando o padrão"));
        assertEquals(1, one.size());
        assertLine("Aviso do tamanho do banco: valor salvo inválido; usando o padrão.", Tone.WARNING, one.get(0));

        List<Line> reversed = StoragePresenter.corrected(List.of(
                "limite de gravação: valor salvo inválido; usando o padrão",
                "aviso do tamanho do banco: valor salvo inválido; usando o padrão"));
        assertEquals("Limite de gravação: valor salvo inválido; usando o padrão.", reversed.get(0).text());
        assertEquals("Aviso do tamanho do banco: valor salvo inválido; usando o padrão.", reversed.get(1).text());
    }

    @Test
    void correctedWithAnEmptyListIsAnEmptyList() {
        assertTrue(StoragePresenter.corrected(List.of()).isEmpty());
    }
}

package com.daniel.presentation.view.pages.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BRK-31 u5 F2, RF-06b and RF-08 case 6: every fixed text of the section, word by word. */
class StoragePresenterConstantsTest {

    @Test
    void titles() {
        assertEquals("Dados e armazenamento", StoragePresenter.SECTION_TITLE);
        assertEquals("Sobre o aplicativo", StoragePresenter.ABOUT_TITLE);
        assertEquals("Espaço", StoragePresenter.BLOCK_TITLE);
    }

    @Test
    void buttons() {
        assertEquals("Medir de novo", StoragePresenter.MEASURE_AGAIN);
        assertEquals("Salvar limites", StoragePresenter.SAVE_LIMITS);
        assertEquals("Criar mesmo assim", StoragePresenter.CREATE_ANYWAY);
    }

    @Test
    void theWarnLimitField() {
        assertEquals("Avisar quando os dados passarem de", StoragePresenter.WARN_LABEL);
        assertEquals("É só um aviso; nada muda no uso.", StoragePresenter.WARN_HELP);
    }

    @Test
    void theReserveFloorField() {
        assertEquals("Margem da reserva", StoragePresenter.FLOOR_LABEL);
        assertEquals("A reserva é 2 × o tamanho dos dados + esta margem.", StoragePresenter.FLOOR_HELP);
    }

    @Test
    void theWriteLimitField() {
        assertEquals("Limite de gravação", StoragePresenter.WRITE_LABEL);
        assertEquals("Abaixo deste espaço livre, novas gravações são recusadas.", StoragePresenter.WRITE_HELP);
    }

    @Test
    void theUnit() {
        assertEquals("MB", StoragePresenter.UNIT);
    }

    @Test
    void theLimitsNote() {
        assertEquals("Os limites ficam neste computador, fora dos dados: restaurar uma cópia não os muda, e eles "
                + "podem ser salvos mesmo quando os dados não aceitam gravações.", StoragePresenter.LIMITS_NOTE);
    }
}

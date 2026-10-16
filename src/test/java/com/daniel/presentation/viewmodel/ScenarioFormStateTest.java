package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ownership of asynchronous scenario callbacks (RF13 extension). Every async
 * operation captures the form epoch when it starts; its result may only be
 * applied if the form is still the same one. Reproduces the review case: a
 * reload/save callback for scenario A must not re-attach A to a form the user
 * has already reset with "Novo", otherwise the next "Salvar" would UPDATE A.
 * No JavaFX involved.
 */
class ScenarioFormStateTest {

    static SimulationParameters params(String rate) {
        return SimulationParameters.fixedRate(1_000_000L, 12, new BigDecimal(rate));
    }

    static SimulationScenario saved(long id, String name) {
        return new SimulationScenario(id, name, params("0.10"), "2026-10-15T00:00:00Z");
    }

    @Test
    void newState_hasNoLoadedScenario_andSaveCandidateIsCreate() {
        ScenarioFormState form = new ScenarioFormState();

        assertNull(form.loaded());
        assertFalse(form.hasLoaded());
        assertNull(form.candidate("B", params("0.10"), false).id());
    }

    @Test
    void load_thenCandidate_isUpdateOfLoadedScenario() {
        ScenarioFormState form = new ScenarioFormState();
        form.load(saved(1, "A"));

        SimulationScenario candidate = form.candidate("B", params("0.12"), false);

        assertEquals(1L, candidate.id());
        assertEquals("B", candidate.name());
        assertEquals(params("0.12"), candidate.parameters());
    }

    @Test
    void candidate_asNew_ignoresLoadedScenario() {
        ScenarioFormState form = new ScenarioFormState();
        form.load(saved(1, "A"));

        assertNull(form.candidate("A2", params("0.10"), true).id());
    }

    @Test
    void reviewCase_reloadCallbackAfterNovo_mustNotReattachOldScenario() {
        ScenarioFormState form = new ScenarioFormState();
        form.load(saved(1, "A"));
        long epochWhenReloadStarted = form.epoch();   // onShow → reloadScenarios(A.id) queued

        form.startNew();                                // user clicks "Novo" before the callback returns
        boolean applied = form.applyIfCurrent(epochWhenReloadStarted, saved(1, "A"));

        assertFalse(applied, "stale callback must be ignored");
        assertNull(form.loaded(), "form stays new");
        assertNull(form.candidate("B", params("0.10"), false).id(), "next Salvar must INSERT B, not UPDATE A");
    }

    @Test
    void reviewCase_saveCallbackAfterNovo_mustNotReattachSavedScenario() {
        ScenarioFormState form = new ScenarioFormState();
        form.load(saved(1, "A"));
        long epochWhenSaveStarted = form.epoch();       // Salvar queued

        form.startNew();                                // "Novo" right after Salvar
        boolean applied = form.applyIfCurrent(epochWhenSaveStarted, saved(1, "A renamed"));

        assertFalse(applied);
        assertNull(form.loaded());
    }

    @Test
    void applyIfCurrent_sameEpoch_attachesResult() {
        ScenarioFormState form = new ScenarioFormState();
        form.startNew();
        long epoch = form.epoch();

        boolean applied = form.applyIfCurrent(epoch, saved(7, "Novo salvo"));

        assertTrue(applied);
        assertEquals(7L, form.loaded().id());
        assertEquals(7L, form.candidate("Novo salvo", params("0.10"), false).id(), "next Salvar updates the saved row");
    }

    @Test
    void applyIfCurrent_afterAnotherScenarioWasLoaded_isIgnored() {
        ScenarioFormState form = new ScenarioFormState();
        form.load(saved(1, "A"));
        long epoch = form.epoch();
        form.load(saved(2, "B"));

        assertFalse(form.applyIfCurrent(epoch, saved(1, "A")));
        assertEquals(2L, form.loaded().id());
    }

    @Test
    void applyIfCurrent_nullResult_clearsNothingAndReturnsFalse() {
        ScenarioFormState form = new ScenarioFormState();
        form.load(saved(1, "A"));

        assertFalse(form.applyIfCurrent(form.epoch(), null));
        assertEquals(1L, form.loaded().id());
    }

    @Test
    void startNewAndLoad_advanceEpochEveryTime() {
        ScenarioFormState form = new ScenarioFormState();
        long e0 = form.epoch();
        long e1 = form.startNew();
        long e2 = form.load(saved(1, "A"));
        long e3 = form.startNew();

        assertTrue(e0 < e1 && e1 < e2 && e2 < e3);
        assertEquals(e3, form.epoch());
    }
}

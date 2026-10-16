package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;

/**
 * Owns the identity of the scenario behind the simulation form (RF13 extension).
 *
 * <p>Every time the form is replaced ("Novo" or loading a scenario) the epoch
 * advances. Asynchronous operations (reload, save, duplicate, quotes, rates)
 * capture {@link #epoch()} when they start and may only touch the form through
 * {@link #applyIfCurrent(long, SimulationScenario)}, which refuses results from
 * an older form. This prevents a late callback from re-attaching an old scenario
 * to a form the user already reset, which would turn the next "Salvar" into an
 * UPDATE of that scenario instead of an INSERT.</p>
 *
 * <p>Single-threaded by contract: all calls happen on the UI thread.</p>
 */
public final class ScenarioFormState {

    private long epoch = 0;
    private SimulationScenario loaded = null;

    /** Current form epoch; capture it before starting an async operation. */
    public long epoch() {
        return epoch;
    }

    /** Scenario the form is bound to, or {@code null} for a new, unsaved form. */
    public SimulationScenario loaded() {
        return loaded;
    }

    public boolean hasLoaded() {
        return loaded != null;
    }

    /** Resets to a new form; returns the new epoch. */
    public long startNew() {
        loaded = null;
        return ++epoch;
    }

    /** Binds the form to a saved scenario; returns the new epoch. */
    public long load(SimulationScenario scenario) {
        loaded = scenario;
        return ++epoch;
    }

    /**
     * Applies the result of an async operation started at {@code startedAtEpoch}
     * only if the form has not been replaced since. Does not advance the epoch:
     * attaching the saved identity of the same form is not a form replacement.
     *
     * @return {@code true} when the result was applied
     */
    public boolean applyIfCurrent(long startedAtEpoch, SimulationScenario result) {
        if (result == null || startedAtEpoch != epoch) return false;
        loaded = result;
        return true;
    }

    /** True when an operation started at {@code startedAtEpoch} still belongs to the current form. */
    public boolean isCurrent(long startedAtEpoch) {
        return startedAtEpoch == epoch;
    }

    /**
     * Scenario to persist for "Salvar"/"Salvar como novo": an update of the loaded
     * scenario, or a new one when nothing is loaded or {@code asNew} is requested.
     */
    public SimulationScenario candidate(String name, SimulationParameters params, boolean asNew) {
        return (loaded == null || asNew)
                ? SimulationScenario.create(name, params)
                : loaded.withName(name).withParameters(params);
    }
}

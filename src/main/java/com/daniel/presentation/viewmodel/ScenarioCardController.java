package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.SimulationParameters;
import com.daniel.core.domain.entity.SimulationScenario;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.InvalidEntry;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.Listing;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Drives the "Cenários salvos" card of the simulation page (RF13 extension) without
 * any JavaFX dependency, so its concurrency rules are testable deterministically.
 *
 * <p>Rules enforced here, all on the UI executor:</p>
 * <ul>
 *   <li>Every async operation captures the {@link ScenarioFormState#epoch()} when it
 *       starts and attaches its result only through
 *       {@link ScenarioFormState#applyIfCurrent}; a form replaced by "Novo" or by
 *       loading another scenario never receives a stale identity.</li>
 *   <li>While a write (save/duplicate/delete) is in flight the card is busy:
 *       "Novo", loading and further writes are refused, and the view disables the
 *       controls. Reloads are not writes and do not block the user.</li>
 *   <li>Invalid stored rows are reported individually and never hide valid ones;
 *       a database failure is reported as an error and no list is rendered.</li>
 * </ul>
 */
public final class ScenarioCardController {

    /** UI surface the controller talks to; implemented by the JavaFX page. */
    public interface View {
        /** Rebuilds the list; {@code selected} may be null (nothing loaded). */
        void renderList(List<SimulationScenario> scenarios, SimulationScenario selected);
        /** Applies a loaded scenario to the form fields. */
        void renderLoaded(SimulationScenario scenario);
        /** Refreshes name/status/projection after a successful save or duplicate. */
        void renderSaved(SimulationScenario saved);
        /** Clears the form for a new scenario. */
        void renderNew();
        /** Disables/enables the card controls (including "Novo") during writes. */
        void setBusy(boolean busy);
        void warn(String message);
        void error(String message);
        void success(String message);
        void info(String message);
    }

    private final SavedAnalysisRepository scenarios;
    private final Executor worker;
    private final Executor ui;
    private final View view;
    private final ScenarioFormState state = new ScenarioFormState();
    private boolean busy = false;

    public ScenarioCardController(SavedAnalysisRepository scenarios, Executor worker, Executor ui, View view) {
        this.scenarios = Objects.requireNonNull(scenarios);
        this.worker = Objects.requireNonNull(worker);
        this.ui = Objects.requireNonNull(ui);
        this.view = Objects.requireNonNull(view);
    }

    public ScenarioFormState state() {
        return state;
    }

    public boolean isBusy() {
        return busy;
    }

    /** "Novo": refused while a write is in flight. */
    public boolean startNew() {
        if (busy) return false;
        state.startNew();
        view.renderNew();
        return true;
    }

    /** Selecting a scenario in the list: refused while a write is in flight. */
    public boolean load(SimulationScenario scenario) {
        if (busy || scenario == null) return false;
        state.load(scenario);
        view.renderLoaded(scenario);
        return true;
    }

    /**
     * Refreshes the list. Keeps the currently loaded scenario selected when the form
     * is still the same one; a form replaced meanwhile is left untouched.
     */
    public void reload() {
        final long epoch = state.epoch();
        final Long preferId = state.hasLoaded() ? state.loaded().id() : null;
        CompletableFuture.supplyAsync(scenarios::listScenariosDetailed, worker)
                .whenComplete((listing, err) -> ui.execute(() -> {
                    if (err != null) {
                        view.error("Não foi possível carregar os cenários: " + rootMessage(err));
                        return;
                    }
                    reportInvalid(listing);
                    SimulationScenario selected;
                    if (state.isCurrent(epoch)) {
                        selected = find(listing.valid(), preferId);
                        state.applyIfCurrent(epoch, selected);
                    } else {
                        selected = find(listing.valid(), state.hasLoaded() ? state.loaded().id() : null);
                    }
                    view.renderList(listing.valid(), selected);
                }));
    }

    /** "Salvar" / "Salvar como novo". Ignored while busy. */
    public void save(String rawName, SimulationParameters params, boolean asNew) {
        if (busy) return;
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            view.warn("Informe um nome para o cenário.");
            return;
        }
        final SimulationScenario candidate = state.candidate(name, params, asNew);
        final long epoch = state.epoch();
        setBusy(true);
        CompletableFuture.supplyAsync(() -> scenarios.saveScenario(candidate), worker)
                .whenComplete((saved, err) -> ui.execute(() -> {
                    setBusy(false);
                    if (err != null) {
                        view.error("Não foi possível salvar: " + rootMessage(err));
                        return;
                    }
                    if (state.applyIfCurrent(epoch, saved)) {
                        view.success("Cenário \"" + saved.name() + "\" salvo.");
                        view.renderSaved(saved);
                    } else {
                        view.info("Cenário \"" + saved.name() + "\" salvo, mas o formulário foi alterado enquanto gravava; "
                                + "selecione-o na lista para continuar editando.");
                    }
                    reload();
                }));
    }

    /** "Duplicar" the loaded scenario. Ignored while busy or with nothing loaded. */
    public void duplicate() {
        if (busy || !state.hasLoaded()) return;
        final SimulationScenario copy = state.loaded().duplicate();
        final long epoch = state.epoch();
        setBusy(true);
        CompletableFuture.supplyAsync(() -> scenarios.saveScenario(copy), worker)
                .whenComplete((saved, err) -> ui.execute(() -> {
                    setBusy(false);
                    if (err != null) {
                        view.error("Não foi possível duplicar: " + rootMessage(err));
                        return;
                    }
                    if (state.applyIfCurrent(epoch, saved)) {
                        view.success("Cópia criada: \"" + saved.name() + "\" — o original permanece inalterado.");
                        view.renderSaved(saved);
                    } else {
                        view.info("Cópia \"" + saved.name() + "\" criada, mas o formulário foi alterado enquanto gravava; "
                                + "selecione-a na lista para editá-la.");
                    }
                    reload();
                }));
    }

    /** Deletes the loaded scenario (confirmation is the page's responsibility). */
    public void delete() {
        if (busy || !state.hasLoaded()) return;
        final SimulationScenario target = state.loaded();
        setBusy(true);
        CompletableFuture.runAsync(() -> scenarios.deleteScenario(target.id()), worker)
                .whenComplete((v, err) -> ui.execute(() -> {
                    setBusy(false);
                    if (err != null) {
                        view.error("Não foi possível excluir: " + rootMessage(err));
                        return;
                    }
                    view.info("Cenário \"" + target.name() + "\" excluído.");
                    state.startNew();
                    view.renderNew();
                    reload();
                }));
    }

    private void setBusy(boolean value) {
        busy = value;
        view.setBusy(value);
    }

    private void reportInvalid(Listing<SimulationScenario> listing) {
        for (InvalidEntry bad : listing.invalid()) {
            view.warn(bad.reason() + " O cenário não foi exibido; os demais continuam disponíveis e nada foi apagado.");
        }
    }

    private static SimulationScenario find(List<SimulationScenario> list, Long id) {
        if (id == null) return null;
        for (SimulationScenario s : list) if (id.equals(s.id())) return s;
        return null;
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof CompletionException || c instanceof ExecutionException)) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}

package com.daniel.presentation.viewmodel;

import com.daniel.core.domain.entity.AllocationPlan;
import com.daniel.core.domain.entity.Enums.CategoryEnum;
import com.daniel.core.service.ARCADiversificationStrategy;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.InvalidEntry;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.Listing;
import com.daniel.infrastructure.persistence.repository.SavedAnalysisRepository.SelectedPlanState;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Drives the "Plano de alocação" card of the Diversificação page (RF10 extension)
 * without JavaFX, so persistence/UI rules are testable deterministically.
 *
 * <ul>
 *   <li>Saving from the editor persists header + items + selection in one
 *       transaction ({@link SavedAnalysisRepository#savePlanAndSelect}); on failure
 *       nothing is left behind and the editor stays open with the error.</li>
 *   <li>The analysis profile always comes from the persisted selection. When the
 *       selected plan exists but is invalid, the ARCA profile is used with an explicit
 *       warning naming the plan and the reason; the stored row and the stored selection
 *       are not modified (RN11 spirit: data is never silently repaired or dropped).</li>
 *   <li>Listing skips invalid plans individually with a warning; a database failure is
 *       an error and nothing is rendered as if the data were absent.</li>
 *   <li>While a write (save/delete) is in flight the card is busy: further writes are
 *       refused here, not only by disabled buttons, so Enter, ESC or a double click cannot
 *       start a second transaction or close the editor the pending callback will act on.</li>
 * </ul>
 */
public final class AllocationPlanCardController {

    /** UI surface implemented by the JavaFX page; all methods run on the UI executor. */
    public interface View {
        /** Rebuilds the plan combo; {@code selectedId} null means the default ARCA entry. */
        void renderPlans(List<AllocationPlan> plans, Long selectedId);
        /** Supplies the profile to the analysis; {@code plan} is null for ARCA. */
        void renderProfile(Map<CategoryEnum, Double> profile, AllocationPlan plan);
        void setBusy(boolean busy);
        void showEditorError(String message);
        void closeEditor();
        void warn(String message);
        void error(String message);
        void success(String message);
        void info(String message);
    }

    private final SavedAnalysisRepository plans;
    private final Executor worker;
    private final Executor ui;
    private final View view;
    private boolean busy = false;

    public AllocationPlanCardController(SavedAnalysisRepository plans, Executor worker, Executor ui, View view) {
        this.plans = Objects.requireNonNull(plans);
        this.worker = Objects.requireNonNull(worker);
        this.ui = Objects.requireNonNull(ui);
        this.view = Objects.requireNonNull(view);
    }

    /** True while a save or delete has not answered yet; the view must refuse edits/close meanwhile. */
    public boolean isBusy() {
        return busy;
    }

    /**
     * Persists the validated editor plan and selects it atomically; keeps the editor open on
     * failure. Returns false (nothing queued) while another write is in flight.
     */
    public boolean saveFromEditor(AllocationPlan candidate) {
        if (busy) return false;
        setBusy(true);
        CompletableFuture.supplyAsync(() -> plans.savePlanAndSelect(candidate), worker)
                .whenComplete((saved, err) -> ui.execute(() -> {
                    setBusy(false);
                    if (err != null) {
                        view.showEditorError(rootMessage(err));
                        return;
                    }
                    view.closeEditor();
                    view.success("Plano \"" + saved.name() + "\" salvo e selecionado.");
                    reload(true);
                }));
        return true;
    }

    /** User chose a plan in the combo ({@code null} = ARCA). */
    public void choose(Long planId) {
        CompletableFuture.runAsync(() -> plans.selectPlan(planId), worker)
                .whenComplete((v, err) -> ui.execute(() -> {
                    if (err != null) {
                        view.error("Não foi possível selecionar o plano: " + rootMessage(err));
                        reload(false);
                        return;
                    }
                    refresh();
                }));
    }

    /** Deletes the plan; refused (false) while another write is in flight. */
    public boolean delete(AllocationPlan plan) {
        if (busy) return false;
        setBusy(true);
        CompletableFuture.runAsync(() -> plans.deletePlan(plan.id()), worker)
                .whenComplete((v, err) -> ui.execute(() -> {
                    setBusy(false);
                    if (err != null) {
                        view.error("Não foi possível excluir: " + rootMessage(err));
                        return;
                    }
                    view.info("Plano \"" + plan.name() + "\" excluído — usando ARCA padrão.");
                    reload(true);
                }));
        return true;
    }

    private void setBusy(boolean value) {
        busy = value;
        view.setBusy(value);
    }

    /** Snapshot read on the worker: plans listing plus the persisted selection state. */
    private record PlansSnapshot(Listing<AllocationPlan> listing, SelectedPlanState selection) {}

    /**
     * Reloads the list and the persisted selection. An invalid selected plan is shown
     * as ARCA in the combo (its row stays in the database); invalid plans are skipped
     * from the list with a warning each.
     */
    public void reload(boolean thenRefresh) {
        CompletableFuture.supplyAsync(() -> new PlansSnapshot(plans.listPlansDetailed(), plans.selectedPlanState()), worker)
                .whenComplete((snap, err) -> ui.execute(() -> {
                    if (err != null) {
                        view.error("Não foi possível carregar os planos: " + rootMessage(err));
                        return;
                    }
                    for (InvalidEntry bad : snap.listing().invalid()) {
                        view.warn("Plano \"" + bad.name() + "\" não pôde ser usado: " + bad.reason()
                                + " Ele não aparece na lista; os demais continuam disponíveis e nada foi apagado.");
                    }
                    Long selectedId = snap.selection().plan().map(AllocationPlan::id).orElse(null);
                    view.renderPlans(snap.listing().valid(), selectedId);
                    if (thenRefresh) applySelection(snap.selection());
                }));
    }

    /** Recomputes the profile from the persisted selection (ARCA with warning if invalid). */
    public void refresh() {
        CompletableFuture.supplyAsync(plans::selectedPlanState, worker)
                .whenComplete((state, err) -> ui.execute(() -> {
                    if (err != null) {
                        view.error("Não foi possível calcular a diversificação: " + rootMessage(err));
                        return;
                    }
                    applySelection(state);
                }));
    }

    private void applySelection(SelectedPlanState state) {
        if (state.problem() != null) {
            String name = state.problem().name() == null || state.problem().name().isBlank()
                    ? "id " + state.problem().id() : state.problem().name();
            view.warn("Plano selecionado \"" + name + "\" inválido: " + state.problem().reason()
                    + " A análise usa a estratégia ARCA padrão até você corrigir, substituir ou excluir esse plano.");
        }
        if (state.plan().isPresent()) {
            AllocationPlan plan = state.plan().get();
            view.renderProfile(plan.toProfile(), plan);
        } else {
            view.renderProfile(ARCADiversificationStrategy.getARCAProfile(), null);
        }
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof CompletionException || c instanceof ExecutionException)) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}

package com.daniel.core.domain.entity;

/**
 * Named, persisted simulation scenario (RF13 extension). Stores parameters only;
 * the projection is reproduced by {@code SimulationCalculator} at the stored
 * calculation version, so results never depend on current market data (RN12).
 *
 * @param id         database id, {@code null} while unsaved
 * @param name       non-blank, trimmed, unique ignoring case
 * @param parameters complete reproducible parameters
 * @param updatedAt  ISO-8601 instant as persisted, {@code null} while unsaved
 */
public record SimulationScenario(Long id, String name, SimulationParameters parameters, String updatedAt) {

    public static final String COPY_SUFFIX = " (cópia)";

    public SimulationScenario {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Informe um nome para o cenário.");
        if (parameters == null) throw new IllegalArgumentException("Parâmetros do cenário ausentes.");
        name = name.trim();
    }

    public static SimulationScenario create(String name, SimulationParameters parameters) {
        return new SimulationScenario(null, name, parameters, null);
    }

    public SimulationScenario withName(String newName) {
        return new SimulationScenario(id, newName, parameters, updatedAt);
    }

    public SimulationScenario withParameters(SimulationParameters newParameters) {
        return new SimulationScenario(id, name, newParameters, updatedAt);
    }

    /** Unsaved copy with the same parameters and a " (cópia)" name; the repository de-duplicates the name. */
    public SimulationScenario duplicate() {
        return new SimulationScenario(null, name + COPY_SUFFIX, parameters, null);
    }
}

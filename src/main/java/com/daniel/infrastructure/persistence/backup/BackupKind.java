package com.daniel.infrastructure.persistence.backup;

import java.util.Optional;

/**
 * Why a copy was made. The tag is part of the file name. Only automatic kinds are ever rotated;
 * manual copies are never deleted by the application.
 */
public enum BackupKind {
    MANUAL("manual", false, "manual"),
    DAILY("diaria", true, "automática do dia"),
    PRE_MIGRATION("pre-migracao", true, "antes de atualizar os dados"),
    PRE_RESTORE("pre-restauracao", true, "antes de restaurar"),
    PRE_MAINTENANCE("pre-manutencao", true, "antes de liberar espaço");

    private final String tag;
    private final boolean automatic;
    private final String label;

    BackupKind(String tag, boolean automatic, String label) {
        this.tag = tag;
        this.automatic = automatic;
        this.label = label;
    }

    /** File-name tag (ASCII, no accents). */
    public String tag() {
        return tag;
    }

    /** Automatic copies rotate; manual ones never do. */
    public boolean automatic() {
        return automatic;
    }

    /** Short pt-BR description for the screen. */
    public String label() {
        return label;
    }

    public static Optional<BackupKind> fromTag(String tag) {
        for (BackupKind k : values()) {
            if (k.tag.equals(tag)) return Optional.of(k);
        }
        return Optional.empty();
    }
}

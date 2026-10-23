package com.daniel.infrastructure.persistence.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The storage limits in the user's preferences, apart from {@code UiPreferences} and from the database
 * (DESENHO-BRK31 D-Q1). They belong to this machine and volume: restoring a database copy does not restore
 * them, and they can still be changed when the database refuses writes. A value that is missing or invalid
 * falls back to the safe default; a change that could not be flushed is reported as not saved and the
 * previous limits stay in effect. Never stores a token or any other secret.
 */
public final class StoragePreferences {

    public static final String NODE_PATH = "/com/daniel/breakinv/storage";
    static final String KEY_WARN = "warnDbBytes";
    static final String KEY_RESERVE = "reserveFloorBytes";
    static final String KEY_WRITE = "writeLimitBytes";

    public record Loaded(StorageLimits limits, List<String> corrected) {
    }

    public sealed interface SaveResult permits Saved, Rejected, NotSaved {
    }

    public record Saved(StorageLimits limits) implements SaveResult {
    }

    /** The values are outside their ranges; nothing was written. */
    public record Rejected(String reason) implements SaveResult {
    }

    /** The preferences could not be persisted; {@code stillInEffect} are the limits in use. */
    public record NotSaved(String reason, StorageLimits stillInEffect) implements SaveResult {
    }

    private final Preferences node;

    public StoragePreferences(Preferences node) {
        this.node = node;
    }

    public static StoragePreferences forUser() {
        return new StoragePreferences(Preferences.userRoot().node(NODE_PATH));
    }

    public Loaded load() {
        StorageLimits d = StorageLimits.defaults();
        List<String> corrected = new ArrayList<>();
        long warn = read(KEY_WARN, d.warnDbBytes(), StorageLimits.MIN_WARN, StorageLimits.MAX_WARN,
                "aviso do tamanho do banco", corrected);
        long reserve = read(KEY_RESERVE, d.reserveFloorBytes(), StorageLimits.MIN_FLOOR, StorageLimits.MAX_FLOOR,
                "margem da reserva", corrected);
        long write = read(KEY_WRITE, d.writeLimitBytes(), StorageLimits.MIN_FLOOR, StorageLimits.MAX_FLOOR,
                "limite de gravação", corrected);
        return new Loaded(new StorageLimits(warn, reserve, write), List.copyOf(corrected));
    }

    public SaveResult save(StorageLimits limits) {
        String problem = limits.problem();
        if (problem != null) return new Rejected(problem);
        StorageLimits previous = load().limits();
        try {
            write(limits);
            node.flush();
            return new Saved(limits);
        } catch (BackingStoreException | IllegalStateException | SecurityException e) {
            try {
                write(previous); // best effort: the unsaved change must not stay in effect for this session
            } catch (IllegalStateException | SecurityException ignored) {
                // the node is gone; load() falls back to the defaults
            }
            return new NotSaved("Não foi possível salvar os limites nas preferências deste computador ("
                    + e.getClass().getSimpleName() + "). Os limites anteriores continuam valendo.", previous);
        }
    }

    private void write(StorageLimits limits) {
        node.putLong(KEY_WARN, limits.warnDbBytes());
        node.putLong(KEY_RESERVE, limits.reserveFloorBytes());
        node.putLong(KEY_WRITE, limits.writeLimitBytes());
    }

    private long read(String key, long fallback, long min, long max, String label, List<String> corrected) {
        String raw;
        try {
            raw = node.get(key, null);
        } catch (IllegalStateException | SecurityException e) {
            corrected.add(label + ": preferências indisponíveis; usando o padrão");
            return fallback;
        }
        if (raw == null) return fallback;
        try {
            long v = Long.parseLong(raw.trim());
            if (v >= min && v <= max) return v;
        } catch (NumberFormatException ignored) {
            // falls through to the default
        }
        corrected.add(label + ": valor salvo inválido; usando o padrão");
        return fallback;
    }
}

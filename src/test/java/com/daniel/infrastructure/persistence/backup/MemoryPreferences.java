package com.daniel.infrastructure.persistence.backup;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

/** In-memory Preferences node whose flush can be made to fail, to prove "não salvo" is reported. */
final class MemoryPreferences extends AbstractPreferences {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, MemoryPreferences> children = new HashMap<>();
    boolean failFlush;
    int flushes;

    MemoryPreferences() {
        super(null, "");
    }

    private MemoryPreferences(MemoryPreferences parent, String name) {
        super(parent, name);
    }

    Map<String, String> values() {
        return Map.copyOf(values);
    }

    @Override
    protected void putSpi(String key, String value) {
        values.put(key, value);
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
    }

    @Override
    protected String[] keysSpi() {
        return values.keySet().toArray(String[]::new);
    }

    @Override
    protected String[] childrenNamesSpi() {
        return children.keySet().toArray(String[]::new);
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n));
    }

    @Override
    protected void syncSpi() {
    }

    @Override
    protected void flushSpi() throws BackingStoreException {
        flushes++;
        if (failFlush) throw new BackingStoreException("armazenamento das preferências indisponível (teste)");
    }
}

package com.daniel.presentation.view.window;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/** In-memory java.util.prefs node for tests: nothing touches the registry or the disk. */
public final class MemoryPreferences extends AbstractPreferences {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, MemoryPreferences> children = new HashMap<>();
    private int flushes;

    public MemoryPreferences() { this(null, ""); }

    private MemoryPreferences(MemoryPreferences parent, String name) { super(parent, name); }

    public int flushCount() { return flushes; }

    @Override protected void putSpi(String key, String value) { values.put(key, value); }
    @Override protected String getSpi(String key) { return values.get(key); }
    @Override protected void removeSpi(String key) { values.remove(key); }
    @Override protected void removeNodeSpi() { values.clear(); }
    @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
    @Override protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
    @Override protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n));
    }
    @Override protected void syncSpi() { }
    @Override protected void flushSpi() { flushes++; }
}

package com.skycraft.perf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-resource ownership, independent of native tickets which no longer carry an owner value. */
public final class LeaseSet<K, O> {
    private final Map<K, Set<O>> owners = new HashMap<>();
    public boolean acquire(K key, O owner, Runnable activate) {
        Set<O> held = owners.get(key);
        if (held != null) return held.add(owner);
        activate.run();
        held = new HashSet<>(); held.add(owner); owners.put(key, held); return true;
    }
    public boolean release(K key, O owner, Runnable deactivate) {
        Set<O> held = owners.get(key);
        if (held == null || !held.remove(owner)) return false;
        if (held.isEmpty()) { owners.remove(key); deactivate.run(); }
        return true;
    }
    public boolean isEmpty() { return owners.isEmpty(); }
}

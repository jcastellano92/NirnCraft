package com.nirncraft.atlas;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Keeps an allocated stage reachable even if constructing its Java owner fails. */
final class AtlasStageOwners<K, R, O> {
    private static final class Entry<R, O> {
        final R resource;
        final java.util.ArrayList<R> rejected = new java.util.ArrayList<>();
        O owner;
        Throwable releaseFailure;
        Entry(R resource) { this.resource = resource; }
    }
    private final ConcurrentHashMap<K, Entry<R, O>> entries = new ConcurrentHashMap<>();

    O prepare(K key, R resource, Function<R, O> make) {
        Entry<R, O> entry = new Entry<>(resource);
        for (;;) {
            synchronized (entry) {
                Entry<R, O> prior = entries.putIfAbsent(key, entry);
                if (prior == null) {
                    entry.owner = make.apply(resource);
                    return entry.owner;
                }
                synchronized (prior) {
                    if (entries.get(key) != prior) continue;
                    // The rejected allocation is still a native resource of this generator; retain it for release.
                    prior.rejected.add(resource);
                    throw new IllegalStateException("stage owner already exists");
                }
            }
        }
    }

    O get(K key) {
        Entry<R, O> entry = entries.get(key);
        if (entry == null) return null;
        synchronized (entry) { return entries.get(key) == entry ? entry.owner : null; }
    }

    void release(K key, Consumer<O> closeOwner, Consumer<R> closeIncomplete) {
        Entry<R, O> entry = entries.get(key);
        if (entry == null) return;
        synchronized (entry) {
            if (entries.get(key) != entry) return;
            if (entry.releaseFailure != null) throw new IllegalStateException("stage release previously failed", entry.releaseFailure);
            try {
            while (!entry.rejected.isEmpty()) {
                R rejected = entry.rejected.get(entry.rejected.size() - 1);
                closeIncomplete.accept(rejected);
                entry.rejected.remove(entry.rejected.size() - 1);
            }
            if (entry.owner != null) closeOwner.accept(entry.owner);
            else closeIncomplete.accept(entry.resource);
            entries.remove(key, entry);
            } catch (RuntimeException | Error failure) {
                entry.releaseFailure = failure;
                throw failure;
            }
        }
    }
}

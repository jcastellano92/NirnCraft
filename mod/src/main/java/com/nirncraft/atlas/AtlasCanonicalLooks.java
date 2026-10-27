package com.nirncraft.atlas;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

/** Assign each equal appearance key the smallest stable state id, independent of traversal order. */
final class AtlasCanonicalLooks<K> {
    private final Map<K, Integer> minimum = new HashMap<>();

    void add(K key, int id) {
        minimum.merge(key, id, Math::min);
    }

    int id(K key) {
        Integer id = minimum.get(key);
        if (id == null) throw new IllegalArgumentException("unknown appearance");
        return id;
    }

    int size() {
        return minimum.size();
    }

    /** Each model identity has its own table. Stale callers cannot replace another model's table. */
    static final class Epochs<B, S> {
        private static final class ModelKey extends WeakReference<Object> {
            final int hash;
            ModelKey(Object model, ReferenceQueue<Object> queue) {
                super(model, queue);
                hash = System.identityHashCode(model);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof ModelKey key)) return false;
                Object model = get();
                return model != null && model == key.get();
            }
        }
        private static final class Generation<B, S> {
            final ReferenceQueue<Object> dead = new ReferenceQueue<>();
            final ConcurrentHashMap<ModelKey, Epoch<B, S>> models = new ConcurrentHashMap<>();
        }
        static final class Epoch<B, S> {
            private final Generation<B, S> generation;
            final ConcurrentHashMap<B, Map<S, Integer>> blocks = new ConcurrentHashMap<>();
            private Epoch(Generation<B, S> generation) { this.generation = generation; }
        }
        private final AtomicReference<Generation<B, S>> current = new AtomicReference<>(new Generation<>());

        Epoch<B, S> forModels(Object models) {
            if (models == null) throw new IllegalArgumentException("missing block models");
            Generation<B, S> generation = current.get();
            ModelKey gone = (ModelKey) generation.dead.poll();
            if (gone != null) generation.models.remove(gone);
            ModelKey key = new ModelKey(models, generation.dead);
            Epoch<B, S> found = generation.models.get(key);
            if (found != null) return found;
            Epoch<B, S> made = new Epoch<>(generation);
            Epoch<B, S> previous = generation.models.putIfAbsent(key, made);
            return previous != null ? previous : made;
        }

        void reset() { current.set(new Generation<>()); }
        boolean isCurrent(Epoch<B, S> epoch) { return current.get() == epoch.generation; }
    }

    /** FNV-1a over little-endian 32-bit words, the native ingest fingerprint's byte order. */
    static long fingerprint(int[] words, int start, int stride, int count) {
        long h = 1469598103934665603L;
        for (int i = 0; i < count; i++) {
            int word = words[start + i * stride];
            for (int b = 0; b < 4; b++) h = (h ^ ((word >>> (b * 8)) & 255)) * 1099511628211L;
        }
        return h;
    }
}

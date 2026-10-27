package com.nirncraft.atlas;

import java.util.concurrent.atomic.AtomicReference;

/** Publishes one owner while ensuring a failed start cannot leave it visible. */
final class AtlasOwnedSlot<T> {
    private final AtomicReference<T> current = new AtomicReference<>();

    T get() { return current.get(); }

    void publish(T owner, Runnable start) {
        if (!current.compareAndSet(null, owner)) throw new IllegalStateException("previous owner has not drained");
        try { start.run(); }
        catch (RuntimeException | Error failure) {
            current.compareAndSet(owner, null);
            throw failure;
        }
    }

    void clear(T owner) { current.compareAndSet(owner, null); }
}

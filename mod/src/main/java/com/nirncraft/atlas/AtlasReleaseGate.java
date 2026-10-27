package com.nirncraft.atlas;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Concurrent users may enter together; closing admission never waits for an in-flight native call. */
final class AtlasReleaseGate {
    private static final long CLOSED = Long.MIN_VALUE;
    private final AtomicLong state = new AtomicLong();
    private final CountDownLatch drained = new CountDownLatch(1);

    Lease enter() {
        for (;;) {
            long before = state.get();
            if ((before & CLOSED) != 0) return null;
            if (before == Long.MAX_VALUE) throw new IllegalStateException("too many native users");
            if (state.compareAndSet(before, before + 1)) return new Lease();
        }
    }

    void closeAdmission() {
        long before = state.getAndUpdate(v -> v | CLOSED);
        if ((before & Long.MAX_VALUE) == 0) drained.countDown();
    }

    /** Wait to completion even when interrupted; callers restore the interrupt only after teardown is safe. */
    boolean awaitDrained() {
        boolean interrupted = false;
        for (;;) {
            try { drained.await(); return interrupted; }
            catch (InterruptedException e) { interrupted = true; }
        }
    }

    /** Native copy waiting can take the context mutex, so only an owned release worker performs it. */
    static java.util.concurrent.CompletableFuture<Void> waitCopyAsync(java.util.function.BooleanSupplier wait) {
        var result = new java.util.concurrent.CompletableFuture<Void>();
        try {
            Thread waiter = new Thread(() -> {
                try {
                    while (!wait.getAsBoolean()) { /* bounded native waits; retain ownership */ }
                    result.complete(null);
                } catch (Throwable t) { result.completeExceptionally(t); }
            }, "atlas context copy release");
            waiter.setDaemon(true);
            waiter.start();
        } catch (Throwable t) { result.completeExceptionally(t); }
        return result;
    }

    final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            long after = state.decrementAndGet();
            if (after == CLOSED) drained.countDown();
        }
    }
}

package com.nirncraft.atlas;

import java.util.concurrent.locks.LockSupport;

/**
 * atlas_info's JSON for the callers that ask every tick (the loading phase's readiness, the profiler line, the gates).
 * The core's call walks the world's, the grid's and the generator's books; a frame of the render thread sat 1 to 4.6 s inside
 * it while the generator stored chunks. A thread of its own reads it (about ten times a second while anyone asks) and keeps the
 * last answer; {@link #latest} hands that out and never waits for the core. {@code atlas_info} no longer waits for the
 * generator's locks either (try-locks, the last numbers when busy), so this thread is never stuck behind them for long.
 *
 * <p>The answer is of the context that is running: a new context has no answer until the thread has read it.</p>
 */
final class AtlasInfo {
    private AtlasInfo() {}

    private record Snap(AtlasNative.Context ctx, String json, long ns) {}

    private static volatile Snap snap;
    private static volatile long askedNs;
    private static Thread thread;

    /** Readings are kept up for this long after the last ask (then the thread idles until the next). */
    private static final long WARM_NS = 5_000_000_000L;
    private static final long PERIOD_NS = 100_000_000L;

    /** The last answer for the running context, or null (none yet: the next call has it). Never blocks. */
    static String latest() {
        AtlasNative.Context c = AtlasClient.infoContext();
        askedNs = System.nanoTime();
        start();
        Snap s = snap;
        return s != null && s.ctx == c ? s.json : null;
    }

    /**
     * The answer of at most {@code maxAgeMs} ago; a direct read (this thread, the core's call) when the thread's is older or
     * there is none. For diagnostics that must be current (the probe verbs); not for per-tick callers.
     */
    static String fresh(long maxAgeMs) {
        var lease = AtlasClient.acquireInfoContext();
        if (lease == null) return null;
        try (lease) {
            AtlasNative.Context c = lease.context();
            Snap s = snap;
            if (s != null && s.ctx == c && System.nanoTime() - s.ns <= maxAgeMs * 1_000_000L) return s.json;
            return read(c);
        }
    }

    private static String read(AtlasNative.Context c) {
        String j = c.info();
        snap = new Snap(c, j, System.nanoTime());
        return j;
    }

    private static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(AtlasInfo::run, "nirncraft-atlas-info");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        for (;;) {
            LockSupport.parkNanos(PERIOD_NS);
            try {
                if (System.nanoTime() - askedNs > WARM_NS) continue;
                var lease = AtlasClient.acquireInfoContext();
                if (lease != null) {
                    try (lease) { read(lease.context()); }
                }
            } catch (RuntimeException e) {
                // (a read that failed is a stale answer: the next one tries again)
            }
        }
    }
}

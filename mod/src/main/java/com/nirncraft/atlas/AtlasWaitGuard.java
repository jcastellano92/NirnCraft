package com.nirncraft.atlas;

/**
 * A readiness wait that must drain: it blocks while its count falls now and then, and is released (no longer blocks) when
 * the count has not fallen for {@link #STALL_NS} while it was being waited on. A count of 0 ends the wait and re-arms it.
 * The clock only runs while someone waits on it: a call after a gap of more than {@link #GAP_NS} (an earlier condition
 * blocked meanwhile) starts it afresh. Any thread.
 */
final class AtlasWaitGuard {
    static final long STALL_NS = Long.getLong("nirncraft.atlas.waitGuard.stallMs", 30_000L) * 1_000_000L;
    static final long GAP_NS = 2_000_000_000L;

    private long last = -1, sinceNs, seenNs;
    private boolean released;

    /**
     * Whether a count of {@code count} still blocks at {@code now}. Returns 1 when it blocks, 0 when it does not, -1 when
     * this call released it (the caller logs why).
     */
    synchronized int blocks(long count, long now) {
        if (count <= 0) {
            last = -1;
            released = false;
            seenNs = now;
            return 0;
        }
        if (last < 0 || count < last || now - seenNs > GAP_NS) sinceNs = now;  // (drained, or waited on afresh)
        last = count;
        seenNs = now;
        if (released) return 0;
        if (now - sinceNs < STALL_NS) return 1;
        released = true;
        return -1;
    }
}

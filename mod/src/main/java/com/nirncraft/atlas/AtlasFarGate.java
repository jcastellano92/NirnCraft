package com.nirncraft.atlas;

/**
 * The finer levels' horizon gate: the far tiles the loading phase (and AtlasReady) waits for, counted from the very set
 * farStep serves, and stall diagnostics which never claim missing tiles are present.
 *
 * <p>The set is each gate level's window (coarseWindow: {@code side} chunks of the level from (ox, oz)), cut in tiles of 16
 * chunks: the tiles with at least a column in it. A window of 64 chunks covers 4 x 4 tiles when its origin is on a tile
 * edge and 5 x 5 otherwise; before this the gate waited for a fixed 5 x 5 per level, which a spawn on the tile grid (near
 * the origin) can never reach: 32 of 50 for good, the player held, and the outer rings deferred behind it (outerRingsWait).
 *
 * <p>farStep scans the windows on the generator's thread and reports what it saw ({@link #scanned}); the readers (the
 * loading screen, the readiness, the outer rings' wait) read {@link #up} on theirs. When the gate is what readiness waits
 * on and neither its count nor its set changed for {@link #STALL_NS}, a warning is emitted while the observed count remains pending.
 */
final class AtlasFarGate {
    /** No progress for this long while the gate blocks: warn. */
    static final long STALL_NS = Long.getLong("nirncraft.atlas.farGate.stallMs", 30_000L) * 1_000_000L;

    private volatile int total = -1, up, ask, bake, unplaced;
    private volatile long sig, changeNs, blockNs;
    private volatile boolean released;

    // Called only by the generator worker: alternate far and near turns while the gate needs work.
    private boolean nextFar = true;
    boolean nextReloadTurn(boolean eligible) {
        if (!eligible) { nextFar = true; return false; }
        boolean result = nextFar;
        nextFar = !nextFar;
        return result;
    }

    /** Tiles of 16 chunks with at least a column in a window of {@code side} chunks from (ox, oz). */
    static int tilesCovering(int ox, int oz, int side) {
        if (side <= 0) return 0;
        int nx = Math.floorDiv(ox + side - 1, 16) - Math.floorDiv(ox, 16) + 1;
        int nz = Math.floorDiv(oz + side - 1, 16) - Math.floorDiv(oz, 16) + 1;
        return nx * nz;
    }

    /**
     * (farStep, its thread) One scan: {@code total} tiles in the gate's windows (a level without a window yet counts
     * {@code nominal} each, as not up), {@code up} of them in the grid, {@code ask} the rest farStep still asks for,
     * {@code bake} the rest the coarse bake's thread looks after, {@code unplaced} levels without a window; {@code sig}
     * changes when a window is placed afresh.
     */
    void scanned(int total, int up, int ask, int bake, int unplaced, long sig, long now) {
        if (total != this.total || up != this.up || sig != this.sig) released = false;  // (a new set: counted afresh)
        if (total != this.total || up != this.up || sig != this.sig) changeNs = now;
        if (up >= total) released = false;
        this.up = up;
        this.ask = ask;
        this.bake = bake;
        this.unplaced = unplaced;
        this.sig = sig;
        this.total = total;
    }

    /** farStep has scanned the windows at least once. */
    boolean known() {
        return total >= 0;
    }

    int total() {
        return total;
    }

    /** The count as farStep saw it (not the release). */
    int seen() {
        return Math.min(up, Math.max(total, 0));
    }

    boolean released() {
        return released;
    }

    /**
     * The observed tile count. {@code blocking} means the near and coarse prerequisites are ready.
     * Returns -1 - observed count once on a stall so the caller logs it without reporting missing tiles as present.
     */
    int up(boolean blocking, long now) {
        int t = total, u = Math.min(up, Math.max(t, 0));
        if (t < 0 || u >= t) return u;
        if (released) return u;
        if (!blocking) {
            blockNs = 0;
            return u;
        }
        long b = blockNs;
        if (b == 0) blockNs = b = now;
        if (now - Math.max(b, changeNs) < STALL_NS) return u;
        released = true;
        return -1 - u;
    }

    /** Why the count stands where it is (the stall guard's log). */
    String why() {
        int t = total;
        if (t < 0) return "farStep has not scanned the windows yet";
        return String.format("%d of %d tiles up: %d farStep still asks for, %d the coarse bake looks after, %d levels without a window%s",
                seen(), t, ask, bake, unplaced, released ? " (stalled: no progress for " + STALL_NS / 1_000_000_000L + " s)" : "");
    }
}

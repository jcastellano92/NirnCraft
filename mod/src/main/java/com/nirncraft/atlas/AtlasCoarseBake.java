package com.nirncraft.atlas;

import com.nirncraft.tamriel.TamrielMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * The whole-map coarse bake (docs/ATLAS-GPU-LOOP.md section 3, step 6; native gen_bake.cpp): the far path run over every
 * tile of the coarse levels the map touches, into the self-baking cache (the far cache's own format, gencache.hpp), so
 * that the distant view is complete from the first frame and a launch only loads it.
 * <ul>
 *   <li><b>Loading phase, first launch of a world:</b> levels 6, 5, 4, 3 (the coarsest first: the floor under every ray
 *       comes first) are made, spawn tiles first and then outward, the loading screen showing the progress. A tile in a
 *       level's window goes into the grid as it is made (atlas_gen_far_ingest). Later launches find them known and only
 *       load.</li>
 *   <li><b>Boot load, every launch:</b> every tile that covers a coarse level's window, coarsest level first and the
 *       nearest first, is put into the grid from the cache (atlas_gen_far_cache_ingest: read from disk, expanded on the
 *       GPU), on this thread, independent of the near window's work. The thread goes on as the windows slide, so the far
 *       view does not depend on AtlasClientGen.farStep any more: farStep only makes the tiles this does not own (the cache
 *       lacks them and the bake gave them up, or lies outside its plan), the live fallback.</li>
 *   <li><b>Level 2, in the background:</b> after the player has control, spawn tiles first and then outward, a tile at a time
 *       while the near window has nothing to make, through the same far path (paced as the far field is: the leftovers of
 *       the GPU budget).</li>
 * </ul>
 * Keying: the cache's set directory, per world (so per seed) and the generator's fingerprint (program, ingest table, map,
 * stages and the far identity: AtlasClientGen.run); another fingerprint opens another set and the old one is deleted, so
 * the bake is made again. TODO (step 7): per-stage, per-region fingerprints (a directory entry's buildId), so that a
 * changed stage rebakes only the regions it touches, and the far tiles' own set apart from the near chunks' (a changed
 * decoration stage that does not touch the far path rebakes the horizon today).
 */
final class AtlasCoarseBake implements Runnable {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.bake"));
    /** Levels made in the loading phase (coarsest first), and the background level. */
    static final int LOAD_HI = 6, LOAD_LO = 3, BACKGROUND = 2;
    /** The map's bounds in blocks (TamrielMap: the generator's map inputs), grown by MARGIN. */
    static final int MIN_X = TamrielMap.MIN_X, MIN_Z = TamrielMap.MIN_Z, MAX_X = TamrielMap.MAX_X, MAX_Z = TamrielMap.MAX_Z;
    static final int MARGIN = Integer.getInteger("nirncraft.atlas.bake.margin", 0);
    /** Measurements: level 2 is made whether or not the near window has work (the worst case for the frames). */
    static final boolean ALWAYS = Boolean.getBoolean("nirncraft.atlas.bake.always");

    private static volatile AtlasCoarseBake active;

    /** Phases: -1 off, 0 waiting for the camera and the windows, 1 making levels 3-6, 2 loaded (levels 3-6 whole), 3 level 2, 4 whole. */
    private volatile int phase;
    private final AtlasGenNative.Gen gen;
    private final Thread thread;
    private volatile boolean running = true;
    // a tile in the grid: {window origin x, z, jumps when it was put, partial (1: the grid refused chunks), next try (ns)}
    private final Map<Long, long[]> served = new ConcurrentHashMap<>();
    private final Set<Long> failed = ConcurrentHashMap.newKeySet();
    private volatile AtlasGenNative.Gen.BakeInfo info;     // the loading bake's, then level 2's
    private volatile int loadTotal, loadDone;              // levels 3-6: tiles planned, finished (known, made or given up)
    private volatile int servePending;                     // known tiles of the windows not in the grid yet (last pass)
    private volatile long startNs, loadedNs, windowsNs, wholeNs;  // (when: the bake began, levels 3-6 whole, their windows in the grid, level 2 whole)
    private volatile long servedTiles, serveNs, bakeTilesLoad, bakeTilesBackground, stepNs;
    private volatile int centreX, centreZ;

    private AtlasCoarseBake(AtlasGenNative.Gen gen) {
        this.gen = gen;
        thread = new Thread(this, "atlas coarse bake");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    /** Starts the bake on a generator whose cache is armed (AtlasClientGen.run); null when off or the library lacks it. */
    static AtlasCoarseBake start(AtlasGenNative.Gen gen) {
        if (!ON || !gen.bakeSupported() || !gen.farIngestSupported()) return null;
        AtlasCoarseBake b = new AtlasCoarseBake(gen);
        active = b;
        b.thread.start();
        return b;
    }

    void stop() {
        running = false;
        if (active == this) active = null;
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!thread.isAlive()) gen.bakeEnd();
    }

    // ---- hooks of AtlasClientGen (farStep, gridReports) ----

    private static long key(int level, int tx, int tz) {
        return (long) level << 56 ^ ((long) tx & 0xFFFFFFFL) << 28 ^ ((long) tz & 0xFFFFFFFL);
    }

    /** The columns of tile (tx, tz) inside the window at (ox, oz) of `side` chunks. */
    private static int cover(int tx, int tz, long ox, long oz, int side) {
        long ovx = Math.max(0, Math.min(16L * (tx + 1), ox + side) - Math.max(16L * tx, ox));
        long ovz = Math.max(0, Math.min(16L * (tz + 1), oz + side) - Math.max(16L * tz, oz));
        return (int) (ovx * ovz);
    }

    private static boolean inPlan(int level, int tx, int tz) {
        if (level < BACKGROUND || level > LOAD_HI) return false;
        int span = 256 << level;
        return tx >= Math.floorDiv(MIN_X - MARGIN, span) && tx <= Math.floorDiv(MAX_X - 1 + MARGIN, span)
                && tz >= Math.floorDiv(MIN_Z - MARGIN, span) && tz <= Math.floorDiv(MAX_Z - 1 + MARGIN, span);
    }

    /**
     * (farStep) Whether the bake's thread looks after this tile of a level's window `win` (coarseWindow's numbers): it is
     * in the grid and up to date, or it is to be made (levels 3-6, within the plan) or loaded (the cache knows it). farStep
     * leaves those alone; the others are its fallback.
     */
    static boolean owns(int level, int tx, int tz, int[] win) {
        AtlasCoarseBake b = active;
        return b != null && b.owned(level, tx, tz, win);
    }

    private boolean owned(int level, int tx, int tz, int[] win) {
        if (phase <= 0 || level < BACKGROUND || level > LOAD_HI) return false;
        long k = key(level, tx, tz);
        if (failed.contains(k)) return false;
        long[] sv = served.get(k);
        if (sv != null && sv[2] == win[4] && cover(tx, tz, win[0], win[1], win[2]) - cover(tx, tz, sv[0], sv[1], win[2]) < 16) return true;
        if (level >= LOAD_LO && inPlan(level, tx, tz)) return phase < 2 || gen.farKnown(level, tx, tz);
        return gen.farKnown(level, tx, tz);
    }

    /** (gridReports) The grid gave up a chunk of this tile: it is put again. */
    static void dropped(int level, int tx, int tz) {
        AtlasCoarseBake b = active;
        if (b != null) b.served.remove(key(level, tx, tz));
    }

    // ---- the loading screen's view ----

    /** What the loading screen shows: phase, levels 3-6 finished / planned, known tiles of the windows still to load. */
    record Progress(int phase, int total, int done, int servePending, boolean loaded, double seconds) {}

    static Progress progress() {
        AtlasCoarseBake b = active;
        if (b == null) return null;
        long s = b.startNs;
        return new Progress(b.phase, b.loadTotal, b.loadDone, b.servePending, b.loadedAndServed(), s == 0 ? 0 : (System.nanoTime() - s) / 1e9);
    }

    /** Levels 3-6 whole (known, made or given up) and every known tile of the windows in the grid. */
    boolean loadedAndServed() {
        return phase >= 2 && windowsNs != 0;
    }

    /** One line for the profile: the bake's state and times. */
    static String line() {
        AtlasCoarseBake b = active;
        if (b == null) return null;
        AtlasGenNative.Gen.BakeInfo i = b.info;
        long s = b.startNs;
        return String.format("bake{phase %d levels 3-6 %d/%d loaded at %.1f s, windows at %.1f s, level 2 %s, served %d tiles %.0f ms, tiles made %d+%d, failed %d, step %.0f ms, queued %d}",
                b.phase, b.loadDone, b.loadTotal, since(s, b.loadedNs), since(s, b.windowsNs), b.wholeNs != 0 ? String.format("whole at %.1f s", since(s, b.wholeNs)) : "pending",
                b.servedTiles, b.serveNs / 1e6, b.bakeTilesLoad, b.bakeTilesBackground, b.failed.size(), b.stepNs / 1e6, i == null ? 0 : i.queued());
    }

    private static double since(long t0, long t) {
        return t0 == 0 || t == 0 ? -1 : (t - t0) / 1e9;
    }

    // ---- the thread ----

    @Override
    public void run() {
        try {
            loop();
        } catch (Throwable t) {
            if (running) com.nirncraft.NirnCraft.LOGGER.warn("atlas coarse bake stopped (farStep takes the far tiles)", t);
            phase = -1;
        }
    }

    private boolean windowsPlaced() {
        int[] w = gen.coarseWindow(LOAD_HI);
        return w != null && w[3] != 0;
    }

    private void loop() {
        while (running && !(AtlasClientGen.hasCamera() && windowsPlaced())) LockSupport.parkNanos(25_000_000L);
        if (!running) return;
        centreX = (int) Math.floor(AtlasClientGen.cameraX());
        centreZ = (int) Math.floor(AtlasClientGen.cameraZ());
        startNs = System.nanoTime();
        AtlasGenNative.Gen.BakeInfo b = gen.bakeBegin(MIN_X, MIN_Z, MAX_X, MAX_Z, centreX, centreZ, LOAD_HI, LOAD_LO, MARGIN, 0);
        if (b == null) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas coarse bake: off (no cache armed for this world)");
            phase = -1;
            return;
        }
        info = b;
        loadTotal = b.total();
        loadDone = b.knownTotal();
        phase = 1;
        com.nirncraft.NirnCraft.LOGGER.info("atlas coarse bake: levels {}-{} over [{}, {}) x [{}, {}), {} tiles, {} on disk already, from ({}, {})",
                LOAD_LO, LOAD_HI, MIN_X - MARGIN, MAX_X + MARGIN, MIN_Z - MARGIN, MAX_Z + MARGIN, b.total(), b.knownTotal(), centreX, centreZ);
        // levels 3-6: tiles on disk are loaded, the others made (and loaded as they are made), the loading phase
        while (running) {
            servePass(8);
            if (loadDone >= loadTotal) break;
            step(1, true);
        }
        if (!running) return;
        loadedNs = System.nanoTime();
        phase = 2;
        // their windows in the grid (every known tile of the levels' windows), the loading phase's last wait
        while (running && servePending > 0) servePass(16);
        windowsNs = System.nanoTime();
        com.nirncraft.NirnCraft.LOGGER.info("atlas coarse bake: levels {}-{} whole after {} s (made {} tiles, {} given up), their windows in the grid after {} s ({} tiles loaded from disk)",
                LOAD_LO, LOAD_HI, String.format("%.1f", since(startNs, loadedNs)), bakeTilesLoad, failed.size(), String.format("%.1f", since(startNs, windowsNs)), servedTiles);
        // level 2: after the player has control, from the near window's idle time; the thread serves the windows meanwhile
        b = gen.bakeBegin(MIN_X, MIN_Z, MAX_X, MAX_Z, centreX, centreZ, BACKGROUND, BACKGROUND, MARGIN, 0);
        boolean whole = b == null || b.knownTotal() >= b.total();
        if (b != null) info = b;
        phase = 3;
        long lastStepNs = 0;
        while (running) {
            servePass(8);
            if (!whole && !AtlasLoadingScreen.holding()) {
                boolean[] idle = AtlasClientGen.idle();
                long now = System.nanoTime();
                if ((ALWAYS || idle == null || idle[0]) && now - lastStepNs > 30_000_000L) {
                    step(1, false);
                    lastStepNs = System.nanoTime();
                    AtlasGenNative.Gen.BakeInfo i = info;
                    whole = i.madeTotal() + i.failedTotal() + i.knownTotal() >= i.total();
                    if (whole) {
                        wholeNs = System.nanoTime();
                        com.nirncraft.NirnCraft.LOGGER.info("atlas coarse bake: level 2 whole {} s after the start ({} tiles made, {} given up)",
                                String.format("%.1f", since(startNs, wholeNs)), bakeTilesBackground, i.failedTotal());
                    }
                }
            } else if (whole && wholeNs == 0) {
                wholeNs = System.nanoTime();
            }
            if (whole && phase == 3) phase = 4;
            LockSupport.parkNanos(whole || AtlasLoadingScreen.holding() ? 20_000_000L : 5_000_000L);
        }
    }

    /** One bake step: the next tile; a tile in a window is in the grid already (the far path put it there). */
    private void step(int tiles, boolean loading) {
        long t0 = System.nanoTime();
        AtlasGenNative.Gen.BakeInfo i = gen.bakeStep(tiles);
        stepNs += System.nanoTime() - t0;
        info = i;
        if (loading) loadDone = i.knownTotal() + i.madeTotal() + i.failedTotal();
        int[] last = i.last();
        if (last[3] == 1) {
            if (loading) bakeTilesLoad++;
            else bakeTilesBackground++;
            int[] win = gen.coarseWindow(last[0]);
            if (win != null && win[3] != 0) served.put(key(last[0], last[1], last[2]), new long[]{win[0], win[1], win[4], 0, 0});
        } else if (last[3] == 2) {
            failed.add(key(last[0], last[1], last[2]));
            com.nirncraft.NirnCraft.LOGGER.warn("atlas coarse bake: level {} tile ({}, {}) given up (status {}): the live far path makes it", last[0], last[1], last[2], i.status());
        }
        if (i.status() != 0 && last[3] != 2) {  // (the device is gone, or worse: farStep's path stays)
            phase = -1;
            running = false;
        }
        // the far buffers still read, or the cache's thread behind: a frame, or a little time, frees them; near work goes first
        LockSupport.parkNanos(i.busy() == 0 ? (loading ? 2_000_000L : 0) : i.busy() == 1 ? 3_000_000L : 25_000_000L);
    }

    /** Puts up to `max` known tiles of the levels' windows into the grid (coarsest level first, the nearest first). */
    private void servePass(int max) {
        long now = System.nanoTime();
        double x = AtlasClientGen.cameraX(), z = AtlasClientGen.cameraZ();
        int pending = 0, did = 0;
        for (int k = LOAD_HI; k >= BACKGROUND; k--) {
            int[] win = gen.coarseWindow(k);
            if (win == null || win[3] == 0) continue;
            int span = 256 << k, side = win[2];
            List<long[]> cand = new ArrayList<>();
            for (int tz = Math.floorDiv(win[1], 16); tz <= Math.floorDiv(win[1] + side - 1, 16); tz++)
                for (int tx = Math.floorDiv(win[0], 16); tx <= Math.floorDiv(win[0] + side - 1, 16); tx++) {
                    if (cover(tx, tz, win[0], win[1], side) == 0) continue;
                    long kk = key(k, tx, tz);
                    long[] sv = served.get(kk);
                    if (sv != null && sv[2] == win[4] && cover(tx, tz, win[0], win[1], side) - cover(tx, tz, sv[0], sv[1], side) < 16
                            && (sv[3] == 0 || now < sv[4])) continue;
                    if (failed.contains(kk) || !gen.farKnown(k, tx, tz)) continue;
                    double cx = (tx + 0.5) * span - x, cz = (tz + 0.5) * span - z;
                    cand.add(new long[]{(long) Math.sqrt(cx * cx + cz * cz), tx, tz});
                }
            cand.sort((a, c) -> Long.compare(a[0], c[0]));
            for (long[] c : cand) {
                if (did >= max) {
                    pending++;
                    continue;
                }
                int tx = (int) c[1], tz = (int) c[2];
                long t0 = System.nanoTime();
                AtlasGenNative.Gen.FarIngest r = gen.farServe(k, tx, tz);
                serveNs += System.nanoTime() - t0;
                long kk = key(k, tx, tz);
                if (r == null) {
                    failed.add(kk);  // (a record unreadable: the live far path makes it)
                    continue;
                }
                boolean partial = r.taken() < r.chunks();
                long[] prev = served.get(kk);
                long tries = partial && prev != null ? prev[3] + 1 : partial ? 1 : 0;
                // (chunks refused for room: again in 4, 8, 16 s, three times, as farStep's retries)
                served.put(kk, new long[]{win[0], win[1], win[4], tries > 3 ? 0 : tries, now + (4_000_000_000L << Math.min(tries, 3))});
                servedTiles++;
                did++;
            }
        }
        servePending = pending;
        if (did == 0) LockSupport.parkNanos(1_000_000L);
    }
}

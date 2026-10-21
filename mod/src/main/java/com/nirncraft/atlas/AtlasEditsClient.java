package com.nirncraft.atlas;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far field's change feed, client side (docs/ATLAS-DELTAS.md): the changes the server pushed for the tiles in this
 * player's far window, kept per 256-block tile. A far tile is patched with them before it goes up (AtlasTileUpload),
 * and tiles already up are put up again on the far-field worker (their refinement fade blends the change in). Lights
 * become one far light group per tile.
 */
final class AtlasEditsClient {
    private AtlasEditsClient() {}

    /**
     * A column as the server has it: top (y of the ground's top block), top / under block state ids, water top or MIN,
     * and the span over the ground (a tree): top (exclusive) or MIN, base or MIN (from the ground), wall / roof ids.
     */
    record Column(int top, int topId, int underId, int water, int spanTop, int spanBase, int wallId, int roofId) {}

    private static final Map<Long, Map<Long, Column>> COLUMNS = new ConcurrentHashMap<>();
    private static final Map<Long, Map<Long, int[]>> LIGHTS = new ConcurrentHashMap<>();  // pos -> {x, y, z, level}
    private static final Set<Long> DIRTY = ConcurrentHashMap.newKeySet();  // LOD-0 tiles with changes not yet up
    static final int LIGHT_GROUPS = 0x200000;

    /** A FarEdits packet (network thread or render thread). */
    static void accept(AtlasNet.FarEdits m) {
        long tk = AtlasEdits.tileKey(m.tx(), m.tz());
        Map<Long, Column> cols = COLUMNS.computeIfAbsent(tk, k -> new ConcurrentHashMap<>());
        int[] c = m.cols();
        for (int i = 0; i + AtlasEdits.COL - 1 < c.length; i += AtlasEdits.COL)
            cols.put(AtlasEdits.colKey(c[i], c[i + 1]),
                    new Column(c[i + 2], c[i + 3], c[i + 4], c[i + 5], c[i + 6], c[i + 7], c[i + 8], c[i + 9]));
        int[] l = m.lights();
        boolean columns = c.length >= AtlasEdits.COL;
        if (l.length > 0) {
            Map<Long, int[]> ls = LIGHTS.computeIfAbsent(tk, k -> new ConcurrentHashMap<>());
            for (int i = 0; i + 3 < l.length; i += 4) {
                long pk = net.minecraft.core.BlockPos.asLong(l[i], l[i + 1], l[i + 2]);
                if (l[i + 3] <= 0) ls.remove(pk);
                else ls.put(pk, new int[] {l[i], l[i + 1], l[i + 2], l[i + 3]});
            }
        }
        // a change to the blocks puts the tile up again at every level; a change to its lights only (a torch lit or
        // placed in the open) is a re-light: the far lights go up, the tiles stay (they were re-uploaded at all 7 LODs)
        if (columns) DIRTY.add(tk);
        else if (l.length > 0) LIGHTS_DIRTY.add(tk);
    }

    private static final Set<Long> LIGHTS_DIRTY = ConcurrentHashMap.newKeySet();  // LOD-0 tiles whose lights changed only

    /** The LOD-0 tiles whose lights changed and nothing else (taken: the caller puts their lights up). */
    static List<Long> takeLightsDirty() {
        List<Long> out = new ArrayList<>(LIGHTS_DIRTY);
        LIGHTS_DIRTY.removeAll(out);
        return out;
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<byte[]> SECTIONS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** A FarSections packet: queued for the far-field worker. */
    static void acceptSections(AtlasNet.FarSections m) {
        for (byte[] b : m.blobs()) SECTIONS.add(b);
    }

    /**
     * Puts the queued real sections up (atlas_upload_section: level 0 exactly, the coarser levels generated from them),
     * block ids to far materials (leaves and grass in the chunk's biome colours), until `deadline`. Far-field worker.
     */
    /** Where the camera is and how far the near field reaches (blocks): changes wholly inside it change nothing seen. */
    static volatile double viewX, viewZ, viewNear;

    /** Whether box [x0, x1) x [z0, z1) lies wholly inside the near field (vanilla draws it; the far field is behind). */
    static boolean underNearField(double x0, double z0, double x1, double z1) {
        double dx = Math.max(Math.abs(x0 - viewX), Math.abs(x1 - viewX)), dz = Math.max(Math.abs(z0 - viewZ), Math.abs(z1 - viewZ));
        return viewNear > 0 && Math.hypot(dx, dz) < viewNear - 16;
    }

    /** Sections held while the near field covers their chunk (far-field worker only; without the client's feed). */
    private static final List<AtlasSections.Decoded> HELD = new ArrayList<>();
    private static volatile boolean clearHeld;  // clear() runs on other threads: the worker empties HELD itself
    /**
     * Per section up (SectionPos.asLong): a hash of its blocks and tints (far-field worker only). The client's feed and
     * the server's recording both carry the near field's chunks; a section the core already has is not put up again
     * (each upload rebuilds its regions at five levels).
     */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap UPLOADED = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static long prunedAt;

    /** The server's sections for atlas-info: queued, held under the near field, uploaded (section keys). */
    static String state() {
        return "serverSections{queued=" + SECTIONS.size() + " held=" + HELD.size() + " uploaded=" + UPLOADED.size() + "}";
    }

    static int applySections(AtlasNative.Context ctx, AtlasMaterials mats, long deadline) {
        int n = 0;
        short[] out = new short[4096];
        Map<Integer, Short> local = new HashMap<>();
        // without the client's feed: a chunk wholly inside the near field changes nothing on screen (vanilla draws it
        // there), its sections wait until the camera has moved away from it (around a fresh place the server sent
        // 13-24 a pass for 40 s, each rebuilding regions no one could see). With it the near field's blocks are what
        // atlas lights the near pass with: nothing waits
        if (clearHeld) {
            HELD.clear();
            synchronized (UPLOADED) {
                UPLOADED.clear();
            }
            clearHeld = false;
        }
        pruneUploaded();
        boolean hold = !AtlasClientSections.ON;
        java.util.ArrayDeque<AtlasSections.Decoded> ready = new java.util.ArrayDeque<>();
        for (java.util.Iterator<AtlasSections.Decoded> it = HELD.iterator(); it.hasNext(); ) {
            AtlasSections.Decoded d = it.next();
            if (!hold || !underNearField(d.cx() * 16, d.cz() * 16, d.cx() * 16 + 16, d.cz() * 16 + 16)) {
                ready.add(d);
                it.remove();
            }
        }
        for (; System.nanoTime() < deadline; ) {
            AtlasSections.Decoded d = ready.poll();
            boolean client = false;
            if (d == null) {
                d = AtlasClientSections.poll(viewX, viewZ);  // the loaded chunks first, nearest first: what the near light traces
                // then atlas's own generation (AtlasClientGen): the window around the camera, long before vanilla's
                // chunks arrive; those still come here after it and go up only where they differ (the hash below)
                boolean real = d != null;
                if (d == null && !AtlasClientGen.uploadsItself()) d = AtlasClientGen.poll(viewX, viewZ);
                client = d != null;
                if (real) AtlasClientSections.real(d.cx(), d.cz());
            }
            if (d == null) {
                byte[] b = SECTIONS.poll();
                if (b == null) break;
                try {
                    d = AtlasSections.decode(b);
                } catch (java.io.IOException e) {
                    continue;
                }
                if (hold && underNearField(d.cx() * 16, d.cz() * 16, d.cx() * 16 + 16, d.cz() * 16 + 16)) {
                    HELD.add(d);
                    continue;
                }
                // a loaded chunk's own blocks are up (the client's feed: every block, full states): the server's far
                // recording of it (the far field's blocks only) must not replace them
                if (AtlasClientSections.isReal(d.cx(), d.cz())) continue;
            }
            put(ctx, mats, d, client, out, local);
            n++;
        }
        return n;
    }

    /**
     * Chunk d's sections up (atlas_upload_section), those the core does not have already (their hash): the far-field
     * worker's feed, and AtlasClientGen's own uploader (any thread: the materials and the hashes are shared safely).
     */
    static int put(AtlasNative.Context ctx, AtlasMaterials mats, AtlasSections.Decoded d, boolean client, short[] out,
                   Map<Integer, Short> local) {
        int up = 0;
        local.clear();
        for (int s = 0; s < d.sections().length; s++) {
                int[] ids = d.sections()[s];
                if (ids == null) continue;  // solid rock: the tiles' stone
                long key = net.minecraft.core.SectionPos.asLong(d.cx(), d.sy0() + s, d.cz());
                if (client && AtlasClientGen.skipRock(key, ids)) continue;  // generation's tiles stone holds it
                long h = 0xcbf29ce484222325L ^ ((long) d.grass() << 24 ^ d.foliage());
                for (int id : ids) h = (h ^ id) * 0x100000001b3L;
                if (d.grassCols() != null)
                    for (int c = 0; c < 256; c++) h = (h ^ ((long) d.grassCols()[c] << 24 ^ d.foliageCols()[c])) * 0x100000001b3L;
                synchronized (UPLOADED) {
                    if (UPLOADED.containsKey(key) && UPLOADED.get(key) == h) continue;  // the core has these blocks
                    UPLOADED.put(key, h);
                }
                for (int i = 0; i < 4096; i++) {
                    int id = ids[i];
                    if (id == 0) {
                        out[i] = 0;
                        continue;
                    }
                    Short m;
                    if (d.grassCols() != null && AtlasMaterials.biomeTinted(Block.stateById(id))) {
                        // a biome-tinted block (grass, leaves, vines) in its own column's colours (vanilla's blend)
                        int col = (i & 15) + 16 * ((i >> 4) & 15);
                        BlockState st = Block.stateById(id);
                        m = mats.section(st, AtlasMaterials.tint(st, d.grassCols()[col], d.foliageCols()[col]));
                    } else {
                        m = local.get(id);
                        if (m == null) {
                            BlockState st = Block.stateById(id);
                            // (shapes on: one id per block state, its real shape, face tiles and brick; mats.section) in
                            // the tint the near field gives it: the chunk's grass and foliage, a constant, or none
                            m = mats.section(st, AtlasMaterials.tint(st, d.grass(), d.foliage()));
                            local.put(id, m);
                        }
                    }
                    out[i] = m;
                }
                mats.flush(ctx);
                ctx.uploadSection(d.cx(), d.sy0() + s, d.cz(), out);
                up++;
        }
        if (client) AtlasClientSections.up(d.cx(), d.cz());
        return up;
    }

    /** Forgets a claim whose section did not go up after all (it must go up when it comes again). Any thread. */
    static void unclaim(long key) {
        synchronized (UPLOADED) {
            UPLOADED.remove(key);
        }
    }

    /** Whether section key with block hash h must go up (the core does not have it): recorded if so. Any thread. */
    static boolean claim(long key, long h) {
        synchronized (UPLOADED) {
            if (UPLOADED.containsKey(key) && UPLOADED.get(key) == h) return false;
            UPLOADED.put(key, h);
            return true;
        }
    }

    /**
     * Forgets the sections far from the camera (every few seconds): past the server's sections window the core drops
     * them (World::sectionKeep lies further out), and a forgotten one that comes again must go up again.
     */
    private static void pruneUploaded() {
        long now = System.nanoTime();
        if (now - prunedAt < 2_000_000_000L) return;
        prunedAt = now;
        // around the camera as of this frame (the client generator's), else the far field's last pass
        double cx = AtlasClientGen.hasCamera() ? AtlasClientGen.cameraX() : viewX, cz = AtlasClientGen.hasCamera() ? AtlasClientGen.cameraZ() : viewZ;
        int ptx = Math.floorDiv((int) Math.floor(cx), AtlasEdits.TILE), ptz = Math.floorDiv((int) Math.floor(cz), AtlasEdits.TILE);
        synchronized (UPLOADED) {
            UPLOADED.keySet().removeIf((long k) -> {
                int tx = Math.floorDiv(net.minecraft.core.SectionPos.x(k) * 16, AtlasEdits.TILE);
                int tz = Math.floorDiv(net.minecraft.core.SectionPos.z(k) * 16, AtlasEdits.TILE);
                return Math.abs(tx - ptx) > AtlasSections.RADIUS_TILES || Math.abs(tz - ptz) > AtlasSections.RADIUS_TILES;
            });
        }
    }

    /** Forget everything (another world, another server). */
    static void clear() {
        SECTIONS.clear();
        COLUMNS.clear();
        LIGHTS.clear();
        DIRTY.clear();
        LIGHTS_DIRTY.clear();
        clearHeld = true;  // (and the sections up: a new context holds none)
    }

    static boolean any() {
        return !COLUMNS.isEmpty();
    }

    /** The LOD-0 tiles whose changes are not up yet (taken: the caller puts them up). */
    static List<Long> takeDirty() {
        List<Long> out = new ArrayList<>(DIRTY);
        DIRTY.removeAll(out);
        return out;
    }

    /** Palette ids for a patched column's blocks: the column (j * TILE + i) and whether it is its top or under block. */
    interface ColumnIds {
        short id(BlockState s, int column, boolean top);
    }

    /**
     * Patches far tile (lod, tx, tz)'s columns with the changes under them: surface (ground top + 1), water and the top
     * and under blocks (palette ids via ids). A coarse column (2^lod blocks) takes a change at its centre block, or any
     * change in its footprint big enough to show at its size (half a column of height). Returns whether any changed.
     */
    static boolean patch(int lod, int tx, int tz, float[] surface, float[] water, short[] topMat, short[] fillMat,
                         ColumnIds ids) {
        if (COLUMNS.isEmpty()) return false;
        int n = AtlasNative.TILE, s = 1 << lod;
        int bx = tx * (n << lod), bz = tz * (n << lod);
        int t0x = Math.floorDiv(bx, AtlasEdits.TILE), t1x = Math.floorDiv(bx + (n << lod) - 1, AtlasEdits.TILE);
        int t0z = Math.floorDiv(bz, AtlasEdits.TILE), t1z = Math.floorDiv(bz + (n << lod) - 1, AtlasEdits.TILE);
        boolean any = false;
        for (int etz = t0z; etz <= t1z; etz++)
            for (int etx = t0x; etx <= t1x; etx++) {
                Map<Long, Column> cols = COLUMNS.get(AtlasEdits.tileKey(etx, etz));
                if (cols == null) continue;
                for (Map.Entry<Long, Column> e : cols.entrySet()) {
                    int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
                    int i = Math.floorDiv(x - bx, s), j = Math.floorDiv(z - bz, s);
                    if (i < 0 || j < 0 || i >= n || j >= n) continue;
                    int c = j * n + i;
                    Column col = e.getValue();
                    boolean centre = x == bx + i * s + (s >> 1) && z == bz + j * s + (s >> 1);
                    float now = col.top() + 1f;
                    if (s > 1 && !centre && Math.abs(now - surface[c]) < s * 0.5f) continue;  // too small for this LOD
                    surface[c] = now;
                    water[c] = col.water() == Integer.MIN_VALUE ? Float.NaN : col.water() + 1f;
                    BlockState top = Block.stateById(col.topId()), under = Block.stateById(col.underId());
                    topMat[c] = ids.id(top, c, true);
                    fillMat[c] = ids.id(under, c, false);
                    any = true;
                }
            }
        return any;
    }

    /** What a far structure column gets from a real one: wall top, roof top, wall / roof palette ids, base (NaN: ground). */
    interface SpanSink {
        void put(int i, int j, float wallTop, float roofTop, short wall, short roof, float base);

        void clear(int i, int j);
    }

    /**
     * The real columns' spans (trees, as the server has them) over far tile (lod, tx, tz)'s structure columns, in place
     * of the prediction: at LOD 0 and 1 a column the server has is exactly what it has there (a predicted tree or
     * building on it is cleared); coarser, the tallest real span in a column's footprint stands for it (where there is
     * one). Buildings and props are the real ground already (AtlasTileUpload patches the ground).
     */
    static void spans(int lod, int tx, int tz, SpanSink sink, java.util.function.Function<BlockState, Short> ids) {
        if (COLUMNS.isEmpty()) return;
        int n = AtlasNative.TILE, s = 1 << lod;
        int bx = tx * (n << lod), bz = tz * (n << lod);
        int t0x = Math.floorDiv(bx, AtlasEdits.TILE), t1x = Math.floorDiv(bx + (n << lod) - 1, AtlasEdits.TILE);
        int t0z = Math.floorDiv(bz, AtlasEdits.TILE), t1z = Math.floorDiv(bz + (n << lod) - 1, AtlasEdits.TILE);
        float[] best = lod > 1 ? new float[n * n] : null;
        for (int etz = t0z; etz <= t1z; etz++)
            for (int etx = t0x; etx <= t1x; etx++) {
                Map<Long, Column> cols = COLUMNS.get(AtlasEdits.tileKey(etx, etz));
                if (cols == null) continue;
                for (Map.Entry<Long, Column> e : cols.entrySet()) {
                    int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
                    int i = Math.floorDiv(x - bx, s), j = Math.floorDiv(z - bz, s);
                    if (i < 0 || j < 0 || i >= n || j >= n) continue;
                    Column c = e.getValue();
                    boolean span = c.spanTop() != AtlasEdits.NONE;
                    if (best != null) {  // coarse: the tallest real span in the footprint
                        if (!span || c.spanTop() <= best[j * n + i]) continue;
                        best[j * n + i] = c.spanTop();
                    } else if (!span) {
                        sink.clear(i, j);
                        continue;
                    }
                    BlockState wall = Block.stateById(c.wallId()), roof = Block.stateById(c.roofId());
                    float base = c.spanBase() == AtlasEdits.NONE ? Float.NaN : c.spanBase();
                    sink.put(i, j, c.spanTop() - 1, c.spanTop(), ids.apply(wall), ids.apply(roof), base);
                }
            }
    }

    /** The changed lights of LOD-0 tile key tk as far lights (fires and lamps by emission). */
    static List<AtlasLights.Light> lights(long tk) {
        Map<Long, int[]> ls = LIGHTS.get(tk);
        List<AtlasLights.Light> out = new ArrayList<>();
        if (ls == null) return out;
        for (int[] l : ls.values()) out.add(AtlasLights.fire(l[0] + 0.5, l[1] + 0.5, l[2] + 0.5, Math.min(1f, l[3] / 15f)));
        return out;
    }

    /** The light group of LOD-0 tile key tk (unique for 260 km either way). */
    static int lightGroup(long tk) {
        int tx = (int) (tk >> 32), tz = (int) tk;
        return LIGHT_GROUPS + ((tx & 0x3FF) << 10 | (tz & 0x3FF));
    }
}

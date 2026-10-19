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
        if (l.length > 0) {
            Map<Long, int[]> ls = LIGHTS.computeIfAbsent(tk, k -> new ConcurrentHashMap<>());
            for (int i = 0; i + 3 < l.length; i += 4) {
                long pk = net.minecraft.core.BlockPos.asLong(l[i], l[i + 1], l[i + 2]);
                if (l[i + 3] <= 0) ls.remove(pk);
                else ls.put(pk, new int[] {l[i], l[i + 1], l[i + 2], l[i + 3]});
            }
        }
        DIRTY.add(tk);
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
    static int applySections(AtlasNative.Context ctx, AtlasMaterials mats, long deadline) {
        int n = 0;
        short[] out = new short[4096];
        Map<Integer, Short> local = new HashMap<>();
        for (byte[] b; System.nanoTime() < deadline && (b = SECTIONS.poll()) != null; ) {
            AtlasSections.Decoded d;
            try {
                d = AtlasSections.decode(b);
            } catch (java.io.IOException e) {
                continue;
            }
            local.clear();
            for (int s = 0; s < d.sections().length; s++) {
                int[] ids = d.sections()[s];
                for (int i = 0; i < 4096; i++) {
                    int id = ids[i];
                    if (id == 0) {
                        out[i] = 0;
                        continue;
                    }
                    Short m = local.get(id);
                    if (m == null) {
                        BlockState st = Block.stateById(id);
                        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                        // (shapes on: one id per far block state, its real shape and face tiles; mats.section)
                        if (AtlasMaterials.biomeTinted(st))
                            m = mats.section(st, name.contains("birch") ? 0x80A755 : name.contains("spruce") ? 0x619961 : d.foliage());
                        else if (name.equals("grass_block")) m = mats.section(st, d.grass());
                        else m = mats.section(st, -1);
                        local.put(id, m);
                    }
                    out[i] = m;
                }
                mats.flush(ctx);
                ctx.uploadSection(d.cx(), d.sy0() + s, d.cz(), out);
            }
            n++;
        }
        return n;
    }

    /** Forget everything (another world, another server). */
    static void clear() {
        SECTIONS.clear();
        COLUMNS.clear();
        LIGHTS.clear();
        DIRTY.clear();
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

    /**
     * Patches far tile (lod, tx, tz)'s columns with the changes under them: surface (ground top + 1), water and the top
     * and under blocks (palette ids via ids). A coarse column (2^lod blocks) takes a change at its centre block, or any
     * change in its footprint big enough to show at its size (half a column of height). Returns whether any changed.
     */
    static boolean patch(int lod, int tx, int tz, float[] surface, float[] water, short[] topMat, short[] fillMat,
                         java.util.function.Function<BlockState, Short> ids) {
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
                    topMat[c] = ids.apply(top);
                    fillMat[c] = ids.apply(under);
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

package com.nirncraft.atlas.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Whether the world the client collides with is the world the GPU drew (docs/ATLAS-GPU-LOOP.md section 4 gate): for each
 * chunk the server filled from the GPU store, the near-id hash of every section as the store held it ({@link #gpu}), and
 * the same hash of the chunk the client received back from the server ({@link #client}, the client feed's read of its
 * vanilla chunk). The ids are the renderer's near ids ({@code AtlasClientSections.nearIdOf}: the full state, look-alike
 * states sharing one id), so a section is equal when the server's blocks draw as the GPU's did.
 *
 * <p>A chunk agrees when every section the client read matches; a chunk the server made in Java (no GPU record) is
 * counted apart. Any thread.</p>
 */
public final class AtlasServerAgreement {
    private AtlasServerAgreement() {}

    private static final Long2ObjectOpenHashMap<long[]> GPU = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap COMPARED = new Long2IntOpenHashMap();  // chunk -> same | diff << 16 (first read)
    private static long sameSections, diffSections, chunksNoGpu, chunksSame, chunksDiff;
    private static final java.util.ArrayList<String> SAMPLES = new java.util.ArrayList<>();
    private static final int KEEP = 6000;
    /** FNV-1a over 4096 ids of zero: an all-air section. */
    public static final long AIR = hash(new int[4096]);

    public static long hash(int[] ids) {
        long h = 0xcbf29ce484222325L;
        for (int id : ids) h = (h ^ id) * 0x100000001b3L;
        return h;
    }

    /** The store's chunk as the server was given it: per section (index 0 = y -64) the near-id hash (null section: air). */
    public static synchronized void gpu(int cx, int cz, long[] sectionHashes, int[][] nearIds) {
        if (GPU.size() > KEEP) {  // oldest first is not tracked: an unbounded run drops the lot, the tally goes on
            GPU.clear();
        }
        long k = net.minecraft.world.level.ChunkPos.pack(cx, cz);
        GPU.put(k, sectionHashes);
        IDS.put(k, nearIds);  // (the last few chunks' ids, to say what differs)
        while (IDS.size() > 48) IDS.remove(IDS.firstLongKey());
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<int[][]> IDS = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();

    /**
     * The client's read of the chunk it received: sections[s] is section index s from minSec (null: not read, deep solid
     * rock) as near ids. Compared once per chunk (a later read of a changed section is the world moving, not the fill).
     */
    public static synchronized void client(int cx, int cz, int minSec, int[][] sections) {
        long key = net.minecraft.world.level.ChunkPos.pack(cx, cz);
        if (COMPARED.containsKey(key)) return;
        long[] g = GPU.get(key);
        if (g == null) {
            chunksNoGpu++;
            COMPARED.put(key, -1);
            return;
        }
        int same = 0, diff = 0;
        StringBuilder bad = null;
        for (int s = 0; s < sections.length; s++) {
            int[] ids = sections[s];
            if (ids == null) continue;
            int gi = minSec + s + 4;  // the store's section index (0 = y -64, section -4)
            if (gi < 0) continue;
            long want = gi < g.length ? g[gi] : AIR;
            if (hash(ids) == want) {
                same++;
            } else {
                diff++;
                if (bad == null) bad = new StringBuilder();
                bad.append(' ').append(minSec + s);
                int[][] want_ = IDS.get(key);
                int[] ref = want_ == null || gi >= want_.length ? null : want_[gi];
                int shown = 0;
                for (int b = 0; b < 4096 && shown < 2; b++) {
                    int a = ref == null ? 0 : ref[b];
                    if (a == ids[b]) continue;
                    shown++;
                    bad.append(String.format("[%d,%d,%d gpu=%s server=%s]", (cx << 4) + (b & 15), (minSec + s) * 16 + (b >> 8), (cz << 4) + (b >> 4 & 15),
                            a == 0 ? "air" : net.minecraft.world.level.block.Block.stateById(a), ids[b] == 0 ? "air" : net.minecraft.world.level.block.Block.stateById(ids[b])));
                }
            }
        }
        sameSections += same;
        diffSections += diff;
        COMPARED.put(key, same | diff << 16);
        if (diff == 0) {
            chunksSame++;
        } else {
            chunksDiff++;
            if (SAMPLES.size() < 40) SAMPLES.add("chunk " + cx + "," + cz + " sections differing:" + bad);
        }
    }

    /** Zeroes the tally (the route's start); the GPU records stay. */
    public static synchronized void reset() {
        COMPARED.clear();
        sameSections = diffSections = chunksNoGpu = chunksSame = chunksDiff = 0;
        SAMPLES.clear();
    }

    public static synchronized JsonObject report() {
        JsonObject o = new JsonObject();
        o.addProperty("chunksCompared", chunksSame + chunksDiff);
        o.addProperty("chunksSame", chunksSame);
        o.addProperty("chunksDiffer", chunksDiff);
        o.addProperty("chunksWithoutGpuRecord", chunksNoGpu);
        o.addProperty("sectionsSame", sameSections);
        o.addProperty("sectionsDiffer", diffSections);
        JsonArray a = new JsonArray();
        for (String s : SAMPLES) a.add(s);
        o.add("samples", a);
        return o;
    }
}

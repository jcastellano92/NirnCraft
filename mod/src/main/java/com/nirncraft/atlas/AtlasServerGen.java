package com.nirncraft.atlas;

import com.nirncraft.atlas.server.AtlasServerAgreement;
import com.nirncraft.atlas.server.AtlasServerFeed;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client generator's side of the integrated server's chunks (docs/ATLAS-GPU-LOOP.md section 4): the server's
 * {@code AtlasChunkGenerator} asks for a chunk, the generator's worker puts it through the chunk store with the chunks the
 * camera wants (ahead of them, the server's chunks come first), and the decorated ids that come out of the store go back to
 * the server as the chunk to fill. The store keeps one answer per chunk: the server gets exactly the blocks the grid was
 * given. Requests are lock-free for the server's threads; everything else runs on the generator's worker thread.
 */
final class AtlasServerGen implements AtlasServerFeed.Provider {
    private static final class Req {
        final int cx, cz;
        final CompletableFuture<AtlasServerFeed.Chunk> f = new CompletableFuture<>();
        boolean submitted;

        Req(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final java.util.Set<net.minecraft.world.level.levelgen.structure.Structure> coveredSets =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final BlockState[] palette;
    private final int[] nearIds;       // per generator id: the renderer's near id (the agreement tally)
    private final AtlasClientGen owner;
    private final ConcurrentHashMap<Long, Req> reqs = new ConcurrentHashMap<>();
    private volatile boolean up, closed;
    private volatile long served, flagged, waitedForTapes, notFinal, overflow;
    /** Requests the store holds at once; the rest are made in Java (-Dnirncraft.atlas.serverGen.outstanding). */
    static final int MAX_OUTSTANDING = Integer.getInteger("nirncraft.atlas.serverGen.outstanding", 24);

    AtlasServerGen(AtlasClientGen owner, List<BlockState> blocks, int[] nearIds, net.minecraft.server.level.ServerLevel level) {
        this.owner = owner;
        // the structures the overlays stamp: the sets whose every structure is a seeded settlement or a dungeon
        // (AtlasOverlayStarts' own rule); the street plan's named settlements are covered besides
        try {
            for (var set : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
                var placement = set.value().placement();
                if (!(placement instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                        && !(placement instanceof com.nirncraft.tamriel.PlacesPlacement)) continue;
                boolean all = !set.value().structures().isEmpty();
                for (var e : set.value().structures()) all &= AtlasOverlayStarts.ALL.test(e.structure().value());
                if (all) for (var e : set.value().structures()) coveredSets.add(e.structure().value());
            }
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas server feed: the covered structures are unknown (Java places every structure's chunks)", e);
        }
        this.palette = blocks.toArray(new BlockState[0]);
        this.nearIds = nearIds;
    }

    /** The worker is past its preamble (the map on the device, the cache armed): the store can be asked. */
    void up() {
        up = true;
    }

    void close() {
        closed = true;
        for (Req r : reqs.values()) r.f.complete(null);  // (the server makes them in Java)
        reqs.clear();
    }

    @Override
    public boolean ready() {
        return up && !closed && AtlasClientGen.hasCamera();
    }

    @Override
    public CompletableFuture<AtlasServerFeed.Chunk> request(int cx, int cz) {
        if (!ready()) return null;
        // a chunk whose settlement or dungeon is not recorded yet (or the starts around the camera not found yet) is not the
        // GPU world's to give: Java makes it now, rather than the server waiting on the recorder (seconds a structure)
        if (!AtlasOverlayLive.finalAt(cx, cz)) {
            notFinal++;
            return null;
        }
        // the store makes a decorated chunk in about a tenth of a second (a trees' chunk longer): a burst the server asks for
        // (a spawn, a teleport, a flight) is more than it can answer in a tolerable time, and the chunks past this many
        // outstanding go to Java at once; the walk's trickle is the GPU's
        if (reqs.size() >= MAX_OUTSTANDING) {
            overflow++;
            return null;
        }
        Req r = reqs.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new Req(cx, cz));
        owner.wake();
        return r.f.copy();  // (the caller's timeout must not complete the shared future)
    }

    @Override
    public boolean covers(net.minecraft.world.level.levelgen.structure.Structure s) {
        return (s instanceof com.nirncraft.tamriel.settlement.SettlementStructure ss && "place".equals(ss.source)) || coveredSets.contains(s);
    }

    @Override
    public void cancel(int cx, int cz) {
        reqs.remove(ChunkPos.pack(cx, cz));
    }

    /** Whether any request waits (the worker passes the id buffer to the store's call only then). */
    boolean pending() {
        return !reqs.isEmpty();
    }

    /**
     * Worker thread: up to max requests not yet in the store, as chunk pairs; those whose settlements' tapes are not ready
     * wait (the chunk would come out without them, and the server keeps what it is given for good).
     */
    int[] take(int max) {
        int[] out = new int[max * 2];
        int n = 0;
        for (Req r : reqs.values()) {
            if (r.submitted) continue;
            if (!AtlasOverlayLive.finalAt(r.cx, r.cz)) {  // (a settlement or start found since the request: Java makes it)
                waitedForTapes++;
                reqs.remove(ChunkPos.pack(r.cx, r.cz));
                r.f.complete(null);
                continue;
            }
            r.submitted = true;
            out[n * 2] = r.cx;
            out[n * 2 + 1] = r.cz;
            if (++n == max) break;
        }
        return java.util.Arrays.copyOf(out, n * 2);
    }

    /**
     * Worker thread: the store gave m chunks (their pairs in xz, ids and flags as atlas_gen_run_store gave them): those
     * the server asked for go to it. A chunk the kernels could not prove exact goes as null (Java makes it).
     */
    void delivered(int[] xz, int m, int[] ids, int[] flags) {
        for (int i = 0; i < m; i++) {
            long k = ChunkPos.pack(xz[i * 2], xz[i * 2 + 1]);
            Req r = reqs.remove(k);
            if (r == null) continue;
            if (flags[i] != 0) {
                flagged++;
                r.f.complete(null);
                continue;
            }
            short[][] secs = new short[40][];
            long[] hashes = new long[40];
            int base = i * AtlasGenNative.CHUNK_WORDS;
            int[] near = new int[4096];
            int[][] nearSecs = new int[40][];
            for (int s = 0; s < 40; s++) {
                short[] sec = null;
                for (int w = 0; w < 2048; w++) {
                    int word = ids[base + s * 2048 + w];
                    if (word == 0 && sec == null) continue;
                    if (sec == null) sec = new short[4096];
                    sec[w * 2] = (short) (word & 0xFFFF);
                    sec[w * 2 + 1] = (short) (word >>> 16);
                }
                secs[s] = sec;
                if (sec == null) {
                    hashes[s] = AtlasServerAgreement.AIR;
                } else {
                    for (int b = 0; b < 4096; b++) near[b] = nearIds[sec[b] & 0xFFFF];
                    hashes[s] = AtlasServerAgreement.hash(near);
                    nearSecs[s] = near.clone();
                }
            }
            AtlasServerAgreement.gpu(xz[i * 2], xz[i * 2 + 1], hashes, nearSecs);
            served++;
            r.f.complete(new AtlasServerFeed.Chunk(xz[i * 2], xz[i * 2 + 1], secs, palette, AtlasSideOps.at(xz[i * 2], xz[i * 2 + 1])));
        }
    }

    @Override
    public String describe() {
        return state() + " " + owner.storeLine();
    }

    /** "server feed: asked / served / flagged / waiting for tapes" (atlas-info). */
    String state() {
        return "serverFeed{up=" + up + " waiting=" + reqs.size() + " served=" + served + " flagged=" + flagged
                + " tapeWaits=" + waitedForTapes + " javaNotFinal=" + notFinal + " javaOverflow=" + overflow + "}";
    }
}

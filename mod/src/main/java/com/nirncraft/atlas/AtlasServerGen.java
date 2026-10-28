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
 * camera wants, and the decorated ids that come out of the store go back to the server as the chunk to fill. The store keeps
 * one answer per chunk: the server gets exactly the blocks the grid was given. Requests are lock-free for the server's
 * threads; everything else runs on the generator's worker thread.
 */
final class AtlasServerGen implements AtlasServerFeed.Provider {
    static final int SERVER_KEEP_R = 11;

    private static final class Req {
        final int cx, cz;
        final CompletableFuture<AtlasServerFeed.Chunk> f = new CompletableFuture<>();
        boolean submitted;

        Req(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private record ReadyChunk(int cx, int cz, AtlasServerFeed.Chunk chunk, long[] hashes) {}

    private final java.util.Set<net.minecraft.world.level.levelgen.structure.Structure> coveredSets =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final BlockState[] palette;
    private final int[] nearIds;       // per generator id: the renderer's near id (the agreement tally)
    private final AtlasClientGen owner;
    private final ConcurrentHashMap<Long, Req> reqs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ReadyChunk> readyChunks = new ConcurrentHashMap<>();
    private volatile boolean up, closed;
    private volatile long served, flagged, waitedForTapes, notFinal, overflow, badReadbacks;
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
        readyChunks.clear();
        for (Req r : reqs.values()) r.f.complete(null);  // (the server makes them in Java)
        reqs.clear();
    }

    void onCacheHit(long k) {
        readyChunks.remove(k);
        Req r = reqs.remove(k);
        if (r != null) r.f.complete(null);
    }

    void invalidate(long k) {
        readyChunks.remove(k);
        Req r = reqs.remove(k);
        if (r != null) r.f.complete(null);
    }

    void prune(int camCx, int camCz) {
        int keep = SERVER_KEEP_R + 2;
        readyChunks.values().removeIf(rc -> Math.max(Math.abs(rc.cx - camCx), Math.abs(rc.cz - camCz)) > keep);
        for (var it = reqs.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Req r = e.getValue();
            if (Math.max(Math.abs(r.cx - camCx), Math.abs(r.cz - camCz)) > keep) {
                it.remove();
                r.f.complete(null);
            }
        }
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
        long k = ChunkPos.pack(cx, cz);
        ReadyChunk rc = readyChunks.remove(k);
        if (rc != null) {
            AtlasServerAgreement.gpu(cx, cz, rc.hashes, null);
            served++;
            return CompletableFuture.completedFuture(rc.chunk);
        }
        // Only serve chunks already completed by the GPU store so Worker-Main threads never block waiting on store stages
        return null;
    }

    @Override
    public boolean covers(net.minecraft.world.level.levelgen.structure.Structure s) {
        return (s instanceof com.nirncraft.tamriel.settlement.SettlementStructure ss && "place".equals(ss.source)) || coveredSets.contains(s);
    }

    @Override
    public void cancel(int cx, int cz) {
        reqs.remove(ChunkPos.pack(cx, cz));
    }

    private long prioSent = Long.MIN_VALUE;

    /**
     * Worker thread: the store's stages run first for the chunks the server waits for (and what their stages read), and their
     * neighbourhoods are made first; told to the store whenever the set changed (a server request outranks the camera's window).
     */
    void prioritize(AtlasGenNative.Gen gen) {
        int n = 0;
        long sig = 1469598103934665603L;
        int[] xz = new int[reqs.size() * 2];
        for (Req r : reqs.values()) {
            if (!r.submitted || n * 2 + 1 >= xz.length) continue;
            xz[n * 2] = r.cx;
            xz[n * 2 + 1] = r.cz;
            sig += ChunkPos.pack(r.cx, r.cz) * 0x9E3779B97F4A7C15L;  // (order free)
            n++;
        }
        if (sig == prioSent) return;
        prioSent = sig;
        gen.storePrioritize(java.util.Arrays.copyOf(xz, n * 2));
    }

    /** Whether any request waits or the current store batch is near the server's chunk window. */
    boolean pending() {
        return !reqs.isEmpty();
    }

    boolean wantsIds(int[] batch, int camCx, int camCz) {
        return !reqs.isEmpty() || readyChunks.size() < 256;
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
            long k = ChunkPos.pack(r.cx, r.cz);
            if (owner.isCacheServed(k) || owner.isStorePacked(k) || !AtlasOverlayLive.finalAt(r.cx, r.cz)) {
                waitedForTapes++;
                reqs.remove(k);
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
     * the server asked for (or within SERVER_KEEP_R of the camera) are decoded. A chunk the kernels could not prove exact
     * goes as null (Java makes it).
     */
    void delivered(int[] xz, int m, int[] ids, int[] flags, int[] info) {
        delivered(xz, m, ids, flags, info, owner.cameraCx(), owner.cameraCz());
    }

    void delivered(int[] xz, int m, int[] ids, int[] flags, int[] info, int camCx, int camCz) {
        for (int i = 0; i < m; i++) {
            int cx = xz[i * 2], cz = xz[i * 2 + 1];
            long k = ChunkPos.pack(cx, cz);
            Req r = reqs.remove(k);
            boolean preCache = r == null && readyChunks.size() < 256
                    && Math.max(Math.abs(cx - camCx), Math.abs(cz - camCz)) <= SERVER_KEEP_R
                    && AtlasOverlayLive.finalAt(cx, cz);
            if (r == null && !preCache) continue;
            if (flags[i] != 0) {
                flagged++;
                if (r != null) r.f.complete(null);
                continue;
            }
            short[][] secs = new short[40][];
            long[] hashes = new long[40];
            int base = i * AtlasGenNative.CHUNK_WORDS;
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
                    long h = 0xcbf29ce484222325L;
                    for (int b = 0; b < 4096; b++) h = (h ^ nearIds[sec[b] & 0xFFFF]) * 0x100000001b3L;
                    hashes[s] = h;
                }
            }
            // the ids against the store's own report of the chunk (kinds: air, blocks, deep rock): a section the report says
            // has blocks and the ids say is air is a bad readback or a slot that is not this chunk's: Java makes the chunk
            int nonAir = 0, disagree = 0;
            for (int s = 0; s < 40; s++) {
                int kind = info[i * AtlasGenNative.INFO_WORDS + 4 + s * 4 + 2];
                if (secs[s] != null) nonAir++;
                if ((kind == AtlasGenNative.SEC_AIR) != (secs[s] == null)) disagree++;
            }
            if (nonAir == 0 || disagree > 0) {
                badReadbacks++;
                if (badReadbacks <= 5)
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas server feed: chunk {},{} ids disagree with the store's report ({} sections non-air, {} disagree): Java makes it",
                            cx, cz, nonAir, disagree);
                if (r != null) r.f.complete(null);
                continue;
            }
            AtlasServerFeed.Chunk chunk = new AtlasServerFeed.Chunk(cx, cz, secs, palette, AtlasSideOps.at(cx, cz));
            if (r != null) {
                AtlasServerAgreement.gpu(cx, cz, hashes, null);
                served++;
                r.f.complete(chunk);
            } else {
                readyChunks.put(k, new ReadyChunk(cx, cz, chunk, hashes));
            }
        }
    }

    @Override
    public String describe() {
        return state() + " " + owner.storeLine();
    }

    /** "server feed: asked / served / flagged / waiting for tapes" (atlas-info). */
    String state() {
        return "serverFeed{up=" + up + " waiting=" + reqs.size() + " ready=" + readyChunks.size() + " served=" + served + " flagged=" + flagged
                + " tapeWaits=" + waitedForTapes + " javaNotFinal=" + notFinal + " javaOverflow=" + overflow + " badReadbacks=" + badReadbacks + "}";
    }
}

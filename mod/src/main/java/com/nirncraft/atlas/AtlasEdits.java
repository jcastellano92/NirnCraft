package com.nirncraft.atlas;

import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far field's change feed, server side (docs/ATLAS-DELTAS.md): the world as it really is where the server has it,
 * on top of the far field's seed prediction. Two sources fill one journal of columns per 256-block tile:
 * <ul>
 *   <li><b>Chunks the server has</b> (generated or loaded): every column as built (terrain, buildings, props, fire
 *   blocks, the real trees), once per chunk content (a hash per chunk), so where anyone has been the far field shows
 *   exactly what is there. The hand-off ring is always such land.</li>
 *   <li><b>Changes</b>: every setBlock that notifies neighbours (players, explosions, fire, fluids, pistons),
 *   coalesced per tick.</li>
 * </ul>
 * A column is its ground (the top of what stands on the terrain, logs aside: terrain, walls, roofs, props), the block
 * under that, the water over it, and one span above the ground (a tree's crown on its trunk, leaves): top, base
 * (Integer.MIN_VALUE = from the ground: a trunk), its wall block (trunk) and roof block (crown). Twice a second each
 * player gets the columns of the tiles in their far window that they have not had yet.
 */
public final class AtlasEdits {
    private AtlasEdits() {}

    static final int TILE = 256;
    /** Window half-size in LOD-0 tiles the changes are pushed for: the LOD 2 window (5 km square). */
    static final int RADIUS_TILES = 10;
    private static final int PUSH_EVERY = 10;      // ticks
    private static final int CHUNKS_PER_TICK = 8;  // chunk scans per tick (each a few hundred block reads)
    static final int NONE = Integer.MIN_VALUE;
    /** Ints per column on the wire and on disk: x, z, top, topId, underId, water, spanTop, spanBase, wallId, roofId. */
    static final int COL = 10;

    record Column(int top, int topId, int underId, int water, int spanTop, int spanBase, int wallId, int roofId,
                  long version) {
        boolean same(Column o) {
            return o != null && top == o.top && topId == o.topId && underId == o.underId && water == o.water
                    && spanTop == o.spanTop && spanBase == o.spanBase && wallId == o.wallId && roofId == o.roofId;
        }
    }

    /** A changed light: emission 1..15 (0 removed), at (x, y, z). */
    record Light(int x, int y, int z, int level, long version) {}

    static final class TileJournal {
        long version;
        final Map<Long, Column> columns = new HashMap<>();
        final Map<Long, Light> lights = new HashMap<>();
    }

    private static final Map<Long, TileJournal> JOURNAL = new ConcurrentHashMap<>();
    private static final Map<Long, Long> CHUNK_HASH = new ConcurrentHashMap<>();  // chunk -> content hash recorded
    private static final java.util.Set<Long> DIRTY = ConcurrentHashMap.newKeySet();  // packed (x, z) columns
    private static final Map<Long, Integer> LIGHT_DIRTY = new ConcurrentHashMap<>();
    private static final ArrayDeque<Long> CHUNKS = new ArrayDeque<>();  // chunks to scan (ChunkPos.pack)
    private static final java.util.Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Map<Long, Long>> SENT = new ConcurrentHashMap<>();
    private static Path file;
    private static boolean changedSinceSave;

    static long tileKey(int tx, int tz) {
        return (long) tx << 32 | (tz & 0xFFFFFFFFL);
    }

    static long colKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    // ---- sources -----------------------------------------------------------------------------------------------

    /** NeighborNotifyEvent: a block changed (the event fires for the new state at pos). */
    public static void onBlockChanged(BlockEvent.NeighborNotifyEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        BlockPos p = e.getPos();
        DIRTY.add(colKey(p.getX(), p.getZ()));
        int light = e.getState().getLightEmission();
        long pk = p.asLong();
        if (light >= 8 || knownLight(p)) LIGHT_DIRTY.put(pk, light);
    }

    /** ChunkEvent.Load: a chunk the server has (new or from disk): scanned once per content. */
    public static void onChunkLoaded(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(e.getChunk() instanceof LevelChunk c)) return;
        long k = c.getPos().pack();
        if (QUEUED.add(k)) {
            synchronized (CHUNKS) {
                CHUNKS.add(k);
            }
        }
    }

    private static boolean knownLight(BlockPos p) {
        TileJournal j = JOURNAL.get(tileKey(Math.floorDiv(p.getX(), TILE), Math.floorDiv(p.getZ(), TILE)));
        return j != null && j.lights.containsKey(p.asLong());
    }

    /** End of a server tick: chunks scanned, changed columns read back, the players' windows served. */
    static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) return;
        if (file == null) open(server);
        scanChunks(level);
        if (!DIRTY.isEmpty() || !LIGHT_DIRTY.isEmpty()) flush(level);
        if (server.getTickCount() % PUSH_EVERY == 0) {
            push(server, level);
            AtlasSections.push(server, level);
        }
        if (server.getTickCount() % 1200 == 0) {
            if (changedSinceSave) save();
            AtlasSections.save();
        }
    }

    private static void scanChunks(ServerLevel level) {
        for (int n = 0; n < CHUNKS_PER_TICK; n++) {
            Long k;
            synchronized (CHUNKS) {
                k = CHUNKS.poll();
            }
            if (k == null) return;
            QUEUED.remove(k);
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
            if (c == null) continue;  // gone again: the next load scans it
            Column[] cols = new Column[256];
            long h = 1469598103934665603L;
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    Column col = read(level, c, (cx << 4) + lx, (cz << 4) + lz);
                    cols[lz * 16 + lx] = col;
                    h = (h ^ col.top()) * 1099511628211L;
                    h = (h ^ col.topId()) * 1099511628211L;
                    h = (h ^ col.spanTop()) * 1099511628211L;
                    h = (h ^ col.roofId()) * 1099511628211L;
                    h = (h ^ col.water()) * 1099511628211L;
                }
            // its light sources (fires, torches, lamps, lava, the mod's fire blocks): the far field's lights
            List<long[]> lights = new ArrayList<>();
            c.findBlockLightSources((pos, st) -> {
                int e = st.getLightEmission();
                if (e >= 10 && lights.size() < 64) lights.add(new long[] {pos.asLong(), e});
            });
            for (long[] l : lights) h = (h ^ l[0]) * 1099511628211L;
            Long had = CHUNK_HASH.put(k, h);
            if (had != null && had == h) continue;  // as journalled
            // the chunk's real blocks around its surface: the far field's finest levels (AtlasSections)
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (Column col : cols) {
                low = Math.min(low, col.top());
                high = Math.max(high, Math.max(col.top(), col.spanTop() == NONE ? col.top() : col.spanTop()));
            }
            AtlasSections.record(level, c, low, high);
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) record((cx << 4) + lx, (cz << 4) + lz, cols[lz * 16 + lx]);
            for (long[] l : lights) {
                BlockPos p = BlockPos.of(l[0]);
                TileJournal j = JOURNAL.computeIfAbsent(tileKey(Math.floorDiv(p.getX(), TILE), Math.floorDiv(p.getZ(), TILE)),
                        kk -> new TileJournal());
                synchronized (j) {
                    Light old = j.lights.get(l[0]);
                    if (old != null && old.level() == (int) l[1]) continue;
                    j.version++;
                    j.lights.put(l[0], new Light(p.getX(), p.getY(), p.getZ(), (int) l[1], j.version));
                }
            }
            changedSinceSave = true;
        }
    }

    private static void flush(ServerLevel level) {
        List<Long> cols = new ArrayList<>(DIRTY);
        DIRTY.removeAll(cols);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (long c : cols) {
            int x = (int) (c >> 32), z = (int) c;
            LevelChunk ch = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (ch == null) continue;
            record(x, z, read(level, ch, x, z));
            CHUNK_HASH.remove(ChunkPos.pack(x >> 4, z >> 4));  // the chunk's next scan journals it whole again
        }
        for (Map.Entry<Long, Integer> e : new ArrayList<>(LIGHT_DIRTY.entrySet())) {
            LIGHT_DIRTY.remove(e.getKey());
            BlockPos p = BlockPos.of(e.getKey());
            int now = level.isLoaded(p) ? level.getBlockState(p).getLightEmission() : e.getValue();
            TileJournal j = JOURNAL.computeIfAbsent(tileKey(Math.floorDiv(p.getX(), TILE), Math.floorDiv(p.getZ(), TILE)),
                    k -> new TileJournal());
            synchronized (j) {
                j.version++;
                j.lights.put(e.getKey(), new Light(p.getX(), p.getY(), p.getZ(), now, j.version));
            }
            changedSinceSave = true;
        }
    }

    /**
     * Column (x, z) of a loaded chunk as the far field draws it: the ground (the top of what is not a tree: terrain,
     * walls, roofs, props), the block under it, the water over it, and the tree over it as one span: from the ground
     * (a trunk) or floating (a crown over another column's trunk) up to the crown's top.
     */
    static Column read(ServerLevel level, LevelChunk c, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int lx = x & 15, lz = z & 15, min = level.getMinY();
        // (heightmaps hold the first free block over the column: the top block is one under)
        int top = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) - 1;
        int any = c.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz) - 1;
        BlockState t = c.getBlockState(m.set(x, top, z));
        int water = NONE, trunkTop = NONE;
        while (top > min && (!t.getFluidState().isEmpty() || t.is(BlockTags.LOGS) || t.isAir())) {
            if (!t.getFluidState().isEmpty() && water == NONE) water = top;
            if (t.is(BlockTags.LOGS) && trunkTop == NONE) trunkTop = top;
            top--;
            t = c.getBlockState(m.set(x, top, z));
        }
        BlockState u = c.getBlockState(m.set(x, top - 1, z));
        // a snow layer lies on the ground (not in the heightmaps): the far ground wears it, the block under it shows
        BlockState above = c.getBlockState(m.set(x, top + 1, z));
        if (above.is(net.minecraft.world.level.block.Blocks.SNOW)) {
            u = t;
            t = above;
        }
        int spanTop = NONE, spanBase = NONE, wall = 0, roof = 0;
        int crownTop = Math.max(any, trunkTop);
        if (crownTop > top) {
            BlockState r = c.getBlockState(m.set(x, crownTop, z));
            if (r.isAir() && crownTop > top + 1) r = c.getBlockState(m.set(x, crownTop - 1, z));
            spanTop = crownTop + 1;
            roof = AtlasFarState.farId(r);
            if (trunkTop != NONE) {
                wall = AtlasFarState.farId(c.getBlockState(m.set(x, trunkTop, z)));  // the trunk, from the ground
            } else {
                // a crown over open air: from its lowest leaf in a run down from the top
                int y = crownTop;
                while (y - 1 > top && !c.getBlockState(m.set(x, y - 1, z)).isAir()) y--;
                spanBase = y;
                wall = roof;
            }
        }
        return new Column(top, AtlasFarState.farId(t), AtlasFarState.farId(u), water, spanTop, spanBase, wall, roof, 0);
    }

    private static void record(int x, int z, Column c) {
        TileJournal j = JOURNAL.computeIfAbsent(tileKey(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)), k -> new TileJournal());
        synchronized (j) {
            if (c.same(j.columns.get(colKey(x, z)))) return;
            j.version++;
            j.columns.put(colKey(x, z), new Column(c.top(), c.topId(), c.underId(), c.water(), c.spanTop(), c.spanBase(),
                    c.wallId(), c.roofId(), j.version));
        }
        changedSinceSave = true;
    }

    // ---- delivery ----------------------------------------------------------------------------------------------

    /** Each player's window: the changes of its tiles newer than what that player has. */
    private static void push(MinecraftServer server, ServerLevel level) {
        if (JOURNAL.isEmpty()) return;
        for (ServerPlayer p : level.players()) {
            Map<Long, Long> sent = SENT.computeIfAbsent(p.getUUID(), u -> new HashMap<>());
            int ptx = Math.floorDiv((int) Math.floor(p.getX()), TILE), ptz = Math.floorDiv((int) Math.floor(p.getZ()), TILE);
            for (Map.Entry<Long, TileJournal> e : JOURNAL.entrySet()) {
                long k = e.getKey();
                int tx = (int) (k >> 32), tz = (int) k;
                if (Math.abs(tx - ptx) > RADIUS_TILES || Math.abs(tz - ptz) > RADIUS_TILES) continue;
                TileJournal j = e.getValue();
                long have = sent.getOrDefault(k, 0L);
                List<AtlasNet.FarEdits> msgs;
                synchronized (j) {
                    if (j.version <= have) continue;
                    msgs = encode(tx, tz, j, have);
                    sent.put(k, j.version);
                }
                for (AtlasNet.FarEdits m : msgs) SkyNetwork.sendToPlayer(p, m);
            }
        }
        SENT.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
    }

    private static int[] pack(long ck, Column col, int[] out, int at) {
        out[at] = (int) (ck >> 32);
        out[at + 1] = (int) ck;
        out[at + 2] = col.top();
        out[at + 3] = col.topId();
        out[at + 4] = col.underId();
        out[at + 5] = col.water();
        out[at + 6] = col.spanTop();
        out[at + 7] = col.spanBase();
        out[at + 8] = col.wallId();
        out[at + 9] = col.roofId();
        return out;
    }

    private static Column unpack(int[] a, int at, long version) {
        return new Column(a[at + 2], a[at + 3], a[at + 4], a[at + 5], a[at + 6], a[at + 7], a[at + 8], a[at + 9], version);
    }

    /** The tile's changes since `since`, in packets of at most MAX_EDITS columns and lights each. */
    private static List<AtlasNet.FarEdits> encode(int tx, int tz, TileJournal j, long since) {
        List<Map.Entry<Long, Column>> cs = new ArrayList<>();
        for (Map.Entry<Long, Column> e : j.columns.entrySet()) if (e.getValue().version() > since) cs.add(e);
        List<Light> ls = new ArrayList<>();
        for (Light l : j.lights.values()) if (l.version() > since) ls.add(l);
        List<AtlasNet.FarEdits> out = new ArrayList<>();
        int max = AtlasNet.MAX_EDITS;
        for (int at = 0, lt = 0; at < cs.size() || lt < ls.size(); at += max, lt += max) {
            int n = Math.max(0, Math.min(max, cs.size() - at)), nl = Math.max(0, Math.min(max, ls.size() - lt));
            int[] c = new int[n * COL];
            for (int i = 0; i < n; i++) pack(cs.get(at + i).getKey(), cs.get(at + i).getValue(), c, i * COL);
            int[] l = new int[nl * 4];
            for (int i = 0; i < nl; i++) {
                Light li = ls.get(lt + i);
                l[i * 4] = li.x();
                l[i * 4 + 1] = li.y();
                l[i * 4 + 2] = li.z();
                l[i * 4 + 3] = li.level();
            }
            out.add(new AtlasNet.FarEdits(tx, tz, c, l));
        }
        return out;
    }

    // ---- persistence: the journal next to the world's far tile cache -----------------------------------------------

    private static void open(MinecraftServer server) {
        file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/nirncraft_atlas/edits-v2.dat");
        JOURNAL.clear();
        SENT.clear();
        CHUNK_HASH.clear();
        AtlasSections.open(server);
        if (!Files.isRegularFile(file)) return;
        try {
            CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            ListTag tiles = root.getListOrEmpty("tiles");
            for (int i = 0; i < tiles.size(); i++) {
                CompoundTag t = tiles.getCompoundOrEmpty(i);
                TileJournal j = new TileJournal();
                j.version = t.getLongOr("v", 0L);
                int[] cs = t.getIntArray("c").orElse(new int[0]);
                for (int k = 0; k + COL - 1 < cs.length; k += COL) j.columns.put(colKey(cs[k], cs[k + 1]), unpack(cs, k, j.version));
                int[] ls = t.getIntArray("l").orElse(new int[0]);
                for (int k = 0; k + 3 < ls.length; k += 4)
                    j.lights.put(BlockPos.asLong(ls[k], ls[k + 1], ls[k + 2]), new Light(ls[k], ls[k + 1], ls[k + 2], ls[k + 3], j.version));
                JOURNAL.put(tileKey(t.getIntOr("x", 0), t.getIntOr("z", 0)), j);
            }
            long[] hashes = root.getLongArray("chunks").orElse(new long[0]);
            for (int k = 0; k + 1 < hashes.length; k += 2) CHUNK_HASH.put(hashes[k], hashes[k + 1]);
            com.nirncraft.NirnCraft.LOGGER.info("atlas: far change journal: {} tiles, {} chunks", JOURNAL.size(), CHUNK_HASH.size());
        } catch (IOException | RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas: far change journal unreadable ({}): starting empty", e.toString());
        }
    }

    /** Writes the journal (server stopping, and every minute while it changes). */
    static void save() {
        if (file == null) return;
        ListTag tiles = new ListTag();
        for (Map.Entry<Long, TileJournal> e : JOURNAL.entrySet()) {
            TileJournal j = e.getValue();
            CompoundTag t = new CompoundTag();
            synchronized (j) {
                t.putInt("x", (int) (e.getKey() >> 32));
                t.putInt("z", (int) (long) e.getKey());
                t.putLong("v", j.version);
                int[] cs = new int[j.columns.size() * COL];
                int i = 0;
                for (Map.Entry<Long, Column> c : j.columns.entrySet()) pack(c.getKey(), c.getValue(), cs, (i++) * COL);
                int[] ls = new int[j.lights.size() * 4];
                i = 0;
                for (Light l : j.lights.values()) {
                    ls[i++] = l.x();
                    ls[i++] = l.y();
                    ls[i++] = l.z();
                    ls[i++] = l.level();
                }
                t.putIntArray("c", cs);
                t.putIntArray("l", ls);
            }
            tiles.add(t);
        }
        CompoundTag root = new CompoundTag();
        root.put("tiles", tiles);
        long[] hashes = new long[CHUNK_HASH.size() * 2];
        int i = 0;
        for (Map.Entry<Long, Long> e : CHUNK_HASH.entrySet()) {
            hashes[i++] = e.getKey();
            hashes[i++] = e.getValue();
        }
        root.putLongArray("chunks", hashes);
        try {
            Files.createDirectories(file.getParent());
            net.minecraft.nbt.NbtIo.writeCompressed(root, file);
            changedSinceSave = false;
        } catch (IOException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas: far change journal not saved: {}", e.toString());
        }
    }

    /** Server stopped: save and forget (a new world gets its own). */
    static void stop() {
        save();
        AtlasSections.stop();
        JOURNAL.clear();
        SENT.clear();
        DIRTY.clear();
        LIGHT_DIRTY.clear();
        CHUNK_HASH.clear();
        synchronized (CHUNKS) {
            CHUNKS.clear();
        }
        QUEUED.clear();
        file = null;
    }
}

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

    /**
     * Diagnostics (the autotest verb "atlas-edits-why on"): a chunk journalled again logs what changed in it (its first
     * changed column, old -> new, by block name, and its light count), a few lines a minute. What keeps a place's far
     * field streaming (fire spreading, leaves decaying, grass, water) shows up here.
     */
    public static volatile boolean why;
    private static final Map<Long, Column[]> LAST = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Column[]> e) {
            return size() > 2048;
        }
    });
    private static final Map<Long, Integer> LAST_LIGHTS = new ConcurrentHashMap<>();
    private static long whyMinute;
    private static int whyLines;
    private static long whySince;
    private static int whyFirst, whyAgain, whyChanged, whyRehash;  // since the last summary
    private static final java.util.Set<Long> DIRTY = ConcurrentHashMap.newKeySet();  // packed (x, z) columns
    private static final Map<Long, Integer> LIGHT_DIRTY = new ConcurrentHashMap<>();
    private static final ArrayDeque<Long> CHUNKS = new ArrayDeque<>();  // chunks to scan (ChunkPos.pack)
    private static final java.util.Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
    // chunks whose kept blocks changed (underground too, where no column does): scanned again, at most every RESECTION_EVERY
    private static final java.util.Set<Long> RESECTION = ConcurrentHashMap.newKeySet();
    private static final int RESECTION_EVERY = 40;  // ticks
    private static final Map<UUID, Map<Long, Long>> SENT = new ConcurrentHashMap<>();
    private static Path file;
    private static volatile boolean changedSinceSave;

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
        // a block the far field keeps placed or one taken out (a mine dug, a wall built): the chunk's sections again
        if (e.getState().isAir() || AtlasSections.kept(e.getState())) RESECTION.add(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4));
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
        if (server.getTickCount() % RESECTION_EVERY == 0 && !RESECTION.isEmpty()) {
            for (Long k : new ArrayList<>(RESECTION)) {
                RESECTION.remove(k);
                CHUNK_HASH.remove(k);  // (scanned whole again: its columns and its sections, AtlasSections.record)
                if (QUEUED.add(k)) {
                    synchronized (CHUNKS) {
                        CHUNKS.add(k);
                    }
                }
            }
        }
        scanChunks(level);
        if (!DIRTY.isEmpty() || !LIGHT_DIRTY.isEmpty()) flush(level);
        if (server.getTickCount() % PUSH_EVERY == 0) {
            push(server, level);
            AtlasSections.push(server, level);
        }
        if (server.getTickCount() % 1200 == 0) saveInBackground();
    }

    // The minute's save (the journal, the sections' tiles) on a thread of its own, one at a time: it ran on the server thread
    // (the whole journal packed and gzipped, every dirty tile deflated), 2 to 3 s a minute, "Can't keep up" about every 62 s.
    private static final java.util.concurrent.atomic.AtomicBoolean SAVING = new java.util.concurrent.atomic.AtomicBoolean();
    private static final Object SAVE_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService SAVER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nirncraft-atlas-journal-save");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static void saveInBackground() {
        if (!SAVING.compareAndSet(false, true)) return;  // (the last one is still writing)
        SAVER.execute(() -> {
            try {
                if (changedSinceSave) save();
                AtlasSections.save();
            } catch (Throwable t) {
                com.nirncraft.NirnCraft.LOGGER.warn("atlas: far change journal not saved: {}", t.toString());
            } finally {
                SAVING.set(false);
            }
        });
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
            if (why) explain(k, cx, cz, had, cols, lights.size());
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
                // only a real change: a fire's age or a crop's growth ticking at a light's position notifies its
                // neighbours every few ticks, and bumping the version each time re-sent the tile to every client and
                // rebuilt it forever (the far field never went idle in towns with chimney fires)
                Light old = j.lights.get(e.getKey());
                if (old != null ? old.level() == now : now <= 0) continue;
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
        // (a chunk's getHeight is its top block already, the heightmap's first free block minus one; Level.getHeight is
        // the free block. One under it was the dirt under every grass top: real ground read a block low and brown)
        int top = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
        int any = c.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
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
            Column was = j.columns.get(colKey(x, z));
            if (c.same(was)) return;
            if (why && was != null && ++whyLines <= 30)  // what keeps changing in a loaded world (diagnostics)
                com.nirncraft.NirnCraft.LOGGER.info("atlas edits why: column {} {} changed: top {}->{} {}->{}, under {}->{}, "
                        + "roof {}->{} at {}->{}, water {}->{}", x, z, was.top(), c.top(), blockName(was.topId()),
                        blockName(c.topId()), blockName(was.underId()), blockName(c.underId()), blockName(was.roofId()),
                        blockName(c.roofId()), was.spanTop(), c.spanTop(), was.water(), c.water());
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
                    if (why) {
                        pushTiles++;
                        pushMsgs += msgs.size();
                        if (pushSample.isEmpty()) pushSample = tx + "," + tz + " v" + have + "->" + j.version;
                    }
                }
                for (AtlasNet.FarEdits m : msgs) SkyNetwork.sendToPlayer(p, m);
            }
        }
        SENT.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
        long now = System.currentTimeMillis();
        if (why && now - pushSince >= 10_000L) {  // what the stream to the clients is made of (diagnostics)
            com.nirncraft.NirnCraft.LOGGER.info("atlas edits why: pushed {} tile updates in {} packets in {} s (e.g. tile {}); "
                    + "journal {} tiles, scan queue {}", pushTiles, pushMsgs, (now - pushSince) / 1000, pushSample, JOURNAL.size(), CHUNKS.size());
            pushSince = now;
            pushTiles = pushMsgs = 0;
            pushSample = "";
        }
    }

    private static long pushSince = System.currentTimeMillis();
    private static int pushTiles, pushMsgs;
    private static String pushSample = "";

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
        java.nio.file.Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/nirncraft_atlas");
        file = dir.resolve("edits-v3.dat");
        JOURNAL.clear();
        SENT.clear();
        CHUNK_HASH.clear();
        AtlasSections.open(server);
        // a v2 journal's columns lie a block low (read() took the block under each top): only its lights are kept, its
        // chunks are journalled again as they load
        java.nio.file.Path old = dir.resolve("edits-v2.dat");
        boolean columns = Files.isRegularFile(file);
        java.nio.file.Path from = columns ? file : old;
        if (!Files.isRegularFile(from)) return;
        try {
            CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(from, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            ListTag tiles = root.getListOrEmpty("tiles");
            for (int i = 0; i < tiles.size(); i++) {
                CompoundTag t = tiles.getCompoundOrEmpty(i);
                TileJournal j = new TileJournal();
                j.version = t.getLongOr("v", 0L);
                int[] cs = columns ? t.getIntArray("c").orElse(new int[0]) : new int[0];
                for (int k = 0; k + COL - 1 < cs.length; k += COL) j.columns.put(colKey(cs[k], cs[k + 1]), unpack(cs, k, j.version));
                int[] ls = t.getIntArray("l").orElse(new int[0]);
                for (int k = 0; k + 3 < ls.length; k += 4)
                    j.lights.put(BlockPos.asLong(ls[k], ls[k + 1], ls[k + 2]), new Light(ls[k], ls[k + 1], ls[k + 2], ls[k + 3], j.version));
                JOURNAL.put(tileKey(t.getIntOr("x", 0), t.getIntOr("z", 0)), j);
            }
            long[] hashes = columns ? root.getLongArray("chunks").orElse(new long[0]) : new long[0];
            for (int k = 0; k + 1 < hashes.length; k += 2) CHUNK_HASH.put(hashes[k], hashes[k + 1]);
            com.nirncraft.NirnCraft.LOGGER.info("atlas: far change journal: {} tiles, {} chunks", JOURNAL.size(), CHUNK_HASH.size());
        } catch (IOException | RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas: far change journal unreadable ({}): starting empty", e.toString());
        }
    }

    /** Writes the journal (server stopping, and every minute while it changes). */
    static void save() {
        synchronized (SAVE_LOCK) {
            saveLocked();
        }
    }

    private static void saveLocked() {
        Path file = AtlasEdits.file;
        if (file == null) return;
        changedSinceSave = false;  // (before the copy: a change while it is written marks the next save)
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
        } catch (IOException e) {
            changedSinceSave = true;
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
        RESECTION.clear();
        file = null;
    }

    private static String blockName(int id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Block.stateById(id).getBlock()).getPath();
    }

    /** "atlas-edits-why": which column of a re-journalled chunk changed, and how (rate-limited). */
    private static void explain(long k, int cx, int cz, Long had, Column[] cols, int lights) {
        Column[] before = LAST.put(k, cols);
        Integer lightsBefore = LAST_LIGHTS.put(k, lights);
        long now = System.currentTimeMillis();
        if (whySince == 0) whySince = now;
        if (had == null) whyFirst++;
        else if (before == null) whyRehash++;  // journalled before this session (the world's saved journal), not seen yet
        else whyAgain++;
        if (now - whySince >= 10_000L) {  // a summary every 10 s: is the far field's stream finite (first loads) or not
            com.nirncraft.NirnCraft.LOGGER.info("atlas edits why: last {} s: {} chunks scanned first time, {} again from the "
                    + "saved journal, {} again this session ({} of them changed); queue {}", (now - whySince) / 1000,
                    whyFirst, whyRehash, whyAgain, whyChanged, CHUNKS.size());
            whySince = now;
            whyFirst = whyAgain = whyChanged = whyRehash = 0;
        }
        if (had == null || before == null) return;
        long minute = System.currentTimeMillis() / 60_000L;
        if (minute != whyMinute) {
            whyMinute = minute;
            whyLines = 0;
        }
        for (int i = 0; i < 256; i++) {
            Column a = before[i], b = cols[i];
            if (a.equals(b)) continue;
            whyChanged++;
            if (++whyLines > 20) return;
            int x = (cx << 4) + (i & 15), z = (cz << 4) + (i >> 4);
            com.nirncraft.NirnCraft.LOGGER.info("atlas edits why: chunk {} {} column {} {}: top {}->{} {}->{}, under {}->{}, roof {}->{} at {}->{}, water {}->{}",
                    cx, cz, x, z, a.top(), b.top(), blockName(a.topId()), blockName(b.topId()), blockName(a.underId()), blockName(b.underId()),
                    blockName(a.roofId()), blockName(b.roofId()), a.spanTop(), b.spanTop(), a.water(), b.water());
            return;
        }
        if (lightsBefore != null && lightsBefore != lights && ++whyLines <= 20)
            com.nirncraft.NirnCraft.LOGGER.info("atlas edits why: chunk {} {}: lights {} -> {}", cx, cz, lightsBefore, lights);
    }
}

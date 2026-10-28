package com.nirncraft.atlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The near field's real blocks for atlas (docs/ATLAS-LOD.md "Underground"): every chunk this client loads goes up as
 * its sections, all of them from the bottom of the world to its highest top except solid rock deep under the ground
 * (AtlasSections.solidRock: the tiles hold stone there), the same far ids and the same rule as the server's recording.
 * So atlas's own world holds the caves, mines and rooms the player stands in and looks along: the near light's rays
 * meet their walls and roofs (sun 0 under rock, the sky only through the cave's mouth), and the far trace continues a
 * cave past the loaded chunks instead of a wall of tile stone. Atlas keeps what went up after the chunk unloads (its
 * sections out to World::sectionKeep, their coarse levels beyond), so a cave walked through stays drawn behind.
 * Changes reach atlas from the server's recording (AtlasEdits re-records a chunk whose kept blocks change); a section
 * already up with the same blocks is not put up again (AtlasEditsClient.applySections). -Dnirncraft.atlas.clientSections=false
 * turns the feed off (the server's surface sections only, held under the near field, as before).
 *
 * <p>Near sections carry every block with a model (nearKept: doors, rails, redstone, ladders, lecterns, torches, crops,
 * NirnCraft's props; a non-cube one becomes its micro-voxel brick, AtlasMaterials.section), each in its full state (a
 * crop's age, redstone's power: the far field's stripped state is for the far journal only), and per column the biome
 * tints vanilla blends there. A block change reaches atlas section by section from the level's neighbour notification
 * (every server-verified and predicted change on the client fires it), wherever the camera looks.
 */
final class AtlasClientSections {
    private AtlasClientSections() {}

    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientSections"));
    private static final long BUDGET_NS = 2_000_000L;  // client thread time a tick for reading chunks

    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();  // loaded chunks not read yet (ChunkPos.pack)
    // read and waiting for the far-field worker, which takes the one nearest the camera first (poll): in arrival order
    // a teleport left the chunks around the eye behind the whole of the old place's (3,200 chunks at ~1 a second: the
    // near light traced the tiles' stand-in houses, solid and a block too wide, for most of an hour)
    private static final java.util.ArrayList<AtlasSections.Decoded> READY = new java.util.ArrayList<>();
    private static final Set<Long> UP = ConcurrentHashMap.newKeySet();  // chunks whose sections are up (and still loaded)
    // chunks whose loaded (vanilla) blocks went up: atlas's own generation never goes over them (AtlasClientGen)
    private static final Set<Long> REAL = ConcurrentHashMap.newKeySet();
    private static final IdentityHashMap<BlockState, Integer> IDS = new IdentityHashMap<>();  // (client thread)
    // sections whose blocks changed (SectionPos.asLong), read again on their own (client thread)
    private static final Set<Long> DIRTY = ConcurrentHashMap.newKeySet();
    // Per-block immutable tables. Each visual group uses its minimum state id, independent of caller order.
    // Replacing the map at reset prevents an in-flight old build from publishing into a new context's epoch.
    private static final AtlasCanonicalLooks.Epochs<net.minecraft.world.level.block.Block, BlockState> LOOKS = new AtlasCanonicalLooks.Epochs<>();
    private static Object idsEpoch;  // IDS is only touched on the client thread

    static void init() {
        if (!ON) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ClientLevel l && l.dimension() == Level.OVERWORLD && e.getChunk() instanceof LevelChunk c)
                QUEUED.add(c.getPos().pack());
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload e) -> {
            if (e.getLevel() instanceof ClientLevel && e.getChunk() != null) {
                long k = e.getChunk().getPos().pack();
                QUEUED.remove(k);
                UP.remove(k);
                REAL.remove(k);
            }
        });
        // a block changed on the client (a server update, a prediction, a piston): its section read again, seen or not
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent e) -> {
            if (e.getLevel() instanceof ClientLevel l && l.dimension() == Level.OVERWORLD) {
                BlockPos p = e.getPos();
                if (UP.contains(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4)))
                    DIRTY.add(net.minecraft.core.SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4));
            }
        });
    }

    /**
     * Whether near (client) sections keep a state: what the far field keeps (AtlasSections.kept) and every other block
     * drawn by a model with quads that is not translucent (glass and ice stay the translucent layer's); a block entity
     * drawn by its renderer alone (a chest) is not (it would stand as a cube inside its own model).
     */
    static boolean nearKept(BlockState s) {
        if (s.isAir()) return false;
        if (AtlasSections.kept(s)) return true;
        if (s.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return false;
        try {
            return AtlasShapes.stateModel(s).brickable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The near id of a state, the one rule for every near section atlas takes (the client feed's, and generation's:
     * AtlasClientGen's table of its generator ids): its full state id, or the lowest id of a state looking just like
     * it; 0 not kept. Any thread.
     */
    static int nearIdOf(BlockState s) {
        return nearId(s);
    }

    private static int nearId(BlockState s) {
        if (s.isAir()) return 0;
        Object models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        var epoch = LOOKS.forModels(models);
        var block = s.getBlock();
        var ids = epoch.blocks.get(block);
        if (ids == null) {
            if (!nearKept(s)) return 0;
            // Build outside the concurrent map: a render caller never waits for another caller's model scan.
            // If first callers race, each scan yields the same canonical ids; only one table is retained.
            var built = buildLooks(block);
            var previous = epoch.blocks.putIfAbsent(block, built);
            ids = previous != null ? previous : built;
        }
        if (models != Minecraft.getInstance().getModelManager().getBlockStateModelSet() || !LOOKS.isCurrent(epoch))
            throw new IllegalStateException("atlas block models changed during alias construction");
        Integer id = ids.get(s);
        return id != null ? id : 0;
    }

    private static java.util.Map<BlockState, Integer> buildLooks(net.minecraft.world.level.block.Block block) {
        long started = System.nanoTime();
        var states = block.getStateDefinition().getPossibleStates();
        var keys = new java.util.IdentityHashMap<BlockState, java.util.List<Object>>();
        var groups = new AtlasCanonicalLooks<java.util.List<Object>>();
        for (BlockState s : states) {
            if (!nearKept(s)) continue;
            var key = lookKey(s);
            keys.put(s, key);
            groups.add(key, net.minecraft.world.level.block.Block.getId(s));
        }
        var ids = new java.util.IdentityHashMap<BlockState, Integer>();
        for (var entry : keys.entrySet()) ids.put(entry.getKey(), groups.id(entry.getValue()));
        double elapsedMs = (System.nanoTime() - started) / 1e6;
        if (elapsedMs >= 2.0)
            com.nirncraft.NirnCraft.LOGGER.info("atlas: canonical states for {}: {} states, {} kept, {} groups, {} ms",
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block), states.size(), ids.size(), groups.size(),
                    String.format(java.util.Locale.ROOT, "%.2f", elapsedMs));
        return java.util.Collections.unmodifiableMap(ids);
    }

    static void resetLooks() {
        LOOKS.reset();
        AtlasShapes.clearSprites();
    }

    private static java.util.List<Object> lookKey(BlockState s) {
        // what it looks like: the model parts it draws (by identity: a fence's arms, a door's half, a crop's age; a model
        // object can be shared by every state of a block and pick its parts by the state it is given)
        java.util.List<Object> parts = new java.util.ArrayList<>();
        for (var part : AtlasShapes.parts(s)) parts.add(new Ident(part));
        if (parts.isEmpty()) parts.add(new Ident(s));  // (no model parts: a fluid; one look a state)
        // (and its fluid: a waterlogged fence is the water's too)
        return java.util.List.of(s.getBlock(), parts, AtlasMaterials.tintKind(s), s.getLightEmission(),
                s.getFluidState().isEmpty());
    }

    /** An object compared by identity (a model instance). */
    private record Ident(Object o) {
        @Override
        public boolean equals(Object x) {
            return x instanceof Ident i && i.o == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(o);
        }
    }

    /** Section sec's blocks as near ids (nearId), x + 16 * (z + 16 * y). */
    private static int[] nearIds(net.minecraft.world.level.chunk.LevelChunkSection sec) {
        var epoch = LOOKS.forModels(Minecraft.getInstance().getModelManager().getBlockStateModelSet());
        if (idsEpoch != epoch) {
            IDS.clear();
            idsEpoch = epoch;
        }
        int[] ids = new int[4096];
        if (sec == null || sec.hasOnlyAir()) return ids;
        BlockState prevState = null;
        int prevId = 0;
        for (int y = 0; y < 16; y++) {
            int yBase = 256 * y;
            for (int z = 0; z < 16; z++) {
                int zBase = yBase + 16 * z;
                for (int x = 0; x < 16; x++) {
                    BlockState s = sec.getBlockState(x, y, z);
                    int id;
                    if (s == prevState) {
                        id = prevId;
                    } else if (s.isAir()) {
                        prevState = s;
                        prevId = id = 0;
                    } else {
                        Integer cached = IDS.get(s);
                        if (cached == null) {
                            cached = nearId(s);
                            IDS.put(s, cached);
                        }
                        prevState = s;
                        prevId = id = cached;
                    }
                    ids[zBase + x] = id;
                }
            }
        }
        return ids;
    }

    /**
     * A loaded chunk whose blocks changed (vanilla is rebuilding one of its section meshes: AtlasNearPass hands them
     * over each frame): read again, so atlas's world, and the light it traces near the player, follow digging and
     * building (sections that did not change are skipped at upload). Render thread.
     */
    static void changed(long chunkPack) {
        if (ON && UP.contains(chunkPack)) QUEUED.add(chunkPack);
    }

    /** Client thread, each tick while atlas runs: the queued chunks read (nearest first), within the budget. */
    private static final AtlasRecorder.Zone Z_TICK = AtlasRecorder.zone("sections:tick");

    static void tick(Minecraft mc) {
        long rz = AtlasRecorder.begin();
        try {
            tickBody(mc);
        } finally {
            AtlasRecorder.end(Z_TICK, rz, 50_000);
        }
    }

    private static void tickBody(Minecraft mc) {
        if (!ON || (QUEUED.isEmpty() && DIRTY.isEmpty()) || mc.level == null || mc.player == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) {  // (left without its chunks' unloads: nothing is waiting)
            QUEUED.clear();
            synchronized (READY) {
                READY.clear();
            }
            return;
        }
        long until = System.nanoTime() + BUDGET_NS;
        // the changed sections first, each on its own (a whole chunk queued again is read whole below)
        for (java.util.Iterator<Long> it = DIRTY.iterator(); it.hasNext() && System.nanoTime() < until; ) {
            long sk = it.next();
            it.remove();
            int cx = net.minecraft.core.SectionPos.x(sk), sy = net.minecraft.core.SectionPos.y(sk), cz = net.minecraft.core.SectionPos.z(sk);
            if (QUEUED.contains(ChunkPos.pack(cx, cz))) continue;
            LevelChunk c = mc.level.getChunkSource().getChunkNow(cx, cz);
            if (c == null || sy < c.getMinSectionY() || sy > c.getMaxSectionY()) continue;
            int[][] one = {nearIds(c.getSection(sy - c.getMinSectionY()))};
            int[][] tints = columnTints(mc.level, c, sy * 16 + 8);
            AtlasSections.Decoded d = new AtlasSections.Decoded(cx, cz, sy, one, tints[2][0], tints[2][1], tints[0], tints[1]);
            dirtyCount++;
            synchronized (READY) {
                READY.add(d);
            }
        }
        if (QUEUED.isEmpty()) return;
        int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
        List<Long> order = new ArrayList<>(QUEUED);
        order.sort(java.util.Comparator.comparingLong(k -> {
            long dx = ChunkPos.getX(k) - pcx, dz = ChunkPos.getZ(k) - pcz;
            return dx * dx + dz * dz;
        }));
        for (long k : order) {
            if (System.nanoTime() > until) break;
            QUEUED.remove(k);
            LevelChunk c = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
            if (c != null) {
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                if (UP.contains(k) && !com.nirncraft.atlas.server.AtlasServerStats.isRecording()
                        && com.nirncraft.atlas.server.AtlasServerAgreement.isPureGpu(cx, cz)
                        && AtlasSideOps.at(cx, cz).isEmpty()) {
                    REAL.add(k);
                    continue;
                }
                long r0 = System.nanoTime();
                AtlasSections.Decoded d = read(mc.level, c);
                readNs += System.nanoTime() - r0;
                readCount++;
                synchronized (READY) {
                    READY.add(d);
                }
            }
        }
    }

    /** Chunk c's sections as atlas takes them (the server's rule, AtlasSections.record). */
    private static AtlasSections.Decoded read(ClientLevel level, LevelChunk c) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                low = Math.min(low, c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                high = Math.max(high, c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            }
        int minSec = c.getMinSectionY(), sy1 = Math.min(Math.floorDiv(high, 16), c.getMaxSectionY());
        // (and up to the highest section anything put up for this chunk: generation's crowns over its real top go,
        // as all-air sections, else they floated there for good)
        sy1 = Math.min(Math.max(sy1, AtlasEditsClient.highest(c.getPos().x(), c.getPos().z())), c.getMaxSectionY());
        int n = Math.max(0, sy1 - minSec + 1);
        int[][] secs = new int[n][];
        for (int s = 0; s < n; s++) {
            var sec = c.getSection(s);
            if (AtlasSections.deep(minSec + s, low) && AtlasSections.solidRock(sec)) continue;  // the tiles' stone
            secs[s] = nearIds(sec);
        }
        int cx = c.getPos().x(), cz = c.getPos().z();
        com.nirncraft.atlas.server.AtlasServerAgreement.client(cx, cz, minSec, secs);  // (the server's chunk against the GPU's)
        int[][] tints = columnTints(level, c, high);
        return new AtlasSections.Decoded(cx, cz, minSec, secs, tints[2][0], tints[2][1], tints[0], tints[1]);
    }

    private static final int[] TINT_GRID = {0, 4, 8, 12, 15};

    /**
     * Chunk c's grass and foliage colours per column (x + 16 z) as vanilla blends them (BiomeColors, its colour cache)
     * at height y: what the chunk mesher tints each block with.
     *
     * <p>Fast-paths uniform biome chunks (>85% of chunks) by checking the 4 corners + center (5 samples vs 256),
     * returning {@code null} column arrays when the chunk's biome tint is uniform so section hashes match the GPU
     * generator's section claims without re-uploading unchanged sections.
     */
    private static int[][] columnTints(ClientLevel level, LevelChunk c, int y) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = c.getPos().x() << 4, bz = c.getPos().z() << 4;

        p.set(bx + 8, y, bz + 8);
        int gCenter = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
        int fCenter = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;

        p.set(bx, y, bz);
        int g00 = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
        int f00 = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;

        p.set(bx + 15, y, bz);
        int g40 = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
        int f40 = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;

        p.set(bx, y, bz + 15);
        int g04 = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
        int f04 = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;

        p.set(bx + 15, y, bz + 15);
        int g44 = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
        int f44 = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;

        if (g00 == gCenter && g40 == gCenter && g04 == gCenter && g44 == gCenter
                && f00 == fCenter && f40 == fCenter && f04 == fCenter && f44 == fCenter) {
            return new int[][] {null, null, {gCenter, fCenter}};
        }

        int[] grass = new int[256], foliage = new int[256];
        int[] gs = new int[25], fs = new int[25];
        gs[0] = g00; fs[0] = f00;
        gs[4] = g40; fs[4] = f40;
        gs[12] = gCenter; fs[12] = fCenter;
        gs[20] = g04; fs[20] = f04;
        gs[24] = g44; fs[24] = f44;

        for (int gz = 0; gz < 5; gz++) {
            int z = TINT_GRID[gz];
            for (int gx = 0; gx < 5; gx++) {
                int idx = gz * 5 + gx;
                if (idx == 0 || idx == 4 || idx == 12 || idx == 20 || idx == 24) continue;
                p.set(bx + TINT_GRID[gx], y, bz + z);
                gs[idx] = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
                fs[idx] = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;
            }
        }

        interpolateGrid(gs, grass);
        interpolateGrid(fs, foliage);
        return new int[][] {grass, foliage, {grass[136], foliage[136]}};
    }

    private static void interpolateGrid(int[] grid25, int[] out256) {
        for (int gz = 0; gz < 4; gz++) {
            int z0 = TINT_GRID[gz], z1 = TINT_GRID[gz + 1], dz = z1 - z0;
            for (int gx = 0; gx < 4; gx++) {
                int x0 = TINT_GRID[gx], x1 = TINT_GRID[gx + 1], dx = x1 - x0;
                int c00 = grid25[gz * 5 + gx], c10 = grid25[gz * 5 + gx + 1];
                int c01 = grid25[(gz + 1) * 5 + gx], c11 = grid25[(gz + 1) * 5 + gx + 1];
                if (c00 == c10 && c00 == c01 && c00 == c11) {
                    for (int z = z0; z <= z1; z++) {
                        int row = z << 4;
                        for (int x = x0; x <= x1; x++) out256[row + x] = c00;
                    }
                    continue;
                }
                int r00 = (c00 >> 16) & 0xFF, g00 = (c00 >> 8) & 0xFF, b00 = c00 & 0xFF;
                int r10 = (c10 >> 16) & 0xFF, g10 = (c10 >> 8) & 0xFF, b10 = c10 & 0xFF;
                int r01 = (c01 >> 16) & 0xFF, g01 = (c01 >> 8) & 0xFF, b01 = c01 & 0xFF;
                int r11 = (c11 >> 16) & 0xFF, g11 = (c11 >> 8) & 0xFF, b11 = c11 & 0xFF;
                int area = dx * dz;
                int half = area >> 1;
                for (int z = z0; z <= z1; z++) {
                    int wz1 = z - z0, wz0 = z1 - z;
                    int row = z << 4;
                    for (int x = x0; x <= x1; x++) {
                        int wx1 = x - x0, wx0 = x1 - x;
                        int w00 = wx0 * wz0, w10 = wx1 * wz0, w01 = wx0 * wz1, w11 = wx1 * wz1;
                        int r = (r00 * w00 + r10 * w10 + r01 * w01 + r11 * w11 + half) / area;
                        int g = (g00 * w00 + g10 * w10 + g01 * w01 + g11 * w11 + half) / area;
                        int b = (b00 * w00 + b10 * w10 + b01 * w01 + b11 * w11 + half) / area;
                        out256[row + x] = (r << 16) | (g << 8) | b;
                    }
                }
            }
        }
    }

    private static long readNs, readCount, dirtyCount;

    /** Where a chunk is in the feed (debugging): queued to read, read and waiting, up, real. */
    static String chunkFlags(int cx, int cz) {
        long k = ChunkPos.pack(cx, cz);
        return (QUEUED.contains(k) ? "Q" : "") + (UP.contains(k) ? "U" : "") + (REAL.contains(k) ? "R" : "");
    }

    /** The feed's state for atlas-info: queued to read, read and waiting for the worker, up. */
    static String state() {
        int ready;
        synchronized (READY) {
            ready = READY.size();
        }
        return "clientSections{on=" + ON + " queued=" + QUEUED.size() + " ready=" + ready + " up=" + UP.size()
                + " read=" + readCount + " dirtySections=" + dirtyCount + " dirtyQueued=" + DIRTY.size() + " rereads=" + rereads + String.format(java.util.Locale.ROOT, " readMsEach=%.3f", readCount > 0 ? readNs / 1e6 / readCount : 0.0) + "}";
    }

    /** The read chunk nearest block (x, z) for atlas, or null (far-field worker). */
    static AtlasSections.Decoded poll(double x, double z) {
        synchronized (READY) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < READY.size(); i++) {
                AtlasSections.Decoded d = READY.get(i);
                double dx = d.cx() * 16 + 8 - x, dz = d.cz() * 16 + 8 - z, dd = dx * dx + dz * dz;
                if (dd < bestD) {
                    bestD = dd;
                    best = i;
                }
            }
            if (best < 0) return null;
            AtlasSections.Decoded d = READY.get(best);
            READY.set(best, READY.get(READY.size() - 1));
            READY.remove(READY.size() - 1);
            return d;
        }
    }

    /** The loaded chunk's own blocks are going up (far-field worker). */
    static void real(int cx, int cz) {
        REAL.add(ChunkPos.pack(cx, cz));
    }

    /**
     * Level 0 asks again for a section of a loaded chunk (AtlasClientGen.gridReports: its GPU copy was let go while it
     * waited past the reach, and the claim that kept the feed from putting it up again is gone): read it again from the
     * chunk, which the generator never remakes (its own blocks stand). Returns whether it was queued. Any thread.
     */
    static boolean reread(int cx, int sy, int cz) {
        if (!ON || !REAL.contains(ChunkPos.pack(cx, cz))) return false;
        DIRTY.add(net.minecraft.core.SectionPos.asLong(cx, sy, cz));
        rereads++;
        return true;
    }

    /** Sections read again for level 0 (reread), for the profile. */
    static volatile long rereads;

    /** Whether this chunk's blocks are up in atlas from any source: generated, from the cache, or the loaded chunk's own. */
    static boolean isUp(int cx, int cz) {
        long k = ChunkPos.pack(cx, cz);
        return UP.contains(k) || REAL.contains(k);
    }

    /** Whether the loaded chunk's own blocks are up (then generated ones must not replace them). */
    static boolean isReal(int cx, int cz) {
        return REAL.contains(ChunkPos.pack(cx, cz));
    }

    /** Its sections went up (far-field worker). */
    static void up(int cx, int cz) {
        UP.add(ChunkPos.pack(cx, cz));
    }

    /** Chunks read and not yet up (the far field is busy while there are). */
    static int pending() {
        synchronized (READY) {
            return QUEUED.size() + READY.size();
        }
    }

    /**
     * Whether atlas holds the real blocks around block (x, z): the 3 x 3 chunks there are up. Then a pixel the near field
     * left empty can be filled from the camera even underground (the far trace starts in the cave, not in tile stone).
     */
    static boolean coversEye(double x, double z) {
        if (!ON) return false;
        int cx = Math.floorDiv((int) Math.floor(x), 16), cz = Math.floorDiv((int) Math.floor(z), 16);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                if (!UP.contains(ChunkPos.pack(cx + dx, cz + dz))) return false;
        return true;
    }

    /**
     * Another world, or atlas started again (a new context holds nothing): every loaded chunk is read again. Client
     * thread (or the worker stopped).
     */
    static void reset() {
        READY.clear();
        UP.clear();
        REAL.clear();
        QUEUED.clear();
        DIRTY.clear();
        IDS.clear();
        resetLooks();
        Minecraft mc = Minecraft.getInstance();
        if (!ON || mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 2;
        int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                if (mc.level.getChunkSource().getChunkNow(pcx + dx, pcz + dz) != null) QUEUED.add(ChunkPos.pack(pcx + dx, pcz + dz));
    }
}

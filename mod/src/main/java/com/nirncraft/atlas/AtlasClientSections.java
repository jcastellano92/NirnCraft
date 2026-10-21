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
    // per (block, model, tint kind, light) the first state seen with it: states that look alike (a fire's age, a
    // leaf's distance) share one id, so a ticking block re-uploads nothing (client thread)
    private static final java.util.Map<java.util.List<Object>, Integer> LOOKS = new ConcurrentHashMap<>();  // (any thread)

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
            return AtlasShapes.brickable(s, AtlasShapes.quads(s));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The near id of a state, the one rule for every near section atlas takes (the client feed's, and generation's:
     * AtlasClientGen's table of its generator ids): its full state id, or that of the first state looking just like
     * it; 0 not kept. Any thread.
     */
    static int nearIdOf(BlockState s) {
        return nearId(s);
    }

    private static int nearId(BlockState s) {
        if (!nearKept(s)) return 0;
        Object model;
        try {
            model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(s);
        } catch (RuntimeException e) {
            model = s;
        }
        java.util.List<Object> key = java.util.List.of(s.getBlock(), new Ident(model), AtlasMaterials.tintKind(s), s.getLightEmission());
        return LOOKS.computeIfAbsent(key, k -> net.minecraft.world.level.block.Block.getId(s));
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
        int[] ids = new int[4096];
        if (sec == null || sec.hasOnlyAir()) return ids;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    BlockState s = sec.getBlockState(x, y, z);
                    Integer id = IDS.get(s);
                    if (id == null) {
                        id = nearId(s);
                        IDS.put(s, id);
                    }
                    ids[x + 16 * (z + 16 * y)] = id;
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
    static void tick(Minecraft mc) {
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
            AtlasSections.Decoded d = new AtlasSections.Decoded(cx, cz, sy, one, tints[0][136], tints[1][136], tints[0], tints[1]);
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
        int n = Math.max(0, sy1 - minSec + 1);
        int[][] secs = new int[n][];
        for (int s = 0; s < n; s++) {
            var sec = c.getSection(s);
            if (AtlasSections.deep(minSec + s, low) && AtlasSections.solidRock(sec)) continue;  // the tiles' stone
            secs[s] = nearIds(sec);
        }
        int cx = c.getPos().x(), cz = c.getPos().z();
        int[][] tints = columnTints(level, c, high);
        return new AtlasSections.Decoded(cx, cz, minSec, secs, tints[0][136], tints[1][136], tints[0], tints[1]);
    }

    /**
     * Chunk c's grass and foliage colours per column (x + 16 z) as vanilla blends them (BiomeColors, its colour cache)
     * at height y: what the chunk mesher tints each block with.
     */
    private static int[][] columnTints(ClientLevel level, LevelChunk c, int y) {
        int[] grass = new int[256], foliage = new int[256];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = c.getPos().x() << 4, bz = c.getPos().z() << 4;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                p.set(bx + x, y, bz + z);
                grass[x + 16 * z] = net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, p) & 0xFFFFFF;
                foliage[x + 16 * z] = net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, p) & 0xFFFFFF;
            }
        return new int[][] {grass, foliage};
    }

    private static long readNs, readCount, dirtyCount;

    /** The feed's state for atlas-info: queued to read, read and waiting for the worker, up. */
    static String state() {
        int ready;
        synchronized (READY) {
            ready = READY.size();
        }
        return "clientSections{on=" + ON + " queued=" + QUEUED.size() + " ready=" + ready + " up=" + UP.size()
                + " read=" + readCount + " dirtySections=" + dirtyCount + " dirtyQueued=" + DIRTY.size() + String.format(java.util.Locale.ROOT, " readMsEach=%.3f", readCount > 0 ? readNs / 1e6 / readCount : 0.0) + "}";
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
        LOOKS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (!ON || mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 2;
        int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                if (mc.level.getChunkSource().getChunkNow(pcx + dx, pcz + dz) != null) QUEUED.add(ChunkPos.pack(pcx + dx, pcz + dz));
    }
}

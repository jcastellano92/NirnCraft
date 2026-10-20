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
import java.util.concurrent.ConcurrentLinkedQueue;

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
 */
final class AtlasClientSections {
    private AtlasClientSections() {}

    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientSections"));
    private static final long BUDGET_NS = 2_000_000L;  // client thread time a tick for reading chunks

    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();  // loaded chunks not read yet (ChunkPos.pack)
    private static final ConcurrentLinkedQueue<AtlasSections.Decoded> READY = new ConcurrentLinkedQueue<>();
    private static final Set<Long> UP = ConcurrentHashMap.newKeySet();  // chunks whose sections are up (and still loaded)
    private static final IdentityHashMap<BlockState, Integer> IDS = new IdentityHashMap<>();  // (client thread)

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
            }
        });
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
        if (!ON || QUEUED.isEmpty() || mc.level == null || mc.player == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) {  // (left without its chunks' unloads: nothing is waiting)
            QUEUED.clear();
            READY.clear();
            return;
        }
        long until = System.nanoTime() + BUDGET_NS;
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
            if (c != null) READY.add(read(mc.level, c));
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
            secs[s] = AtlasSections.ids(sec, IDS);
        }
        int cx = c.getPos().x(), cz = c.getPos().z();
        BlockPos centre = new BlockPos((cx << 4) + 8, high, (cz << 4) + 8);
        var biome = level.getBiome(centre).value();
        return new AtlasSections.Decoded(cx, cz, minSec, secs,
                biome.getGrassColor(centre.getX(), centre.getZ()) & 0xFFFFFF, biome.getFoliageColor() & 0xFFFFFF);
    }

    /** The next chunk read for atlas, or null (far-field worker). */
    static AtlasSections.Decoded poll() {
        return READY.poll();
    }

    /** Its sections went up (far-field worker). */
    static void up(int cx, int cz) {
        UP.add(ChunkPos.pack(cx, cz));
    }

    /** Chunks read and not yet up (the far field is busy while there are). */
    static int pending() {
        return QUEUED.size() + READY.size();
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
        QUEUED.clear();
        IDS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (!ON || mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 2;
        int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                if (mc.level.getChunkSource().getChunkNow(pcx + dx, pcz + dz) != null) QUEUED.add(ChunkPos.pack(pcx + dx, pcz + dz));
    }
}

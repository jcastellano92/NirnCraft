package com.skycraft.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * Terrain reads for builders that run inside a feature (a {@link WorldGenRegion}).
 *
 * <p>26.3 lets a feature read and write only the 3x3 chunks around the chunk being decorated; a chunk further out may
 * be anywhere between structure starts and features, and may be written by another worker thread at the same moment
 * (the game logs "Detected unsafe terrain read during worldgen"). 1.20.1 had the same limit but said nothing, so a
 * builder whose probes reached further (a formation's slope probes, a fence's neighbour) read whatever that chunk
 * happened to hold. Inside the write zone these helpers read the region as before; outside it they answer from the
 * generator's deterministic base column (the same noise terrain that chunk will get), like the settlement
 * {@code Canvas} does. Nothing is ever written outside the zone, so only the builders' decisions see these values.</p>
 *
 * <p>Outside a generation region (a live level: dev commands, showcases) every read goes to the level.</p>
 */
public final class SafeReads {
    private SafeReads() {}

    private static final int CACHE_COLUMNS = 4096;

    private static final class Cache {
        ServerLevel owner;
        final Long2ObjectOpenHashMap<NoiseColumn> columns = new Long2ObjectOpenHashMap<>();
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    /** True when column (x, z) may be read directly: always in a live level, inside the write zone in a region. */
    public static boolean readable(LevelReader level, int x, int z) {
        if (!(level instanceof WorldGenRegion region)) return true;
        ChunkPos c = region.getCenter();
        return Math.abs((x >> 4) - c.x()) <= 1 && Math.abs((z >> 4) - c.z()) <= 1;
    }

    public static boolean readable(LevelReader level, BlockPos p) {
        return readable(level, p.getX(), p.getZ());
    }

    public static BlockState state(LevelReader level, BlockPos p) {
        if (readable(level, p)) return level.getBlockState(p);
        if (p.getY() < level.getMinY() || p.getY() > level.getMaxY()) return Blocks.VOID_AIR.defaultBlockState();
        return column((WorldGenRegion) level, p.getX(), p.getZ()).getBlock(p.getY());
    }

    public static FluidState fluid(LevelReader level, BlockPos p) {
        if (readable(level, p)) return level.getFluidState(p);
        return state(level, p).getFluidState();
    }

    /** {@link LevelReader#getHeight(Heightmap.Types, int, int)}: the y above the top block that counts for {@code type}. */
    public static int height(LevelReader level, Heightmap.Types type, int x, int z) {
        if (readable(level, x, z)) return level.getHeight(type, x, z);
        NoiseColumn col = column((WorldGenRegion) level, x, z);
        var opaque = type.isOpaque();
        for (int y = level.getMaxY(); y >= level.getMinY(); y--) {
            if (opaque.test(col.getBlock(y))) return y + 1;
        }
        return level.getMinY();
    }

    private static NoiseColumn column(WorldGenRegion region, int x, int z) {
        Cache c = CACHE.get();
        ServerLevel owner = region.getLevel();
        if (c.owner != owner) {
            c.owner = owner;
            c.columns.clear();
        }
        long key = ChunkPos.pack(x, z);
        NoiseColumn col = c.columns.get(key);
        if (col == null) {
            var source = owner.getChunkSource();
            col = source.getGenerator().getBaseColumn(x, z, region, source.randomState());
            if (c.columns.size() >= CACHE_COLUMNS) c.columns.clear();
            c.columns.put(key, col);
        }
        return col;
    }
}

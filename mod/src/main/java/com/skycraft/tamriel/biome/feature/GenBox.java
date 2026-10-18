package com.skycraft.tamriel.biome.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The writable box of one feature placement: the 3×3 chunks around the chunk being decorated. Every block a
 * Skycraft biome feature writes goes through {@link #set}, so big trees can never touch a chunk outside the
 * region (which would log an error and drop the write).
 */
public final class GenBox {
    public final WorldGenLevel level;
    public final int minX, maxX, minZ, maxZ, minY, maxY;
    /** The decorated chunk. */
    public final ChunkPos center;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private GenBox(WorldGenLevel level, ChunkPos center) {
        this.level = level;
        this.center = center;
        this.minX = center.getMinBlockX() - 16;
        this.maxX = center.getMaxBlockX() + 16;
        this.minZ = center.getMinBlockZ() - 16;
        this.maxZ = center.getMaxBlockZ() + 16;
        this.minY = level.getMinY() + 1;
        this.maxY = (level.getMaxY() + 1) - 1;
    }

    public static GenBox of(WorldGenLevel level, BlockPos origin) {
        ChunkPos center = level instanceof WorldGenRegion region ? region.getCenter() : ChunkPos.containing(origin);
        return new GenBox(level, center);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y < maxY;
    }

    /** How far a structure centred on (x, z) may reach without leaving the box. */
    public int reach(int x, int z) {
        return Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
    }

    public BlockState get(int x, int y, int z) {
        if (!contains(x, y, z)) return Blocks.AIR.defaultBlockState();
        return level.getBlockState(cursor.set(x, y, z));
    }

    public boolean set(int x, int y, int z, BlockState state) {
        if (!contains(x, y, z)) return false;
        return level.setBlock(cursor.set(x, y, z), state, 2);
    }

    /** Sets the block only where the current one is air, a plant, snow, leaves or water (never terrain or logs). */
    public boolean setSoft(int x, int y, int z, BlockState state) {
        if (!contains(x, y, z)) return false;
        BlockState here = level.getBlockState(cursor.set(x, y, z));
        if (!isSoft(here)) return false;
        return level.setBlock(cursor, state, 2);
    }

    public static boolean isSoft(BlockState here) {
        return here.isAir() || here.canBeReplaced() || here.is(BlockTags.LEAVES) || here.is(BlockTags.REPLACEABLE_BY_TREES)
                || here.is(Blocks.SNOW) || here.is(Blocks.WATER) || here.is(BlockTags.FLOWERS) || here.is(BlockTags.SAPLINGS);
    }

    /** Surface height (first free block) of a column, or {@link Integer#MIN_VALUE} outside the box. */
    public int surface(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return Integer.MIN_VALUE;
        return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
    }

    /** Surface height counting water as ground (first air block). */
    public int surfaceWithWater(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return Integer.MIN_VALUE;
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }
}

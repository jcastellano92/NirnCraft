package com.nirncraft.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * A safe place to stand for respawns and first arrivals: solid ground that is not a leaf or log (never a treetop or
 * canopy), two blocks of air above it, no water or lava. Columns come from the "no leaves" heightmap, and only
 * chunks that are already loaded are read (the target's own chunk is loaded on demand).
 */
public final class SafeSpawn {
    private SafeSpawn() {}

    /** The standing position (feet block) nearest {@code target}, within {@code maxRadius} blocks, or null. */
    @Nullable
    public static BlockPos find(ServerLevel level, BlockPos target, int maxRadius) {
        level.getChunk(target.getX() >> 4, target.getZ() >> 4);
        return findLoaded(level, target, maxRadius);
    }

    /** Like {@link #find} but never loads or generates a chunk: only already-loaded chunks are read. */
    @Nullable
    public static BlockPos findLoaded(ServerLevel level, BlockPos target, int maxRadius) {
        for (int r = 0; r <= maxRadius; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    int x = target.getX() + dx;
                    int z = target.getZ() + dz;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) continue;
                    BlockPos p = column(level, chunk, x, z);
                    if (p != null) return p;
                }
            }
        }
        return null;
    }

    /** True if a player standing at {@code feet} would be on safe ground. */
    public static boolean isSafe(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        return standable(level, below, ground)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getFluidState(feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet.above()).isEmpty();
    }

    @Nullable
    private static BlockPos column(ServerLevel level, LevelChunk chunk, int x, int z) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        if (top <= level.getMinY() + 1 || top >= (level.getMaxY()+1) - 3) return null;
        // the heightmap gives the first free block above the top non-leaf block; settle on the first sound floor
        for (int y = top; y >= Math.max(level.getMinY() + 2, top - 6); y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (isSafe(level, feet)) return feet;
        }
        return null;
    }

    private static boolean standable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.FIRE)) return false;
        return state.isFaceSturdy(level, pos, Direction.UP) && level.getFluidState(pos).isEmpty()
                && !state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                && !state.is(net.minecraft.world.level.block.Blocks.CACTUS);
    }
}

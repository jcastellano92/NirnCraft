package com.nirncraft.seas.world;

import com.nirncraft.NirnCraft;
import com.nirncraft.seas.SeasRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Block placement for the sea and coast sites: a centre (the middle of the decorated chunk, so a site of radius up to
 * 20 stays inside the 3 x 3 chunks a feature may write), heights, waterlogging that follows the water around, loot
 * containers, lair markers and a few shapes. Writes outside the allowed chunks are dropped.
 */
public final class SiteKit {
    public final WorldGenLevel level;
    public final RandomSource r;
    /** Site centre: the decorated chunk's middle. */
    public final int cx, cz;
    private final int chunkX, chunkZ;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    public SiteKit(WorldGenLevel level, RandomSource r, BlockPos origin) {
        this.level = level;
        this.r = r;
        this.chunkX = origin.getX() >> 4;
        this.chunkZ = origin.getZ() >> 4;
        this.cx = (chunkX << 4) + 8;
        this.cz = (chunkZ << 4) + 8;
    }

    public static Identifier loot(String path) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, path);
    }

    /** Whether a column may be written (the decorated chunk and its eight neighbours). */
    public boolean ok(int x, int z) {
        return Math.abs((x >> 4) - chunkX) <= 1 && Math.abs((z >> 4) - chunkZ) <= 1;
    }

    // ------------------------------------------------------------------ heights

    /** Top of the solid ground (sea bed under water). */
    public int floor(int x, int z) {
        if (!ok(x, z)) return Integer.MIN_VALUE;
        if (level instanceof net.minecraft.server.level.WorldGenRegion) return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
    }

    /** First air above the surface (water counts as surface). */
    public int surface(int x, int z) {
        if (!ok(x, z)) return Integer.MIN_VALUE;
        if (level instanceof net.minecraft.server.level.WorldGenRegion) return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    /** Water depth at a column (0 on land). */
    public int depth(int x, int z) {
        return Math.max(0, surface(x, z) - floor(x, z));
    }

    public boolean water(int x, int y, int z) {
        return ok(x, z) && level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER);
    }

    public BlockState get(int x, int y, int z) {
        return ok(x, z) ? level.getBlockState(m.set(x, y, z)) : Blocks.AIR.defaultBlockState();
    }

    // ------------------------------------------------------------------ placing

    /** Places a block; waterloggable blocks take the water already there. */
    public void set(int x, int y, int z, BlockState state) {
        if (!ok(x, z) || y <= level.getMinY() || y >= (level.getMaxY()+1) - 1) return;
        m.set(x, y, z);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(m).is(FluidTags.WATER) || isWaterBlock(level.getBlockState(m)));
        }
        level.setBlock(m, state, 2);
    }

    /** Places a block forcing the waterlogging state (air pockets: false). */
    public void setDry(int x, int y, int z, BlockState state) {
        if (!ok(x, z) || y <= level.getMinY() || y >= (level.getMaxY()+1) - 1) return;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        level.setBlock(m.set(x, y, z), state, 2);
    }

    private static boolean isWaterBlock(BlockState s) {
        return s.is(Blocks.WATER) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
                || s.is(Blocks.BUBBLE_COLUMN);
    }

    /** Only where nothing solid stands yet (water, air, plants). */
    public void setIfOpen(int x, int y, int z, BlockState state) {
        BlockState at = get(x, y, z);
        if (at.isAir() || isWaterBlock(at) || at.canBeReplaced()) set(x, y, z, state);
    }

    /** Clears to water (under the sea) or air. */
    public void clear(int x, int y, int z, int seaLevel) {
        set(x, y, z, y <= seaLevel ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    public void air(int x, int y, int z) {
        setDry(x, y, z, Blocks.AIR.defaultBlockState());
    }

    /** Fills a box (inclusive). */
    public void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, state);
    }

    /** Ground under a column down to the floor (pillars, foundations). */
    public void pillarDown(int x, int y, int z, BlockState state, int maxDepth) {
        for (int d = 0; d < maxDepth; d++) {
            BlockState at = get(x, y - d, z);
            if (d > 0 && !at.isAir() && !isWaterBlock(at) && !at.canBeReplaced()) break;
            set(x, y - d, z, state);
        }
    }

    // ------------------------------------------------------------------ containers, markers

    public void chest(int x, int y, int z, Direction facing, Identifier table) {
        if (!ok(x, z)) return;
        set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        lootAt(x, y, z, table);
    }

    public void barrel(int x, int y, int z, Identifier table) {
        if (!ok(x, z)) return;
        set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        lootAt(x, y, z, table);
    }

    private void lootAt(int x, int y, int z, Identifier table) {
        BlockEntity be = level.getBlockEntity(m.set(x, y, z));
        if (be instanceof RandomizableContainerBlockEntity c) c.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table), r.nextLong());
    }

    /** Leaves a lair marker; fill it with {@link LairMarkerBlockEntity#creature} / {@link LairMarkerBlockEntity#npc}. */
    @Nullable
    public LairMarkerBlockEntity marker(int x, int y, int z) {
        if (!ok(x, z)) return null;
        set(x, y, z, SeasRegistry.LAIR_MARKER.get().defaultBlockState());
        return level.getBlockEntity(m.set(x, y, z)) instanceof LairMarkerBlockEntity be ? be : null;
    }

    /** A horizontal-facing block (stairs, chests, furnaces...). */
    public static BlockState facing(BlockState s, Direction d) {
        if (s.hasProperty(HorizontalDirectionalBlock.FACING)) return s.setValue(HorizontalDirectionalBlock.FACING, d);
        if (s.hasProperty(BlockStateProperties.FACING)) return s.setValue(BlockStateProperties.FACING, d);
        return s;
    }

    // ------------------------------------------------------------------ shapes

    /**
     * A rough mound of ground (islets, lair mounds): radius {@code rad} at the base rising to {@code top}, with noise
     * on the edge; {@code skin} on the outside, {@code core} within. Returns nothing; ground under it is filled down.
     */
    public void mound(int x0, int z0, float rad, int top, BlockState core, BlockState skin, BlockState beach, int seaLevel) {
        int ir = (int) Math.ceil(rad) + 2;
        double seed = r.nextDouble() * 100;
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                int x = x0 + dx, z = z0 + dz;
                if (!ok(x, z)) continue;
                double a = Math.atan2(dz, dx);
                double edge = rad * (0.82 + 0.18 * Math.sin(a * 3 + seed) + 0.08 * Math.sin(a * 7 + seed * 2));
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > edge) continue;
                double t = 1 - d / edge;
                int bed = floor(x, z);
                int h = (int) Math.round(seaLevel - 1 + (top - seaLevel + 1) * Math.pow(t, 0.42));
                if (h < bed) continue;
                for (int y = bed; y <= h; y++) {
                    boolean outer = y >= h - 1;
                    BlockState s = outer ? (h <= seaLevel + 1 ? beach : skin) : core;
                    set(x, y, z, s);
                }
                for (int y = h + 1; y <= seaLevel; y++) {
                    if (water(x, y, z)) continue;
                    set(x, y, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
    }

    /** A disc of {@code state} at height y (floors, platforms). */
    public void disc(int x0, int y, int z0, float rad, BlockState state) {
        int ir = (int) Math.ceil(rad);
        for (int dx = -ir; dx <= ir; dx++)
            for (int dz = -ir; dz <= ir; dz++)
                if (dx * dx + dz * dz <= rad * rad) set(x0 + dx, y, z0 + dz, state);
    }

    /** An upward bubble column from a soul sand vent: breathable air for divers (vanilla refills air in it). */
    public void airVent(int x, int yBase, int z, int yTop) {
        set(x, yBase, z, Blocks.SOUL_SAND.defaultBlockState());
        for (int y = yBase + 1; y <= yTop; y++) {
            if (!water(x, y, z)) break;
            set(x, y, z, Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BlockStateProperties.DRAG, false));
        }
    }

    public int nextInt(int lo, int hi) {
        return lo + r.nextInt(Math.max(1, hi - lo + 1));
    }
}

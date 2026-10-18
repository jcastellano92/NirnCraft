package com.skycraft.tamriel.terrain;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/**
 * {@code skycraft:map_water}: fills the map's lakes and rivers above sea level. The terrain density carves their
 * basins and channels below the map's water level; this feature, run once per chunk in the {@code lakes} step, puts
 * water source blocks in every carved column up to that level. Rivers step down along their course, so the surface
 * drops a block at a time (small waterfalls). It only touches the chunk being decorated.
 */
public class MapWaterFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    public MapWaterFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        int x0 = origin.getX() & ~15, z0 = origin.getZ() & ~15;
        MapSample s = new MapSample();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean placed = false;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                TamrielMap.sample(x, z, s);
                if (!s.inWater()) {
                    // marshes (Hjaalmarch, Black Marsh...) sit at sea level: their shallow dips below it are pools,
                    // not dry pits (inland, the aquifers leave them empty)
                    if (s.wetland >= 0.5f) placed |= fillMarshPool(level, pos, x, z);
                    continue;
                }
                int top = (int) Math.ceil(s.waterLevel) - 1;
                if (top < TamrielMap.SEA_LEVEL) continue;  // the sea and aquifers handle water below sea level
                int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                for (int y = floor; y <= top; y++) {
                    pos.set(x, y, z);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir() || (st.getFluidState().isEmpty() && st.canBeReplaced() && !st.isSolid())) {
                        level.setBlock(pos, WATER, 2);
                        placed = true;
                    }
                }
            }
        }
        return placed;
    }

    /** Fills an open-sky dip of at most {@link #MARSH_POOL_DEPTH} blocks below sea level with water up to sea level. */
    private static boolean fillMarshPool(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int top = TamrielMap.SEA_LEVEL - 1;
        int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        // deeper holes are cave mouths: leave them dry so the water doesn't pour into the caves
        if (floor > top || floor < TamrielMap.SEA_LEVEL - MARSH_POOL_DEPTH) return false;
        if (level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) != floor) return false;
        boolean placed = false;
        for (int y = floor; y <= top; y++) {
            pos.set(x, y, z);
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, WATER, 2);
                placed = true;
            }
        }
        return placed;
    }

    private static final int MARSH_POOL_DEPTH = 8;
}

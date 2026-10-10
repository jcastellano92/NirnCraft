package com.skycraft.tamriel.terrain;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * {@code skycraft:map_water}: fills the map's lakes and rivers above sea level. The terrain density carves their
 * basins and channels below the map's water level; this feature, run once per chunk in the {@code lakes} step, puts
 * water source blocks in every carved column up to that level. Rivers step down along their course, so the surface
 * drops a block at a time (small waterfalls). It only touches the chunk being decorated.
 */
public class MapWaterFeature extends Feature<NoneFeatureConfiguration> {
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
                if (!s.inWater()) continue;
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
}

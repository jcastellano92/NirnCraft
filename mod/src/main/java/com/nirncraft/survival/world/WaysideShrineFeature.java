package com.nirncraft.survival.world;

import com.mojang.serialization.Codec;
import com.nirncraft.survival.Divine;
import com.nirncraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

/**
 * A small wayside shrine: a 3x3 weathered stone platform with a shrine of a random Divine, candles and potted flowers.
 * Rare on the Overworld surface (see {@code nirncraft:wayside_shrine} placed feature).
 */
public class WaysideShrineFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public WaysideShrineFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        BlockState ground = level.getBlockState(origin.below());
        if (!ground.isSolid() || ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS) || ground.is(Blocks.ICE)
                || !level.getFluidState(origin).isEmpty() || !level.getFluidState(origin.below()).isEmpty()) {
            return false;
        }
        // fairly flat ground only
        int y0 = origin.getY();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX() + dx, origin.getZ() + dz);
                if (Math.abs(h - y0) > 1) return false;
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floor = origin.offset(dx, -1, dz);
                for (int d = 1; d <= 3; d++) {
                    BlockPos under = floor.below(d);
                    if (level.getBlockState(under).isSolid()) break;
                    setBlock(level, under, Blocks.COBBLESTONE.defaultBlockState());
                }
                BlockState stone = switch (random.nextInt(4)) {
                    case 0 -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                    case 1 -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
                    default -> Blocks.STONE_BRICKS.defaultBlockState();
                };
                setBlock(level, floor, stone);
                for (int up = 0; up <= 2; up++) {
                    BlockPos p = origin.offset(dx, up, dz);
                    if (!level.getBlockState(p).isAir()) setBlock(level, p, Blocks.AIR.defaultBlockState());
                }
            }
        }
        Divine divine = Divine.VALUES[random.nextInt(Divine.VALUES.length)];
        setBlock(level, origin, SurvivalRegistry.SHRINES.get(divine).get().defaultBlockState());
        int[][] corners = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
        for (int[] c : corners) {
            BlockPos p = origin.offset(c[0], 0, c[1]);
            int roll = random.nextInt(4);
            if (roll == 0) {
                setBlock(level, p, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + random.nextInt(3))
                        .setValue(CandleBlock.LIT, true));
            } else if (roll == 1) {
                setBlock(level, p, (random.nextBoolean() ? Blocks.POTTED_LILY_OF_THE_VALLEY : Blocks.POTTED_POPPY).defaultBlockState());
            }
        }
        return true;
    }
}

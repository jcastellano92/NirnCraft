package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/**
 * Red Mountain's lava vents: a cone of volcanic rock with glowing cracks around a crater of lava, ringed with ash
 * drifts. One in four is a huge vent (radius up to 12) centred on the chunk.
 */
public class LavaVentFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public LavaVentFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        boolean huge = random.nextInt(4) == 0;
        int cx = huge ? box.center.getMiddleBlockX() : ctx.origin().getX();
        int cz = huge ? box.center.getMiddleBlockZ() : ctx.origin().getZ();
        int r = huge ? 8 + random.nextInt(5) : 3 + random.nextInt(4);
        r = Math.min(r, box.reach(cx, cz) - 3);
        if (r < 3) return false;
        int base = box.surface(cx, cz);
        // reject slopes steeper than the cone itself
        int lo = base, hi = base;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int h = box.surface(cx + (int) (Math.cos(a) * r), cz + (int) (Math.sin(a) * r));
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        if (hi - lo > r) return false;
        int height = (int) (r * (0.7 + random.nextDouble() * 0.4));
        BlockState rock = BiomeBlocks.VOLCANIC_ROCK.get().defaultBlockState();
        BlockState cracked = BiomeBlocks.CRACKED_VOLCANIC_ROCK.get().defaultBlockState();
        BlockState magma = BiomeBlocks.MAGMA_VOLCANIC_ROCK.get().defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();
        int crater = Math.max(1, r / 3);
        int topY = lo + height;
        for (int dx = -r - 3; dx <= r + 3; dx++) {
            for (int dz = -r - 3; dz <= r + 3; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                int x = cx + dx, z = cz + dz;
                int ground = box.surface(x, z);
                if (d > r) {
                    // ash drifts around the foot
                    if (d <= r + 3 && random.nextInt(3) != 0 && GenBox.isSoft(box.get(x, ground, z))
                            && box.get(x, ground - 1, z).isFaceSturdy(ctx.level(), new net.minecraft.core.BlockPos(x, ground - 1, z), net.minecraft.core.Direction.UP)) {
                        box.set(x, ground, z, BiomeBlocks.ASH_LAYER.get().defaultBlockState()
                                .setValue(SnowLayerBlock.LAYERS, 1 + random.nextInt(3)));
                    }
                    continue;
                }
                int coneTop = topY - (int) Math.round((d / r) * height * (0.85 + random.nextDouble() * 0.3));
                for (int y = Math.min(ground, lo) - 2; y <= coneTop; y++) {
                    BlockState s = random.nextInt(7) == 0 ? magma : random.nextInt(4) == 0 ? cracked : rock;
                    box.set(x, y, z, s);
                }
                if (d <= crater) {
                    // the crater: lava pool sunk into the top, magma underneath
                    for (int y = topY - 2; y <= topY + 1; y++) box.set(x, y, z, Blocks.AIR.defaultBlockState());
                    box.set(x, topY - 2, z, lava);
                    box.set(x, topY - 1, z, d <= crater - 0.5 ? lava : rock);
                    box.set(x, topY - 3, z, magma);
                } else if (d <= crater + 1.5) {
                    box.set(x, topY - 1, z, magma);
                }
            }
        }
        return true;
    }
}

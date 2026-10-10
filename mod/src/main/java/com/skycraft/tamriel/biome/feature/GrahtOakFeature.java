package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Valenwood's Graht-oaks: giant trees with a flared, rooted trunk 5-7 blocks across, 35-55 blocks tall, and thick
 * flat-topped branches you can walk along, each ending in a leaf crown. The tree is re-centred on the decorated
 * chunk so its whole crown (up to 22 blocks out) stays inside the 3×3-chunk write area.
 */
public class GrahtOakFeature extends Feature<NoneFeatureConfiguration> {
    public GrahtOakFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(level, ctx.origin());
        int cx = box.center.getMiddleBlockX() + random.nextInt(5) - 2;
        int cz = box.center.getMiddleBlockZ() + random.nextInt(5) - 2;
        int y0 = box.surface(cx, cz);
        BlockState ground = box.get(cx, y0 - 1, cz);
        if (!ground.is(BlockTags.DIRT) && !ground.is(net.minecraft.world.level.block.Blocks.MUD)) return false;
        if (y0 > level.getMaxBuildHeight() - 70) return false;

        BlockState wood = BiomeBlocks.GRAHT_OAK_WOOD.get().defaultBlockState();
        BlockState leaves = BiomeBlocks.GRAHT_OAK_LEAVES.get().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int maxReach = Math.min(22, box.reach(cx, cz) - 1);
        double radius = 2.4 + random.nextDouble() * 1.1;
        int height = 34 + random.nextInt(20);

        // the trunk: a column that leans gently, wider at the foot
        double tx = cx, tz = cz;
        double driftX = (random.nextDouble() - 0.5) * 0.08, driftZ = (random.nextDouble() - 0.5) * 0.08;
        double[] centerX = new double[height + 1];
        double[] centerZ = new double[height + 1];
        for (int dy = -3; dy <= height; dy++) {
            if (dy >= 0) {
                tx += driftX;
                tz += driftZ;
                centerX[dy] = tx;
                centerZ[dy] = tz;
            }
            double r = radius + (dy < 4 ? (4 - dy) * 0.45 : 0) - (dy > height * 0.8 ? (dy - height * 0.8) * 0.12 : 0);
            disc(box, tx, y0 + dy, tz, r, wood);
        }
        // buttress roots flaring into the ground
        int roots = 5 + random.nextInt(4);
        for (int i = 0; i < roots; i++) {
            double a = (i + random.nextDouble() * 0.6) * Math.PI * 2 / roots;
            int len = 5 + random.nextInt(5);
            for (int s = 0; s < len; s++) {
                double x = cx + Math.cos(a) * (radius + s);
                double z = cz + Math.sin(a) * (radius + s);
                int top = y0 + 3 - s * 4 / len;
                for (int y = y0 - 2; y <= top; y++) box.set(Mth.floor(x), y, Mth.floor(z), wood);
            }
        }
        // walkable branches at several heights, each ending in a crown
        int branches = 5 + random.nextInt(4);
        for (int i = 0; i < branches; i++) {
            int by = (int) (height * (0.4 + 0.5 * i / (double) branches)) + random.nextInt(3);
            by = Math.min(by, height - 2);
            double a = random.nextDouble() * Math.PI * 2;
            // the crown at the tip must fit too (GenBox drops anything outside the write area)
            int len = Math.max(4, Math.min((int) (maxReach - radius - 9), 8 + random.nextInt(9)));
            double bx = centerX[by], bz = centerZ[by];
            double ex = bx, ez = bz;
            double ey = y0 + by;
            Direction.Axis axis = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? Direction.Axis.X : Direction.Axis.Z;
            BlockState log = BiomeBlocks.GRAHT_OAK_LOG.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
            for (int s = 0; s <= len; s++) {
                ex = bx + Math.cos(a) * (radius + s);
                ez = bz + Math.sin(a) * (radius + s);
                ey += s < 3 ? 0.5 : 0.25;
                int x = Mth.floor(ex), y = Mth.floor(ey), z = Mth.floor(ez);
                // a 3-wide deck with a flat top: walkable
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (s > len - 3 && Math.abs(ox) + Math.abs(oz) > 1) continue;
                        box.set(x + ox, y, z + oz, log);
                    }
                }
                box.set(x, y - 1, z, log);
            }
            crown(box, ex, ey + 2, ez, 4 + random.nextInt(2), 3, leaves, random);
        }
        // the top crown
        crown(box, centerX[height], y0 + height + 1, centerZ[height], 6 + random.nextInt(2), 4, leaves, random);
        return true;
    }

    static void disc(GenBox box, double cx, int y, double cz, double r, BlockState state) {
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                double fx = Math.floor(cx) + dx + 0.5 - cx, fz = Math.floor(cz) + dz + 0.5 - cz;
                if (fx * fx + fz * fz <= r * r) box.set(Mth.floor(cx) + dx, y, Mth.floor(cz) + dz, state);
            }
        }
    }

    /** A flattened ellipsoid of leaves with a ragged edge. */
    static void crown(GenBox box, double cx, double cy, double cz, int r, int ry, BlockState leaves, RandomSource random) {
        int x0 = Mth.floor(cx), y0 = Mth.floor(cy), z0 = Mth.floor(cz);
        for (int dy = -ry; dy <= ry; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = (dx * dx + dz * dz) / (double) (r * r) + (dy * dy) / (double) (ry * ry);
                    if (d > 1.0 || (d > 0.7 && random.nextFloat() < 0.35f)) continue;
                    box.setSoft(x0 + dx, y0 + dy, z0 + dz, leaves);
                }
            }
        }
    }
}

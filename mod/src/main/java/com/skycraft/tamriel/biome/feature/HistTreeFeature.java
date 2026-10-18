package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/**
 * The Hist of Black Marsh: an ancient, twisting trunk standing in the mire on arching roots, weeping amber sap, under a
 * drooping umbrella of leaves with hanging strands. Centred on the decorated chunk to fit the 3×3-chunk write area.
 */
public class HistTreeFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public HistTreeFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int cx = box.center.getMiddleBlockX() + random.nextInt(7) - 3;
        int cz = box.center.getMiddleBlockZ() + random.nextInt(7) - 3;
        if (!com.skycraft.world.PlacementMask.clear(ctx.level(), cx, cz, 12)) return false;
        int floor = box.surface(cx, cz);
        BlockState ground = box.get(cx, floor - 1, cz);
        if (!ground.is(BlockTags.SUBSTRATE_OVERWORLD) && !ground.is(Blocks.MUD) && !ground.is(Blocks.CLAY) && !ground.is(BlockTags.SAND)) {
            return false;
        }
        int waterTop = box.surfaceWithWater(cx, cz);
        if (waterTop - floor > 4) return false;   // not in deep water

        BlockState logY = BiomeBlocks.HIST_LOG.get().defaultBlockState();
        BlockState sap = BiomeBlocks.HIST_SAP_BLOCK.get().defaultBlockState();
        BlockState leaves = BiomeBlocks.HIST_LEAVES.get().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int height = 22 + random.nextInt(13);
        double radius = 1.8 + random.nextDouble() * 1.2;
        int reach = Math.min(20, box.reach(cx, cz) - 1);

        // arching roots: the trunk stands on them above the mire
        int roots = 6 + random.nextInt(4);
        for (int i = 0; i < roots; i++) {
            double a = (i + random.nextDouble() * 0.5) * Math.PI * 2 / roots;
            int len = 6 + random.nextInt(5);
            Direction.Axis axis = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? Direction.Axis.X : Direction.Axis.Z;
            BlockState rootLog = logY.setValue(RotatedPillarBlock.AXIS, axis);
            for (int s = 0; s <= len; s++) {
                double t = s / (double) len;
                int x = Mth.floor(cx + 0.5 + Math.cos(a) * (radius + s));
                int z = Mth.floor(cz + 0.5 + Math.sin(a) * (radius + s));
                int y = floor + (int) Math.round(Math.sin(t * Math.PI) * 3 + 3 * (1 - t)) - 1;
                box.set(x, y, z, rootLog);
                box.set(x, y - 1, z, logY);
                if (s == len) for (int d = 1; d <= 3; d++) box.set(x, y - 1 - d, z, logY);
            }
        }
        // the twisting trunk
        double tx = cx + 0.5, tz = cz + 0.5;
        double phase = random.nextDouble() * Math.PI * 2;
        // each Hist coils its own way: how wide and how tight the trunk spirals, how broad the canopy is
        double coil = 0.5 + random.nextDouble() * 1.4, pitch = 0.1 + random.nextDouble() * 0.16;
        int canopyR = 5 + random.nextInt(3);
        double topX = tx, topZ = tz;
        for (int dy = -2; dy < height; dy++) {
            double r = radius + (dy < 5 ? (5 - Math.max(dy, 0)) * 0.3 : 0);
            double ox = Math.cos(phase + dy * pitch) * coil, oz = Math.sin(phase + dy * pitch) * coil;
            topX = tx + ox;
            topZ = tz + oz;
            GrahtOakFeature.disc(box, topX, floor + 3 + dy, topZ, r, logY);
            // weeping sap on the bark
            if (dy > 2 && random.nextInt(5) == 0) {
                double sa = random.nextDouble() * Math.PI * 2;
                box.set(Mth.floor(topX + Math.cos(sa) * (r + 0.3)), floor + 3 + dy, Mth.floor(topZ + Math.sin(sa) * (r + 0.3)), sap);
            }
        }
        int top = floor + 3 + height;
        // a few heavy boughs and the drooping canopy
        int boughs = 3 + random.nextInt(4);
        for (int i = 0; i < boughs; i++) {
            double a = phase + i * Math.PI * 2 / boughs + random.nextDouble() * 0.5;
            int len = Math.min(reach - 8, 5 + random.nextInt(4));
            int by = top - 3 - random.nextInt(4);
            Direction.Axis axis = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? Direction.Axis.X : Direction.Axis.Z;
            BlockState bough = logY.setValue(RotatedPillarBlock.AXIS, axis);
            double ex = topX, ez = topZ;
            for (int s = 0; s <= len; s++) {
                ex = topX + Math.cos(a) * (radius + s);
                ez = topZ + Math.sin(a) * (radius + s);
                box.set(Mth.floor(ex), by + s / 3, Mth.floor(ez), bough);
            }
            canopy(box, ex, by + len / 3 + 1, ez, 5 + random.nextInt(2), leaves, random);
        }
        canopy(box, topX, top + 1, topZ, canopyR, leaves, random);
        return true;
    }

    /** An umbrella of leaves: a dome with hanging strands under its rim. */
    private static void canopy(GenBox box, double cx, int cy, double cz, int r, BlockState leaves, RandomSource random) {
        int x0 = Mth.floor(cx), z0 = Mth.floor(cz);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) / r;
                if (d > 1.0) continue;
                int thick = d < 0.5 ? 3 : d < 0.85 ? 2 : 1;
                int dome = (int) Math.round((1 - d * d) * 2);
                for (int t = 0; t < thick; t++) box.setSoft(x0 + dx, cy + dome - t, z0 + dz, leaves);
                if (d > 0.6 && random.nextInt(4) == 0) {
                    int hang = 1 + random.nextInt(4);
                    for (int h = 1; h <= hang; h++) box.setSoft(x0 + dx, cy + dome - thick - h + 1, z0 + dz, leaves);
                }
            }
        }
    }
}

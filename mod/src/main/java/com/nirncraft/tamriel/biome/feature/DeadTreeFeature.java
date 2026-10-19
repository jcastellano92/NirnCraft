package com.nirncraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.BlockStateConfiguration;

/**
 * Dead, leafless trees: Hjaalmarch's drowned snags, the charred trunks of the ashlands, bleached acacias in the desert.
 * A bare trunk with a few crooked, sometimes broken, branches.
 */
public class DeadTreeFeature extends ConfiguredFeature<BlockStateConfiguration> {
    public DeadTreeFeature(Codec<BlockStateConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<BlockStateConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        BlockPos o = ctx.origin();
        int x = o.getX(), y = o.getY(), z = o.getZ();
        BlockState below = box.get(x, y - 1, z);
        if (!below.isFaceSturdy(ctx.level(), o.below(), Direction.UP) || box.reach(x, z) < 5) return false;
        if (!com.nirncraft.world.PlacementMask.clear(ctx.level(), x, z, 3)) return false;
        BlockState log = ctx.config().state;
        if (!log.hasProperty(RotatedPillarBlock.AXIS)) return false;
        int height = 4 + random.nextInt(6);
        int tx = x, tz = z;
        for (int dy = 0; dy < height; dy++) {
            if (dy > 2 && random.nextInt(6) == 0) {
                tx += random.nextInt(3) - 1;
                tz += random.nextInt(3) - 1;
            }
            if (dy > 0 && !GenBox.isSoft(box.get(tx, y + dy, tz))) break;
            box.set(tx, y + dy, tz, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        }
        int branches = 1 + random.nextInt(4);
        for (int i = 0; i < branches; i++) {
            Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            int by = y + 2 + random.nextInt(Math.max(1, height - 2));
            int len = 1 + random.nextInt(3);
            int bx = tx, bz = tz;
            for (int s = 1; s <= len; s++) {
                bx += dir.getStepX();
                bz += dir.getStepZ();
                int yy = by + (s > 1 && random.nextBoolean() ? 1 : 0);
                by = yy;
                box.setSoft(bx, yy, bz, log.setValue(RotatedPillarBlock.AXIS, dir.getAxis()));
            }
            if (random.nextBoolean()) box.setSoft(bx, by + 1, bz, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        }
        return true;
    }
}

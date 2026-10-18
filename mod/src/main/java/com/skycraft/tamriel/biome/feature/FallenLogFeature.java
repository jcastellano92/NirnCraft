package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.BlockStateConfiguration;

/** A fallen trunk lying on the forest floor, a stump beside it, moss on its back. */
public class FallenLogFeature extends ConfiguredFeature<BlockStateConfiguration> {
    public FallenLogFeature(Codec<BlockStateConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<BlockStateConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        BlockPos o = ctx.origin();
        BlockState log = ctx.config().state;
        if (!log.hasProperty(RotatedPillarBlock.AXIS)) return false;
        Direction dir = random.nextBoolean() ? Direction.EAST : Direction.SOUTH;
        int len = 4 + random.nextInt(4);
        int x = o.getX(), y = o.getY(), z = o.getZ();
        if (box.reach(x, z) < len + 2) return false;
        if (!com.skycraft.world.PlacementMask.clear(ctx.level(), x, z, 5)) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // every block of the log needs air to lie in and ground under it
        for (int s = 0; s < len; s++) {
            int lx = x + dir.getStepX() * (s + 2), lz = z + dir.getStepZ() * (s + 2);
            BlockState here = box.get(lx, y, lz);
            BlockState under = box.get(lx, y - 1, lz);
            if (!GenBox.isSoft(here) || !here.getFluidState().isEmpty()) return false;
            if (!under.isFaceSturdy(ctx.level(), p.set(lx, y - 1, lz), Direction.UP)) return false;
        }
        BlockState below = box.get(x, y - 1, z);
        if (below.isFaceSturdy(ctx.level(), p.set(x, y - 1, z), Direction.UP) && GenBox.isSoft(box.get(x, y, z))) {
            box.set(x, y, z, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));     // the stump
        }
        BlockState lying = log.setValue(RotatedPillarBlock.AXIS, dir.getAxis());
        for (int s = 0; s < len; s++) {
            int lx = x + dir.getStepX() * (s + 2), lz = z + dir.getStepZ() * (s + 2);
            box.set(lx, y, lz, lying);
            if (random.nextInt(3) == 0 && box.get(lx, y + 1, lz).isAir()) {
                box.set(lx, y + 1, lz, Blocks.MOSS_CARPET.defaultBlockState());
            }
        }
        return true;
    }
}

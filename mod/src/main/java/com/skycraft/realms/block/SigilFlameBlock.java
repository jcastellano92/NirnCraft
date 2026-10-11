package com.skycraft.realms.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blood-fire updraft in the shaft of a Great Sigil Tower: a column of it lifts whoever steps in up to the Sigil
 * chamber (like a bubble column, but upwards through air) and catches falls on the way down. It doesn't burn.
 */
public class SigilFlameBlock extends Block {
    public SigilFlameBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        boolean top = !level.getBlockState(pos.above()).is(this);
        Vec3 v = entity.getDeltaMovement();
        if (entity.isShiftKeyDown()) {
            // sneaking lets you sink slowly
            entity.setDeltaMovement(v.x, Math.max(v.y, -0.15), v.z);
        } else if (top) {
            entity.setDeltaMovement(v.x, Math.max(v.y, 0.25), v.z);
        } else {
            entity.setDeltaMovement(v.x * 0.9, Math.min(0.75, Math.max(v.y, 0.0) + 0.12), v.z * 0.9);
        }
        entity.resetFallDistance();
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.FLAME, pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(),
                    pos.getZ() + random.nextDouble(), 0, 0.15, 0);
        }
        if (random.nextInt(5) == 0) {
            level.addParticle(ParticleTypes.CRIMSON_SPORE, pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(),
                    pos.getZ() + random.nextDouble(), 0, 0.2, 0);
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }
}

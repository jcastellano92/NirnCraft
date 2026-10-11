package com.skycraft.realms.block;

import com.skycraft.realms.gate.OblivionGates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Sigil Stone at the top of a Great Sigil Tower, burning in its pedestal of flame. Taking it ({@code use}) closes
 * the Oblivion gate that leads to this tower ({@link OblivionGates#takeSigilStone}): the gate in Nirn collapses, the
 * stone becomes the taker's, and everyone on the island is thrown back home.
 */
public class SigilStoneBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(3, 2, 3, 13, 14, 13);

    public SigilStoneBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) OblivionGates.takeSigilStone(sp, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        for (int i = 0; i < 3; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            level.addParticle(ParticleTypes.FLAME, x + Math.cos(a) * 0.7, y - 0.4 + random.nextDouble() * 0.4,
                    z + Math.sin(a) * 0.7, -Math.cos(a) * 0.02, 0.04, -Math.sin(a) * 0.02);
        }
        if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.LAVA, x, y, z, 0, 0, 0);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }
}

package com.skycraft.survival.block;

import com.skycraft.survival.cooking.CookingMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Skyrim's cooking pot. Works anywhere (best hung over a campfire, where it bubbles). Right-click opens the cooking
 * menu; cooking gives no skill experience, as in Skyrim.
 */
public class CookingPotBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 10, 14);

    public CookingPotBlock(Properties props) {
        super(props);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return useItemOn(net.minecraft.world.item.ItemStack.EMPTY, state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useItemOn(net.minecraft.world.item.ItemStack held263, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            if (com.skycraft.crime.Ownership.isOwnedByOther(sp, level, pos) && !com.skycraft.economy.Shop.isOpen(level)) {
                if (com.skycraft.survival.inn.Innkeepers.roomHere(sp) == null) {
                    com.skycraft.core.Notifier.message(sp, net.minecraft.network.chat.Component.translatable("dialogue.skycraft.economy.closed"));
                    level.playSound(null, pos, net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
                    return InteractionResult.CONSUME;
                }
            }
            CookingMenu.open(sp, pos);
        }
        return InteractionResult.CONSUME;
    }

    /** True if the pot hangs over a lit campfire (or any fire). */
    public static boolean heated(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return CampfireBlock.isLitCampfire(below) || below.is(net.minecraft.tags.BlockTags.FIRE);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!heated(level, pos)) return;
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.62;
        double z = pos.getZ() + 0.5;
        if (random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.BUBBLE_POP, x + (random.nextDouble() - 0.5) * 0.5, y, z + (random.nextDouble() - 0.5) * 0.5, 0, 0.02, 0);
        }
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x + (random.nextDouble() - 0.5) * 0.4, y + 0.1, z + (random.nextDouble() - 0.5) * 0.4, 0, 0.04, 0);
        }
    }
}

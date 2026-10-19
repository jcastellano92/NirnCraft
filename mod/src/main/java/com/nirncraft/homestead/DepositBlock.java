package com.nirncraft.homestead;

import com.nirncraft.core.Notifier;
import com.nirncraft.vitals.ActionHandler;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Hearthfire's quarried stone and clay deposits: endless, unbreakable sources of building material next to a
 * purchased plot. Use them with a pickaxe (stone) or a shovel or pickaxe (clay); each strike costs a little stamina.
 */
public class DepositBlock extends Block {
    public enum Kind { STONE, CLAY }

    private final Kind kind;

    public DepositBlock(Kind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    @SuppressWarnings("deprecation")
        @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack tool = player.getItemInHand(hand);
        boolean pick = tool.is(net.minecraft.tags.ItemTags.PICKAXES);
        boolean shovel = tool.is(net.minecraft.tags.ItemTags.SHOVELS);
        if (!(pick || kind == Kind.CLAY && shovel)) {
            if (!level.isClientSide() && player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND) {
                Notifier.message(sp, Component.translatable("homestead.nirncraft.deposit.tool." + kind.name().toLowerCase(java.util.Locale.ROOT)));
            }
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.CONSUME;
        if (sp.getCooldowns().isOnCooldown(tool)) return InteractionResult.CONSUME;
        if (!Vitals.consumeStamina(sp, 6f, false)) {
            Notifier.message(sp, Component.translatable("homestead.nirncraft.deposit.tired"));
            return InteractionResult.CONSUME;
        }
        sp.getCooldowns().addCooldown(tool, 12);
        sp.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        ItemStack out = kind == Kind.STONE ? new ItemStack(HomesteadRegistry.QUARRIED_STONE.get()) : new ItemStack(Items.CLAY_BALL, 2);
        if (!ActionHandler.addToBags(sp, out)) sp.drop(out, false, net.minecraft.util.Prediction.SERVER_ONLY);
        tool.hurtAndBreak(1, sp, hand);
        level.playSound(null, pos, kind == Kind.STONE ? SoundEvents.STONE_BREAK : SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.0f, 0.9f);
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    8, 0.3, 0.1, 0.3, 0.05);
        }
        return InteractionResult.CONSUME;
    }
}

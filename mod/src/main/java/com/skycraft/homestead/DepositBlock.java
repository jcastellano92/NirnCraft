package com.skycraft.homestead;

import com.skycraft.core.Notifier;
import com.skycraft.vitals.ActionHandler;
import com.skycraft.vitals.Vitals;
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
import net.minecraftforge.common.ToolActions;

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

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack tool = player.getItemInHand(hand);
        boolean pick = tool.canPerformAction(ToolActions.PICKAXE_DIG);
        boolean shovel = tool.canPerformAction(ToolActions.SHOVEL_DIG);
        if (!(pick || kind == Kind.CLAY && shovel)) {
            if (!level.isClientSide && player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND) {
                Notifier.message(sp, Component.translatable("homestead.skycraft.deposit.tool." + kind.name().toLowerCase(java.util.Locale.ROOT)));
            }
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.CONSUME;
        if (sp.getCooldowns().isOnCooldown(tool.getItem())) return InteractionResult.CONSUME;
        if (!Vitals.consumeStamina(sp, 6f, false)) {
            Notifier.message(sp, Component.translatable("homestead.skycraft.deposit.tired"));
            return InteractionResult.CONSUME;
        }
        sp.getCooldowns().addCooldown(tool.getItem(), 12);
        sp.swing(hand, true);
        ItemStack out = kind == Kind.STONE ? new ItemStack(HomesteadRegistry.QUARRIED_STONE.get()) : new ItemStack(Items.CLAY_BALL, 2);
        if (!ActionHandler.addToBags(sp, out)) sp.drop(out, false);
        tool.hurtAndBreak(1, sp, p -> p.broadcastBreakEvent(hand));
        level.playSound(null, pos, kind == Kind.STONE ? SoundEvents.STONE_BREAK : SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.0f, 0.9f);
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    8, 0.3, 0.1, 0.3, 0.05);
        }
        return InteractionResult.CONSUME;
    }
}

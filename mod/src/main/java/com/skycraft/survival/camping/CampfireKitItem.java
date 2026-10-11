package com.skycraft.survival.camping;

import com.skycraft.core.Notifier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Firewood, kindling and a flint: builds a burning campfire where you use it. A campfire warms you (Survival Mode
 * cold), cooks food and, with a cooking pot hung over it, makes stews.
 */
public class CampfireKitItem extends Item {
    public CampfireKitItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        BlockPos clicked = ctx.getClickedPos();
        BlockPos pos = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(ctx.getClickedFace());
        boolean ok = level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
        if (!ok) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("survival.skycraft.camping.no_room"));
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Direction facing = player == null ? Direction.NORTH : player.getDirection().getOpposite();
        BlockState fire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING, facing).setValue(CampfireBlock.LIT, true);
        level.setBlock(pos, fire, 11);
        level.gameEvent(player, GameEvent.BLOCK_PLACE, pos);
        level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 0.9f);
        level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
        if (player == null || !player.isCreative()) ctx.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.skycraft.survival.campfire_kit").withStyle(ChatFormatting.GRAY));
    }
}

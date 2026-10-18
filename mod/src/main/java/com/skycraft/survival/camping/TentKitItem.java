package com.skycraft.survival.camping;

import com.skycraft.core.Notifier;
import com.skycraft.inventory.Inv;
import com.skycraft.survival.SurvivalRegistry;
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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A rolled-up hide tent. Used on the ground it pitches a 3x3 tent with its entrance toward you; if you carry a bedroll
 * it is laid out inside. Breaking the tent gives the kit back (the bedroll drops on its own).
 */
public class TentKitItem extends Item {
    public TentKitItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        BlockPos clicked = ctx.getClickedPos();
        BlockPos origin = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.above();
        Direction facing = player.getDirection().getOpposite();
        if (!canPitch(level, origin, facing)) {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                Notifier.message(sp, Component.translatable("survival.skycraft.camping.no_room"));
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        for (TentBlock.Part part : TentBlock.Part.values()) {
            BlockPos partPos = TentBlock.partPos(origin, facing, part);
            level.setBlock(partPos, SurvivalRegistry.TENT.get().defaultBlockState()
                    .setValue(TentBlock.FACING, facing).setValue(TentBlock.PART, part), 3);
            com.skycraft.dig.PlacedBlocks.mark(level, partPos, true); // yours: you can break camp anywhere (world protection)
        }
        // lay out a carried bedroll, head toward the back
        if (Inv.has(player, SurvivalRegistry.BEDROLL_ITEM.get()) || player.isCreative()) {
            Direction in = facing.getOpposite();
            BlockState foot = SurvivalRegistry.BEDROLL.get().defaultBlockState().setValue(BedBlock.FACING, in).setValue(BedBlock.PART, BedPart.FOOT);
            level.setBlock(origin, foot, 3);
            level.setBlock(origin.relative(in), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
            com.skycraft.dig.PlacedBlocks.mark(level, origin, true);
            com.skycraft.dig.PlacedBlocks.mark(level, origin.relative(in), true);
            if (!player.isCreative()) Inv.take(player, SurvivalRegistry.BEDROLL_ITEM.get(), 1);
        }
        level.playSound(null, origin, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f);
        level.playSound(null, origin, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.BLOCKS, 1.0f, 0.9f);
        if (!player.isCreative()) ctx.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }

    /** Every tent block and the bedroll spots are free, with something solid to stand on. */
    public static boolean canPitch(Level level, BlockPos origin, Direction facing) {
        Direction in = facing.getOpposite();
        for (int side = -1; side <= 1; side++) {
            for (int depth = 0; depth <= 2; depth++) {
                BlockPos p = origin.relative(in, depth).relative(in.getClockWise(), side);
                for (int up = 0; up <= 1; up++) {
                    if (!level.getBlockState(p.above(up)).canBeReplaced()) return false;
                }
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return false;
            }
        }
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.skycraft.survival.tent_kit").withStyle(ChatFormatting.GRAY));
    }
}

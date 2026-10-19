package com.nirncraft.guide;

import com.nirncraft.guide.client.GuideClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code nirncraft:travelers_handbook}: the in-game guide. Its pages are client lang text, so using it simply opens the
 * reading screen on the client; the server has nothing to do.
 */
public class HandbookItem extends Item {
    public HandbookItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            Sides.runOn(Dist.CLIENT, () -> () -> GuideClient.openHandbook());
        } else {
            level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, player.getSoundSource(), 1f, 1f);
        }
        return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.nirncraft.guide.handbook").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}

package com.skycraft.dungeons.puzzle;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A dragon claw: the key of a Nordic claw door. Its palm shows the ring combination, top to bottom. */
public class DragonClawItem extends Item {
    public final Claw claw;

    public DragonClawItem(Claw claw, Properties props) {
        super(props);
        this.claw = claw;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.skycraft.dragon_claw.palm",
                claw.symbol(0).displayName(), claw.symbol(1).displayName(), claw.symbol(2).displayName())
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.skycraft.dragon_claw.hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}

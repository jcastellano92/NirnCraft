package com.skycraft.arsenal.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The Wabbajack and the Staff of Magnus: artifact staves. */
public class ArtifactStaffItem extends StaffItem implements ArtifactItem {
    public ArtifactStaffItem(StaffKind kind, Properties props) {
        super(kind, props);
    }

    @Override
    public String artifactId() {
        return kind.id;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context263, display263, tooltip, flag);
        tooltip.accept(Component.translatable("lore.skycraft.artifact." + kind.id).withStyle(net.minecraft.ChatFormatting.DARK_GRAY,
                net.minecraft.ChatFormatting.ITALIC));
    }
}

package com.skycraft.arsenal.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Spellbreaker, Peryite's Dwemer shield: while blocking, a ward stops incoming spells (see ArtifactEvents). */
public class SpellbreakerItem extends ShieldItem implements ArtifactItem {
    public static final String ID = "spellbreaker";

    public SpellbreakerItem(Properties props) {
        super(com.skycraft.platform.ShieldProperties.apply(props).repairable(net.minecraft.tags.ItemTags.WOODEN_TOOL_MATERIALS));
    }

    @Override
    public String artifactId() {
        return ID;
    }


    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public boolean isPrimaryItemFor(ItemStack stack, net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> enchantment) { return false; }

    @Override
    public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> enchantment) { return false; }


    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        ArtifactItem.tooltip(ID, tooltip);
    }
}

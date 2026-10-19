package com.nirncraft.arsenal.item;

import com.nirncraft.crafting.SmithingTier;
import com.nirncraft.crafting.WeaponType;
import com.nirncraft.crafting.item.SkyAxeItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A named artifact war axe or battleaxe (Wuuthrad). */
public class ArtifactAxeItem extends SkyAxeItem implements ArtifactItem {
    private final String id;

    public ArtifactAxeItem(String id, SmithingTier tier, WeaponType type, Properties props) {
        super(tier, type, props, ArtifactItem.DURABILITY);
        this.id = id;
    }

    @Override
    public String artifactId() {
        return id;
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
        ArtifactItem.tooltip(id, tooltip);
    }
}

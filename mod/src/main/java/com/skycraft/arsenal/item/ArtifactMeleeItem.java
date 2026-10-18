package com.skycraft.arsenal.item;

import com.skycraft.crafting.SmithingTier;
import com.skycraft.crafting.WeaponType;
import com.skycraft.crafting.item.SkyMeleeItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A named artifact sword, dagger, mace, greatsword or warhammer (tempered like its base material). */
public class ArtifactMeleeItem extends SkyMeleeItem implements ArtifactItem {
    private final String id;

    public ArtifactMeleeItem(String id, SmithingTier tier, WeaponType type, Properties props) {
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

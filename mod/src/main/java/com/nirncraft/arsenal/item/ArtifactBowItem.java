package com.nirncraft.arsenal.item;

import com.nirncraft.crafting.SmithingTier;
import com.nirncraft.crafting.item.SkyBowItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A named artifact bow (Auriel's Bow, Nightingale Bow, Zephyr, Bow of Shadows). Arrows it shoots carry the
 * artifact id in their persistent data ({@link #ARROW_TAG}) so the arsenal events can apply its power on impact.
 */
public class ArtifactBowItem extends SkyBowItem implements ArtifactItem {
    public static final String ARROW_TAG = "nirncraft_artifact";

    private final String id;

    public ArtifactBowItem(String id, SmithingTier tier, Properties props) {
        super(tier, props);
        this.id = id;
    }

    @Override
    public String artifactId() {
        return id;
    }

    @Override
    public AbstractArrow customArrow(AbstractArrow arrow, ItemStack projectileStack, ItemStack weaponStack) {
        AbstractArrow result = super.customArrow(arrow, projectileStack, weaponStack);
        result.getPersistentData().putString(ARROW_TAG, id);
        return result;
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

package com.nirncraft.crafting.item;

import com.nirncraft.crafting.SmithingTier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Skyrim bows (Long Bow, Hunting Bow, Orcish ... Dragonbone). Better bows shoot harder arrows: the arrow's base damage
 * is raised to the tier's {@link SmithingTier#bowDamage} (vanilla: 2.0), on top of any special arrow bonus.
 */
public class SkyBowItem extends BowItem {
    public final SmithingTier smithingTier;

    public SkyBowItem(SmithingTier tier, Properties props) {
        super(props.enchantable(tier.getEnchantmentValue()).repairable(tier.material()));
        this.smithingTier = tier;
    }

    @Override
    public AbstractArrow customArrow(AbstractArrow arrow, ItemStack projectileStack, ItemStack weaponStack) {
        arrow.setBaseDamage(com.nirncraft.platform.EntityData.arrowDamage(arrow) + (smithingTier.bowDamage - 2.0));
        return arrow;
    }

}

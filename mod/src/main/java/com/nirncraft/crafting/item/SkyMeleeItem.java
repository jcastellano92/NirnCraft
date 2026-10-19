package com.nirncraft.crafting.item;

import com.nirncraft.crafting.SmithingTier;
import com.nirncraft.crafting.WeaponType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Daggers, swords, greatswords, maces and warhammers. Maces, warhammers and daggers don't sweep like swords do.
 */
public class SkyMeleeItem extends Item {
    public final SmithingTier smithingTier;
    public final WeaponType type;

    public SkyMeleeItem(SmithingTier tier, WeaponType type, Properties props) {
        this(tier, type, props, Math.max(1, Math.round(tier.getUses() * type.durabilityMult)));
    }

    protected SkyMeleeItem(SmithingTier tier, WeaponType type, Properties props, int durability) {
        super(props.sword(tier.vanilla(), type.damage, type.speed).repairable(tier.material())
                .durability(durability));
        this.smithingTier = tier;
        this.type = type;
    }

    @Override
    public boolean canPerformAction(net.minecraft.world.item.ItemInstance stack, ItemAbility toolAction) {
        if (toolAction == ItemAbilities.SWORD_SWEEP) return type == WeaponType.SWORD || type == WeaponType.GREATSWORD;
        return super.canPerformAction(stack, toolAction);
    }
}

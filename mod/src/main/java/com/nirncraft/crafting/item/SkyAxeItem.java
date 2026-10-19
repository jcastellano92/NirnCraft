package com.nirncraft.crafting.item;

import com.nirncraft.crafting.SmithingTier;
import com.nirncraft.crafting.WeaponType;
import net.minecraft.world.item.Item;

/** War axes and battleaxes: real axes, so they still chop wood. */
public class SkyAxeItem extends Item {
    public final SmithingTier smithingTier;
    public final WeaponType type;

    public SkyAxeItem(SmithingTier tier, WeaponType type, Properties props) {
        this(tier, type, props, Math.max(1, Math.round(tier.getUses() * type.durabilityMult)));
    }

    protected SkyAxeItem(SmithingTier tier, WeaponType type, Properties props, int durability) {
        super(props.axe(tier.vanilla(), type.damage, type.speed).repairable(tier.material())
                .durability(durability));
        this.smithingTier = tier;
        this.type = type;
    }
}

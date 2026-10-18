package com.skycraft.platform;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** A scoped consuming item for virtual inventory use, matching vanilla's getUseItem context. */
public final class ItemUse {
    private record Consumption(LivingEntity entity, ItemStack item) {}
    private static final ThreadLocal<Consumption> ACTIVE = new ThreadLocal<>();
    private ItemUse() {}
    public static ItemStack consuming(LivingEntity entity) {
        Consumption active = ACTIVE.get();
        return active != null && active.entity == entity ? active.item : entity.getUseItem();
    }
    public static ItemStack finish(ItemStack item, LivingEntity user) {
        Consumption previous = ACTIVE.get();
        ACTIVE.set(new Consumption(user, item.copy()));
        try { return item.finishUsingItem(user.level(), user); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
}

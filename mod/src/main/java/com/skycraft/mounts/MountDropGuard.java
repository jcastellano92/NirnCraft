package com.skycraft.mounts;

import com.skycraft.Skycraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SaddleItem;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A mount's tack is mirrored into the vanilla saddle/armour slots so the animal renders and behaves as saddled; when an
 * owned mount really dies (an explicit kill) those mirrors must not drop as extra items: the real gear is remembered
 * in the owner's record and comes back with the mount.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class MountDropGuard {
    private MountDropGuard() {}

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Player || !event.getEntity().getPersistentData().contains(Mounts.KIND)) return;
        event.getDrops().removeIf(drop -> isTack((ItemEntity) drop));
    }

    private static boolean isTack(ItemEntity drop) {
        var stack = drop.getItem();
        return stack.is(Items.SADDLE) || stack.getItem() instanceof SaddleItem || stack.getItem() instanceof HorseArmorItem
                || SaddleType.of(stack) != null;
    }
}

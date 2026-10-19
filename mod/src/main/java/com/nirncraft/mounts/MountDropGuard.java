package com.nirncraft.mounts;

import com.nirncraft.NirnCraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * A mount's tack is mirrored into the vanilla saddle/armour slots so the animal renders and behaves as saddled; when an
 * owned mount really dies (an explicit kill) those mirrors must not drop as extra items: the real gear is remembered
 * in the owner's record and comes back with the mount.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class MountDropGuard {
    private MountDropGuard() {}

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Player || !event.getEntity().getPersistentData().contains(Mounts.KIND)) return;
        event.getDrops().removeIf(drop -> isTack((ItemEntity) drop));
    }

    private static boolean isTack(ItemEntity drop) {
        var stack = drop.getItem();
        return stack.is(Items.SADDLE) || MountItems.isHorseArmor(stack)
                || SaddleType.of(stack) != null;
    }
}

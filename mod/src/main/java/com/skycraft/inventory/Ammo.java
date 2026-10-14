package com.skycraft.inventory;

import com.skycraft.Skycraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Arrows (and bolts, and anything else a bow or crossbow takes) that sit only in the overflow {@link Pack} are found by
 * vanilla's {@code Player#getProjectile}, which reads the 36 slots and nothing else. Vanilla code cannot be redirected
 * (no mixins), so the pack's ammo is brought into a slot at the moments it is needed:
 * <ul>
 *     <li>the instant a ranged weapon is used ({@code RightClickItem}, before {@code BowItem.use} counts the ammo);</li>
 *     <li>every 5 ticks while a ranged weapon is in hand, so the client's own prediction sees the ammo too.</li>
 * </ul>
 * One stack is materialised ({@link Pack#materialize}); the pack keeps the rest and refills it as ammo is shot.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Ammo {
    private Ammo() {}

    /** True when {@code weapon} is a ranged weapon the player has no ammo for in the slots but the pack has. */
    static boolean ensure(ServerPlayer player, ItemStack weapon) {
        if (!(weapon.getItem() instanceof ProjectileWeaponItem ranged) || player.isCreative()) return false;
        if (!player.getProjectile(weapon).isEmpty()) return false;
        int idx = Pack.find(player, s -> ranged.getAllSupportedProjectiles().test(s));
        if (idx < 0) return false;
        return Pack.materialize(player, idx, null, player.getInventory().selected) >= 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() == InteractionHand.MAIN_HAND || event.getHand() == InteractionHand.OFF_HAND) ensure(player, event.getItemStack());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 5 != 0 || Pack.size(player) == 0) return;
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof ProjectileWeaponItem) ensure(player, main);
        else if (player.getOffhandItem().getItem() instanceof ProjectileWeaponItem) ensure(player, player.getOffhandItem());
    }
}

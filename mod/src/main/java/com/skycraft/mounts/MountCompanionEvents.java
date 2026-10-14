package com.skycraft.mounts;

import com.skycraft.Skycraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus glue of {@link MountCompanion}: an owned mount that would die lies down instead, a downed mount is not a
 * target and takes nothing, nobody mounts it, the companion goals are added when a mount loads, and the state is
 * resolved at logout and load. Handlers run before the other mount and fauna handlers so that a death is turned into
 * going down before anything reacts to it.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class MountCompanionEvents {
    private MountCompanionEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        MountCompanion.tick(player);
    }

    /** A blow on a downed mount does nothing (aimed or area); only an explicit kill gets through. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !MountCompanion.isDowned(victim)) return;
        if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !MountCompanion.isDowned(victim)) return;
        if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) return;
        event.setCanceled(true);
    }

    /** The damage that would be the end: cancel it and lay the mount down with a sliver of health. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !(victim instanceof Mob mob) || !MountCompanion.mayDown(mob, event.getSource())) return;
        if (event.getAmount() < mob.getHealth()) return;
        event.setCanceled(true);
        MountCompanion.down(mob, mob.level().getGameTime(), MountCompanion.owner(mob));
    }

    /** Any other death of an owned mount (a death called directly, a mod's lethal effect) goes the same way. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !(victim instanceof Mob mob)) return;
        if (MountCompanion.isDowned(mob) && !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) {
            event.setCanceled(true); // already lying down: a second death call changes nothing
            return;
        }
        if (!MountCompanion.mayDown(mob, event.getSource())) return;
        event.setCanceled(true);
        MountCompanion.down(mob, mob.level().getGameTime(), MountCompanion.owner(mob));
    }

    /** Nothing takes a downed mount as its target, and a downed mob takes none. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity().level().isClientSide) return;
        LivingEntity next = event.getNewTarget();
        if (next != null && MountCompanion.isDowned(next) || next != null && MountCompanion.isDowned(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** A downed mount cannot be ridden, and neither can one that has only just got up. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting() || event.getLevel().isClientSide()) return;
        if (event.getEntityBeingMounted() instanceof Mob mount && MountCompanion.isCompanion(mount)
                && MountCompanion.denyMount(event.getEntityMounting(), mount)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || Mounts.kindOf(mob) == null) return;
        MountCompanion.addGoals(mob);
        if (event.loadedFromDisk() && MountCompanion.isDowned(mob)) MountCompanion.resolveLoaded(mob, MountCompanion.owner(mob));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) MountCompanion.onLogout(player);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (event.getEntity() instanceof ServerPlayer viewer && target instanceof Mob) MountCompanion.syncTo(viewer, target);
    }
}

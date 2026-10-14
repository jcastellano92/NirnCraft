package com.skycraft.mounts;

import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import com.skycraft.creatures.spec.Attacks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Fighting from the saddle. Works with any swing that ends in a vanilla attack (Better Combat's directional combos
 * included, its own "don't hit your mount" rule kept): your blows never land on your own mount, a strike at speed
 * carries the charge (up to +50% damage, Skyrim's mounted power attacks), and a fighting beast (senche-tiger, giant
 * wolf, mammoth, Akaviri tiger, grazer) lashes out at whatever its rider hits. A war saddle adds to the blow
 * ({@link SaddleType#WAR}). An unridden mount defends its owner.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class MountedCombat {
    private MountedCombat() {}

    /** Your own mount (ridden or not) is not a target. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        Entity src = event.getSource().getEntity();
        LivingEntity victim = event.getEntity();
        if (!(src instanceof Player p) || victim.level().isClientSide) return;
        if (p.getVehicle() == victim || Mounts.kindOf(victim) != null && Mounts.isOwner(p, victim) && !p.isCreative()) {
            event.setCanceled(true);
            // a swing at your own animal that you are not sitting on: it does not land, and it is not forgotten
            if (p.getVehicle() != victim && p instanceof ServerPlayer sp) MountGrowth.abuse(sp, victim);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        Entity src = event.getSource().getEntity();
        if (src instanceof ServerPlayer rider && event.getSource().getDirectEntity() == rider
                && rider.getVehicle() instanceof LivingEntity mount && Mounts.kindOf(mount) != null) {
            // the charge: the mount's speed behind the blow
            float bonus = (float) Math.min(0.5, MountStamina.speed(rider) * 1.1);
            // a war saddle braces the blow (the charge still counts)
            bonus += MountGear.damageBonus(mount);
            bonus += MountGrowth.trickBlow(rider); // Trick Rider
            MountGrowth.struck(rider, mount, false, 0f); // a blow from the saddle is training
            if (bonus > 0.05f) event.setAmount(event.getAmount() * (1f + bonus));
            // a fighting beast strikes too
            if (mount instanceof MountBeast beast && beast.distanceTo(victim) < beast.getBbWidth() + 2.5f && beast.readyToAssist()) {
                CreatureAnims.play(beast, AnimSlot.ATTACK);
                Attacks.strike(beast, victim, 0.8f);
            }
        }
        // a blow the ridden mount takes trains its Health
        if (victim.getControllingPassenger() instanceof ServerPlayer rider && Mounts.kindOf(victim) != null && src != rider) {
            MountGrowth.struck(rider, victim, true, event.getAmount());
        }
        if (victim instanceof ServerPlayer owner && src instanceof LivingEntity attacker && attacker != owner) {
            // an unridden mount comes to its owner's aid
            Entity e = Mounts.activeEntity(owner);
            if (e instanceof MountBeast beast && !beast.isVehicle() && !Mounts.isFriend(beast, attacker)
                    && beast.distanceToSqr(owner) < 20 * 20 && !beast.spec().attacks.isEmpty()) {
                beast.setTarget(attacker);
            }
        }
        if (src instanceof Mob m && victim instanceof MountBeast beast && !beast.isVehicle() && beast.getTarget() == null) {
            beast.setTarget(m);
        }
    }
}

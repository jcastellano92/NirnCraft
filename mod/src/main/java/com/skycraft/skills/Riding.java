package com.skycraft.skills;

import com.skycraft.Skycraft;
import com.skycraft.combat.CombatHandler;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.perk.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Riding: horseback travel and mounted combat. The skill makes horses a little faster; perks: Horseman (speed),
 * Stable Hand (your horse takes less damage), Mounted Combat (melee from the saddle), Endurance Rider (higher jumps),
 * Horse Archer (archery from the saddle), Trample (a galloping horse knocks down small foes), Bond (your horse heals
 * while you ride it) and Cavalry Charge (galloping blows stagger and hit much harder).
 *
 * <p>XP: distance ridden (one use per 16 blocks of real travel) and kills from the saddle. The speed and jump bonuses
 * are transient attribute modifiers on the horse, only while you ride it.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Riding {
    private static final UUID SPEED_ID = UUID.fromString("2f8f9a3e-7d41-4c55-9a8e-5b1c2d3e4f60");
    private static final UUID JUMP_ID = UUID.fromString("2f8f9a3e-7d41-4c55-9a8e-5b1c2d3e4f61");
    /** Distance ridden since the last XP award, per player. */
    private static final Map<UUID, Double> RIDDEN = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

    private Riding() {}

    private static AbstractHorse mount(Player p) {
        return p.getVehicle() instanceof AbstractHorse horse ? horse : null;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer p)) return;
        AbstractHorse horse = mount(p);
        if (horse == null) {
            LAST_POS.remove(p.getUUID());
            return;
        }
        PlayerData data = SkyData.get(p);
        if (p.tickCount % 20 == 0) {
            applyModifiers(horse, data);
            if (Perks.has(data, "riding.bond") && horse.getHealth() < horse.getMaxHealth()) horse.heal(0.5f);
        }
        // XP for real travel (not walking in circles in a pen: positions are sampled once a second)
        if (p.tickCount % 20 == 0) {
            Vec3 now = horse.position();
            Vec3 last = LAST_POS.put(p.getUUID(), now);
            if (last != null) {
                double d = Math.min(30, now.distanceTo(last));
                double total = RIDDEN.getOrDefault(p.getUUID(), 0.0) + (d > 2 ? d : 0);
                while (total >= 16) {
                    total -= 16;
                    Progression.addSkillXp(p, Skill.RIDING, 1f);
                }
                RIDDEN.put(p.getUUID(), total);
            }
        }
        // Trample: a galloping horse bowls over small creatures in its way.
        if (p.tickCount % 5 == 0 && Perks.has(data, "riding.trample") && horse.getDeltaMovement().horizontalDistanceSqr() > 0.09) {
            for (LivingEntity e : horse.level().getEntitiesOfClass(LivingEntity.class, horse.getBoundingBox().inflate(0.4),
                    e -> e != horse && e != p && e.isAlive() && e.getBbHeight() < 1.6f && com.skycraft.magic.Targeting.canHarm(p, e))) {
                Vec3 push = horse.getDeltaMovement().multiply(1, 0, 1).normalize().scale(0.9);
                e.hurt(horse.damageSources().mobAttack(horse), 4f);
                e.push(push.x, 0.35, push.z);
                e.hurtMarked = true;
                CombatHandler.stagger(p, e, 25);
                horse.level().playSound(null, e.blockPosition(), SoundEvents.HORSE_STEP, SoundSource.PLAYERS, 1f, 0.6f);
            }
        }
    }

    /** Horseman: +4% speed per skill 20 levels and +5% per rank; Endurance Rider: +20% jump strength. */
    private static void applyModifiers(AbstractHorse horse, PlayerData data) {
        double speed = data.getSkill(Skill.RIDING) * 0.002 + 0.05 * Perks.rank(data, "riding.horseman");
        set(horse.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID, "Riding skill", speed);
        set(horse.getAttribute(Attributes.JUMP_STRENGTH), JUMP_ID, "Endurance Rider", Perks.has(data, "riding.endurance_rider") ? 0.2 : 0);
    }

    private static void set(AttributeInstance inst, UUID id, String name, double value) {
        if (inst == null) return;
        AttributeModifier old = inst.getModifier(id);
        if (old != null && Math.abs(old.getAmount() - value) < 1e-6) return;
        if (old != null) inst.removeModifier(id);
        if (value > 0) inst.addTransientModifier(new AttributeModifier(id, name, value, AttributeModifier.Operation.MULTIPLY_BASE));
    }

    /** Removes the rider's bonuses from a horse it got off (dismounts are checked when the horse next ticks). */
    @SubscribeEvent
    public static void onMountChange(net.minecraftforge.event.entity.EntityMountEvent event) {
        if (event.isDismounting() && event.getEntityBeingMounted() instanceof AbstractHorse horse && !horse.level().isClientSide) {
            set(horse.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID, "", 0);
            set(horse.getAttribute(Attributes.JUMP_STRENGTH), JUMP_ID, "", 0);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        // Stable Hand: your horse takes 25% less damage while you ride it.
        if (victim instanceof AbstractHorse horse && horse.getControllingPassenger() instanceof ServerPlayer rider
                && Perks.has(rider, "riding.stable_hand")) {
            event.setAmount(event.getAmount() * 0.75f);
        }
        Entity src = event.getSource().getEntity();
        if (!(src instanceof ServerPlayer p) || mount(p) == null) return;
        AbstractHorse horse = mount(p);
        boolean arrow = event.getSource().getDirectEntity() instanceof AbstractArrow;
        float mult = 1f;
        if (arrow) {
            if (Perks.has(p, "riding.horse_archer")) mult *= 1.25f;
        } else if (event.getSource().getDirectEntity() == p) {
            if (Perks.has(p, "riding.mounted_combat")) mult *= 1.25f;
            boolean galloping = horse.getDeltaMovement().horizontalDistanceSqr() > 0.09;
            if (galloping && Perks.has(p, "riding.cavalry_charge")) {
                mult *= 1.5f;
                CombatHandler.stagger(p, victim, 30);
            }
        }
        if (mult != 1f) event.setAmount(event.getAmount() * mult);
        // mounted combat trains Riding a little (by damage dealt, like the weapon skills)
        Progression.addSkillXp(p, Skill.RIDING, Math.min(event.getAmount(), victim.getHealth()) * 0.15f);
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer p && mount(p) != null && !(event.getEntity() instanceof Player)) {
            Progression.addSkillXp(p, Skill.RIDING, 4f);
        }
    }

    public static void forget(UUID id) {
        RIDDEN.remove(id);
        LAST_POS.remove(id);
    }
}

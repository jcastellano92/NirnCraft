package com.nirncraft.magic.shout;

import com.nirncraft.magic.MagicFx;
import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.magic.Targeting;
import com.nirncraft.magic.spell.Element;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Slow Time (TIID KLO UL): real time slowing around the shouter. Creatures in the field only get one tick in every
 * 2 / 3 / 5 (one, two, three words): they move, attack, fall and even burn at half, a third or a fifth of normal speed.
 * Projectiles that enter the field crawl at the same fraction of their speed and pick up again when they leave it.
 * Other players (PvP) can't be tick-skipped and are heavily slowed instead. The shouter is unaffected.
 */
public final class SlowTime {
    private static final String PROJ_KEY = "nirncraft_time_slowed";
    private static final int[] PERIOD = {2, 3, 5};
    private static final int[] SECONDS = {8, 12, 16};
    private static final double RADIUS = 32;

    private record Field(UUID owner, int period, long until) {}

    private static final List<Field> FIELDS = new ArrayList<>();
    private static final List<Projectile> SLOWED = new ArrayList<>();

    private SlowTime() {}

    public static void start(ServerPlayer p, int words) {
        int w = Math.max(1, Math.min(3, words));
        FIELDS.removeIf(f -> f.owner.equals(p.getUUID()));
        FIELDS.add(new Field(p.getUUID(), PERIOD[w - 1], p.level().getGameTime() + SECONDS[w - 1] * 20L));
        MagicFx.sendNear(p.level(), p.position(), 64, MagicFx.TIME_FIELD, Element.ARCANE, p.position(), p.position(), p.getId(), 12);
    }

    /** Whether this creature skips the current tick (called from the living tick event, server side only). */
    public static boolean skipsTick(LivingEntity e) {
        if (e instanceof Player) return false;
        MobEffectInstance inst = e.getEffect(com.nirncraft.platform.Effects.holder(MagicRegistry.TIME_SLOWED.get()));
        if (inst == null) return false;
        int period = PERIOD[Math.max(0, Math.min(2, inst.getAmplifier()))];
        return (e.level().getGameTime() + e.getId()) % period != 0;
    }

    public static void tick(MinecraftServer server) {
        if (FIELDS.isEmpty() && SLOWED.isEmpty()) return;
        List<Projectile> inside = new ArrayList<>();
        for (Iterator<Field> it = FIELDS.iterator(); it.hasNext(); ) {
            Field f = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(f.owner);
            if (owner == null || !owner.isAlive() || owner.level().getGameTime() >= f.until) {
                it.remove();
                continue;
            }
            ServerLevel level = owner.level();
            long now = level.getGameTime();
            if (now % 5 == 0) {
                int amp = f.period == 2 ? 0 : f.period == 3 ? 1 : 2;
                for (LivingEntity e : Targeting.around(level, owner.position(), RADIUS, e -> e != owner && !e.isSpectator())) {
                    if (e instanceof ServerPlayer other) {
                        if (Targeting.canHarm(owner, other)) other.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 10, amp + 2, false, false));
                    } else {
                        // the effect only counts down on the ticks the creature gets, so it lasts ~2 real seconds
                        e.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MagicRegistry.TIME_SLOWED.get()), Math.max(2, 40 / f.period), amp, false, false, true));
                    }
                }
            }
            if (now % 10 == 0) {
                MagicFx.sendNear(level, owner.position(), 48, MagicFx.TIME_FIELD, Element.ARCANE, owner.position(), owner.position(), owner.getId(), 10);
            }
            for (Projectile proj : level.getEntitiesOfClass(Projectile.class, owner.getBoundingBox().inflate(RADIUS),
                    pr -> pr.getOwner() != owner && pr.isAlive())) {
                if (proj instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow a && com.nirncraft.arsenal.entity.SkyArrow.isArrowInGround(a)) continue;
                inside.add(proj);
                CompoundTag pd = proj.getPersistentData();
                if (!pd.contains(PROJ_KEY)) {
                    pd.putInt(PROJ_KEY, f.period);
                    proj.setDeltaMovement(proj.getDeltaMovement().scale(1.0 / f.period));
                    proj.syncVelocity = true;
                    SLOWED.add(proj);
                } else if (!proj.isNoGravity()) {
                    // slowed time slows gravity too: give back all but 1/period^2 of this tick's pull
                    double g = proj instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow ? 0.05
                            : proj instanceof com.nirncraft.magic.spell.SpellProjectile ? 0
                            : proj instanceof net.minecraft.world.entity.projectile.ThrowableProjectile ? 0.03 : 0;
                    int period = Math.max(1, pd.getIntOr(PROJ_KEY, 0));
                    if (g > 0) proj.setDeltaMovement(proj.getDeltaMovement().add(0, g * (1 - 1.0 / (period * period)), 0));
                }
            }
        }
        // projectiles that left every field (or whose field ended) speed up again
        for (Iterator<Projectile> it = SLOWED.iterator(); it.hasNext(); ) {
            Projectile proj = it.next();
            if (!proj.isAlive()) {
                it.remove();
            } else if (!inside.contains(proj)) {
                CompoundTag pd = proj.getPersistentData();
                int period = Math.max(1, pd.getIntOr(PROJ_KEY, 0));
                pd.remove(PROJ_KEY);
                Vec3 v = proj.getDeltaMovement();
                proj.setDeltaMovement(v.scale(period));
                proj.syncVelocity = true;
                it.remove();
            }
        }
    }

    public static void clear() {
        FIELDS.clear();
        SLOWED.clear();
    }
}

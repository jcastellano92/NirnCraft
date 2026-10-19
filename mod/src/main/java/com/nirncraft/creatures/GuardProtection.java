package com.nirncraft.creatures;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Holds;
import com.nirncraft.core.Notifier;
import com.nirncraft.creatures.entity.GiantEntity;
import com.nirncraft.creatures.entity.GuardEntity;
import com.nirncraft.crime.Bounty;
import com.nirncraft.crime.Crimes;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes hold guards reliably protective. Once a second around every player: guards pick the best threat within
 * {@code guardAlertRadius} (attackers of the player or a citizen first, then any hostile), civilians run from
 * hostiles, and three or more hostiles raise a town alarm (all guards converge, bell, message) that clears after
 * {@code alarmClearSeconds} without hostiles. State is a small per-settlement-cell map, server side only.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class GuardProtection {
    private static final int ALARM_THRESHOLD = 3;
    private static final int CELL = 96;

    private static final class Alarm {
        long lastThreat;
        double x, z;
    }

    private static final Map<String, Alarm> ALARMS = new HashMap<>();

    private GuardProtection() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 7 || player.isSpectator()) return;
        try {
            scan(player);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.debug("Guard scan failed", e);
        }
    }

    private static boolean helpable(ServerPlayer p) {
        if (!CreaturesConfig.GUARD_HELP_PLAYER.get() || p.isCreative() || p.isSpectator()) return false;
        if (Bounty.isHostile(p)) return false;
        return Bounty.get(p, Holds.holdAt(p.level(), p.blockPosition())) < Bounty.KILL_ON_SIGHT;
    }

    private static boolean isProtected(LivingEntity e) {
        if (e instanceof ServerPlayer p) return helpable(p);
        return e instanceof GuardEntity || e instanceof AbstractVillager || e instanceof NpcEntity npc && Crimes.isCivilian(npc);
    }

    /** Whether this creature is attacking someone the guards protect. */
    private static boolean attacksProtected(Mob m) {
        LivingEntity t = m.getTarget();
        return t != null && t.isAlive() && isProtected(t);
    }

    private static boolean isThreat(Mob m) {
        if (!m.isAlive() || m instanceof GuardEntity || Crimes.isCivilian(m)) return false;
        return m instanceof Enemy || attacksProtected(m);
    }

    private static void scan(ServerPlayer player) {
        ServerLevel level = player.level();
        double r = CreaturesConfig.GUARD_ALERT_RADIUS.get();
        AABB box = player.getBoundingBox().inflate(r * 1.5, 20, r * 1.5);
        List<GuardEntity> guards = level.getEntitiesOfClass(GuardEntity.class, box, Mob::isAlive);
        if (guards.isEmpty()) {
            // few settlements keep a watch now (society.GuardWatch): the people still run from what hunts them
            List<Mob> hostile = new ArrayList<>();
            for (Mob t : level.getEntitiesOfClass(Mob.class, box, GuardProtection::isThreat)) {
                if (t.distanceToSqr(player) <= r * r) hostile.add(t);
            }
            if (CreaturesConfig.CIVILIANS_FLEE.get() && !hostile.isEmpty()) civiliansFlee(level, player, r, hostile, false);
            return;
        }
        long now = level.getGameTime();

        double cx = 0, cz = 0;
        for (GuardEntity g : guards) {
            cx += g.getX();
            cz += g.getZ();
        }
        cx /= guards.size();
        cz /= guards.size();
        String key = level.dimension().identifier() + ":" + Mth.floor(cx / CELL) + ":" + Mth.floor(cz / CELL);
        Alarm alarm = ALARMS.get(key);
        boolean alarmed = alarm != null;
        double reach = alarmed ? r * 1.5 : r;

        List<Mob> threats = level.getEntitiesOfClass(Mob.class, box, GuardProtection::isThreat);
        List<Mob> near = new ArrayList<>();
        for (Mob t : threats) {
            for (GuardEntity g : guards) {
                if (g.distanceToSqr(t) <= reach * reach) {
                    near.add(t);
                    break;
                }
            }
        }

        // town-wide alarm
        if (CreaturesConfig.TOWN_ALARM.get()) {
            if (!near.isEmpty() && alarm != null) alarm.lastThreat = now;
            if (alarm == null && near.size() >= ALARM_THRESHOLD) {
                alarm = new Alarm();
                alarm.lastThreat = now;
                alarm.x = cx;
                alarm.z = cz;
                ALARMS.put(key, alarm);
                alarmed = true;
                reach = r * 1.5;
                level.playSound(null, cx, guards.get(0).getY(), cz, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 3.0f, 0.8f);
                for (ServerPlayer p : level.players()) {
                    if (p.distanceToSqr(cx, p.getY(), cz) < reach * reach * 1.6) {
                        Notifier.message(p, Component.translatableWithFallback("creatures.nirncraft.town_alarm", "The town is under attack!"));
                    }
                }
            } else if (alarm != null && now - alarm.lastThreat > CreaturesConfig.ALARM_CLEAR_SECONDS.get() * 20L) {
                ALARMS.remove(key);
                alarmed = false;
            }
            if (ALARMS.size() > 64) ALARMS.values().removeIf(a -> now - a.lastThreat > 6000);
        }

        // guards pick targets (never replacing a player target set by the crime module)
        for (GuardEntity g : guards) {
            LivingEntity cur = g.getTarget();
            if (cur instanceof Player) continue;
            boolean curAttacker = cur instanceof Mob cm && cm.isAlive() && attacksProtected(cm);
            Mob best = null;
            boolean bestAttacker = false;
            double bestScore = Double.MAX_VALUE;
            for (Mob t : near) {
                double d2 = g.distanceToSqr(t);
                if (d2 > reach * reach) continue;
                boolean attacker = attacksProtected(t);
                if (!attacker && (t instanceof GiantEntity || t instanceof Enderman)) continue;
                double score = d2 - (attacker ? (t.getTarget() instanceof Player ? 9000 : 6000) : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = t;
                    bestAttacker = attacker;
                }
            }
            if (best != null && best != cur && (cur == null || !cur.isAlive() || bestAttacker && !curAttacker)) {
                g.setTarget(best);
                cur = best;
            }
            // stuck / idle at a distance: push the path again
            if (cur != null && cur.isAlive() && g.getNavigation().isDone() && g.distanceToSqr(cur) > 9) {
                g.getNavigation().moveTo(cur, 1.2);
            }
        }

        // civilians run from hostiles nearby
        if (CreaturesConfig.CIVILIANS_FLEE.get() && !near.isEmpty()) civiliansFlee(level, player, r, near, alarmed);
    }

    /** Townsfolk within {@code r} of the player run from the hostiles in {@code near} (further when the town is alarmed). */
    private static void civiliansFlee(ServerLevel level, ServerPlayer player, double r, List<Mob> near, boolean alarmed) {
        double fear = alarmed ? 24 : 14;
        AABB civBox = player.getBoundingBox().inflate(r, 12, r);
        for (Mob civ : level.getEntitiesOfClass(Mob.class, civBox, m -> m.isAlive() && m instanceof PathfinderMob
                && (m instanceof AbstractVillager || m instanceof NpcEntity) && m.getTarget() == null)) {
            Mob threat = null;
            double bd = fear * fear;
            for (Mob t : near) {
                double d = civ.distanceToSqr(t);
                if (d < bd) {
                    bd = d;
                    threat = t;
                }
            }
            if (threat == null) continue;
            Vec3 to = DefaultRandomPos.getPosAway((PathfinderMob) civ, 16, 7, threat.position());
            if (to != null) civ.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
        }
    }
}

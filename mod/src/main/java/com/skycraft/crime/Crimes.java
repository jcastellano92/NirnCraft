package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.core.Holds;
import com.skycraft.dialogue.Dialogue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Crime detection: who counts as a witness, reporting crimes to a hold, and the violent crimes (assault, murder).
 * A crime is only added to the bounty when someone sees it. Victims always witness their own assault.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Crimes {
    public static final TagKey<EntityType<?>> GUARDS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "guards"));
    public static final double WITNESS_RANGE = 20;
    /** Assaulting the same victim again only counts once per this many ticks (30 s). */
    private static final long ASSAULT_THROTTLE = 600;
    /** Guards fight a player who just assaulted someone for this long before coming to arrest them. */
    private static final int ASSAULT_HOSTILE_TICKS = 600;

    /** Killing someone who hurt the player within this many ticks (30 s) is self-defense. */
    private static final long SELF_DEFENSE_TICKS = 600;

    private static final Map<String, Long> LAST_ASSAULT = new HashMap<>();
    /** "aggressor uuid|player uuid" -> game time the aggressor last hurt the player. */
    private static final Map<String, Long> AGGRESSORS = new HashMap<>();

    private Crimes() {}

    // ------------------------------------------------------------------ who's who

    public static boolean isGuard(Entity entity) {
        return entity.getType().is(GUARDS);
    }

    /** Townsfolk whose murder or assault is a crime, and who report crimes they see. */
    public static boolean isCivilian(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        if (entity instanceof AbstractVillager || isGuard(entity)) return true;
        return entity.getType().is(Dialogue.TALKERS) && !(entity instanceof Enemy);
    }

    /** Whether crimes are possible where the player is (not in creative, not in jail). */
    public static boolean canCommitCrime(ServerPlayer player) {
        return !player.isCreative() && !player.isSpectator() && !Jail.isJailDimension(player.level());
    }

    // ------------------------------------------------------------------ witnesses

    /** Whether anyone (other than {@code exclude}) sees the player right now. */
    public static boolean witnessed(ServerPlayer player, @Nullable LivingEntity exclude) {
        return findWitness(player, exclude) != null;
    }

    @Nullable
    public static LivingEntity findWitness(ServerPlayer player, @Nullable LivingEntity exclude) {
        var level = player.level();
        boolean dark = level.getMaxLocalRawBrightness(player.blockPosition()) < 5;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(WITNESS_RANGE),
                e -> e != exclude && e != player && e.isAlive() && isCivilian(e))) {
            if (e.isSleeping()) continue;
            double range = WITNESS_RANGE * Math.max(0.1, player.getVisibilityPercent(e));
            if (dark && player.isCrouching()) range *= 0.5;
            double dist = e.distanceTo(player);
            if (dist > Math.max(2.5, range)) continue;
            // a sneaking player behind someone isn't noticed unless they're right next to them
            if (player.isCrouching() && dist > 3 && !facing(e, player)) continue;
            if (e.hasLineOfSight(player)) return e;
        }
        return null;
    }

    /** Whether {@code looker} faces {@code target} (within ~110 degrees of its view direction). */
    public static boolean facing(LivingEntity looker, Entity target) {
        Vec3 view = looker.getViewVector(1f);
        Vec3 to = target.position().subtract(looker.position());
        Vec3 flatView = new Vec3(view.x, 0, view.z);
        Vec3 flatTo = new Vec3(to.x, 0, to.z);
        if (flatView.lengthSqr() < 1e-6 || flatTo.lengthSqr() < 1e-6) return true;
        return flatView.normalize().dot(flatTo.normalize()) > -0.35;
    }

    // ------------------------------------------------------------------ reporting

    /**
     * Reports a crime committed at {@code where} if it was witnessed. {@code alwaysSeen} is for crimes the victim
     * notices (assault, caught pickpocketing). Returns whether a bounty was added.
     */
    public static boolean report(ServerPlayer player, BlockPos where, int bounty, boolean alwaysSeen, @Nullable LivingEntity exclude) {
        if (!canCommitCrime(player) || bounty <= 0) return false;
        if (!alwaysSeen && !witnessed(player, exclude)) return false;
        String hold = Holds.holdAt(player.level(), where);
        Bounty.add(player, hold, bounty);
        Guards.alert(player);
        // townsfolk who saw it think less of the player (society relationships)
        com.skycraft.society.Relationships.onCrimeWitnessed(player, where, bounty);
        return true;
    }

    // ------------------------------------------------------------------ violent crimes

    @Nullable
    private static ServerPlayer attacker(net.minecraft.world.damagesource.DamageSource source) {
        Entity e = source.getEntity();
        return e instanceof ServerPlayer sp ? sp : null;
    }

    /** Remembers a civilian (or any NPC) hurting a player, for self-defense. */
    private static void noteAggression(LivingEntity aggressor, ServerPlayer player) {
        long now = player.level().getGameTime();
        if (AGGRESSORS.size() > 2048) AGGRESSORS.entrySet().removeIf(e -> now - e.getValue() > SELF_DEFENSE_TICKS);
        AGGRESSORS.put(aggressor.getUUID() + "|" + player.getUUID(), now);
    }

    /**
     * Whether killing/hurting {@code victim} is self-defense: it attacked the player recently, is fighting them right
     * now, or accepted a duel with them. Guards are never fair game.
     */
    public static boolean selfDefense(LivingEntity victim, ServerPlayer player) {
        if (isGuard(victim)) return false;
        var data = victim.getPersistentData();
        if (data.hasUUID(com.skycraft.society.NpcDialogue.DUEL_TAG) && player.getUUID().equals(data.getUUID(com.skycraft.society.NpcDialogue.DUEL_TAG))) return true;
        // the player started it: fighting back doesn't make the victim fair game
        Long assaulted = LAST_ASSAULT.get(victim.getUUID() + "|" + player.getUUID());
        if (assaulted != null && player.level().getGameTime() - assaulted < 6000) return false;
        if (victim instanceof Mob mob && mob.getTarget() == player) return true;
        if (victim instanceof com.skycraft.society.entity.NpcEntity npc && npc.isHostileTo(player)) return true;
        Long last = AGGRESSORS.get(victim.getUUID() + "|" + player.getUUID());
        return last != null && player.level().getGameTime() - last <= SELF_DEFENSE_TICKS;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (victim instanceof ServerPlayer hurtPlayer && event.getSource().getEntity() instanceof LivingEntity aggressor
                && !(aggressor instanceof net.minecraft.world.entity.player.Player)) {
            noteAggression(aggressor, hurtPlayer);
            return;
        }
        ServerPlayer player = attacker(event.getSource());
        if (player == null || !canCommitCrime(player)) return;

        // Attacking an owned farm animal in a settlement
        if (victim instanceof net.minecraft.world.entity.animal.Animal animal && Ownership.isOwnedByOther(player, animal)) {
            report(player, victim.blockPosition(), 15, false, null);
            return;
        }

        if (!isCivilian(victim)) return;

        // Attacking a guard: assault and resisting arrest. The only leeway is one stray punch (weapon sheathed) per
        // guard per minute, and never while already fighting the guards.
        if (isGuard(victim)) {
            if (victim instanceof Mob mob) {
                if (com.skycraft.combat.Sheathe.isSheathed(player) && !Bounty.isHostile(player)
                        && !Guards.hitRecently(victim, player, 1200) && Guards.allowAccident(victim, player)) {
                    Guards.handleAccidentalHit(player, mob);
                    return;
                }
                mob.setTarget(player);
            }
            Guards.noteHit(victim, player);
            long now = victim.level().getGameTime();
            String key = victim.getUUID() + "|" + player.getUUID();
            Long last = LAST_ASSAULT.get(key);
            if (last == null || now - last >= ASSAULT_THROTTLE) {
                LAST_ASSAULT.put(key, now);
                Bounty.increment(player, "assaults", 1);
                report(player, victim.blockPosition(), Bounty.ASSAULT, true, null);
            }
            Guards.resist(player);
            return;
        }

        // fighting back against someone who's already attacking you isn't a new crime
        if (selfDefense(victim, player)) return;

        long now = victim.level().getGameTime();
        String key = victim.getUUID() + "|" + player.getUUID();
        Long last = LAST_ASSAULT.get(key);
        if (last != null && now - last < ASSAULT_THROTTLE) return;
        if (LAST_ASSAULT.size() > 2048) LAST_ASSAULT.entrySet().removeIf(e -> now - e.getValue() > ASSAULT_THROTTLE);
        LAST_ASSAULT.put(key, now);

        // Fear tracking: victim and nearby civilians fear the attacker until the player leaves
        if (victim instanceof com.skycraft.society.entity.NpcEntity ne) {
            ne.setFearedPlayer(player.getUUID());
        }
        for (com.skycraft.society.entity.NpcEntity nearbyCivilian : victim.level().getEntitiesOfClass(com.skycraft.society.entity.NpcEntity.class, victim.getBoundingBox().inflate(12), e -> e.role().civilian)) {
            nearbyCivilian.setFearedPlayer(player.getUUID());
        }

        // Only report immediately if witnessed by other bystanders or guards are nearby
        boolean witnessedByOther = witnessed(player, victim);
        boolean guardsNearby = !victim.level().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(24), Crimes::isGuard).isEmpty();

        if (witnessedByOther || guardsNearby) {
            Bounty.increment(player, "assaults", 1);
            report(player, victim.blockPosition(), Bounty.ASSAULT, true, null);
            Bounty.makeHostile(player, ASSAULT_HOSTILE_TICKS);
            Guards.attack(player, 24);
        } else {
            // Unwitnessed: no immediate bounty! Queue report if victim flees to a guard
            Theft.queueWitnessReport(player, victim, victim.blockPosition(), Bounty.ASSAULT);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        ServerPlayer player = attacker(event.getSource());
        if (player == null || !canCommitCrime(player)) return;

        // Killing an owned farm animal in a settlement
        if (victim instanceof net.minecraft.world.entity.animal.Animal animal && Ownership.isOwnedByOther(player, animal)) {
            if (witnessed(player, victim)) {
                String hold = Holds.holdAt(player.level(), victim.blockPosition());
                Bounty.add(player, hold, 50);
                Guards.alert(player);
            }
            return;
        }

        if (!isCivilian(victim)) return;
        // killing someone who attacked you (or who accepted your duel) isn't murder
        if (selfDefense(victim, player)) return;

        // the Dark Brotherhood always knows
        Bounty.increment(player, "murders", 1);
        if (witnessed(player, victim)) {
            String hold = Holds.holdAt(player.level(), victim.blockPosition());
            Bounty.add(player, hold, Bounty.MURDER);
            Bounty.markViolent(player, hold);
            Guards.attack(player, 32);
        }
    }

    static void forget(java.util.UUID player) {
        String suffix = "|" + player;
        LAST_ASSAULT.keySet().removeIf(k -> k.endsWith(suffix));
        AGGRESSORS.keySet().removeIf(k -> k.endsWith(suffix));
    }
}

package com.skycraft.crime;

import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.network.NotifyKind;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guards (any entity in {@code #skycraft:guards}): they know about every bounty in their hold, walk up to wanted
 * players and confront them with the arrest dialogue, and attack players who resist arrest, who just assaulted
 * someone, or whose bounty is 1000+ (kill on sight).
 */
public final class Guards {
    public static final double AWARE_RANGE = 24;
    public static final double RESIST_RANGE = 48;
    /** Resisting arrest keeps the guards hostile for 5 minutes (or until the bounty is cleared). */
    public static final int RESIST_TICKS = 6000;
    private static final long CONFRONT_COOLDOWN = 400;
    private static final int RUMORS = 8;
    /** A failed persuasion cannot be retried in the same hold for one in-game day. */
    private static final long PERSUADE_COOLDOWN = 24000L;

    /** A guard hit by the player doesn't stand down (yield, paid bounty) for this many ticks (30 s). */
    private static final long CALM_AFTER_HIT = 600;

    private static final Map<UUID, Long> NEXT_CONFRONT = new HashMap<>();
    /** "guard uuid|player uuid" -> game time the player last hit that guard. */
    private static final Map<String, Long> LAST_HIT = new HashMap<>();
    private static final Map<String, Long> ACCIDENTS = new HashMap<>();

    private Guards() {}

    static void noteHit(LivingEntity guard, ServerPlayer player) {
        long now = guard.level().getGameTime();
        if (LAST_HIT.size() > 1024) LAST_HIT.entrySet().removeIf(e -> now - e.getValue() > 1200);
        LAST_HIT.put(guard.getUUID() + "|" + player.getUUID(), now);
    }

    /** One stray (sheathed) punch per guard per minute is forgiven; returns whether this one is. */
    static boolean allowAccident(LivingEntity guard, ServerPlayer player) {
        long now = guard.level().getGameTime();
        String key = guard.getUUID() + "|" + player.getUUID();
        Long last = ACCIDENTS.get(key);
        if (last != null && now - last < 1200) return false;
        if (ACCIDENTS.size() > 1024) ACCIDENTS.entrySet().removeIf(e -> now - e.getValue() > 1200);
        ACCIDENTS.put(key, now);
        return true;
    }

    /** Whether the player hit this guard within the last {@code ticks}. */
    static boolean hitRecently(LivingEntity guard, ServerPlayer player, long ticks) {
        Long last = LAST_HIT.get(guard.getUUID() + "|" + player.getUUID());
        return last != null && guard.level().getGameTime() - last < ticks;
    }

    // ------------------------------------------------------------------ behaviour (every second per player)

    static void tick(ServerPlayer player) {
        if (player.isSpectator() || Jail.isJailed(player) || Jail.isLoadingJail(player) || Jail.isJailDimension(player.level())) return;
        var level = player.serverLevel();
        String hold = Holds.holdAt(level, player.blockPosition());
        int bounty = player.isCreative() ? 0 : Bounty.get(player, hold);
        boolean hostile = bounty > 0 && (bounty >= Bounty.KILL_ON_SIGHT || Bounty.isHostile(player));
        long now = level.getGameTime();

        List<Mob> guards = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(AWARE_RANGE),
                m -> m.isAlive() && Crimes.isGuard(m));
        for (Mob guard : guards) {
            // a guard the player just hit keeps fighting, whatever the bounty says
            boolean struck = hitRecently(guard, player, CALM_AFTER_HIT);
            if (struck) {
                if (guard.getTarget() != player) guard.setTarget(player);
                continue;
            }
            if (bounty <= 0) {
                if (guard.getTarget() == player) calm(guard);
                continue;
            }
            double distSqr = guard.distanceToSqr(player);
            boolean sees = distSqr < 64 || guard.hasLineOfSight(player);
            if (hostile) {
                // Yielding: if player sheathes their weapon (or has empty hands) and bounty < KILL_ON_SIGHT, guards stand down to arrest
                if (com.skycraft.combat.Sheathe.isSheathed(player) && bounty < Bounty.KILL_ON_SIGHT) {
                    Bounty.clearHostile(player);
                    hostile = false;
                    calm(guard);
                } else {
                    if (sees && guard.getTarget() != player) guard.setTarget(player);
                    continue;
                }
            }
            if (guard.getTarget() == player) calm(guard);
            if (!sees) continue;
            if (distSqr > 16) {
                if (guard instanceof com.skycraft.creatures.entity.GuardEntity ge) {
                    ge.summon(player.blockPosition(), 40);
                }
                guard.getNavigation().moveTo(player, 1.1);
            } else {
                if (guard instanceof com.skycraft.creatures.entity.GuardEntity ge) {
                    ge.summon(player.blockPosition(), 20);
                }
                guard.getNavigation().stop();
                guard.getLookControl().setLookAt(player, 30f, 30f);
                Long next = NEXT_CONFRONT.get(player.getUUID());
                if ((next == null || now >= next) && player.isAlive() && player.containerMenu == player.inventoryMenu) {
                    NEXT_CONFRONT.put(player.getUUID(), now + CONFRONT_COOLDOWN);
                    Dialogue.endConversation(player, guard.getId());
                    Dialogue.open(player, guard, Component.translatable("crime.skycraft.guard.arrest"));
                }
            }
        }
    }

    /** Guards near a fresh crime come over (the per-second tick takes it from there). */
    static void alert(ServerPlayer player) {
        for (Mob guard : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(AWARE_RANGE),
                m -> m.isAlive() && Crimes.isGuard(m))) {
            if (guard.getTarget() == null) {
                if (guard instanceof com.skycraft.creatures.entity.GuardEntity ge) {
                    ge.summon(player.blockPosition(), 60);
                }
                guard.getNavigation().moveTo(player, 1.1);
            }
        }
    }

    /** Every guard within {@code range} attacks the player. */
    static void attack(ServerPlayer player, double range) {
        for (Mob guard : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(range),
                m -> m.isAlive() && Crimes.isGuard(m))) {
            guard.setTarget(player);
        }
    }

    /** Guards near the player stop fighting them (bounty paid, served or forgiven). */
    public static void pacify(ServerPlayer player) {
        for (Mob guard : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(64),
                m -> m.isAlive() && Crimes.isGuard(m) && m.getTarget() == player)) {
            calm(guard);
        }
        NEXT_CONFRONT.remove(player.getUUID());
        // the debt is settled: guards struck earlier may stand down now
        String suffix = "|" + player.getUUID();
        LAST_HIT.keySet().removeIf(k -> k.endsWith(suffix));
    }

    private static void calm(Mob guard) {
        if (guard instanceof NeutralMob neutral) neutral.stopBeingAngry();
        guard.setTarget(null);
        guard.setLastHurtByMob(null);
    }

    public static void handleAccidentalHit(ServerPlayer player, Mob guard) {
        String hold = Holds.holdAt(player.level(), guard.blockPosition());
        int bounty = Bounty.get(player, hold);
        if (bounty < Bounty.KILL_ON_SIGHT) {
            guard.getNavigation().moveTo(player, 1.0);
            guard.getLookControl().setLookAt(player, 30f, 30f);
            calm(guard);
            Bounty.add(player, hold, 10);
            Dialogue.open(player, guard, Component.translatableWithFallback("crime.skycraft.guard.watch_it", "Smart move. Put your weapon away and watch it next time."));
        } else {
            guard.setTarget(player);
            resist(player);
        }
    }

    static void resist(ServerPlayer player) {
        Bounty.makeHostile(player, RESIST_TICKS);
        attack(player, RESIST_RANGE);
        Notifier.send(player, NotifyKind.CRIME, Component.translatable("crime.skycraft.resisting"), Component.empty());
    }

    static void forget(UUID player) {
        NEXT_CONFRONT.remove(player);
        String suffix = "|" + player;
        LAST_HIT.keySet().removeIf(k -> k.endsWith(suffix));
        ACCIDENTS.keySet().removeIf(k -> k.endsWith(suffix));
    }

    // ------------------------------------------------------------------ arrest dialogue

    static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!Crimes.isGuard(npc) || player.isCreative()) return;
        String hold = Holds.holdAt(player.level(), player.blockPosition());
        int bounty = Bounty.get(player, hold);
        if (bounty <= 0) {
            out.add(new DialogueOption("crime.rumor", Component.translatable("dialogue.skycraft.crime.rumor"), 400,
                    (p, n) -> Dialogue.open(p, n, Component.translatable("crime.skycraft.rumor." + p.getRandom().nextInt(RUMORS)))));
            return;
        }
        int speech = SkyData.get(player).getSkill(Skill.SPEECH);
        boolean violent = Bounty.violent(player, hold);
        boolean triedPersuasion = triedRecently(player, hold);

        out.add(new DialogueOption("crime.pay", Component.translatable("dialogue.skycraft.crime.pay", Currency.formatCompact(bounty)), 1,
                (p, n) -> pay(p, n, hold)));
        out.add(new DialogueOption("crime.jail", Component.translatable("dialogue.skycraft.crime.jail"), 2,
                (p, n) -> Jail.send(p, hold)));
        if (!violent && bounty <= 100 && (speech >= 50 || Perks.has(player, "speech.persuasion")) && !triedPersuasion) {
            int chance = persuadeChance(player, bounty);
            out.add(new DialogueOption("crime.persuade", Component.translatable("dialogue.skycraft.crime.persuade", chance), 3,
                    (p, n) -> persuade(p, n, hold, false)));
        }
        if (Perks.has(player, "speech.bribery")) {
            int cost = bribeCost(bounty);
            out.add(new DialogueOption("crime.bribe", Component.translatable("dialogue.skycraft.crime.bribe", Currency.formatCompact(cost)), 4,
                    (p, n) -> bribe(p, n, hold)));
        }
        if (!violent && bounty <= 500 && Perks.has(player, "speech.intimidation") && !triedPersuasion) {
            int chance = intimidateChance(player);
            out.add(new DialogueOption("crime.intimidate", Component.translatable("dialogue.skycraft.crime.intimidate", chance), 5,
                    (p, n) -> persuade(p, n, hold, true)));
        }
        out.add(new DialogueOption("crime.resist", Component.translatable("dialogue.skycraft.crime.resist"), 6,
                (p, n) -> resist(p)));
    }

    /**
     * A failed persuasion or intimidation closes the option for a whole in-game day in that hold, whatever the bounty
     * does meanwhile (a new petty crime must not re-open it).
     */
    private static boolean triedRecently(ServerPlayer player, String hold) {
        long at = Bounty.state(player).getLong("persuade_at_" + hold);
        long now = player.level().getGameTime();
        return at > 0 && now + 1 >= at && now + 1 - at < PERSUADE_COOLDOWN;
    }

    private static void pay(ServerPlayer player, LivingEntity guard, String hold) {
        int bounty = Bounty.get(player, hold);
        if (bounty <= 0) return;
        if (!Currency.take(player, bounty)) {
            Dialogue.open(player, guard, Component.translatable("crime.skycraft.guard.cant_afford", bounty));
            return;
        }
        Bounty.clear(player, hold);
        Bounty.confiscate(player, hold);
        Notifier.send(player, NotifyKind.CRIME, Component.translatable("crime.skycraft.bounty_paid", bounty, Holds.displayName(hold)), Component.empty());
        Notifier.message(player, Component.translatable("crime.skycraft.guard.paid_line", guard.getDisplayName()));
    }

    static int bribeCost(int bounty) {
        return (int) Math.ceil(bounty * 1.5);
    }

    private static void bribe(ServerPlayer player, LivingEntity guard, String hold) {
        int bounty = Bounty.get(player, hold);
        if (bounty <= 0) return;
        int cost = bribeCost(bounty);
        if (!Currency.take(player, cost)) {
            Dialogue.open(player, guard, Component.translatable("crime.skycraft.guard.cant_afford", cost));
            return;
        }
        Bounty.clear(player, hold);
        Progression.addSkillXp(player, Skill.SPEECH, Math.min(cost, 500));
        Notifier.message(player, Component.translatable("crime.skycraft.guard.bribed_line", guard.getDisplayName()));
    }

    private static int persuadeChance(ServerPlayer player, int bounty) {
        int speech = SkyData.get(player).getSkill(Skill.SPEECH);
        double c = 20 + speech * 0.6 + (Perks.has(player, "speech.persuasion") ? 25 : 0) - bounty * 0.15;
        return (int) Math.max(5, Math.min(95, c));
    }

    private static int intimidateChance(ServerPlayer player) {
        int speech = SkyData.get(player).getSkill(Skill.SPEECH);
        double c = 15 + speech * 0.45 + SkyData.get(player).getLevel() * 0.8;
        return (int) Math.max(5, Math.min(95, c));
    }

    private static void persuade(ServerPlayer player, LivingEntity guard, String hold, boolean intimidate) {
        int bounty = Bounty.get(player, hold);
        if (bounty <= 0) return;
        int chance = intimidate ? intimidateChance(player) : persuadeChance(player, bounty);
        if (player.getRandom().nextInt(100) < chance) {
            Bounty.clear(player, hold);
            Progression.addSkillXp(player, Skill.SPEECH, intimidate ? 60 : 45);
            Notifier.message(player, Component.translatable(intimidate ? "crime.skycraft.guard.intimidated_line" : "crime.skycraft.guard.persuaded_line",
                    guard.getDisplayName()));
        } else {
            Bounty.state(player).putLong("persuade_at_" + hold, player.level().getGameTime() + 1);
            SkyData.get(player).markDirty();
            Dialogue.open(player, guard, Component.translatable(intimidate ? "crime.skycraft.guard.intimidate_failed" : "crime.skycraft.guard.persuade_failed"));
        }
    }
}

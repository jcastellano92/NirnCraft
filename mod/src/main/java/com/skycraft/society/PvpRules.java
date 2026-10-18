package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PvP is a crime in the holds: attacking another player in front of witnesses adds an assault bounty, killing one is
 * murder (the Dark Brotherhood always counts it). Hitting back at someone who attacked you first is self-defense.
 * Party friendly fire is already blocked by the quest module (cancelled events are ignored here).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class PvpRules {
    /** Self-defense window and assault throttle, in ticks (30 s). */
    private static final long WINDOW = 600;
    /** One fight between two players: who struck first, and when either last hit the other. */
    private record Fight(UUID aggressor, long last) {
    }

    /** Unordered player pair -> current fight. */
    private static final Map<String, Fight> FIGHTS = new HashMap<>();
    /** "attacker|victim" -> game time of the last reported assault. */
    private static final Map<String, Long> LAST_REPORT = new HashMap<>();

    private PvpRules() {}

    private static String key(ServerPlayer a, ServerPlayer b) {
        return a.getUUID() + "|" + b.getUUID();
    }

    private static String pair(ServerPlayer a, ServerPlayer b) {
        return a.getUUID().compareTo(b.getUUID()) < 0 ? key(a, b) : key(b, a);
    }

    /** Records a hit; returns true if the attacker is defending themselves (the victim struck first). */
    private static boolean hit(ServerPlayer attacker, ServerPlayer victim, long now) {
        String p = pair(attacker, victim);
        Fight f = FIGHTS.get(p);
        if (f == null || now - f.last() > WINDOW) f = new Fight(attacker.getUUID(), now);
        FIGHTS.put(p, new Fight(f.aggressor(), now));
        if (FIGHTS.size() > 512) FIGHTS.entrySet().removeIf(e -> now - e.getValue().last() > WINDOW);
        return !f.aggressor().equals(attacker.getUUID());
    }

    private static boolean applies(ServerPlayer attacker, ServerPlayer victim) {
        if (!SocietyConfig.PVP_BOUNTY.get() || attacker == victim || victim.isCreative() || victim.isSpectator()
                || !Crimes.canCommitCrime(attacker) || Bounty.isHostile(victim)) return false;
        // Homestead grounds permit PvP without hold bounties
        if (com.skycraft.homestead.HomesteadData.get(victim.level().getServer()).getClaimAt(victim.level(), victim.blockPosition()) != null) {
            return false;
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0) return;
        if (!(event.getEntity() instanceof ServerPlayer victim) || !(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (attacker == victim) return;
        long now = victim.level().getGameTime();
        boolean defense = hit(attacker, victim, now);
        String k = key(attacker, victim);
        if (defense || !applies(attacker, victim)) return;
        Long reported = LAST_REPORT.get(k);
        if (reported != null && now - reported < WINDOW) return;
        if (Crimes.report(attacker, victim.blockPosition(), Bounty.ASSAULT, false, victim)) {
            LAST_REPORT.put(k, now);
            Bounty.increment(attacker, "assaults", 1);
            Bounty.makeHostile(attacker, 600);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer victim) || !(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        if (killer == victim) return;
        long now = victim.level().getGameTime();
        boolean defense = hit(killer, victim, now);
        FIGHTS.remove(pair(killer, victim));
        if (defense || !applies(killer, victim)) return;
        Bounty.increment(killer, "murders", 1);
        if (Crimes.report(killer, victim.blockPosition(), Bounty.MURDER, false, victim)) {
            Bounty.makeHostile(killer, 1200);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        String id = event.getEntity().getUUID().toString();
        FIGHTS.keySet().removeIf(k -> k.contains(id));
        LAST_REPORT.keySet().removeIf(k -> k.contains(id));
    }
}

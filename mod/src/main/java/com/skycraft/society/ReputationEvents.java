package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.crime.Crimes;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What changes reputation:
 * <ul>
 *     <li>killing faction members (soldiers, Thalmor, Forsworn, vampires, assassins...), with civil-war side effects;</li>
 *     <li>attacking faction members who weren't attacking you;</li>
 *     <li>the crime module's counters (murders, assaults, stolen items, pickpocketing), read every few seconds;</li>
 *     <li>the quest module's faction membership, ranks and faction quest points (joining, promotions, quests done);</li>
 *     <li>time: negative standings slowly recover.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class ReputationEvents {
    private static final String[] CRIME_COUNTERS = {"murders", "assaults", "items_stolen", "pickpockets"};
    private static final String[] QUEST_FACTIONS = {"companions", "college", "thieves_guild", "dark_brotherhood", "imperial_legion",
            "stormcloaks", "bards_college"};
    private static final Identifier BANDIT = Identifier.fromNamespaceAndPath(Skycraft.MODID, "bandit");
    private static final Identifier BANDIT_CHIEF = Identifier.fromNamespaceAndPath(Skycraft.MODID, "bandit_chief");
    private static final Identifier DRAGON = Identifier.fromNamespaceAndPath(Skycraft.MODID, "dragon");
    private static final long HURT_THROTTLE = 600;

    private static final Map<String, Long> LAST_HURT = new HashMap<>();
    private static final Map<UUID, Integer> RECOVERY = new HashMap<>();

    private ReputationEvents() {}

    // ------------------------------------------------------------------ kills

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (victim instanceof NpcEntity npc) {
            npcKilled(player, npc);
            return;
        }
        if (Crimes.isGuard(victim)) {
            Reputation.add(player, Reputation.GUARDS, -12);
            return;
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType());
        if (BANDIT.equals(id) || BANDIT_CHIEF.equals(id)) {
            Reputation.add(player, Reputation.BANDITS, BANDIT_CHIEF.equals(id) ? -8 : -4);
            Reputation.add(player, Reputation.TOWNSFOLK, 1);
            Reputation.add(player, Reputation.GUARDS, 1);
        } else if (DRAGON.equals(id)) {
            Reputation.add(player, Reputation.TOWNSFOLK, 8);
            Reputation.add(player, Reputation.GUARDS, 4);
            Reputation.add(player, Reputation.COMPANIONS, 2);
        } else if (victim instanceof AbstractIllager) {
            Reputation.add(player, Reputation.TOWNSFOLK, 1);
        }
    }

    private static void npcKilled(ServerPlayer p, NpcEntity npc) {
        boolean defense = npc.getTarget() == p || p.getUUID().equals(npc.getHuntTarget());
        int k = defense ? 1 : 2; // self-defense angers a faction half as much
        if (npc.isPrisoner()) Reputation.add(p, Reputation.TOWNSFOLK, -3);
        switch (npc.role()) {
            case IMPERIAL_SOLDIER -> {
                Reputation.add(p, Reputation.IMPERIAL_LEGION, -5 * k);
                Reputation.add(p, Reputation.STORMCLOAKS, 3);
            }
            case STORMCLOAK_SOLDIER -> {
                Reputation.add(p, Reputation.STORMCLOAKS, -5 * k);
                Reputation.add(p, Reputation.IMPERIAL_LEGION, 3);
                Reputation.add(p, Reputation.THALMOR, 1);
            }
            case THALMOR -> {
                Reputation.add(p, Reputation.THALMOR, -5 * k);
                Reputation.add(p, Reputation.STORMCLOAKS, 3);
            }
            case FORSWORN -> {
                Reputation.add(p, Reputation.FORSWORN, -6);
                Reputation.add(p, Reputation.TOWNSFOLK, 1);
            }
            case VAMPIRE -> {
                Reputation.add(p, Reputation.VAMPIRES, -6);
                Reputation.add(p, Reputation.TOWNSFOLK, 2);
                Reputation.add(p, Reputation.COMPANIONS, 1);
            }
            case NECROMANCER -> {
                Reputation.add(p, Reputation.COLLEGE, 2);
                Reputation.add(p, Reputation.TOWNSFOLK, 1);
            }
            case ASSASSIN -> Reputation.add(p, Reputation.DARK_BROTHERHOOD, -12);
            case THUG -> Reputation.add(p, Reputation.BANDITS, -3);
            case THIEF -> Reputation.add(p, Reputation.THIEVES_GUILD, -4);
            case MAGE -> Reputation.add(p, Reputation.COLLEGE, -4 * k);
            case JARL -> Reputation.add(p, Reputation.GUARDS, -25);
            case HOUSECARL -> Reputation.add(p, Reputation.GUARDS, -5 * k);
            default -> {
                // townsfolk murders are counted by the crime module and read back in scan()
            }
        }
    }

    // ------------------------------------------------------------------ assaults on faction members

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0) return;
        if (!(event.getEntity() instanceof NpcEntity npc) || npc.level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        NpcRole role = npc.role();
        if (role.civilian || role.faction == null || npc.getTarget() == player || player.getUUID().equals(npc.getHuntTarget())) return;
        if (Npcs.hostileToPlayer(role, player)) return; // already enemies
        long now = npc.level().getGameTime();
        String key = npc.getUUID() + "|" + player.getUUID();
        Long last = LAST_HURT.get(key);
        if (last != null && now - last < HURT_THROTTLE) return;
        if (LAST_HURT.size() > 1024) LAST_HURT.entrySet().removeIf(e -> now - e.getValue() > HURT_THROTTLE);
        LAST_HURT.put(key, now);
        Reputation.add(player, role.faction, -3);
    }

    // ------------------------------------------------------------------ crime counters, faction ranks, recovery

    /** Compares the crime/quest state with the last snapshot and applies the differences. Every 5 seconds. */
    static void scan(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module(Reputation.MODULE);
        boolean first = !com.skycraft.platform.NbtData.hasType(mod, "seen", Tag.TAG_COMPOUND);
        CompoundTag seen = Npcs.sub(mod, "seen");
        CompoundTag crime = data.module("crime");
        CompoundTag quest = data.module("quest");
        CompoundTag ranks = quest.getCompoundOrEmpty("factions");
        CompoundTag points = quest.getCompoundOrEmpty("faction_points");
        boolean changed = first;

        for (String c : CRIME_COUNTERS) {
            int now = crime.getIntOr(c, 0);
            int was = seen.getIntOr(c, 0);
            if (now != was) {
                seen.putInt(c, now);
                changed = true;
                if (!first && now > was) crime(player, c, now - was);
            }
        }
        for (String f : QUEST_FACTIONS) {
            int rank = ranks.contains(f) ? ranks.getIntOr(f, 0) : -1;
            int wasRank = seen.contains("rank_" + f) ? seen.getIntOr("rank_" + f,0) : -1;
            if (rank != wasRank) {
                seen.putInt("rank_" + f, rank);
                changed = true;
                if (!first) rankChanged(player, f, wasRank, rank);
            }
            int pts = points.getIntOr(f, 0);
            int wasPts = seen.getIntOr("pts_" + f,0);
            if (pts != wasPts) {
                seen.putInt("pts_" + f, pts);
                changed = true;
                if (!first && pts > wasPts && rank >= 0) Reputation.add(player, repFaction(f), Math.min(10, 2 * (pts - wasPts)));
            }
        }
        if (changed) data.markDirty();
    }

    private static String repFaction(String questFaction) {
        return questFaction.equals("bards_college") ? Reputation.TOWNSFOLK : questFaction;
    }

    private static void crime(ServerPlayer p, String counter, int n) {
        switch (counter) {
            case "murders" -> {
                Reputation.add(p, Reputation.TOWNSFOLK, -10 * n);
                Reputation.add(p, Reputation.GUARDS, -5 * n);
                Reputation.add(p, Reputation.DARK_BROTHERHOOD, 3 * n);
            }
            case "assaults" -> {
                Reputation.add(p, Reputation.TOWNSFOLK, -3 * n);
                Reputation.add(p, Reputation.GUARDS, -2 * n);
            }
            case "items_stolen" -> {
                Reputation.add(p, Reputation.TOWNSFOLK, -Math.max(1, n / 3));
                if (n >= 5) Reputation.add(p, Reputation.THIEVES_GUILD, n / 5);
            }
            case "pickpockets" -> {
                Reputation.add(p, Reputation.TOWNSFOLK, -n);
                Reputation.add(p, Reputation.THIEVES_GUILD, n);
            }
            default -> {
            }
        }
    }

    private static void rankChanged(ServerPlayer p, String faction, int was, int now) {
        String rep = repFaction(faction);
        if (was < 0 && now >= 0) {
            Reputation.add(p, rep, faction.equals("bards_college") ? 5 : 15);
            if (faction.equals(Reputation.IMPERIAL_LEGION)) {
                Reputation.add(p, Reputation.STORMCLOAKS, -30);
            } else if (faction.equals(Reputation.STORMCLOAKS)) {
                Reputation.add(p, Reputation.IMPERIAL_LEGION, -30);
                Reputation.add(p, Reputation.THALMOR, -40); // Talos worshippers, the lot of them
            }
        } else if (was >= 0 && now < 0) {
            Reputation.add(p, rep, -20);
        } else if (now > was) {
            Reputation.add(p, rep, 8 * (now - was));
        }
    }

    /** Negative standings creep back towards neutral. Called every second. */
    static void recover(ServerPlayer player) {
        int minutes = SocietyConfig.RECOVERY_MINUTES.get();
        if (minutes <= 0) return;
        int left = RECOVERY.getOrDefault(player.getUUID(), minutes * 60) - 1;
        if (left > 0) {
            RECOVERY.put(player.getUUID(), left);
            return;
        }
        RECOVERY.put(player.getUUID(), minutes * 60);
        PlayerData data = SkyData.get(player);
        CompoundTag rep = Reputation.tag(data);
        boolean changed = false;
        for (String f : rep.keySet().toArray(new String[0])) {
            int v = rep.getIntOr(f, 0);
            if (v < 0) {
                if (v + 1 == 0) rep.remove(f);
                else rep.putInt(f, v + 1);
                changed = true;
            }
        }
        if (changed) data.markDirty();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RECOVERY.remove(event.getEntity().getUUID());
        String suffix = "|" + event.getEntity().getUUID();
        LAST_HURT.keySet().removeIf(k -> k.endsWith(suffix));
    }
}

package com.skycraft.society;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.economy.Merchants;
import com.skycraft.network.SkyNetwork;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Overhead speech (contract 22): {@link #say(LivingEntity, Component)} shows a line above the speaker's head for
 * every player within {@link #HEAR_RANGE} blocks. Also drives ambient lines: villagers, guards and society NPCs
 * comment on players passing by (weapon drawn, sneaking, wanted, famous, faction, weather, time of day...).
 *
 * <p>Lines are translation keys {@code society.skycraft.bark.<category>.<n>}; {@link #LINES} holds each category's
 * line count.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Barks {
    public static final double HEAR_RANGE = 24;
    /** How long a line stays up, in ticks. */
    public static final int SHOW_TICKS = 80;
    /** Minimum ticks between two lines from the same speaker (any source). */
    private static final int SPEAKER_GAP = 50;
    /** Minimum ticks between two ambient lines aimed at the same player. */
    private static final int PLAYER_GAP = 160;

    /** Category -> number of lines in the language file. */
    public static final Map<String, Integer> LINES = new HashMap<>();

    static {
        // guards
        lines("guard.generic", 30);
        lines("guard.night", 3);
        lines("guard.weapon", 3);
        lines("guard.sneak", 3);
        lines("guard.wanted", 3);
        lines("guard.hero", 3);
        lines("guard.liked", 2);
        lines("guard.disliked", 3);
        // townsfolk
        lines("town.generic", 8);
        lines("town.morning", 3);
        lines("town.night", 3);
        lines("town.rain", 3);
        lines("town.snow", 2);
        lines("town.weapon", 3);
        lines("town.sneak", 3);
        lines("town.wanted", 3);
        lines("town.hero", 3);
        lines("town.liked", 3);
        lines("town.disliked", 3);
        lines("merchant.generic", 3);
        lines("merchant.liked", 3);
        // faction members
        lines("faction.companions", 2);
        lines("faction.college", 2);
        lines("faction.thieves_guild", 2);
        lines("faction.imperial_legion", 2);
        lines("faction.stormcloaks", 2);
        lines("faction.bards_college", 2);
        // roles
        lines("role.hunter", 3);
        lines("role.miner", 3);
        lines("role.lumberjack", 3);
        lines("role.bard", 3);
        lines("role.priest", 4);
        lines("role.beggar", 3);
        lines("role.mage", 3);
        lines("role.adventurer", 3);
        lines("role.thalmor", 3);
        lines("role.imperial_soldier", 3);
        lines("role.stormcloak_soldier", 3);
        lines("role.forsworn", 2);
        lines("role.vampire", 2);
        lines("role.necromancer", 2);
        lines("role.assassin", 2);
        lines("role.thug", 2);
        lines("role.courier", 2);
        lines("role.innkeeper", 3);
        lines("role.jarl", 3);
        lines("role.housecarl", 3);
        lines("role.thief", 2);
        lines("role.farmer", 3);
        lines("role.blacksmith", 3);
        lines("role.merchant", 3);
        lines("role.apothecary", 3);
        lines("role.fisher", 3);
        lines("role.butcher", 3);
        lines("role.scholar", 3);
        lines("role.steward", 3);
        lines("role.caravan_trader", 3);
        lines("role.citizen", 4);
        lines("role.child", 4);
        lines("prisoner", 3);
        // combat
        lines("attack.generic", 3);
        lines("attack.thalmor", 3);
        lines("attack.imperial_soldier", 2);
        lines("attack.stormcloak_soldier", 2);
        lines("attack.forsworn", 3);
        lines("attack.vampire", 3);
        lines("attack.necromancer", 3);
        lines("attack.assassin", 3);
        lines("attack.thug", 3);
        lines("attack.housecarl", 2);
        lines("attack.adventurer", 2);
        // events
        lines("priest_heal", 3);
        lines("courier_arrive", 1);
        lines("courier_bye", 2);
        lines("beggar_thanks", 2);
        lines("prisoner_freed", 2);
        lines("cart", 2);
        lines("thief_flee", 2);
        lines("guard_chase", 2);
        lines("escort", 2);
        lines("dragon", 2);
    }

    private static final Map<UUID, Long> SPEAKER_NEXT = new HashMap<>();
    private static final Map<UUID, Long> AMBIENT_NEXT = new HashMap<>();
    private static final Map<UUID, Long> PLAYER_NEXT = new HashMap<>();
    private static int budget;

    private Barks() {}

    private static void lines(String category, int count) {
        LINES.put(category, count);
    }

    // ------------------------------------------------------------------ API

    /** Shows {@code text} above {@code speaker}'s head for nearby players (~4 s). Server side; no-op on the client. */
    public static void say(LivingEntity speaker, Component text) {
        if (!(speaker.level() instanceof ServerLevel level)) return;
        SPEAKER_NEXT.put(speaker.getUUID(), level.getServer().getTickCount() + (long) SPEAKER_GAP);
        SocietyPackets.Bark msg = new SocietyPackets.Bark(speaker.getId(), text, SHOW_TICKS);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(speaker) <= HEAR_RANGE * HEAR_RANGE) SkyNetwork.sendToPlayer(p, msg);
        }
    }

    /** Says a random line of {@code category} (see {@link #LINES}); ignored if the speaker spoke a moment ago. */
    public static void sayLine(LivingEntity speaker, String category) {
        if (!(speaker.level() instanceof ServerLevel level)) return;
        Long next = SPEAKER_NEXT.get(speaker.getUUID());
        if (next != null && level.getServer().getTickCount() < next) return;
        Component line = line(category, speaker.getRandom(), new Object[0]);
        if (line != null) say(speaker, line);
    }

    /** Says a random line of {@code category} with format arguments, regardless of the speaker's last line. */
    public static void sayLineNow(LivingEntity speaker, String category, Object... args) {
        Component line = line(category, speaker.getRandom(), args);
        if (line != null) say(speaker, line);
    }

    private static Component line(String category, RandomSource random, Object[] args) {
        Integer count = LINES.get(category);
        if (count == null || count <= 0) return null;
        return Component.translatable("society.skycraft.bark." + category + "." + random.nextInt(count), args);
    }

    /** Battle cry when an NPC turns on a player. */
    public static void combat(NpcEntity npc, Player target) {
        String cat = "attack." + npc.role().id;
        if (!LINES.containsKey(cat)) {
            if (!npc.role().combatant) return;
            cat = "attack.generic";
        }
        sayLineNow(npc, cat, target.getDisplayName());
    }

    // ------------------------------------------------------------------ ambient lines

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % 20 == 0) budget = SocietyConfig.MAX_BARKS_PER_SECOND.get();
        if (tick % 1200 == 0) prune(tick);
        if (tick % 10 != 5 || !SocietyConfig.AMBIENT_BARKS.get()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (budget <= 0) return;
            if (player.isSpectator()) continue;
            Long pn = PLAYER_NEXT.get(player.getUUID());
            if (pn != null && tick < pn) continue;
            try {
                if (ambient(player, tick)) {
                    budget--;
                    PLAYER_NEXT.put(player.getUUID(), (long) tick + PLAYER_GAP);
                }
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft society: ambient bark failed", e);
            }
        }
    }

    private static void prune(int tick) {
        SPEAKER_NEXT.values().removeIf(v -> v < tick);
        AMBIENT_NEXT.values().removeIf(v -> v < tick);
        PLAYER_NEXT.values().removeIf(v -> v < tick);
    }

    private static boolean isSpeaker(Mob m) {
        if (!m.isAlive() || m.isSleeping() || m.getTarget() != null || m.isNoAi()) return false;
        if (m instanceof AbstractVillager v) return !v.isBaby();
        return m instanceof NpcEntity || Crimes.isGuard(m);
    }

    private static boolean ambient(ServerPlayer player, int tick) {
        double range = SocietyConfig.BARK_RANGE.get();
        List<Mob> near = player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(range), Barks::isSpeaker);
        if (near.isEmpty()) return false;
        RandomSource random = player.getRandom();
        int start = random.nextInt(near.size());
        for (int k = 0; k < near.size(); k++) {
            Mob m = near.get((start + k) % near.size());
            Long next = AMBIENT_NEXT.get(m.getUUID());
            if (next != null && tick < next) continue;
            Long gap = SPEAKER_NEXT.get(m.getUUID());
            if (gap != null && tick < gap) continue;
            if (m.distanceToSqr(player) > range * range || !m.hasLineOfSight(player)) continue;
            Component override = ContextualDialogue.ambientBarkOverride(player, m, random);
            if (override != null) {
                int cooldown = SocietyConfig.between(SocietyConfig.BARK_COOLDOWN_MIN.get(), SocietyConfig.BARK_COOLDOWN_MAX.get(), random);
                AMBIENT_NEXT.put(m.getUUID(), (long) tick + cooldown * 20L);
                m.getLookControl().setLookAt(player, 30f, 30f);
                say(m, override);
                return true;
            }
            String category = choose(player, m, random);
            if (category == null) continue;
            int cooldown = SocietyConfig.between(SocietyConfig.BARK_COOLDOWN_MIN.get(), SocietyConfig.BARK_COOLDOWN_MAX.get(), random);
            AMBIENT_NEXT.put(m.getUUID(), (long) tick + cooldown * 20L);
            m.getLookControl().setLookAt(player, 30f, 30f);
            Component line;
            if ("town.rumor".equals(category)) {
                Component loc = com.skycraft.world.Discovery.revealNear(player, player.blockPosition(), 1500);
                if (loc != null) {
                    line = Component.translatable("society.skycraft.bark.town.rumor", loc);
                } else {
                    line = line("town.generic", random, new Object[]{player.getDisplayName()});
                }
            } else {
                line = line(category, random, new Object[]{player.getDisplayName()});
            }
            if (line != null) say(m, line);
            return true;
        }
        return false;
    }

    /** Picks what {@code speaker} says to {@code player}, most pressing topic first. */
    static String choose(ServerPlayer player, Mob speaker, RandomSource r) {
        boolean guard = Crimes.isGuard(speaker);
        NpcEntity npc = speaker instanceof NpcEntity n ? n : null;
        if (npc != null) {
            if (npc.isPrisoner()) return "prisoner";
            if (npc.isHostileTo(player)) return null;
        }
        String group = guard ? "guard" : "town";
        boolean soldier = npc != null && (npc.role().combatant && !npc.role().civilian || npc.role() == NpcRole.HOUSECARL);

        if (!player.isCreative() && Bounty.current(player) > 0 && (guard || r.nextBoolean())) return group + ".wanted";
        if (weaponDrawn(player) && !soldier && r.nextInt(3) > 0) return group + ".weapon";
        if (player.isCrouching() && r.nextInt(3) > 0) return group + ".sneak";
        if (!guard && r.nextInt(7) == 0) return "town.rumor";
        if (npc != null && r.nextInt(100) < 55) return "role." + npc.role().id;
        if (famous(player) && r.nextInt(4) == 0) return group + ".hero";
        String faction = memberFaction(player, r);
        if (faction != null && r.nextInt(4) == 0) return "faction." + faction;

        int townsfolk = Reputation.get(player, Reputation.TOWNSFOLK);
        if (!guard && npc == null && Merchants.isMerchant(speaker) && r.nextBoolean()) {
            return townsfolk >= 30 ? "merchant.liked" : "merchant.generic";
        }
        if (townsfolk >= 30 && r.nextInt(3) == 0) return group + ".liked";
        if (townsfolk <= -20 && r.nextBoolean()) return group + ".disliked";

        Level level = speaker.level();
        BlockPos pos = speaker.blockPosition();
        if (level.isRaining() && r.nextInt(3) == 0 && level.canSeeSky(pos)) {
            Biome.Precipitation p = level.getBiome(pos).value().getPrecipitationAt(pos);
            if (p == Biome.Precipitation.RAIN) return "town.rain";
            if (p == Biome.Precipitation.SNOW) return "town.snow";
        }
        long time = level.getDayTime() % 24000L;
        if (time > 13000 && time < 23000 && r.nextInt(3) == 0) return group + ".night";
        if (!guard && time < 3000 && r.nextInt(3) == 0) return "town.morning";
        return group + ".generic";
    }

    public static boolean weaponDrawn(Player player) {
        Item item = player.getMainHandItem().getItem();
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof ProjectileWeaponItem || item instanceof TridentItem) return true;
        return item instanceof TieredItem && !(item instanceof DiggerItem);
    }

    /** Dragon slayers and veterans get recognized. */
    public static boolean famous(Player player) {
        PlayerData data = SkyData.get(player);
        return data.getLevel() >= 20 || data.getStat("dragon_souls") > 0 || data.module("magic").getInt("dragon_souls") > 0;
    }

    private static final String[] MEMBER_LINES = {"companions", "college", "thieves_guild", "imperial_legion", "stormcloaks", "bards_college"};

    private static String memberFaction(Player player, RandomSource r) {
        var factions = SkyData.get(player).module("quest").getCompound("factions");
        int start = r.nextInt(MEMBER_LINES.length);
        for (int i = 0; i < MEMBER_LINES.length; i++) {
            String f = MEMBER_LINES[(start + i) % MEMBER_LINES.length];
            if (factions.contains(f)) return f;
        }
        return null;
    }
}

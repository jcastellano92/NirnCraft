package com.skycraft.society;

import com.skycraft.combat.Sheathe;
import com.skycraft.core.Holds;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import com.skycraft.crime.Bounty;
import com.skycraft.curse.Curses;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.economy.Merchants;
import com.skycraft.fire.aftermath.HelgenFolk;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.survival.Diseases;
import com.skycraft.survival.inn.Innkeepers;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Dynamic contextual dialogue engine. Evaluates world events (such as the Helgen dragon attack, dragon slaying,
 * and civil war state), time of day, weather, biome, province/hold/town, player race/status/curses/skills/factions,
 * and NPC role, job, mood, relationship rank, family ties, age, and personality to produce living greetings,
 * ambient barks, and situational conversation responses.
 */
public final class ContextualDialogue {

    /** All 9 Skyrim holds that have dedicated deed-keyed reactive dialogue lines (G11: >= 12 per hold). */
    public static final List<String> SKYRIM_HOLDS = List.of(
            "whiterun", "haafingar", "eastmarch", "the_rift", "the_reach",
            "falkreath", "hjaalmarch", "the_pale", "winterhold"
    );

    /** Canonical player deeds recognized by guards, innkeepers, and court NPCs (F-REACT). */
    public static final List<String> DEEDS = List.of(
            "dragon_slayer", "companion_member", "college_member", "thieves_member",
            "brotherhood_member", "civil_war_hero", "high_bounty_cleared", "local_hero",
            "hold_story", "bounty_hunter", "master_craftsman", "barrow_delver"
    );

    public enum Personality {
        STOIC, WARM, PROUD, CAUTIOUS, CURIOUS;

        public static Personality of(LivingEntity npc) {
            int idx = Math.floorMod(npc.getUUID().hashCode() ^ 0x9E3779B9, values().length);
            return values()[idx];
        }
    }

    private ContextualDialogue() {}

    /**
     * Resolves a reactive dialogue translation key {@code react.<region>.<deed>} with fallback to
     * {@code react.generic.<deed>} when the region is outside the 9 Skyrim holds or unspecified (F-REACT / R3d-1.3).
     */
    public static String resolveReactiveKey(@Nullable String regionOrHold, @Nullable String deed) {
        String cleanDeed = (deed != null && DEEDS.contains(deed)) ? deed : "local_hero";
        String reg = normalizeHold(regionOrHold);
        if (reg != null && SKYRIM_HOLDS.contains(reg)) {
            return "react." + reg + "." + cleanDeed;
        }
        return "react.generic." + cleanDeed;
    }

    @Nullable
    public static String normalizeHold(@Nullable String regionOrHold) {
        if (regionOrHold == null || regionOrHold.isBlank()) return null;
        String s = regionOrHold.trim().toLowerCase(Locale.ROOT);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return s;
    }

    /** True if {@code speaker} is a guard, innkeeper, or court NPC who reacts to the player's regional/faction deeds. */
    public static boolean isReactiveSpeaker(@Nullable LivingEntity speaker) {
        if (speaker == null) return false;
        if (com.skycraft.crime.Crimes.isGuard(speaker) || Innkeepers.isInnkeeper(speaker)) return true;
        if (speaker instanceof NpcEntity npc) {
            NpcRole role = npc.role();
            if (role == NpcRole.INNKEEPER || role == NpcRole.IMPERIAL_SOLDIER || role == NpcRole.STORMCLOAK_SOLDIER) {
                return true;
            }
            if (Courts.isCourtRole(role) || Courts.isCourtJob(Merchants.jobId(npc))) {
                return true;
            }
        }
        return false;
    }

    /** Returns the list of deeds the player has earned in {@code hold} and across the world. */
    public static List<String> activeDeeds(ServerPlayer player, @Nullable String hold) {
        PlayerData data = SkyData.get(player);
        CompoundTag quests = data.module("quest").getCompound("completed");
        List<String> out = new java.util.ArrayList<>();

        if (data.getStat("dragon_souls") > 0 || data.module("magic").getInt("dragon_souls") > 0
                || quests.getBoolean("mq_04_dragon_rising")) {
            out.add("dragon_slayer");
        }
        if (Reputation.get(player, Reputation.COMPANIONS) >= 20 || hasCompletedPrefix(quests, "fc_comp_")) {
            out.add("companion_member");
        }
        if (Reputation.get(player, Reputation.COLLEGE) >= 20 || hasCompletedPrefix(quests, "fc_col_")) {
            out.add("college_member");
        }
        if (Reputation.get(player, Reputation.THIEVES_GUILD) >= 20 || hasCompletedPrefix(quests, "fc_tg_")) {
            out.add("thieves_member");
        }
        if (Reputation.get(player, Reputation.DARK_BROTHERHOOD) >= 20 || hasCompletedPrefix(quests, "fc_db_")) {
            out.add("brotherhood_member");
        }
        if (Reputation.get(player, Reputation.IMPERIAL_LEGION) >= 35
                || Reputation.get(player, Reputation.STORMCLOAKS) >= 35
                || hasCompletedPrefix(quests, "cw_")) {
            out.add("civil_war_hero");
        }
        if (data.getStat("bounties_cleared") > 0 || data.getStat("fines_paid") > 0) {
            out.add("high_bounty_cleared");
        }
        String cleanHold = normalizeHold(hold);
        if (cleanHold != null && hasCompletedPrefix(quests, "hs_" + cleanHold)) {
            out.add("hold_story");
        }
        if (Reputation.get(player, Reputation.TOWNSFOLK) >= 30 || hasCompletedPrefix(quests, "side_")) {
            out.add("local_hero");
        }
        if (data.getStat("bounties_completed") > 0 || hasCompletedPrefix(quests, "rad_")) {
            out.add("bounty_hunter");
        }
        if (data.getSkill(Skill.SMITHING) >= 60 || data.getSkill(Skill.ALCHEMY) >= 60 || data.getSkill(Skill.ENCHANTING) >= 60) {
            out.add("master_craftsman");
        }
        if (data.getStat("dungeons_cleared") > 0 || data.getStat("places_discovered") >= 5) {
            out.add("barrow_delver");
        }
        return out;
    }

    private static boolean hasCompletedPrefix(CompoundTag completed, String prefix) {
        for (String k : completed.getAllKeys()) {
            if (k.startsWith(prefix) && completed.getBoolean(k)) return true;
        }
        return false;
    }

    /** Resolves a reactive line for a guard, innkeeper, or court NPC addressing {@code player}, or null if none applies. */
    @Nullable
    public static Component reactiveLine(ServerPlayer player, LivingEntity speaker) {
        if (!isReactiveSpeaker(speaker)) return null;
        Region region = Cultures.regionAt(speaker.level(), speaker.blockPosition());
        String hold = region != null ? region.shortId() : Holds.holdAt(speaker.level(), speaker.blockPosition());
        List<String> deeds = activeDeeds(player, hold);
        if (deeds.isEmpty()) return null;
        String deed = deeds.get(Math.floorMod(speaker.getUUID().hashCode() + (int) (speaker.level().getDayTime() / 1200L), deeds.size()));
        String key = resolveReactiveKey(hold, deed);
        return Component.translatable(key, player.getName());
    }

    /**
     * Evaluates contextual overrides for an NPC greeting when a player opens dialogue.
     * Returns null when the standard role/relationship greeting should be used.
     */
    @Nullable
    public static Component greetingOverride(ServerPlayer player, NpcEntity npc) {
        // 1. Helgen aftermath: every non-quest NPC in ruined Helgen (including shopkeepers) speaks of the attack & fear
        if (HelgenFolk.isHelgenEventNpc(player, npc)) {
            return HelgenFolk.line(player, npc);
        }

        // 2. Innkeeper when the player already has an active room rented at this inn
        if (Innkeepers.isInnkeeper(npc) && Innkeepers.hasOwnRoom(player, npc)) {
            return Component.translatable("survival.skycraft.inn.greet_rented");
        }

        RandomSource r = npc.getRandom();
        NpcRole role = npc.role();
        if (npc.isPrisoner()) return null;

        // 2b. F-REACT (R3d-1.3): guards, innkeepers, and court NPCs react to the player's deeds in the hold
        if (isReactiveSpeaker(npc) && r.nextInt(3) == 0) {
            Component react = reactiveLine(player, npc);
            if (react != null) return react;
        }

        if (!role.civilian) return null;

        // 3. Curses & visible afflictions (Sanies Lupinus, Beast Blood, Vampirism, active diseases)
        if (r.nextInt(4) == 0) {
            if (Curses.hasSaniesLupinus(player)) {
                return Component.translatable("society.skycraft.context.greet.sanies." + r.nextInt(2));
            }
            if (Curses.isWerewolf(player)) {
                return Component.translatable("society.skycraft.context.greet.werewolf." + r.nextInt(2));
            }
            if (Curses.isVampire(player)) {
                return Component.translatable("society.skycraft.context.greet.vampire." + r.nextInt(2));
            }
            if (!Diseases.active(player).isEmpty()) {
                return Component.translatable("society.skycraft.context.greet.diseased." + r.nextInt(2));
            }
        }

        // 4. Drawn weapon reaction for cautious/civilian townsfolk
        if (!Sheathe.isSheathed(player) && Barks.weaponDrawn(player) && r.nextInt(3) == 0) {
            return Component.translatable("society.skycraft.context.greet.armed." + r.nextInt(2));
        }

        // 5. Recent world / quest milestones (Dragonborn recognition)
        PlayerData data = SkyData.get(player);
        boolean dragonSlayer = data.getStat("dragon_souls") > 0 || data.module("magic").getInt("dragon_souls") > 0;
        if (dragonSlayer && r.nextInt(5) == 0) {
            return Component.translatable("society.skycraft.context.greet.dragonborn." + r.nextInt(2), player.getName());
        }

        // 6. Kinship greeting when player and NPC share the same race/heritage
        Race playerRace = data.getRace();
        if (playerRace != null && npc.race() != null
                && playerRace.name().equalsIgnoreCase(npc.race().name())
                && Relationships.rank(npc, player).ordinal() >= Relationships.Rank.STRANGER.ordinal()
                && r.nextInt(4) == 0) {
            String key = switch (npc.race()) {
                case NORD -> "nord";
                case DUNMER -> "dunmer";
                case KHAJIIT -> "khajiit";
                case ARGONIAN -> "argonian";
                case ORC -> "orc";
                case ALTMER, BOSMER -> "mer";
                default -> "kin";
            };
            return Component.translatable("society.skycraft.context.greet.kin." + key, player.getName());
        }

        // 7. Time of day, weather, and biome atmosphere
        Level level = npc.level();
        BlockPos pos = npc.blockPosition();
        if (level.isRaining() && level.canSeeSky(pos) && r.nextInt(4) == 0) {
            Biome.Precipitation p = level.getBiome(pos).value().getPrecipitationAt(pos);
            if (p == Biome.Precipitation.SNOW) {
                return Component.translatable("society.skycraft.context.greet.snow." + r.nextInt(2));
            }
            if (p == Biome.Precipitation.RAIN) {
                return Component.translatable("society.skycraft.context.greet.rain." + r.nextInt(2));
            }
        }
        long time = level.getDayTime() % 24000L;
        if (time >= 13500L && time < 22500L && r.nextInt(4) == 0) {
            return Component.translatable("society.skycraft.context.greet.night." + r.nextInt(2));
        }

        // 8. High skill mastery recognition
        if (r.nextInt(6) == 0) {
            Skill top = highestSkill(data);
            if (top != null && data.getSkill(top) >= 60) {
                return Component.translatable("society.skycraft.context.greet.skill",
                        Component.translatable("skill.skycraft." + top.id()));
            }
        }

        return null;
    }

    /**
     * Evaluates an ambient bark override for a speaker near the player.
     * In Helgen, all non-quest speakers bark about the dragon attack and fear instead of generic town/farm lines.
     */
    @Nullable
    public static Component ambientBarkOverride(ServerPlayer player, Mob speaker, RandomSource r) {
        if (HelgenFolk.isHelgenEventNpc(player, speaker)) {
            return HelgenFolk.line(player, speaker);
        }
        if (speaker instanceof NpcEntity npc && npc.isHostileTo(player)) return null;

        if (isReactiveSpeaker(speaker) && r.nextInt(4) == 0) {
            Component react = reactiveLine(player, speaker);
            if (react != null) return react;
        }

        if (r.nextInt(5) == 0) {
            if (Curses.hasSaniesLupinus(player)) {
                return Component.translatable("society.skycraft.context.greet.sanies." + r.nextInt(2));
            }
            if (Curses.isWerewolf(player)) {
                return Component.translatable("society.skycraft.context.greet.werewolf." + r.nextInt(2));
            }
            if (Curses.isVampire(player)) {
                return Component.translatable("society.skycraft.context.greet.vampire." + r.nextInt(2));
            }
        }
        return null;
    }

    /** Adds a contextual local-life / current-events dialogue option for civilian townsfolk outside ruined Helgen. */
    public static void addContextualOptions(ServerPlayer player, NpcEntity npc, List<DialogueOption> out) {
        if (HelgenFolk.inHelgen(npc) || !npc.role().civilian || npc.isChild() || npc.isPrisoner()) return;
        out.add(new DialogueOption("society.context",
                Component.translatable("dialogue.skycraft.society.context"),
                525,
                (p, n) -> {
                    if (n instanceof NpcEntity target) {
                        Dialogue.open(p, target, buildContextualReflection(p, target));
                    }
                }));
        if (isReactiveSpeaker(npc) && !activeDeeds(player, Holds.holdAt(npc.level(), npc.blockPosition())).isEmpty()) {
            out.add(new DialogueOption("society.react",
                    Component.translatable("dialogue.skycraft.society.react"),
                    515,
                    (p, n) -> {
                        Component react = reactiveLine(p, n);
                        if (react != null) {
                            Dialogue.open(p, n, react);
                        } else if (n instanceof NpcEntity target) {
                            Dialogue.open(p, target, buildContextualReflection(p, target));
                        }
                    }));
        }
    }

    /** Builds a multi-faceted response combining location/biome, time/weather, NPC personality/job, and world state. */
    public static Component buildContextualReflection(ServerPlayer player, NpcEntity npc) {
        Level level = npc.level();
        BlockPos pos = npc.blockPosition();
        Region region = Cultures.regionAt(level, pos);
        Place nearest = Places.nearest(null, pos.getX(), pos.getZ(), 256).orElse(null);
        Component placeName = nearest != null ? nearest.name() : region.name();
        String hold = Holds.holdAt(level, pos);
        int bounty = Bounty.get(player, hold);

        if (bounty >= 100) {
            return Component.translatable("society.skycraft.context.say.bounty", placeName);
        }

        if (isReactiveSpeaker(npc) && npc.getRandom().nextBoolean()) {
            Component react = reactiveLine(player, npc);
            if (react != null) return react;
        }

        PlayerData data = SkyData.get(player);
        CompoundTag quests = data.module("quest").getCompound("completed");
        if (quests.getBoolean("mq_04_dragon_rising") || data.getStat("dragon_souls") > 0) {
            if (npc.getRandom().nextBoolean()) {
                return Component.translatable("society.skycraft.context.say.dragons", placeName);
            }
        }

        Personality p = Personality.of(npc);
        long time = level.getDayTime() % 24000L;
        boolean night = time >= 13000L && time < 23000L;
        float temp = level.getBiome(pos).value().getBaseTemperature();

        if (level.isRaining() && level.canSeeSky(pos)) {
            return Component.translatable("society.skycraft.context.say.weather", placeName);
        }
        if (night) {
            return Component.translatable("society.skycraft.context.say.night", placeName);
        }
        if (temp < 0.2f && npc.getRandom().nextInt(3) == 0) {
            return Component.translatable("society.skycraft.context.say.cold", placeName);
        }
        if (temp > 1.2f && npc.getRandom().nextInt(3) == 0) {
            return Component.translatable("society.skycraft.context.say.arid", placeName);
        }

        String job = Merchants.jobId(npc);
        if (!job.isEmpty() && !job.equals("citizen") && npc.getRandom().nextBoolean()) {
            return Component.translatable("society.skycraft.context.say.personality." + p.name().toLowerCase(Locale.ROOT), placeName);
        }
        return Component.translatable("society.skycraft.context.say.personality." + p.name().toLowerCase(Locale.ROOT), placeName);
    }

    @Nullable
    private static Skill highestSkill(PlayerData data) {
        Skill best = null;
        int bestLvl = 0;
        for (Skill s : Skill.values()) {
            int lvl = data.getSkill(s);
            if (lvl > bestLvl) {
                bestLvl = lvl;
                best = s;
            }
        }
        return best;
    }
}

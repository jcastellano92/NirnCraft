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

    public enum Personality {
        STOIC, WARM, PROUD, CAUTIOUS, CURIOUS;

        public static Personality of(LivingEntity npc) {
            int idx = Math.floorMod(npc.getUUID().hashCode() ^ 0x9E3779B9, values().length);
            return values()[idx];
        }
    }

    private ContextualDialogue() {}

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
        if (!role.civilian || npc.isPrisoner()) return null;

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

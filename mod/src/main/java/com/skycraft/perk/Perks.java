package com.skycraft.perk;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every perk of every constellation. Effects are implemented by the system owning the skill; they query
 * perks with {@link #rank(Player, String)} / {@link #has(Player, String)} using the ids defined here.
 */
public final class Perks {
    private static final Map<String, Perk> BY_ID = new LinkedHashMap<>();
    private static final Map<Skill, List<Perk>> BY_SKILL = new EnumMap<>(Skill.class);

    private Perks() {}

    public static Perk get(String id) {
        return BY_ID.get(id);
    }

    public static List<Perk> of(Skill skill) {
        return BY_SKILL.getOrDefault(skill, Collections.emptyList());
    }

    public static Iterable<Perk> all() {
        return BY_ID.values();
    }

    public static int rank(Player player, String id) {
        return SkyData.get(player).getPerkRank(id);
    }

    public static boolean has(Player player, String id) {
        return SkyData.get(player).hasPerk(id);
    }

    /** Same as {@link #rank(Player, String)} for handlers that already resolved the player's data (avoids a capability lookup per call). */
    public static int rank(PlayerData data, String id) {
        return data.getPerkRank(id);
    }

    /** Same as {@link #has(Player, String)} for handlers that already resolved the player's data. */
    public static boolean has(PlayerData data, String id) {
        return data.hasPerk(id);
    }

    /** Whether the player may buy the next rank of the perk right now (ignoring perk points). */
    public static boolean canUnlock(PlayerData data, Perk perk) {
        int current = data.getPerkRank(perk.id());
        if (current >= perk.maxRank()) return false;
        if (data.getSkill(perk.skill()) < perk.requiredLevel(current + 1)) return false;
        if (perk.parents().isEmpty() || current > 0) return true;
        for (String parent : perk.parents()) {
            if (data.hasPerk(parent)) return true;
        }
        return false;
    }

    private static void p(Skill skill, String name, float x, float y, int[] levels, String... parents) {
        String id = skill.id() + "." + name;
        List<String> parentIds = new ArrayList<>();
        for (String par : parents) parentIds.add(par.contains(".") ? par : skill.id() + "." + par);
        Perk perk = new Perk(id, skill, levels, List.copyOf(parentIds), x, y);
        BY_ID.put(id, perk);
        BY_SKILL.computeIfAbsent(skill, k -> new ArrayList<>()).add(perk);
    }

    private static int[] lv(int... levels) {
        return levels;
    }

    private static final int[] FIVE = {0, 20, 40, 60, 80};

    /** Adds the Novice..Master cost-reduction chain shared by every school of magic and lockpicking. */
    private static void tiers(Skill skill, String suffix) {
        String[] names = {"novice", "apprentice", "adept", "expert", "master"};
        int[] req = {0, 25, 50, 75, 100};
        for (int i = 0; i < names.length; i++) {
            if (i == 0) p(skill, names[i] + "_" + suffix, 0f, 0f, lv(req[i]));
            else p(skill, names[i] + "_" + suffix, 0f, i / 4f, lv(req[i]), names[i - 1] + "_" + suffix);
        }
    }

    static {
        // ---------------------------------------------------------------- One-Handed
        Skill s = Skill.ONE_HANDED;
        p(s, "armsman", 0f, 0f, FIVE);
        p(s, "fighting_stance", 0.05f, 0.3f, lv(20), "armsman");
        p(s, "hack_and_slash", -0.75f, 0.35f, lv(30, 60, 90), "armsman");
        p(s, "bone_breaker", -0.4f, 0.45f, lv(30, 60, 90), "armsman");
        p(s, "bladesman", 0.6f, 0.35f, lv(30, 60, 90), "armsman");
        p(s, "dual_flurry", -0.6f, 0.65f, lv(30, 50), "armsman");
        p(s, "savage_strike", 0.1f, 0.6f, lv(50), "fighting_stance");
        p(s, "critical_charge", 0.45f, 0.7f, lv(50), "fighting_stance");
        p(s, "dual_savagery", -0.35f, 0.85f, lv(70), "dual_flurry");
        p(s, "paralyzing_strike", 0.1f, 1f, lv(100), "critical_charge", "savage_strike");

        // ---------------------------------------------------------------- Two-Handed
        s = Skill.TWO_HANDED;
        p(s, "barbarian", 0f, 0f, FIVE);
        p(s, "champions_stance", 0f, 0.3f, lv(20), "barbarian");
        p(s, "limbsplitter", -0.7f, 0.35f, lv(30, 60, 90), "barbarian");
        p(s, "skullcrusher", -0.35f, 0.5f, lv(30, 60, 90), "barbarian");
        p(s, "deep_wounds", 0.65f, 0.35f, lv(30, 60, 90), "barbarian");
        p(s, "devastating_blow", -0.1f, 0.6f, lv(50), "champions_stance");
        p(s, "great_critical_charge", 0.4f, 0.65f, lv(50), "champions_stance");
        p(s, "sweep", 0.2f, 0.8f, lv(70), "devastating_blow", "great_critical_charge");
        p(s, "warmaster", 0.1f, 1f, lv(100), "sweep");

        // ---------------------------------------------------------------- Archery
        s = Skill.ARCHERY;
        p(s, "overdraw", 0f, 0f, FIVE);
        p(s, "eagle_eye", -0.5f, 0.3f, lv(30), "overdraw");
        p(s, "critical_shot", 0.5f, 0.3f, lv(30, 60, 90), "overdraw");
        p(s, "steady_hand", -0.7f, 0.55f, lv(40, 60), "eagle_eye");
        p(s, "power_shot", -0.2f, 0.55f, lv(50), "eagle_eye");
        p(s, "hunters_discipline", 0.55f, 0.55f, lv(50), "critical_shot");
        p(s, "ranger", 0.6f, 0.78f, lv(60), "hunters_discipline");
        p(s, "quick_shot", -0.15f, 0.8f, lv(70), "power_shot");
        p(s, "bullseye", 0.1f, 1f, lv(100), "quick_shot", "ranger");

        // ---------------------------------------------------------------- Block
        s = Skill.BLOCK;
        p(s, "shield_wall", 0f, 0f, FIVE);
        p(s, "quick_reflexes", -0.55f, 0.35f, lv(30), "shield_wall");
        p(s, "deflect_arrows", 0.5f, 0.3f, lv(30), "shield_wall");
        p(s, "power_bash", 0f, 0.35f, lv(30), "shield_wall");
        p(s, "elemental_protection", 0.6f, 0.6f, lv(50), "deflect_arrows");
        p(s, "deadly_bash", 0f, 0.6f, lv(50), "power_bash");
        p(s, "block_runner", 0.45f, 0.82f, lv(70), "elemental_protection");
        p(s, "disarming_bash", -0.4f, 0.8f, lv(70), "deadly_bash");
        p(s, "shield_charge", 0.1f, 1f, lv(100), "block_runner");

        // ---------------------------------------------------------------- Heavy Armor
        s = Skill.HEAVY_ARMOR;
        p(s, "juggernaut", 0f, 0f, FIVE);
        p(s, "fists_of_steel", -0.6f, 0.3f, lv(30), "juggernaut");
        p(s, "well_fitted", 0.4f, 0.3f, lv(30), "juggernaut");
        p(s, "cushioned", -0.55f, 0.6f, lv(50), "fists_of_steel");
        p(s, "tower_of_strength", 0.45f, 0.55f, lv(50), "well_fitted");
        p(s, "conditioning", -0.3f, 0.85f, lv(70), "cushioned");
        p(s, "matching_set", 0.5f, 0.8f, lv(70), "tower_of_strength");
        p(s, "reflect_blows", 0.15f, 1f, lv(100), "matching_set");

        // ---------------------------------------------------------------- Smithing
        s = Skill.SMITHING;
        p(s, "steel_smithing", 0f, 0f, lv(0));
        p(s, "elven_smithing", -0.5f, 0.3f, lv(30), "steel_smithing");
        p(s, "dwarven_smithing", 0.5f, 0.3f, lv(30), "steel_smithing");
        p(s, "advanced_armors", -0.6f, 0.55f, lv(50), "elven_smithing");
        p(s, "orcish_smithing", 0.6f, 0.5f, lv(50), "dwarven_smithing");
        p(s, "arcane_blacksmith", 0f, 0.5f, lv(60), "steel_smithing");
        p(s, "glass_smithing", -0.5f, 0.75f, lv(70), "advanced_armors");
        p(s, "ebony_smithing", 0.55f, 0.7f, lv(80), "orcish_smithing");
        p(s, "daedric_smithing", 0.4f, 0.88f, lv(90), "ebony_smithing");
        p(s, "dragon_armor", 0f, 1f, lv(100), "daedric_smithing", "glass_smithing");

        // ---------------------------------------------------------------- Destruction
        s = Skill.DESTRUCTION;
        tiers(s, "destruction");
        p(s, "dual_casting", -0.3f, 0.2f, lv(20), "novice_destruction");
        p(s, "augmented_flames", -0.7f, 0.3f, lv(30, 60), "novice_destruction");
        p(s, "augmented_frost", -0.4f, 0.4f, lv(30, 60), "novice_destruction");
        p(s, "augmented_shock", 0.5f, 0.35f, lv(30, 60), "novice_destruction");
        p(s, "rune_master", 0.4f, 0.1f, lv(40), "novice_destruction");
        p(s, "impact", 0.5f, 0.6f, lv(40), "novice_destruction");
        p(s, "intense_flames", -0.75f, 0.6f, lv(50), "augmented_flames");
        p(s, "deep_freeze", -0.45f, 0.7f, lv(60), "augmented_frost");
        p(s, "disintegrate", 0.55f, 0.85f, lv(70), "augmented_shock");

        // ---------------------------------------------------------------- Restoration
        s = Skill.RESTORATION;
        tiers(s, "restoration");
        p(s, "dual_casting", -0.3f, 0.2f, lv(20), "novice_restoration");
        p(s, "regeneration", -0.5f, 0.2f, lv(20), "novice_restoration");
        p(s, "respite", 0.5f, 0.3f, lv(40), "novice_restoration");
        p(s, "recovery", 0.6f, 0.55f, lv(30, 60), "novice_restoration");
        p(s, "ward_absorb", -0.5f, 0.5f, lv(60), "regeneration");
        p(s, "necromage", -0.55f, 0.75f, lv(70), "regeneration");
        p(s, "avoid_death", 0.55f, 0.85f, lv(90), "recovery");

        // ---------------------------------------------------------------- Alteration
        s = Skill.ALTERATION;
        tiers(s, "alteration");
        p(s, "dual_casting", -0.3f, 0.2f, lv(20), "novice_alteration");
        p(s, "mage_armor", -0.5f, 0.35f, lv(30, 50, 70), "novice_alteration");
        p(s, "magic_resistance", 0.5f, 0.35f, lv(30, 50, 70), "novice_alteration");
        p(s, "stability", -0.5f, 0.7f, lv(70), "mage_armor");
        p(s, "atronach", 0.5f, 0.9f, lv(100), "magic_resistance");

        // ---------------------------------------------------------------- Conjuration
        s = Skill.CONJURATION;
        tiers(s, "conjuration");
        p(s, "dual_casting", -0.3f, 0.2f, lv(20), "novice_conjuration");
        p(s, "mystic_binding", -0.6f, 0.2f, lv(20), "novice_conjuration");
        p(s, "soul_stealer", -0.7f, 0.45f, lv(30), "mystic_binding");
        p(s, "oblivion_binding", -0.6f, 0.7f, lv(50), "soul_stealer");
        p(s, "summoner", 0.4f, 0.3f, lv(30, 70), "novice_conjuration");
        p(s, "atromancy", 0.6f, 0.5f, lv(40), "summoner");
        p(s, "necromancy", 0.3f, 0.55f, lv(40), "novice_conjuration");
        p(s, "dark_souls", 0.35f, 0.8f, lv(70), "necromancy");
        p(s, "twin_souls", 0.15f, 1f, lv(100), "dark_souls", "atromancy");

        // ---------------------------------------------------------------- Illusion
        s = Skill.ILLUSION;
        tiers(s, "illusion");
        p(s, "dual_casting", -0.3f, 0.2f, lv(20), "novice_illusion");
        p(s, "animage", -0.5f, 0.25f, lv(20), "novice_illusion");
        p(s, "hypnotic_gaze", 0.5f, 0.3f, lv(30), "novice_illusion");
        p(s, "kindred_mage", -0.55f, 0.5f, lv(40), "animage");
        p(s, "aspect_of_terror", 0.55f, 0.55f, lv(50), "hypnotic_gaze");
        p(s, "quiet_casting", -0.6f, 0.75f, lv(50), "kindred_mage");
        p(s, "rage", 0.5f, 0.75f, lv(70), "aspect_of_terror");
        p(s, "master_of_the_mind", 0.2f, 0.95f, lv(90), "rage", "quiet_casting");

        // ---------------------------------------------------------------- Enchanting
        s = Skill.ENCHANTING;
        p(s, "enchanter", 0f, 0f, FIVE);
        p(s, "fire_enchanter", -0.6f, 0.3f, lv(30), "enchanter");
        p(s, "soul_squeezer", 0.5f, 0.2f, lv(20), "enchanter");
        p(s, "frost_enchanter", -0.65f, 0.5f, lv(40), "fire_enchanter");
        p(s, "soul_siphon", 0.6f, 0.45f, lv(40), "soul_squeezer");
        p(s, "insightful_enchanter", 0.1f, 0.45f, lv(50), "enchanter");
        p(s, "storm_enchanter", -0.55f, 0.72f, lv(50), "frost_enchanter");
        p(s, "corpus_enchanter", 0.3f, 0.7f, lv(70), "insightful_enchanter");
        p(s, "extra_effect", 0f, 1f, lv(100), "corpus_enchanter", "storm_enchanter");

        // ---------------------------------------------------------------- Light Armor
        s = Skill.LIGHT_ARMOR;
        p(s, "agile_defender", 0f, 0f, FIVE);
        p(s, "custom_fit", 0.1f, 0.3f, lv(30), "agile_defender");
        p(s, "unhindered", -0.5f, 0.55f, lv(50), "custom_fit");
        p(s, "wind_walker", -0.4f, 0.8f, lv(60), "unhindered");
        p(s, "matching_set_light", 0.5f, 0.6f, lv(70), "custom_fit");
        p(s, "deft_movement", 0.15f, 1f, lv(100), "wind_walker", "matching_set_light");

        // ---------------------------------------------------------------- Sneak
        s = Skill.SNEAK;
        p(s, "stealth", 0f, 0f, FIVE);
        p(s, "backstab", -0.4f, 0.3f, lv(30), "stealth");
        p(s, "muffled_movement", 0.45f, 0.3f, lv(30), "stealth");
        p(s, "deadly_aim", -0.65f, 0.5f, lv(40), "backstab");
        p(s, "light_foot", 0.6f, 0.5f, lv(40), "muffled_movement");
        p(s, "assassins_blade", -0.45f, 0.72f, lv(50), "deadly_aim");
        p(s, "silent_roll", 0.55f, 0.72f, lv(50), "light_foot");
        p(s, "silence", 0.3f, 0.85f, lv(70), "silent_roll");
        p(s, "shadow_warrior", 0f, 1f, lv(100), "silence", "assassins_blade");

        // ---------------------------------------------------------------- Lockpicking
        s = Skill.LOCKPICKING;
        tiers(s, "locks");
        p(s, "quick_hands", -0.55f, 0.35f, lv(40), "novice_locks");
        p(s, "wax_key", -0.6f, 0.6f, lv(50), "quick_hands");
        p(s, "golden_touch", 0.55f, 0.4f, lv(60), "novice_locks");
        p(s, "treasure_hunter", 0.6f, 0.65f, lv(70), "golden_touch");
        p(s, "unbreakable", 0.35f, 0.95f, lv(100), "treasure_hunter");

        // ---------------------------------------------------------------- Pickpocket
        s = Skill.PICKPOCKET;
        p(s, "light_fingers", 0f, 0f, FIVE);
        p(s, "night_thief", -0.5f, 0.3f, lv(30), "light_fingers");
        p(s, "cutpurse", 0.5f, 0.35f, lv(40), "light_fingers");
        p(s, "poisoned", -0.6f, 0.55f, lv(40), "night_thief");
        p(s, "extra_pockets", 0f, 0.5f, lv(50), "light_fingers");
        p(s, "keymaster", 0.55f, 0.6f, lv(60), "cutpurse");
        p(s, "misdirection", 0.4f, 0.8f, lv(70), "keymaster");
        p(s, "perfect_touch", 0.1f, 1f, lv(100), "misdirection");

        // ---------------------------------------------------------------- Speech
        s = Skill.SPEECH;
        p(s, "haggling", 0f, 0f, FIVE);
        p(s, "allure", -0.5f, 0.3f, lv(30), "haggling");
        p(s, "bribery", 0.5f, 0.3f, lv(30), "haggling");
        p(s, "merchant", -0.45f, 0.55f, lv(50), "allure");
        p(s, "persuasion", 0.55f, 0.5f, lv(50), "bribery");
        p(s, "investor", -0.5f, 0.75f, lv(70), "merchant");
        p(s, "intimidation", 0.5f, 0.72f, lv(70), "persuasion");
        p(s, "fence", -0.3f, 0.9f, lv(90), "investor");
        p(s, "master_trader", 0.05f, 1f, lv(100), "fence");

        // ---------------------------------------------------------------- Alchemy
        s = Skill.ALCHEMY;
        p(s, "alchemist", 0f, 0f, FIVE);
        p(s, "physician", -0.45f, 0.2f, lv(20), "alchemist");
        p(s, "benefactor", -0.55f, 0.45f, lv(30), "physician");
        p(s, "poisoner", 0.5f, 0.3f, lv(30), "alchemist");
        p(s, "experimenter", -0.4f, 0.65f, lv(50, 70, 90), "benefactor");
        p(s, "green_thumb", 0f, 0.5f, lv(50), "alchemist");
        p(s, "concentrated_poison", 0.55f, 0.6f, lv(60), "poisoner");
        p(s, "snakeblood", 0.45f, 0.82f, lv(80), "concentrated_poison");
        p(s, "purity", 0f, 1f, lv(100), "experimenter", "snakeblood");

        // ---------------------------------------------------------------- Mining (custom)
        s = Skill.MINING;
        p(s, "prospector", 0f, 0f, FIVE);
        p(s, "excavator", -0.5f, 0.12f, lv(10), "prospector");
        p(s, "stonebreaker", -0.6f, 0.38f, lv(30), "excavator");
        p(s, "tunneler", -0.35f, 0.6f, lv(50), "stonebreaker");
        p(s, "geologist", 0.5f, 0.55f, lv(60), "prospector");
        p(s, "deep_delver", -0.2f, 0.8f, lv(75), "tunneler");
        p(s, "vein_miner", 0.15f, 1f, lv(100), "deep_delver", "geologist");

        // ---------------------------------------------------------------- Woodcutting (custom)
        s = Skill.WOODCUTTING;
        p(s, "lumberjack", 0f, 0f, FIVE);
        p(s, "forager", 0.5f, 0.3f, lv(30), "lumberjack");
        p(s, "carpenter", -0.5f, 0.35f, lv(20), "lumberjack");
        p(s, "hearthfire", -0.45f, 0.7f, lv(50), "carpenter");
        p(s, "timber", 0.2f, 1f, lv(70), "forager", "hearthfire");

        // ---------------------------------------------------------------- Fishing (custom)
        s = Skill.FISHING;
        p(s, "angler", 0f, 0f, FIVE);
        p(s, "patient_hands", -0.5f, 0.35f, lv(20), "angler");
        p(s, "steady_reel", 0f, 0.45f, lv(25, 55), "angler");
        p(s, "treasure_fisher", 0.5f, 0.35f, lv(30, 60), "angler");
        p(s, "double_catch", -0.4f, 0.7f, lv(50), "patient_hands");
        p(s, "legendary_angler", 0.15f, 1f, lv(100), "double_catch", "treasure_fisher");

        // ---------------------------------------------------------------- Hunting (custom)
        s = Skill.HUNTING;
        p(s, "tracker", 0f, 0f, FIVE);
        p(s, "skinner", -0.5f, 0.3f, lv(20), "tracker");
        p(s, "field_dresser", -0.5f, 0.6f, lv(40), "skinner");
        p(s, "beast_lore", 0.5f, 0.45f, lv(60), "tracker");
        p(s, "monster_hunter", 0.4f, 0.75f, lv(70), "beast_lore");
        p(s, "apex_predator", 0f, 1f, lv(100), "monster_hunter", "field_dresser");

        // ---------------------------------------------------------------- Unarmed (custom)
        s = Skill.UNARMED;
        p(s, "pugilist", 0f, 0f, FIVE);
        p(s, "iron_fist", -0.45f, 0.35f, lv(25), "pugilist");
        p(s, "heavy_strikes", 0.45f, 0.35f, lv(40), "pugilist");
        p(s, "haymaker", -0.3f, 0.7f, lv(60), "iron_fist");
        p(s, "grandmaster", 0f, 1f, lv(100), "haymaker", "heavy_strikes");

        // ---------------------------------------------------------------- Athletics (custom)
        s = Skill.ATHLETICS;
        p(s, "runner", 0f, 0f, FIVE);
        p(s, "sprinter", -0.5f, 0.35f, lv(25), "runner");
        p(s, "climber", 0.5f, 0.35f, lv(35), "runner");
        p(s, "dodge_roll", -0.35f, 0.7f, lv(50), "sprinter");
        p(s, "wind_walker", 0f, 1f, lv(100), "dodge_roll", "climber");
        // the carry line (inventory.CarryWeight): +25 per rank, +50 per rank, +100
        p(s, "strong_back", -1.0f, 0.15f, lv(20, 45, 70), "runner");
        p(s, "pack_mule", -1.0f, 0.5f, lv(55, 85), "strong_back");
        p(s, "beast_of_burden", -0.8f, 0.85f, lv(100), "pack_mule");
    }

    static {
        // ---------------------------------------------------------------- Polearms (custom)
        Skill s = Skill.POLEARM;
        p(s, "lancer", 0f, 0f, FIVE);
        p(s, "long_reach", -0.5f, 0.25f, lv(25), "lancer");
        p(s, "impale", 0.5f, 0.3f, lv(30, 60, 90), "lancer");
        p(s, "set_against_charge", -0.65f, 0.5f, lv(40), "long_reach");
        p(s, "sweeping_arc", 0.05f, 0.5f, lv(50), "lancer");
        p(s, "phalanx", -0.45f, 0.75f, lv(60), "set_against_charge");
        p(s, "pinning_strike", 0.55f, 0.65f, lv(70), "impale");
        p(s, "skewer", 0.1f, 1f, lv(100), "pinning_strike", "phalanx");

        // ---------------------------------------------------------------- Mysticism (custom school)
        s = Skill.MYSTICISM;
        tiers(s, "mysticism");
        p(s, "dual_casting", -0.3f, 0.2f, lv(20), "novice_mysticism");
        p(s, "far_sight", -0.6f, 0.3f, lv(30), "novice_mysticism");
        p(s, "thaumaturge", 0.5f, 0.25f, lv(30, 60), "novice_mysticism");
        p(s, "anchor", -0.7f, 0.55f, lv(40), "far_sight");
        p(s, "mirror_mage", 0.6f, 0.5f, lv(50), "thaumaturge");
        p(s, "featherweight", -0.5f, 0.78f, lv(50), "anchor");
        p(s, "dispeller", 0.35f, 0.7f, lv(60), "mirror_mage");
        p(s, "spellbinder", 0.6f, 0.88f, lv(80), "dispeller");
        p(s, "mystic_mastery", 0.25f, 1f, lv(100), "spellbinder", "featherweight");

        // ---------------------------------------------------------------- Acrobatics (custom)
        s = Skill.ACROBATICS;
        p(s, "leaper", 0f, 0f, FIVE);
        p(s, "featherfall", -0.5f, 0.3f, lv(30, 60), "leaper");
        p(s, "nimble", 0.5f, 0.3f, lv(30), "leaper");
        p(s, "double_jump", 0.55f, 0.6f, lv(50), "nimble");
        p(s, "tumble", -0.55f, 0.62f, lv(50), "featherfall");
        p(s, "aerial_strike", 0.3f, 0.82f, lv(70), "double_jump");
        p(s, "cat_fall", -0.2f, 1f, lv(100), "tumble", "aerial_strike");

        // ---------------------------------------------------------------- Riding (custom)
        s = Skill.RIDING;
        p(s, "horseman", 0f, 0f, FIVE);
        p(s, "stable_hand", -0.5f, 0.25f, lv(20), "horseman");
        p(s, "mounted_combat", 0.5f, 0.3f, lv(30), "horseman");
        p(s, "endurance_rider", -0.6f, 0.52f, lv(40), "stable_hand");
        p(s, "horse_archer", 0.65f, 0.55f, lv(50), "mounted_combat");
        p(s, "trample", 0.2f, 0.6f, lv(60), "mounted_combat");
        p(s, "bond", -0.45f, 0.8f, lv(70), "endurance_rider");
        p(s, "cavalry_charge", 0.1f, 1f, lv(100), "trample", "bond");
        // the horseman's eye and the stable's trade (com.skycraft.mounts: stat cards and sheets, wild mounts, taming, tack)
        p(s, "horse_sense", 0f, 0.14f, lv(10), "horseman");
        p(s, "wild_eye", -0.28f, 0.4f, lv(25), "horse_sense");
        p(s, "steady_seat", 0.05f, 0.4f, lv(20, 45), "horse_sense");
        p(s, "pack_master", -0.9f, 0.1f, lv(15, 40, 65), "horseman");
        p(s, "endurance", -0.95f, 0.62f, lv(30, 60), "stable_hand");
        p(s, "trick_rider", 0.85f, 0.8f, lv(45, 75), "horse_archer");

        // ---------------------------------------------------------------- Cooking (custom)
        s = Skill.COOKING;
        p(s, "cook", 0f, 0f, FIVE);
        p(s, "hearty_meals", -0.5f, 0.3f, lv(20), "cook");
        p(s, "seasoned", 0.5f, 0.3f, lv(30), "cook");
        p(s, "camp_cook", -0.6f, 0.58f, lv(40), "hearty_meals");
        p(s, "preserver", 0.55f, 0.6f, lv(50), "seasoned");
        p(s, "master_chef", -0.2f, 0.8f, lv(70), "camp_cook");
        p(s, "gourmet", 0.15f, 1f, lv(100), "master_chef", "preserver");
    }

    /** Debug helper used by the /skycraft perks command. */
    public static String describe(Perk perk) {
        return perk.id() + " " + Arrays.toString(perk.requiredLevels()) + " <- " + perk.parents();
    }
}

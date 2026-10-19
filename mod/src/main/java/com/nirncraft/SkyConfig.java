package com.nirncraft;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common (server-authoritative) configuration: config/nirncraft-common.toml */
public final class SkyConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue SKILL_XP_RATE;
    public static final ModConfigSpec.DoubleValue LEVEL_XP_RATE;
    public static final ModConfigSpec.IntValue PERK_POINTS_PER_LEVEL;
    public static final ModConfigSpec.BooleanValue RESTRICT_DIGGING;
    public static final ModConfigSpec.BooleanValue REQUIRE_CORRECT_TOOL;
    public static final ModConfigSpec.BooleanValue SKYRIM_HUNGER;
    public static final ModConfigSpec.BooleanValue SKYRIM_REGEN;
    public static final ModConfigSpec.DoubleValue SPRINT_STAMINA_PER_SECOND;
    public static final ModConfigSpec.DoubleValue POWER_ATTACK_STAMINA;
    public static final ModConfigSpec.DoubleValue JUMP_STAMINA;
    public static final ModConfigSpec.DoubleValue HEALTH_REGEN_PERCENT;
    public static final ModConfigSpec.DoubleValue MAGICKA_REGEN_PERCENT;
    public static final ModConfigSpec.DoubleValue STAMINA_REGEN_PERCENT;
    public static final ModConfigSpec.DoubleValue WEAPON_SKILL_DAMAGE_SCALE;
    public static final ModConfigSpec.BooleanValue SNEAK_ATTACKS;
    public static final ModConfigSpec.BooleanValue PROMPT_RACE;
    public static final ModConfigSpec.IntValue MAX_TRAININGS_PER_LEVEL;
    public static final ModConfigSpec.IntValue POWER_COOLDOWN_MAX_SECONDS;
    public static final ModConfigSpec.IntValue POWER_COOLDOWN_MIN_SECONDS;
    public static final ModConfigSpec.EnumValue<DeathDrops> DEATH_DROPS;
    public static final ModConfigSpec.DoubleValue DEATH_GOLD_DROP_PERCENT;
    public static final ModConfigSpec.IntValue DEATH_VALUABLE_MIN_VALUE;
    public static final ModConfigSpec.EnumValue<RespawnMode> RESPAWN_MODE;
    public static final ModConfigSpec.IntValue RESPAWN_INVULN_SECONDS;
    public static final ModConfigSpec.IntValue RESPAWN_SAFE_RADIUS;
    public static final ModConfigSpec.BooleanValue HAND_SPELL_GLOW;
    public static final ModConfigSpec.BooleanValue STORY_LECTERN_TAKEABLE;

    /** Where you wake after dying with no bed: the nearest settlement you have visited, or the world spawn. */
    public enum RespawnMode { VISITED_SETTLEMENT, WORLD_SPAWN }

    /** What a player loses on death. VALUABLES: only gold and valuables; GEAR: everything but equipped gear; EVERYTHING: vanilla. */
    public enum DeathDrops { VALUABLES, GEAR, EVERYTHING }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("progression");
        SKILL_XP_RATE = b.comment("Multiplier for all skill XP gains.")
                .defineInRange("skillXpRate", 1.0, 0.01, 100.0);
        LEVEL_XP_RATE = b.comment("Multiplier for character XP gained from skill increases.")
                .defineInRange("levelXpRate", 1.0, 0.01, 100.0);
        PERK_POINTS_PER_LEVEL = b.defineInRange("perkPointsPerLevel", 1, 0, 10);
        MAX_TRAININGS_PER_LEVEL = b.comment("How many times a trainer can be paid per character level (Skyrim: 5).")
                .defineInRange("maxTrainingsPerLevel", 5, 0, 100);
        PROMPT_RACE = b.comment("Show the race selection screen to new players.").define("promptRace", true);
        POWER_COOLDOWN_MAX_SECONDS = b.comment("Greater power (racial, stone, curse) cooldown at character level 1, in seconds (use, don't abuse).")
                .defineInRange("powerCooldownMaxSeconds", 300, 10, 86400);
        POWER_COOLDOWN_MIN_SECONDS = b.comment("Greater power cooldown at character level 50 and above, in seconds.")
                .defineInRange("powerCooldownMinSeconds", 120, 10, 86400);
        b.pop();

        b.push("death");
        DEATH_DROPS = b.comment("VALUABLES: you keep armour, weapons, ammo, tools and food and drop only gold and valuables (gems, jewellery, soul gems, costly misc).",
                        "GEAR: you keep only what you wear and hold. EVERYTHING: vanilla, all items drop to your corpse. Quest items are always kept.")
                .defineEnum("dropMode", DeathDrops.VALUABLES);
        DEATH_GOLD_DROP_PERCENT = b.comment("Percent of the gold wallet dropped (as coins in your corpse) on death.")
                .defineInRange("goldDropPercent", 25.0, 0.0, 100.0);
        DEATH_VALUABLE_MIN_VALUE = b.comment("A misc item worth at least this many septims counts as a valuable.")
                .defineInRange("valuableMinValue", 150, 1, 1000000);
        RESPAWN_MODE = b.comment("With no bed or home set: VISITED_SETTLEMENT = the nearest settlement this player has discovered (their own record), WORLD_SPAWN = the spawn point.")
                .defineEnum("respawnMode", RespawnMode.VISITED_SETTLEMENT);
        RESPAWN_INVULN_SECONDS = b.comment("Seconds of invulnerability after respawning (hostiles also ignore you for the creatures grace period).")
                .defineInRange("respawnInvulnSeconds", 5, 0, 60);
        RESPAWN_SAFE_RADIUS = b.comment("A respawn spot is moved if a hostile mob stands within this many blocks (0 = off).")
                .defineInRange("respawnSafeRadius", 60, 0, 200);
        b.pop();

        b.push("survival");
        RESTRICT_DIGGING = b.comment("You start as a nobody: terrain (dirt, stone...) can only be dug after unlocking Mining perks.",
                        "Ores, logs, crops, plants and player-placed blocks are always breakable.")
                .define("restrictDigging", true);
        REQUIRE_CORRECT_TOOL = b.comment("Ores require a pickaxe and logs require an axe, like Skyrim's mining and wood chopping.")
                .define("requireCorrectTool", true);
        SKYRIM_HUNGER = b.comment("Hunger never drains (Skyrim default). Food heals health and restores stamina instead.")
                .define("skyrimHunger", true);
        SKYRIM_REGEN = b.comment("Replace vanilla regeneration with Skyrim-style regen that slows down in combat.")
                .define("skyrimRegen", true);
        HEALTH_REGEN_PERCENT = b.comment("Percent of max health regenerated per second out of combat.")
                .defineInRange("healthRegenPercent", 0.7, 0.0, 100.0);
        MAGICKA_REGEN_PERCENT = b.comment("Percent of max magicka regenerated per second out of combat.")
                .defineInRange("magickaRegenPercent", 3.0, 0.0, 100.0);
        STAMINA_REGEN_PERCENT = b.comment("Percent of max stamina regenerated per second out of combat.")
                .defineInRange("staminaRegenPercent", 5.0, 0.0, 100.0);
        b.pop();

        b.push("combat");
        SPRINT_STAMINA_PER_SECOND = b.defineInRange("sprintStaminaPerSecond", 7.0, 0.0, 100.0);
        POWER_ATTACK_STAMINA = b.defineInRange("powerAttackStamina", 25.0, 0.0, 200.0);
        JUMP_STAMINA = b.defineInRange("jumpStamina", 0.0, 0.0, 100.0);
        WEAPON_SKILL_DAMAGE_SCALE = b.comment("Extra weapon damage per skill level (0.005 = +50% at skill 100, like Skyrim).")
                .defineInRange("weaponSkillDamageScale", 0.005, 0.0, 1.0);
        SNEAK_ATTACKS = b.define("sneakAttacks", true);
        HAND_SPELL_GLOW = b.comment("Equipped spells show a small glow in their hand (no item is drawn over the arm).")
                .define("handSpellGlow", true);
        b.pop();

        b.push("lore");
        STORY_LECTERN_TAKEABLE = b.comment("A lectern with a story keeps its book: a right-click opens the story in the reading screen.",
                        "On, a sneaking right-click opens the vanilla lectern instead, where the book can be taken.")
                .define("storyLecternTakeable", false);
        b.pop();

        SPEC = b.build();
    }

    /** Greater power cooldown in ticks: max at level 1 easing to min at level 50. */
    public static int powerCooldownTicks(int level) {
        int max = POWER_COOLDOWN_MAX_SECONDS.get();
        int min = Math.min(max, POWER_COOLDOWN_MIN_SECONDS.get());
        double t = Math.max(0, Math.min(1, (level - 1) / 49.0));
        return (int) Math.round((max - (max - min) * t) * 20);
    }

    /** "2m 10s" for a tick count. */
    public static String formatTicks(long ticks) {
        long s = Math.max(0, ticks) / 20 + 1;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private SkyConfig() {}
}

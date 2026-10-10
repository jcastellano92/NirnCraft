package com.skycraft;

import net.minecraftforge.common.ForgeConfigSpec;

/** Common (server-authoritative) configuration: config/skycraft-common.toml */
public final class SkyConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue SKILL_XP_RATE;
    public static final ForgeConfigSpec.DoubleValue LEVEL_XP_RATE;
    public static final ForgeConfigSpec.IntValue PERK_POINTS_PER_LEVEL;
    public static final ForgeConfigSpec.BooleanValue RESTRICT_DIGGING;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_CORRECT_TOOL;
    public static final ForgeConfigSpec.BooleanValue SKYRIM_HUNGER;
    public static final ForgeConfigSpec.BooleanValue SKYRIM_REGEN;
    public static final ForgeConfigSpec.DoubleValue SPRINT_STAMINA_PER_SECOND;
    public static final ForgeConfigSpec.DoubleValue POWER_ATTACK_STAMINA;
    public static final ForgeConfigSpec.DoubleValue JUMP_STAMINA;
    public static final ForgeConfigSpec.DoubleValue HEALTH_REGEN_PERCENT;
    public static final ForgeConfigSpec.DoubleValue MAGICKA_REGEN_PERCENT;
    public static final ForgeConfigSpec.DoubleValue STAMINA_REGEN_PERCENT;
    public static final ForgeConfigSpec.DoubleValue WEAPON_SKILL_DAMAGE_SCALE;
    public static final ForgeConfigSpec.BooleanValue SNEAK_ATTACKS;
    public static final ForgeConfigSpec.BooleanValue PROMPT_RACE;
    public static final ForgeConfigSpec.IntValue MAX_TRAININGS_PER_LEVEL;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("progression");
        SKILL_XP_RATE = b.comment("Multiplier for all skill XP gains (Skyrim's curve is quite fast for Minecraft's hit rate).")
                .defineInRange("skillXpRate", 0.5, 0.01, 100.0);
        LEVEL_XP_RATE = b.comment("Multiplier for character XP gained from skill increases.")
                .defineInRange("levelXpRate", 1.0, 0.01, 100.0);
        PERK_POINTS_PER_LEVEL = b.defineInRange("perkPointsPerLevel", 1, 0, 10);
        MAX_TRAININGS_PER_LEVEL = b.comment("How many times a trainer can be paid per character level (Skyrim: 5).")
                .defineInRange("maxTrainingsPerLevel", 5, 0, 100);
        PROMPT_RACE = b.comment("Show the race selection screen to new players.").define("promptRace", true);
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
        b.pop();

        SPEC = b.build();
    }

    private SkyConfig() {}
}

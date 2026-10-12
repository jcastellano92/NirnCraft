package com.skycraft.creatures;

import net.minecraftforge.common.ForgeConfigSpec;

/** Creatures configuration: config/skycraft-creatures.toml (common, server-authoritative). */
public final class CreaturesConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue DRAGON_ATTACK_CHANCE;
    public static final ForgeConfigSpec.IntValue DRAGON_ATTACK_INTERVAL;
    public static final ForgeConfigSpec.IntValue DRAGON_MIN_LEVEL;
    public static final ForgeConfigSpec.BooleanValue CORPSES;
    public static final ForgeConfigSpec.IntValue CORPSE_DESPAWN_SECONDS;
    public static final ForgeConfigSpec.IntValue EMPTY_CORPSE_DESPAWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue LEVELED_ENEMIES;
    public static final ForgeConfigSpec.BooleanValue GUARD_SPAWNING;
    public static final ForgeConfigSpec.DoubleValue DRAUGR_REPLACES_ZOMBIES;
    public static final ForgeConfigSpec.BooleanValue DISABLE_INSOMNIA;
    public static final ForgeConfigSpec.BooleanValue REGION_SPAWNING;
    public static final ForgeConfigSpec.IntValue HOSTILE_BUDGET;
    public static final ForgeConfigSpec.IntValue WILDLIFE_BUDGET;
    public static final ForgeConfigSpec.IntValue CALM_RADIUS;
    public static final ForgeConfigSpec.IntValue START_AREA_RADIUS;
    public static final ForgeConfigSpec.IntValue HOSTILE_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue GRACE_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_HOSTILE_GROUPS;
    public static final ForgeConfigSpec.IntValue MIN_HOSTILE_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue ROAD_HOSTILE_FACTOR;
    public static final ForgeConfigSpec.DoubleValue DAY_HOSTILE_FACTOR;
    public static final ForgeConfigSpec.IntValue GUARD_ALERT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue GUARD_HELP_PLAYER;
    public static final ForgeConfigSpec.BooleanValue TOWN_ALARM;
    public static final ForgeConfigSpec.IntValue ALARM_CLEAR_SECONDS;
    public static final ForgeConfigSpec.BooleanValue CIVILIANS_FLEE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("dragons");
        DRAGON_ATTACK_CHANCE = b.comment("Chance for a dragon attack on each check (per online player, overworld, daytime/dusk, on the surface).")
                .defineInRange("attackChance", 0.08, 0.0, 1.0);
        DRAGON_ATTACK_INTERVAL = b.comment("Ticks between dragon attack checks for each player (12000 = 10 real minutes).")
                .defineInRange("attackIntervalTicks", 12000, 200, 1_000_000);
        DRAGON_MIN_LEVEL = b.comment("Minimum character level before dragons start attacking.")
                .defineInRange("minPlayerLevel", 3, 1, 1000);
        b.pop();

        b.push("corpses");
        CORPSES = b.comment("Creatures killed by players leave a lootable body instead of scattering their drops.")
                .define("enabled", true);
        CORPSE_DESPAWN_SECONDS = b.comment("Seconds before an unlooted body disappears.")
                .defineInRange("despawnSeconds", 900, 30, 86400);
        EMPTY_CORPSE_DESPAWN_SECONDS = b.comment("Seconds before an emptied body disappears.")
                .defineInRange("emptyDespawnSeconds", 60, 1, 86400);
        b.pop();

        b.push("spawning");
        LEVELED_ENEMIES = b.comment("Hostile creatures get a level near the nearest player's level (more health and damage).")
                .define("leveledEnemies", true);
        GUARD_SPAWNING = b.comment("Keep 2-4 hold guards around every village meeting point (bell).")
                .define("guardSpawning", true);
        DRAUGR_REPLACES_ZOMBIES = b.comment("Fraction of natural zombie spawns in cold, mountain and taiga biomes replaced by draugr.")
                .defineInRange("draugrReplacesZombies", 0.6, 0.0, 1.0);
        DISABLE_INSOMNIA = b.comment("Set the doInsomnia game rule to false on server start (no phantoms / cliff racers from lack of sleep).")
                .define("disableInsomnia", true);
        REGION_SPAWNING = b.comment("Skyrim-style leveled spawning by region and biome (data/*/region_spawns/*.json), per player.")
                .define("regionSpawning", true);
        HOSTILE_BUDGET = b.comment("At most this many hostile creatures placed by the region spawner within 96 blocks of a player.")
                .defineInRange("hostileBudget", 8, 0, 64);
        WILDLIFE_BUDGET = b.comment("At most this many wild animals placed by the region spawner within 96 blocks of a player.")
                .defineInRange("wildlifeBudget", 10, 0, 64);
        CALM_RADIUS = b.comment("No natural hostile spawns or hostile encounters within this many blocks of the world spawn or a settlement (0 = off).")
                .defineInRange("calmRadius", 150, 0, 1000);
        START_AREA_RADIUS = b.comment("No bosses and no bandit camps/bandits within this many blocks of the world spawn (0 = off).")
                .defineInRange("startAreaRadius", 300, 0, 2000);
        HOSTILE_COOLDOWN_SECONDS = b.comment("Minimum seconds between two hostile spawn events (region spawner, night spawns, hostile encounters) near one player.")
                .defineInRange("hostileCooldownSeconds", 100, 0, 3600);
        GRACE_SECONDS = b.comment("No hostile spawn events for this many seconds after a player logs in or respawns.")
                .defineInRange("graceSeconds", 180, 0, 3600);
        MAX_HOSTILE_GROUPS = b.comment("Most hostile region-spawned groups alive within 96 blocks of a player.")
                .defineInRange("maxHostileGroups", 1, 1, 8);
        MIN_HOSTILE_DISTANCE = b.comment("Hostile groups never spawn closer than this many blocks to a player.")
                .defineInRange("minHostileDistance", 40, 28, 64);
        ROAD_HOSTILE_FACTOR = b.comment("Chance multiplier for hostile spawn attempts on or beside a road (main roads feel safe).")
                .defineInRange("roadHostileFactor", 0.08, 0.0, 1.0);
        DAY_HOSTILE_FACTOR = b.comment("Chance multiplier for hostile spawn attempts in daylight (1.0 at night).")
                .defineInRange("dayHostileFactor", 0.5, 0.0, 1.0);
        b.pop();
        b.push("guards");
        GUARD_ALERT_RADIUS = b.comment("Guards engage hostile creatures and anyone attacking a citizen or the player within this many blocks of themselves (settlement and nearby road).")
                .defineInRange("guardAlertRadius", 40, 16, 96);
        GUARD_HELP_PLAYER = b.comment("Guards defend the player (who has no hostile bounty) from hostile creatures.")
                .define("guardHelpPlayer", true);
        TOWN_ALARM = b.comment("Three or more hostiles near a town raise an alarm: every guard in range converges, a bell rings and citizens shelter.")
                .define("townAlarm", true);
        ALARM_CLEAR_SECONDS = b.comment("Seconds without hostiles before the town alarm is cleared.")
                .defineInRange("alarmClearSeconds", 30, 5, 600);
        CIVILIANS_FLEE = b.comment("Villagers and townsfolk run away from hostiles near them.")
                .define("civiliansFlee", true);
        b.pop();
        SPEC = b.build();
    }

    private CreaturesConfig() {}
}

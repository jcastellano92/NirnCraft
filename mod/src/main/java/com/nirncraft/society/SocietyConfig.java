package com.nirncraft.society;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Society configuration: config/nirncraft-society.toml (COMMON, server-authoritative). */
public final class SocietyConfig {
    public static final ModConfigSpec SPEC;

    // ------------------------------------------------------------------ npcs
    public static final ModConfigSpec.BooleanValue POPULATE_SETTLEMENTS;
    public static final ModConfigSpec.IntValue MAX_RESIDENTS;
    public static final ModConfigSpec.BooleanValue CONVERT_VILLAGERS;
    public static final ModConfigSpec.BooleanValue ROUTINES;
    public static final ModConfigSpec.BooleanValue CHATTER;
    public static final ModConfigSpec.IntValue SPOUSE_DAILY_GOLD;

    // ------------------------------------------------------------------ barks
    public static final ModConfigSpec.BooleanValue AMBIENT_BARKS;
    public static final ModConfigSpec.IntValue BARK_RANGE;
    public static final ModConfigSpec.IntValue BARK_COOLDOWN_MIN;
    public static final ModConfigSpec.IntValue BARK_COOLDOWN_MAX;
    public static final ModConfigSpec.IntValue MAX_BARKS_PER_SECOND;

    // ------------------------------------------------------------------ reputation
    public static final ModConfigSpec.IntValue HOSTILE_THRESHOLD;
    public static final ModConfigSpec.IntValue RECOVERY_MINUTES;

    // ------------------------------------------------------------------ hit squads
    public static final ModConfigSpec.BooleanValue HIT_SQUADS;
    public static final ModConfigSpec.IntValue SQUAD_MIN_MINUTES;
    public static final ModConfigSpec.IntValue SQUAD_MAX_MINUTES;

    // ------------------------------------------------------------------ encounters
    public static final ModConfigSpec.BooleanValue ENCOUNTERS;
    public static final ModConfigSpec.IntValue ENCOUNTER_MIN_SECONDS;
    public static final ModConfigSpec.IntValue ENCOUNTER_MAX_SECONDS;
    public static final ModConfigSpec.IntValue ENCOUNTER_CAP;
    public static final ModConfigSpec.BooleanValue DRAGON_FLYBYS;
    public static final ModConfigSpec.BooleanValue HOSTILE_ENCOUNTERS;

    // ------------------------------------------------------------------ pvp / cosmetics
    public static final ModConfigSpec.BooleanValue PVP_BOUNTY;
    public static final ModConfigSpec.BooleanValue COSMETICS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("npcs");
        POPULATE_SETTLEMENTS = b.comment("Give every detected settlement (roads module) a few townsfolk: innkeeper, bard, priest, workers,",
                        "and the first settlement of each hold its Jarl and housecarl.")
                .define("populateSettlements", true);
        MAX_RESIDENTS = b.comment("Upper bound of townsfolk a settlement is given (a capital gets the most, a farm the fewest).")
                .defineInRange("maxResidents", 22, 2, 64);
        CONVERT_VILLAGERS = b.comment("Vanilla villagers and wandering traders that join the world (breeding, zombie curing, spawn eggs,",
                        "commands, other mods, old saves) become NirnCraft townsfolk / Khajiit caravan traders of the region's culture.")
                .define("convertVillagers", true);
        ROUTINES = b.comment("Townsfolk follow daily routines: sleep, breakfast, work, lunch, wandering, evenings at the inn.")
                .define("routines", true);
        CHATTER = b.comment("Townsfolk stop to chat with each other when a player is near enough to overhear.")
                .define("chatter", true);
        SPOUSE_DAILY_GOLD = b.comment("Gold a spouse's shop earns for the player each day (spouses without a trade earn half).")
                .defineInRange("spouseDailyGold", 100, 0, 10000);
        b.pop();

        b.push("barks");
        AMBIENT_BARKS = b.comment("Villagers, guards and NPCs say things above their heads when a player passes by.")
                .define("ambientBarks", true);
        BARK_RANGE = b.comment("Distance in blocks at which a passing player triggers an ambient line.")
                .defineInRange("range", 6, 2, 16);
        BARK_COOLDOWN_MIN = b.comment("Minimum seconds before the same speaker says another ambient line.")
                .defineInRange("cooldownMinSeconds", 30, 5, 3600);
        BARK_COOLDOWN_MAX = b.comment("Maximum seconds before the same speaker says another ambient line.")
                .defineInRange("cooldownMaxSeconds", 60, 5, 3600);
        MAX_BARKS_PER_SECOND = b.comment("Server-wide limit of ambient lines per second.")
                .defineInRange("maxPerSecond", 3, 1, 50);
        b.pop();

        b.push("reputation");
        HOSTILE_THRESHOLD = b.comment("At or below this reputation a faction attacks the player on sight and may send hit squads.")
                .defineInRange("hostileThreshold", -40, -100, 0);
        RECOVERY_MINUTES = b.comment("Every this many minutes of play, each negative reputation recovers by 1 (0 = never).")
                .defineInRange("recoveryMinutes", 10, 0, 1440);
        b.pop();

        b.push("hitSquads");
        HIT_SQUADS = b.comment("Factions that hate the player send Thalmor justiciars, Forsworn ambushes, army patrols,",
                        "Dark Brotherhood assassins or hired thugs after them.")
                .define("enabled", true);
        SQUAD_MIN_MINUTES = b.comment("Minimum minutes of play between two hit squad rolls.")
                .defineInRange("minMinutes", 20, 1, 1440);
        SQUAD_MAX_MINUTES = b.comment("Maximum minutes of play between two hit squad rolls.")
                .defineInRange("maxMinutes", 40, 1, 1440);
        b.pop();

        b.push("encounters");
        ENCOUNTERS = b.comment("Random wilderness encounters near players in the overworld (hunters, bards, prisoner escorts,",
                        "fights, couriers, stranded merchants, vampires at night...).")
                .define("enabled", true);
        ENCOUNTER_MIN_SECONDS = b.comment("Minimum seconds between two encounter rolls per player.")
                .defineInRange("minSeconds", 180, 10, 36000);
        ENCOUNTER_MAX_SECONDS = b.comment("Maximum seconds between two encounter rolls per player.")
                .defineInRange("maxSeconds", 360, 10, 36000);
        ENCOUNTER_CAP = b.comment("Maximum encounter groups within 128 blocks of a player.")
                .defineInRange("groupsPerArea", 3, 0, 32);
        DRAGON_FLYBYS = b.comment("Rarely, a dragon (nirncraft:dragon) flies over the wilderness.")
                .define("dragonFlybys", true);
        HOSTILE_ENCOUNTERS = b.comment("Allow encounters that attack the player (vampires, Forsworn ambushes). Never on Peaceful.")
                .define("hostileEncounters", true);
        b.pop();

        b.push("pvp");
        PVP_BOUNTY = b.comment("Attacking another player where witnesses see it adds an assault bounty; killing one is murder.")
                .define("pvpBounty", true);
        b.pop();

        b.push("cosmetics");
        COSMETICS = b.comment("Let faction members wear their faction's regalia (tabard, cloak, pelt, sash, hood) over their skin.")
                .define("enabled", true);
        b.pop();
        SPEC = b.build();
    }

    private SocietyConfig() {}

    /** Random duration between a min and max config value (swapped if misconfigured). */
    static int between(int a, int b, net.minecraft.util.RandomSource random) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo + (hi > lo ? random.nextInt(hi - lo + 1) : 0);
    }
}

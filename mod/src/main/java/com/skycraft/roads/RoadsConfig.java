package com.skycraft.roads;

import net.minecraftforge.common.ForgeConfigSpec;

/** Roads configuration: config/skycraft-roads.toml (COMMON, server-authoritative). */
public final class RoadsConfig {
    public static final ForgeConfigSpec SPEC;

    // ------------------------------------------------------------------ roads
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue CONNECTIONS;
    public static final ForgeConfigSpec.IntValue SETTLEMENT_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_EXPANSIONS;
    public static final ForgeConfigSpec.IntValue MAX_BRIDGE_LENGTH;
    public static final ForgeConfigSpec.IntValue LANTERN_SPACING;
    public static final ForgeConfigSpec.BooleanValue SIGNPOSTS;
    public static final ForgeConfigSpec.BooleanValue LANTERNS;
    public static final ForgeConfigSpec.BooleanValue ROADSIDE;
    public static final ForgeConfigSpec.ConfigValue<String> LEGACY_PAVER;
    public static final ForgeConfigSpec.BooleanValue PAINT;

    // ------------------------------------------------------------------ performance
    public static final ForgeConfigSpec.IntValue PAVE_BLOCKS_PER_TICK;
    public static final ForgeConfigSpec.DoubleValue PAVE_MILLIS_PER_TICK;
    public static final ForgeConfigSpec.IntValue PLAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue PLANNER_THREADS;

    // ------------------------------------------------------------------ travelers
    public static final ForgeConfigSpec.BooleanValue TRAVELERS;
    public static final ForgeConfigSpec.IntValue TRAVELER_INTERVAL;
    public static final ForgeConfigSpec.IntValue TRAVELER_CAP;
    public static final ForgeConfigSpec.IntValue TRAVELER_MERCHANT_PERCENT;
    public static final ForgeConfigSpec.IntValue TRAVELER_DESPAWN_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue BANDIT_AMBUSHES;

    // ------------------------------------------------------------------ compass
    public static final ForgeConfigSpec.BooleanValue COMPASS_MARKERS;
    public static final ForgeConfigSpec.IntValue COMPASS_RANGE;

    // ------------------------------------------------------------------ carriage
    public static final ForgeConfigSpec.BooleanValue CARRIAGES;
    public static final ForgeConfigSpec.IntValue CARRIAGE_COST;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("roads");
        ENABLED = b.comment("Master switch: detect settlements, plan and pave roads between them (overworld only).")
                .define("enabled", true);
        MAX_DISTANCE = b.comment("Settlements farther apart than this (blocks) are not connected, unless one would otherwise be isolated.")
                .defineInRange("maxDistance", 1600, 128, 6000);
        CONNECTIONS = b.comment("Each new settlement is connected to this many of its nearest known settlements.")
                .defineInRange("connectionsPerSettlement", 2, 1, 6);
        SETTLEMENT_RADIUS = b.comment("Roads are not paved within this radius of a settlement center (villages have their own paths).",
                        "The settlement's structure bounding box (+6) is always excluded too.")
                .defineInRange("settlementRadius", 40, 8, 256);
        MAX_EXPANSIONS = b.comment("A* node budget per planning stage (the coarse corridor of a long road, and each ~256-block stretch).",
                        "If no path is found within it the link is skipped and the settlement is linked to another neighbour.")
                .defineInRange("maxPathExpansions", 20000, 500, 200000);
        MAX_BRIDGE_LENGTH = b.comment("Water crossings longer than this (blocks) get no bridge: the planner routes round them (the Talos Bridge to the Imperial City is the one longer bridge).")
                .defineInRange("maxBridgeLength", 48, 0, 512);
        LANTERN_SPACING = b.comment("Distance in blocks between lantern posts along a road (0 = none).")
                .defineInRange("lanternSpacing", 40, 0, 4096);
        SIGNPOSTS = b.comment("Place signposts at settlement exits and road junctions.").define("signposts", true);
        LANTERNS = b.comment("Place lantern posts along roads.").define("lanterns", true);
        ROADSIDE = b.comment("Dress the verges of new roads: carts, barrels, hay, fences, cairns, camps, woodpiles (denser near towns).")
                .define("roadsideDressing", true);
        LEGACY_PAVER = b.comment("The runtime paver that pastes roads onto generated chunks (the pre-plan pipeline).",
                        "false = never: roads are painted at chunk generation from the baked road plan, the network",
                        "follows the plan, and the paver only places signposts, lanterns, shrines and dressing.",
                        "auto = only in saves that predate the plan-first generation (to upgrade old worlds).",
                        "true = always (and it plans its own roads on noise heights where the plan has none).")
                .defineInList("legacyPaver", "auto", java.util.List.of("auto", "true", "false"));
        PAINT = b.comment("Paint the planned roads, bridges, culverts, fords, stairs and tunnels while chunks generate",
                        "(read by worldgen threads; switch off only for A/B timing).")
                .define("paintAtGeneration", true);
        b.pop();

        b.push("performance");
        PAVE_BLOCKS_PER_TICK = b.comment("Maximum number of road block changes per server tick (all levels together).")
                .defineInRange("paveBlocksPerTick", 400, 1, 10000);
        PAVE_MILLIS_PER_TICK = b.comment("Hard time budget for paving per server tick, in milliseconds.")
                .defineInRange("paveMillisPerTick", 3.0, 0.1, 50.0);
        PLAN_INTERVAL_TICKS = b.comment("Ticks between starting two road plans (planning runs on a low-priority background thread).")
                .defineInRange("planIntervalTicks", 10, 1, 1200);
        PLANNER_THREADS = b.comment("Background threads planning roads (low priority; the whole of Tamriel takes a few minutes with 2).",
                        "Read when the server starts.")
                .defineInRange("plannerThreads", 2, 1, 8);
        b.pop();

        b.push("travelers");
        TRAVELERS = b.comment("Spawn ambient traffic on roads near players: Khajiit caravans, travelers, guard patrols and bandit ambushes.")
                .define("enabled", true);
        TRAVELER_INTERVAL = b.comment("Seconds between traveler spawn attempts per player (owner, playtest 4: spaced, not a stream).")
                .defineInRange("spawnIntervalSeconds", 100, 5, 3600);
        TRAVELER_CAP = b.comment("Maximum traveler groups within 128 blocks of a player.")
                .defineInRange("groupsNearPlayer", 3, 0, 32);
        TRAVELER_MERCHANT_PERCENT = b.comment("Share of road traffic that are travelling merchants (Khajiit caravans, tinkers); the rest are",
                "couriers, pilgrims, hunters, bards, farmers, refugees, adventurers, tax collectors, soldiers and patrols.")
                .defineInRange("merchantPercent", 15, 0, 60);
        TRAVELER_DESPAWN_DISTANCE = b.comment("Travelers farther than this from every player are removed.")
                .defineInRange("despawnDistance", 160, 64, 512);
        BANDIT_AMBUSHES = b.comment("Allow rare bandit ambushes beside roads (needs skycraft:bandit, never on Peaceful).")
                .define("banditAmbushes", true);
        b.pop();

        b.push("compass");
        COMPASS_MARKERS = b.comment("Show nearby settlements the player has not discovered yet on the compass.")
                .define("markers", true);
        COMPASS_RANGE = b.comment("Range in blocks of settlement compass markers.")
                .defineInRange("range", 400, 32, 4096);
        b.pop();

        b.push("carriage");
        CARRIAGES = b.comment("Enable paid horse carriage transport between hold capitals and settlements.")
                .define("enabled", true);
        CARRIAGE_COST = b.comment("Base Septim fare for carriage travel between holds (major holds cost 20-50).")
                .defineInRange("baseFare", 20, 0, 1000);
        b.pop();
        SPEC = b.build();
    }

    private RoadsConfig() {}
}

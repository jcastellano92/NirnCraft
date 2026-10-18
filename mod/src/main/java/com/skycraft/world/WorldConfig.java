package com.skycraft.world;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * World module settings: {@code config/skycraft-world-common.toml} (server rules) and
 * {@code config/skycraft-world-client.toml} (music & atmosphere, per player).
 */
public final class WorldConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    // common
    public static final ForgeConfigSpec.BooleanValue DISCOVERY;
    public static final ForgeConfigSpec.BooleanValue FAST_TRAVEL;
    public static final ForgeConfigSpec.IntValue BLOCKS_PER_TRAVEL_HOUR;
    public static final ForgeConfigSpec.BooleanValue TRAVEL_TIME_MULTIPLAYER;
    public static final ForgeConfigSpec.BooleanValue BED_MENU;
    public static final ForgeConfigSpec.BooleanValue REST_SYNC_MULTIPLAYER;
    public static final ForgeConfigSpec.BooleanValue REST_ALLOW_WAIT;
    public static final ForgeConfigSpec.BooleanValue REST_ALLOW_SLEEP_SKIP;
    public static final ForgeConfigSpec.IntValue REST_AFK_SECONDS;
    public static final ForgeConfigSpec.IntValue REST_FULL_RECHARGE_SECONDS;
    public static final ForgeConfigSpec.IntValue REST_BEDROLL_PERCENT;
    public static final ForgeConfigSpec.IntValue REST_MAX_SKIP_SECONDS;
    public static final ForgeConfigSpec.IntValue WELL_RESTED_TICKS;

    // client
    public static final ForgeConfigSpec.BooleanValue MUSIC;
    public static final ForgeConfigSpec.BooleanValue REALM_FOG;
    public static final ForgeConfigSpec.IntValue COMPASS_RANGE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("world");
        DISCOVERY = b.comment("Announce structures as Skyrim locations (\"DISCOVERED: Bleakwind Barrow\") and track them per player.")
                .define("discovery", true);
        FAST_TRAVEL = b.comment("Allow fast travel to discovered locations from the world map.").define("fastTravel", true);
        BLOCKS_PER_TRAVEL_HOUR = b.comment("Fast travel advances the clock by one hour per this many blocks travelled",
                        "(0 = never advance time; see travelTimeMultiplayer for servers).")
                .defineInRange("blocksPerTravelHour", 400, 0, 1000000);
        TRAVEL_TIME_MULTIPLAYER = b.comment("Fast travel also advances the world clock when several players are online (otherwise it only",
                        "does so for a lone player). Off keeps other players' clocks from jumping when someone travels.")
                .define("travelTimeMultiplayer", true);
        BED_MENU = b.comment("Right-clicking a bed opens the Skyrim sleep menu at any time of day (sneak + empty hand for vanilla sleeping).")
                .define("bedMenu", true);
        WELL_RESTED_TICKS = b.comment("Duration of Well Rested after sleeping at least one hour (ticks).")
                .defineInRange("wellRestedTicks", 28800, 0, 10000000);
        b.pop();
        b.push("rest");
        REST_SYNC_MULTIPLAYER = b.comment("Multiplayer: time only passes when every player of the dimension (spectators, prisoners and AFK players",
                        "excepted) is resting, and the first to rest picks the hours for all. Off: no world time skip in multiplayer;",
                        "resting then only restores needs and passes the player's own rest time.")
                .define("syncMultiplayer", true);
        REST_ALLOW_WAIT = b.comment("Allow waiting (sitting on the ground).").define("allowWait", true);
        REST_ALLOW_SLEEP_SKIP = b.comment("Allow time to pass while sleeping in a bed (off: beds only restore the rest need).")
                .define("allowSleepSkip", true);
        REST_AFK_SECONDS = b.comment("A player who has not moved or looked around for this many seconds no longer holds the others back (0 = never).")
                .defineInRange("afkSeconds", 300, 0, 86400);
        REST_FULL_RECHARGE_SECONDS = b.comment("Lying in a bed refills a drained rest bar in this many seconds, no skip needed.")
                .defineInRange("fullRechargeSeconds", 120, 10, 3600);
        REST_BEDROLL_PERCENT = b.comment("Recovery speed on a bedroll or in a tent, in percent of a bed's.")
                .defineInRange("bedrollPercent", 75, 5, 100);
        REST_MAX_SKIP_SECONDS = b.comment("Real seconds the longest fast-forward (24 hours) takes.")
                .defineInRange("maxSkipSeconds", 12, 3, 60);
        b.pop();
        COMMON_SPEC = b.build();

        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        c.push("atmosphere");
        MUSIC = c.comment("Play the Skycraft situational soundtrack (exploration, towns, dungeons, combat...) instead of vanilla music.")
                .define("music", true);
        REALM_FOG = c.comment("Tint the fog of Oblivion (Nether) and Sovngarde (End).").define("realmFog", true);
        COMPASS_RANGE = c.comment("Discovered locations closer than this are shown on the compass.")
                .defineInRange("compassRange", 350, 16, 10000);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private WorldConfig() {}
}

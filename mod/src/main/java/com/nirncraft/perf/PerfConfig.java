package com.nirncraft.perf;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config {@code nirncraft-perf-server.toml}: the pre-generator and baked data switches. */
public final class PerfConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO;
    public static final ModConfigSpec.BooleanValue AUTO_INTEGRATED;
    public static final ModConfigSpec.IntValue AUTO_RADIUS;
    public static final ModConfigSpec.BooleanValue AUTO_ROADS;
    public static final ModConfigSpec.IntValue ROAD_RADIUS;
    public static final ModConfigSpec.BooleanValue PAUSE_WITH_PLAYERS;
    public static final ModConfigSpec.IntValue AHEAD_CHUNKS;
    public static final ModConfigSpec.IntValue MAX_IN_FLIGHT;
    public static final ModConfigSpec.DoubleValue MAX_TICK_MS;
    public static final ModConfigSpec.BooleanValue USE_BAKED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Background pre-generation of full chunks around spawn and along the roads (/nirncraft-pregen).",
                "It only generates ordinary chunks; Distant Horizons builds its LODs from them like from any explored chunk.")
                .push("pregen");
        AUTO = b.comment("Pre-generate automatically on dedicated servers (off by default: multiplayer hosts opt in).")
                .define("auto", false);
        AUTO_INTEGRATED = b.comment("Pre-generate on single-player / LAN / Essential-hosted worlds (throttled: one chunk at a time while you play).")
                .define("autoOnIntegrated", true);
        AUTO_RADIUS = b.comment("Radius around the world spawn for automatic pre-generation, in blocks.")
                .defineInRange("autoRadius", 2048, 0, 30000);
        AUTO_ROADS = b.comment("Automatic pre-generation also covers the road network (within roadRadius).")
                .define("autoRoads", true);
        ROAD_RADIUS = b.comment("How far from spawn road chunks are pre-generated, in blocks.")
                .defineInRange("roadRadius", 6000, 0, 60000);
        PAUSE_WITH_PLAYERS = b.comment("Pause the spawn and last-position spirals while players are online (the frontier ahead of each player still runs; a manual '/nirncraft-pregen start ... now' ignores this).")
                .define("pauseWithPlayers", true);
        AHEAD_CHUNKS = b.comment("How many chunks beyond the view distance the player-following frontier reaches.")
                .defineInRange("aheadChunks", 14, 2, 40);
        MAX_IN_FLIGHT = b.comment("Chunks generating at once; 0 = automatic (half the worldgen threads, a quarter with Distant Horizons).")
                .defineInRange("maxInFlight", 0, 0, 256);
        MAX_TICK_MS = b.comment("Pause while the average server tick takes longer than this (ms). 50 ms = 20 TPS.")
                .defineInRange("maxTickMs", 35.0, 5.0, 1000.0);
        b.pop();
        b.comment("Pre-computed data for the fixed Nirn world (see com.nirncraft.bake).").push("bake");
        USE_BAKED = b.comment("Use the baked data shipped in the mod when the world seed and worldgen data match it.")
                .define("useBaked", true);
        b.pop();
        SPEC = b.build();
    }

    private PerfConfig() {}

    /** Config values are only readable once the server config loaded; fall back to defaults before that. */
    static <T> T get(ModConfigSpec.ConfigValue<T> v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }
}

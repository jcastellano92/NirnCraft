package com.nirncraft.sailing;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Sailing configuration: config/nirncraft-sailing.toml (COMMON, server-authoritative). */
public final class SailingConfig {
    public static final ModConfigSpec SPEC;

    // ------------------------------------------------------------------ ports and ferries
    public static final ModConfigSpec.BooleanValue PORTS;
    public static final ModConfigSpec.BooleanValue FERRIES;
    public static final ModConfigSpec.IntValue FERRY_BASE_FARE;
    public static final ModConfigSpec.IntValue FERRY_BLOCKS_PER_SEPTIM;
    public static final ModConfigSpec.IntValue EXPEDITION_BASE_FARE;
    public static final ModConfigSpec.IntValue SEA_BLOCKS_PER_HOUR;
    public static final ModConfigSpec.BooleanValue MOORED_SHIPS;

    // ------------------------------------------------------------------ ships
    public static final ModConfigSpec.DoubleValue WIND_STRENGTH;
    public static final ModConfigSpec.BooleanValue STORM_DAMAGE;
    public static final ModConfigSpec.DoubleValue COLLISION_DAMAGE;
    public static final ModConfigSpec.IntValue ROWBOAT_PRICE;
    public static final ModConfigSpec.IntValue LONGSHIP_PRICE;
    public static final ModConfigSpec.IntValue COG_PRICE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("ports");
        PORTS = b.comment("Build a pier with a harbormaster at every port of nirncraft/tamriel/ports.json when a player first comes near",
                        "(a settlement's own dock is adopted instead when one is found).")
                .define("ports", true);
        FERRIES = b.comment("Harbormasters sell passage along the sea routes (fast travel with the voyage screen).")
                .define("ferries", true);
        FERRY_BASE_FARE = b.comment("Every passage costs this much, plus one Septim per ferryBlocksPerSeptim blocks of sea route.")
                .defineInRange("ferryBaseFare", 40, 0, 100000);
        FERRY_BLOCKS_PER_SEPTIM = b.defineInRange("ferryBlocksPerSeptim", 18, 1, 100000);
        EXPEDITION_BASE_FARE = b.comment("Base price of an expedition to a foreign land (Atmora, Akavir, Yokuda, Pyandonea, Thras).")
                .defineInRange("expeditionBaseFare", 600, 0, 1000000);
        SEA_BLOCKS_PER_HOUR = b.comment("A voyage advances the clock by one hour per this many blocks of sea route",
                        "(only while a single player is online; 0 = never).")
                .defineInRange("seaBlocksPerHour", 260, 0, 1000000);
        MOORED_SHIPS = b.comment("Moor a ferry ship at each new pier.")
                .define("mooredShips", true);
        b.pop();

        b.push("ships");
        WIND_STRENGTH = b.comment("Multiplier on the wind that fills the sails.")
                .defineInRange("windStrength", 1.0, 0.0, 4.0);
        STORM_DAMAGE = b.comment("Storms far from shore slowly damage ships (more with a full sail).")
                .define("stormDamage", true);
        COLLISION_DAMAGE = b.comment("Hull damage per block/tick of speed when ramming the shore or another ship.")
                .defineInRange("collisionDamage", 40.0, 0.0, 1000.0);
        ROWBOAT_PRICE = b.comment("Harbormasters sell ships for these prices.")
                .defineInRange("rowboatPrice", 180, 0, 1000000);
        LONGSHIP_PRICE = b.defineInRange("longshipPrice", 2400, 0, 1000000);
        COG_PRICE = b.defineInRange("cogPrice", 3600, 0, 1000000);
        b.pop();
        SPEC = b.build();
    }

    private SailingConfig() {}

    public static int price(ShipType type) {
        return switch (type) {
            case ROWBOAT -> ROWBOAT_PRICE.get();
            case LONGSHIP -> LONGSHIP_PRICE.get();
            case COG -> COG_PRICE.get();
        };
    }
}

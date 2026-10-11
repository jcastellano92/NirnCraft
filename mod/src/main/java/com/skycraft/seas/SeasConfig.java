package com.skycraft.seas;

import net.minecraftforge.common.ForgeConfigSpec;

/** Seas and skies configuration: config/skycraft-seas.toml (COMMON, server-authoritative). */
public final class SeasConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue SEA_TRAFFIC;
    public static final ForgeConfigSpec.IntValue TRAFFIC_INTERVAL;
    public static final ForgeConfigSpec.IntValue TRAFFIC_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue TRAFFIC_MAX;
    public static final ForgeConfigSpec.DoubleValue GHOST_SHIP_CHANCE;
    public static final ForgeConfigSpec.BooleanValue PIRATES_ATTACK;

    public static final ForgeConfigSpec.BooleanValue SEA_LIFE;
    public static final ForgeConfigSpec.IntValue SEA_LIFE_BUDGET;
    public static final ForgeConfigSpec.DoubleValue SERPENT_CHANCE;
    public static final ForgeConfigSpec.BooleanValue HAWKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("traffic");
        SEA_TRAFFIC = b.comment("NPC ships sail Nirn's seas near players at sea: merchants, the Imperial navy, Nord longships, pirates,",
                        "Maormer warships off Pyandonea, Sload hulks off Thras, and ghost ships at night.")
                .define("seaTraffic", true);
        TRAFFIC_INTERVAL = b.comment("Seconds between attempts to put a ship on the sea near each player at sea.")
                .defineInRange("interval", 45, 5, 3600);
        TRAFFIC_PER_PLAYER = b.comment("Ships of the traffic within 256 blocks of a player at most.")
                .defineInRange("perPlayer", 3, 0, 16);
        TRAFFIC_MAX = b.comment("Ships of the traffic in the whole world at most.")
                .defineInRange("max", 12, 0, 64);
        GHOST_SHIP_CHANCE = b.comment("Chance that a ship appearing at night is a ghost ship.")
                .defineInRange("ghostShipChance", 0.05, 0.0, 1.0);
        PIRATES_ATTACK = b.comment("Pirate, Maormer and Sload ships give chase and board players' ships.")
                .define("piratesAttack", true);
        b.pop();
        b.push("life");
        SEA_LIFE = b.comment("Sea creatures around players at sea and underwater: dreugh, sea netch, slaughterfish, whales, schools of",
                        "fish; the rare sea serpent of the open ocean and the sea leviathan of the Maormer waters.")
                .define("seaLife", true);
        SEA_LIFE_BUDGET = b.comment("Sea creatures this spawner keeps around one player at most.")
                .defineInRange("budget", 10, 0, 64);
        SERPENT_CHANCE = b.comment("Chance per attempt over the deep open ocean that a sea serpent rises.")
                .defineInRange("serpentChance", 0.02, 0.0, 1.0);
        HAWKS = b.comment("Hawks circle over Skyrim's tundra, crags and coasts (huntable: feathers, beaks, eggs).")
                .define("hawks", true);
        b.pop();
        SPEC = b.build();
    }

    private SeasConfig() {}
}

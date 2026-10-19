package com.nirncraft.arsenal;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Arsenal configuration: {@code config/nirncraft-arsenal-common.toml} (server-authoritative rules) and
 * {@code config/nirncraft-arsenal-client.toml} (kill cam presentation, per player).
 */
public final class ArsenalConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // common
    public static final ModConfigSpec.BooleanValue HEADSHOTS;
    public static final ModConfigSpec.DoubleValue HEADSHOT_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue RECORD_ARROWS;
    public static final ModConfigSpec.DoubleValue MOB_WEAPON_DROP_CHANCE;
    public static final ModConfigSpec.BooleanValue KILL_CAMS;
    public static final ModConfigSpec.DoubleValue KILL_CAM_LAST_ENEMY_CHANCE;
    public static final ModConfigSpec.DoubleValue KILL_CAM_OTHER_CHANCE;

    // client
    public static final ModConfigSpec.BooleanValue CLIENT_KILL_CAMS;
    public static final ModConfigSpec.BooleanValue KILL_CAM_NEAR_PLAYERS;
    public static final ModConfigSpec.BooleanValue KILL_CAM_HIDE_HUD;
    public static final ModConfigSpec.DoubleValue KILL_CAM_ROLL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("archery");
        HEADSHOTS = b.comment("Arrows that hit a humanoid's head deal extra damage.").define("headshots", true);
        HEADSHOT_MULTIPLIER = b.defineInRange("headshotMultiplier", 1.5, 1.0, 10.0);
        RECORD_ARROWS = b.comment("Arrows that hit a mob are kept on its body (looted from its corpse).").define("arrowsStayInBodies", true);
        b.pop();
        b.push("loot");
        MOB_WEAPON_DROP_CHANCE = b.comment("Chance that a humanoid hostile killed by a player drops a leveled Skyrim weapon.")
                .defineInRange("mobWeaponDropChance", 0.06, 0.0, 1.0);
        b.pop();
        b.push("killcams");
        KILL_CAMS = b.comment("Server switch for kill cams (each player can also turn them off in the client config).").define("enabled", true);
        KILL_CAM_LAST_ENEMY_CHANCE = b.comment("Chance of a kill cam when killing the last enemy fighting you.")
                .defineInRange("lastEnemyChance", 1.0, 0.0, 1.0);
        KILL_CAM_OTHER_CHANCE = b.comment("Chance of a kill cam for other killing blows on hostile creatures.")
                .defineInRange("otherChance", 0.2, 0.0, 1.0);
        b.pop();
        COMMON_SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        c.push("killcams");
        CLIENT_KILL_CAMS = c.comment("Show Skyrim-style kill cams on killing blows.").define("enabled", true);
        KILL_CAM_NEAR_PLAYERS = c.comment("Also show kill cams while other players are within 32 blocks.").define("whenPlayersNearby", false);
        KILL_CAM_HIDE_HUD = c.comment("Hide the HUD during kill cams.").define("hideHud", true);
        KILL_CAM_ROLL = c.comment("Maximum camera roll (degrees) during melee kill cams.").defineInRange("roll", 6.0, 0.0, 30.0);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private ArsenalConfig() {}
}

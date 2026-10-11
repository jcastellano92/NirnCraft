package com.skycraft.dungeons;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLLoader;

/** Dungeons configuration: config/skycraft-dungeons.toml (COMMON, server-authoritative). */
public final class DungeonsConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue TELEMETRY;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("telemetry");
        TELEMETRY = b.comment("Log every player's dungeon runs (enter and clear time, deaths, chests opened, loot value, boss killed)",
                        "to logs/skycraft-dungeons.csv for playtests. On by default in development, off in the released pack.")
                .define("enabled", !FMLLoader.isProduction());
        b.pop();
        SPEC = b.build();
    }

    private DungeonsConfig() {}
}

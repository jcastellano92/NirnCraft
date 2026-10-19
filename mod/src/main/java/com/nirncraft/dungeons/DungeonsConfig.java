package com.nirncraft.dungeons;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.loading.FMLLoader;

/** Dungeons configuration: config/nirncraft-dungeons.toml (COMMON, server-authoritative). */
public final class DungeonsConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue TELEMETRY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("telemetry");
        TELEMETRY = b.comment("Log every player's dungeon runs (enter and clear time, deaths, chests opened, loot value, boss killed)",
                        "to logs/nirncraft-dungeons.csv for playtests. On by default in development, off in the released pack.")
                .define("enabled", !FMLLoader.getCurrent().isProduction());
        b.pop();
        SPEC = b.build();
    }

    private DungeonsConfig() {}
}

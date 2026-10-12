package com.skycraft.scatter;

import net.minecraftforge.common.ForgeConfigSpec;

/** Scatter settings: {@code config/skycraft-scatter.toml}. */
public final class ScatterConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue DENSITY;
    public static final ForgeConfigSpec.BooleanValue DISCOVERIES;
    public static final ForgeConfigSpec.DoubleValue DISCOVERY_XP;
    public static final ForgeConfigSpec.BooleanValue FOUND_MESSAGES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("scatter");
        ENABLED = b.comment("Place ambient scatter (debris, leftovers of camps, natural formations) outside settlements.",
                "Changing this only affects newly generated chunks.").define("enabled", true);
        DENSITY = b.comment("Multiplier on how many props each chunk gets (1 = designed density, 0.5 = half).")
                .defineInRange("density", 1.0, 0.0, 2.0);
        DISCOVERIES = b.comment("Hidden stashes and rare natural formations in the wild.").define("discoveries", true);
        DISCOVERY_XP = b.comment("Athletics experience for coming upon a stash or formation for the first time; 0 turns it off.")
                .defineInRange("discoveryXp", 3.0, 0.0, 1000.0);
        FOUND_MESSAGES = b.comment("Show a quiet line the first time you come upon a stash or formation.").define("foundMessages", true);
        b.pop();
        SPEC = b.build();
    }

    private ScatterConfig() {}

    public static double density() {
        try {
            return ENABLED.get() ? DENSITY.get() : 0.0;
        } catch (IllegalStateException e) {
            return 1.0; // worldgen threads may ask before the config is loaded
        }
    }

    public static boolean discoveries() {
        try {
            return ENABLED.get() && DISCOVERIES.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    static double xp() {
        try {
            return DISCOVERY_XP.get();
        } catch (IllegalStateException e) {
            return 3.0;
        }
    }

    static boolean messages() {
        try {
            return FOUND_MESSAGES.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }
}

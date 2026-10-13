package com.skycraft.fire;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Settings of the fire module. {@code skycraft-fire-client.toml}: the far fire renderer and the volume of the fire and
 * aftermath ambience. {@code skycraft-fire.toml} (common): how far fire sites are synced and what standing in a big
 * fire does.
 */
public final class FireConfig {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec COMMON_SPEC;

    /** Draw fire sites (flames, glow and smoke columns) out to {@link #FAR_FIRE_DISTANCE} blocks, over any terrain. */
    public static final ForgeConfigSpec.BooleanValue FAR_FIRE;
    public static final ForgeConfigSpec.IntValue FAR_FIRE_DISTANCE;
    public static final ForgeConfigSpec.IntValue FAR_FIRE_MAX_SITES;
    public static final ForgeConfigSpec.BooleanValue FAR_SMOKE;
    public static final ForgeConfigSpec.DoubleValue AMBIENT_VOLUME;
    public static final ForgeConfigSpec.BooleanValue AFTERMATH_MUSIC;

    public static final ForgeConfigSpec.IntValue SYNC_RADIUS;
    public static final ForgeConfigSpec.BooleanValue BIG_FIRE_HURTS;

    static {
        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        c.comment("Distant fire: burning towns stay visible from the road, as flame, glow and a rising smoke column.").push("farFire");
        FAR_FIRE = c.comment("Draw the far fire renderer (billboards drawn after the particles, independent of particle distance).")
                .define("farFire", true);
        FAR_FIRE_DISTANCE = c.comment("Farthest distance, in blocks, at which fire sites are drawn.").defineInRange("distance", 640, 96, 1536);
        FAR_FIRE_MAX_SITES = c.comment("Most fire sites drawn per frame (nearest first).").defineInRange("maxSites", 48, 4, 256);
        FAR_SMOKE = c.comment("Draw smoke columns over fire sites (turn off if smoke costs frames).").define("smoke", true);
        c.pop();
        c.comment("Ambience of fires and ruined towns (crackle, creaking timber, sobbing). Positional, with cooldowns.").push("ambience");
        AMBIENT_VOLUME = c.comment("Volume multiplier of the fire and aftermath ambience sounds.").defineInRange("volume", 0.6, 0.0, 1.5);
        AFTERMATH_MUSIC = c.comment("Play the aftermath music inside a place that was just attacked (Helgen).").define("aftermathMusic", true);
        c.pop();
        CLIENT_SPEC = c.build();

        ForgeConfigSpec.Builder s = new ForgeConfigSpec.Builder();
        s.comment("Fire sites (server-defined fires drawn far away) and the big fire block family.").push("fire");
        SYNC_RADIUS = s.comment("Radius, in blocks, within which a player is told about fire sites.").defineInRange("syncRadius", 1100, 128, 4096);
        BIG_FIRE_HURTS = s.comment("Standing in a big fire block sets you alight and hurts (it never spreads or burns blocks).")
                .define("bigFireHurts", true);
        s.pop();
        COMMON_SPEC = s.build();
    }

    private FireConfig() {}
}

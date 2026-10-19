package com.nirncraft.fire;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings of the fire module. {@code nirncraft-fire-client.toml}: the far fire renderer and the volume of the fire and
 * aftermath ambience. {@code nirncraft-fire.toml} (common): how far fire sites are synced and what standing in a big
 * fire does.
 */
public final class FireConfig {
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec COMMON_SPEC;

    /** Draw fire sites (flames, glow and smoke columns) out to {@link #FAR_FIRE_DISTANCE} blocks, over any terrain. */
    public static final ModConfigSpec.BooleanValue FAR_FIRE;
    public static final ModConfigSpec.IntValue FAR_FIRE_DISTANCE;
    public static final ModConfigSpec.IntValue FAR_FIRE_MAX_SITES;
    public static final ModConfigSpec.BooleanValue FAR_SMOKE;
    public static final ModConfigSpec.DoubleValue AMBIENT_VOLUME;
    public static final ModConfigSpec.BooleanValue AFTERMATH_MUSIC;

    public static final ModConfigSpec.IntValue SYNC_RADIUS;
    public static final ModConfigSpec.BooleanValue BIG_FIRE_HURTS;

    static {
        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
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

        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        s.comment("Fire sites (server-defined fires drawn far away) and the big fire block family.").push("fire");
        SYNC_RADIUS = s.comment("Radius, in blocks, within which a player is told about fire sites.").defineInRange("syncRadius", 1100, 128, 4096);
        BIG_FIRE_HURTS = s.comment("Standing in a big fire block sets you alight and hurts (it never spreads or burns blocks).")
                .define("bigFireHurts", true);
        s.pop();
        COMMON_SPEC = s.build();
    }

    private FireConfig() {}
}

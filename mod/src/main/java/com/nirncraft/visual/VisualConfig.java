package com.nirncraft.visual;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client presentation settings: {@code config/nirncraft-visual-client.toml}. Every feature can be turned off. */
public final class VisualConfig {
    public static final ModConfigSpec CLIENT_SPEC;

    public static final ModConfigSpec.BooleanValue PARTICLES;
    public static final ModConfigSpec.IntValue PARTICLE_BUDGET;
    public static final ModConfigSpec.IntValue PARTICLE_RANGE;
    public static final ModConfigSpec.BooleanValue FROST_BREATH;
    public static final ModConfigSpec.BooleanValue DRIFTING_SNOW;
    public static final ModConfigSpec.BooleanValue FALLING_LEAVES;
    public static final ModConfigSpec.BooleanValue FIREFLIES;
    public static final ModConfigSpec.BooleanValue ASH;
    public static final ModConfigSpec.BooleanValue EMBERS;
    public static final ModConfigSpec.BooleanValue SAND;
    public static final ModConfigSpec.BooleanValue MARSH_WISPS;
    public static final ModConfigSpec.BooleanValue DUST_MOTES;

    public static final ModConfigSpec.BooleanValue TARGET_BAR;
    public static final ModConfigSpec.BooleanValue SKY_CROSSHAIR;
    public static final ModConfigSpec.BooleanValue STYLED_BOSS_BAR;
    public static final ModConfigSpec.BooleanValue STYLED_BANNERS;

    public static final ModConfigSpec.BooleanValue LOADING_SCREEN;
    public static final ModConfigSpec.BooleanValue SKIN_VANILLA_SCREENS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Atmospheric particles. They also follow Video Settings > Particles (Decreased halves them, Minimal turns them off).")
                .push("particles");
        PARTICLES = b.comment("Master switch for every atmospheric particle below.").define("enabled", true);
        PARTICLE_BUDGET = b.comment("Most atmospheric particles alive at once.").defineInRange("budget", 600, 0, 4000);
        PARTICLE_RANGE = b.comment("Particles farther than this many blocks from the camera are removed.").defineInRange("range", 40, 8, 128);
        FROST_BREATH = b.comment("Visible breath for players and people in the cold.").define("frostBreath", true);
        DRIFTING_SNOW = b.comment("Snow blown along snowy ground in the wind.").define("driftingSnow", true);
        FALLING_LEAVES = b.comment("Leaves drifting down under trees, coloured like the tree.").define("fallingLeaves", true);
        FIREFLIES = b.comment("Fireflies in warm forests and meadows on clear nights.").define("fireflies", true);
        ASH = b.comment("Ash flakes drifting in volcanic regions.").define("ash", true);
        EMBERS = b.comment("Embers rising from nearby lava.").define("embers", true);
        SAND = b.comment("Blown sand and dust in deserts.").define("sand", true);
        MARSH_WISPS = b.comment("Low fog wisps over marshes.").define("marshWisps", true);
        DUST_MOTES = b.comment("Dust motes floating in caves and dungeons.").define("dustMotes", true);
        b.pop();

        b.push("hud");
        TARGET_BAR = b.comment("Enemy health bar at the top of the screen for whatever you're fighting or aiming at.").define("targetBar", true);
        SKY_CROSSHAIR = b.comment("Skyrim-style crosshair instead of the vanilla cross.").define("crosshair", true);
        STYLED_BOSS_BAR = b.comment("Ornate boss health bar (off: plain bar).").define("styledBossBar", true);
        STYLED_BANNERS = b.comment("Ornate level-up, skill, quest and discovery banners.").define("styledBanners", true);
        b.pop();

        b.push("menus");
        LOADING_SCREEN = b.comment("Skyrim-style loading screen with lore tips while joining a world or changing dimension.")
                .define("loadingScreen", true);
        SKIN_VANILLA_SCREENS = b.comment("Restyle the death screen, confirmation dialogs and progress screens to match the NirnCraft menus.")
                .define("skinVanillaScreens", true);
        b.pop();
        CLIENT_SPEC = b.build();
    }

    private VisualConfig() {}
}

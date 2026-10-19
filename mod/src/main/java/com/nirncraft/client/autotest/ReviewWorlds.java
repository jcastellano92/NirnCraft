package com.nirncraft.client.autotest;

import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Native world creation shared by visual reviews; each scene still supplies its real dimensions and seed. */
public final class ReviewWorlds {
    private ReviewWorlds() {}

    public static LevelSettings settings(String name, GameType gameType, boolean hardcore,
                                         Difficulty difficulty, boolean commands, WorldDataConfiguration configuration) {
        return new LevelSettings(name, gameType,
                new LevelSettings.DifficultySettings(difficulty, hardcore, false), commands, configuration);
    }
    public static WorldDimensions normal(HolderLookup.Provider registries) {
        return registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
    }
    public static void create(Minecraft minecraft, String id, LevelSettings settings, WorldOptions options,
                              Function<HolderLookup.Provider, WorldDimensions> dimensions, Screen parent) {
        minecraft.createWorldOpenFlows().createFreshLevel(id, settings, options, dimensions, parent);
    }
    public static void open(Minecraft minecraft, String id, Screen parent) {
        minecraft.createWorldOpenFlows().openWorld(id, () -> minecraft.gui.setScreen(parent));
    }
    public static void hudHidden(Minecraft minecraft, boolean hidden) {
        if (minecraft.gui.hud.isHidden() != hidden) minecraft.gui.hud.toggle();
    }
}

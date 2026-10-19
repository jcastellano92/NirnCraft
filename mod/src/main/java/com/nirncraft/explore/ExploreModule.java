package com.nirncraft.explore;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Exploration: Skyrim's "something worth finding every few minutes". Minor sites between the named places (camps,
 * wayshrines, treasure caches and their maps, cairns, culture shrines and wonders), unique items hidden once per
 * world, notes that lead to places, skill books in the wild, a little Athletics experience for discoveries, and the
 * density audit that measures it all. See docs/EXPLORATION.md.
 */
public final class ExploreModule {
    private ExploreModule() {}

    public static void init(IEventBus modBus) {
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, ExploreConfig.SPEC, "nirncraft-explore.toml");
        ExploreRegistry.init(modBus);
        ExploreLoot.init(modBus);
        SkillBookModifier.init(modBus);
        ExploreNotes.register();
    }
}

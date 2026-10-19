package com.nirncraft.lore.place;

import net.neoforged.bus.api.IEventBus;

/**
 * Place lore: the stories, readables, mini-quests and regional rumours of Tamriel's named places ({@link PlaceLore}).
 * Registered from {@link com.nirncraft.lore.LoreModule#init}. See docs/LORE.md.
 */
public final class PlaceLoreModule {
    private PlaceLoreModule() {}

    public static void init(IEventBus modBus) {
        PlaceLoreLoot.init(modBus);
        PlaceLoreDialogue.register();
    }
}

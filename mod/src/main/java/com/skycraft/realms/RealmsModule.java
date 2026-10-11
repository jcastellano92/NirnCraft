package com.skycraft.realms;

import com.skycraft.realms.creatures.RealmCreatures;
import com.skycraft.realms.worldgen.RealmWorldgen;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * The planes of Oblivion and the afterlife as real realms: the Deadlands (the Nether's worldgen), Sovngarde (the End's),
 * the Shivering Isles, Apocrypha, the Soul Cairn and Coldharbour. Terrain, biomes, structures, ways in and out, the
 * realms' creatures and lore. See docs/FEATURES.md ("Realms") and {@link Realm}.
 */
public final class RealmsModule {
    private RealmsModule() {}

    public static void init(IEventBus modBus) {
        RealmBlocks.BLOCKS.register(modBus);
        RealmBlocks.ITEMS.register(modBus);
        RealmItems.ITEMS.register(modBus);
        RealmWorldgen.init(modBus);
        RealmCreatures.init();
        RealmLore.init();
    }
}

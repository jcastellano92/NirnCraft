package com.skycraft.quest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Parties, quests, radiant bounties, factions (Companions, College, Thieves Guild, Dark Brotherhood, Legion,
 * Stormcloaks, Bards College), the main quest "The Dragonborn" and the journal.
 *
 * <p>Forge-bus handlers: {@link QuestEvents}, {@link com.skycraft.quest.party.PartyEvents};
 * client: {@code com.skycraft.quest.client}.</p>
 */
public final class QuestModule {
    private QuestModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        QuestItems.init(modBus);
        com.skycraft.quest.story.StartCottage.register(modBus);
        com.skycraft.Skycraft.config(ModConfig.Type.LOCAL, QuestConfig.SPEC, "skycraft-quest.toml");
        QuestDialogue.register();
        com.skycraft.quest.faction.FactionDialogue.register();
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        QuestPackets.register();
    }
}

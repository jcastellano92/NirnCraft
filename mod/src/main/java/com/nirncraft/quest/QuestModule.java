package com.nirncraft.quest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Parties, quests, radiant bounties, factions (Companions, College, Thieves Guild, Dark Brotherhood, Legion,
 * Stormcloaks, Bards College), the main quest "The Dragonborn" and the journal.
 *
 * <p>Forge-bus handlers: {@link QuestEvents}, {@link com.nirncraft.quest.party.PartyEvents};
 * client: {@code com.nirncraft.quest.client}.</p>
 */
public final class QuestModule {
    private QuestModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        QuestItems.init(modBus);
        com.nirncraft.quest.story.StartCottage.register(modBus);
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, QuestConfig.SPEC, "nirncraft-quest.toml");
        QuestDialogue.register();
        com.nirncraft.quest.faction.FactionDialogue.register();
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        QuestPackets.register();
    }
}

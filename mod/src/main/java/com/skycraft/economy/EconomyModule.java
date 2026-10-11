package com.skycraft.economy;

import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Item values, barter menus, merchants, skill trainers, skill books and Speech.
 *
 * <ul>
 *   <li>{@link ItemValues}: data-driven gold values ({@code data/<ns>/skycraft_values/*.json}) + heuristic fallback.</li>
 *   <li>{@link Merchants}/{@link Shop}: villagers and wandering traders run shops stocked from {@code #skycraft:merchant/*}.</li>
 *   <li>{@link Barter}: the server side of the barter menu ({@code client.BarterScreen}).</li>
 *   <li>{@link Trainers}: Skyrim skill trainers. {@link SkillBookItem}: Skyrim skill books.</li>
 *   <li>{@link EconomyDialogue}: conversation topics (barter, invest, train, bulk selling).</li>
 * </ul>
 */
public final class EconomyModule {
    private EconomyModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        EconomyItems.init(modBus);
        EconomyLoot.init(modBus);
        EconomyDialogue.register();
        // a homestead deed bought from a steward counts as the drafting-table plot purchase
        com.skycraft.homestead.Homesteads.setPlotOwnership((player, plotId) -> Houses.HOUSES.stream()
                .anyMatch(h -> h.homestead() && h.placeId().equals(plotId) && Houses.isHouseOwner(player, h)));
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        EconomyPackets.register();
    }
}

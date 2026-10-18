package com.skycraft.seas;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Seas and skies: what makes Nirn's oceans, coasts, islands and skies worth exploring. Started from
 * {@code sailing.SailingModule} (its sister module: walkable decks, ships and liveries live there).
 *
 * <ul>
 *     <li>Life at sea ({@code traffic.*}): NPC ships on the sea lanes, crewed by society NPCs or the dead, with
 *     pirates that chase, lash alongside and board, ghost ships by night, prizes for whoever kills a crew.</li>
 *     <li>Sea creatures ({@link SeaSpecs}, {@link SeasRegistry}): dreugh and their queen, the sea leviathan, giant
 *     slaughterfish, sea netch, whales, giant and coral crabs, seals, hawks; schools of river betties and Abecean
 *     longfins; the sea serpent becomes a rare boss of the deep open ocean. Spawned by {@link SeaLife}.</li>
 *     <li>Sea and coast sites ({@code world.*}): sunken Yokudan ruins, Sload coral spires, Dreugh lairs, sunken Maormer
 *     warships, Dwemer air-pocket ruins, Nordic ship graves, slaughterfish nests, serpent lairs; kelp forests, coral
 *     reefs and pearl beds of kollops; islets with lighthouses, pirate coves, tidal caves, rookeries, sea stacks,
 *     hermits and wrecks; coastal smugglers' caves, whale bones, horker haul-outs.</li>
 *     <li>Skies (client, {@code client.*}): ambient flocks of crows, ravens, gulls and wheeling hawks drawn on the
 *     client only, salmon leaping in the rivers.</li>
 *     <li>{@code /skycraft-seas}: {@link SeasCommands}.</li>
 * </ul>
 */
public final class SeasModule {
    private SeasModule() {}

    public static void init(IEventBus modBus) {
        com.skycraft.Skycraft.config(ModConfig.Type.LOCAL, SeasConfig.SPEC, "skycraft-seas.toml");
        SeasRegistry.init(modBus);
        SeasLore.register();
    }
}

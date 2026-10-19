package com.nirncraft.survival;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Skyrim food and drink, cooking, inns and renting rooms, diseases and shrines, regenerating ore veins.
 *
 * <ul>
 *     <li>Food & drink: {@link SurvivalRegistry} items ({@link com.nirncraft.survival.item.SkyFoodItem}), crops, the
 *     {@code nirncraft:cooking_pot} ({@link com.nirncraft.survival.cooking.CookingRecipes}).</li>
 *     <li>Inns: {@link com.nirncraft.survival.inn.Innkeepers} (one per settlement of the roads registry) and
 *     {@link SurvivalDialogue}.</li>
 *     <li>Diseases (contract 24): {@link Diseases}; cures by {@code nirncraft:cure_disease}, {@link Shrines} and priests.</li>
 *     <li>Ore veins: {@link OreVeins} and {@code nirncraft:depleted_ore}.</li>
 *     <li>Survival Mode ({@link com.nirncraft.survival.mode.SurvivalMode}): hunger, fatigue and cold, with warmth from
 *     clothing.</li>
 *     <li>Camping ({@code camping}: bedrolls, tents, campfire kits), fishing ({@code fishing}: rods, the regional fish
 *     of Nirn, the reel-in minigame) and player bodies ({@code corpse}).</li>
 *     <li>Hearthfire homesteads have their own package ({@link com.nirncraft.homestead.Homesteads}).</li>
 * </ul>
 */
public final class SurvivalModule {
    private SurvivalModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        SurvivalEffects.init(modBus);
        SurvivalRegistry.init(modBus);
        com.nirncraft.survival.fishing.FishingRegistry.init(modBus);
        com.nirncraft.homestead.HomesteadRegistry.init(modBus);
        com.nirncraft.survival.mode.SurvivalMode.init(modBus);
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, SurvivalConfig.SPEC, "nirncraft-survival.toml");
        SurvivalDialogue.register();
        SurvivalEvents.register();
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        SurvivalPackets.register();
    }
}

package com.skycraft;

import com.mojang.logging.LogUtils;
import com.skycraft.core.PlayerData;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.network.SkyNetwork;
import com.skycraft.registry.ModCreativeTab;
import com.skycraft.registry.ModEffects;
import com.skycraft.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * Skycraft Core: turns Minecraft into an Elder Scrolls V-style RPG.
 *
 * <p>The core owns player data, skills, perks, leveling, vitals, combat, digging rules and the HUD. Feature
 * modules ({@code magic}, {@code creatures}, {@code economy}, {@code crime}, {@code quest}, {@code crafting},
 * {@code world}) register their content through their {@code init} methods below.</p>
 */
@Mod(Skycraft.MODID)
public class Skycraft {
    public static final String MODID = "skycraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static ModContainer container;

    public Skycraft(IEventBus modBus, ModContainer owner) {
        container=owner;
        config(ModConfig.Type.LOCAL, SkyConfig.SPEC, "skycraft-common.toml");
        com.skycraft.core.SkyData.init(modBus);
        PlacedBlocks.init(modBus);

        ModItems.init(modBus);
        ModEffects.init(modBus);
        ModCreativeTab.init(modBus);
        com.skycraft.loot.LootModule.init(modBus);

        com.skycraft.magic.MagicModule.init(modBus);
        com.skycraft.creatures.CreaturesModule.init(modBus);
        com.skycraft.economy.EconomyModule.init(modBus);
        com.skycraft.crime.CrimeModule.init(modBus);
        com.skycraft.quest.QuestModule.init(modBus);
        com.skycraft.crafting.CraftingModule.init(modBus);
        com.skycraft.world.WorldModule.init(modBus);
        com.skycraft.roads.RoadsModule.init(modBus);
        com.skycraft.inventory.InventoryModule.init(modBus);
        com.skycraft.survival.SurvivalModule.init(modBus);
        com.skycraft.lore.LoreModule.init(modBus);
        com.skycraft.fauna.FaunaModule.init(modBus);
        com.skycraft.dungeons.DungeonsModule.init(modBus);
        com.skycraft.society.SocietyModule.init(modBus);
        com.skycraft.arsenal.ArsenalModule.init(modBus);
        com.skycraft.atmosphere.AtmosphereModule.init(modBus);
        com.skycraft.tamriel.TamrielModule.init(modBus);
        com.skycraft.perf.PerfModule.init(modBus);
        com.skycraft.audio.AudioModule.init(modBus);
        com.skycraft.curse.CurseModule.init(modBus);
        com.skycraft.sailing.SailingModule.init(modBus);
        com.skycraft.guide.GuideModule.init(modBus);
        com.skycraft.realms.RealmsModule.init(modBus);
        com.skycraft.explore.ExploreModule.init(modBus);
        com.skycraft.scatter.ScatterModule.init(modBus);
        com.skycraft.landmarks.LandmarksModule.init(modBus);
        com.skycraft.worldplan.street.StreetPlanModule.init(modBus);
        com.skycraft.protect.ProtectModule.init(modBus);
        com.skycraft.fire.FireModule.init(modBus);

        modBus.addListener(this::commonSetup);
        SkyNetwork.init(modBus);
    }

    public static void config(ModConfig.Type type,net.neoforged.neoforge.common.ModConfigSpec spec) {
        container.registerConfig(type,spec);
    }

    public static void config(ModConfig.Type type,net.neoforged.neoforge.common.ModConfigSpec spec,String filename) {
        container.registerConfig(type,spec,filename);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Skycraft Core ready: the Dragonborn comes.");
    }
}

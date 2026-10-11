package com.skycraft;

import com.mojang.logging.LogUtils;
import com.skycraft.core.PlayerData;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.network.SkyNetwork;
import com.skycraft.registry.ModCreativeTab;
import com.skycraft.registry.ModEffects;
import com.skycraft.registry.ModItems;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

    public Skycraft() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SkyConfig.SPEC);

        ModItems.init(modBus);
        ModEffects.init(modBus);
        ModCreativeTab.init(modBus);
        com.skycraft.loot.AddTableModifier.init(modBus);

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
        com.skycraft.audio.AudioModule.init(modBus);
        com.skycraft.curse.CurseModule.init(modBus);

        modBus.addListener(this::registerCapabilities);
        modBus.addListener(this::commonSetup);
        SkyNetwork.init();
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.register(PlayerData.class);
        event.register(PlacedBlocks.Store.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Skycraft Core ready: the Dragonborn comes.");
    }
}

package com.nirncraft;

import com.mojang.logging.LogUtils;
import com.nirncraft.core.PlayerData;
import com.nirncraft.dig.PlacedBlocks;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.registry.ModCreativeTab;
import com.nirncraft.registry.ModEffects;
import com.nirncraft.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * NirnCraft Core: turns Minecraft into an Elder Scrolls V-style RPG.
 *
 * <p>The core owns player data, skills, perks, leveling, vitals, combat, digging rules and the HUD. Feature
 * modules ({@code magic}, {@code creatures}, {@code economy}, {@code crime}, {@code quest}, {@code crafting},
 * {@code world}) register their content through their {@code init} methods below.</p>
 */
@Mod(NirnCraft.MODID)
public class NirnCraft {
    public static final String MODID = "nirncraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static ModContainer container;

    /**
     * The test harness's per-run flags (tools/harness/game.py): <game dir>/nirncraft-harness-flags.properties, applied
     * as system properties before anything reads them, unless the JVM already has one. The launcher's JvmArgs are not a
     * reliable channel: Prism keeps its own copy of the instance settings and wrote it back over the harness's edit at
     * launch (flags silently missing from runs).
     */
    private static void harnessFlags() {
        java.nio.file.Path f = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("nirncraft-harness-flags.properties");
        java.util.Properties props = new java.util.Properties();
        if (java.nio.file.Files.isRegularFile(f)) {
            try (var in = java.nio.file.Files.newBufferedReader(f)) {
                props.load(in);
            } catch (java.io.IOException e) {
                LOGGER.warn("NirnCraft: harness flags not read from {}: {}", f, e.toString());
                return;
            }
        } else if (System.getProperty("nirncraft.harness") == null) {
            return;
        }
        // In a harness run the file is the group's word: Prism rewrites JvmArgs from its own stale copy at launch (another
        // worktree's group, e.g. clientGen / unified), which used to win over the file. The file overrides, and the group
        // switches it does not name are cleared (2026-10-25: the player hold's tests waited on a client generator they had
        // switched off).
        boolean harness = System.getProperty("nirncraft.harness") != null;
        if (harness)
            for (String k : System.getProperties().stringPropertyNames())
                if ((k.startsWith("nirncraft.atlas.") || k.startsWith("nirncraft.hold.") || k.equals("nirncraft.autotest.intro") || k.equals("nirncraft.autotest.keepWorld")) && !props.containsKey(k))
                    System.clearProperty(k);
        for (String k : props.stringPropertyNames())
            if (harness || System.getProperty(k) == null) System.setProperty(k, props.getProperty(k));
        LOGGER.info("NirnCraft: harness flags {}", props);
    }

    public NirnCraft(IEventBus modBus, ModContainer owner) {
        container=owner;
        harnessFlags();
        config(ModConfig.Type.LOCAL, SkyConfig.SPEC, "nirncraft-common.toml");
        com.nirncraft.core.SkyData.init(modBus);
        PlacedBlocks.init(modBus);

        ModItems.init(modBus);
        ModEffects.init(modBus);
        ModCreativeTab.init(modBus);
        com.nirncraft.loot.LootModule.init(modBus);

        com.nirncraft.magic.MagicModule.init(modBus);
        com.nirncraft.creatures.CreaturesModule.init(modBus);
        com.nirncraft.economy.EconomyModule.init(modBus);
        com.nirncraft.crime.CrimeModule.init(modBus);
        com.nirncraft.quest.QuestModule.init(modBus);
        com.nirncraft.crafting.CraftingModule.init(modBus);
        com.nirncraft.world.WorldModule.init(modBus);
        com.nirncraft.roads.RoadsModule.init(modBus);
        com.nirncraft.inventory.InventoryModule.init(modBus);
        com.nirncraft.survival.SurvivalModule.init(modBus);
        com.nirncraft.lore.LoreModule.init(modBus);
        com.nirncraft.fauna.FaunaModule.init(modBus);
        com.nirncraft.dungeons.DungeonsModule.init(modBus);
        com.nirncraft.society.SocietyModule.init(modBus);
        com.nirncraft.arsenal.ArsenalModule.init(modBus);
        com.nirncraft.atmosphere.AtmosphereModule.init(modBus);
        com.nirncraft.tamriel.TamrielModule.init(modBus);
        com.nirncraft.perf.PerfModule.init(modBus);
        com.nirncraft.audio.AudioModule.init(modBus);
        com.nirncraft.curse.CurseModule.init(modBus);
        com.nirncraft.sailing.SailingModule.init(modBus);
        com.nirncraft.guide.GuideModule.init(modBus);
        com.nirncraft.realms.RealmsModule.init(modBus);
        com.nirncraft.explore.ExploreModule.init(modBus);
        com.nirncraft.scatter.ScatterModule.init(modBus);
        com.nirncraft.landmarks.LandmarksModule.init(modBus);
        com.nirncraft.worldplan.street.StreetPlanModule.init(modBus);
        com.nirncraft.protect.ProtectModule.init(modBus);
        com.nirncraft.fire.FireModule.init(modBus);

        modBus.addListener(this::commonSetup);
        SkyNetwork.init(modBus);
        // atlas: the native far field (server tile stream + far actors; the client renderer on clients)
        com.nirncraft.atlas.AtlasServer.init();
        com.nirncraft.atlas.server.AtlasChunkGenerator.init(modBus);
        com.nirncraft.platform.Sides.runOn(net.neoforged.api.distmarker.Dist.CLIENT,
                () -> () -> com.nirncraft.atlas.AtlasClient.init(modBus));
    }

    public static void config(ModConfig.Type type,net.neoforged.neoforge.common.ModConfigSpec spec) {
        container.registerConfig(type,spec);
    }

    public static void config(ModConfig.Type type,net.neoforged.neoforge.common.ModConfigSpec spec,String filename) {
        container.registerConfig(type,spec,filename);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("NirnCraft Core ready: the Dragonborn comes.");
    }
}

package com.skycraft.dungeons;

import com.skycraft.dungeons.entity.DwarvenCenturion;
import com.skycraft.dungeons.entity.DwarvenSphere;
import com.skycraft.dungeons.entity.DwarvenSpider;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Procedural dungeons and landmarks of Nirn (one {@code skycraft:dungeon} structure type, themed per structure JSON):
 * Skyrim's named sites at their places on the map ({@code skycraft:places}), the long ones in tiles
 * ({@code skycraft:sites}: the 7,000 Steps, Blackreach), and the random dungeons of the other provinces and the endless
 * lands, dressed in their region's culture ({@code world.Kit}). Also the Nordic puzzles (glyph pillars, claw doors and
 * the dragon claws, {@code puzzle}), dormant draugr, and the Dwemer automatons that guard the ruins.
 *
 * <p>Everything syncs through vanilla structure data and {@code SynchedEntityData}; the module has no packets.</p>
 */
public final class DungeonsModule {
    private DungeonsModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        DungeonsRegistry.init(modBus);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                DungeonsConfig.SPEC, "skycraft-dungeons.toml");
        modBus.addListener(DungeonsModule::onAttributes);
        modBus.addListener(DungeonsModule::onSpawnPlacements);
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        // no packets: structure pieces, entity data and boss bars use vanilla sync
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(DungeonsRegistry.DWARVEN_SPIDER.get(), DwarvenSpider.createAttributes().build());
        event.put(DungeonsRegistry.DWARVEN_SPHERE.get(), DwarvenSphere.createAttributes().build());
        event.put(DungeonsRegistry.DWARVEN_CENTURION.get(), DwarvenCenturion.createAttributes().build());
    }

    private static void onSpawnPlacements(SpawnPlacementRegisterEvent event) {
        SpawnPlacementRegisterEvent.Operation op = SpawnPlacementRegisterEvent.Operation.REPLACE;
        // automatons only spawn naturally inside Dwemer ruins (structure spawn overrides), in the dark
        event.register(DungeonsRegistry.DWARVEN_SPIDER.get(), SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Monster::checkMonsterSpawnRules, op);
        event.register(DungeonsRegistry.DWARVEN_SPHERE.get(), SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Monster::checkMonsterSpawnRules, op);
        event.register(DungeonsRegistry.DWARVEN_CENTURION.get(), SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> reason != net.minecraft.world.entity.MobSpawnType.NATURAL
                        && Monster.checkAnyLightMonsterSpawnRules(type, level, reason, pos, random), op);
    }
}

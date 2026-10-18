package com.skycraft.roads;

import com.skycraft.Skycraft;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Road network between settlements, signposts, and travelers/caravans/patrols walking the roads.
 *
 * <ul>
 *     <li>{@link RoadNetwork}: destination detection ({@code #skycraft:towns} and {@code #skycraft:road_destinations}
 *     structure starts in loaded chunks),
 *     background road planning, feeding loaded road chunks to the {@link Paver}.</li>
 *     <li>{@link RoadPlanner}: A* on the generator's noise heights (never loads chunks).</li>
 *     <li>{@link RoadPaint}: the road surface, bridges, culverts, fords, stairs and tunnels, painted from the baked road
 *     plan while chunks generate (docs/WORLDGEN-PIPELINE.md P3); {@link PlanRoute} takes the network's polylines from
 *     the same plan.</li>
 *     <li>{@link Paver}: the legacy runtime paver (old saves, {@code roads.legacyPaver}); in plan-first worlds only
 *     lantern posts, signposts, milestones, shrines and dressing.</li>
 *     <li>{@link Travelers}: caravans, travelers, guard patrols and bandit ambushes on roads near players.</li>
 *     <li>{@link RoadsPackets}: compass markers for nearby undiscovered settlements (source {@code "roads"}).</li>
 *     <li>{@link RoadsCommands}: {@code /skycraft-roads}.</li>
 * </ul>
 * Forge-bus handlers are registered through {@code @Mod.EventBusSubscriber}.
 */
public final class RoadsModule {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    /** {@code skycraft:road_paint}: added to every overworld biome at the last decoration step by a biome modifier. */
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> ROAD_PAINT = FEATURES.register("road_paint",
            () -> new RoadPaint(NoneFeatureConfiguration.CODEC));

    private RoadsModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RoadsConfig.SPEC, "skycraft-roads.toml");
        FEATURES.register(modBus);
        RoadLights.init(modBus);
        RoadMask.install();
        Carriages.register();
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        RoadsPackets.register();
    }
}

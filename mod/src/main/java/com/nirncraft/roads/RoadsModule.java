package com.nirncraft.roads;

import com.nirncraft.NirnCraft;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import com.nirncraft.worldgen.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;

/**
 * Road network between settlements, signposts, and travelers/caravans/patrols walking the roads.
 *
 * <ul>
 *     <li>{@link RoadNetwork}: destination detection ({@code #nirncraft:towns} and {@code #nirncraft:road_destinations}
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
 *     <li>{@link RoadsCommands}: {@code /nirncraft-roads}.</li>
 * </ul>
 * Forge-bus handlers are registered through {@code @net.neoforged.fml.common.EventBusSubscriber}.
 */
public final class RoadsModule {
    public static final DeferredRegister<MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(Registries.FEATURE_TYPE, NirnCraft.MODID);

    /** {@code nirncraft:road_paint}: added to every overworld biome at the last decoration step by a biome modifier. */
    public static final Supplier<MapCodec<? extends Feature>> ROAD_PAINT = FEATURES.register("road_paint",
            () -> new RoadPaint(NoneFeatureConfiguration.CODEC).codec());

    private RoadsModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, RoadsConfig.SPEC, "nirncraft-roads.toml");
        FEATURES.register(modBus);
        RoadLights.init(modBus);
        RoadMask.install();
        Carriages.register();
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        RoadsPackets.register();
    }
}

package com.skycraft.scatter;

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
 * Scatter: debris, leavings and natural formations that make sense for where they stand. Near settlements there is
 * human clutter (carts, crates, fences, firewood, graves, rubble); far out there are fewer human traces and more
 * natural features (boulder fields, fallen logs, dens, arches, crystals); roads, shores, ruins and dungeons each get
 * their own set. A zone model ({@link ZoneModel}) decides, a JSON rule table ({@link ScatterRules}) says what, and one
 * worldgen feature ({@link ScatterFeature}) places it. Rare hidden stashes and formations live in
 * {@link Discoveries}.
 */
public final class ScatterModule {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> SCATTER = FEATURES.register("scatter",
            () -> new ScatterFeature(NoneFeatureConfiguration.CODEC));

    private ScatterModule() {}

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ScatterConfig.SPEC, "skycraft-scatter.toml");
        FEATURES.register(modBus);
    }
}

package com.skycraft.scatter;

import com.skycraft.Skycraft;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import com.skycraft.worldgen.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;

/**
 * Scatter: debris, leavings and natural formations that make sense for where they stand. Near settlements there is
 * human clutter (carts, crates, fences, firewood, graves, rubble); far out there are fewer human traces and more
 * natural features (boulder fields, fallen logs, dens, arches, crystals); roads, shores, ruins and dungeons each get
 * their own set. A zone model ({@link ZoneModel}) decides, a JSON rule table ({@link ScatterRules}) says what, and one
 * worldgen feature ({@link ScatterFeature}) places it. Rare hidden stashes and formations live in
 * {@link Discoveries}.
 */
public final class ScatterModule {
    public static final DeferredRegister<MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(Registries.FEATURE_TYPE, Skycraft.MODID);
    public static final Supplier<MapCodec<? extends Feature>> SCATTER = FEATURES.register("scatter",
            () -> new ScatterFeature(NoneFeatureConfiguration.CODEC).codec());

    private ScatterModule() {}

    public static void init(IEventBus modBus) {
        com.skycraft.Skycraft.config(ModConfig.Type.LOCAL, ScatterConfig.SPEC, "skycraft-scatter.toml");
        FEATURES.register(modBus);
    }
}

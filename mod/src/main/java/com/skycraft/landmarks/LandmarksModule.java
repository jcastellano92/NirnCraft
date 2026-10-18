package com.skycraft.landmarks;

import com.skycraft.Skycraft;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import com.skycraft.worldgen.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;

/**
 * Natural landmarks and vignettes: tiered (S, M, L, XL), parametric formations and small human scenes with
 * generated names and stories, placed on their own grids away from settlements, roads and the minor sites. The data
 * is under {@code skycraft/landmarks/}; the debug command is {@code /skycraft-landmarks}. See docs/LANDMARKS.md.
 */
public final class LandmarksModule {
    public static final DeferredRegister<MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(Registries.FEATURE_TYPE, Skycraft.MODID);

    public static final Supplier<MapCodec<? extends Feature>> LANDMARK = FEATURES.register("landmark",
            () -> new LandmarkFeature(NoneFeatureConfiguration.CODEC).codec());

    private LandmarksModule() {}

    public static void init(IEventBus modBus) {
        FEATURES.register(modBus);
    }
}

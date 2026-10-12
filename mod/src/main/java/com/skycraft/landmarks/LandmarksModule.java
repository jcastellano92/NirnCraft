package com.skycraft.landmarks;

import com.skycraft.Skycraft;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Natural landmarks and vignettes: tiered (S, M, L, XL), parametric formations and small human scenes with
 * generated names and stories, placed on their own grids away from settlements, roads and the minor sites. The data
 * is under {@code skycraft/landmarks/}; the debug command is {@code /skycraft-landmarks}. See docs/LANDMARKS.md.
 */
public final class LandmarksModule {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> LANDMARK = FEATURES.register("landmark",
            () -> new LandmarkFeature(NoneFeatureConfiguration.CODEC));

    private LandmarksModule() {}

    public static void init(IEventBus modBus) {
        FEATURES.register(modBus);
    }
}

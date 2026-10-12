package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Nirn's vegetation: the procedural tree generator ({@link FloraTreeFeature}, {@link TreeSpecies}), ground cover in
 * noise-shaped drifts ({@link GroundCoverFeature}), varied rocks ({@link RockFeature}), and the placement that spaces
 * them naturally ({@link ScatterPlacement}, {@link DensityFilter}). The biomes use them through the features that
 * tools/worldgen/biomes.py generates; see docs/FLORA.md.
 */
public final class Flora {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);
    public static final DeferredRegister<PlacementModifierType<?>> PLACEMENTS =
            DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, Skycraft.MODID);

    public static final RegistryObject<Feature<FloraTreeFeature.Config>> TREE = FEATURES.register("flora_tree",
            () -> new FloraTreeFeature(FloraTreeFeature.Config.CODEC));
    public static final RegistryObject<Feature<GroundCoverFeature.Config>> GROUND_COVER = FEATURES.register("ground_cover",
            () -> new GroundCoverFeature(GroundCoverFeature.Config.CODEC));
    public static final RegistryObject<Feature<RockFeature.Config>> ROCK = FEATURES.register("flora_rock",
            () -> new RockFeature(RockFeature.Config.CODEC));

    public static final RegistryObject<PlacementModifierType<ScatterPlacement>> SCATTER =
            PLACEMENTS.register("flora_scatter", () -> type(ScatterPlacement.CODEC));
    public static final RegistryObject<PlacementModifierType<DensityFilter>> DENSITY =
            PLACEMENTS.register("flora_density", () -> type(DensityFilter.CODEC));

    public static final RegistryObject<PlacementModifierType<MaskFilter>> MASK =
            PLACEMENTS.register("placement_mask", () -> type(MaskFilter.CODEC));

    private Flora() {}

    private static <P extends net.minecraft.world.level.levelgen.placement.PlacementModifier> PlacementModifierType<P> type(Codec<P> codec) {
        return () -> codec;
    }

    public static void init(IEventBus modBus) {
        FEATURES.register(modBus);
        PLACEMENTS.register(modBus);
    }
}

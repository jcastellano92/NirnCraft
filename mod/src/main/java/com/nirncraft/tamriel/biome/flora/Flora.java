package com.nirncraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * Nirn's vegetation: the procedural tree generator ({@link FloraTreeFeature}, {@link TreeSpecies}), ground cover in
 * noise-shaped drifts ({@link GroundCoverFeature}), varied rocks ({@link RockFeature}), and the placement that spaces
 * them naturally ({@link ScatterPlacement}, {@link DensityFilter}). The biomes use them through the features that
 * tools/worldgen/biomes.py generates; see docs/FLORA.md.
 */
public final class Flora {
    public static final DeferredRegister<MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(Registries.FEATURE_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends PlacementModifier>> PLACEMENTS =
            DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, NirnCraft.MODID);

    public static final Supplier<MapCodec<? extends Feature>> TREE = FEATURES.register("flora_tree",
            () -> new FloraTreeFeature(FloraTreeFeature.Config.CODEC).codec());
    public static final Supplier<MapCodec<? extends Feature>> GROUND_COVER = FEATURES.register("ground_cover",
            () -> new GroundCoverFeature(GroundCoverFeature.Config.CODEC).codec());
    public static final Supplier<MapCodec<? extends Feature>> ROCK = FEATURES.register("flora_rock",
            () -> new RockFeature(RockFeature.Config.CODEC).codec());

    public static final Supplier<MapCodec<? extends PlacementModifier>> SCATTER =
            PLACEMENTS.register("flora_scatter", () -> ScatterPlacement.CODEC);
    public static final Supplier<MapCodec<? extends PlacementModifier>> DENSITY =
            PLACEMENTS.register("flora_density", () -> DensityFilter.CODEC);

    public static final Supplier<MapCodec<? extends PlacementModifier>> MASK =
            PLACEMENTS.register("placement_mask", () -> MaskFilter.CODEC);

    private Flora() {}


    public static void init(IEventBus modBus) {
        FEATURES.register(modBus);
        PLACEMENTS.register(modBus);
    }
}

package com.skycraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import com.skycraft.realms.worldgen.decor.RealmDecor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** The realms' worldgen types: terrain density function, biome source, structure, piece, placement, decor feature. */
public final class RealmWorldgen {
    public static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, Skycraft.MODID);
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, Skycraft.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, Skycraft.MODID);
    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENTS = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, Skycraft.MODID);

    public static final RegistryObject<Codec<? extends DensityFunction>> REALM_TERRAIN =
            DENSITY_TYPES.register("realm_terrain", RealmHeight.CODEC::codec);
    public static final RegistryObject<Codec<? extends BiomeSource>> REALM_BIOMES =
            BIOME_SOURCES.register("realm", () -> RealmBiomeSource.CODEC);
    public static final RegistryObject<StructureType<RealmStructure>> REALM_STRUCTURE =
            STRUCTURE_TYPES.register("realm_site", () -> () -> RealmStructure.CODEC);
    public static final RegistryObject<StructurePieceType> REALM_PIECE =
            PIECE_TYPES.register("realm_piece", () -> (StructurePieceType) RealmPiece::new);
    public static final RegistryObject<StructurePlacementType<RealmGridPlacement>> GRID_PLACEMENT =
            PLACEMENTS.register("realm_grid", () -> () -> RealmGridPlacement.CODEC);
    public static final RegistryObject<Feature<RealmDecor.Config>> DECOR = FEATURES.register("realm_decor", RealmDecor::new);

    private RealmWorldgen() {}

    public static void init(IEventBus modBus) {
        DENSITY_TYPES.register(modBus);
        BIOME_SOURCES.register(modBus);
        STRUCTURE_TYPES.register(modBus);
        PIECE_TYPES.register(modBus);
        PLACEMENTS.register(modBus);
        FEATURES.register(modBus);
    }
}

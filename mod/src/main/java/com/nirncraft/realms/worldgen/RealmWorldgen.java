package com.nirncraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.nirncraft.NirnCraft;
import com.nirncraft.realms.worldgen.decor.RealmDecor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** The realms' worldgen types: terrain density function, biome source, structure, piece, placement, decor feature. */
public final class RealmWorldgen {
    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, NirnCraft.MODID);
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends StructurePlacement>> PLACEMENTS = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(Registries.FEATURE_TYPE, NirnCraft.MODID);

    public static final Supplier<MapCodec<? extends DensityFunction>> REALM_TERRAIN =
            DENSITY_TYPES.register("realm_terrain", () -> RealmHeight.CODEC);
    public static final Supplier<MapCodec<? extends BiomeSource>> REALM_BIOMES =
            BIOME_SOURCES.register("realm", () -> RealmBiomeSource.CODEC);
    public static final Supplier<StructureType<RealmStructure>> REALM_STRUCTURE =
            STRUCTURE_TYPES.register("realm_site", () -> () -> RealmStructure.CODEC);
    public static final Supplier<StructurePieceType> REALM_PIECE =
            PIECE_TYPES.register("realm_piece", () -> (StructurePieceType) RealmPiece::new);
    public static final Supplier<MapCodec<? extends StructurePlacement>> GRID_PLACEMENT =
            PLACEMENTS.register("realm_grid", () -> RealmGridPlacement.CODEC);
    public static final Supplier<MapCodec<? extends Feature>> DECOR = FEATURES.register("realm_decor", () -> new RealmDecor().codec());

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

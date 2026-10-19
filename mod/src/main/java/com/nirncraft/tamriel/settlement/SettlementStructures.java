package com.nirncraft.tamriel.settlement;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/**
 * Registry objects of NirnCraft settlements: the {@code nirncraft:settlement} structure type, its piece type and the
 * {@code nirncraft:settlement_districts} placement (extra starts for cities too big for one structure start).
 */
public final class SettlementStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends StructurePlacement>> PLACEMENTS = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, NirnCraft.MODID);

    public static final Supplier<StructureType<SettlementStructure>> SETTLEMENT =
            STRUCTURE_TYPES.register("settlement", () -> () -> SettlementStructure.CODEC);
    public static final Supplier<StructurePieceType> PIECE =
            PIECE_TYPES.register("settlement_piece", () -> (StructurePieceType) SettlementPiece::new);
    public static final Supplier<MapCodec<? extends StructurePlacement>> DISTRICTS =
            PLACEMENTS.register("settlement_districts", () -> DistrictPlacement.CODEC);

    private SettlementStructures() {}

    public static void init(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
        PIECE_TYPES.register(modBus);
        PLACEMENTS.register(modBus);
        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.init(modBus);
    }
}

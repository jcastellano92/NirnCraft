package com.skycraft.tamriel.settlement;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry objects of Skycraft settlements: the {@code skycraft:settlement} structure type, its piece type and the
 * {@code skycraft:settlement_districts} placement (extra starts for cities too big for one structure start).
 */
public final class SettlementStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, Skycraft.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, Skycraft.MODID);
    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENTS = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, Skycraft.MODID);

    public static final RegistryObject<StructureType<SettlementStructure>> SETTLEMENT =
            STRUCTURE_TYPES.register("settlement", () -> () -> SettlementStructure.CODEC);
    public static final RegistryObject<StructurePieceType> PIECE =
            PIECE_TYPES.register("settlement_piece", () -> (StructurePieceType) SettlementPiece::new);
    public static final RegistryObject<StructurePlacementType<DistrictPlacement>> DISTRICTS =
            PLACEMENTS.register("settlement_districts", () -> () -> DistrictPlacement.CODEC);

    private SettlementStructures() {}

    public static void init(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
        PIECE_TYPES.register(modBus);
        PLACEMENTS.register(modBus);
    }
}

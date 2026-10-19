package com.nirncraft.tamriel;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** Structures: the nirncraft:places placement, settlements and fixed landmarks (Phase 2 workstreams S1/S2). */
public final class TamrielStructures {
    public static final DeferredRegister<MapCodec<? extends StructurePlacement>> PLACEMENTS =
            DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, NirnCraft.MODID);

    public static final Supplier<MapCodec<? extends StructurePlacement>> PLACES_PLACEMENT =
            PLACEMENTS.register("places", () -> PlacesPlacement.CODEC);

    private TamrielStructures() {}

    public static void init(IEventBus modBus) {
        PLACEMENTS.register(modBus);
        com.nirncraft.tamriel.settlement.SettlementStructures.init(modBus);
    }
}

package com.skycraft.tamriel;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Structures: the skycraft:places placement, settlements and fixed landmarks (Phase 2 workstreams S1/S2). */
public final class TamrielStructures {
    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENTS =
            DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, Skycraft.MODID);

    public static final RegistryObject<StructurePlacementType<PlacesPlacement>> PLACES_PLACEMENT =
            PLACEMENTS.register("places", () -> () -> PlacesPlacement.CODEC);

    private TamrielStructures() {}

    public static void init(IEventBus modBus) {
        PLACEMENTS.register(modBus);
    }
}

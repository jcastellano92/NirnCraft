package com.skycraft.tamriel;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Biomes: the skycraft:tamriel biome source, biome blocks, trees and vegetation features (Phase 2 workstream B). */
public final class TamrielBiomes {
    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, Skycraft.MODID);

    public static final RegistryObject<Codec<? extends BiomeSource>> TAMRIEL_SOURCE =
            BIOME_SOURCES.register("tamriel", () -> TamrielBiomeSource.CODEC);

    private TamrielBiomes() {}

    public static void init(IEventBus modBus) {
        BIOME_SOURCES.register(modBus);
    }
}

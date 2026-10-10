package com.skycraft.tamriel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * The {@code skycraft:tamriel} biome source: picks each biome from the region at that position
 * ({@link TamrielMap}) and the region's palette.
 *
 * <p>CONTRACT STUB: the Phase 2 biomes workstream replaces the body (palettes, every Tamriel biome). The type id
 * {@code skycraft:tamriel} and the JSON shape {@code {"type": "skycraft:tamriel"}} are fixed.</p>
 */
public class TamrielBiomeSource extends BiomeSource {
    public static final Codec<TamrielBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryOps.retrieveGetter(Registries.BIOME)
    ).apply(instance, instance.stable(TamrielBiomeSource::new)));

    private final Holder<Biome> land;
    private final Holder<Biome> snow;
    private final Holder<Biome> ocean;

    public TamrielBiomeSource(HolderGetter<Biome> biomes) {
        this.land = biomes.getOrThrow(Biomes.PLAINS);
        this.snow = biomes.getOrThrow(Biomes.SNOWY_SLOPES);
        this.ocean = biomes.getOrThrow(Biomes.OCEAN);
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(land, snow, ocean);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        float h = TamrielMap.elevation(qx << 2, qz << 2);
        if (h < TamrielMap.SEA_LEVEL - 2) return ocean;
        return h > 200 ? snow : land;
    }
}

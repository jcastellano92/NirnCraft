package com.nirncraft.tamriel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;


import java.util.List;
import java.util.Optional;

/**
 * {@code nirncraft:places}: a structure starts in exactly the chunks that hold the anchor of a matching named place
 * ({@link Places#inChunk}). A structure set lists the place {@code types} it builds and, optionally, the
 * {@code variants} it accepts (empty = any variant). The structure's {@code findGenerationPoint} looks the place up
 * again with {@link #placeFor}.
 *
 * <pre>{@code "placement": {"type": "nirncraft:places", "salt": 1, "types": ["capital", "city"], "variants": []}}</pre>
 */
public class PlacesPlacement implements StructurePlacement {
    public static final MapCodec<PlacesPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("salt", 0).forGetter(PlacesPlacement::salt),
            Codec.STRING.listOf().fieldOf("types").forGetter(p -> p.types),
            Codec.STRING.listOf().optionalFieldOf("variants", List.of()).forGetter(p -> p.variants)
    ).apply(instance, PlacesPlacement::new));

    private final int salt;
    public int salt() { return salt; }
    private final List<String> types;
    private final List<String> variants;

    public PlacesPlacement(int salt, List<String> types, List<String> variants) {

        this.salt = salt;
        this.types = List.copyOf(types);
        this.variants = List.copyOf(variants);
    }

    @Override
    public boolean isStructureChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        return placeFor(new ChunkPos(chunkX, chunkZ)).isPresent();
    }

    /** The place this placement builds in {@code chunk}, if any. */
    public Optional<Place> placeFor(ChunkPos chunk) {
        for (Place p : Places.inChunk(chunk)) {
            if (matches(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    public boolean matches(Place p) {
        return types.contains(p.type().id()) && (variants.isEmpty() || variants.contains(p.variant()));
    }

    @Override
    public MapCodec<? extends StructurePlacement> codec() { return CODEC; }
}

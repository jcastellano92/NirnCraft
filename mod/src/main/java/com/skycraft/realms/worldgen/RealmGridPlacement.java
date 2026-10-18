package com.skycraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;


import java.util.List;
import java.util.Optional;

/**
 * {@code skycraft:realm_grid}: a structure starts at fixed chunks, independent of the seed, so other code can know
 * where it is ({@link com.skycraft.realms.RealmLayout}): every {@code spacing}-th chunk on both axes (shifted by
 * {@code offset}), and/or an explicit {@code fixed} list of chunk positions.
 *
 * <pre>{@code "placement": {"type": "skycraft:realm_grid", "salt": 1, "spacing": 40, "offset": [0, 0], "fixed": [[0, -18]]}}</pre>
 */
public class RealmGridPlacement implements StructurePlacement {
    public static final MapCodec<RealmGridPlacement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("salt", 0).forGetter(p -> p.salt),
            Codec.INT.optionalFieldOf("spacing", 0).forGetter(p -> p.spacing),
            Codec.INT.listOf().optionalFieldOf("offset", List.of(0, 0)).forGetter(p -> List.of(p.offsetX, p.offsetZ)),
            Codec.INT.listOf().listOf().optionalFieldOf("fixed", List.of()).forGetter(p -> p.fixed)
    ).apply(i, RealmGridPlacement::new));

    private final int salt;
    private final int spacing;
    private final int offsetX;
    private final int offsetZ;
    private final List<List<Integer>> fixed;
    private final long[] fixedKeys;

    public RealmGridPlacement(int salt, int spacing, List<Integer> offset, List<List<Integer>> fixed) {

        this.salt = salt;
        this.spacing = spacing;
        this.offsetX = offset.isEmpty() ? 0 : offset.get(0);
        this.offsetZ = offset.size() < 2 ? 0 : offset.get(1);
        this.fixed = List.copyOf(fixed);
        this.fixedKeys = new long[fixed.size()];
        for (int k = 0; k < fixed.size(); k++) {
            List<Integer> f = fixed.get(k);
            fixedKeys[k] = key(f.get(0), f.get(1));
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Override
    public boolean isStructureChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        if (spacing > 0 && Math.floorMod(chunkX - offsetX, spacing) == 0 && Math.floorMod(chunkZ - offsetZ, spacing) == 0) {
            return true;
        }
        long k = key(chunkX, chunkZ);
        for (long f : fixedKeys) if (f == k) return true;
        return false;
    }

    @Override
    public MapCodec<? extends StructurePlacement> codec() { return CODEC; }
}

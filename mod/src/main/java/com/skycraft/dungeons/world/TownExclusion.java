package com.skycraft.dungeons.world;

import com.skycraft.world.LocationTags;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Keeps dungeons away from towns. Structure-set exclusion zones can only name one set (and the village set is
 * replaced by village mods), so instead {@link DungeonStructure#findGenerationPoint} asks here whether any structure
 * set containing a {@code #skycraft:towns} structure places a start within a few chunks, using only the placement math
 * and the biome source (safe at {@code STRUCTURE_STARTS}: no chunk access, no other structure lookups).
 */
final class TownExclusion {
    private record Cache(RegistryAccess access, List<Holder<StructureSet>> sets) {
    }

    private static volatile Cache cache;

    private TownExclusion() {}

    /** Structure sets with at least one town in them, computed once per registry (server). */
    private static List<Holder<StructureSet>> townSets(RegistryAccess access) {
        Cache c = cache;
        if (c != null && c.access() == access) return c.sets();
        List<Holder<StructureSet>> sets = new ArrayList<>();
        access.registryOrThrow(Registries.STRUCTURE_SET).holders().forEach(set -> {
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                if (e.structure().is(LocationTags.TOWNS)) {
                    sets.add(set);
                    break;
                }
            }
        });
        List<Holder<StructureSet>> frozen = List.copyOf(sets);
        cache = new Cache(access, frozen);
        return frozen;
    }

    /** True if a town may start within {@code range} chunks of this generation context's chunk. */
    static boolean nearTown(Structure.GenerationContext ctx, int range) {
        List<Holder<StructureSet>> sets = townSets(ctx.registryAccess());
        if (sets.isEmpty()) return false;
        // a bare state: random-spread placements only need the seed from it
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(ctx.randomState(), ctx.seed(),
                ctx.biomeSource(), Stream.empty());
        ChunkPos cp = ctx.chunkPos();
        int y = QuartPos.fromBlock(ctx.chunkGenerator().getSeaLevel());
        for (Holder<StructureSet> set : sets) {
            StructurePlacement placement = set.value().placement();
            for (int x = cp.x - range; x <= cp.x + range; x++) {
                for (int z = cp.z - range; z <= cp.z + range; z++) {
                    if (!placement.isStructureChunk(state, x, z)) continue;
                    // the start chunk is chosen; would a town (not some other member of the set) like its biome?
                    Holder<Biome> biome = ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock((x << 4) + 8), y,
                            QuartPos.fromBlock((z << 4) + 8), ctx.randomState().sampler());
                    for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                        if (e.structure().is(LocationTags.TOWNS) && e.structure().value().biomes().contains(biome)) return true;
                    }
                }
            }
        }
        return false;
    }
}

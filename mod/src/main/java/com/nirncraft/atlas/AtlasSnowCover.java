package com.nirncraft.atlas;

import com.nirncraft.tamriel.biome.flora.DensityFilter;
import com.nirncraft.tamriel.biome.flora.GroundCoverFeature;
import com.nirncraft.worldgen.ConfiguredFeature;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where snow lies on a cold far column. freeze_top_layer only snows where the block above the ground is air, and the
 * ground cover (grass, ferns, flowers, berries) was placed before it: in the near world a cold meadow is grass with
 * snow in the gaps, not a white sheet. The far field has no plants, so it estimates them: each biome's ground_cover
 * features give the expected plants per block at (x, z) (count x cluster x the flora_density field there), the share
 * of ground they cover is 1 - e^-plants, and a hash per few-block patch picks whether it is a gap (snow) or plants
 * (their grass). Same share, same drifts and bare patches as the near world, column by column statistically.
 */
final class AtlasSnowCover {
    /** One ground_cover feature: plants per chunk before the density field, and the field (null: everywhere). */
    record Cover(double perChunk, DensityFilter density) {}

    private static final Cover[] NONE = new Cover[0];

    private final long seed;
    private final Map<Holder<Biome>, Cover[]> byBiome = new ConcurrentHashMap<>();

    AtlasSnowCover(long seed) {
        this.seed = seed;
    }

    /** The chance snow lies on the ground of a cold column at (x, z) of this biome: the share plants leave bare. */
    double bare(Holder<Biome> biome, int x, int z) {
        Cover[] covers = byBiome.computeIfAbsent(biome, AtlasSnowCover::covers);
        if (covers.length == 0) return 1.0;
        double plants = 0;
        for (Cover c : covers) plants += c.perChunk / 256.0 * (c.density == null ? 1.0 : c.density.keep(seed, x, z));
        return Math.exp(-plants);
    }

    /**
     * Whether this column is one of the bare ones. Plants grow in tufts (a cluster within a couple of blocks), so the
     * gaps between them, where the snow lies, come in patches too: the roll is per ~3-block cell (cells jittered per
     * column so their edges are ragged, not a grid), each cell bare with the bare share. Stable per column.
     */
    boolean snows(double bare, int x, int z) {
        if (bare >= 1.0) return true;
        long j = hash(x, z, 0x51ED27L);
        int cx = Math.floorDiv(x + (int) (j & 1) + (int) ((j >>> 8) & 1), 3);
        int cz = Math.floorDiv(z + (int) ((j >>> 16) & 1) + (int) ((j >>> 24) & 1), 3);
        return (hash(cx, cz, 0x5A0EL) >>> 11) * 0x1.0p-53 < bare;
    }

    private long hash(int x, int z, long salt) {
        long h = seed ^ salt ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }

    /** A biome's ground cover as bare() sums it (the GPU's copy: AtlasGenMap.far). */
    static Cover[] covers(Holder<Biome> biome) {
        List<Cover> out = new ArrayList<>();
        for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features())
            for (Holder<PlacedFeature> pf : step) {
                GroundCoverFeature.Config cfg = ConfiguredFeature.configOf(pf.value().feature().value(), GroundCoverFeature.class);
                if (cfg == null || cfg.nearWater()) continue;  // shore plants: a thin fringe, not the meadow
                double n = (cfg.cluster().minInclusive() + cfg.cluster().maxInclusive()) * 0.5;
                DensityFilter density = null;
                for (PlacementModifier m : pf.value().placement()) {
                    if (m instanceof CountPlacement c) n *= (c.count().minInclusive() + c.count().maxInclusive()) * 0.5;
                    else if (m instanceof RarityFilter r) n /= Math.max(1, r.chance());
                    else if (m instanceof DensityFilter d) density = d;
                }
                out.add(new Cover(n, density));
            }
        return out.isEmpty() ? NONE : out.toArray(Cover[]::new);
    }
}

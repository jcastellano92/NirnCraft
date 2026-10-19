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
 * of ground they cover is 1 - e^-plants, and a hash of the column picks whether this one is a gap (snow) or a plant
 * (its grass). Same share, same drifts and bare patches as the near world, column by column statistically.
 */
final class AtlasSnowCover {
    /** One ground_cover feature: plants per chunk before the density field, and the field (null: everywhere). */
    private record Cover(double perChunk, DensityFilter density) {}

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

    /** Whether this column is one of the bare ones (a stable hash of the column against the bare share). */
    boolean snows(double bare, int x, int z) {
        if (bare >= 1.0) return true;
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53 < bare;
    }

    private static Cover[] covers(Holder<Biome> biome) {
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

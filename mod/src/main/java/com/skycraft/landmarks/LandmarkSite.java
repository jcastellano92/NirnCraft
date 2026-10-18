package com.skycraft.landmarks;

import com.skycraft.explore.MinorSites;
import com.skycraft.explore.SiteKind;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * One natural landmark or vignette.
 *
 * @param seed   per-site seed: size, material, weathering, props and the story all derive from it
 * @param family the base family of the spot (tundra, desert...)
 * @param name   generated (or pinned) name; empty for unnamed S and M vignettes
 * @param pinned id of the pinned XL entry, or null for grid sites
 */
public record LandmarkSite(Tier tier, int cellX, int cellZ, int x, int z, Kind kind, long seed, int facing,
                           String regionId, String province, String family, String name, @Nullable String pinned) {

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    /** Stable key for "found" sets and resident spawns. */
    public long key() {
        return H.mix(ChunkPos.pack(cellX, cellZ) ^ ((tier.ordinal() + 1) * 0x9E3779B97F4A7C15L));
    }

    public Direction direction() {
        return Direction.from2DDataValue(facing);
    }

    public long distSqr(int bx, int bz) {
        long dx = bx - x, dz = bz - z;
        return dx * dx + dz * dz;
    }

    /**
     * The explore module's builder helper needs a minor-site record: this carries our position, seed and facing.
     * The kind it names is never used for placement, only for the builder's "may build over water" rule.
     */
    public MinorSites.Site asMinorSite() {
        return new MinorSites.Site(cellX, cellZ, x, z, SiteKind.ABANDONED_CAMP, seed, facing, null, regionId, province);
    }
}

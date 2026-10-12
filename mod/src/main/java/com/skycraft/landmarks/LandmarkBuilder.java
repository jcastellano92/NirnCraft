package com.skycraft.landmarks;

import com.skycraft.Skycraft;
import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.landmarks.build.Builders;
import com.skycraft.landmarks.build.Stage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

/**
 * Builds one landmark site into a world-generation level: the checks that need real terrain (surface structures
 * overhead, water in the footprint, cliffs), the kind's builder, a named marker at the foot, an optional secret
 * cache, and the buried marker that tells the server the site stands.
 */
public final class LandmarkBuilder {
    private LandmarkBuilder() {}

    /**
     * @param force skip the terrain checks (showcases and tests build wherever they are told to)
     * @param family when non-null, overrides the map's base family (showcases show a kind in every biome)
     */
    public static boolean build(WorldGenLevel level, LandmarkSite site, boolean force, @Nullable String family) {
        Kind kind = site.kind();
        ChunkPos chunk = new ChunkPos(site.chunkX(), site.chunkZ());
        if (!force && site.pinned() == null && surfaceStructureHere(level, chunk, kind.tier() == Tier.XL ? 1 : 1)) return false;
        LandCtx ctx = LandCtx.of(site.x(), site.z());
        if (family != null) ctx.base = family;
        RandomSource random = RandomSource.create(site.seed());
        String arch = ctx.archetype;
        SiteBuilder b = new SiteBuilder(level, random, site.asMinorSite(), Palette.of(site.province(), arch));
        int spread = b.settle(Math.min(kind.radius(), 12));
        if (!force) {
            if (spread > kind.maxSpread()) return false;
            boolean coastal = kind.needs().contains("coast");
            if (!coastal && b.footprintWet(kind.radius())) return false;
        }
        Stage stage = new Stage(level, b, site, ctx);
        boolean built;
        try {
            built = Builders.build(stage);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft landmarks: failed to build {} at {} {}", kind.id(), site.x(), site.z(), e);
            return false;
        }
        if (!built) return false;
        try {
            if (!site.name().isEmpty() && kind.sign()) nameMarker(stage, site);
            if (kind.secret() > 0 && H.unit(site.seed(), 90) < kind.secret()) secret(stage);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft landmarks: extras failed for {}", kind.id(), e);
        }
        // the buried marker (a skull two blocks under the centre ground) tells the server the site stands
        int my = b.ground(0, 0) - 2;
        BlockPos at = b.at(0, my - b.y, 0);
        if (b.writable(at)) level.setBlock(at, Blocks.SKELETON_SKULL.defaultBlockState().setValue(SkullBlock.ROTATION, 0), 2);
        return true;
    }

    /** A signpost at the foot of a landmark with its name. */
    private static void nameMarker(Stage s, LandmarkSite site) {
        int r = Math.min(s.rad - 2, 11);
        double a = (site.facing() * Math.PI / 2) + Math.PI;
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = a + attempt * 0.7;
            int x = (int) Math.round(Math.cos(ang) * r), z = (int) Math.round(Math.sin(ang) * r);
            if (!s.dry(x, z)) continue;
            int y = s.top(x, z);
            if (!s.free(x, y, z)) continue;
            s.set(x, y - 1, z, Blocks.COARSE_DIRT);
            s.standingSign(x, y, z, 8, Lore.wrap(site.name()));
            s.set(x + 1, y, z, s.wood.fence());
            return;
        }
    }

    /** A chest buried under a cairn near the centre, in about one L or XL site in six. */
    private static void secret(Stage s) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double ang = H.unit(s.seed, 91 + attempt) * Math.PI * 2;
            int d = 4 + attempt % 5;
            int x = (int) Math.round(Math.cos(ang) * d), z = (int) Math.round(Math.sin(ang) * d);
            if (!s.within(x, z) || !s.dry(x, z)) continue;
            int y = s.top(x, z);
            if (!s.free(x, y, z)) continue;
            s.set(x, y - 3, z, Blocks.CHEST);
            s.chest(x, y - 3, z, Direction.NORTH, "explore/cache");
            s.set(x, y - 2, z, s.rubble(x, y, z));
            s.set(x, y - 1, z, s.rubble(x, y, z));
            s.set(x, y, z, s.rubble(x, y, z));
            s.set(x, y + 1, z, s.rubble(x, y, z));
            return;
        }
    }

    /**
     * True when a surface structure (a town, camp, fort or ruin above ground) has a piece over any chunk of the
     * 3x3 around the site. Only structure references of those chunks are read, which are complete by decoration.
     */
    static boolean surfaceStructureHere(WorldGenLevel level, ChunkPos centre, int reach) {
        Registry<Structure> registry = null;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                ChunkAccess access = level.getChunk(centre.x + dx, centre.z + dz);
                var refs = access.getAllReferences();
                if (refs.isEmpty()) continue;
                if (registry == null) registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                for (var e : refs.entrySet()) {
                    if (e.getValue().isEmpty()) continue;
                    Structure s = e.getKey();
                    if (s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) return true;
                    Holder<Structure> h = registry.wrapAsHolder(s);
                    if (h.is(com.skycraft.world.LocationTags.TOWNS)) return true;
                }
            }
        }
        return false;
    }
}

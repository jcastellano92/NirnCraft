package com.nirncraft.realms.worldgen.site;

import com.nirncraft.dungeons.world.Painter;
import com.nirncraft.realms.worldgen.RealmPiece;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every realm site by id ({@code deadlands/sigil_keep}, {@code sovngarde/hall_of_valor}, {@code nirn/oblivion_gate}...):
 * where it starts, its bounding box and its painter. Sites register themselves from their realm's class.
 */
public final class RealmSites {
    /** Where a site starts: origin block, a site parameter and a facing (2D data value). */
    public record Start(int x, int y, int z, int param, int facing) {}

    public interface Site {
        /** Only noise heights (ctx.chunkGenerator().getBaseHeight); never reads the world. */
        Optional<Start> locate(Structure.GenerationContext ctx);

        BoundingBox box(Start s);

        void paint(Painter p, RealmPiece piece);
    }

    private static final Map<String, Site> SITES = new HashMap<>();

    static {
        DeadlandsSites.register();
        NirnSites.register();
        SovngardeSites.register();
        IslesSites.register();
        ApocryphaSites.register();
        SoulCairnSites.register();
        ColdharbourSites.register();
    }

    private RealmSites() {}

    static void add(String id, Site site) {
        if (SITES.put(id, site) != null) throw new IllegalStateException("Duplicate realm site " + id);
    }

    @Nullable
    public static Site get(String id) {
        return SITES.get(id);
    }

    public static Iterable<String> ids() {
        return SITES.keySet();
    }

    // ------------------------------------------------------------------ locate helpers

    /** Solid ground height (first free block above it) at a column, from the noise (fluids ignored). */
    public static int ground(Structure.GenerationContext ctx, int x, int z) {
        return ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
    }

    /** Surface height counting fluids (the top of a lava or water sea). */
    public static int surface(Structure.GenerationContext ctx, int x, int z) {
        return ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
    }

    /** True when (x, z) lies in the context's start chunk. */
    public static boolean inChunk(Structure.GenerationContext ctx, int x, int z) {
        ChunkPos c = ctx.chunkPos();
        return (x >> 4) == c.x() && (z >> 4) == c.z();
    }

    /** A box around an origin: {@code r} sideways, from {@code below} under to {@code above} over it. */
    public static BoundingBox around(Start s, int r, int below, int above) {
        return new BoundingBox(s.x() - r, s.y() - below, s.z() - r, s.x() + r, s.y() + above, s.z() + r);
    }

    public static BoundingBox around(Start s, int rx, int rz, int below, int above) {
        return new BoundingBox(s.x() - rx, s.y() - below, s.z() - rz, s.x() + rx, s.y() + above, s.z() + rz);
    }

    /** Stable per-site hash for a position (decor choices that must agree between chunks). */
    public static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }
}

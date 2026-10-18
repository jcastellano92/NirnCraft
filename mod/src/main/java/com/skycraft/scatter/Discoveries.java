package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.explore.MinorSites;
import com.skycraft.explore.SiteKind;
import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.scatter.ScatterRules.Kind;
import com.skycraft.scatter.Terrain.Ground;
import com.skycraft.scatter.Terrain.RockSet;
import com.skycraft.scatter.ZoneModel.Info;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/**
 * Rare finds in the wild, placed on two sparse grids and fully determined by the world seed and the map:
 * <ul>
 *     <li>hidden stashes (192-block cells): a chest under a boulder cluster, with explore loot;</li>
 *     <li>formations (640-block cells, so a handful per region): a great rock arch, a grove of spires, a glacial
 *     erratic, a bloom of crystals, the bones of a dragon, a hollow giant tree.</li>
 * </ul>
 * They keep to open country (the wild zones, ruin and dungeon surroundings), clear of every named place and minor
 * site. Coming within reach of one for the first time gives a little Athletics experience ({@link ScatterEvents}).
 */
public final class Discoveries {
    public static final int STASH_CELL = 192, FORMATION_CELL = 640;
    static final long SALT_STASH = 0x57A5400000C1L, SALT_FORM = 0xF0A3A71045L;

    public enum Type {
        HIDDEN_STASH("stash", "Hidden stash", 6),
        GREAT_ARCH("great_arch", "Great arch", 30),
        SPIRE_GROVE("spire_grove", "Spire grove", 28),
        ERRATIC("erratic", "Glacial erratic", 22),
        CRYSTAL_BLOOM("crystal_bloom", "Crystal bloom", 24),
        DRAGON_BONES("dragon_bones", "Dragon bones", 24),
        GIANT_HOLLOW("giant_hollow", "Hollow giant", 18);

        public final String kind, label;
        public final int radius;
        public static final Type[] VALUES = values();

        Type(String kind, String label, int radius) {
            this.kind = kind;
            this.label = label;
            this.radius = radius;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param key stable key of the cell grid and cell, for "found" sets */
    public record Find(Type type, int x, int z, long seed, String regionId, long key) {
        public int chunkX() {
            return x >> 4;
        }

        public int chunkZ() {
            return z >> 4;
        }

        public long distSqr(int bx, int bz) {
            long dx = bx - x, dz = bz - z;
            return dx * dx + dz * dz;
        }
    }

    private static final ConcurrentHashMap<Long, Optional<Find>> STASHES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Optional<Find>> FORMATIONS = new ConcurrentHashMap<>();
    private static volatile long cacheSeed;

    private Discoveries() {}

    private static void checkSeed(long seed) {
        if (cacheSeed != seed) {
            synchronized (Discoveries.class) {
                if (cacheSeed != seed) {
                    STASHES.clear();
                    FORMATIONS.clear();
                    cacheSeed = seed;
                }
            }
        }
    }

    private static double unit(long h, int k) {
        return (Scatter.mix(h + k * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    @Nullable
    public static Find stash(long seed, int cellX, int cellZ) {
        checkSeed(seed);
        long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        Optional<Find> hit = STASHES.get(key);
        if (hit == null) {
            hit = Optional.ofNullable(computeStash(seed, cellX, cellZ));
            if (STASHES.size() > 8192) STASHES.clear();
            STASHES.put(key, hit);
        }
        return hit.orElse(null);
    }

    @Nullable
    public static Find formation(long seed, int cellX, int cellZ) {
        checkSeed(seed);
        long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        Optional<Find> hit = FORMATIONS.get(key);
        if (hit == null) {
            hit = Optional.ofNullable(computeFormation(seed, cellX, cellZ));
            if (FORMATIONS.size() > 4096) FORMATIONS.clear();
            FORMATIONS.put(key, hit);
        }
        return hit.orElse(null);
    }

    /** The find whose centre lies in chunk (cx, cz), stash first. */
    @Nullable
    public static Find inChunk(long seed, int cx, int cz) {
        int x0 = cx * 16, z0 = cz * 16;
        Find s = stash(seed, Math.floorDiv(x0, STASH_CELL), Math.floorDiv(z0, STASH_CELL));
        if (s != null && s.chunkX() == cx && s.chunkZ() == cz) return s;
        Find f = formation(seed, Math.floorDiv(x0, FORMATION_CELL), Math.floorDiv(z0, FORMATION_CELL));
        if (f != null && f.chunkX() == cx && f.chunkZ() == cz) return f;
        return null;
    }

    /** Every find with its centre within {@code radius} of (x, z). */
    public static List<Find> within(long seed, int x, int z, int radius) {
        List<Find> out = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (int cx = Math.floorDiv(x - radius, STASH_CELL); cx <= Math.floorDiv(x + radius, STASH_CELL); cx++) {
            for (int cz = Math.floorDiv(z - radius, STASH_CELL); cz <= Math.floorDiv(z + radius, STASH_CELL); cz++) {
                Find f = stash(seed, cx, cz);
                if (f != null && f.distSqr(x, z) <= r2) out.add(f);
            }
        }
        for (int cx = Math.floorDiv(x - radius, FORMATION_CELL); cx <= Math.floorDiv(x + radius, FORMATION_CELL); cx++) {
            for (int cz = Math.floorDiv(z - radius, FORMATION_CELL); cz <= Math.floorDiv(z + radius, FORMATION_CELL); cz++) {
                Find f = formation(seed, cx, cz);
                if (f != null && f.distSqr(x, z) <= r2) out.add(f);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ where

    @Nullable
    private static Find computeStash(long seed, int cx, int cz) {
        long h = Scatter.mix(seed ^ Scatter.mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + SALT_STASH));
        int x = cx * STASH_CELL + 24 + (int) (unit(h, 1) * (STASH_CELL - 48));
        int z = cz * STASH_CELL + 24 + (int) (unit(h, 2) * (STASH_CELL - 48));
        Info info = ZoneModel.at(seed, x, z);
        double p = switch (info.zone()) {
            case DEEP_WILD -> 0.20;
            case WILD -> 0.13;
            case RUIN_ADJACENT -> 0.10;
            case DUNGEON_ADJACENT -> 0.07;
            case SHORE -> 0.06;
            default -> 0.0;
        };
        if (unit(h, 3) >= p) return null;
        if (!dry(seed, x, z, 10, Type.HIDDEN_STASH.radius)) return null;
        return new Find(Type.HIDDEN_STASH, x, z, Scatter.mix(h ^ 0x51A5), info.regionId(), ((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    @Nullable
    private static Find computeFormation(long seed, int cx, int cz) {
        long h = Scatter.mix(seed ^ Scatter.mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + SALT_FORM));
        if (unit(h, 3) >= 0.6) return null;
        // a few jittered tries so a lake or a town in the middle of the cell does not lose the formation
        for (int attempt = 0; attempt < 5; attempt++) {
            int x = cx * FORMATION_CELL + 64 + (int) (unit(h, 10 + attempt * 2) * (FORMATION_CELL - 128));
            int z = cz * FORMATION_CELL + 64 + (int) (unit(h, 11 + attempt * 2) * (FORMATION_CELL - 128));
            Info info = ZoneModel.at(seed, x, z);
            if (info.zone() != ScatterZone.WILD && info.zone() != ScatterZone.DEEP_WILD) continue;
            if (info.humanity() > 0.12f) continue;
            if (!dry(seed, x, z, 14, 32)) continue;
            Type t = chooseFormation(info, unit(h, 4));
            return new Find(t, x, z, Scatter.mix(h ^ t.ordinal()), info.regionId(), ((long) cx << 32) ^ (cz & 0xFFFFFFFFL) ^ 0x4000000000000000L);
        }
        return null;
    }

    private static Type chooseFormation(Info info, double u) {
        boolean rocky = info.mountains();
        double[] w = new double[Type.VALUES.length];
        w[Type.GREAT_ARCH.ordinal()] = rocky ? 4 : 1.5;
        w[Type.SPIRE_GROVE.ordinal()] = rocky ? 4 : 2;
        w[Type.ERRATIC.ordinal()] = 2.5;
        w[Type.CRYSTAL_BLOOM.ordinal()] = rocky ? 3 : 1;
        w[Type.DRAGON_BONES.ordinal()] = 2;
        w[Type.GIANT_HOLLOW.ordinal()] = rocky ? 0.5 : 3;
        double total = 0;
        for (double v : w) total += v;
        double roll = u * total;
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0) return Type.VALUES[i];
        }
        return Type.ERRATIC;
    }

    /** Dry land with room: not in or near water, clear of every named place and minor site footprint. */
    private static boolean dry(long seed, int x, int z, int waterClear, int radius) {
        MapSample s = new MapSample();
        int[][] probes = {{0, 0}, {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}};
        for (int[] o : probes) {
            TamrielMap.sample(x + o[0], z + o[1], s);
            if (s.inWater() || s.coastDistance < waterClear) return false;
            if (!Float.isNaN(s.waterLevel) && s.lakeDistance > -waterClear) return false;
        }
        int m = radius + 40;
        if (!Places.overlapping(x - m, z - m, x + m, z + m).isEmpty()) return false;
        for (MinorSites.Site site : MinorSites.within(seed, x, z, radius + 30)) {
            int need = site.kind().radius + radius + 6;
            if (site.distSqr(x, z) < (long) need * need) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ building

    /** Builds the find centred in chunk (cx, cz), if any. Returns true when something was built. */
    static boolean generate(WorldGenLevel level, ChunkPos cp) {
        if (!ScatterConfig.discoveries()) return false;
        long seed = level.getSeed();
        Find f = inChunk(seed, cp.x(), cp.z());
        if (f == null) return false;
        Kind k = ScatterRules.get().kinds.get(f.type().kind);
        if (k == null || k.builder == null) return false;
        Info info = ZoneModel.at(seed, f.x(), f.z());
        RandomSource rnd = RandomSource.create(f.seed());
        MinorSites.Site fake = new MinorSites.Site(f.x() >> 4, f.z() >> 4, f.x(), f.z(), SiteKind.ABANDONED_CAMP, f.seed(),
                rnd.nextInt(4), null, info.regionId(), info.province());
        SiteBuilder b = new SiteBuilder(level, rnd, fake, Palette.of(info.province(), info.archetype()));
        int g = b.groundAt(f.x(), f.z());
        BlockPos col = new BlockPos(f.x(), g, f.z());
        BlockState gs = level.getBlockState(col);
        if (!gs.getFluidState().isEmpty() || !level.getFluidState(col.above()).isEmpty()) return false;
        Ground ground = Terrain.ground(gs);
        b.y = g + 1;
        Brush br = new Brush(b, RockSet.of(ground, gs));
        try {
            boolean ok = Builders.build(k.builder, br, k);
            if (ok) ScatterStats.discovery(f.type());
            return ok;
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft scatter: failed to build {} at {} {}", f.type(), f.x(), f.z(), e);
            return false;
        }
    }
}

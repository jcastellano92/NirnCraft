package com.skycraft.explore;

import com.skycraft.dungeons.world.Cultures;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the minor sites are. The world is cut into {@value #CELL}-block cells; each cell holds at most one site,
 * at a hashed chunk well inside the cell (so neighbours stay at least four chunks apart). Whether a cell is occupied
 * and by what kind depends only on the world seed and the Tamriel map (region culture, coast, lakes and rivers,
 * height above the surroundings, cold), so it is cheap, thread-safe, and the same everywhere it is asked: the
 * worldgen feature, the density audit, treasure maps pointing at caches, and the "found a site" check.
 *
 * <p>The hash is a plain SplitMix64 so {@code tools/explore/density_audit.py} can reproduce the grid offline.</p>
 */
public final class MinorSites {
    public static final int CELL_CHUNKS = 10;
    public static final int CELL = CELL_CHUNKS * 16;
    /** Sites keep this far (blocks) beyond their footprint from any named place's radius. */
    public static final int PLACE_MARGIN = 40;
    static final long SALT = 0x51C37A0EEF1DL;

    /**
     * One minor site.
     *
     * @param facing  0..3 = south, west, north, east (towards the water for docks and wrecks)
     * @param unique  id of the unique item hidden here ({@link Uniques}), or null
     */
    public record Site(int cellX, int cellZ, int x, int z, SiteKind kind, long seed, int facing, @Nullable String unique,
                       String regionId, String province) {
        public int chunkX() {
            return x >> 4;
        }

        public int chunkZ() {
            return z >> 4;
        }

        /** Stable key of the cell, for "found" sets and spawned hermits. */
        public long key() {
            return ChunkPos.asLong(cellX, cellZ);
        }

        public Direction direction() {
            return Direction.from2DDataValue(facing);
        }

        public long distSqr(int bx, int bz) {
            long dx = bx - x, dz = bz - z;
            return dx * dx + dz * dz;
        }
    }

    private MinorSites() {}

    // ------------------------------------------------------------------ cache

    private static final class Cache {
        final long seed;
        final ConcurrentHashMap<Long, Optional<Site>> sites = new ConcurrentHashMap<>();

        Cache(long seed) {
            this.seed = seed;
        }
    }

    private static volatile Cache cache = new Cache(0);

    // ------------------------------------------------------------------ queries

    /** The site of cell (cellX, cellZ), or null if the cell is empty. */
    @Nullable
    public static Site at(long worldSeed, int cellX, int cellZ) {
        Cache c = cache;
        if (c.seed != worldSeed) {
            c = new Cache(worldSeed);
            cache = c;
        }
        long key = ChunkPos.asLong(cellX, cellZ);
        Optional<Site> hit = c.sites.get(key);
        if (hit != null) return hit.orElse(null);
        Site site = compute(worldSeed, cellX, cellZ);
        if (c.sites.size() > 8192) c.sites.clear();
        c.sites.put(key, Optional.ofNullable(site));
        return site;
    }

    /** The site whose centre lies in chunk (chunkX, chunkZ), if any. */
    @Nullable
    public static Site inChunk(long worldSeed, int chunkX, int chunkZ) {
        // one lookup per cell (cached): sites by the water may have moved off their hashed chunk towards the shore
        int cx = Math.floorDiv(chunkX, CELL_CHUNKS), cz = Math.floorDiv(chunkZ, CELL_CHUNKS);
        Site s = at(worldSeed, cx, cz);
        return s != null && s.chunkX() == chunkX && s.chunkZ() == chunkZ ? s : null;
    }

    /** Every site with its centre within {@code radius} blocks of (x, z), nearest first. */
    public static List<Site> within(long worldSeed, int x, int z, int radius) {
        List<Site> out = new ArrayList<>();
        int c0x = Math.floorDiv(x - radius, CELL), c1x = Math.floorDiv(x + radius, CELL);
        int c0z = Math.floorDiv(z - radius, CELL), c1z = Math.floorDiv(z + radius, CELL);
        long r2 = (long) radius * radius;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                Site s = at(worldSeed, cx, cz);
                if (s != null && s.distSqr(x, z) <= r2) out.add(s);
            }
        }
        out.sort((a, b) -> Long.compare(a.distSqr(x, z), b.distSqr(x, z)));
        return out;
    }

    /** The nearest site of {@code kind} within {@code radius} of (x, z) other than the one in cell {@code skipKey}. */
    @Nullable
    public static Site nearest(long worldSeed, SiteKind kind, int x, int z, int radius, long skipKey) {
        for (Site s : within(worldSeed, x, z, radius)) {
            if (s.kind() == kind && s.key() != skipKey) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ the grid

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long cellHash(long seed, int cx, int cz) {
        return mix(seed ^ mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + SALT));
    }

    /** Uniform [0, 1) number k of a hash. */
    static double unit(long h, int k) {
        return (mix(h + k * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    /** Chunk offset 2..7 inside the cell. */
    static int chunkOffset(long h, int k) {
        return 2 + (int) (unit(h, k) * 6);
    }

    private static Site compute(long seed, int cx, int cz) {
        long h = cellHash(seed, cx, cz);
        Uniques.UniqueSite forced = Uniques.forCell(cx, cz);
        int x, z;
        Ctx ctx;
        if (forced != null) {
            int[] dry = spotInCell(forced.x(), forced.z(), cx, cz, forced.kind().radius + 16);
            if (dry == null) return null;
            x = dry[0];
            z = dry[1];
            ctx = Ctx.of(x, z);
            return new Site(cx, cz, x, z, forced.kind(), mix(h ^ forced.id().hashCode()), waterFacing(x, z, ctx), forced.id(),
                    ctx.region.id(), ctx.province);
        }
        x = (cx * CELL_CHUNKS + chunkOffset(h, 1)) * 16 + 4 + (int) (unit(h, 3) * 8);
        z = (cz * CELL_CHUNKS + chunkOffset(h, 2)) * 16 + 4 + (int) (unit(h, 4) * 8);
        ctx = Ctx.of(x, z);
        if (ctx.province.equals("sea") || ctx.s.coastDistance < -96) {
            int[] isle = com.skycraft.tamriel.terrain.OceanIsles.siteInMinorCell(cx, cz, CELL);
            if (isle == null || unit(h, 5) >= 0.75 * ExploreConfig.densityScale()) return null;
            int ix = isle[0], iz = isle[1], itype = isle[2];
            double r = unit(h, 6);
            SiteKind mKind = switch (itype) {
                case com.skycraft.tamriel.terrain.OceanIsles.TYPE_SEA_STACK ->
                        r < 0.32 ? SiteKind.OVERLOOK : r < 0.62 ? SiteKind.SEA_SHRINE : r < 0.84 ? SiteKind.BANDIT_LOOKOUT : SiteKind.TREASURE_CACHE;
                case com.skycraft.tamriel.terrain.OceanIsles.TYPE_ATOLL ->
                        r < 0.28 ? SiteKind.FISHING_DOCK : r < 0.56 ? SiteKind.HERMIT_HUT : r < 0.80 ? SiteKind.SEA_SHRINE : SiteKind.TREASURE_CACHE;
                case com.skycraft.tamriel.terrain.OceanIsles.TYPE_CAY ->
                        r < 0.32 ? SiteKind.ABANDONED_CAMP : r < 0.58 ? SiteKind.HERMIT_HUT : r < 0.80 ? SiteKind.SEA_SHRINE : SiteKind.TREASURE_CACHE;
                default ->
                        r < 0.55 ? SiteKind.SHIPWRECK_DEBRIS : r < 0.82 ? SiteKind.TREASURE_CACHE : SiteKind.FISHING_DOCK;
            };
            int m = mKind.radius + PLACE_MARGIN;
            if (!Places.overlapping(ix - m, iz - m, ix + m, iz + m).isEmpty()) return null;
            Ctx ictx = Ctx.of(ix, iz);
            int facing = (int) (unit(h, 7) * 4);
            return new Site(cx, cz, ix, iz, mKind, mix(h ^ (mKind.ordinal() * 0x632BE59BD9B4E019L)), facing, null,
                    ictx.region.id(), ictx.province);
        }
        if (unit(h, 5) >= occupancy(ctx) * ExploreConfig.densityScale()) return null;
        if (ctx.s.inWater() || ctx.s.coastDistance < 3) return null;
        SiteKind kind = choose(ctx, unit(h, 6));
        if (kind == null) return null;
        int m = kind.radius + PLACE_MARGIN;
        if (!Places.overlapping(x - m, z - m, x + m, z + m).isEmpty()) return null;
        // the footprint stays dry (docks and wrecks reach the water on purpose)
        if (kind != SiteKind.FISHING_DOCK && kind != SiteKind.SHIPWRECK_DEBRIS) {
            MapSample p = new MapSample();
            int r = kind.radius;
            int[][] probes = {{r, 0}, {-r, 0}, {0, r}, {0, -r}};
            for (int[] o : probes) {
                TamrielMap.sample(x + o[0], z + o[1], p);
                if (p.inWater() || p.coastDistance < 1) return null;
            }
        }
        boolean waterSite = kind == SiteKind.FISHING_DOCK || kind == SiteKind.SHIPWRECK_DEBRIS;
        int facing = waterSite ? waterFacing(x, z, ctx) : (int) (unit(h, 7) * 4);
        if (waterSite) {
            // walk down to the waterline (staying inside the cell): wrecks lie on the beach, docks start at the bank
            int[] at = shoreward(x, z, facing, cx, cz);
            x = at[0];
            z = at[1];
            if (!Places.overlapping(x - m, z - m, x + m, z + m).isEmpty()) return null;
        }
        return new Site(cx, cz, x, z, kind, mix(h ^ (kind.ordinal() * 0x632BE59BD9B4E019L)), facing, null,
                ctx.region.id(), ctx.province);
    }

    private static final java.util.Set<String> ISLES = java.util.Set.of("hammerfell/stros_mkai", "summerset/artaeum",
            "yokuda/sunken_isles", "akavir/tang_mo", "thras/coral_kingdom", "pyandonea/maormer_isles");

    /**
     * Steps from (x, z) towards the water ({@code facing}) until the map says the shore is a few blocks away, at most
     * 60 blocks and never out of the cell. Returns the new centre.
     */
    private static int[] shoreward(int x, int z, int facing, int cx, int cz) {
        Direction d = Direction.from2DDataValue(facing);
        MapSample p = new MapSample();
        int minX = cx * CELL + 12, maxX = cx * CELL + CELL - 13, minZ = cz * CELL + 12, maxZ = cz * CELL + CELL - 13;
        int bx = x, bz = z;
        for (int step = 4; step <= 60; step += 4) {
            int px = x + d.getStepX() * step, pz = z + d.getStepZ() * step;
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) break;
            TamrielMap.sample(px, pz, p);
            if (p.inWater() || p.coastDistance < 2) break;
            bx = px;
            bz = pz;
            boolean nearLake = !Float.isNaN(p.waterLevel) && (p.lakeDistance > -6 || p.riverDistance < p.riverHalfWidth + 6);
            if (p.coastDistance < 8 || nearLake) break;
        }
        return new int[]{bx, bz};
    }

    /** The share of cells that hold a site: lower where named places already crowd the land. */
    static double occupancy(Ctx c) {
        // small islands: most of their cells are sea, so the few on land are all used
        if (ISLES.contains(c.region.id())) return 0.9;
        return switch (c.province) {
            case "skyrim" -> 0.45;
            case "morrowind" -> c.region.id().equals("morrowind/solstheim") ? 0.5 : 0.6;
            // Black Marsh has the fewest towns and roads to fill the gaps
            case "black_marsh" -> 0.7;
            case "cyrodiil", "high_rock", "hammerfell", "elsweyr", "valenwood", "summerset" -> 0.6;
            case "realm" -> 0.6;
            default -> 0.6; // the fixed foreign lands
        };
    }

    // ------------------------------------------------------------------ choosing a kind

    private static final SiteKind[] K = SiteKind.VALUES;

    @Nullable
    static SiteKind choose(Ctx c, double u) {
        double[] w = weights(c);
        double total = 0;
        for (double v : w) total += v;
        if (total <= 0) return null;
        double roll = u * total;
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0) return K[i];
        }
        return null;
    }

    /** Weights per kind for this spot. Also used by the audit to report the mix. */
    static double[] weights(Ctx c) {
        double[] w = new double[K.length];
        String p = c.province;
        String arch = c.archetype;
        boolean foreignSea = p.equals("pyandonea") || p.equals("thras");
        w[SiteKind.ABANDONED_CAMP.ordinal()] = 5;
        w[SiteKind.HUNTERS_CAMP.ordinal()] = foreignSea ? 1 : 4;
        w[SiteKind.WAYSHRINE.ordinal()] = nineLands(p) ? 3 + (c.s.road > 0.25f ? 6 : 0) : 0;
        w[SiteKind.TREASURE_CACHE.ordinal()] = 4;
        w[SiteKind.DEAD_ADVENTURER.ordinal()] = 5;
        w[SiteKind.HERB_GARDEN.ordinal()] = 2;
        w[SiteKind.RARE_REAGENT.ordinal()] = 2;
        w[SiteKind.STONE_CIRCLE.ordinal()] = 1;
        w[SiteKind.ANCIENT_BATTLEFIELD.ordinal()] = 1.5;
        w[SiteKind.MAGE_RUIN.ordinal()] = 1.5;
        w[SiteKind.HERMIT_HUT.ordinal()] = 2;
        w[SiteKind.BANDIT_LOOKOUT.ordinal()] = 1 + (c.high ? 4 : 0);
        w[SiteKind.OVERLOOK.ordinal()] = c.high ? 5 : 0;
        w[SiteKind.MINERAL_VEIN.ordinal()] = 1 + (c.mountains ? 4 : 0);
        if (c.coast) w[SiteKind.SHIPWRECK_DEBRIS.ordinal()] = 10;
        if (c.shore) w[SiteKind.FISHING_DOCK.ordinal()] = 10;
        else if (c.coast) w[SiteKind.FISHING_DOCK.ordinal()] = 3;
        if (c.cold) w[SiteKind.HOT_SPRING.ordinal()] = 2.5;
        if (p.equals("skyrim") || p.equals("morrowind") || p.equals("hammerfell") || p.equals("high_rock")) {
            w[SiteKind.DWEMER_VENT.ordinal()] = 1.5 + (c.mountains ? 2 : 0);
        }
        // the culture's own little wonders
        switch (p) {
            case "skyrim" -> w[SiteKind.NORDIC_CAIRN.ordinal()] = 5;
            case "atmora" -> w[SiteKind.NORDIC_CAIRN.ordinal()] = 7;
            case "cyrodiil" -> w[SiteKind.AYLEID_WELL.ordinal()] = 6;
            case "morrowind" -> {
                w[SiteKind.VELOTHI_SHRINE.ordinal()] = 6;
                if (c.region.id().equals("morrowind/solstheim")) w[SiteKind.NORDIC_CAIRN.ordinal()] = 3;
            }
            case "black_marsh" -> w[SiteKind.HIST_SAPLING.ordinal()] = 6;
            case "elsweyr" -> w[SiteKind.MOON_SUGAR_FIELD.ordinal()] = 6;
            case "valenwood" -> {
                w[SiteKind.GIANT_BEEHIVE.ordinal()] = 6;
                w[SiteKind.AYLEID_WELL.ordinal()] = 1.5;
            }
            case "summerset" -> w[SiteKind.CRYSTAL_OUTCROP.ordinal()] = 6;
            case "hammerfell", "yokuda" -> w[SiteKind.YOKUDAN_OBELISK.ordinal()] = 6;
            case "high_rock" -> {
                w[SiteKind.BRETON_MENHIR.ordinal()] = 6;
                w[SiteKind.AYLEID_WELL.ordinal()] = 1;
            }
            case "akavir" -> w[SiteKind.AKAVIRI_SHRINE.ordinal()] = 7;
            case "pyandonea", "thras" -> w[SiteKind.SEA_SHRINE.ordinal()] = 7;
            case "realm" -> {
                w[SiteKind.REALM_MONOLITH.ordinal()] = 4;
                switch (arch) {
                    case "frozen", "alpine" -> w[SiteKind.NORDIC_CAIRN.ordinal()] = 5;
                    case "temperate" -> {
                        w[SiteKind.BRETON_MENHIR.ordinal()] = 3;
                        w[SiteKind.AYLEID_WELL.ordinal()] = 3;
                    }
                    case "ash" -> w[SiteKind.VELOTHI_SHRINE.ordinal()] = 5;
                    case "swamp" -> w[SiteKind.HIST_SAPLING.ordinal()] = 5;
                    case "desert" -> w[SiteKind.YOKUDAN_OBELISK.ordinal()] = 5;
                    case "jungle" -> w[SiteKind.GIANT_BEEHIVE.ordinal()] = 5;
                    case "crystal" -> w[SiteKind.CRYSTAL_OUTCROP.ordinal()] = 5;
                    case "savanna" -> w[SiteKind.MOON_SUGAR_FIELD.ordinal()] = 5;
                    case "mushroom" -> w[SiteKind.RARE_REAGENT.ordinal()] += 4;
                    default -> { }
                }
            }
            default -> { }
        }
        return w;
    }

    /** Lands that keep wayshrines of the Nine (Morrowind keeps the Tribunal's and Velothi shrines instead). */
    private static boolean nineLands(String p) {
        return switch (p) {
            case "skyrim", "cyrodiil", "high_rock", "hammerfell", "summerset", "valenwood", "elsweyr", "black_marsh",
                 "atmora", "realm" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ map context

    /** What the map says about a spot: region, culture, coast, shores, height, cold. */
    static final class Ctx {
        final MapSample s = new MapSample();
        Region region;
        String province;
        String archetype;
        boolean coast, shore, high, mountains, cold;

        static Ctx of(int x, int z) {
            Ctx c = new Ctx();
            TamrielMap.sample(x, z, c.s);
            c.region = TamrielMap.region(x, z);
            c.province = Cultures.province(c.region, x, z);
            String pal = c.region.palette() == null ? "" : c.region.palette();
            c.archetype = pal.startsWith("realm_") ? pal.substring(6) : "";
            MapSample s = c.s;
            c.coast = s.coastDistance >= 3 && s.coastDistance <= 64 && s.elevation < TamrielMap.SEA_LEVEL + 14;
            boolean hasWater = !Float.isNaN(s.waterLevel);
            c.shore = hasWater && ((s.lakeDistance >= -40 && s.lakeDistance <= -3)
                    || (s.riverHalfWidth >= 4 && s.riverDistance >= s.riverHalfWidth + 3 && s.riverDistance <= s.riverHalfWidth + 40));
            // height over the surroundings (a ring of map samples)
            MapSample p = new MapSample();
            double sum = 0;
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                TamrielMap.sample(x + (int) (Math.cos(a) * 80), z + (int) (Math.sin(a) * 80), p);
                sum += p.elevation;
            }
            float prominence = (float) (s.elevation - sum / 6);
            c.high = prominence > 10 || s.ridge > 0.45f;
            c.mountains = s.crag > 0.3f || s.ridge > 0.3f || s.elevation > 130;
            String id = c.region.id();
            c.cold = s.elevation > 170 || id.equals("skyrim/the_pale") || id.equals("skyrim/winterhold")
                    || id.equals("skyrim/eastmarch") || id.equals("morrowind/solstheim") || id.equals("akavir/kamal")
                    || id.equals("cyrodiil/jerall_mountains") || c.province.equals("atmora")
                    || c.archetype.equals("frozen") || c.archetype.equals("alpine");
            return c;
        }
    }

    /** Direction (2D data value) towards the nearest water from (x, z), probing 20 blocks out. */
    private static int waterFacing(int x, int z, Ctx c) {
        MapSample p = new MapSample();
        int best = 0;
        float bestScore = Float.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            Direction dir = Direction.from2DDataValue(d);
            float score = 0;
            for (int step = 8; step <= 24; step += 8) {
                TamrielMap.sample(x + dir.getStepX() * step, z + dir.getStepZ() * step, p);
                score += p.inWater() ? -50 : p.coastDistance;
            }
            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /**
     * (x, z) or the nearest map spot to it inside cell (cx, cz) that is dry and clear of every named place's footprint
     * by {@code margin}; null if the cell has none. Pinned sites (uniques) use this, so a place added later near a
     * unique's spot pushes the site aside instead of building on top of it.
     */
    @Nullable
    private static int[] spotInCell(int x, int z, int cx, int cz, int margin) {
        MapSample p = new MapSample();
        int minX = cx * CELL + 8, maxX = cx * CELL + CELL - 9, minZ = cz * CELL + 8, maxZ = cz * CELL + CELL - 9;
        for (int d = 0; d <= CELL; d += 8) {
            int steps = d == 0 ? 1 : 8 + d / 8;
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps;
                int px = x + (int) Math.round(Math.cos(ang) * d), pz = z + (int) Math.round(Math.sin(ang) * d);
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) continue;
                TamrielMap.sample(px, pz, p);
                if (p.inWater() || p.coastDistance < 3) continue;
                if (!Places.overlapping(px - margin, pz - margin, px + margin, pz + margin).isEmpty()) continue;
                return new int[]{px, pz};
            }
        }
        return null;
    }

    /** Lower-case id helper for logs and the audit. */
    static String describe(Site s) {
        return String.format(Locale.ROOT, "%s at %d %d (%s)", s.kind().id, s.x(), s.z(), s.regionId());
    }
}

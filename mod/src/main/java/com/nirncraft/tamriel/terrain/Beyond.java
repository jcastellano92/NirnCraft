package com.nirncraft.tamriel.terrain;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nirn beyond the Tamriel raster (docs/TAMRIEL.md section 3).
 *
 * <ul>
 *   <li>The fixed foreign lands, placed in their lore directions and shaped by fixed noise: Atmora (north), Akavir and
 *       the Tang Mo isles (east), Yokuda and the Sunken Isles (west), Pyandonea and Thras (south-west).</li>
 *   <li>Beyond the fixed world, only open ocean. The former Endless Lands generator and saved-region lookup
 *       remain available to explicit tools and old data; they are not runtime geography.</li>
 * </ul>
 *
 * Everything is deterministic and independent of the world seed. {@link #sample} is allocation-free; generated
 * {@link Region} objects are created on demand and cached.
 */
public final class Beyond {
    /** Hard geography policy: dormant procedural realms are not active world coverage. */
    public static final boolean ENDLESS_LANDS_ENABLED = false;
    public static final int SUPER = 12288;
    /** Super-cells overlapping this box stay ocean: it holds Tamriel and the fixed foreign lands. */
    private static final int CORE_MIN_X = -27000, CORE_MAX_X = 26000, CORE_MIN_Z = -18500, CORE_MAX_Z = 26500;

    private Beyond() {}

    // ---- archetypes of the endless lands -----------------------------------------------------------------------------
    enum Archetype {
        FROZEN(95, 20, .5f, .4f, 0, 0, 120, .5f, 260, "atmoran", "frozen",
                "Wastes", "Icefields", "Rime Coast", "Frostmarch", "Glacier"),
        TEMPERATE(85, 14, .4f, .15f, 0, 0, 220, .3f, 220, "breton", "temperate",
                "Vale", "Downs", "Weald", "Marches", "Shire"),
        ASH(105, 25, .6f, .6f, 0, 0, 140, .55f, 300, "dunmer", "ash",
                "Ashlands", "Cinder Fields", "Ember Reach", "Blight", "Scorch"),
        SWAMP(65, 2, .15f, 0, 0, 1, 400, 0, 0, "argonian", "swamp",
                "Mire", "Fens", "Bog", "Sloughs", "Deepwater"),
        DESERT(88, 12, .3f, .25f, .95f, 0, 260, .25f, 220, "yokudan", "desert",
                "Sands", "Dunes", "Barrens", "Waste", "Erg"),
        JUNGLE(78, 12, .45f, .2f, 0, .25f, 200, .3f, 220, "khajiit", "jungle",
                "Wilds", "Jungle", "Canopy", "Tangle", "Verdance"),
        CRYSTAL(95, 18, .45f, .7f, 0, 0, 100, .45f, 260, "altmer", "maritime",
                "Shimmer Coast", "Glasslands", "Prism Reach", "Crystal Vale", "Gleam"),
        SAVANNA(92, 8, .3f, .2f, .1f, 0, 260, .15f, 200, "khajiit", "savanna",
                "Plains", "Grasslands", "Steppe", "Veld", "Ochre Fields"),
        MUSHROOM(80, 15, .45f, .3f, 0, .2f, 180, .25f, 220, "dunmer", "temperate",
                "Fungal Groves", "Sporelands", "Cap Forest", "Mycel Reach", "Shroom Vale"),
        ALPINE(130, 30, .75f, .6f, 0, 0, 120, 1f, 330, "nord", "skyrim_cold",
                "Peaks", "Highlands", "Crags", "Spires", "Heights");

        final Regions.Terrain terrain;
        final float mountains, peak;
        final String culture, weather, id;
        final String[] words;

        Archetype(float base, float relief, float rough, float crag, float dunes, float wet, float coast, float mountains,
                  float peak, String culture, String weather, String... words) {
            this.terrain = new Regions.Terrain(base, relief, rough, crag, dunes, wet, coast);
            this.mountains = mountains;
            this.peak = peak;
            this.culture = culture;
            this.weather = weather;
            this.words = words;
            this.id = name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // ---- the fixed foreign lands -------------------------------------------------------------------------------------
    private enum Shape { CONTINENT, ARCHIPELAGO }

    private record Land(String name, Shape shape, double cx, double cz, double rx, double rz, int seed,
                        float mountains, double islandScale, double reach) {
        boolean near(double x, double z) {
            double dx = x - cx, dz = z - cz;
            return dx * dx + dz * dz < reach * reach;
        }
    }

    private static final Land ATMORA = land("atmora", Shape.CONTINENT, 0, -11500, 4300, 2600, 11, .45f, 0);
    private static final Land AKAVIR = land("akavir", Shape.CONTINENT, 17600, 2800, 3300, 4900, 12, .35f, 0);
    private static final Land TANG_MO = land("tang_mo", Shape.ARCHIPELAGO, 22200, 5200, 2000, 2000, 13, .3f, 520);
    private static final Land YOKUDA = land("yokuda", Shape.CONTINENT, -19800, 6300, 3200, 4300, 14, .25f, 0);
    private static final Land SUNKEN = land("sunken_isles", Shape.ARCHIPELAGO, -18300, 11300, 2100, 2100, 15, .15f, 600);
    private static final Land PYANDONEA = land("pyandonea", Shape.ARCHIPELAGO, -19300, 20600, 3000, 3000, 16, .2f, 1100);
    private static final Land THRAS = land("thras", Shape.ARCHIPELAGO, -15200, 17000, 1500, 1500, 17, .1f, 420);
    private static final Land[] LANDS = {ATMORA, AKAVIR, TANG_MO, YOKUDA, SUNKEN, PYANDONEA, THRAS};

    private static Land land(String name, Shape shape, double cx, double cz, double rx, double rz, int seed,
                             float mountains, double islandScale) {
        return new Land(name, shape, cx, cz, rx, rz, seed, mountains, islandScale, Math.max(rx, rz) * 1.5 + 1200);
    }

    private static final int R_ICE = Regions.indexOf("atmora/ice_waste"), R_FOREST = Regions.indexOf("atmora/frozen_forest");
    private static final int R_TSAESCI = Regions.indexOf("akavir/tsaesci"), R_KAMAL = Regions.indexOf("akavir/kamal");
    private static final int R_TANGMO = Regions.indexOf("akavir/tang_mo"), R_KAPOTUN = Regions.indexOf("akavir/ka_po_tun");
    private static final int R_YOKUDA = Regions.indexOf("yokuda/yokudan_desert"), R_SUNKEN = Regions.indexOf("yokuda/sunken_isles");
    private static final int R_PYANDONEA = Regions.indexOf("pyandonea/maormer_isles"), R_THRAS = Regions.indexOf("thras/coral_kingdom");
    private static final int R_GHOSTS = Regions.indexOf("sea/sea_of_ghosts"), R_PADOMAIC = Regions.indexOf("sea/padomaic_ocean");
    private static final int R_ELTHERIC = Regions.indexOf("sea/eltheric_ocean"), R_ABECEAN = Regions.indexOf("sea/abecean_sea");

    /** Signed coast distance (approximate blocks) of a fixed land at (x, z). */
    private static double landField(Land l, double x, double z) {
        double dx = x - l.cx, dz = z - l.cz;
        if (l.shape == Shape.CONTINENT) {
            double e = Math.sqrt((dx / l.rx) * (dx / l.rx) + (dz / l.rz) * (dz / l.rz));
            double r = Math.sqrt(l.rx * l.rz);
            return (1 - e) * r + MapNoise.fbm(x, z, 1700, 3, l.seed) * 700 + MapNoise.fbm(x, z, 320, 2, l.seed + 5) * 130;
        }
        double d2 = (dx * dx + dz * dz) / (l.rx * l.rz);
        double m = 1 - d2;
        double g = MapNoise.fbm(x, z, l.islandScale, 3, l.seed) + 0.5 * m - 0.36;
        return g * l.islandScale * 0.6;
    }

    private static int fixedRegion(Land l, double x, double z) {
        if (l == ATMORA) return (z - l.cz) + MapNoise.fbm(x, z, 900, 2, 61) * 700 < -250 ? R_ICE : R_FOREST;
        if (l == AKAVIR) {
            double u = x - l.cx + MapNoise.fbm(x, z, 900, 2, 62) * 600;
            double v = z - l.cz + MapNoise.fbm(x, z, 900, 2, 63) * 600;
            if (v < -1500) return R_KAMAL;
            if (u < -200 && v > -300) return R_TSAESCI;
            return R_KAPOTUN;
        }
        if (l == TANG_MO) return R_TANGMO;
        if (l == YOKUDA) return R_YOKUDA;
        if (l == SUNKEN) return R_SUNKEN;
        if (l == PYANDONEA) return R_PYANDONEA;
        return R_THRAS;
    }

    // ---- sampling ------------------------------------------------------------------------------------------------

    /** True when (x, z) could be on or near a fixed foreign land (cheap bounding test). */
    public static boolean nearForeign(int x, int z) {
        for (Land l : LANDS) if (l.near(x, z)) return true;
        return false;
    }

    /** Authored raster and the complete fixed foreign influence envelopes, including their coastal seas. */
    public static boolean fixedWorldFootprint(int x, int z) {
        return TamrielMap.inTamriel(x, z) || nearForeign(x, z);
    }

    /**
     * Inside the raster near a foreign land: overrides {@code out} with the foreign land where it reaches further than
     * Tamriel's sea (union of the two).
     */
    public static void mergeForeign(int x, int z, MapSample out) {
        Land best = null;
        double bestCd = out.coastDistance;
        for (Land l : LANDS) {
            if (!l.near(x, z)) continue;
            double cd = landField(l, x, z);
            if (cd > bestCd) {
                bestCd = cd;
                best = l;
            }
        }
        if (best == null) return;
        int region = bestCd > 0 ? fixedRegion(best, x, z) : out.region;
        fillLand(x, z, bestCd, region, Regions.terrain(fixedRegion(best, x, z)), best.mountains, 270, best.seed, out);
        out.inTamriel = true;
    }

    /** Fills {@code out} for a column outside the raster. */
    public static void sample(int x, int z, MapSample out) {
        sample(x,z,out,ENDLESS_LANDS_ENABLED);
    }

    /** Dormant historical generator for explicit offline tools; never used by normal world generation. */
    public static void sampleEndlessForTools(int x, int z, MapSample out) {
        sample(x,z,out,true);
    }

    private static void sample(int x, int z, MapSample out, boolean endlessPreview) {
        out.clear();
        // distance to the raster's sea, continued outward so the sea floor joins up at the raster edge
        double ex = MapNoise.clamp(x, TamrielMap.MIN_X + 48, TamrielMap.MAX_X - 49);
        double ez = MapNoise.clamp(z, TamrielMap.MIN_Z + 48, TamrielMap.MAX_Z - 49);
        double outside = Math.hypot(x - ex, z - ez);
        double edgeCd = MapRaster.get().loaded() ? edgeCoast(ex, ez) - outside : -100000;
        double cd = edgeCd;
        int region = seaRegion(x, z, outside);
        Regions.Terrain terrain = Regions.Terrain.DEFAULT;
        float mountains = 0;
        float peak = 260;
        int seed = 0;

        for (Land l : LANDS) {
            if (!l.near(x, z)) continue;
            double c = landField(l, x, z);
            if (c > cd) {
                cd = c;
                int fr = fixedRegion(l, x, z);
                terrain = Regions.terrain(fr);
                mountains = l.mountains;
                peak = 270;
                seed = l.seed;
                if (c > 0) region = fr;
            }
        }
        // the endless lands
        int cx = Math.floorDiv(x, SUPER), cz = Math.floorDiv(z, SUPER);
        if (endlessPreview && !coreCell(cx, cz)) {
            int h = MapNoise.hash(cx, cz, 7001);
            int type = Math.floorMod(h, 100);
            if (type >= 30) {
                Archetype arch = archetype(cx, cz);
                double c = realmField(x, z, cx, cz, type < 60);
                if (c > cd) {
                    cd = c;
                    terrain = arch.terrain;
                    mountains = arch.mountains;
                    peak = arch.peak;
                    seed = h;
                    if (c > 0) region = encode(cx, cz, realmSite(x, z, cx, cz));
                }
            }
        }
        fillLand(x, z, cd, region, terrain, mountains, peak, seed, out);
        out.inTamriel = false;
    }

    private static double edgeCoast(double ex, double ez) {
        MapRaster r = MapRaster.get();
        double fx = (ex - TamrielMap.MIN_X) / TamrielMap.CELL - 0.5, fz = (ez - TamrielMap.MIN_Z) / TamrielMap.CELL - 0.5;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz;
        double a = r.cell(ix, iz, MapRaster.COAST), b = r.cell(ix + 1, iz, MapRaster.COAST);
        double c = r.cell(ix, iz + 1, MapRaster.COAST), d = r.cell(ix + 1, iz + 1, MapRaster.COAST);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    private static int seaRegion(int x, int z, double outside) {
        if (outside > 14000) return 0;
        double dxw = TamrielMap.MIN_X - x, dxe = x - TamrielMap.MAX_X, dzn = TamrielMap.MIN_Z - z, dzs = z - TamrielMap.MAX_Z;
        double m = Math.max(Math.max(dxw, dxe), Math.max(dzn, dzs));
        if (m == dzn) return R_GHOSTS;
        if (m == dxe) return z > 9000 ? R_ELTHERIC : R_PADOMAIC;
        if (m == dzs) return x < -6000 ? R_ABECEAN : R_ELTHERIC;
        return z < -1000 ? R_GHOSTS : R_ABECEAN;
    }

    /** Common land/sea shaping from a coast distance, shared by the foreign and endless lands. */
    private static void fillLand(int x, int z, double cd, int region, Regions.Terrain t, float mountains, float peak,
                                 int seed, MapSample out) {
        out.region = region;
        out.blendRegion = region;
        out.blendWeight = 0;
        out.coastDistance = (float) cd;
        out.waterLevel = Float.NaN;
        out.riverDistance = 10000f;
        out.riverHalfWidth = 0;
        out.lakeDistance = -10000f;
        out.road = 0;
        if (cd <= 0) {
            out.elevation = (float) (TamrielMap.SEA_LEVEL - seaDepth(-cd));
            out.ridge = 0;
            out.roughness = 0.2f;
            out.crag = 0;
            out.dunes = 0;
            out.wetland = 0;
            TamrielMap.deriveStrengths(out);
            return;
        }
        double base = t.base() + t.relief() * MapNoise.fbm(x, z, 1200, 3, seed + 1)
                + t.rough() * 26 * (MapNoise.ridged(x, z, 1500, 3, seed + 2) - 0.42);
        double ramp = Math.max(60, t.coast() * 1.8);
        double beach = TamrielMap.SEA_LEVEL + 0.05 * Math.min(cd, 64);
        double h = beach + (Math.max(base, beach) - beach) * MapNoise.smoothstep(48, 48 + ramp, cd);
        double ridge = 0;
        if (mountains > 0) {
            double rn = MapNoise.ridged(x, z, 1900, 4, seed + 3);
            double m = MapNoise.smoothstep(0.42, 0.85, rn) * mountains * MapNoise.smoothstep(150, 1100, cd);
            h += Math.max(0, peak - h) * m * m * (3 - 2 * m) * 0.9;
            ridge = m;
        }
        if (t.wet() > 0.2f) {
            double wv = MapNoise.smoothstep(0.2, 0.6, t.wet());
            double wl = TamrielMap.SEA_LEVEL + 0.7 + 1.7 * MapNoise.fbm(x, z, 160, 2, 41);
            h += (wl - h) * wv * MapNoise.smoothstep(TamrielMap.SEA_LEVEL + 45, TamrielMap.SEA_LEVEL + 20, h);
            out.wetland = (float) wv;
        } else {
            out.wetland = 0;
        }
        out.elevation = (float) Math.min(h, 372);
        out.ridge = (float) ridge;
        out.roughness = (float) MapNoise.clamp(t.rough() + ridge * 0.45, 0, 1);
        out.crag = (float) MapNoise.clamp(t.crag() + ridge * 0.6 * MapNoise.smoothstep(110, 200, h), 0, 1);
        out.dunes = t.dunes();
        TamrielMap.deriveStrengths(out);
    }

    /** Depth below sea level at d blocks from the coast (same curve as tamriel_map.py). */
    static double seaDepth(double d) {
        return 0.05 * Math.min(d, 64) + 75 * (1 - StrictMath.exp(-Math.max(d - 64, 0) / 1300.0));
    }

    // ---- endless lands ---------------------------------------------------------------------------------------------
    private static boolean coreCell(int cx, int cz) {
        long x0 = (long) cx * SUPER, z0 = (long) cz * SUPER;
        return x0 + SUPER > CORE_MIN_X && x0 < CORE_MAX_X && z0 + SUPER > CORE_MIN_Z && z0 < CORE_MAX_Z;
    }

    static Archetype archetype(int cx, int cz) {
        Archetype[] all = Archetype.values();
        // climate bands: the far north and south lean frozen/alpine, the middle latitudes everything else
        int pick = Math.floorMod(MapNoise.hash(cx, cz, 7002), all.length);
        if (cz < -6 && MapNoise.rand(cx, cz, 7003) < 0.5) return MapNoise.rand(cx, cz, 7004) < 0.6 ? Archetype.FROZEN : Archetype.ALPINE;
        return all[pick];
    }

    private static double cellCentreX(int cx, int cz) {
        return (cx + 0.5 + (MapNoise.rand(cx, cz, 7010) - 0.5) * 0.16) * SUPER;
    }

    private static double cellCentreZ(int cx, int cz) {
        return (cz + 0.5 + (MapNoise.rand(cx, cz, 7011) - 0.5) * 0.16) * SUPER;
    }

    private static double realmField(int x, int z, int cx, int cz, boolean archipelago) {
        double ccx = cellCentreX(cx, cz), ccz = cellCentreZ(cx, cz);
        int seed = MapNoise.hash(cx, cz, 7012);
        double f;
        if (archipelago) {
            double r = 3400 + MapNoise.rand(cx, cz, 7013) * 1100;
            double scale = 450 + MapNoise.rand(cx, cz, 7014) * 500;
            double d2 = ((x - ccx) * (x - ccx) + (z - ccz) * (z - ccz)) / (r * r);
            f = (MapNoise.fbm(x, z, scale, 3, seed) + 0.5 * (1 - d2) - 0.36) * scale * 0.6;
        } else {
            double rx = 2900 + MapNoise.rand(cx, cz, 7015) * 1300, rz = 2900 + MapNoise.rand(cx, cz, 7016) * 1300;
            double dx = (x - ccx) / rx, dz = (z - ccz) / rz;
            double e = Math.sqrt(dx * dx + dz * dz);
            f = (1 - e) * Math.sqrt(rx * rz) + MapNoise.fbm(x, z, 1600, 3, seed) * 650
                    + MapNoise.fbm(x, z, 300, 2, seed + 5) * 120;
        }
        long x0 = (long) cx * SUPER, z0 = (long) cz * SUPER;
        double edge = Math.min(Math.min(x - x0, x0 + SUPER - x), Math.min(z - z0, z0 + SUPER - z));
        return Math.min(f, edge - 700);
    }

    private static int siteCount(int cx, int cz) {
        return 2 + Math.floorMod(MapNoise.hash(cx, cz, 7020), 4);
    }

    private static int realmSite(int x, int z, int cx, int cz) {
        int n = siteCount(cx, cz);
        double ccx = cellCentreX(cx, cz), ccz = cellCentreZ(cx, cz);
        double wx = x + MapNoise.fbm(x, z, 700, 2, 7021) * 400, wz = z + MapNoise.fbm(x, z, 700, 2, 7022) * 400;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            double sx = ccx + (MapNoise.rand(cx * 8 + k, cz, 7023) - 0.5) * 6000;
            double sz = ccz + (MapNoise.rand(cx, cz * 8 + k, 7024) - 0.5) * 6000;
            double d = (wx - sx) * (wx - sx) + (wz - sz) * (wz - sz);
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    // ---- generated region objects ----------------------------------------------------------------------------------
    private static final ConcurrentHashMap<Integer, Region> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 4096;

    /** Packs a super-cell and site into a negative region index (12 bits per axis, 3 for the site). */
    static int encode(int cx, int cz, int site) {
        return -(1 + (((cx & 0xFFF) << 15) | ((cz & 0xFFF) << 3) | (site & 7)));
    }

    public static Region generated(int index) {
        Region r = CACHE.get(index);
        if (r != null) return r;
        int v = -index - 1;
        int cx = (v >> 15) & 0xFFF, cz = (v >> 3) & 0xFFF, site = v & 7;
        cx = (cx << 20) >> 20;
        cz = (cz << 20) >> 20;
        r = build(cx, cz, site);
        if (CACHE.size() > CACHE_LIMIT) CACHE.clear();
        CACHE.put(index, r);
        return r;
    }

    public static Optional<Region> generatedById(String id) {
        // realm/x<cx>_z<cz>/<n>
        if (!id.startsWith("realm/x")) return Optional.empty();
        try {
            int us = id.indexOf("_z"), sl = id.lastIndexOf('/');
            int cx = Integer.parseInt(id.substring(7, us));
            int cz = Integer.parseInt(id.substring(us + 2, sl));
            int site = Integer.parseInt(id.substring(sl + 1));
            if (coreCell(cx, cz) || site < 0 || site >= siteCount(cx, cz)) return Optional.empty();
            return Optional.of(generated(encode(cx, cz, site)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final String[] PRE = {"Vel", "Ka", "Mor", "Thal", "Ys", "Or", "Bra", "Sil", "Zan", "Ela", "Dun", "Hra",
            "Qua", "Tor", "Ny", "Ar", "Gal", "Ser", "Ul", "Vae", "Kor", "Ith", "Sha", "Bel"};
    private static final String[] MID = {"a", "e", "i", "o", "u", "ae", "ia", "or", "en", "ul", "ar", "is"};
    private static final String[] SUF = {"mor", "dar", "thas", "vyr", "nia", "kesh", "lund", "gard", "oth", "ir", "esh",
            "ra", "mund", "val", "rhen", "zir"};

    /** Generated name of the landmass in super-cell (cx, cz). */
    public static String landName(int cx, int cz) {
        int h = MapNoise.hash(cx, cz, 7100);
        String name = PRE[Math.floorMod(h, PRE.length)] + MID[Math.floorMod(h >> 5, MID.length)]
                + SUF[Math.floorMod(h >> 10, SUF.length)];
        return Math.floorMod(MapNoise.hash(cx, cz, 7001), 100) < 60 ? "Isles of " + name : name;
    }

    private static Region build(int cx, int cz, int site) {
        Archetype a = archetype(cx, cz);
        String land = landName(cx, cz);
        String word = a.words[Math.floorMod(MapNoise.hash(cx, cz, 7101) + site, a.words.length)];
        String bare = land.startsWith("Isles of ") ? land.substring(9) : land;
        String name = bare + " " + word;
        int ring = Math.max(Math.abs(cx), Math.abs(cz));
        int min = Math.min(60, 40 + ring * 2), max = Math.min(100, 60 + ring * 5);
        String id = "realm/x" + cx + "_z" + cz + "/" + site;
        return new Region(id, "realm", "realm", a.culture,
                Component.translatableWithFallback("region.nirncraft.realm.generated", name, name), min, max,
                "realm_" + a.id, "realm", a.weather, null);
    }

    /** The archetype id of a generated region index ({@code frozen}, {@code alpine}, ...), for palettes and tools. */
    public static String archetypeOf(int index) {
        if (index >= 0) return null;
        int v = -index - 1;
        int cx = ((((v >> 15) & 0xFFF)) << 20) >> 20, cz = ((((v >> 3) & 0xFFF)) << 20) >> 20;
        return archetype(cx, cz).id;
    }
}

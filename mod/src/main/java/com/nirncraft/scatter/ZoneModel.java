package com.nirncraft.scatter;

import com.nirncraft.dungeons.world.Cultures;
import com.nirncraft.explore.MinorSites;
import com.nirncraft.explore.SiteKind;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides the {@link ScatterZone} of a chunk from the map: distance to the nearest settlement of each size class (one
 * query of the {@link Places} spatial index), to a road (the map's road corridor channel; the live road network is
 * consulted by {@link RoadProbe} where it can be), to water, dungeons and ruins. Pure function of the world seed and
 * the map: deterministic, thread-safe, cached per chunk.
 *
 * <p>The zone is also given as two continuous numbers so densities fade smoothly instead of stepping at zone borders:
 * {@code humanity} (1 at a settlement's edge, 0 beyond its reach) and {@code remote} (0 beside any settlement, 1 a
 * long walk from every one).</p>
 */
public final class ZoneModel {
    /** Search box half size: capital footprint (200) + reach (260), and enough to call a place "far from everything". */
    static final int SEARCH = 720;
    /** Edge distance (blocks) from every settlement beyond which land counts as deep wild. */
    static final int DEEP = 640;
    /** Distance from the settlement edge at which "remote" reaches 1. */
    static final int REMOTE_FULL = 900;

    /**
     * @param settleEdge  blocks from the nearest settlement's footprint (negative inside); large when none is near
     * @param roadHint    0..1, the map's road corridor strength around the chunk
     * @param waterDist   blocks to the nearest water (lake, river, coast); large when none
     * @param dungeonEdge blocks from the nearest named dungeon's footprint
     * @param ruinEdge    blocks from the nearest fort, landmark, battlefield or mage ruin
     */
    public record Info(ScatterZone zone, float humanity, float remote, int settleEdge, PlaceType settleType, String settleId,
                       float roadHint, int waterDist, int dungeonEdge, int ruinEdge, String province, String archetype,
                       String regionId, float elevation, boolean mountains) {
    }

    /** Landmark variants that are ruins, altars or monuments (not springs, ponds, peaks, gates or districts). */
    private static final java.util.Set<String> RUIN_VARIANTS = java.util.Set.of("ruin", "dwemer", "altar", "monument", "stones");

    private static final Info NONE = new Info(ScatterZone.NONE, 0, 1, 9999, null, "", 0, 9999, 9999, 9999, "sea", "", "", 0, false);

    private static final class Cache {
        final long seed;
        final ConcurrentHashMap<Long, Info> chunks = new ConcurrentHashMap<>();

        Cache(long seed) {
            this.seed = seed;
        }
    }

    private static volatile Cache cache = new Cache(0);

    private ZoneModel() {}

    /** The zone info of chunk (cx, cz). */
    public static Info chunk(long seed, int cx, int cz) {
        Cache c = cache;
        if (c.seed != seed) {
            c = new Cache(seed);
            cache = c;
        }
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Info hit = c.chunks.get(key);
        if (hit != null) return hit;
        Info info = compute(seed, cx, cz);
        if (c.chunks.size() > 60_000) c.chunks.clear();
        c.chunks.put(key, info);
        return info;
    }

    /** The zone info around a block column (its chunk's). */
    public static Info at(long seed, int x, int z) {
        return chunk(seed, x >> 4, z >> 4);
    }

    /** Blocks to the nearest water in the map sample (coast, lake shore, river bank); large when none is near. */
    public static int waterDistance(MapSample s) {
        float d = 9999f;
        if (s.coastDistance >= 0 && s.coastDistance < 9999f) d = s.coastDistance;
        if (!Float.isNaN(s.waterLevel)) {
            float lake = s.lakeDistance > -9000f ? -s.lakeDistance : 9999f;
            float river = Math.max(0f, s.riverDistance - s.riverHalfWidth);
            d = Math.min(d, Math.min(lake, river));
        }
        return (int) Math.min(d, 9999f);
    }

    private static Info compute(long seed, int cx, int cz) {
        ScatterRules rules = ScatterRules.get();
        int x = cx * 16 + 8, z = cz * 16 + 8;
        MapSample s = new MapSample();
        TamrielMap.sample(x, z, s);
        Region region = TamrielMap.region(x, z);
        String province = Cultures.province(region, x, z);
        if ("sea".equals(province) || s.coastDistance < -2f || (s.lakeDistance > 20f && !Float.isNaN(s.waterLevel))) return NONE;
        String pal = region.palette() == null ? "" : region.palette();
        String archetype = pal.startsWith("realm_") ? pal.substring(6) : "";

        float best = 0f, urban = 0f;
        int settleEdge = 9999, dungeonEdge = 9999, ruinEdge = 9999;
        PlaceType settleType = null;
        String settleId = "";
        for (Place p : Places.overlapping(x - SEARCH, z - SEARCH, x + SEARCH, z + SEARCH)) {
            int edge = (int) Math.sqrt((double) p.distSqr(x, z)) - p.radius();
            PlaceType t = p.type();
            if (t.isSettlement()) {
                if (edge < settleEdge) {
                    settleEdge = edge;
                    settleType = t;
                    settleId = p.id();
                }
                Integer reach = rules.reach.get(t);
                if (reach != null) {
                    float h = edge <= 0 ? 1f : Math.max(0f, 1f - edge / (float) reach);
                    if (h > best) best = h;
                    if (t != PlaceType.FARM && h > urban) urban = h; // a lone farm gets fields, not a town's clutter
                }
            } else if (t == PlaceType.DUNGEON_FIXED) {
                dungeonEdge = Math.min(dungeonEdge, edge);
            } else if (t == PlaceType.FORT || t == PlaceType.DAEDRIC_SHRINE || t == PlaceType.DRAGON_MOUND
                    || (t == PlaceType.LANDMARK && RUIN_VARIANTS.contains(p.variant()))) {
                ruinEdge = Math.min(ruinEdge, edge);
            }
        }
        for (MinorSites.Site m : MinorSites.within(seed, x, z, 110)) {
            SiteKind k = m.kind();
            if (k == SiteKind.ANCIENT_BATTLEFIELD || k == SiteKind.MAGE_RUIN) {
                ruinEdge = Math.min(ruinEdge, (int) Math.sqrt((double) m.distSqr(x, z)) - k.radius);
            }
        }
        // road corridor strength around the chunk (consult both the map corridor channel and the baked RoadPlan)
        float road = s.road;
        MapSample p = new MapSample();
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            TamrielMap.sample(x + (int) Math.round(Math.cos(a) * 12), z + (int) Math.round(Math.sin(a) * 12), p);
            if (p.road > road) road = p.road;
        }
        com.nirncraft.worldplan.RoadPlan.Hit roadHit = new com.nirncraft.worldplan.RoadPlan.Hit();
        if (com.nirncraft.worldplan.RoadPlan.get().at(x + 0.5, z + 0.5, roadHit)) {
            float margin = roadHit.distance - roadHit.halfWidth;
            if (margin <= 20f) {
                road = 1.0f;
            } else if (margin <= 68f) {
                road = Math.max(road, 1.0f - (margin - 20f) / 48f);
            }
        }
        int water = waterDistance(s);

        float humanity = Math.max(best, 0.5f * road);
        float remote = Math.min(1f, Math.max(0f, (settleEdge - 120) / (float) (REMOTE_FULL - 120)));
        boolean mountains = s.crag > 0.3f || s.ridge > 0.35f || s.elevation > 150f;

        ScatterZone zone;
        if (urban >= 0.5f) zone = ScatterZone.URBAN_FRINGE;
        else if (dungeonEdge < 45) zone = ScatterZone.DUNGEON_ADJACENT;
        else if (ruinEdge < 55) zone = ScatterZone.RUIN_ADJACENT;
        else if (road >= 0.6f) zone = ScatterZone.ROADSIDE;
        else if (water <= 28) zone = ScatterZone.SHORE;
        else if (best >= 0.18f) zone = ScatterZone.FARMLAND;
        else if (settleEdge > DEEP && road < 0.2f && dungeonEdge > 300) zone = ScatterZone.DEEP_WILD;
        else zone = ScatterZone.WILD;
        // the roadside and the shore are never "empty" country
        if (zone == ScatterZone.ROADSIDE) humanity = Math.max(humanity, 0.45f);
        return new Info(zone, humanity, remote, settleEdge, settleType, settleId, road, water, dungeonEdge, ruinEdge, province,
                archetype, region.id(), s.elevation, mountains);
    }
}

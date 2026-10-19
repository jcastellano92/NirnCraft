package com.nirncraft.landmarks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.explore.ExploreConfig;
import com.nirncraft.explore.MinorSites;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the natural landmarks are. Each {@link Tier} has its own grid of cells; a cell holds at most one site, at a
 * hashed chunk well inside it (so a site always has a full 23-block reach in every direction inside the 3x3 chunks a
 * feature may write). Whether a cell is occupied, and by what kind, depends only on the world seed and the Tamriel
 * map, so the worldgen feature, the commands, the "found" check and the audit always agree.
 *
 * <p>Sites stay off water, roads, named places, minor sites and each other: a lower tier gives way to every higher
 * one. XL sites that the pinned list names stand near their marquee landmark instead of on the grid.</p>
 */
public final class Landmarks {
    private static final long SALT = 0x4C414E444D41524BL;
    /** Largest probe radius for the road and water checks. */
    private static final float ROAD_LIMIT = 0.08f;

    private Landmarks() {}

    // ------------------------------------------------------------------ cache

    private static final class Cache {
        final long seed;
        @SuppressWarnings("unchecked")
        final ConcurrentHashMap<Long, Optional<LandmarkSite>>[] tiers = new ConcurrentHashMap[Tier.VALUES.length];

        Cache(long seed) {
            this.seed = seed;
            for (int i = 0; i < tiers.length; i++) tiers[i] = new ConcurrentHashMap<>();
        }
    }

    private static volatile Cache cache = new Cache(0);

    private static Cache cache(long seed) {
        Cache c = cache;
        if (c.seed != seed) {
            c = new Cache(seed);
            cache = c;
        }
        return c;
    }

    // ------------------------------------------------------------------ pinned

    /** One pinned XL entry resolved against the places index. */
    private record Pinned(String id, Kind kind, int x, int z, String name) {}

    private static volatile List<Pinned> pinnedList;

    private static List<Pinned> pinned() {
        List<Pinned> p = pinnedList;
        if (p != null) return p;
        synchronized (Landmarks.class) {
            if (pinnedList != null) return pinnedList;
            List<Pinned> out = new ArrayList<>();
            try (InputStream in = Landmarks.class.getResourceAsStream("/nirncraft/landmarks/pinned.json")) {
                if (in != null) {
                    JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement e : root.getAsJsonArray("pinned")) {
                        JsonObject o = e.getAsJsonObject();
                        Kind kind = Kinds.byId(o.get("kind").getAsString());
                        Optional<Place> anchor = Places.byId(o.get("place").getAsString());
                        if (kind == null || anchor.isEmpty()) {
                            NirnCraft.LOGGER.warn("NirnCraft landmarks: pinned entry {} skipped (kind or place missing)", o.get("id"));
                            continue;
                        }
                        double ang = Math.toRadians(o.get("angle").getAsDouble());
                        int d = o.get("dist").getAsInt();
                        int x = anchor.get().x() + (int) Math.round(Math.cos(ang) * d);
                        int z = anchor.get().z() + (int) Math.round(Math.sin(ang) * d);
                        out.add(new Pinned(o.get("id").getAsString(), kind, ((x >> 4) << 4) + 8, ((z >> 4) << 4) + 8,
                                o.has("name") ? o.get("name").getAsString() : ""));
                    }
                }
            } catch (Exception ex) {
                NirnCraft.LOGGER.warn("NirnCraft landmarks: could not read pinned.json", ex);
            }
            pinnedList = List.copyOf(out);
            return pinnedList;
        }
    }

    // ------------------------------------------------------------------ queries

    /** The site of a tier's cell, or null if the cell is empty. */
    @Nullable
    public static LandmarkSite at(long seed, Tier tier, int cellX, int cellZ) {
        Cache c = cache(seed);
        ConcurrentHashMap<Long, Optional<LandmarkSite>> map = c.tiers[tier.ordinal()];
        long key = ChunkPos.pack(cellX, cellZ);
        Optional<LandmarkSite> hit = map.get(key);
        if (hit != null) return hit.orElse(null);
        LandmarkSite site = compute(seed, tier, cellX, cellZ);
        if (map.size() > 16384) map.clear();
        map.put(key, Optional.ofNullable(site));
        return site;
    }

    /** The sites (at most one per tier) whose centre lies in this chunk. */
    public static List<LandmarkSite> inChunk(long seed, int chunkX, int chunkZ) {
        List<LandmarkSite> out = null;
        for (Tier t : Tier.VALUES) {
            int cx = Math.floorDiv(chunkX, t.cellChunks), cz = Math.floorDiv(chunkZ, t.cellChunks);
            LandmarkSite s = at(seed, t, cx, cz);
            if (s != null && s.chunkX() == chunkX && s.chunkZ() == chunkZ) {
                if (out == null) out = new ArrayList<>(2);
                out.add(s);
            }
        }
        return out == null ? List.of() : out;
    }

    /** Every site of every tier with its centre within {@code radius} of (x, z), nearest first. */
    public static List<LandmarkSite> within(long seed, int x, int z, int radius) {
        List<LandmarkSite> out = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (Tier t : Tier.VALUES) {
            int c0x = Math.floorDiv(x - radius, t.cell), c1x = Math.floorDiv(x + radius, t.cell);
            int c0z = Math.floorDiv(z - radius, t.cell), c1z = Math.floorDiv(z + radius, t.cell);
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    LandmarkSite s = at(seed, t, cx, cz);
                    if (s != null && s.distSqr(x, z) <= r2) out.add(s);
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.distSqr(x, z), b.distSqr(x, z)));
        return out;
    }

    // ------------------------------------------------------------------ placement

    @Nullable
    private static LandmarkSite compute(long seed, Tier tier, int cx, int cz) {
        if (tier == Tier.XL) {
            for (Pinned p : pinned()) {
                if (Math.floorDiv(p.x, tier.cell) == cx && Math.floorDiv(p.z, tier.cell) == cz) {
                    return computePinned(seed, p, cx, cz);
                }
            }
        }
        long h = H.mix(seed ^ H.mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + SALT + tier.ordinal() * 0x632BE59BD9B4E019L));
        int m = tier.radiusCap / 16 + 1;
        int span = tier.cellChunks - 2 * m;
        int chunkX = cx * tier.cellChunks + m + H.range(h, 1, span);
        int chunkZ = cz * tier.cellChunks + m + H.range(h, 2, span);
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        if (H.unit(h, 3) >= tier.occupancy * ExploreConfig.densityScale()) return null;
        LandCtx ctx = LandCtx.of(x, z);
        if (ctx.province.equals("sea") || ctx.s.inWater() || ctx.s.coastDistance < 3) return null;
        Kind kind = Kinds.choose(tier, ctx, H.unit(h, 4));
        if (kind == null) return null;
        // human vignettes (graves, packs, camps, ambushes...) are planned scenes now (P6): their cell stays empty
        if (com.nirncraft.worldplan.scene.SceneKinds.planned(kind.builder())) return null;
        if (!clear(seed, tier, kind, x, z)) return null;
        if (tier == Tier.XL) {
            for (Pinned p : pinned()) {
                long dx = p.x - x, dz = p.z - z;
                if (dx * dx + dz * dz < 400L * 400L) return null;
            }
        }
        return make(tier, cx, cz, x, z, kind, h, ctx, null, "");
    }

    @Nullable
    private static LandmarkSite computePinned(long seed, Pinned p, int cx, int cz) {
        Tier tier = Tier.XL;
        long h = H.mix(seed ^ H.mix(p.id.hashCode() * 0x9E3779B97F4A7C15L + SALT));
        // nudge off water or places: a ring search inside the cell, 24-block steps
        MapSample s = new MapSample();
        int minX = cx * tier.cell + 40, maxX = cx * tier.cell + tier.cell - 41, minZ = cz * tier.cell + 40, maxZ = cz * tier.cell + tier.cell - 41;
        for (int d = 0; d <= 240; d += 24) {
            int steps = d == 0 ? 1 : 6 + d / 12;
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI * 2 / steps + (p.id.hashCode() & 7);
                int px = ((p.x + (int) Math.round(Math.cos(ang) * d)) >> 4 << 4) + 8;
                int pz = ((p.z + (int) Math.round(Math.sin(ang) * d)) >> 4 << 4) + 8;
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) continue;
                TamrielMap.sample(px, pz, s);
                if (s.inWater() || s.coastDistance < 3) continue;
                if (!clear(seed, tier, p.kind, px, pz)) continue;
                LandCtx ctx = LandCtx.of(px, pz);
                if (ctx.province.equals("sea")) continue;
                return make(tier, cx, cz, px, pz, p.kind, h, ctx, p.id, p.name);
            }
        }
        return null;
    }

    private static LandmarkSite make(Tier tier, int cx, int cz, int x, int z, Kind kind, long h, LandCtx ctx,
                                     @Nullable String pinnedId, String fixedName) {
        long sh = H.mix(h ^ (kind.id().hashCode() * 0x632BE59BD9B4E019L));
        String name = fixedName;
        if (name.isEmpty() && !kind.noun().isEmpty()) {
            name = Lore.placeName(ctx.province, kind.noun(), RandomSource.create(sh ^ 0x5DEECE66DL));
        }
        return new LandmarkSite(tier, cx, cz, x, z, kind, sh, H.range(h, 7, 4), ctx.region.id(), ctx.province, ctx.base, name, pinnedId);
    }

    /**
     * Cheap rejection tests: named places, minor sites, higher tiers, water around the footprint, roads, steep
     * macro slope. Nothing here loads a chunk.
     */
    private static boolean clear(long seed, Tier tier, Kind kind, int x, int z) {
        int r = kind.radius();
        int m = r + tier.placeMargin;
        if (!Places.overlapping(x - m, z - m, x + m, z + m).isEmpty()) return false;
        MapSample p = new MapSample();
        TamrielMap.sample(x, z, p);
        if (p.road > ROAD_LIMIT) return false;
        if (p.slope > (tier == Tier.S || tier == Tier.M ? 0.9f : 1.6f)) return false;
        boolean coastal = kind.needs().contains("coast");
        for (int d : new int[]{r / 2, r}) {
            if (d <= 0) continue;
            for (int[] o : OFFSETS) {
                TamrielMap.sample(x + o[0] * d, z + o[1] * d, p);
                if (p.inWater() || p.coastDistance < (coastal ? 0 : 1)) return false;
                if (p.road > ROAD_LIMIT) return false;
            }
        }
        // minor sites of the explore module
        for (MinorSites.Site s : MinorSites.within(seed, x, z, r + 26)) {
            if (s.distSqr(x, z) < (long) (r + s.kind().radius + 6) * (r + s.kind().radius + 6)) return false;
        }
        // a lower tier gives way to every higher one
        for (Tier t : Tier.VALUES) {
            if (t.ordinal() <= tier.ordinal()) continue;
            int reach = r + t.radiusCap + 10;
            int c0x = Math.floorDiv(x - reach, t.cell), c1x = Math.floorDiv(x + reach, t.cell);
            int c0z = Math.floorDiv(z - reach, t.cell), c1z = Math.floorDiv(z + reach, t.cell);
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    LandmarkSite o = at(seed, t, cx, cz);
                    if (o == null) continue;
                    int need = r + o.kind().radius() + 10;
                    if (o.distSqr(x, z) < (long) need * need) return false;
                }
            }
        }
        return true;
    }

    private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    /** A site of a chosen kind at (x, z) snapped to its chunk centre, ignoring the grid and every placement rule. */
    public static LandmarkSite custom(long seed, Kind kind, int x, int z, long salt) {
        int cx = ((x >> 4) << 4) + 8, cz = ((z >> 4) << 4) + 8;
        LandCtx ctx = LandCtx.of(cx, cz);
        long h = H.mix(seed ^ H.mix(salt * 0x9E3779B97F4A7C15L + cx * 31L + cz * 17L));
        return make(kind.tier(), Math.floorDiv(cx, kind.tier().cell), Math.floorDiv(cz, kind.tier().cell), cx, cz, kind, h, ctx, null, "");
    }

    /**
     * A key that is the same for two sites exactly when they look alike: kind, rock set, wood, and coarse buckets of
     * the seeded size and weathering. Used to measure repetition.
     */
    public static String variantKey(LandmarkSite s) {
        LandCtx c = LandCtx.of(s.x(), s.z());
        String rock = s.kind().params().has("rock") ? s.kind().params().get("rock").getAsString()
                : Mats.rockFor(c.families(), H.mix(s.seed() ^ 0x51L)).id();
        String wood = Mats.woodFor(c.families(), H.mix(s.seed() ^ 0x77L)).id();
        int size = (int) Math.round(H.between(s.seed(), 11, 0.75, 1.3) * 4);
        int wear = (int) (H.between(s.seed(), 12, 0.0, 1.0) * 3);
        return s.kind().id() + "|" + rock + "|" + wood + "|" + size + "|" + wear;
    }

    // ------------------------------------------------------------------ audit helpers

    /** Sites of every tier inside the rectangle [x0, x1] x [z0, z1] (block coordinates). */
    public static List<LandmarkSite> inRect(long seed, int x0, int z0, int x1, int z1) {
        List<LandmarkSite> out = new ArrayList<>();
        for (Tier t : Tier.VALUES) {
            for (int cx = Math.floorDiv(x0, t.cell); cx <= Math.floorDiv(x1, t.cell); cx++) {
                for (int cz = Math.floorDiv(z0, t.cell); cz <= Math.floorDiv(z1, t.cell); cz++) {
                    LandmarkSite s = at(seed, t, cx, cz);
                    if (s != null && s.x() >= x0 && s.x() <= x1 && s.z() >= z0 && s.z() <= z1) out.add(s);
                }
            }
        }
        return out;
    }

    /** All pinned entries as sites (for listing), resolved against the given seed. */
    public static List<LandmarkSite> pinnedSites(long seed) {
        List<LandmarkSite> out = new ArrayList<>();
        for (Pinned p : pinned()) {
            LandmarkSite s = at(seed, Tier.XL, Math.floorDiv(p.x, Tier.XL.cell), Math.floorDiv(p.z, Tier.XL.cell));
            if (s != null && p.id.equals(s.pinned())) out.add(s);
        }
        return out;
    }

    public static Map<String, Integer> countsByKind(List<LandmarkSite> sites) {
        Map<String, Integer> m = new HashMap<>();
        for (LandmarkSite s : sites) m.merge(s.kind().id(), 1, Integer::sum);
        return m;
    }
}

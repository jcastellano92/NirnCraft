package com.skycraft.aether;

// aether:sandbox-only (compiled against the real mod by sandbox_check.ps1, not by compile_check.ps1's stubs)

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.SettlementPiece;
import com.skycraft.tamriel.settlement.build.Builders;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.LandmarkRegistry;
import com.skycraft.tamriel.settlement.build.Landmarks;
import com.skycraft.tamriel.settlement.grammar.Blueprint;
import com.skycraft.tamriel.settlement.grammar.Outdoors;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.tamriel.settlement.plan.Spec;
import com.skycraft.worldplan.street.StreetPlan;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/**
 * Far-field buildings: Skycraft's planned settlements (StreetPlan: every named capital, city, town, village and farm,
 * baked and seed-free, so clients draw them themselves) and the named wild sites (AetherSites: forts, towers, shrines,
 * wonders, dungeon entrances) rasterised into aether's per-tile structure layers. Grammar buildings become their
 * blueprint's parts with wall top, roof rise and roof shape; city walls lines from floor to floor2; gates and towers
 * boxes. Landmarks (the White-Gold Tower, the College on its sea stack, Elden Root's tree ...) are captured from the
 * mod's own landmark builders (AetherCapture: the exact silhouette, spans floating where they do, e.g. an arch's deck),
 * falling back to a profile box if a builder cannot run outside a world. Colours are the blocks' far colours
 * (AetherMaterials). Detail below a column's size is sampled away (houses fade with distance), but walls stay at least
 * one column thick and silhouettes are max-pooled, so a city's outline and its towers carry to the horizon.
 *
 * <p>Lights: every settlement also uploads its lights as one aether light group (night windows of its buildings,
 * street lamps and braziers, wall lanterns, gate torches, camp fires, and the light blocks of captured landmarks), and
 * every wild site its own (lighthouse lamps, shrine braziers, the Red Mountain moat), for the far field's night.</p>
 */
final class AetherSettlements implements AetherFarField.Overlay {
    private static final int N = AetherNative.TILE * AetherNative.TILE;
    /** Light groups: settlement i is 1 + i; the wild sites of a 4 km cell are SITE_GROUPS + a cell hash. */
    static final int SITE_GROUPS = 0x100000;

    enum Roof { FLAT, GABLE_X, GABLE_Z, HIP, DOME, SPIRE }

    /** One building part or box in world blocks: [x0, x1] x [z0, z1], walls to wallTop, roof rising `rise` above. */
    record Mass(int x0, int z0, int x1, int z1, float wallTop, float rise, Roof roof, short wall, short roofMat) {}

    /** A wall segment: centre line (ax, az) - (bx, bz), half width, top interpolated from topA to topB. */
    record Line(float ax, float az, float bx, float bz, float half, float topA, float topB, short mat) {}

    private final StreetPlan plan;
    private final IntBinaryOperator ground;  // block x, z -> terrain height (null: aether's stand-in)
    private final AetherMaterials mats;
    private final Map<Integer, List<Object>> bySettlement = new HashMap<>();  // settlement index -> masses, lines, shapes
    private final Map<Integer, List<AetherLights.Light>> settlementLights = new HashMap<>();
    private final Map<String, Optional<AetherCapture.Shape>> sites = new HashMap<>();
    private final Map<Integer, AetherCapture.Shape> falls = new HashMap<>();
    /** Natural landmarks from the server (AetherNatural), by "x,z,kind": their silhouettes, built once. */
    private final Map<String, Optional<AetherCapture.Shape>> natural = new HashMap<>();
    private AetherNet.FarSites sitesSeen;
    /** Supplies the server's natural-landmark list (AetherClient.farSites in game; the harness sets its own). */
    static volatile java.util.function.Supplier<AetherNet.FarSites> farSites = () -> AetherClient.farSites;
    private final Set<Long> siteCells = new HashSet<>();
    private final Set<Long> uploaded = new HashSet<>();
    private final float[] wallTop = new float[N], roofTop = new float[N], baseArr = new float[N];
    private final short[] wallMat = new short[N], roofMat = new short[N];
    private boolean floating;
    private IntBinaryOperator g;
    private static volatile boolean captureWarned;
    /** Landmarks from their own builders (off: the old profile boxes, for comparison in the harness). */
    static final boolean CAPTURE = !Boolean.getBoolean("skycraft.aether.noCapture");
    /** Harness counters: landmarks captured from their builders / drawn as profiles, wild site silhouettes, lights. */
    int captured, profiled, siteShapes, lightCount;
    /** Harness timings (ms): landmark captures, wild site silhouettes, settlement massing in all. */
    double captureMs, siteMs, massingMs;
    /** Harness: why landmarks fell back to profiles (first few). */
    final List<String> fallbacks = new ArrayList<>();

    AetherSettlements(StreetPlan plan, IntBinaryOperator ground, AetherMaterials mats) {
        this.plan = plan;
        this.ground = ground;
        this.mats = mats;
    }

    /**
     * Over the mod's street plan (loads or plans it on first use: call off the render thread). ground: the terrain
     * height forts stand on (the exact one in single player), or null for aether's raster stand-in.
     */
    static AetherSettlements create(IntBinaryOperator ground, AetherMaterials mats) {
        return new AetherSettlements(StreetPlan.get(), ground, mats);
    }

    /**
     * Buildings for every resident tile of LOD `lod` in the window that has not had them yet (tiles evicted since are
     * redone when they come back), and the light groups of the settlements and sites they touch. Far-field worker.
     */
    @Override
    public void cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
        int span = AetherNative.TILE << lod;
        g = ground != null ? ground : (x, z) -> (int) Math.floor(ctx.previewHeight(x + 0.5, z + 0.5));
        refreshNatural();
        for (int tz = Math.floorDiv(z0, span); tz <= Math.floorDiv(z1 - 1, span); tz++)
            for (int tx = Math.floorDiv(x0, span); tx <= Math.floorDiv(x1 - 1, span); tx++) {
                long k = AetherRefiningSource.key(lod, tx, tz);
                if (!ctx.hasTile(lod, tx, tz)) {
                    uploaded.remove(k);  // evicted (the buildings went with it): again when it is back
                    continue;
                }
                if (!uploaded.add(k)) continue;
                boolean any = fill(lod, tx, tz);
                mats.flush(ctx);
                flushLights(ctx);
                if (any) ctx.uploadColumnStructures(lod, tx, tz, wallTop, roofTop, wallMat, roofMat, floating ? baseArr : null);
            }
    }

    private final List<Integer> lightsPending = new ArrayList<>();
    private final List<Long> siteLightsPending = new ArrayList<>();
    private final Map<Long, List<AetherLights.Light>> siteLights = new HashMap<>();

    private void flushLights(AetherNative.Context ctx) {
        for (int i : lightsPending) {
            AetherLights.upload(ctx, 1 + i, settlementLights.get(i));
            lightCount += settlementLights.get(i).size();
        }
        lightsPending.clear();
        for (long cell : siteLightsPending) {
            AetherLights.upload(ctx, SITE_GROUPS + (int) (cell * 0x9E3779B97F4A7C15L >>> 44), siteLights.get(cell));
            lightCount += siteLights.get(cell).size();
        }
        siteLightsPending.clear();
    }

    /** Rasterises tile (lod, tx, tz) into the arrays; false when no settlement or site reaches it. */
    boolean fill(int lod, int tx, int tz) {
        int s = 1 << lod, bx = tx * (AetherNative.TILE << lod), bz = tz * (AetherNative.TILE << lod);
        int ex = bx + (AetherNative.TILE << lod), ez = bz + (AetherNative.TILE << lod);
        boolean any = false;
        for (int i = 0; i < plan.settlements(); i++) {
            int r = plan.sReach[i] + 64;
            if (plan.sX[i] + r < bx || plan.sX[i] - r >= ex || plan.sZ[i] + r < bz || plan.sZ[i] - r >= ez) continue;
            if (!any) clear();
            any = true;
            for (Object o : pieces(i)) stamp(o, s, bx, bz);
        }
        for (Place p : Places.overlapping(bx - 64, bz - 64, ex + 64, ez + 64)) {
            if (p.type().isSettlement()) continue;
            AetherCapture.Shape sh = site(p);
            if (sh == null) continue;
            if (!any) clear();
            any = true;
            stamp(sh, s, bx, bz);
        }
        AetherNet.FarSites ns = sitesSeen;
        for (int k = 0; ns != null && k < ns.count(); k++) {
            int nx = ns.xz()[k * 2], nz = ns.xz()[k * 2 + 1];
            if (nx < bx - 32 || nx >= ex + 32 || nz < bz - 32 || nz >= ez + 32) continue;
            AetherCapture.Shape sh = naturalShape(ns, k);
            if (sh == null) continue;
            if (!any) clear();
            any = true;
            stamp(sh, s, bx, bz);
        }
        com.skycraft.tamriel.terrain.Hydrology.Fall[] all = com.skycraft.tamriel.terrain.Hydrology.get().falls();
        for (int k = 0; k < all.length; k++) {
            com.skycraft.tamriel.terrain.Hydrology.Fall f = all[k];
            if (f.x() < bx - 32 || f.x() >= ex + 32 || f.z() < bz - 32 || f.z() >= ez + 32) continue;
            if (!any) clear();
            any = true;
            stamp(fall(k, f), s, bx, bz);
        }
        return any;
    }

    private AetherCapture.Shape naturalShape(AetherNet.FarSites ns, int k) {
        String key = ns.xz()[k * 2] + "," + ns.xz()[k * 2 + 1] + "," + ns.kinds()[k];
        return natural.computeIfAbsent(key, q -> {
            try {
                return Optional.ofNullable(AetherNatural.shape(ns, k, g));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * A new list of natural landmarks from the server: the resident tiles that a site new to the list touches are
     * stamped again (their buildings re-uploaded: the refinement fade dissolves the landmark in).
     */
    private void refreshNatural() {
        AetherNet.FarSites now = farSites.get();
        if (now == null || now == sitesSeen) return;
        java.util.Set<String> before = new HashSet<>();
        AetherNet.FarSites old = sitesSeen;
        for (int k = 0; old != null && k < old.count(); k++) before.add(old.xz()[k * 2] + "," + old.xz()[k * 2 + 1]);
        sitesSeen = now;
        for (int k = 0; k < now.count(); k++) {
            int nx = now.xz()[k * 2], nz = now.xz()[k * 2 + 1];
            if (before.contains(nx + "," + nz)) continue;
            for (int lod = 0; lod < AetherFarField.LODS; lod++) {
                int span = AetherNative.TILE << lod;
                for (int tz = Math.floorDiv(nz - 32, span); tz <= Math.floorDiv(nz + 32, span); tz++)
                    for (int tx = Math.floorDiv(nx - 32, span); tx <= Math.floorDiv(nx + 32, span); tx++)
                        uploaded.remove(AetherRefiningSource.key(lod, tx, tz));
            }
        }
    }

    /** The falls sheet: three blocks beyond the lip (DungeonStructure's, Hydrology's), across the channel, from the pool bed up to the upper water; its spray. */
    private AetherCapture.Shape fall(int k, com.skycraft.tamriel.terrain.Hydrology.Fall f) {
        return falls.computeIfAbsent(k, i -> {
            int r = (int) Math.ceil(f.hw()) + 4;
            AetherCapture.Shape sh = AetherCapture.Shape.around(f.x(), f.z(), r);
            BlockState sheet = net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN.defaultBlockState();
            for (float t = -f.hw(); t <= f.hw(); t += 0.25f)
                for (float along = 0.5f; along <= 1.5f; along += 0.5f) {
                    int x = (int) Math.floor(f.x() + 0.5 + f.ux() * along - f.uz() * t);
                    int z = (int) Math.floor(f.z() + 0.5 + f.uz() * along + f.ux() * t);
                    sh.span(x, z, Float.NaN, f.up(), sheet, sheet);
                }
            sh.rise = f.drop();
            float drop = f.drop();
            long cell = (long) Math.floorDiv(f.x(), 4096) << 32 | (Math.floorDiv(f.z(), 4096) & 0xFFFFFFFFL);
            siteLights.computeIfAbsent(cell, c -> new ArrayList<>()).add(AetherLights.mist(f.x() + 0.5 + f.ux() * 3,
                    f.down() + 1.5 + drop * 0.15, f.z() + 0.5 + f.uz() * 3, 2f + f.hw() + drop * 0.35f,
                    (0.12f + drop / 45f) * (f.big() ? 1.5f : 1f)));
            if (!siteLightsPending.contains(cell)) siteLightsPending.add(cell);
            return sh;
        });
    }

    private void clear() {
        Arrays.fill(wallTop, Float.NaN);
        Arrays.fill(roofTop, Float.NaN);
        Arrays.fill(baseArr, Float.NaN);
        Arrays.fill(wallMat, (short) 0);
        Arrays.fill(roofMat, (short) 0);
        floating = false;
    }

    /** A wild site's silhouette, built once; its lights join its 4 km cell's light group. */
    private AetherCapture.Shape site(Place p) {
        Optional<AetherCapture.Shape> v = sites.get(p.id());
        if (v == null) {
            AetherCapture.Shape sh = null;
            long t0 = System.nanoTime();
            try {
                sh = AetherSites.shape(p, g);
            } catch (RuntimeException e) {
                // one odd site must not cost the far field
            }
            siteMs += (System.nanoTime() - t0) / 1e6;
            v = Optional.ofNullable(sh);
            sites.put(p.id(), v);
            if (sh != null) siteShapes++;
            if (sh != null && !sh.lights.isEmpty()) {
                long cell = (long) Math.floorDiv(p.x(), 4096) << 32 | (Math.floorDiv(p.z(), 4096) & 0xFFFFFFFFL);
                List<AetherLights.Light> l = siteLights.computeIfAbsent(cell, k -> new ArrayList<>());
                boolean volcano = p.id().equals("red_mountain") || p.id().equals("dagoth_ur");
                for (int[] q : sh.lights)
                    l.add(volcano && q[3] >= 15 ? AetherLights.lava(q[0] + 0.5, q[1] + 0.5, q[2] + 0.5, 4f)
                            : AetherLights.fire(q[0] + 0.5, q[1] + 0.5, q[2] + 0.5, q[3] / 15f));
                if (p.id().equals("red_mountain")) {
                    // the caldera's glow: the crater floor's lava lighting the ash and smoke over it, so it reads above
                    // the rim from anywhere on Vvardenfell (the rim hides the floor itself from below)
                    double fx = sh.x0 + sh.w * 0.5, fz = sh.z0 + sh.d * 0.5, floorY = sh.lights.get(0)[1] + 1.0;
                    l.add(new AetherLights.Light(fx, floorY + 2, fz, 40f, 3f, AetherLights.LAVA_RGB, AetherLights.LAVA));
                    l.add(new AetherLights.Light(fx, floorY + 85, fz, 110f, 0.55f, AetherLights.LAVA_RGB, AetherLights.LAVA));
                }
                if (!siteLightsPending.contains(cell)) siteLightsPending.add(cell);
            }
        }
        return v.orElse(null);
    }

    private void stamp(Object o, int s, int bx, int bz) {
        if (o instanceof Mass m) stamp(m, s, bx, bz);
        else if (o instanceof Line l) stamp(l, s, bx, bz);
        else stamp((AetherCapture.Shape) o, s, bx, bz);
    }

    private void stamp(Mass m, int s, int bx, int bz) {
        // columns whose centre lies in the footprint
        int i0 = Math.max(0, ceilDiv(m.x0 - bx - s / 2, s)), i1 = Math.min(AetherNative.TILE - 1, Math.floorDiv(m.x1 - bx - s / 2, s));
        int j0 = Math.max(0, ceilDiv(m.z0 - bz - s / 2, s)), j1 = Math.min(AetherNative.TILE - 1, Math.floorDiv(m.z1 - bz - s / 2, s));
        if (i0 > i1 || j0 > j1) {
            // smaller than a column: only big pieces (towers, keeps, landmarks) keep their column
            if (m.x1 - m.x0 + 1 < 9 && m.z1 - m.z0 + 1 < 9) return;
            int ci = Math.floorDiv((m.x0 + m.x1) / 2 - bx, s), cj = Math.floorDiv((m.z0 + m.z1) / 2 - bz, s);
            if (ci < 0 || cj < 0 || ci >= AetherNative.TILE || cj >= AetherNative.TILE) return;
            i0 = i1 = ci;
            j0 = j1 = cj;
        }
        float cx = (m.x0 + m.x1 + 1) * 0.5f, cz = (m.z0 + m.z1 + 1) * 0.5f;
        float hx = (m.x1 - m.x0 + 1) * 0.5f, hz = (m.z1 - m.z0 + 1) * 0.5f;
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                float wx = bx + (i + 0.5f) * s, wz = bz + (j + 0.5f) * s;
                float ux = Math.min(1f, Math.abs(wx - cx) / hx), uz = Math.min(1f, Math.abs(wz - cz) / hz);
                float up = switch (m.roof) {
                    case FLAT -> 1f;
                    case GABLE_X -> 1f - uz;  // ridge along x
                    case GABLE_Z -> 1f - ux;
                    case HIP -> 1f - Math.max(ux, uz);
                    case DOME -> (float) Math.sqrt(Math.max(0f, 1f - ux * ux - uz * uz));
                    case SPIRE -> Math.max(0f, 1f - (float) Math.sqrt(ux * ux + uz * uz));
                };
                put(i, j, m.wallTop, m.wallTop + m.rise * up, m.wall, m.roofMat, Float.NaN);
            }
    }

    private void stamp(Line l, int s, int bx, int bz) {
        float half = Math.max(l.half, s * 0.5f);  // at least one column thick at every LOD
        float minX = Math.min(l.ax, l.bx) - half, maxX = Math.max(l.ax, l.bx) + half;
        float minZ = Math.min(l.az, l.bz) - half, maxZ = Math.max(l.az, l.bz) + half;
        int i0 = Math.max(0, (int) Math.floor((minX - bx) / s)), i1 = Math.min(AetherNative.TILE - 1, (int) Math.floor((maxX - bx) / s));
        int j0 = Math.max(0, (int) Math.floor((minZ - bz) / s)), j1 = Math.min(AetherNative.TILE - 1, (int) Math.floor((maxZ - bz) / s));
        float dx = l.bx - l.ax, dz = l.bz - l.az, len2 = Math.max(1e-6f, dx * dx + dz * dz);
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                float wx = bx + (i + 0.5f) * s, wz = bz + (j + 0.5f) * s;
                float t = Math.max(0f, Math.min(1f, ((wx - l.ax) * dx + (wz - l.az) * dz) / len2));
                float px = l.ax + dx * t - wx, pz = l.az + dz * t - wz;
                if (px * px + pz * pz > half * half) continue;
                float top = l.topA + (l.topB - l.topA) * t;
                put(i, j, top, top, l.mat, l.mat, Float.NaN);
            }
    }

    /**
     * A captured or parametric silhouette, max-pooled into each column it touches: the tallest span in the column
     * wins (a spire narrower than a column keeps its column, so it carries to the horizon), grounded if any of the
     * column's spans stands on the ground. Shapes that would be a bump lower than half a column are skipped.
     */
    private void stamp(AetherCapture.Shape sh, int s, int bx, int bz) {
        if (sh.rise < s * 0.5f && Math.max(sh.w, sh.d) < s) return;
        int i0 = Math.max(0, Math.floorDiv(sh.x0 - bx, s)), i1 = Math.min(AetherNative.TILE - 1, Math.floorDiv(sh.x0 + sh.w - 1 - bx, s));
        int j0 = Math.max(0, Math.floorDiv(sh.z0 - bz, s)), j1 = Math.min(AetherNative.TILE - 1, Math.floorDiv(sh.z0 + sh.d - 1 - bz, s));
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                int cx0 = Math.max(bx + i * s, sh.x0), cx1 = Math.min(bx + (i + 1) * s, sh.x0 + sh.w);
                int cz0 = Math.max(bz + j * s, sh.z0), cz1 = Math.min(bz + (j + 1) * s, sh.z0 + sh.d);
                int best = -1;
                boolean grounded = false;
                float lowest = Float.POSITIVE_INFINITY;
                for (int z = cz0; z < cz1; z++)
                    for (int x = cx0; x < cx1; x++) {
                        int c = (z - sh.z0) * sh.w + (x - sh.x0);
                        float t = sh.top[c];
                        if (Float.isNaN(t)) continue;
                        if (best < 0 || t > sh.top[best]) best = c;
                        if (Float.isNaN(sh.base[c])) grounded = true;
                        else lowest = Math.min(lowest, sh.base[c]);
                    }
                if (best < 0) continue;
                float top = sh.top[best];
                float base = grounded ? Float.NaN : lowest;
                put(i, j, top - 1, top, mats.id(sh.wall[best]), mats.id(sh.roof[best]), base);
            }
    }

    private void put(int i, int j, float wTop, float rTop, short wMat, short rMat, float base) {
        int c = j * AetherNative.TILE + i;
        if (!(rTop > roofTop[c]) && !Float.isNaN(roofTop[c])) return;  // the taller piece wins
        wallTop[c] = wTop;
        roofTop[c] = rTop;
        wallMat[c] = wMat;
        roofMat[c] = rMat;
        baseArr[c] = base;
        floating |= !Float.isNaN(base);
    }

    /** Massing of settlement i, built once (and its light group queued). */
    private List<Object> pieces(int i) {
        return bySettlement.computeIfAbsent(i, k -> {
            long t0 = System.nanoTime();
            List<Object> out = new ArrayList<>();
            List<AetherLights.Light> lights = new ArrayList<>();
            String id = plan.sId[k];
            Place place = Places.byId(id).orElse(null);
            long seed = place != null ? Cities.seed(place) : 0L;
            List<Spec> layout = plan.layout(id);
            for (int n = 0; n < layout.size(); n++) {
                Spec sp = layout.get(n);
                try {
                    piece(sp, out, lights, id, seed ^ (n * 0x9E3779B97F4A7C15L));
                } catch (RuntimeException e) {
                    // one odd piece must not cost the town: skip it
                }
            }
            settlementLights.put(k, lights);
            lightsPending.add(k);
            massingMs += (System.nanoTime() - t0) / 1e6;
            return out;
        });
    }

    private void piece(Spec s, List<Object> out, List<AetherLights.Light> lights, String settlement, long pieceSeed) {
        if (s.has(Spec.OMIT) || s.floor == Spec.UNSET || s.kind == null) return;
        Archetype k = s.kind;
        float floor = s.floor + 1;  // floor is the y of the floor block: walls stand on top of it
        lightsOf(s, floor, lights);
        if (k == Archetype.WALL) {
            Palette p = palette(s);
            float f2 = (s.floor2 == Spec.UNSET ? s.floor : s.floor2) + 1;
            BlockState b = s.has(Spec.PALISADE) ? p.post : p.cityWall;
            out.add(new Line(s.x + 0.5f, s.z + 0.5f, s.x2 + 0.5f, s.z2 + 0.5f, Math.max(1, s.w) * 0.5f, floor + s.h, f2 + s.h, mats.id(b)));
            return;
        }
        if (k == Archetype.GATE) {
            Palette p = palette(s);
            box(s, out, floor + s.h, 0, Roof.FLAT, p.cityWall, p.cityWall);
            return;
        }
        if (k == Archetype.LANDMARK) {
            long t0 = System.nanoTime();
            Optional<AetherCapture.Shape> cap = CAPTURE ? captureLandmark(s, settlement, pieceSeed) : null;
            captureMs += (System.nanoTime() - t0) / 1e6;
            if (cap != null) {
                captured++;
                // nothing above the ground: a rock or sea stack the terrain itself already shapes (the plan's pads)
                if (cap.isEmpty()) return;
                AetherCapture.Shape sh = cap.get();
                out.add(sh);
                for (int[] q : sh.lights) lights.add(AetherLights.lamp(q[0] + 0.5, q[1] + 0.5, q[2] + 0.5, q[3] / 15f));
                return;
            }
            profiled++;
            LandmarkRegistry.Def d = LandmarkRegistry.ofVariant(s.variant);
            float h = s.h > 0 ? s.h : d != null ? d.h() : 20;
            Palette p = palette(s);
            int w = Math.max(s.w, d != null ? d.w() : s.w), dd = Math.max(s.d, d != null ? d.d() : s.d);
            // the rotated footprint (a south-facing 55 x 25 cliff runs along x, an east-facing one along z)
            boolean turned = s.facing.getAxis() == Direction.Axis.X;
            int fw = turned ? dd : w, fd = turned ? w : dd;
            int x0 = s.x - fw / 2, z0 = s.z - fd / 2;
            boolean spire = h > 3 * Math.max(w, dd);
            float rise = spire ? Math.max(w, dd) * 1.5f : Math.min(w, dd) * 0.25f;
            out.add(new Mass(x0, z0, x0 + fw - 1, z0 + fd - 1, floor + h - rise, rise, spire ? Roof.SPIRE : Roof.DOME,
                    mats.id(p.cityWall), mats.id(p.roofFull)));
            return;
        }
        if (!k.building) return;  // streets, plazas, fields, docks, stalls ...: ground detail
        if (s.grammar()) {
            Blueprint bp = Blueprint.of(s);
            float wall = floor + bp.wallTop() + 1;
            windows(s, bp, floor, lights);
            if (bp.body != Blueprint.Body.RECT) {
                box(s, out, floor + bp.height() - 4, 0, Roof.DOME, bp.pal.wall, bp.pal.roofFull);
                return;
            }
            int[] o = SettlementPiece.origin(s);
            for (Blueprint.Part part : bp.parts) {
                int ax = o[0] + mapX(s.facing, part.x0(), part.z0()), az = o[1] + mapZ(s.facing, part.x0(), part.z0());
                int cx = o[0] + mapX(s.facing, part.x1(), part.z1()), cz = o[1] + mapZ(s.facing, part.x1(), part.z1());
                // a ridge along the part's local z runs along world z when the building faces north or south
                boolean alongWorldZ = part.alongZ() == (s.facing.getAxis() == Direction.Axis.Z);
                Roof roof = switch (bp.roof) {
                    case FLAT, CRENEL -> Roof.FLAT;
                    case DOME -> Roof.DOME;
                    case SPIRE -> Roof.SPIRE;
                    case HIP -> Roof.HIP;
                    default -> alongWorldZ ? Roof.GABLE_Z : Roof.GABLE_X;
                };
                out.add(new Mass(Math.min(ax, cx), Math.min(az, cz), Math.max(ax, cx), Math.max(az, cz), wall, bp.rise(part),
                        roof, mats.id(bp.pal.wall), mats.id(bp.pal.roofFull)));
            }
            return;
        }
        // the older archetype builders: their height, two thirds walls
        Palette p = palette(s);
        float h = Builders.height(s) - 4;
        boolean tower = k == Archetype.TOWER;
        box(s, out, floor + (tower ? h : h * 0.62f), tower ? 2 : h * 0.38f, tower ? Roof.FLAT : Roof.HIP,
                tower ? p.cityWall : p.wall, p.roofFull);
        windows(s, null, floor, lights);
    }

    /**
     * The exact silhouette of a settlement landmark: Landmarks.build run on a canvas over a capturing level, framed
     * exactly as SettlementPiece frames it (origin, facing, the piece's seed). Empty when it builds nothing above the
     * ground; null when the builder cannot run here (then a profile stands in).
     */
    private Optional<AetherCapture.Shape> captureLandmark(Spec s, String settlement, long seed) {
        try {
            BoundingBox piece = SettlementPiece.box(s);
            int h = Math.max(s.h, Builders.height(s));
            int x0 = piece.minX(), z0 = piece.minZ(), x1 = piece.maxX(), z1 = piece.maxZ(), top = s.floor + h + 60;
            String[] arch = s.variant == Landmarks.ARCH ? s.name.split(",") : new String[0];
            if (arch.length >= 6) {
                // an arch spans between two world points of its spec's name ("sx,sz,ex,ez,top,width")
                int ax = Integer.parseInt(arch[0]), az = Integer.parseInt(arch[1]), bx = Integer.parseInt(arch[2]), bz = Integer.parseInt(arch[3]);
                int w = Integer.parseInt(arch[5]) / 2 + 6;
                x0 = Math.min(ax, bx) - w;
                z0 = Math.min(az, bz) - w;
                x1 = Math.max(ax, bx) + w;
                z1 = Math.max(az, bz) + w;
                top = s.floor + (int) (Integer.parseInt(arch[4]) * 1.7) + 20;
            }
            BoundingBox box = new BoundingBox(x0 - 12, Math.max(-63, s.floor - 40), z0 - 12, x1 + 12, Math.min(638, top), z1 + 12);
            Palette p = palette(s);
            int[] o = s.isLine() ? new int[] {0, 0} : SettlementPiece.origin(s);
            Direction f = s.isLine() ? Direction.NORTH : s.facing;
            AetherCapture.Shape sh = AetherCapture.capture(box, g, seed, level -> {
                Canvas c = new Canvas(level, box, o[0], s.floor, o[1], f, seed);
                Landmarks.build(c, p, s, settlement);
            });
            return Optional.ofNullable(sh);
        } catch (RuntimeException | LinkageError e) {
            if (fallbacks.size() < 12) fallbacks.add(settlement + " variant " + s.variant + ": " + e);
            if (!captureWarned) {
                captureWarned = true;
                com.skycraft.Skycraft.LOGGER.info("aether: landmark capture fell back to profiles ({})", e.toString());
            }
            return null;
        }
    }

    // ---- lights ----------------------------------------------------------------------------------------------

    /** Street lamps, braziers, camp fires, wall lanterns and gate torches of a piece (Outdoors, Works, Zoning). */
    private static void lightsOf(Spec s, float floor, List<AetherLights.Light> out) {
        switch (s.kind) {
            case YARD -> {
                if (s.variant == Outdoors.LAMP_POST) out.add(AetherLights.lamp(s.x + 0.5, floor + 3, s.z + 0.5, 0.8f));
                else if (s.variant == Outdoors.BRAZIER) out.add(AetherLights.fire(s.x + 0.5, floor + 2, s.z + 0.5, 1f));
            }
            case LAMP -> out.add(AetherLights.lamp(s.x + 0.5, floor + 3, s.z + 0.5, 0.8f));
            case CAMP -> out.add(AetherLights.fire(s.x + 0.5, floor + 1, s.z + 0.5, 1.2f));
            case PLAZA -> {
                if (s.variant == 3) out.add(AetherLights.fire(s.x + 0.5, floor + 2, s.z + 0.5, 1.2f));
                if (s.w >= 9) {
                    out.add(AetherLights.lamp(s.x - s.w / 2 + 0.5, floor + 3, s.z - s.d / 2 + 0.5, 0.8f));
                    out.add(AetherLights.lamp(s.x + s.w / 2 + 0.5, floor + 3, s.z + s.d / 2 + 0.5, 0.8f));
                }
            }
            case WALL -> {
                // a lantern on about one wall-top column in nine
                float f2 = (s.floor2 == Spec.UNSET ? s.floor : s.floor2) + 1;
                double len = Math.hypot(s.x2 - s.x, s.z2 - s.z);
                for (double t = 4.5; t < len; t += 9) {
                    double u = t / len;
                    out.add(AetherLights.lamp(s.x + (s.x2 - s.x) * u + 0.5, floor + (f2 - floor) * u + s.h + 2, s.z + (s.z2 - s.z) * u + 0.5, 0.6f));
                }
            }
            case GATE -> out.add(AetherLights.fire(s.x + 0.5, floor + 3, s.z + 0.5, 1f));
            default -> { }
        }
    }

    /**
     * A building's lit windows at night: one light per facade the windows face, at the first storey's window height,
     * as bright as its window count (one every three blocks per storey, about half of them lit), set a block out from
     * the wall so the building itself hides the facades facing away. Lanterns by the door of inns, shops and keeps.
     */
    private static void windows(Spec s, Blueprint bp, float floor, List<AetherLights.Light> out) {
        int storeys = bp != null ? Math.max(1, bp.storeys) : 1;
        int[] o = SettlementPiece.origin(s);
        // facade centres in the local frame: front (z = 0), back, left, right
        double[][] faces = {{(s.w - 1) * 0.5, -1, s.w}, {(s.w - 1) * 0.5, s.d, s.w}, {-1, (s.d - 1) * 0.5, s.d}, {s.w, (s.d - 1) * 0.5, s.d}};
        for (double[] f : faces) {
            double wx = o[0] + mapX(s.facing, (int) Math.round(f[0]), (int) Math.round(f[1])) + 0.5;
            double wz = o[1] + mapZ(s.facing, (int) Math.round(f[0]), (int) Math.round(f[1])) + 0.5;
            float lit = (float) (f[2] / 3.0 * storeys * 0.5);
            if (lit < 0.5f) continue;
            out.add(AetherLights.window(wx, floor + 2.5, wz, Math.min(6f, lit) * 0.25f));
        }
        if (bp != null && bp.lanterns) {
            double wx = o[0] + mapX(s.facing, bp.doorX, -1) + 0.5, wz = o[1] + mapZ(s.facing, bp.doorX, -1) + 0.5;
            out.add(AetherLights.lamp(wx, floor + 2.5, wz, 0.7f));
        }
    }

    /** The piece's rotated footprint as one mass. */
    private void box(Spec s, List<Object> out, float wallTop, float rise, Roof roof, BlockState wall, BlockState roofB) {
        int[] o = SettlementPiece.origin(s);
        int ax = o[0] + mapX(s.facing, 0, 0), az = o[1] + mapZ(s.facing, 0, 0);
        int cx = o[0] + mapX(s.facing, s.w - 1, s.d - 1), cz = o[1] + mapZ(s.facing, s.w - 1, s.d - 1);
        out.add(new Mass(Math.min(ax, cx), Math.min(az, cz), Math.max(ax, cx), Math.max(az, cz), wallTop, rise, roof,
                mats.id(wall), mats.id(roofB)));
    }

    private static Palette palette(Spec s) {
        return Kits.palette(Culture.byOrdinal(Math.max(0, s.culture)), s.style);
    }

    /** SettlementPiece.mapX / mapZ: a building's local (lx, lz) to a world offset from its origin. */
    static int mapX(Direction f, int lx, int lz) {
        return switch (f) {
            case EAST -> -lz;
            case SOUTH -> -lx;
            case WEST -> lz;
            default -> lx;
        };
    }

    static int mapZ(Direction f, int lx, int lz) {
        return switch (f) {
            case EAST -> lx;
            case SOUTH -> -lz;
            case WEST -> -lx;
            default -> lz;
        };
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }
}

package com.skycraft.worldplan;

import com.skycraft.roads.PlanLinks;
import com.skycraft.tamriel.PlaceType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Pipeline stage S7 (P2): how the terrain's height function obeys the {@link RoadPlan}. Not baked (it only reads the
 * plan), so changing it does not invalidate the bake.
 *
 * <p><b>Road bed.</b> Around every planned road the ground is pulled to the profile height. The flat zone reaches
 * {@code halfWidth + 4.5} blocks (2.5 for footpaths): Minecraft interpolates the terrain density between cell corners
 * 4 blocks apart, so for the surface of every column within the half width to be exact, all corners of its cell must
 * be at the bed height too. Beyond the flat zone the ground blends back to the natural terrain with a smoothstep over
 * 6 to 12 blocks (longer for deeper cuts and fills, at most 20 blocks from the centre line altogether): cut slopes
 * where the road is below the ground, embankments where it is above. The seeded detail noise fades out over the same
 * zone ({@link #CALM}), so the profile (made on the smooth macro ground) is never far from the natural ground.</p>
 *
 * <p><b>Spans.</b> Bridges and tunnels leave the natural terrain between their ends (the weight fades out over
 * {@link #RAMP} blocks inside the span, so an abutment or portal is a smooth ramp). Channel columns (river, stream,
 * lake) are never raised, so a culvert stays open under its embankment and a road along a stream does not dam it:
 * only a ford is laid through the channel. Tunnel mouths and the open cells are reported in {@link Bed}.</p>
 *
 * <p><b>Pads.</b> Settlements and trail destinations flatten to their pad height inside their radius and blend over
 * {@link PlanTerrain#PAD_BLEND} blocks outside it (the shape the planner uses), except on water.</p>
 *
 * <p>All queries are allocation-free (one {@link Bed} scratch per thread) and deterministic.</p>
 */
public final class PlanGround {
    /** Per-thread dev-harness mode, see {@link #mode}. */
    private static final ThreadLocal<int[]> MODE = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Bed> SCRATCH = ThreadLocal.withInitial(Bed::new);

    /** Flat zone beyond the half width (blocks): the 4-block cell corners of every bed column must be flat too. */
    static final float FLAT_PAD = 4.5f, TRAIL_PAD = 3.5f;
    /** Shoulder length range, and the farthest distance from a centre line that the road shapes the terrain. */
    static final float SHOULDER_MIN = 6f, SHOULDER_MAX = 12f, OUTER = 20f;
    /** Length over which a bridge or tunnel span fades the shaping out from its ends. */
    public static final float RAMP = 8f;
    /** How much of the seeded detail the road/pad weight removes (1 = all). */
    public static final float CALM = 0.9f;
    /** The terrain target is the profile plus this, so the top solid block is exactly floor(profile). */
    static final float EPS = 0.002f;
    private static final byte TRAIL_KIND = 3;
    private static final int K = 4;
    private static final int SITE_SHIFT = 8;

    /** One answer; reuse an instance per thread ({@link #scratch}). */
    public static final class Bed {
        /** Combined shaping weight 0..1 and the height the terrain is pulled to (profile + a hair). */
        public float weight, height;
        /** The nearest planned road: distance from its centre line, half width, profile height, span type, class. */
        public float distance, halfWidth, profile;
        public byte span, kind;
        public int segment, vertex;
        /** Distance along the segment (blocks). */
        public float along;
        /** The column is within the road bed (half width + 1). */
        public boolean inBed;
        /** Natural terrain is left under a bridge deck / tunnel roof here (inside the span, past its ramps). */
        public boolean open;
        /** Within {@link #RAMP} blocks of a tunnel span's end: the portal where P3 cuts the tunnel mouth. */
        public boolean mouth;
        /** Pad shaping at the column: weight and pad height. */
        public float padWeight, padHeight;
        /** P5: the street plan's plot / street pad at the column: weight and the height the terrain is pulled to. */
        public float plotWeight, plotHeight;
        // candidates (nearest edge per stretch of road)
        private int n;
        private final int[] cEdge = new int[K];
        private final float[] cD2 = new float[K], cT = new float[K];

        void clear() {
            weight = 0f;
            height = Float.NaN;
            distance = Float.MAX_VALUE;
            halfWidth = 0f;
            profile = Float.NaN;
            span = RoadPlan.NORMAL;
            kind = 0;
            segment = -1;
            vertex = -1;
            along = 0f;
            inBed = false;
            open = false;
            mouth = false;
            padWeight = 0f;
            padHeight = Float.NaN;
            n = 0;
        }
    }

    private final RoadPlan plan;
    private final boolean[] padded;
    private final Long2ObjectOpenHashMap<int[]> siteGrid = new Long2ObjectOpenHashMap<>();

    private static volatile PlanGround cached;

    /** Own edge index: 32-block buckets, every edge in each bucket within {@link #OUTER} blocks of it. */
    private static final int BUCKET_SHIFT = 5;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap bucketOf = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private final int[] bucketOff;
    private final int[] bucketEdges;
    private final float[] invLen2;

    private static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xffffffffL);
    }

    private PlanGround(RoadPlan plan) {
        this.plan = plan;
        long t0 = System.nanoTime();
        // ---- road edge index
        RoadPlan p = plan;
        int nv = p.vertices();
        invLen2 = new float[nv];
        int[] off = null, edges = null;
        bucketOf.defaultReturnValue(-1);
        IntArrayList counts = new IntArrayList();
        for (int pass = 0; pass < 2; pass++) {
            int[] fill = null;
            if (pass == 1) {
                off = new int[counts.size() + 1];
                for (int k = 0; k < counts.size(); k++) off[k + 1] = off[k] + counts.getInt(k);
                edges = new int[off[counts.size()]];
                fill = new int[counts.size()];
            }
            for (int s = 0; s < p.segments(); s++) {
                for (int i = p.segStart[s]; i + 1 < p.segEnd[s]; i++) {
                    float dx = p.vx[i + 1] - p.vx[i], dz = p.vz[i + 1] - p.vz[i];
                    float l2 = dx * dx + dz * dz;
                    invLen2[i] = l2 < 1e-9f ? 0f : 1f / l2;
                    int x0 = (int) Math.floor(Math.min(p.vx[i], p.vx[i + 1]) - OUTER) >> BUCKET_SHIFT;
                    int x1 = (int) Math.floor(Math.max(p.vx[i], p.vx[i + 1]) + OUTER) >> BUCKET_SHIFT;
                    int z0 = (int) Math.floor(Math.min(p.vz[i], p.vz[i + 1]) - OUTER) >> BUCKET_SHIFT;
                    int z1 = (int) Math.floor(Math.max(p.vz[i], p.vz[i + 1]) + OUTER) >> BUCKET_SHIFT;
                    for (int bx = x0; bx <= x1; bx++) {
                        for (int bz = z0; bz <= z1; bz++) {
                            long key = key(bx, bz);
                            int k = bucketOf.get(key);
                            if (pass == 0) {
                                if (k < 0) {
                                    k = counts.size();
                                    bucketOf.put(key, k);
                                    counts.add(0);
                                }
                                counts.set(k, counts.getInt(k) + 1);
                            } else {
                                edges[off[k] + fill[k]++] = i;
                            }
                        }
                    }
                }
            }
        }
        bucketOff = off;
        bucketEdges = edges;
        int n = plan.sites();
        padded = new boolean[n];
        Long2ObjectOpenHashMap<IntArrayList> tmp = new Long2ObjectOpenHashMap<>();
        java.util.ArrayList<Long> order = new java.util.ArrayList<>();
        PlaceType[] types = PlaceType.values();
        for (int i = 0; i < n; i++) {
            PlaceType t = types[plan.siteType[i]];
            padded[i] = t.isSettlement() || PlanLinks.isTrailDestination(t);
            if (!padded[i]) continue;
            int reach = (int) Math.ceil(plan.siteRadius[i] + PlanTerrain.PAD_BLEND);
            for (int bx = (plan.siteX[i] - reach) >> SITE_SHIFT; bx <= (plan.siteX[i] + reach) >> SITE_SHIFT; bx++) {
                for (int bz = (plan.siteZ[i] - reach) >> SITE_SHIFT; bz <= (plan.siteZ[i] + reach) >> SITE_SHIFT; bz++) {
                    long k = key(bx, bz);
                    IntArrayList l = tmp.get(k);
                    if (l == null) {
                        tmp.put(k, l = new IntArrayList());
                        order.add(k);
                    }
                    l.add(i);
                }
            }
        }
        for (Long k : order) siteGrid.put(k.longValue(), tmp.get(k.longValue()).toIntArray());
        com.skycraft.Skycraft.LOGGER.info("[roadplan] terrain index: {} road buckets, {} edge entries, {} pad buckets ({} ms)",
                bucketOf.size(), bucketEdges.length, siteGrid.size(), (System.nanoTime() - t0) / 1_000_000);
    }

    /** Built once per plan: the loader thread, the server thread and every worldgen worker may ask at the same time. */
    private static PlanGround of(RoadPlan p) {
        PlanGround g = cached;
        if (g != null && g.plan == p) return g;
        synchronized (PlanGround.class) {
            g = cached;
            if (g == null || g.plan != p) cached = g = new PlanGround(p);
            return g;
        }
    }

    /** Dev harness modes (per thread): plan shaping (production), old terrain, and natural terrain with the detail
     *  calmed over roads and pads but no bed/pad blend (what the cut/fill depth is measured against). */
    public static final int MODE_PLAN = 0, MODE_OLD = 1, MODE_CALM_ONLY = 2;

    public static int mode() {
        return MODE.get()[0];
    }

    public static void setMode(int mode) {
        MODE.get()[0] = mode;
    }

    /** True when the plan lookups (and the detail calm) are on for this thread. */
    public static boolean enabled() {
        return MODE.get()[0] != MODE_OLD;
    }

    /** True in production mode: the channels and the final bed/pad blend follow the plan. */
    public static boolean blending() {
        return MODE.get()[0] == MODE_PLAN;
    }

    /** Dev harness: the old terrain (no plan shaping) for the calling thread only. */
    public static void setDisabled(boolean disabled) {
        MODE.get()[0] = disabled ? MODE_OLD : MODE_PLAN;
    }

    /** This thread's scratch {@link Bed}. */
    public static Bed scratch() {
        return SCRATCH.get();
    }

    static double bedHeight(RoadPlan p, int x, int z) {
        Bed b = scratch();
        if (!of(p).bed(x, z, b) || !b.inBed) return Double.NaN;
        return b.profile;
    }

    /** The ground object for the loaded plan. */
    public static PlanGround get() {
        return of(RoadPlan.get());
    }

    /** Terrain-shaping influence of road and pad at a column, 0..1 (for the cave/overhang channels). */
    public static float influence(int x, int z) {
        PlanGround g = of(RoadPlan.get());
        Bed b = scratch();
        float w = 0f;
        if (g.bed(x, z, b)) w = b.weight;
        if (g.pad(x, z, b)) w = Math.max(w, b.padWeight);
        if (g.plot(x, z, b)) w = Math.max(w, b.plotWeight);
        return w;
    }

    /**
     * P5: the street plan's plot and street pads at column (x, z) ({@code b.plotWeight}, {@code b.plotHeight}, see
     * {@link com.skycraft.worldplan.street.StreetPlan#pad}); false where none reaches. Applied after the site pad and
     * before the road bed, never on water columns.
     */
    public boolean plot(int x, int z, Bed b) {
        return com.skycraft.worldplan.street.StreetPlan.get().pad(x, z, b);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    /**
     * The roads within {@link #OUTER} blocks of column (x, z): fills the combined shaping {@code weight}/{@code height}
     * and the nearest road's data ({@code distance}, {@code profile}, {@code span}, {@code inBed}, {@code open},
     * {@code mouth}). Returns false when no road shapes the column at all. Channel exclusion (the culvert opening)
     * is the caller's: it knows whether the column is in water.
     */
    public boolean bed(int x, int z, Bed b) {
        b.clear();
        RoadPlan p = plan;
        int k = bucketOf.get(key(x >> BUCKET_SHIFT, z >> BUCKET_SHIFT));
        if (k < 0) return false;
        float px = x, pz = z;
        // nearest edge per stretch of road (edges within 4 of a candidate's edge are the same stretch)
        for (int j = bucketOff[k], end = bucketOff[k + 1]; j < end; j++) {
            int i = bucketEdges[j];
            float ax = p.vx[i], az = p.vz[i];
            float dx = p.vx[i + 1] - ax, dz = p.vz[i + 1] - az;
            float t = ((px - ax) * dx + (pz - az) * dz) * invLen2[i];
            t = t < 0f ? 0f : t > 1f ? 1f : t;
            float qx = ax + dx * t - px, qz = az + dz * t - pz;
            float d2 = qx * qx + qz * qz;
            if (d2 >= OUTER * OUTER) continue;
            int slot = -1;
            for (int c = 0; c < b.n; c++) {
                int e = b.cEdge[c];
                if (p.vseg[e] == p.vseg[i] && Math.abs(e - i) <= 4) {
                    slot = c;
                    break;
                }
            }
            if (slot >= 0) {
                if (d2 >= b.cD2[slot]) continue;
            } else if (b.n < K) {
                slot = b.n++;
            } else {
                int worst = 0;
                for (int c = 1; c < K; c++) if (b.cD2[c] > b.cD2[worst]) worst = c;
                if (d2 >= b.cD2[worst]) continue;
                slot = worst;
            }
            b.cEdge[slot] = i;
            b.cD2[slot] = d2;
            b.cT[slot] = t;
        }
        if (b.n == 0) return false;
        float sumW = 0f, sumWH = 0f, maxW = 0f, nearest = Float.MAX_VALUE;
        for (int c = 0; c < b.n; c++) {
            int i = b.cEdge[c], s = p.vseg[i];
            float t = b.cT[c], d = (float) Math.sqrt(b.cD2[c]);
            float hw = p.segHalfWidth[s];
            byte kind = p.segKind[s];
            float h = p.vh[i] + (p.vh[i + 1] - p.vh[i]) * t;
            float g = p.vg[i] + (p.vg[i + 1] - p.vg[i]) * t;
            float along = p.varc[i] + (p.varc[i + 1] - p.varc[i]) * t;
            int v = t < 0.5f ? i : i + 1;
            byte span = p.vspan[v];
            float r = hw + (kind == TRAIL_KIND ? TRAIL_PAD : FLAT_PAD);
            float len = Math.min(SHOULDER_MAX, SHOULDER_MIN + 0.75f * Math.abs(g - h));
            len = Math.max(3f, Math.min(len, OUTER - r));
            float w = d <= r ? 1f : d >= r + len ? 0f : 1f - smooth((d - r) / len);
            boolean open = false, mouth = false;
            if (span == RoadPlan.BRIDGE || span == RoadPlan.TUNNEL) {
                int sp = p.spanAt(s, v);
                if (sp >= 0) {
                    float a0 = p.varc[p.spanFrom[sp]], a1 = p.varc[p.spanTo[sp]];
                    // the first FLAT_PAD blocks inside the span keep the bed (the 4-block cell corners of the road
                    // columns before the span end must stay flat), then the weight fades out over the ramp
                    float into = Math.max(0f, Math.min(along - a0, a1 - along)) - FLAT_PAD;
                    float room = (a1 - a0) - 2f * FLAT_PAD;
                    if (room > 0.5f) {
                        float ramp = Math.min(RAMP, 0.4f * room);
                        float fade = Math.max(0f, Math.min(1f, into / ramp));
                        w *= 1f - smooth(fade);
                        open = fade >= 1f;
                        mouth = span == RoadPlan.TUNNEL && fade < 1f;
                    } else {
                        mouth = span == RoadPlan.TUNNEL;
                    }
                }
            }
            sumW += w * w;
            sumWH += w * w * (h + EPS);
            if (w > maxW) maxW = w;
            if (d < nearest) {
                nearest = d;
                b.distance = d;
                b.halfWidth = hw;
                b.profile = h;
                b.span = span;
                b.kind = kind;
                b.segment = s;
                b.vertex = i;
                b.along = along;
                b.inBed = d <= hw + 1f;
                b.open = open;
                b.mouth = mouth;
            }
        }
        if (maxW <= 0f) return false;
        b.weight = maxW;
        b.height = sumWH / sumW;
        return true;
    }

    /**
     * True when the road at the column (a successful {@link #bed} with {@code b}) crosses a water channel here and the
     * terrain left it natural: the opening under a culvert's embankment or a bridge deck (P3 builds the arch/deck
     * over it at the bed height). Fords are laid through the channel, so they are not openings.
     */
    public static boolean opening(com.skycraft.tamriel.MapSample s, Bed b) {
        return b.weight > 0f && s.inWater() && b.span != RoadPlan.FORD;
    }

    /** The pad shaping at column (x, z): fills {@code padWeight}/{@code padHeight}; false when no pad reaches it. */
    public boolean pad(int x, int z, Bed b) {
        b.padWeight = 0f;
        b.padHeight = Float.NaN;
        int[] l = siteGrid.get(key(x >> SITE_SHIFT, z >> SITE_SHIFT));
        if (l == null) return false;
        float bestW = 0f, pad = Float.NaN;
        for (int i : l) {
            float dx = x - plan.siteX[i], dz = z - plan.siteZ[i];
            float d = (float) Math.sqrt(dx * dx + dz * dz), r = plan.siteRadius[i];
            if (d >= r + PlanTerrain.PAD_BLEND) continue;
            float w = d <= r ? 1f : 1f - (d - r) / PlanTerrain.PAD_BLEND;
            w = w * w * (3f - 2f * w);
            if (w > bestW) {
                bestW = w;
                pad = plan.sitePad[i];
            }
        }
        if (bestW <= 0f) return false;
        b.padWeight = bestW;
        b.padHeight = pad;
        return true;
    }

    /** Distance (blocks) from (x, z) to the nearest padded site's edge (negative inside), or +inf; brute force. */
    public double padDistance(int x, int z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < padded.length; i++) {
            if (!padded[i]) continue;
            double d = Math.hypot(x - plan.siteX[i], z - plan.siteZ[i]) - plan.siteRadius[i];
            if (d < best) best = d;
        }
        return best;
    }
}

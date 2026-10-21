package com.nirncraft.worldplan.street;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.settlement.plan.Archetype;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.worldplan.PlanGround;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Direction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The baked street plan (pipeline stage S5, docs/WORLDGEN-PIPELINE.md P5): for every named settlement, the layout the
 * settlement grammar resolved from its authored city plan on the seed-independent plan terrain ({@link StreetTerrain}),
 * and what the rest of the pipeline reads from it:
 * <ul>
 *   <li>a <b>street graph</b>: nodes {x, z, h} (h = walk surface, the y of the top block) of class street, gate, door,
 *   path, plaza or road, and edges {a, b, width, class MAIN/LANE/ALLEY/PATH/STAIRS/BRIDGE, surface}; every GATE (and
 *   ROAD) node is a {@link com.nirncraft.worldplan.RoadPlan} vertex: {@code nJoinSeg/nJoinVert} name it, so the street
 *   and the road meet in one cell;</li>
 *   <li><b>doors</b> {building, x, y, z, facing, node}: every declared entrance, linked to the street graph by a
 *   doorstep path (PATH edges, one block wide, at most one block of rise per block); {@code dReach} is false for a door
 *   no path could reach (a lint error);</li>
 *   <li><b>plots</b> {building, rectangle, pad height}: footprints (axis-aligned rectangles, inclusive block bounds); the
 *   ones with a pad pull the generated terrain to their floor (P2 style, {@link #pad}), all of them feed
 *   {@code PlacementMask} ({@link StreetMask});</li>
 *   <li>the <b>layout</b> itself (the resolved {@link Spec} list): {@code SettlementStructure} builds named places from
 *   it instead of planning them at runtime, so the generated blocks are exactly what the graph describes.</li>
 * </ul>
 * Immutable after loading; every read is thread-safe and the per-column queries are allocation-free.
 */
public final class StreetPlan {
    public static final int MAGIC = 0x53504c4e; // "SPLN"
    public static final int VERSION = 1;

    // node classes
    public static final byte N_STREET = 0, N_GATE = 1, N_DOOR = 2, N_PATH = 3, N_PLAZA = 4, N_ROAD = 5;
    public static final String[] NODE_NAMES = {"street", "gate", "door", "path", "plaza", "road"};
    // edge classes (the P5 contract)
    public static final byte MAIN = 0, LANE = 1, ALLEY = 2, PATH = 3, STAIRS = 4, BRIDGE = 5;
    public static final String[] CLASS_NAMES = {"main", "lane", "alley", "path", "stairs", "bridge"};
    // edge surfaces
    public static final byte PAVED = 0, BEATEN = 1, PLANKS = 2, STONE = 3;
    // plot kinds: a building (no path crosses it), a wall/gate/tower line, an open work (yard, field, plaza...)
    public static final byte PLOT_BUILDING = 0, PLOT_WALL = 1, PLOT_WORK = 2;
    /** No pad under this plot. */
    public static final int NO_PAD = Integer.MIN_VALUE;

    /** How far {@link #at} answers (blocks), and the reach of the index around edges and plots. */
    public static final float REACH = 16f;
    /** Pads blend into the natural terrain over this many blocks beyond a plot or street band. */
    public static final float PAD_BLEND = 4f;
    static final int BUCKET_SHIFT = 4;
    /** Ints per serialised {@link Spec}. */
    static final int SPEC_INTS = 20;

    // ---- settlements (per settlement s, the ranges [off[s], off[s+1]) of its nodes, edges, doors, plots, specs)
    public final String[] sId;
    public final int[] sX, sZ, sReach;
    public final int[] sNodeOff, sEdgeOff, sDoorOff, sPlotOff, sSpecOff;

    // ---- nodes
    public final float[] nx, nz, nh;
    public final byte[] nClass;
    /** The road plan segment and global vertex a GATE / ROAD node is (else -1). */
    public final int[] nJoinSeg, nJoinVert;

    // ---- edges (node indices are global)
    public final int[] ea, eb;
    public final float[] ew;
    public final byte[] eClass, eSurf;

    // ---- doors
    public final int[] dSpec, dx, dy, dz, dNode;
    /** Outward direction ({@link Direction#get2DDataValue}). */
    public final byte[] dFacing;
    public final boolean[] dReach;

    // ---- plots
    public final int[] pSpec, pMinX, pMinZ, pMaxX, pMaxZ, pPad;
    public final byte[] pKind;

    // ---- the resolved layouts
    final int[] specInts;
    final String[] specNames;

    // ---- index: 16-block bucket -> edges / plots within REACH of it
    private final Long2IntOpenHashMap bucketOf = new Long2IntOpenHashMap();
    private final int[] bucketEdgeOff, bucketEdges, bucketPlotOff, bucketPlots;
    private final float[] edgeInvLen2;
    /** Settlement of each edge / plot (for answers). */
    private final int[] edgeSettlement;
    private final Object2IntOpenHashMap<String> byId = new Object2IntOpenHashMap<>();
    private final Map<String, List<Spec>> layouts = new ConcurrentHashMap<>();

    /** One answer of {@link #at}; reuse an instance per thread. */
    public static final class Hit {
        public int edge = -1, settlement = -1;
        public byte cls, surface;
        /** Distance from the edge's centre line, its width, and the interpolated walk surface (y of the top block). */
        public float distance, width, height;
        /** Position along the edge (0 at node a, 1 at node b). */
        public float t;

        void clear() {
            edge = -1;
            settlement = -1;
            distance = Float.MAX_VALUE;
        }
    }

    StreetPlan(Builder b) {
        sId = b.sId;
        sX = b.sX;
        sZ = b.sZ;
        sReach = b.sReach;
        sNodeOff = b.sNodeOff;
        sEdgeOff = b.sEdgeOff;
        sDoorOff = b.sDoorOff;
        sPlotOff = b.sPlotOff;
        sSpecOff = b.sSpecOff;
        nx = b.nx;
        nz = b.nz;
        nh = b.nh;
        nClass = b.nClass;
        nJoinSeg = b.nJoinSeg;
        nJoinVert = b.nJoinVert;
        ea = b.ea;
        eb = b.eb;
        ew = b.ew;
        eClass = b.eClass;
        eSurf = b.eSurf;
        dSpec = b.dSpec;
        dx = b.dx;
        dy = b.dy;
        dz = b.dz;
        dNode = b.dNode;
        dFacing = b.dFacing;
        dReach = b.dReach;
        pSpec = b.pSpec;
        pMinX = b.pMinX;
        pMinZ = b.pMinZ;
        pMaxX = b.pMaxX;
        pMaxZ = b.pMaxZ;
        pPad = b.pPad;
        pKind = b.pKind;
        specInts = b.specInts;
        specNames = b.specNames;
        for (int s = 0; s < sId.length; s++) byId.put(sId[s], s);
        byId.defaultReturnValue(-1);
        int ne = ea.length, np = pSpec.length;
        edgeInvLen2 = new float[ne];
        edgeSettlement = new int[ne];
        int[] plotSettlement = new int[np];
        for (int s = 0; s < sId.length; s++) {
            for (int e = sEdgeOff[s]; e < sEdgeOff[s + 1]; e++) edgeSettlement[e] = s;
            for (int p = sPlotOff[s]; p < sPlotOff[s + 1]; p++) plotSettlement[p] = s;
        }
        // index
        Long2ObjectOpenHashMap<IntArrayList[]> tmp = new Long2ObjectOpenHashMap<>();
        List<Long> order = new ArrayList<>();
        for (int e = 0; e < ne; e++) {
            float ax = nx[ea[e]], az = nz[ea[e]], bx = nx[eb[e]], bz = nz[eb[e]];
            float ddx = bx - ax, ddz = bz - az, l2 = ddx * ddx + ddz * ddz;
            edgeInvLen2[e] = l2 < 1e-9f ? 0f : 1f / l2;
            float r = REACH + ew[e] / 2f;
            addToIndex(tmp, order, (int) Math.floor(Math.min(ax, bx) - r), (int) Math.floor(Math.min(az, bz) - r),
                    (int) Math.floor(Math.max(ax, bx) + r), (int) Math.floor(Math.max(az, bz) + r), e, 0);
        }
        for (int p = 0; p < np; p++) {
            int r = (int) REACH;
            addToIndex(tmp, order, pMinX[p] - r, pMinZ[p] - r, pMaxX[p] + r, pMaxZ[p] + r, p, 1);
        }
        bucketOf.defaultReturnValue(-1);
        int nb = order.size();
        bucketEdgeOff = new int[nb + 1];
        bucketPlotOff = new int[nb + 1];
        int te = 0, tp = 0;
        for (Long k : order) {
            IntArrayList[] l = tmp.get(k.longValue());
            te += l[0].size();
            tp += l[1].size();
        }
        bucketEdges = new int[te];
        bucketPlots = new int[tp];
        int pe = 0, pp = 0;
        for (int k = 0; k < nb; k++) {
            long key = order.get(k);
            IntArrayList[] l = tmp.get(key);
            bucketOf.put(key, k);
            bucketEdgeOff[k] = pe;
            l[0].getElements(0, bucketEdges, pe, l[0].size());
            pe += l[0].size();
            bucketPlotOff[k] = pp;
            l[1].getElements(0, bucketPlots, pp, l[1].size());
            pp += l[1].size();
        }
        bucketEdgeOff[nb] = pe;
        bucketPlotOff[nb] = pp;
    }

    private static void addToIndex(Long2ObjectOpenHashMap<IntArrayList[]> tmp, List<Long> order, int x0, int z0, int x1, int z1,
                                   int id, int list) {
        for (int bx = x0 >> BUCKET_SHIFT; bx <= x1 >> BUCKET_SHIFT; bx++) {
            for (int bz = z0 >> BUCKET_SHIFT; bz <= z1 >> BUCKET_SHIFT; bz++) {
                long k = key(bx, bz);
                IntArrayList[] l = tmp.get(k);
                if (l == null) {
                    l = new IntArrayList[]{new IntArrayList(4), new IntArrayList(4)};
                    tmp.put(k, l);
                    order.add(k);
                }
                l[list].add(id);
            }
        }
    }

    static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xffffffffL);
    }

    // ------------------------------------------------------------------ queries

    public int settlements() {
        return sId.length;
    }

    /** Index of a settlement by place id, or -1. */
    public int settlement(String id) {
        return byId.getInt(id);
    }

    public int nodes() {
        return nx.length;
    }

    public int edges() {
        return ea.length;
    }

    public int doors() {
        return dSpec.length;
    }

    public int plots() {
        return pSpec.length;
    }

    /** Resolves the owning plan before callers access bucket/edge/plot arrays. */
    public static StreetPlan planFor(int x, int z) {
        if (!com.nirncraft.tamriel.TamrielMap.inTamriel(x, z)) {
            StreetPlan tile = com.nirncraft.worldplan.tile.TilePlanner.streetPlanAt(x, z);
            if (tile != null) return tile;
        }
        return get();
    }

    /**
     * The street edge nearest to column (x, z) within {@link #REACH} blocks (streets, gate lanes, doorstep paths,
     * bridges, stairs): fills {@code out} and returns true, or false. Same contract as {@code RoadPlan.at}.
     */
    public boolean at(double x, double z, Hit out) {
        out.clear();
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        int k = bucketOf.get(key(ix >> BUCKET_SHIFT, iz >> BUCKET_SHIFT));
        if (k < 0) {
            if (this == Holder.PLAN && !com.nirncraft.tamriel.TamrielMap.inTamriel(ix, iz)) {
                StreetPlan tp = com.nirncraft.worldplan.tile.TilePlanner.streetPlanAt(ix, iz);
                if (tp != null && tp != this) return tp.at(x, z, out);
            }
            return false;
        }
        float px = (float) x, pz = (float) z, best = REACH * REACH;
        int bestE = -1;
        float bestT = 0;
        for (int j = bucketEdgeOff[k], end = bucketEdgeOff[k + 1]; j < end; j++) {
            int e = bucketEdges[j];
            float ax = nx[ea[e]], az = nz[ea[e]];
            float ddx = nx[eb[e]] - ax, ddz = nz[eb[e]] - az;
            float t = ((px - ax) * ddx + (pz - az) * ddz) * edgeInvLen2[e];
            t = t < 0f ? 0f : t > 1f ? 1f : t;
            float qx = ax + ddx * t - px, qz = az + ddz * t - pz;
            float d2 = qx * qx + qz * qz;
            if (d2 < best) {
                best = d2;
                bestE = e;
                bestT = t;
            }
        }
        if (bestE < 0) {
            if (this == Holder.PLAN && !com.nirncraft.tamriel.TamrielMap.inTamriel(ix, iz)) {
                StreetPlan tp = com.nirncraft.worldplan.tile.TilePlanner.streetPlanAt(ix, iz);
                if (tp != null && tp != this) return tp.at(x, z, out);
            }
            return false;
        }
        out.edge = bestE;
        out.settlement = edgeSettlement[bestE];
        out.cls = eClass[bestE];
        out.surface = eSurf[bestE];
        out.width = ew[bestE];
        out.t = bestT;
        out.distance = (float) Math.sqrt(best);
        out.height = nh[ea[bestE]] + (nh[eb[bestE]] - nh[ea[bestE]]) * bestT;
        return true;
    }

    /** One door of a settlement. */
    public record Door(int spec, int x, int y, int z, Direction facing, int node, boolean reachable) {
    }

    /** The doors of a settlement (empty if the place has no street plan). */
    public List<Door> doorsOf(String settlementId) {
        int s = settlement(settlementId);
        if (s < 0) return List.of();
        List<Door> out = new ArrayList<>(sDoorOff[s + 1] - sDoorOff[s]);
        for (int d = sDoorOff[s]; d < sDoorOff[s + 1]; d++) {
            out.add(new Door(dSpec[d], dx[d], dy[d], dz[d], Direction.from2DDataValue(dFacing[d]), dNode[d], dReach[d]));
        }
        return out;
    }

    /** The resolved layout of a named settlement (shared, do not modify), or null when the plan has none. */
    public List<Spec> layout(String settlementId) {
        int s = settlement(settlementId);
        if (s < 0) return null;
        return layouts.computeIfAbsent(settlementId, k -> {
            List<Spec> out = new ArrayList<>(sSpecOff[s + 1] - sSpecOff[s]);
            for (int i = sSpecOff[s]; i < sSpecOff[s + 1]; i++) out.add(decode(i));
            return List.copyOf(out);
        });
    }

    private Spec decode(int i) {
        int o = i * SPEC_INTS;
        int[] a = specInts;
        Spec s = new Spec(Archetype.byOrdinal(a[o]));
        s.culture = a[o + 1];
        s.style = a[o + 2];
        s.x = a[o + 3];
        s.z = a[o + 4];
        s.x2 = a[o + 5];
        s.z2 = a[o + 6];
        s.w = a[o + 7];
        s.d = a[o + 8];
        s.h = a[o + 9];
        s.facing = Direction.from2DDataValue(a[o + 10]);
        s.floor = a[o + 11];
        s.floor2 = a[o + 12];
        s.flags = a[o + 13];
        s.variant = a[o + 14];
        s.margin = a[o + 15];
        s.use = a[o + 16];
        s.wealth = a[o + 17];
        s.gseed = a[o + 18];
        s.slope = a[o + 19];
        s.name = specNames[i];
        return s;
    }

    static void encode(Spec s, int[] a, int o) {
        a[o] = s.kind.ordinal();
        a[o + 1] = s.culture;
        a[o + 2] = s.style;
        a[o + 3] = s.x;
        a[o + 4] = s.z;
        a[o + 5] = s.x2;
        a[o + 6] = s.z2;
        a[o + 7] = s.w;
        a[o + 8] = s.d;
        a[o + 9] = s.h;
        a[o + 10] = s.facing.get2DDataValue();
        a[o + 11] = s.floor;
        a[o + 12] = s.floor2;
        a[o + 13] = s.flags;
        a[o + 14] = s.variant;
        a[o + 15] = s.margin;
        a[o + 16] = s.use;
        a[o + 17] = s.wealth;
        a[o + 18] = s.gseed;
        a[o + 19] = s.slope;
    }

    /**
     * P5 terrain: the plot and street pads at column (x, z): fills {@code b.plotWeight} / {@code b.plotHeight} (the
     * height the terrain is pulled to: the pad's top block plus a half, so the top solid block is the pad) and returns
     * true, or false where no pad reaches. Plots: weight 1 on the rectangle and one block round it, then a smoothstep
     * over {@link #PAD_BLEND} blocks; street bands likewise from half the width plus one. Allocation-free.
     */
    public boolean pad(int x, int z, PlanGround.Bed b) {
        b.plotWeight = 0f;
        b.plotHeight = Float.NaN;
        int k = bucketOf.get(key(x >> BUCKET_SHIFT, z >> BUCKET_SHIFT));
        if (k < 0) {
            if (this == Holder.PLAN && !com.nirncraft.tamriel.TamrielMap.inTamriel(x, z)) {
                StreetPlan tp = com.nirncraft.worldplan.tile.TilePlanner.streetPlanAt(x, z);
                if (tp != null && tp != this) return tp.pad(x, z, b);
            }
            return false;
        }
        float bestW = 0f, h = Float.NaN;
        for (int j = bucketPlotOff[k], end = bucketPlotOff[k + 1]; j < end; j++) {
            int p = bucketPlots[j];
            if (pPad[p] == NO_PAD) continue;
            int ox = x < pMinX[p] ? pMinX[p] - x : x > pMaxX[p] ? x - pMaxX[p] : 0;
            int oz = z < pMinZ[p] ? pMinZ[p] - z : z > pMaxZ[p] ? z - pMaxZ[p] : 0;
            float d = Math.max(ox, oz) - 1f;
            float w = d <= 0f ? 1f : d >= PAD_BLEND ? 0f : 1f - smooth(d / PAD_BLEND);
            if (w > bestW) {
                bestW = w;
                h = pPad[p] + 0.5f;
            }
        }
        if (bestW < 1f) {
            float px = x + 0.5f, pz = z + 0.5f;
            for (int j = bucketEdgeOff[k], end = bucketEdgeOff[k + 1]; j < end; j++) {
                int e = bucketEdges[j];
                byte c = eClass[e];
                // doorstep paths and bridges are painted, not shaped; steep streets (stairs) keep the ground their steps follow
                if (c == PATH || c == BRIDGE || c == STAIRS) continue;
                float ax = nx[ea[e]], az = nz[ea[e]];
                float ddx = nx[eb[e]] - ax, ddz = nz[eb[e]] - az;
                float t = ((px - ax) * ddx + (pz - az) * ddz) * edgeInvLen2[e];
                t = t < 0f ? 0f : t > 1f ? 1f : t;
                float qx = ax + ddx * t - px, qz = az + ddz * t - pz;
                float d = (float) Math.sqrt(qx * qx + qz * qz) - ew[e] / 2f - 1f;
                if (d >= PAD_BLEND) continue;
                float w = d <= 0f ? 1f : 1f - smooth(d / PAD_BLEND);
                if (w > bestW) {
                    bestW = w;
                    h = (float) Math.floor(nh[ea[e]] + (nh[eb[e]] - nh[ea[e]]) * t + 0.5f) + 0.5f;
                }
            }
        }
        if (bestW <= 0f) return false;
        b.plotWeight = bestW;
        b.plotHeight = h;
        return true;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    // ------------------------------------------------------------------ painter / mask access (public: the atlas overlays upload the buckets)

    /** The number of chunk buckets (bucket ids are 0 .. buckets() - 1). */
    public int buckets() {
        return bucketEdgeOff.length - 1;
    }

    public int bucket(int bx, int bz) {
        return bucketOf.get(key(bx, bz));
    }

    public int edgeFrom(int k) {
        return bucketEdgeOff[k];
    }

    public int edgeTo(int k) {
        return bucketEdgeOff[k + 1];
    }

    public int edgeAt(int j) {
        return bucketEdges[j];
    }

    public int plotFrom(int k) {
        return bucketPlotOff[k];
    }

    public int plotTo(int k) {
        return bucketPlotOff[k + 1];
    }

    public int plotAt(int j) {
        return bucketPlots[j];
    }

    // ------------------------------------------------------------------ serialisation

    public byte[] write() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream o = new DataOutputStream(bo)) {
            o.writeInt(MAGIC);
            o.writeInt(VERSION);
            int ns = sId.length;
            o.writeInt(ns);
            for (int s = 0; s < ns; s++) {
                o.writeUTF(sId[s]);
                o.writeInt(sX[s]);
                o.writeInt(sZ[s]);
                o.writeInt(sReach[s]);
            }
            ints(o, sNodeOff);
            ints(o, sEdgeOff);
            ints(o, sDoorOff);
            ints(o, sPlotOff);
            ints(o, sSpecOff);
            floats(o, nx);
            floats(o, nz);
            floats(o, nh);
            bytes(o, nClass);
            ints(o, nJoinSeg);
            ints(o, nJoinVert);
            ints(o, ea);
            ints(o, eb);
            floats(o, ew);
            bytes(o, eClass);
            bytes(o, eSurf);
            ints(o, dSpec);
            ints(o, dx);
            ints(o, dy);
            ints(o, dz);
            ints(o, dNode);
            bytes(o, dFacing);
            o.writeInt(dReach.length);
            for (boolean r : dReach) o.writeBoolean(r);
            ints(o, pSpec);
            ints(o, pMinX);
            ints(o, pMinZ);
            ints(o, pMaxX);
            ints(o, pMaxZ);
            ints(o, pPad);
            bytes(o, pKind);
            ints(o, specInts);
            o.writeInt(specNames.length);
            for (String n : specNames) o.writeUTF(n);
        }
        return bo.toByteArray();
    }

    public static StreetPlan read(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("not a street plan");
            int v = in.readInt();
            if (v != VERSION) throw new IOException("street plan version " + v + ", expected " + VERSION);
            Builder b = new Builder();
            int ns = in.readInt();
            b.sId = new String[ns];
            b.sX = new int[ns];
            b.sZ = new int[ns];
            b.sReach = new int[ns];
            for (int s = 0; s < ns; s++) {
                b.sId[s] = in.readUTF();
                b.sX[s] = in.readInt();
                b.sZ[s] = in.readInt();
                b.sReach[s] = in.readInt();
            }
            b.sNodeOff = ints(in);
            b.sEdgeOff = ints(in);
            b.sDoorOff = ints(in);
            b.sPlotOff = ints(in);
            b.sSpecOff = ints(in);
            b.nx = floats(in);
            b.nz = floats(in);
            b.nh = floats(in);
            b.nClass = bytes(in);
            b.nJoinSeg = ints(in);
            b.nJoinVert = ints(in);
            b.ea = ints(in);
            b.eb = ints(in);
            b.ew = floats(in);
            b.eClass = bytes(in);
            b.eSurf = bytes(in);
            b.dSpec = ints(in);
            b.dx = ints(in);
            b.dy = ints(in);
            b.dz = ints(in);
            b.dNode = ints(in);
            b.dFacing = bytes(in);
            int nr = in.readInt();
            b.dReach = new boolean[nr];
            for (int i = 0; i < nr; i++) b.dReach[i] = in.readBoolean();
            b.pSpec = ints(in);
            b.pMinX = ints(in);
            b.pMinZ = ints(in);
            b.pMaxX = ints(in);
            b.pMaxZ = ints(in);
            b.pPad = ints(in);
            b.pKind = bytes(in);
            b.specInts = ints(in);
            int nn = in.readInt();
            b.specNames = new String[nn];
            for (int i = 0; i < nn; i++) b.specNames[i] = in.readUTF();
            return new StreetPlan(b);
        }
    }

    private static void ints(DataOutputStream o, int[] a) throws IOException {
        o.writeInt(a.length);
        for (int v : a) o.writeInt(v);
    }

    private static void floats(DataOutputStream o, float[] a) throws IOException {
        o.writeInt(a.length);
        for (float v : a) o.writeFloat(v);
    }

    private static void bytes(DataOutputStream o, byte[] a) throws IOException {
        o.writeInt(a.length);
        o.write(a);
    }

    private static int[] ints(DataInputStream in) throws IOException {
        int[] a = new int[in.readInt()];
        for (int i = 0; i < a.length; i++) a[i] = in.readInt();
        return a;
    }

    private static float[] floats(DataInputStream in) throws IOException {
        float[] a = new float[in.readInt()];
        for (int i = 0; i < a.length; i++) a[i] = in.readFloat();
        return a;
    }

    private static byte[] bytes(DataInputStream in) throws IOException {
        byte[] a = new byte[in.readInt()];
        in.readFully(a);
        return a;
    }

    /** Plain arrays, filled by {@link StreetPlanBuilder} or {@link #read}. */
    static final class Builder {
        String[] sId;
        int[] sX, sZ, sReach, sNodeOff, sEdgeOff, sDoorOff, sPlotOff, sSpecOff;
        float[] nx, nz, nh;
        byte[] nClass;
        int[] nJoinSeg, nJoinVert;
        int[] ea, eb;
        float[] ew;
        byte[] eClass, eSurf;
        int[] dSpec, dx, dy, dz, dNode;
        byte[] dFacing;
        boolean[] dReach;
        int[] pSpec, pMinX, pMinZ, pMaxX, pMaxZ, pPad;
        byte[] pKind;
        int[] specInts;
        String[] specNames;
    }

    static StreetPlan empty() {
        Builder b = new Builder();
        b.sId = new String[0];
        b.sX = b.sZ = b.sReach = new int[0];
        b.sNodeOff = b.sEdgeOff = b.sDoorOff = b.sPlotOff = b.sSpecOff = new int[]{0};
        b.nx = b.nz = b.nh = new float[0];
        b.nClass = new byte[0];
        b.nJoinSeg = b.nJoinVert = new int[0];
        b.ea = b.eb = new int[0];
        b.ew = new float[0];
        b.eClass = b.eSurf = new byte[0];
        b.dSpec = b.dx = b.dy = b.dz = b.dNode = new int[0];
        b.dFacing = new byte[0];
        b.dReach = new boolean[0];
        b.pSpec = b.pMinX = b.pMinZ = b.pMaxX = b.pMaxZ = b.pPad = new int[0];
        b.pKind = new byte[0];
        b.specInts = new int[0];
        b.specNames = new String[0];
        return new StreetPlan(b);
    }

    // ------------------------------------------------------------------ the shipped plan

    private static final class Holder {
        static final StreetPlan PLAN = load();

        private static StreetPlan load() {
            long t0 = System.nanoTime();
            byte[] baked = com.nirncraft.bake.Bake.load(StreetPlanBuilder.BAKE_NAME);
            if (baked != null) {
                try {
                    StreetPlan p = read(baked);
                    NirnCraft.LOGGER.info("[streetplan] loaded the baked street plan: {} settlements, {} nodes, {} edges, {} doors, {} plots ({} ms)",
                            p.settlements(), p.nodes(), p.edges(), p.doors(), p.plots(), (System.nanoTime() - t0) / 1_000_000);
                    return p;
                } catch (IOException | RuntimeException e) {
                    NirnCraft.LOGGER.warn("[streetplan] the baked street plan is unreadable; planning live", e);
                }
            }
            try {
                StreetPlan p = read(StreetPlanBuilder.computeLive());
                NirnCraft.LOGGER.info("[streetplan] planned live: {} settlements, {} doors ({} ms)", p.settlements(), p.doors(),
                        (System.nanoTime() - t0) / 1_000_000);
                return p;
            } catch (IOException | RuntimeException e) {
                NirnCraft.LOGGER.error("[streetplan] planning failed; no street plan (named settlements are planned at runtime)", e);
                return empty();
            }
        }
    }

    /** The plan for this build (baked, else computed live on first use: call {@link #warm} early). */
    public static StreetPlan get() {
        return Holder.PLAN;
    }

    public static void warm() {
        if (Holder.PLAN == null) throw new IllegalStateException();
    }
}

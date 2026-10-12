package com.skycraft.worldplan;

import com.skycraft.Skycraft;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * The baked road plan (pipeline stages S3 + S4, see {@code docs/WORLDGEN-PIPELINE.md}): every road, lane, gate lane
 * and trail of the planned network as a polyline about {@link RoadPlanBuilder#SPACING} blocks apart, with its class
 * and width, a per-vertex road height (the vertical profile, within the class's grade cap) and a span type per vertex
 * (normal, cut, fill, bridge, culvert, ford, switchback, stairs, tunnel), plus the site pads of every place. It depends
 * only on the authored map and the places, never on the world seed, so it is computed at build time
 * ({@code tools/bake/bake.ps1}, bake name {@code roadplan}) and loaded at startup.
 *
 * <p>Immutable after loading: every read is thread-safe. {@link #at} answers "which road is nearest to this column,
 * and how high is it there" from a spatial index of 64-block buckets, allocation-free.</p>
 *
 * <p>A link may consist of several segments: where it meets another road the shared part is cut away and the piece
 * ends on that road (a junction); {@link #segJoinA}/{@link #segJoinB} name the segment it joins (or a site, or
 * nothing).</p>
 */
public final class RoadPlan {
    public static final int MAGIC = 0x52504c4e; // "RPLN"
    public static final int VERSION = 1;

    // span types, one per vertex (keep in step with tools/qa/roadplan_lint.py)
    public static final byte NORMAL = 0, CUT = 1, FILL = 2, BRIDGE = 3, CULVERT = 4, FORD = 5, SWITCHBACK = 6, STAIRS = 7, TUNNEL = 8;
    public static final String[] SPAN_NAMES = {"normal", "cut", "fill", "bridge", "culvert", "ford", "switchback", "stairs", "tunnel"};
    /** Road classes (as {@code com.skycraft.roads.Road}): highway, road, lane (tracks to farms, gate lanes), trail. */
    public static final String[] CLASS_NAMES = {"highway", "road", "lane", "trail"};
    /** A segment end that joins nothing / a site pad (else the id of the segment it joins). */
    public static final int JOIN_NONE = -1, JOIN_SITE = -2;
    /** Link status. */
    public static final byte LINK_OK = 0, LINK_FAILED = 1, LINK_MERGED = 2;

    /** Spatial index bucket (blocks) and how far from a road {@link #at} still answers. */
    public static final int BUCKET_SHIFT = 6;
    public static final float REACH = 32f;

    // ---- sites (S3): every place with its pad
    public final String[] siteId;
    public final byte[] siteType;
    public final int[] siteX, siteZ, siteRadius;
    public final float[] sitePad;

    // ---- links
    public final String[] linkA, linkB, linkName;
    public final byte[] linkKind, linkStatus;
    public final int[] linkBridge;

    // ---- segments
    public final int[] segLink, segStart, segEnd, segJoinA, segJoinB;
    public final byte[] segKind;
    /** Half width of the road bed (blocks). */
    public final float[] segHalfWidth;
    /** Spans per segment: {@code spanOff[s]..spanOff[s+1]} index the span arrays. */
    public final int[] spanOff;
    public final byte[] spanType;
    /** Span vertex range (global vertex indices, inclusive). */
    public final int[] spanFrom, spanTo;
    /** Bridge deck height (highest profile point; NaN for other spans). */
    public final float[] spanDeck;
    /** Pier positions of a bridge span: arc distances from its first vertex, {@code pierOff[k]..pierOff[k+1]}. */
    public final int[] pierOff;
    public final float[] pierAt;

    // ---- vertices (global; a segment owns segStart..segEnd-1)
    public final float[] vx, vz, vh, vg, vw, varc;
    public final byte[] vspan;
    public final int[] vseg;

    // ---- index: bucket key -> edges (global index of an edge's first vertex)
    private final Long2IntOpenHashMap bucketOf = new Long2IntOpenHashMap();
    private final int[] bucketOff;
    private final int[] bucketEdges;
    private final float[] edgeInvLen2;

    /** One answer of {@link #at}; reuse an instance per thread. */
    public static final class Hit {
        /** Segment, its link and class, and the road bed half width. */
        public int segment = -1, link = -1, vertex = -1;
        public byte kind, span;
        public float halfWidth;
        /** Horizontal distance from the centre line (blocks), road height at the projection, and the macro ground. */
        public float distance, height, ground;
        /** Water surface at the projection (NaN where dry). */
        public float water;
        /** Position of the projection along the edge (0..1) and along the segment (blocks). */
        public float t, along;
        /** Unit direction of the road at the projection. */
        public float dirX, dirZ;

        void clear() {
            segment = -1;
            link = -1;
            vertex = -1;
            distance = Float.MAX_VALUE;
        }
    }

    RoadPlan(Builder b) {
        siteId = b.siteId;
        siteType = b.siteType;
        siteX = b.siteX;
        siteZ = b.siteZ;
        siteRadius = b.siteRadius;
        sitePad = b.sitePad;
        linkA = b.linkA;
        linkB = b.linkB;
        linkName = b.linkName;
        linkKind = b.linkKind;
        linkStatus = b.linkStatus;
        linkBridge = b.linkBridge;
        segLink = b.segLink;
        segStart = b.segStart;
        segEnd = b.segEnd;
        segJoinA = b.segJoinA;
        segJoinB = b.segJoinB;
        segKind = b.segKind;
        segHalfWidth = b.segHalfWidth;
        spanOff = b.spanOff;
        spanType = b.spanType;
        spanFrom = b.spanFrom;
        spanTo = b.spanTo;
        spanDeck = b.spanDeck;
        pierOff = b.pierOff;
        pierAt = b.pierAt;
        vx = b.vx;
        vz = b.vz;
        vh = b.vh;
        vg = b.vg;
        vw = b.vw;
        vspan = b.vspan;
        int n = vx.length;
        vseg = new int[n];
        varc = new float[n];
        for (int s = 0; s < segStart.length; s++) {
            for (int i = segStart[s]; i < segEnd[s]; i++) {
                vseg[i] = s;
                varc[i] = i == segStart[s] ? 0f : varc[i - 1] + (float) Math.hypot(vx[i] - vx[i - 1], vz[i] - vz[i - 1]);
            }
        }
        // index: every edge goes into each bucket its box (grown by REACH) touches
        edgeInvLen2 = new float[n];
        Long2ObjectOpenHashMap<IntArrayList> tmp = new Long2ObjectOpenHashMap<>();
        java.util.ArrayList<Long> order = new java.util.ArrayList<>();
        for (int s = 0; s < segStart.length; s++) {
            for (int i = segStart[s]; i + 1 < segEnd[s]; i++) {
                float dx = vx[i + 1] - vx[i], dz = vz[i + 1] - vz[i];
                float l2 = dx * dx + dz * dz;
                edgeInvLen2[i] = l2 < 1e-9f ? 0f : 1f / l2;
                int x0 = (int) Math.floor(Math.min(vx[i], vx[i + 1]) - REACH) >> BUCKET_SHIFT;
                int x1 = (int) Math.floor(Math.max(vx[i], vx[i + 1]) + REACH) >> BUCKET_SHIFT;
                int z0 = (int) Math.floor(Math.min(vz[i], vz[i + 1]) - REACH) >> BUCKET_SHIFT;
                int z1 = (int) Math.floor(Math.max(vz[i], vz[i + 1]) + REACH) >> BUCKET_SHIFT;
                for (int bx = x0; bx <= x1; bx++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        long k = key(bx, bz);
                        IntArrayList l = tmp.get(k);
                        if (l == null) {
                            l = new IntArrayList(8);
                            tmp.put(k, l);
                            order.add(k);
                        }
                        l.add(i);
                    }
                }
            }
        }
        bucketOff = new int[order.size() + 1];
        int total = 0;
        for (Long k : order) total += tmp.get(k.longValue()).size();
        bucketEdges = new int[total];
        bucketOf.defaultReturnValue(-1);
        int p = 0;
        for (int k = 0; k < order.size(); k++) {
            long key = order.get(k);
            IntArrayList l = tmp.get(key);
            bucketOf.put(key, k);
            bucketOff[k] = p;
            l.getElements(0, bucketEdges, p, l.size());
            p += l.size();
        }
        bucketOff[order.size()] = p;
    }

    private static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xffffffffL);
    }

    // ------------------------------------------------------------------ queries

    public int sites() {
        return siteId.length;
    }

    public int links() {
        return linkA.length;
    }

    public int segments() {
        return segStart.length;
    }

    public int vertices() {
        return vx.length;
    }

    public int buckets() {
        return bucketOff.length - 1;
    }

    /**
     * The road nearest to column (x, z) within {@link #REACH} blocks: fills {@code out} and returns true, or returns
     * false (and {@code out.segment == -1}) when no planned road is that close. Allocation-free; thread-safe.
     */
    public boolean at(double x, double z, Hit out) {
        out.clear();
        int k = bucketOf.get(key((int) Math.floor(x) >> BUCKET_SHIFT, (int) Math.floor(z) >> BUCKET_SHIFT));
        if (k < 0) return false;
        float px = (float) x, pz = (float) z;
        float best = REACH * REACH;
        int bestEdge = -1;
        float bestT = 0;
        for (int j = bucketOff[k], end = bucketOff[k + 1]; j < end; j++) {
            int i = bucketEdges[j];
            float ax = vx[i], az = vz[i];
            float dx = vx[i + 1] - ax, dz = vz[i + 1] - az;
            float t = ((px - ax) * dx + (pz - az) * dz) * edgeInvLen2[i];
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            float qx = ax + dx * t - px, qz = az + dz * t - pz;
            float d2 = qx * qx + qz * qz;
            if (d2 < best) {
                best = d2;
                bestEdge = i;
                bestT = t;
            }
        }
        if (bestEdge < 0) return false;
        int i = bestEdge, s = vseg[i];
        out.segment = s;
        out.link = segLink[s];
        out.kind = segKind[s];
        out.halfWidth = segHalfWidth[s];
        out.vertex = i;
        out.t = bestT;
        out.distance = (float) Math.sqrt(best);
        out.height = vh[i] + (vh[i + 1] - vh[i]) * bestT;
        out.ground = vg[i] + (vg[i + 1] - vg[i]) * bestT;
        float w0 = vw[i], w1 = vw[i + 1];
        out.water = Float.isNaN(w0) ? w1 : Float.isNaN(w1) ? w0 : w0 + (w1 - w0) * bestT;
        out.span = vspan[bestT < 0.5f ? i : i + 1];
        out.along = varc[i] + (varc[i + 1] - varc[i]) * bestT;
        float dx = vx[i + 1] - vx[i], dz = vz[i + 1] - vz[i];
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        out.dirX = len < 1e-6f ? 1f : dx / len;
        out.dirZ = len < 1e-6f ? 0f : dz / len;
        return true;
    }

    /**
     * P2: the exact bed height (fractional profile height, the terrain's height target) of the road at column (x, z),
     * or NaN when the column is not within the road bed (half width + 1) of any planned road. The terrain's top solid
     * block there is {@code floor(bedHeight)}; the half step (fraction >= 0.5) is for P3's slabs. Allocation-free and
     * thread-safe. Richer lookups (spans, tunnel mouths, pads): {@link PlanGround}.
     */
    public static double bedHeight(double x, double z) {
        return PlanGround.bedHeight(get(), (int) Math.floor(x), (int) Math.floor(z));
    }

    /** Span index of segment {@code s} containing global vertex {@code v}, or -1. */
    public int spanAt(int s, int v) {
        for (int k = spanOff[s]; k < spanOff[s + 1]; k++) if (v >= spanFrom[k] && v <= spanTo[k]) return k;
        return -1;
    }

    /** Total centre-line length (blocks) of the segments of class {@code kind} (or all, kind < 0). */
    public double length(int kind) {
        double sum = 0;
        for (int s = 0; s < segStart.length; s++) if (kind < 0 || segKind[s] == kind) sum += varc[segEnd[s] - 1];
        return sum;
    }

    // ------------------------------------------------------------------ serialisation

    private static short q16(float v) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, Math.round(v * 16f)));
    }

    /** Quantises a builder's values exactly as {@link #write}/{@link #read} round-trips them. */
    static float quant(float v) {
        return Float.isNaN(v) ? Float.NaN : q16(v) / 16f;
    }

    public byte[] write() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(siteId.length);
            for (int i = 0; i < siteId.length; i++) {
                out.writeUTF(siteId[i]);
                out.writeByte(siteType[i]);
                out.writeInt(siteX[i]);
                out.writeInt(siteZ[i]);
                out.writeShort(siteRadius[i]);
                out.writeShort(q16(sitePad[i]));
            }
            out.writeInt(linkA.length);
            for (int i = 0; i < linkA.length; i++) {
                out.writeUTF(linkA[i]);
                out.writeUTF(linkB[i]);
                out.writeUTF(linkName[i]);
                out.writeByte(linkKind[i]);
                out.writeByte(linkStatus[i]);
                out.writeShort(linkBridge[i]);
            }
            out.writeInt(segStart.length);
            for (int s = 0; s < segStart.length; s++) {
                out.writeInt(segLink[s]);
                out.writeByte(segKind[s]);
                out.writeByte(Math.round(segHalfWidth[s] * 4f));
                out.writeInt(segJoinA[s]);
                out.writeInt(segJoinB[s]);
                int a = segStart[s], n = segEnd[s] - a;
                out.writeInt(n);
                int lx = 0, lz = 0;
                for (int i = 0; i < n; i++) {
                    int qx = Math.round(vx[a + i] * 16f), qz = Math.round(vz[a + i] * 16f);
                    if (i == 0) {
                        out.writeInt(qx);
                        out.writeInt(qz);
                    } else {
                        out.writeShort(qx - lx);
                        out.writeShort(qz - lz);
                    }
                    lx = qx;
                    lz = qz;
                }
                for (int i = 0; i < n; i++) out.writeShort(q16(vh[a + i]));
                for (int i = 0; i < n; i++) out.writeShort(q16(vg[a + i]));
                for (int i = 0; i < n; i++) out.writeShort(Float.isNaN(vw[a + i]) ? Short.MIN_VALUE : q16(vw[a + i]));
                for (int i = 0; i < n; i++) out.writeByte(vspan[a + i]);
                out.writeShort(spanOff[s + 1] - spanOff[s]);
                for (int k = spanOff[s]; k < spanOff[s + 1]; k++) {
                    out.writeByte(spanType[k]);
                    out.writeInt(spanFrom[k] - a);
                    out.writeInt(spanTo[k] - a);
                    out.writeShort(Float.isNaN(spanDeck[k]) ? Short.MIN_VALUE : q16(spanDeck[k]));
                    out.writeShort(pierOff[k + 1] - pierOff[k]);
                    for (int p = pierOff[k]; p < pierOff[k + 1]; p++) out.writeShort(q16(pierAt[p]));
                }
            }
        }
        return bo.toByteArray();
    }

    public static RoadPlan read(byte[] data) throws IOException {
        Builder b = new Builder();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("not a road plan");
            int version = in.readInt();
            if (version != VERSION) throw new IOException("road plan version " + version + ", expected " + VERSION);
            int ns = in.readInt();
            b.sites(ns);
            for (int i = 0; i < ns; i++) {
                b.siteId[i] = in.readUTF();
                b.siteType[i] = in.readByte();
                b.siteX[i] = in.readInt();
                b.siteZ[i] = in.readInt();
                b.siteRadius[i] = in.readShort();
                b.sitePad[i] = in.readShort() / 16f;
            }
            int nl = in.readInt();
            b.links(nl);
            for (int i = 0; i < nl; i++) {
                b.linkA[i] = in.readUTF();
                b.linkB[i] = in.readUTF();
                b.linkName[i] = in.readUTF();
                b.linkKind[i] = in.readByte();
                b.linkStatus[i] = in.readByte();
                b.linkBridge[i] = in.readShort();
            }
            int nseg = in.readInt();
            b.segs(nseg);
            FloatList x = new FloatList(), z = new FloatList(), h = new FloatList(), g = new FloatList(), w = new FloatList();
            it.unimi.dsi.fastutil.bytes.ByteArrayList span = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
            it.unimi.dsi.fastutil.bytes.ByteArrayList st = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
            IntArrayList sf = new IntArrayList(), sto = new IntArrayList(), soff = new IntArrayList(), poff = new IntArrayList();
            FloatList deck = new FloatList(), piers = new FloatList();
            for (int s = 0; s < nseg; s++) {
                b.segLink[s] = in.readInt();
                b.segKind[s] = in.readByte();
                b.segHalfWidth[s] = in.readUnsignedByte() / 4f;
                b.segJoinA[s] = in.readInt();
                b.segJoinB[s] = in.readInt();
                int n = in.readInt(), a = x.size();
                b.segStart[s] = a;
                b.segEnd[s] = a + n;
                int qx = 0, qz = 0;
                for (int i = 0; i < n; i++) {
                    if (i == 0) {
                        qx = in.readInt();
                        qz = in.readInt();
                    } else {
                        qx += in.readShort();
                        qz += in.readShort();
                    }
                    x.add(qx / 16f);
                    z.add(qz / 16f);
                }
                for (int i = 0; i < n; i++) h.add(in.readShort() / 16f);
                for (int i = 0; i < n; i++) g.add(in.readShort() / 16f);
                for (int i = 0; i < n; i++) {
                    short v = in.readShort();
                    w.add(v == Short.MIN_VALUE ? Float.NaN : v / 16f);
                }
                for (int i = 0; i < n; i++) span.add(in.readByte());
                int k = in.readShort();
                soff.add(st.size());
                for (int j = 0; j < k; j++) {
                    st.add(in.readByte());
                    sf.add(a + in.readInt());
                    sto.add(a + in.readInt());
                    short d = in.readShort();
                    deck.add(d == Short.MIN_VALUE ? Float.NaN : d / 16f);
                    int np = in.readShort();
                    poff.add(piers.size());
                    for (int p = 0; p < np; p++) piers.add(in.readShort() / 16f);
                }
            }
            soff.add(st.size());
            poff.add(piers.size());
            b.vx = x.toArray();
            b.vz = z.toArray();
            b.vh = h.toArray();
            b.vg = g.toArray();
            b.vw = w.toArray();
            b.vspan = span.toByteArray();
            b.spanOff = soff.toIntArray();
            b.spanType = st.toByteArray();
            b.spanFrom = sf.toIntArray();
            b.spanTo = sto.toIntArray();
            b.spanDeck = deck.toArray();
            b.pierOff = poff.toIntArray();
            b.pierAt = piers.toArray();
        }
        return new RoadPlan(b);
    }

    /** Raw arrays, filled by {@link #read} or {@link RoadPlanBuilder}. */
    static final class Builder {
        String[] siteId, linkA, linkB, linkName;
        byte[] siteType, linkKind, linkStatus, segKind, spanType, vspan;
        int[] siteX, siteZ, siteRadius, linkBridge, segLink, segStart, segEnd, segJoinA, segJoinB, spanOff, spanFrom, spanTo, pierOff;
        float[] sitePad, segHalfWidth, spanDeck, pierAt, vx, vz, vh, vg, vw;

        void sites(int n) {
            siteId = new String[n];
            siteType = new byte[n];
            siteX = new int[n];
            siteZ = new int[n];
            siteRadius = new int[n];
            sitePad = new float[n];
        }

        void links(int n) {
            linkA = new String[n];
            linkB = new String[n];
            linkName = new String[n];
            linkKind = new byte[n];
            linkStatus = new byte[n];
            linkBridge = new int[n];
        }

        void segs(int n) {
            segLink = new int[n];
            segStart = new int[n];
            segEnd = new int[n];
            segJoinA = new int[n];
            segJoinB = new int[n];
            segKind = new byte[n];
            segHalfWidth = new float[n];
        }
    }

    /** A growable float array (fastutil's FloatArrayList without the import churn). */
    static final class FloatList {
        float[] a = new float[64];
        int n;

        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        float get(int i) {
            return a[i];
        }

        int size() {
            return n;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }

    // ------------------------------------------------------------------ the shipped plan

    private static final class Holder {
        static final RoadPlan PLAN = load();

        private static RoadPlan load() {
            long t0 = System.nanoTime();
            byte[] baked = com.skycraft.bake.Bake.load(RoadPlanBuilder.BAKE_NAME);
            if (baked != null) {
                try {
                    RoadPlan p = read(baked);
                    Skycraft.LOGGER.info("[roadplan] loaded the baked road plan: {} links, {} segments, {} vertices, {} buckets ({} ms)",
                            p.links(), p.segments(), p.vertices(), p.buckets(), (System.nanoTime() - t0) / 1_000_000);
                    return p;
                } catch (IOException | RuntimeException e) {
                    Skycraft.LOGGER.warn("[roadplan] the baked road plan is unreadable; planning live", e);
                }
            }
            try {
                RoadPlan p = read(RoadPlanBuilder.computeLive());
                Skycraft.LOGGER.info("[roadplan] planned live: {} links, {} segments ({} ms)", p.links(), p.segments(),
                        (System.nanoTime() - t0) / 1_000_000);
                return p;
            } catch (IOException | RuntimeException e) {
                Skycraft.LOGGER.error("[roadplan] planning failed; no road plan", e);
                return empty();
            }
        }
    }

    /** The plan for this build (baked, else computed live on first use: call {@link #warm} early). */
    public static RoadPlan get() {
        return Holder.PLAN;
    }

    public static void warm() {
        if (Holder.PLAN == null) throw new IllegalStateException();
    }

    static RoadPlan empty() {
        Builder b = new Builder();
        b.sites(0);
        b.links(0);
        b.segs(0);
        b.spanOff = new int[]{0};
        b.spanType = new byte[0];
        b.spanFrom = new int[0];
        b.spanTo = new int[0];
        b.spanDeck = new float[0];
        b.pierOff = new int[]{0};
        b.pierAt = new float[0];
        b.vx = b.vz = b.vh = b.vg = b.vw = new float[0];
        b.vspan = new byte[0];
        return new RoadPlan(b);
    }

    /** Segments of a link, in order along it. */
    public IntArrayList segmentsOf(int link) {
        IntArrayList out = new IntArrayList();
        for (int s = 0; s < segLink.length; s++) if (segLink[s] == link) out.add(s);
        return out;
    }

    static List<String> classNames() {
        return List.of(CLASS_NAMES);
    }
}

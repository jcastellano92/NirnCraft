package com.nirncraft.atlas;

import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielBiomeSource;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.biome.Palettes;
import com.nirncraft.tamriel.terrain.Hydrology;
import com.nirncraft.tamriel.terrain.MapRaster;
import com.nirncraft.tamriel.terrain.OceanIsles;
import com.nirncraft.worldplan.PlanGround;
import com.nirncraft.worldplan.RoadPlan;
import com.nirncraft.worldplan.street.StreetPlan;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The map of Nirn as the client generator's kernels read it (atlas_map.slang, docs/ATLAS-CLIENT-GENERATION.md): the
 * Tamriel raster's channels and regions, the hydrology's waterfalls, the sharp peaks, the fixed foreign lands and the
 * regions' terrain, packed into one word array uploaded once (atlas_gen_set_map). Everything is the same data the Java
 * map reads (taken from its own loaded objects), so the kernels' TamrielMap.sample matches it bit for bit. Built once
 * per process: the map does not depend on the world seed.
 *
 * <p>The page passes' data follows (atlas_page.slang): the ocean isles of every cell of the fixed world (OceanIsles'
 * own cellIsle), the authored road plan with PlanGround's edge and pad indices, the street plan's pads, PlanGround's
 * and the terrain's constants, and the biome palettes resolved to a program's biome ids (the only part that depends on
 * the program, appended per call).</p>
 */
final class AtlasGenMap {
    static final int MAGIC = 0x50414D41;  // "AMAP"
    static final int VERSION = 1;
    // header: offsets of the sections (atlas_map.slang keeps the same numbers)
    static final int H_RASTER = 2, H_REGIONS = 3, H_HYDRO = 4, H_PEAKS = 5, H_LANDS = 6, H_TERRAIN = 7, H_ISLES = 8,
            H_ROADS = 9, H_STREETS = 10, H_PAL = 11, H_CONSTS = 12, H_FAR = 13;
    static final int HEADER = 32;
    static final int BIOME_NONE = 0xFFFF;

    private static volatile int[] built;

    private AtlasGenMap() {}

    /** The seed-independent part (cached). */
    static int[] words() {
        int[] w = built;
        if (w == null) {
            synchronized (AtlasGenMap.class) {
                w = built;
                if (w == null) built = w = build();
            }
        }
        return w;
    }

    /**
     * The whole map for a program: the cached part, then the palettes in that program's biome ids. Throws when the
     * biome source is not Tamriel's (or in its test modes): the pages then stay in Java.
     */
    static int[] words(AtlasGenProgram prog, BiomeSource biomes) {
        int[] base = words();
        Words w = new Words();
        w.a = java.util.Arrays.copyOf(base, base.length + (1 << 16));
        w.n = base.length;
        palettes(w, prog, biomes);
        far(w, prog);
        return w.done();
    }

    /** A growing word list. */
    private static final class Words {
        int[] a = new int[1 << 20];
        int n;

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        void f(float v) {
            add(Float.floatToRawIntBits(v));
        }

        void d(double v) {
            long b = Double.doubleToRawLongBits(v);
            add((int) b);
            add((int) (b >>> 32));
        }

        void set(int i, int v) {
            a[i] = v;
        }

        int[] done() {
            return java.util.Arrays.copyOf(a, n);
        }
    }

    private static int[] build() {
        long tStart = System.nanoTime();
        MapRaster r = MapRaster.get();
        Words w = new Words();
        for (int i = 0; i < HEADER; i++) w.add(0);
        w.set(0, MAGIC);
        w.set(1, VERSION);

        // ---- the raster: W x H cells of CH interleaved floats, then the region and blend partner bytes
        float[] data = (float[]) AtlasGenProgram.get(MapRaster.class, r, "data");
        byte[] region = (byte[]) AtlasGenProgram.get(MapRaster.class, r, "region");
        byte[] blend = (byte[]) AtlasGenProgram.get(MapRaster.class, r, "blendRegion");
        w.set(H_RASTER, w.n);
        for (float v : data) w.f(v);
        w.set(H_REGIONS, w.n);
        int cells = region.length;
        for (int c = 0; c < cells; c += 4) {
            int v = 0;
            for (int k = 0; k < 4 && c + k < cells; k++) v |= (region[c + k] & 0xFF) << (8 * k);
            w.add(v);
        }
        for (int c = 0; c < cells; c += 4) {
            int v = 0;
            for (int k = 0; k < 4 && c + k < cells; k++) v |= (blend[c + k] & 0xFF) << (8 * k);
            w.add(v);
        }

        // ---- hydrology: the waterfalls, and a dense 64-block grid over the raster listing the falls whose influence
        // covers each cell (Hydrology's own grid, same order)
        Hydrology.Fall[] falls = Hydrology.get().falls();
        w.set(H_HYDRO, w.n);
        int g0x = Math.floorDiv(TamrielMap.MIN_X, 64), g0z = Math.floorDiv(TamrielMap.MIN_Z, 64);
        int gw = Math.floorDiv(TamrielMap.MAX_X - 1, 64) - g0x + 1, gh = Math.floorDiv(TamrielMap.MAX_Z - 1, 64) - g0z + 1;
        w.add(falls.length);
        w.add(g0x);
        w.add(g0z);
        w.add(gw);
        w.add(gh);
        int fallsAt = w.n + 3;
        w.add(fallsAt);
        int gridAt = fallsAt + falls.length * 8;
        w.add(gridAt);
        w.add(0);  // pad
        for (Hydrology.Fall f : falls) {
            w.add(f.x());
            w.add(f.z());
            w.f(f.ux());
            w.f(f.uz());
            w.f(f.up());
            w.f(f.down());
            w.f(f.hw());
            w.add(0);
        }
        @SuppressWarnings("unchecked")
        List<Integer>[] lists = new List[gw * gh];
        for (int i = 0; i < falls.length; i++) {
            Hydrology.Fall f = falls[i];
            // Hydrology's constructor: the box of REACH 44 + hw + 20 around the lip
            int c0x = Math.floorDiv((int) (f.x() - 44f - f.hw() - 20), 64), c1x = Math.floorDiv((int) (f.x() + 44f + f.hw() + 20), 64);
            int c0z = Math.floorDiv((int) (f.z() - 44f - f.hw() - 20), 64), c1z = Math.floorDiv((int) (f.z() + 44f + f.hw() + 20), 64);
            for (int cx = c0x; cx <= c1x; cx++)
                for (int cz = c0z; cz <= c1z; cz++) {
                    int gx = cx - g0x, gz = cz - g0z;
                    if (gx < 0 || gz < 0 || gx >= gw || gz >= gh) continue;
                    List<Integer> l = lists[gz * gw + gx];
                    if (l == null) lists[gz * gw + gx] = l = new ArrayList<>();
                    l.add(i);
                }
        }
        // offsets (cells + 1), then the indices
        int off = 0;
        int[] offs = new int[gw * gh + 1];
        for (int c = 0; c < gw * gh; c++) {
            offs[c] = off;
            off += lists[c] == null ? 0 : lists[c].size();
        }
        offs[gw * gh] = off;
        for (int o : offs) w.add(o);
        for (List<Integer> l : lists) if (l != null) for (int i : l) w.add(i);

        // ---- the sharp peaks (MapPeaks): the union box, then each peak's record
        w.set(H_PEAKS, w.n);
        Object[] peaks = (Object[]) staticField("com.nirncraft.tamriel.terrain.MapPeaks", "PEAKS");
        Class<?> pc = peaks.getClass().getComponentType();
        w.add(peaks.length);
        w.add((Integer) staticField("com.nirncraft.tamriel.terrain.MapPeaks", "MIN_X"));
        w.add((Integer) staticField("com.nirncraft.tamriel.terrain.MapPeaks", "MAX_X"));
        w.add((Integer) staticField("com.nirncraft.tamriel.terrain.MapPeaks", "MIN_Z"));
        w.add((Integer) staticField("com.nirncraft.tamriel.terrain.MapPeaks", "MAX_Z"));
        int peakIdx = w.n;
        for (int i = 0; i < peaks.length; i++) w.add(0);
        for (int i = 0; i < peaks.length; i++) {
            Object p = peaks[i];
            w.set(peakIdx + i, w.n);
            for (String n : new String[]{"x", "z", "height", "shape", "power", "hornHeight", "hornRadius", "calderaRadius",
                    "calderaDepth", "jag"})
                w.d((Double) AtlasGenProgram.get(pc, p, n));
            for (String n : new String[]{"minX", "maxX", "minZ", "maxZ"}) w.add((Integer) AtlasGenProgram.get(pc, p, n));
            float[] reach = (float[]) AtlasGenProgram.get(pc, p, "reach");
            if (reach.length != 720) throw new IllegalStateException("MapPeaks bins changed");
            for (float v : reach) w.f(v);
            double[][] shelves = (double[][]) AtlasGenProgram.get(pc, p, "shelves");
            w.add(shelves.length);
            for (double[] s : shelves) for (int k = 0; k < 4; k++) w.d(s[k]);
            double[][] clear = (double[][]) AtlasGenProgram.get(pc, p, "clear");
            w.add(clear.length);
            for (double[] s : clear) for (int k = 0; k < 3; k++) w.d(s[k]);
            double[][][] paths = (double[][][]) AtlasGenProgram.get(pc, p, "paths");
            w.add(paths.length);
            for (double[][] path : paths) {
                w.add(path.length);
                for (double[] pt : path) for (int k = 0; k < 3; k++) w.d(pt[k]);
            }
        }

        // ---- the fixed foreign lands (Beyond): in Beyond's order, which the kernels' fixedRegion relies on
        w.set(H_LANDS, w.n);
        Object[] lands = (Object[]) staticField("com.nirncraft.tamriel.terrain.Beyond", "LANDS");
        String[] order = {"atmora", "akavir", "tang_mo", "yokuda", "sunken_isles", "pyandonea", "thras"};
        if (lands.length != order.length) throw new IllegalStateException("Beyond's lands changed");
        w.add(lands.length);
        for (int i = 0; i < lands.length; i++) {
            Object l = lands[i];
            if (!order[i].equals(component(l, "name"))) throw new IllegalStateException("Beyond's lands changed order");
            w.add("ARCHIPELAGO".equals(component(l, "shape").toString()) ? 1 : 0);
            w.add((Integer) component(l, "seed"));
            w.f((Float) component(l, "mountains"));
            w.add(0);
            for (String n : new String[]{"cx", "cz", "rx", "rz", "islandScale", "reach"}) w.d((Double) component(l, n));
        }
        for (String n : new String[]{"R_ICE", "R_FOREST", "R_TSAESCI", "R_KAMAL", "R_TANGMO", "R_KAPOTUN", "R_YOKUDA",
                "R_SUNKEN", "R_PYANDONEA", "R_THRAS", "R_GHOSTS", "R_PADOMAIC", "R_ELTHERIC", "R_ABECEAN"}) {
            int ri = (Integer) staticField("com.nirncraft.tamriel.terrain.Beyond", n);
            if (ri < 0) throw new IllegalStateException("Beyond." + n + " names no region");
            w.add(ri);
        }
        w.add(r.loaded() ? 1 : 0);

        // ---- the regions' terrain parameters (Regions.terrain), then the default for unknown indices
        w.set(H_TERRAIN, w.n);
        int nr = Regions.count();
        w.add(nr);
        for (int i = -1; i < nr; i++) {
            Regions.Terrain t = i < 0 ? Regions.Terrain.DEFAULT : Regions.terrain(i);
            w.f(t.base());
            w.f(t.relief());
            w.f(t.rough());
            w.f(t.crag());
            w.f(t.dunes());
            w.f(t.wet());
            w.f(t.coast());
            w.add(0);
        }

        long t0 = System.nanoTime();
        isles(w);
        long t1 = System.nanoTime();
        roads(w);
        long t2 = System.nanoTime();
        streets(w);
        consts(w);
        long t3 = System.nanoTime();
        com.nirncraft.NirnCraft.LOGGER.info("atlas generator map: {} words (raster and peaks {} ms, isles {} ms, road plan {} ms, street plan {} ms)",
                w.n, (t0 - tStart) / 1_000_000, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
        return w.done();
    }

    // ---- OceanIsles: every cell of the fixed world's box, from OceanIsles.cellIsle itself ----
    private static void isles(Words w) {
        int minX = TamrielMap.MIN_X, maxX = TamrielMap.MAX_X, minZ = TamrielMap.MIN_Z, maxZ = TamrielMap.MAX_Z;
        for (Object l : (Object[]) staticField("com.nirncraft.tamriel.terrain.Beyond", "LANDS")) {
            double cx = (Double) component(l, "cx"), cz = (Double) component(l, "cz"), reach = (Double) component(l, "reach");
            minX = Math.min(minX, (int) Math.floor(cx - reach));
            maxX = Math.max(maxX, (int) Math.ceil(cx + reach));
            minZ = Math.min(minZ, (int) Math.floor(cz - reach));
            maxZ = Math.max(maxZ, (int) Math.ceil(cz + reach));
        }
        int cell = OceanIsles.CELL;
        int cx0 = Math.floorDiv(minX, cell) - 1, cz0 = Math.floorDiv(minZ, cell) - 1;
        int gw = Math.floorDiv(maxX, cell) + 2 - cx0, gh = Math.floorDiv(maxZ, cell) + 2 - cz0;
        java.lang.reflect.Method unit;
        try {
            unit = OceanIsles.class.getDeclaredMethod("unit", long.class, int.class);
            unit.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OceanIsles.unit unavailable", e);
        }
        int[] grid = new int[gw * gh];
        List<OceanIsles.Isle> found = new ArrayList<>();
        for (int gz = 0; gz < gh; gz++)
            for (int gx = 0; gx < gw; gx++) {
                OceanIsles.Isle isle = OceanIsles.cellIsle(cx0 + gx, cz0 + gz);
                grid[gz * gw + gx] = isle == null ? -1 : found.size();
                if (isle != null) found.add(isle);
            }
        w.set(H_ISLES, w.n);
        int at = w.n;
        for (int i = 0; i < 6; i++) w.add(0);
        w.set(at, cx0);
        w.set(at + 1, cz0);
        w.set(at + 2, gw);
        w.set(at + 3, gh);
        w.set(at + 4, w.n);
        for (int g : grid) w.add(g);
        w.set(at + 5, w.n);
        try {
            for (OceanIsles.Isle i : found) {
                w.add(i.centerX());
                w.add(i.centerZ());
                w.add(i.siteX());
                w.add(i.siteZ());
                w.add(i.type());
                w.add(i.radius());
                w.f(i.peakAboveSea());
                w.add(0);
                for (int k = 8; k <= 10; k++) w.d((Double) unit.invoke(null, i.hash(), k));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OceanIsles.unit failed", e);
        }
    }

    /** (bx, bz, k) triples of a bucket map's keys, sorted by (bx, bz). */
    private static void keys(Words w, long[] keys, int[] ks) {
        Integer[] order = new Integer[keys.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int ax = (int) (keys[a] >> 32), bx = (int) (keys[b] >> 32);
            if (ax != bx) return Integer.compare(ax, bx);
            return Integer.compare((int) keys[a], (int) keys[b]);
        });
        for (int i : order) {
            w.add((int) (keys[i] >> 32));
            w.add((int) keys[i]);
            w.add(ks[i]);
        }
    }

    private static void ints(Words w, int[] a) {
        for (int v : a) w.add(v);
    }

    private static void floats(Words w, float[] a) {
        for (float v : a) w.f(v);
    }

    private static void bytes(Words w, byte[] a) {
        for (byte v : a) w.add(v);
    }

    private static void need(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("the page kernels' constant changed: " + what);
    }

    // ---- the road plan and PlanGround's indices (the authored plan; tile plans beyond Tamriel are not here) ----
    private static void roads(Words w) {
        need(PlanGround.RAMP == 8f && RoadPlan.BRIDGE == 3 && RoadPlan.FORD == 5 && RoadPlan.TUNNEL == 8, "RoadPlan spans");
        need((Integer) staticField(PlanGround.class.getName(), "K") == 4
                && (Integer) staticField(PlanGround.class.getName(), "BUCKET_SHIFT") == 5
                && (Integer) staticField(PlanGround.class.getName(), "SITE_SHIFT") == 8
                && (Byte) staticField(PlanGround.class.getName(), "TRAIL_KIND") == 3, "PlanGround's index");
        PlanGround g = PlanGround.get();
        RoadPlan p = (RoadPlan) AtlasGenProgram.get(PlanGround.class, g, "plan");
        Long2IntOpenHashMap bucketOf = (Long2IntOpenHashMap) AtlasGenProgram.get(PlanGround.class, g, "bucketOf");
        int[] bucketOff = (int[]) AtlasGenProgram.get(PlanGround.class, g, "bucketOff");
        int[] bucketEdges = (int[]) AtlasGenProgram.get(PlanGround.class, g, "bucketEdges");
        float[] invLen2 = (float[]) AtlasGenProgram.get(PlanGround.class, g, "invLen2");
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<int[]> siteGrid = (Long2ObjectOpenHashMap<int[]>) AtlasGenProgram.get(PlanGround.class, g, "siteGrid");
        w.set(H_ROADS, w.n);
        int at = w.n;
        for (int i = 0; i < 26; i++) w.add(0);
        long[] keys = new long[bucketOf.size()];
        int[] ks = new int[keys.length];
        int n = 0;
        for (var e : bucketOf.long2IntEntrySet()) {
            keys[n] = e.getLongKey();
            ks[n++] = e.getIntValue();
        }
        w.set(at, keys.length);
        w.set(at + 1, w.n);
        keys(w, keys, ks);
        w.set(at + 2, w.n);
        ints(w, bucketOff == null ? new int[1] : bucketOff);
        w.set(at + 3, w.n);
        ints(w, bucketEdges == null ? new int[0] : bucketEdges);
        w.set(at + 4, w.n);
        floats(w, p.vx);
        w.set(at + 5, w.n);
        floats(w, p.vz);
        w.set(at + 6, w.n);
        floats(w, p.vh);
        w.set(at + 7, w.n);
        floats(w, p.vg);
        w.set(at + 8, w.n);
        floats(w, p.varc);
        w.set(at + 9, w.n);
        bytes(w, p.vspan);
        w.set(at + 10, w.n);
        ints(w, p.vseg);
        w.set(at + 11, w.n);
        floats(w, invLen2);
        w.set(at + 12, w.n);
        floats(w, p.segHalfWidth);
        w.set(at + 13, w.n);
        bytes(w, p.segKind);
        w.set(at + 14, w.n);
        ints(w, p.spanOff);
        w.set(at + 15, w.n);
        ints(w, p.spanFrom);
        w.set(at + 16, w.n);
        ints(w, p.spanTo);
        // the site pads: PlanGround's grid of padded sites (each key's list in its own order)
        long[] sk = new long[siteGrid.size()];
        int[] sj = new int[sk.length];
        n = 0;
        for (var e : siteGrid.long2ObjectEntrySet()) {
            sk[n] = e.getLongKey();
            sj[n] = n;
            n++;
        }
        w.set(at + 17, sk.length);
        w.set(at + 18, w.n);
        keys(w, sk, sj);
        int[] off = new int[sk.length + 1];
        List<int[]> lists = new ArrayList<>();
        for (int i = 0; i < sk.length; i++) {
            int[] l = siteGrid.get(sk[i]);
            lists.add(l);
            off[i + 1] = off[i] + l.length;
        }
        w.set(at + 19, w.n);
        ints(w, off);
        w.set(at + 20, w.n);
        for (int[] l : lists) ints(w, l);
        w.set(at + 21, w.n);
        ints(w, p.siteX);
        w.set(at + 22, w.n);
        ints(w, p.siteZ);
        w.set(at + 23, w.n);
        ints(w, p.siteRadius);
        w.set(at + 24, w.n);
        floats(w, p.sitePad);
    }

    // ---- the street plan's pads (StreetPlan.pad) ----
    private static void streets(Words w) {
        need((Integer) staticField(StreetPlan.class.getName(), "BUCKET_SHIFT") == 4 && StreetPlan.PATH == 3
                && StreetPlan.STAIRS == 4 && StreetPlan.BRIDGE == 5 && StreetPlan.NO_PAD == Integer.MIN_VALUE, "StreetPlan");
        StreetPlan p = StreetPlan.get();
        w.set(H_STREETS, w.n);
        int at = w.n;
        for (int i = 0; i < 20; i++) w.add(0);
        if (p == null) return;
        Long2IntOpenHashMap bucketOf = (Long2IntOpenHashMap) AtlasGenProgram.get(StreetPlan.class, p, "bucketOf");
        long[] keys = new long[bucketOf.size()];
        int[] ks = new int[keys.length];
        int n = 0;
        for (var e : bucketOf.long2IntEntrySet()) {
            keys[n] = e.getLongKey();
            ks[n++] = e.getIntValue();
        }
        w.set(at, keys.length);
        w.set(at + 1, w.n);
        keys(w, keys, ks);
        String[] arrays = {"bucketPlotOff", "bucketPlots", "bucketEdgeOff", "bucketEdges"};
        for (int i = 0; i < 4; i++) {
            w.set(at + 2 + i, w.n);
            ints(w, (int[]) AtlasGenProgram.get(StreetPlan.class, p, arrays[i]));
        }
        int[][] plots = {p.pPad, p.pMinX, p.pMinZ, p.pMaxX, p.pMaxZ};
        for (int i = 0; i < 5; i++) {
            w.set(at + 6 + i, w.n);
            ints(w, plots[i]);
        }
        w.set(at + 11, w.n);
        bytes(w, p.eClass);
        w.set(at + 12, w.n);
        ints(w, p.ea);
        w.set(at + 13, w.n);
        ints(w, p.eb);
        w.set(at + 14, w.n);
        floats(w, p.nx);
        w.set(at + 15, w.n);
        floats(w, p.nz);
        w.set(at + 16, w.n);
        floats(w, p.nh);
        w.set(at + 17, w.n);
        floats(w, p.ew);
        w.set(at + 18, w.n);
        floats(w, (float[]) AtlasGenProgram.get(StreetPlan.class, p, "edgeInvLen2"));
    }

    // ---- PlanGround's, PlanTerrain's, StreetPlan's and TamrielHeight's constants ----
    private static void consts(Words w) {
        w.set(H_CONSTS, w.n);
        String pg = PlanGround.class.getName();
        for (String n : new String[]{"FLAT_PAD", "TRAIL_PAD", "SHOULDER_MIN", "SHOULDER_MAX", "OUTER", "RAMP", "EPS", "CALM"})
            w.f((Float) staticField(pg, n));
        w.f((Float) staticField("com.nirncraft.worldplan.PlanTerrain", "PAD_BLEND"));
        w.f(StreetPlan.PAD_BLEND);
        w.d(com.nirncraft.tamriel.terrain.TamrielHeight.MAX_SURFACE);
    }

    // ---- the palettes in a program's biome ids (TamrielBiomeSource, Palettes) ----
    private static final String[] SUBTERRANEAN = {"nordicKarstCaverns", "iceCaves", "dwemerCaverns", "falmerGlowCaves",
            "lavaCaves", "mushroomGrottos", "ayleidLimestoneCaverns", "valenwoodRootWarrens", "blackMarshHistCaverns",
            "elsweyrSandstoneCaverns", "summersetCrystalGrottoes", "yokudanSandVaults", "submergedSeaCaverns",
            "abyssalTrenchCaves"};

    private static void palettes(Words w, AtlasGenProgram prog, BiomeSource source) {
        if (!(source instanceof TamrielBiomeSource src) || source.getClass() != TamrielBiomeSource.class)
            throw new IllegalStateException("not Tamriel's biome source: " + source);
        if (AtlasGenProgram.get(TamrielBiomeSource.class, src, "forced") != null
                || (Boolean) AtlasGenProgram.get(TamrielBiomeSource.class, src, "grid"))
            throw new IllegalStateException("the biome source's test mode is on");
        Palettes.Compiled compiled = src.palettes();
        Map<Palettes.Palette, Integer> index = new IdentityHashMap<>();
        List<Palettes.Palette> list = new ArrayList<>();
        int nr = Regions.count();
        int[] regionPal = new int[nr], regionCave = new int[nr];
        for (int i = 0; i < nr; i++) {
            Region r = Regions.byIndex(i);
            regionPal[i] = index.computeIfAbsent(compiled.of(r), p -> {
                list.add(p);
                return list.size() - 1;
            });
            regionCave[i] = caveClass(r.id());
        }
        Region fb = Regions.fallback();
        int fallback = index.computeIfAbsent(compiled.of(fb), p -> {
            list.add(p);
            return list.size() - 1;
        });
        w.set(H_PAL, w.n);
        int at = w.n;
        for (int i = 0; i < 8; i++) w.add(0);
        w.set(at, nr);
        w.set(at + 1, w.n);
        for (int i = 0; i < nr; i++) {
            w.add(regionPal[i]);
            w.add(regionCave[i]);
        }
        w.set(at + 2, fallback);
        w.set(at + 3, caveClass(fb.id()));
        w.set(at + 4, w.n);
        for (String f : SUBTERRANEAN) {
            @SuppressWarnings("unchecked")
            Holder<Biome> h = (Holder<Biome>) AtlasGenProgram.get(TamrielBiomeSource.class, src, f);
            w.add(biomeId(prog, h));
        }
        w.set(at + 5, list.size());
        w.set(at + 6, w.n);
        int offs = w.n;
        for (int i = 0; i < list.size(); i++) w.add(0);
        Palettes.Context[] ctxs = Palettes.Context.values();
        if (ctxs.length != 12) throw new IllegalStateException("Palettes.Context changed");
        for (int pi = 0; pi < list.size(); pi++) {
            Palettes.Palette p = list.get(pi);
            int rec = w.n;
            w.set(offs + pi, rec);
            for (int i = 0; i < 42; i++) w.add(0);
            w.set(rec, p.uplandY);
            w.set(rec + 1, p.peakY);
            w.set(rec + 2, p.summitY);
            w.set(rec + 3, p.micro.length);
            double[] linear = (double[]) AtlasGenProgram.get(Palettes.Palette.class, p, "linear");
            double[] radial = (double[]) AtlasGenProgram.get(Palettes.Palette.class, p, "radial");
            w.set(rec + 5, linear != null ? 1 : 0);
            w.set(rec + 6, radial != null ? 1 : 0);
            for (int k = 0; linear != null && k < 6; k++) setD(w, rec + 20 + k * 2, linear[k]);
            for (int k = 0; radial != null && k < 5; k++) setD(w, rec + 32 + k * 2, radial[k]);
            for (Palettes.Context c : ctxs) {
                Palettes.Choices ch = p.get(c);
                w.set(rec + 8 + c.ordinal(), w.n);
                @SuppressWarnings("unchecked")
                Holder<Biome>[] bs = (Holder<Biome>[]) AtlasGenProgram.get(Palettes.Choices.class, ch, "biomes");
                float[] upper = (float[]) AtlasGenProgram.get(Palettes.Choices.class, ch, "upper");
                w.add(bs.length);
                for (int i = 0; i < bs.length; i++) {
                    w.f(upper[i]);
                    w.add(biomeId(prog, bs[i]));
                }
            }
            w.set(rec + 4, w.n);
            for (Palettes.MicroRule r : p.micro) {
                w.add(biomeId(prog, r.biome));
                for (float v : new float[]{r.elevLo, r.elevHi, r.slopeLo, r.slopeHi, r.rsLo, r.rsHi, r.heatLo, r.heatHi,
                        r.waterLo, r.waterHi, r.coastLo, r.coastHi, r.ridgeLo, r.ridgeHi, r.aspectLo, r.aspectHi})
                    w.f(v);
            }
        }
    }

    // ---- the far columns' extras (atlas_page.slang MH_FAR): freeze_top_layer's snow and ice (AtlasSurface.column,
    // AtlasSnowCover, Biome.coldEnoughToSnow) and the far roads (FarRoads.surface), in a program's block and biome ids ----
    static final int FAR_HEADER = 32, COVER_WORDS = 12, NOISE_WORDS = 68, LAYER_WORDS = NOISE_WORDS + 3;

    private static void far(Words w, AtlasGenProgram prog) {
        int at = w.n;
        w.set(H_FAR, at);
        for (int i = 0; i < FAR_HEADER; i++) w.add(0);
        w.set(at, blockId(prog, net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState()));
        w.set(at + 1, blockId(prog, net.minecraft.world.level.block.Blocks.ICE.defaultBlockState()));
        // the blocks snow lies on: isSolidRender, not ice or packed ice
        int nb = prog.blocks.size();
        int[] bits = new int[(nb + 31) >> 5];
        for (int i = 0; i < nb; i++) {
            net.minecraft.world.level.block.state.BlockState s = prog.blocks.get(i);
            if (s.isSolidRender() && !s.is(net.minecraft.world.level.block.Blocks.ICE)
                    && !s.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)) bits[i >> 5] |= 1 << (i & 31);
        }
        w.set(at + 2, nb);
        w.set(at + 3, w.n);
        ints(w, bits);
        // the biomes: base temperature, modifier (1 frozen), their ground cover (AtlasSnowCover.covers)
        int nbio = prog.biomes.size();
        w.set(at + 4, nbio);
        w.set(at + 5, w.n);
        int recs = w.n;
        for (int i = 0; i < nbio * 4; i++) w.add(0);
        for (int i = 0; i < nbio; i++) {
            Holder<Biome> h = prog.biomes.get(i);
            Biome b = h.value();
            w.set(recs + i * 4, Float.floatToRawIntBits(b.getBaseTemperature()));
            w.set(recs + i * 4 + 1, b.getModifiedClimateSettings().temperatureModifier() == Biome.TemperatureModifier.FROZEN ? 1 : 0);
            AtlasSnowCover.Cover[] covers = AtlasSnowCover.covers(h);
            w.set(recs + i * 4 + 2, w.n);
            w.set(recs + i * 4 + 3, covers.length);
            for (AtlasSnowCover.Cover c : covers) {
                int r = w.n;
                for (int k = 0; k < COVER_WORDS; k++) w.add(0);
                setD(w, r, c.perChunk());
                com.nirncraft.tamriel.biome.flora.DensityFilter d = c.density();
                if (d == null) continue;
                com.nirncraft.tamriel.biome.flora.FloraField f = d.field();
                w.set(r + 2, 1);
                w.set(r + 3, Float.floatToRawIntBits(d.density()));
                w.set(r + 4, Float.floatToRawIntBits(d.floor()));
                w.set(r + 5, f.salt());
                w.set(r + 6, Float.floatToRawIntBits(f.scale()));
                w.set(r + 7, Float.floatToRawIntBits(f.coverage()));
                w.set(r + 8, Float.floatToRawIntBits(f.softness()));
                w.set(r + 9, f.kind().ordinal());
                w.set(r + 10, f.mode().ordinal());
            }
        }
        need(com.nirncraft.tamriel.biome.flora.FloraField.Kind.WORLEY.ordinal() == 1
                && com.nirncraft.tamriel.biome.flora.FloraField.Mode.ANY.ordinal() == 3
                && com.nirncraft.tamriel.biome.flora.FloraField.Mode.EDGE.ordinal() == 2, "FloraField's kinds and modes");
        w.set(at + 6, (int) prog.seed);
        w.set(at + 7, (int) (prog.seed >>> 32));
        // Biome's temperature noises (SimplexNoise: F2, G2; TEMPERATURE_NOISE, BIOME_INFO_NOISE, FROZEN_TEMPERATURE_NOISE)
        String sn = net.minecraft.world.level.levelgen.synth.SimplexNoise.class.getName();
        setD(w, at + 8, (Double) staticField(sn, "F2"));
        setD(w, at + 10, (Double) staticField(sn, "G2"));
        w.set(at + 12, w.n);
        simplex(w, staticField(Biome.class.getName(), "TEMPERATURE_NOISE"));
        w.set(at + 13, w.n);
        simplex(w, Biome.BIOME_INFO_NOISE);
        w.set(at + 14, w.n);
        Object stack = Biome.FROZEN_TEMPERATURE_NOISE;
        if (stack.getClass() != net.minecraft.world.level.levelgen.synth.NoiseStack.class)
            throw new IllegalStateException("FROZEN_TEMPERATURE_NOISE: " + stack.getClass());
        Object[] layers = (Object[]) AtlasGenProgram.get(net.minecraft.world.level.levelgen.synth.NoiseStack.class, stack, "layers");
        w.add(layers.length);
        try {
            Class<?> lc = Class.forName(net.minecraft.world.level.levelgen.synth.NoiseStack.class.getName() + "$Layer");
            for (Object l : layers) {
                simplex(w, AtlasGenProgram.field(lc, "noise").get(l));
                w.d(AtlasGenProgram.field(lc, "frequency").getDouble(l));
                w.f(AtlasGenProgram.field(lc, "amplitude").getFloat(l));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NoiseStack.Layer unavailable", e);
        }
        // the far roads: culture per region (RoadStyle.cultureAt), the styles (FarRoads.gpuStyles), RoadPlan.at's index
        int nr = Regions.count();
        w.set(at + 15, nr);
        w.set(at + 16, w.n);
        for (int i = 0; i < nr; i++) w.add(com.nirncraft.tamriel.settlement.Culture.of(Regions.byIndex(i).culture()).ordinal());
        need(com.nirncraft.tamriel.settlement.Culture.NORD.ordinal() == 0
                && com.nirncraft.roads.FarRoads.STYLE_WORDS == 37, "far road styles");
        w.set(at + 17, w.n);
        ints(w, com.nirncraft.roads.FarRoads.gpuStyles(s -> blockId(prog, s)));
        w.set(at + 18, com.nirncraft.tamriel.settlement.Culture.VALUES.length);
        RoadPlan p = (RoadPlan) AtlasGenProgram.get(PlanGround.class, PlanGround.get(), "plan");
        need((Integer) staticField(RoadPlan.class.getName(), "BUCKET_SHIFT") == 6 && RoadPlan.REACH == 32f, "RoadPlan's index");
        if (p != null) {
            Long2IntOpenHashMap bucketOf = (Long2IntOpenHashMap) AtlasGenProgram.get(RoadPlan.class, p, "bucketOf");
            long[] keys = new long[bucketOf.size()];
            int[] ks = new int[keys.length];
            int n = 0;
            for (var e : bucketOf.long2IntEntrySet()) {
                keys[n] = e.getLongKey();
                ks[n++] = e.getIntValue();
            }
            w.set(at + 19, keys.length);
            w.set(at + 20, w.n);
            keys(w, keys, ks);
            w.set(at + 21, w.n);
            ints(w, (int[]) AtlasGenProgram.get(RoadPlan.class, p, "bucketOff"));
            w.set(at + 22, w.n);
            ints(w, (int[]) AtlasGenProgram.get(RoadPlan.class, p, "bucketEdges"));
            w.set(at + 23, w.n);
            floats(w, (float[]) AtlasGenProgram.get(RoadPlan.class, p, "edgeInvLen2"));
            w.set(at + 24, keys.length > 0 ? 1 : 0);
        }
    }

    /** A SimplexNoise: its permutation (4 a word), offsetX, offsetY (NOISE_WORDS). */
    private static void simplex(Words w, Object noise) {
        if (noise.getClass() != net.minecraft.world.level.levelgen.synth.SimplexNoise.class)
            throw new IllegalStateException("not a SimplexNoise: " + noise.getClass());
        Class<?> g = net.minecraft.world.level.levelgen.synth.GradientNoise.class;
        byte[] perms = (byte[]) AtlasGenProgram.get(g, noise, "perms");
        int[] p = new int[64];
        for (int i = 0; i < 256; i++) p[i >> 2] |= (perms[i] & 255) << ((i & 3) * 8);
        ints(w, p);
        w.d((Double) AtlasGenProgram.get(g, noise, "offsetX"));
        w.d((Double) AtlasGenProgram.get(g, noise, "offsetY"));
    }

    private static int blockId(AtlasGenProgram prog, net.minecraft.world.level.block.state.BlockState s) {
        Integer id = prog.blockIds.get(s);
        if (id == null) throw new IllegalStateException("a far block not in the program: " + s);
        return id;
    }

    private static int biomeId(AtlasGenProgram prog, Holder<Biome> h) {
        if (h == null) return BIOME_NONE;
        Integer id = prog.biomeIds.get(h);
        return id == null ? BIOME_NONE : id;
    }

    private static void setD(Words w, int i, double v) {
        long b = Double.doubleToRawLongBits(v);
        w.set(i, (int) b);
        w.set(i + 1, (int) (b >>> 32));
    }

    /** TamrielBiomeSource.pickProvinceCaveBiome's region tests, in its order, as atlas_page.slang's provinceCave codes. */
    static int caveClass(String r) {
        if (r == null) r = "";
        if (r.startsWith("skyrim/") || r.equals("province/skyrim")) return r.contains("winterhold") || r.contains("the_pale") ? 2 : 1;
        if (r.startsWith("high_rock/") || r.equals("province/high_rock")) return 3;
        if (r.startsWith("cyrodiil/") || r.equals("province/cyrodiil")) return 4;
        if (r.startsWith("morrowind/") || r.equals("province/morrowind"))
            return r.contains("telvannis") || r.contains("bitter_coast") ? 5 : 6;
        if (r.startsWith("valenwood/") || r.equals("province/valenwood")) return 7;
        if (r.startsWith("black_marsh/") || r.equals("province/black_marsh")) return 8;
        if (r.startsWith("elsweyr/") || r.equals("province/elsweyr")) return 9;
        if (r.startsWith("summerset/") || r.equals("province/summerset")) return 10;
        if (r.startsWith("hammerfell/") || r.equals("province/hammerfell")) return 11;
        if (r.startsWith("yokuda/") || r.equals("province/yokuda")) return 12;
        if (r.startsWith("atmora/") || r.equals("province/atmora") || r.contains("kamal")) return 13;
        if (r.startsWith("sea/") || r.equals("province/sea") || r.startsWith("thras/") || r.startsWith("pyandonea/")) return 14;
        return 0;
    }

    static Object staticField(String cls, String name) {
        try {
            Class<?> c = Class.forName(cls);
            return AtlasGenProgram.field(c, name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(cls + "." + name + " unavailable", e);
        }
    }

    static Object component(Object record, String name) {
        try {
            for (RecordComponent rc : record.getClass().getRecordComponents())
                if (rc.getName().equals(name)) {
                    var m = rc.getAccessor();
                    m.setAccessible(true);
                    return m.invoke(record);
                }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(record.getClass().getSimpleName() + "." + name + " unavailable", e);
        }
        throw new IllegalStateException(record.getClass().getSimpleName() + " has no " + name);
    }

}

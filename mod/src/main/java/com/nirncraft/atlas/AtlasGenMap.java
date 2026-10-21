package com.nirncraft.atlas;

import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.terrain.Hydrology;
import com.nirncraft.tamriel.terrain.MapRaster;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * The map of Nirn as the client generator's kernels read it (atlas_map.slang, docs/ATLAS-CLIENT-GENERATION.md): the
 * Tamriel raster's channels and regions, the hydrology's waterfalls, the sharp peaks, the fixed foreign lands and the
 * regions' terrain, packed into one word array uploaded once (atlas_gen_set_map). Everything is the same data the Java
 * map reads (taken from its own loaded objects), so the kernels' TamrielMap.sample matches it bit for bit. Built once
 * per process: the map does not depend on the world seed.
 */
final class AtlasGenMap {
    static final int MAGIC = 0x50414D41;  // "AMAP"
    static final int VERSION = 1;
    // header: offsets of the sections (atlas_map.slang keeps the same numbers)
    static final int H_RASTER = 2, H_REGIONS = 3, H_HYDRO = 4, H_PEAKS = 5, H_LANDS = 6, H_TERRAIN = 7;
    static final int HEADER = 32;

    private static volatile int[] built;

    private AtlasGenMap() {}

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
                "R_SUNKEN", "R_PYANDONEA", "R_THRAS", "R_GHOSTS", "R_PADOMAIC", "R_ELTHERIC", "R_ABECEAN"})
            w.add((Integer) staticField("com.nirncraft.tamriel.terrain.Beyond", n));
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
        return w.done();
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

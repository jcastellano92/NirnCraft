package com.skycraft.aether;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.MapNoise;
import com.skycraft.tamriel.terrain.OceanIsles;
import com.skycraft.tamriel.terrain.TamrielHeight;
import com.skycraft.worldgen.WorldNoise;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The Java half of the exact-height kernel (native/shaders/aether_height.slang): the world's seeded noises as the
 * kernel's table (read from the live NoiseStack / PerlinNoise objects), and per column the record of every term of
 * TamrielHeight.height that does not depend on those noises, computed by the very expressions of height() (so
 * Math.pow, the map smoothsteps and the plan lookups stay in Java). The kernel does the noises and the rest.
 *
 * <p>Keep {@link #prepare} in step with TamrielHeight.height: it is a line-for-line split of that method. The
 * verifier in AetherExactSource compares the result with the CPU path and turns the GPU off for good on any
 * difference, so a drift shows up as a logged fallback, never as wrong terrain.</p>
 */
final class AetherHeightGpu implements AutoCloseable {
    static final int WORDS = AetherNative.HEIGHT_COLUMN_WORDS, RECORD_BYTES = WORDS * 4;
    // column flags and doubles (aether_height.slang)
    static final int F_OCEAN = 1, F_RIDGE = 2, F_PEAK = 4, F_CRAG = 8, F_DUNES = 16, F_WET = 32, F_WETCH = 64,
            F_LAKE_MIN = 128, F_LAKE_MAX = 256, F_RIVER_MIN = 512, F_RIVER_MAX = 1024, F_PAD = 2048, F_PLOT = 4096,
            F_BED = 8192, F_CPU = 16384;
    static final int C_H0 = 0, C_AMPR = 1, C_SMOOTH = 2, C_SMOOTHK = 3, C_RIDGEK = 4, C_PEAKK = 5, C_CRAGW = 6,
            C_DUNESK = 7, C_WETLAND = 8, C_WETA = 9, C_WETB = 10, C_LAKE = 11, C_RIVER = 12, C_PADH = 13, C_WPAD = 14,
            C_PLOTH = 15, C_WPLOT = 16, C_BEDH = 17, C_WBED = 18, DOUBLES = 19;

    final AetherNative.Height engine;
    private final TamrielHeight height;
    /** OceanIsles.cellIsle per cell (a pure function of the cell; NONE when the cell has none). */
    private final ConcurrentHashMap<Long, Object> isles = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    private AetherHeightGpu(AetherNative.Height engine, TamrielHeight height) {
        this.engine = engine;
        this.height = height;
    }

    /**
     * The exact-height engine for this world's noises on `backend`, or null with the reason in why[0]: no usable
     * device, or noises of a shape the kernel does not implement (anything but NoiseStack of PerlinNoise layers).
     */
    static AetherHeightGpu create(AetherNative lib, int backend, TamrielHeight height, String[] why) {
        int[] table;
        try {
            table = table(height);
        } catch (ReflectiveOperationException | RuntimeException e) {
            why[0] = "noise tables unavailable: " + e;
            return null;
        }
        if (table == null) {
            why[0] = "noises are not NoiseStack(PerlinNoise) (or far lands are on): CPU only";
            return null;
        }
        AetherNative.Height h = lib.createHeight(backend);
        if (h == null) {
            why[0] = "no device with what the exact kernel needs (Vulkan 1.2, shaderFloat64, RTE + signed-zero/inf/nan float controls)";
            return null;
        }
        h.setNoise(table);
        return new AetherHeightGpu(h, height);
    }

    String device() {
        return engine.device();
    }

    // ---- the noise table -------------------------------------------------------------------------------------

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** The kernel's noise table for hills, detail, ridges, dunes, or null when a noise has another shape. */
    static int[] table(TamrielHeight h) throws ReflectiveOperationException {
        if (net.minecraft.SharedConstants.DEBUG_ENABLE_FARLANDS) return null;  // GradientNoise.wrap would not wrap
        WorldNoise[] noises = {h.hills(), h.detail(), h.ridges(), h.dunes()};
        Field layersF = field(NoiseStack.class, "layers");
        Field perms = field(GradientNoise.class, "perms"), ox = field(GradientNoise.class, "offsetX"),
                oy = field(GradientNoise.class, "offsetY"), oz = field(GradientNoise.class, "offsetZ");
        int total = 0;
        NoiseStack.Layer[][] all = new NoiseStack.Layer[4][];
        for (int n = 0; n < 4; n++) {
            Noise noise = noises[n].noise();
            // NoiseStack.get and NoiseStack$Perlin.get are the same sum; any other subclass is not this kernel
            if (noise == null || (noise.getClass() != NoiseStack.class
                    && !noise.getClass().getName().equals(NoiseStack.class.getName() + "$Perlin"))) return null;
            all[n] = (NoiseStack.Layer[]) layersF.get(noise);
            for (NoiseStack.Layer l : all[n]) if (l.noise().getClass() != PerlinNoise.class) return null;
            total += all[n].length;
        }
        int lw = AetherNative.HEIGHT_LAYER_WORDS;
        int[] t = new int[8 + total * lw];
        int first = 0;
        for (int n = 0; n < 4; n++) {
            t[n * 2] = first;
            t[n * 2 + 1] = all[n].length;
            for (NoiseStack.Layer l : all[n]) {
                int w = 8 + first * lw;
                putDouble(t, w, l.frequency());
                putDouble(t, w + 2, ox.getDouble(l.noise()));
                putDouble(t, w + 4, oy.getDouble(l.noise()));
                putDouble(t, w + 6, oz.getDouble(l.noise()));
                t[w + 8] = Float.floatToRawIntBits(l.amplitude());
                byte[] p = (byte[]) perms.get(l.noise());
                if (p.length != 256) return null;
                for (int i = 0; i < 256; i++) t[w + 12 + (i >> 2)] |= (p[i] & 255) << ((i & 3) * 8);
                first++;
            }
        }
        return t;
    }

    private static void putDouble(int[] t, int w, double v) {
        long b = Double.doubleToRawLongBits(v);
        t[w] = (int) b;
        t[w + 1] = (int) (b >>> 32);
    }

    // ---- the column records ------------------------------------------------------------------------------------

    /** Scratch for one thread: the record's doubles. */
    static final class Rec {
        final double[] d = new double[DOUBLES];
    }

    private static final ThreadLocal<Rec> REC = ThreadLocal.withInitial(Rec::new);

    /**
     * Writes column k's record (block x, z; s = its map sample) into `cols`. Returns true when the kernel can do the
     * column, false for a CPU column (an ocean isle may shape it: OceanIsles' transcendentals stay in Java).
     * Mirrors TamrielHeight.height up to the first seeded noise, then hands over its partial products.
     */
    boolean prepare(int x, int z, MapSample s, MemorySegment cols, int k) {
        double[] r = REC.get().d;
        java.util.Arrays.fill(r, 0.0);
        int flags = 0;
        double h = s.elevation;
        double cd = s.coastDistance;
        r[C_H0] = h;
        if (cd < 0) {
            flags |= F_OCEAN;
            r[C_AMPR] = 0.4 + 0.6 * MapNoise.smoothstep(-600, 0, cd);
            if (cd < -96 && isleMayShape(x, z)) flags |= F_CPU;
            write(cols, k, flags, r);
            return (flags & F_CPU) == 0;
        }

        // P2: the road plan and the site pads (verbatim from TamrielHeight.height)
        float wBed = 0f, wPad = 0f, wPlot = 0f;
        PlanGround.Bed pb = null;
        if (PlanGround.enabled()) {
            PlanGround g = PlanGround.getAt(x, z);
            pb = PlanGround.scratch();
            boolean inWater = s.inWater();
            if (g.bed(x, z, pb) && (!inWater || pb.span == RoadPlan.FORD)) wBed = pb.weight;
            boolean tidal = s.elevation < TamrielMap.SEA_LEVEL - 0.5f && cd < 24;
            if (!inWater && !tidal && g.pad(x, z, pb)) wPad = pb.padWeight;
            if (!inWater && g.plot(x, z, pb)) wPlot = pb.plotWeight;
        }

        double amp = MapNoise.smoothstep(0, 56, cd) * (1 - 0.75 * s.road) * (1 - s.calm)
                * (1 - PlanGround.CALM * Math.max(wBed, Math.max(wPad, wPlot)));
        boolean water = !Float.isNaN(s.waterLevel);
        if (water) {
            double reach = 30 + 40 * MapNoise.clamp((s.riverHalfWidth - 1) / 3.0, 0, 1);
            amp *= Math.min(MapNoise.smoothstep(s.riverHalfWidth + 4, s.riverHalfWidth + reach, s.riverDistance),
                    MapNoise.smoothstep(0, 70, -s.lakeDistance));
        }
        double smooth = 1 / (1 + 6 * s.slope * s.slope);
        // rolling hills: h += amp * s.roughness * (...): the kernel gets the left-hand product
        r[C_AMPR] = amp * s.roughness;
        r[C_SMOOTH] = smooth;
        r[C_SMOOTHK] = 0.4 + 0.6 * smooth;

        double own = Math.min(1.0, s.peak / 8.0);
        if (s.ridge > 0.02f && own < 1) {
            flags |= F_RIDGE;
            r[C_RIDGEK] = amp * (1 - own) * Math.pow(s.ridge, 1.3);
        }
        if (s.peak > 0) {
            flags |= F_PEAK;
            r[C_PEAKK] = amp * s.peak;
        }
        if (s.crag > 0.05f) {
            double steep = MapNoise.smoothstep(0.25, 0.85, s.slope);
            double w = s.crag * 0.85 * amp * (0.15 + 0.85 * steep);
            if (w > 0.01) {
                flags |= F_CRAG;
                r[C_CRAGW] = w;
            }
        }
        if (s.dunes > 0.02f) {
            flags |= F_DUNES;
            r[C_DUNESK] = amp * s.dunes;
        }
        if (s.wetland > 0.05f) {
            flags |= F_WET;  // the kernel adds the h < SEA_LEVEL + 10 test (h depends on the noises)
            r[C_WETLAND] = s.wetland;
            if (s.wetland > 0.3f) {
                flags |= F_WETCH;
                r[C_WETA] = MapNoise.smoothstep(0.3, 0.8, s.wetland);
                r[C_WETB] = 1 - 0.8 * s.road;
            }
        }
        if (water) {
            double wl = s.waterLevel;
            if (s.lakeDistance > -40) {
                if (s.lakeDistance > 0) {
                    flags |= F_LAKE_MIN;
                    r[C_LAKE] = wl - Math.min(12, 1.2 + s.lakeDistance * 0.25);
                } else {
                    flags |= F_LAKE_MAX;
                    r[C_LAKE] = wl + 1 - s.lakeDistance * 0.04;
                }
            }
            double hw = s.riverHalfWidth;
            double bankReach = 6 + 10 * MapNoise.clamp((hw - 1) / 3.0, 0, 1);
            if (hw > 0 && s.riverDistance < hw + bankReach) {
                double d = s.riverDistance;
                if (d < hw) {
                    double depth = MapNoise.clamp(hw * 0.3, hw < 4 ? 1.0 + 0.25 * hw : 1.5, 5);
                    double u = d / hw;
                    flags |= F_RIVER_MIN;
                    r[C_RIVER] = wl - 0.6 - depth * (1 - u * u);
                } else if (s.lakeDistance < -8) {
                    flags |= F_RIVER_MAX;
                    r[C_RIVER] = wl + 1.0 + (d - hw) * 0.05;
                }
            }
        }
        if (PlanGround.blending()) {
            if (wPad > 0f) {
                flags |= F_PAD;
                r[C_PADH] = pb.padHeight;
                r[C_WPAD] = wPad;
            }
            if (wPlot > 0f) {
                flags |= F_PLOT;
                r[C_PLOTH] = pb.plotHeight;
                r[C_WPLOT] = wPlot;
            }
            if (wBed > 0f) {
                flags |= F_BED;
                r[C_BEDH] = pb.height;
                r[C_WBED] = wBed;
            }
        }
        write(cols, k, flags, r);
        return true;
    }

    private static void write(MemorySegment cols, int k, int flags, double[] r) {
        long base = (long) k * RECORD_BYTES;
        cols.set(JAVA_INT, base, flags);
        cols.set(JAVA_INT, base + 4, 0);
        for (int i = 0; i < DOUBLES; i++) cols.set(JAVA_DOUBLE, base + 8 + 8L * i, r[i]);
    }

    /** OceanIsles.surfaceHeight's noise-free early outs: false when no isle can shape column (x, z). */
    private boolean isleMayShape(int x, int z) {
        int cx = Math.floorDiv(x, OceanIsles.CELL), cz = Math.floorDiv(z, OceanIsles.CELL);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Object o = isles.get(key);
        if (o == null) {
            OceanIsles.Isle isle = OceanIsles.cellIsle(cx, cz);
            o = isle == null ? NONE : isle;
            if (isles.size() > 200_000) isles.clear();
            isles.put(key, o);
        }
        if (o == NONE) return false;
        OceanIsles.Isle isle = (OceanIsles.Isle) o;
        double dx = x - isle.centerX(), dz = z - isle.centerZ();
        double r = isle.radius();
        double outer = r + 36.0;
        return !(dx * dx + dz * dz > outer * outer);
    }

    @Override
    public void close() {
        engine.close();
    }
}

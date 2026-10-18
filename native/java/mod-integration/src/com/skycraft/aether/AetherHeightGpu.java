package com.skycraft.aether;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.MapNoise;
import com.skycraft.tamriel.terrain.OceanIsles;
import com.skycraft.tamriel.terrain.TamrielHeight;
import com.skycraft.worldgen.WorldNoise;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * The Java half of the exact-noise kernel (native/shaders/aether_height.slang). The kernel evaluates the seeded
 * noise calls TamrielHeight.height makes (its WorldNoise.getValue calls, "slots"), bit-exact, for a tile at a time;
 * then the mod's own TamrielHeight.height runs per column on the CPU with its WorldNoise objects answered from those
 * values ({@link Replay}). Everything but the noises is the mod's code, so the result is height()'s by construction,
 * as long as the noise values are. A call that matches no computed slot (the mod changed a call) falls back to the
 * real noise: still exact, only slower ({@link #misses}).
 *
 * <p>One shortcut mirrors mod code: ocean columns no isle can reach take the sea-floor line of height() directly
 * (OceanIsles' per-column cell lookup would otherwise dominate the deep sea). AetherExactSource's verifier compares
 * whole tiles and samples with the CPU path, so a drift there is a logged fallback, never wrong terrain.</p>
 */
final class AetherHeightGpu implements AutoCloseable {
    static final int SLOTS = AetherNative.HEIGHT_SLOTS;
    /** Slot bits (aether_height.slang): hills, detail (always); ridge pair; peak pair; crag; dunes; wetland pair. */
    static final int S_BASE = 1 | 2, S_RIDGE = 4 | 8, S_PEAK = 16 | 32, S_CRAG = 64, S_DUNES = 128, S_WET = 256 | 512;
    /** The noise (hills 0, detail 1, ridges 2, dunes 3) of each slot. */
    private static final int[] SLOT_NOISE = {0, 1, 2, 1, 2, 1, 3, 3, 2, 0};

    final AetherNative.Height engine;
    /** height() with its noises served from the kernel's values (the current column of the calling thread). */
    final TamrielHeight replayHeight;
    /** Noise calls height() made that matched no computed slot (answered by the real noise). */
    final LongAdder misses = new LongAdder();
    /** OceanIsles.cellIsle per cell (a pure function of the cell; NONE when the cell has none). */
    private final ConcurrentHashMap<Long, Object> isles = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    private AetherHeightGpu(AetherNative.Height engine, TamrielHeight height) {
        this.engine = engine;
        WorldNoise[] n = {height.hills(), height.detail(), height.ridges(), height.dunes()};
        replayHeight = new TamrielHeight(new WorldNoise(null, new Replay(0, n[0].noise(), this)),
                new WorldNoise(null, new Replay(1, n[1].noise(), this)), new WorldNoise(null, new Replay(2, n[2].noise(), this)),
                new WorldNoise(null, new Replay(3, n[3].noise(), this)));
    }

    /**
     * The exact-noise engine for this world's noises on `backend`, or null with the reason in why[0]: no usable
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
            why[0] = "noises are not NoiseStack(PerlinNoise) in the kernel's range (or far lands are on)";
            return null;
        }
        AetherNative.Height h = lib.createHeight(backend);
        if (h == null) {
            why[0] = "no device with what the exact kernel needs (Vulkan 1.2, shaderFloat64, RTE + signed-zero/inf/nan float controls)";
            return null;
        }
        try {
            h.setNoise(table);
        } catch (RuntimeException e) {
            h.close();
            why[0] = e.getMessage();
            return null;
        }
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
        // NoiseStack.Layer(noise, frequency, amplitude) is a protected record: its fields by reflection too
        Class<?> layerC = Class.forName(NoiseStack.class.getName() + "$Layer");
        Field lNoise = field(layerC, "noise"), lFreq = field(layerC, "frequency"), lAmp = field(layerC, "amplitude");
        int total = 0;
        Object[][] all = new Object[4][];
        for (int n = 0; n < 4; n++) {
            Noise noise = noises[n].noise();
            // NoiseStack.get and NoiseStack$Perlin.get are the same sum; any other subclass is not this kernel
            if (noise == null || (noise.getClass() != NoiseStack.class
                    && !noise.getClass().getName().equals(NoiseStack.class.getName() + "$Perlin"))) return null;
            all[n] = (Object[]) layersF.get(noise);
            for (Object l : all[n]) if (lNoise.get(l).getClass() != PerlinNoise.class) return null;
            total += all[n].length;
        }
        int lw = AetherNative.HEIGHT_LAYER_WORDS;
        int[] t = new int[8 + total * lw];
        int first = 0;
        for (int n = 0; n < 4; n++) {
            t[n * 2] = first;
            t[n * 2 + 1] = all[n].length;
            for (Object l : all[n]) {
                int w = 8 + first * lw;
                Object perlin = lNoise.get(l);
                putDouble(t, w, lFreq.getDouble(l));
                putDouble(t, w + 2, ox.getDouble(perlin));
                putDouble(t, w + 4, oy.getDouble(perlin));
                putDouble(t, w + 6, oz.getDouble(perlin));
                t[w + 8] = Float.floatToRawIntBits(lAmp.getFloat(l));
                byte[] p = (byte[]) perms.get(perlin);
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

    // ---- per column ------------------------------------------------------------------------------------------

    /**
     * The noise calls TamrielHeight.height may make for a column with sample s (a superset: e.g. the wetland pair
     * whenever the wetland is above 0.3, the crag noise whenever there are crags; height() decides).
     */
    static int slotsFor(MapSample s) {
        int bits = S_BASE;
        if (s.coastDistance < 0) return bits;
        double own = Math.min(1.0, s.peak / 8.0);
        if (s.ridge > 0.02f && own < 1) bits |= S_RIDGE;
        if (s.peak > 0) bits |= S_PEAK;
        if (s.crag > 0.05f) bits |= S_CRAG;
        if (s.dunes > 0.02f) bits |= S_DUNES;
        if (s.wetland > 0.3f) bits |= S_WET;
        return bits;
    }

    /** True when the column's height is height()'s sea-floor line (ocean, and no isle can reach it). */
    boolean seaFloor(int x, int z, MapSample s) {
        return s.coastDistance < 0 && !(s.coastDistance < -96 && isleMayShape(x, z));
    }

    /**
     * height()'s sea floor for an ocean column no isle reaches (TamrielHeight.height, its first branch, with
     * OceanIsles.surfaceHeight returning NaN), from the kernel's hills and detail values.
     */
    static double seaFloorHeight(MapSample s, double hillsN, double detailN) {
        double h = s.elevation;
        double cd = s.coastDistance;
        return Math.min(h + (hillsN * 5 + detailN * 1.5) * (0.4 + 0.6 * MapNoise.smoothstep(-600, 0, cd)), TamrielMap.SEA_LEVEL - 1);
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

    /** The calling thread's current column for {@link Replay}: its block, slots and the kernel's values. */
    static final class Column {
        final double[] cx = new double[SLOTS], cy = new double[SLOTS], cz = new double[SLOTS];
        final float[] v = new float[SLOTS];
        int bits;

        /** Column (x, z) with slot values values[s * count + k] for the slots in `bits`. */
        void set(int x, int z, int bits, MemorySegment values, int count, int k) {
            this.bits = bits;
            for (int s = 0; s < SLOTS; s++) {
                if ((bits & (1 << s)) == 0) continue;
                v[s] = values.getAtIndex(JAVA_FLOAT, (long) s * count + k);
                // TamrielHeight.height's argument expressions (int x, z; double constants)
                switch (s) {
                    case 0, 1, 2 -> { cx[s] = x; cy[s] = 0; cz[s] = z; }
                    case 3 -> { cx[s] = x * 2.1; cy[s] = 37; cz[s] = z * 2.1; }
                    case 4 -> { cx[s] = x * 1.7; cy[s] = 113; cz[s] = z * 1.7; }
                    case 5 -> { cx[s] = x * 3.3; cy[s] = 131; cz[s] = z * 3.3; }
                    case 6 -> { cx[s] = x * 0.22; cy[s] = 61; cz[s] = z * 0.22; }
                    case 7 -> { cx[s] = x * 0.8 + z * 0.35; cy[s] = 0; cz[s] = z * 1.7 - x * 0.2; }
                    case 8 -> { cx[s] = x * 1.9; cy[s] = 57; cz[s] = z * 1.9; }
                    default -> { cx[s] = x * 3.6 + 400; cy[s] = 23; cz[s] = z * 3.6; }
                }
            }
        }

        float value(int s) {
            return v[s];
        }
    }

    static final ThreadLocal<Column> COLUMN = ThreadLocal.withInitial(Column::new);

    /** A height() noise answered from the current column's kernel values; anything else from the real noise. */
    record Replay(int id, Noise real, AetherHeightGpu owner) implements Noise {
        @Override public Interval range() { return real.range(); }
        @Override public float get(double x, double z) { return real.get(x, z); }

        @Override
        public float get(double x, double y, double z) {
            Column c = COLUMN.get();
            for (int s = 0; s < SLOTS; s++)
                if (SLOT_NOISE[s] == id && (c.bits & (1 << s)) != 0 && c.cx[s] == x && c.cy[s] == y && c.cz[s] == z)
                    return c.v[s];
            owner.misses.increment();
            return real.get(x, y, z);
        }
    }

    @Override
    public void close() {
        engine.close();
    }
}

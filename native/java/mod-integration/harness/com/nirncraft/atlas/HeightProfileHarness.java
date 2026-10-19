package com.nirncraft.atlas;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.terrain.OceanIsles;
import com.nirncraft.tamriel.terrain.TamrielHeight;
import com.nirncraft.worldgen.WorldNoise;
import com.nirncraft.worldplan.PlanGround;
import com.nirncraft.worldplan.RoadPlan;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.synth.Noise;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.IntStream;

/**
 * CPU profile of the exact far-field columns (no GPU, no native library): for the full 7-LOD window around a camera,
 * how the time of AtlasExactSource.fill splits between TamrielMap.sample (raster, hydrology, peaks, beyond), the
 * seeded noise evaluations inside TamrielHeight.height, the road/pad plan lookups (PlanGround), the ocean isles, and
 * the rest of the height arithmetic. Each part is replayed on its own over exactly the calls the real pass made.
 * <pre>  run_exact_harness.ps1 -Main HeightProfileHarness [-Seed 12345] [-X 0] [-Z 0] [-Threads 8]</pre>
 */
public final class HeightProfileHarness {
    private HeightProfileHarness() {}

    static final java.io.PrintStream OUT = System.out;
    static final int TILE = AtlasNative.TILE, N = TILE * TILE;

    /** Records every get(x, y, z) of one of the four height noises into the calling thread's log. */
    record Rec(int id, Noise inner) implements Noise {
        static final ThreadLocal<double[][]> LOG = ThreadLocal.withInitial(() -> new double[][] {new double[1 << 16]});
        static final ThreadLocal<int[]> LEN = ThreadLocal.withInitial(() -> new int[1]);

        @Override public Interval range() { return inner.range(); }
        @Override public float get(double x, double z) { throw new UnsupportedOperationException(); }

        @Override public float get(double x, double y, double z) {
            double[][] l = LOG.get();
            int[] n = LEN.get();
            if (n[0] + 4 > l[0].length) l[0] = java.util.Arrays.copyOf(l[0], l[0].length * 2);
            double[] a = l[0];
            a[n[0]++] = id;
            a[n[0]++] = x;
            a[n[0]++] = y;
            a[n[0]++] = z;
            return inner.get(x, y, z);
        }
    }

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        long seed = Long.parseLong(a[3]);
        double cx0 = Double.parseDouble(a[4]), cz0 = Double.parseDouble(a[5]);
        int threads = Integer.parseInt(a[7]);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        TamrielHeight h = HarnessSeed.height(seed);
        Noise[] raw = {h.hills().noise(), h.detail().noise(), h.ridges().noise(), h.dunes().noise()};
        TamrielHeight rec = new TamrielHeight(new WorldNoise(null, new Rec(0, raw[0])), new WorldNoise(null, new Rec(1, raw[1])),
                new WorldNoise(null, new Rec(2, raw[2])), new WorldNoise(null, new Rec(3, raw[3])));
        ForkJoinPool pool = new ForkJoinPool(threads);
        MapSample[] ms = new MapSample[N];
        for (int i = 0; i < N; i++) ms[i] = new MapSample();
        double[][] logs = new double[TILE][];
        int[] logLen = new int[TILE];
        DoubleAdder sink = new DoubleAdder();
        // warm up the JIT on one tile so the first LOD does not pay for compilation
        for (int pass = 0; pass < 2; pass++) {
            long tSample = 0, tHeight = 0, tNoise = 0, tPlan = 0, tIsle = 0, calls = 0, cols = 0, land = 0, deepSea = 0;
            long[] perNoise = new long[4];
            for (int lod = 0; lod < AtlasFarField.LODS; lod++) {
                int span = 256 << lod, s = 1 << lod;
                int cx = Math.floorDiv((int) Math.floor(cx0), span), cz = Math.floorDiv((int) Math.floor(cz0), span);
                int w = pass == 0 ? 0 : AtlasFarField.WINDOW;
                for (int tz = cz - w; tz <= cz + w; tz++)
                    for (int tx = cx - w; tx <= cx + w; tx++) {
                        int bx = tx * (TILE << lod) + (s >> 1), bz = tz * (TILE << lod) + (s >> 1);
                        long t0 = System.nanoTime();
                        pool.submit(() -> IntStream.range(0, TILE).parallel().forEach(j -> {
                            for (int i = 0; i < TILE; i++) TamrielMap.sample(bx + i * s, bz + j * s, ms[j * TILE + i]);
                        })).join();
                        long t1 = System.nanoTime();
                        pool.submit(() -> IntStream.range(0, TILE).parallel().forEach(j -> {
                            double acc = 0;
                            for (int i = 0; i < TILE; i++) acc += h.height(bx + i * s, bz + j * s, ms[j * TILE + i]);
                            sink.add(acc);
                        })).join();
                        long t2 = System.nanoTime();
                        // the same pass through the recording noises (untimed): every noise call of every column
                        pool.submit(() -> IntStream.range(0, TILE).parallel().forEach(j -> {
                            Rec.LEN.get()[0] = 0;
                            for (int i = 0; i < TILE; i++) rec.height(bx + i * s, bz + j * s, ms[j * TILE + i]);
                            logLen[j] = Rec.LEN.get()[0];
                            logs[j] = java.util.Arrays.copyOf(Rec.LOG.get()[0], logLen[j]);
                        })).join();
                        long t3 = System.nanoTime();
                        pool.submit(() -> IntStream.range(0, TILE).parallel().forEach(j -> {
                            double[] l = logs[j];
                            float acc = 0;
                            for (int k = 0; k < logLen[j]; k += 4) acc += raw[(int) l[k]].get(l[k + 1], l[k + 2], l[k + 3]);
                            sink.add(acc);
                        })).join();
                        long t4 = System.nanoTime();
                        // the plan lookups exactly as height() makes them (land columns)
                        pool.submit(() -> IntStream.range(0, TILE).parallel().forEach(j -> {
                            float acc = 0;
                            for (int i = 0; i < TILE; i++) {
                                MapSample m = ms[j * TILE + i];
                                if (m.coastDistance < 0 || !PlanGround.enabled()) continue;
                                int x = bx + i * s, z = bz + j * s;
                                PlanGround g = PlanGround.getAt(x, z);
                                PlanGround.Bed pb = PlanGround.scratch();
                                boolean inWater = m.inWater();
                                if (g.bed(x, z, pb) && (!inWater || pb.span == RoadPlan.FORD)) acc += pb.weight;
                                boolean tidal = m.elevation < TamrielMap.SEA_LEVEL - 0.5f && m.coastDistance < 24;
                                if (!inWater && !tidal && g.pad(x, z, pb)) acc += pb.padWeight;
                                if (!inWater && g.plot(x, z, pb)) acc += pb.plotWeight;
                            }
                            sink.add(acc);
                        })).join();
                        long t5 = System.nanoTime();
                        pool.submit(() -> IntStream.range(0, TILE).parallel().forEach(j -> {
                            double acc = 0;
                            for (int i = 0; i < TILE; i++) {
                                MapSample m = ms[j * TILE + i];
                                if (m.coastDistance < -96) {
                                    double v = OceanIsles.surfaceHeight(bx + i * s, bz + j * s, m.coastDistance, 0, 0);
                                    if (!Double.isNaN(v)) acc += v;
                                }
                            }
                            sink.add(acc);
                        })).join();
                        long t6 = System.nanoTime();
                        tSample += t1 - t0;
                        tHeight += t2 - t1;
                        tNoise += t4 - t3;
                        tPlan += t5 - t4;
                        tIsle += t6 - t5;
                        for (int j = 0; j < TILE; j++) {
                            calls += logLen[j] / 4;
                            for (int k = 0; k < logLen[j]; k += 4) perNoise[(int) logs[j][k]]++;
                        }
                        for (MapSample m : ms) {
                            if (m.coastDistance >= 0) land++;
                            else if (m.coastDistance < -96) deepSea++;
                        }
                        cols += N;
                    }
            }
            if (pass == 0) continue;
            double total = (tSample + tHeight) / 1e6;
            OUT.printf("seed %d camera (%.0f, %.0f): %d columns (%.1f%% land, %.1f%% open sea < -96), %d threads%n", seed,
                    cx0, cz0, cols, 100.0 * land / cols, 100.0 * deepSea / cols, threads);
            OUT.printf("  wall total (sample + height)   %7.0f ms%n", total);
            OUT.printf("  TamrielMap.sample              %7.0f ms  %5.1f%%%n", tSample / 1e6, 100 * tSample / 1e6 / total);
            OUT.printf("  TamrielHeight.height           %7.0f ms  %5.1f%%%n", tHeight / 1e6, 100 * tHeight / 1e6 / total);
            OUT.printf("    seeded noise (replayed)      %7.0f ms  %5.1f%%  (%d calls, %.2f per column: hills %d detail %d ridges %d dunes %d)%n",
                    tNoise / 1e6, 100 * tNoise / 1e6 / total, calls, calls / (double) cols, perNoise[0], perNoise[1], perNoise[2], perNoise[3]);
            OUT.printf("    PlanGround lookups (replayed)%7.0f ms  %5.1f%%%n", tPlan / 1e6, 100 * tPlan / 1e6 / total);
            OUT.printf("    OceanIsles (replayed)        %7.0f ms  %5.1f%%%n", tIsle / 1e6, 100 * tIsle / 1e6 / total);
            double rest = (tHeight - tNoise - tPlan - tIsle) / 1e6;
            OUT.printf("    rest of height (by diff.)    %7.0f ms  %5.1f%%%n", rest, 100 * rest / total);
        }
        OUT.println("(sink " + sink.sum() + ")");
        pool.shutdown();
    }
}

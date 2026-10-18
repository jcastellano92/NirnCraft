package com.skycraft.aether;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.TamrielHeight;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Bit-exactness and speed of the GPU exact-height path (AetherHeightGpu + aether_height_batch) against the CPU path,
 * offline. For every seed and camera: every column of the full 7-LOD window computed both ways, compared as raw
 * double bits (and the water tops as raw float bits, and the tile codec bytes); CPU ms against the GPU path's
 * prep / upload / kernel / readback / finish. Then the in-game path (verification + speed check) on one window.
 * <pre>  run_exact_harness.ps1 -Main GpuHeightHarness [-Threads 8]   (-Dharness.seeds=a,b,c -Dharness.cams=x:z;x:z)</pre>
 */
public final class GpuHeightHarness {
    private GpuHeightHarness() {}

    static final java.io.PrintStream OUT = System.out;
    static final int TILE = AetherNative.TILE, N = TILE * TILE;

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        Path dll = Path.of(a[0]).toAbsolutePath();
        int threads = Integer.parseInt(a[7]);
        String[] seeds = System.getProperty("harness.seeds", "12345,-4172144997902289642,42").split(",");
        String[] cams = System.getProperty("harness.cams", "0:0;-9000:7000;-14500:-4900").split(";");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AetherNative n = AetherNative.load(dll);
        long allCols = 0, allDiff = 0, allTopDiff = 0, allCodecDiff = 0, allFlagged = 0, allCpuCols = 0;
        boolean first = true;
        double sumCpu = 0, sumGpu = 0;
        for (String seedS : seeds) {
            long seed = Long.parseLong(seedS.trim());
            TamrielHeight height = HarnessSeed.height(seed);
            for (String cam : cams) {
                String[] xz = cam.split(":");
                int cx0 = Integer.parseInt(xz[0].trim()), cz0 = Integer.parseInt(xz[1].trim());
                AetherExactSource src = new AetherExactSource(height, null, () -> null, threads);
                String why = src.enableGpu(n);
                if (why != null) throw new IllegalStateException("GPU path unavailable: " + why);
                if (first) {
                    OUT.printf("device: %s (startup probe: ~%.2f ms of kernel per tile)%n", src.gpuDevice(), src.gpuProbeMs);
                    // JIT warm-up of both paths on one tile, untimed
                    double[] h = new double[N];
                    float[] t = new float[N];
                    src.heightsCpu(0, 0, 0, h, t);
                    src.heightsGpu(src.gpu(), 0, 0, 0, h, t);
                    src.resetGpuStats();
                    first = false;
                }
                Window w = window(src, cx0, cz0);
                allCols += w.cols;
                allDiff += w.diff;
                allTopDiff += w.topDiff;
                allCodecDiff += w.codecDiff;
                allFlagged += src.gpuFlagged.sum();
                allCpuCols += src.gpuCpuColumns.sum();
                OUT.printf("seed %d camera (%d, %d): %d columns, %d heights differ, %d water tops differ, %d tile codecs differ;"
                                + " %d flagged back to the CPU, %d isle columns, %d replay misses; land %.1f%%, peaks/ridges %.1f%%, crags %.1f%%,"
                                + " rivers/lakes %.1f%%, region borders %.1f%%, regions %d, x %d..%d z %d..%d%n",
                        seed, cx0, cz0, w.cols, w.diff, w.topDiff, w.codecDiff, src.gpuFlagged.sum(), src.gpuCpuColumns.sum(), src.gpu().misses.sum(),
                        100.0 * w.land / w.cols, 100.0 * w.rugged / w.cols, 100.0 * w.crag / w.cols, 100.0 * w.water / w.cols, 100.0 * w.border / w.cols,
                        w.regions.size(), w.minX, w.maxX, w.minZ, w.maxZ);
                OUT.printf("  window CPU %6.0f ms | GPU path %6.0f ms (pipelined; summed stages: map samples %5.0f, batches %5.0f ="
                                + " upload %4.0f + kernel %4.0f + readback %4.0f + call overhead, height() with GPU noises %5.0f)%n",
                        w.cpuMs, w.gpuMs, src.gpuPrepMs.sum(), src.gpuBatchMs.sum(), src.gpuUploadMs.sum(),
                        src.gpuKernelMs.sum(), src.gpuReadbackMs.sum(), src.gpuFinishMs.sum());
                // the paths on their own (not interleaved tile by tile), second of two passes each
                double cpu = 0, gpu = 0;
                for (int pass = 0; pass < 2; pass++) {
                    cpu = timeWindow(src, cx0, cz0, false);
                    src.resetGpuStats();
                    gpu = timeWindow(src, cx0, cz0, true);
                }
                OUT.printf("  separate passes: window CPU %6.0f ms, GPU path %6.0f ms (%.2fx); GPU path stages summed: map samples"
                                + " %5.0f, batches %5.0f (upload %4.0f, kernel %4.0f, readback %4.0f), height() %5.0f%n", cpu, gpu,
                        cpu / gpu, src.gpuPrepMs.sum(), src.gpuBatchMs.sum(), src.gpuUploadMs.sum(), src.gpuKernelMs.sum(),
                        src.gpuReadbackMs.sum(), src.gpuFinishMs.sum());
                sumCpu += cpu;
                sumGpu += gpu;
                src.close();
            }
        }
        OUT.printf("mean window: CPU %.0f ms, GPU path %.0f ms (%d threads)%n", sumCpu / (seeds.length * cams.length),
                sumGpu / (seeds.length * cams.length), threads);
        OUT.printf("TOTAL: %d columns compared over %d windows, %d heights differ, %d water tops differ, %d tile codecs differ;"
                        + " %d columns flagged back to the CPU by the kernel, %d isle columns%n",
                allCols, seeds.length * cams.length, allDiff, allTopDiff, allCodecDiff, allFlagged, allCpuCols);
        if (allDiff + allTopDiff + allCodecDiff != 0) throw new IllegalStateException("GPU path is not bit-exact");

        // the in-game path: verification of the first tile per LOD, sampled checks, the speed check
        TamrielHeight height = HarnessSeed.height(Long.parseLong(seeds[0].trim()));
        AetherExactSource src = new AetherExactSource(height, null, () -> null, threads);
        src.keepGpuWhenSlower = false;
        src.enableGpu(n);
        long t0 = System.nanoTime();
        double[] h = new double[N];
        float[] top = new float[N];
        for (int lod = 0; lod < AetherFarField.LODS; lod++) {
            int span = TILE << lod, cx = Math.floorDiv(0, span), cz = Math.floorDiv(0, span);
            for (int tz = cz - AetherFarField.WINDOW; tz <= cz + AetherFarField.WINDOW; tz++)
                for (int tx = cx - AetherFarField.WINDOW; tx <= cx + AetherFarField.WINDOW; tx++) src.heights(lod, tx, tz, h, top);
        }
        OUT.printf("in-game path (verified, speed-checked) window: %.0f ms, GPU %s%s%n", (System.nanoTime() - t0) / 1e6,
                src.gpuOn() ? "kept" : "off", src.gpuOffReason != null ? " (" + src.gpuOffReason + ")" : "");
        src.close();
    }

    /** Wall time of one full window on the CPU path or the GPU path alone. */
    static double timeWindow(AetherExactSource src, int x, int z, boolean gpu) {
        double[] h = new double[N];
        float[] t = new float[N];
        long t0 = System.nanoTime();
        for (int lod = 0; lod < AetherFarField.LODS; lod++) {
            int span = TILE << lod, cx = Math.floorDiv(x, span), cz = Math.floorDiv(z, span);
            for (int tz = cz - AetherFarField.WINDOW; tz <= cz + AetherFarField.WINDOW; tz++)
                for (int tx = cx - AetherFarField.WINDOW; tx <= cx + AetherFarField.WINDOW; tx++)
                    if (gpu) src.heightsGpu(src.gpu(), lod, tx, tz, h, t);
                    else src.heightsCpu(lod, tx, tz, h, t);
        }
        return (System.nanoTime() - t0) / 1e6;
    }

    static final class Window {
        long cols, diff, topDiff, codecDiff, land, rugged, crag, water, border;
        double cpuMs, gpuMs;
        final double[] cpuLod = new double[8], gpuLod = new double[8];
        final java.util.Set<Integer> regions = new java.util.HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    }

    static Window window(AetherExactSource src, int x, int z) {
        Window w = new Window();
        double[] h1 = new double[N], h2 = new double[N];
        float[] t1 = new float[N], t2 = new float[N];
        float[] s1 = new float[N], w1 = new float[N], s2 = new float[N], w2 = new float[N];
        MapSample m = new MapSample();
        for (int lod = 0; lod < AetherFarField.LODS; lod++) {
            int span = TILE << lod, s = 1 << lod;
            int cx = Math.floorDiv(x, span), cz = Math.floorDiv(z, span);
            for (int tz = cz - AetherFarField.WINDOW; tz <= cz + AetherFarField.WINDOW; tz++)
                for (int tx = cx - AetherFarField.WINDOW; tx <= cx + AetherFarField.WINDOW; tx++) {
                    long a0 = System.nanoTime();
                    src.heightsCpu(lod, tx, tz, h1, t1);
                    long a1 = System.nanoTime();
                    src.heightsGpu(src.gpu(), lod, tx, tz, h2, t2);
                    long a2 = System.nanoTime();
                    w.cpuLod[lod] += (a1 - a0) / 1e6;
                    w.gpuLod[lod] += (a2 - a1) / 1e6;
                    w.cpuMs += (a1 - a0) / 1e6;
                    w.gpuMs += (a2 - a1) / 1e6;
                    for (int k = 0; k < N; k++) {
                        if (Double.doubleToRawLongBits(h1[k]) != Double.doubleToRawLongBits(h2[k])) {
                            if (w.diff < 5)
                                OUT.printf("  DIFF lod %d tile %d,%d col %d: cpu %s gpu %s%n", lod, tx, tz, k,
                                        Double.toString(h1[k]), Double.toString(h2[k]));
                            w.diff++;
                        }
                        if (Float.floatToRawIntBits(t1[k]) != Float.floatToRawIntBits(t2[k])) w.topDiff++;
                        s1[k] = (float) h1[k];
                        w1[k] = AetherExactSource.waterAt(h1[k], t1[k]);
                        s2[k] = (float) h2[k];
                        w2[k] = AetherExactSource.waterAt(h2[k], t2[k]);
                    }
                    if (!Arrays.equals(AetherTileCodec.encode(lod, s1, w1), AetherTileCodec.encode(lod, s2, w2))) w.codecDiff++;
                    w.cols += N;
                    // what the window covers (every 16th column)
                    int bx = tx * span + (s >> 1), bz = tz * span + (s >> 1);
                    w.minX = Math.min(w.minX, bx);
                    w.maxX = Math.max(w.maxX, bx + (TILE - 1) * s);
                    w.minZ = Math.min(w.minZ, bz);
                    w.maxZ = Math.max(w.maxZ, bz + (TILE - 1) * s);
                    for (int k = 0; k < N; k += 16) {
                        TamrielMap.sample(bx + (k % TILE) * s, bz + (k / TILE) * s, m);
                        if (m.coastDistance >= 0) w.land += 16;
                        if (m.ridge > 0.02f || m.peak > 0) w.rugged += 16;
                        if (m.crag > 0.05f) w.crag += 16;
                        if (m.inWater()) w.water += 16;
                        if (m.blendWeight > 0) w.border += 16;
                        w.regions.add(m.region);
                    }
                }
        }
        return w;
    }
}

package com.nirncraft.atlas;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * AtlasSurface offline, on vanilla's own overworld (its registries bootstrapped in-process: the overworld noise
 * settings, their material rule and the multi-noise biome source), since NirnCraft's need a running server: drives the
 * 26.3 material rules per far column through the reflectively built rule context, and reports what it paints over a
 * LOD 0 tile and a LOD 4 tile, the per-column cost, and that the tile bytes are deterministic (two runs, same bytes).
 * <pre>  java ... com.nirncraft.atlas.SurfaceHarness [seed] [x] [z]</pre>
 */
public final class SurfaceHarness {
    private SurfaceHarness() {}

    static final PrintStream OUT = System.out;

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        long seed = a.length > 0 ? Long.parseLong(a[0]) : 12345;
        int cx = a.length > 1 ? Integer.parseInt(a[1]) : 0, cz = a.length > 2 ? Integer.parseInt(a[2]) : 0;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        long t0 = System.nanoTime();
        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var preset = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        NoiseBasedChunkGenerator gen = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(preset), settings);
        RandomState rs = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings.value());
        AtlasSurface s = AtlasSurface.forGenerator(gen, rs, seed, LevelHeightAccessor.create(-64, 384));
        OUT.printf("vanilla overworld registries + rule context: %.0f ms%n", (System.nanoTime() - t0) / 1e6);
        var surfaceFn = settings.value().noiseRouter().chunkSurfaceLevel();
        for (int lod : new int[] {0, 4}) {
            int n = AtlasNative.TILE, st = 1 << lod;
            int tx = Math.floorDiv(cx, n << lod), tz = Math.floorDiv(cz, n << lod);
            float[] surf = new float[n * n], water = new float[n * n];
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++) {
                    int x = tx * (n << lod) + i * st + (st >> 1), z = tz * (n << lod) + j * st + (st >> 1);
                    surf[j * n + i] = rs.sampleBlockValueUncached(surfaceFn, x, 0, z) + 0.5f;
                    water[j * n + i] = surf[j * n + i] < 63 ? 63f : Float.NaN;
                }
            byte[][] bytes = new byte[2][];
            double ms = 1e30;
            AtlasTileCodec.Surface b = null;
            for (int run = 0; run < 6; run++) {
                b = new AtlasTileCodec.Surface();
                long t1 = System.nanoTime();
                final AtlasTileCodec.Surface bb = b;
                java.util.stream.IntStream.range(0, 16).parallel().forEach(q -> s.fillRows(lod, tx, tz, surf, water, bb, q * 16, q * 16 + 16));
                ms = Math.min(ms, (System.nanoTime() - t1) / 1e6);  // warm: the best of six
                bytes[Math.min(run, 1)] = AtlasTileCodec.encode(lod, surf, water, null, null, b);
            }
            Map<String, Integer> hist = new TreeMap<>();
            int snow = 0, frozen = 0;
            for (int c = 0; c < n * n; c++) {
                hist.merge(b.names.get(b.top[c] & 0xFF), 1, Integer::sum);
                if ((b.flags[c] & AtlasSurface.SNOW) != 0) snow++;
                if ((b.flags[c] & AtlasSurface.FROZEN) != 0) frozen++;
            }
            int par = Math.min(16, Runtime.getRuntime().availableProcessors());
            OUT.printf("lod %d tile %d,%d: %.0f ms (%.2f us/column/thread on %d threads), %d KB encoded, deterministic %s; snow %d, frozen %d%n",
                    lod, tx, tz, ms, ms * 1000 / (n * n) * par, par,
                    bytes[0].length / 1024, java.util.Arrays.equals(bytes[0], bytes[1]), snow, frozen);
            hist.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                    .forEach(e -> OUT.printf("    %-36s %6.2f%%%n", e.getKey(), 100.0 * e.getValue() / (n * n)));
            float[] s2 = new float[n * n], w2 = new float[n * n];
            AtlasTileCodec.Surface back = new AtlasTileCodec.Surface();
            int r = AtlasTileCodec.decode(lod, bytes[0], s2, w2, null, null, back);
            boolean same = r == 2 && back.names.equals(b.names) && java.util.Arrays.equals(back.top, b.top)
                    && java.util.Arrays.equals(back.under, b.under) && java.util.Arrays.equals(back.flags, b.flags);
            OUT.println("    codec v3 round trip: " + (same ? "exact" : "MISMATCH (" + r + ")"));
            if (!same) throw new IllegalStateException("codec round trip");
        }
    }
}

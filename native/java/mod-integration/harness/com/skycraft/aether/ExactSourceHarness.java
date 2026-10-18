package com.skycraft.aether;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.TamrielHeight;
import com.skycraft.worldgen.WorldNoise;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * Offline harness for the exact column source: the mod's real terrain height path (height-subset) with a world seed,
 * driven through AetherExactSource into aether exactly as the client does in single player, outside Minecraft.
 * Reports the column throughput, how far the seed-free preview stand-in is from the real surface, and renders the
 * same view from both sources side by side.
 * <pre>  .\native\java\mod-integration\harness\run_exact_harness.ps1 [-Seed 12345] [-X 0] [-Z 0] [-Yaw 100]</pre>
 */
public final class ExactSourceHarness {
    private ExactSourceHarness() {}

    /** The world's tamriel_height as RandomState builds it: Noises.instantiate = def.create(seed positional.fromHashOf(id)). */
    static TamrielHeight forSeed(long seed) throws Exception {
        PositionalRandomFactory r = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();
        WorldNoise[] n = new WorldNoise[AetherExactSource.NOISES.length];
        for (int i = 0; i < n.length; i++) {
            String name = AetherExactSource.NOISES[i];
            try (InputStream in = ExactSourceHarness.class.getResourceAsStream("/data/skycraft/worldgen/noise/" + name + ".json")) {
                if (in == null) throw new IllegalStateException("noise " + name + " not on the classpath");
                NormalNoise def = NormalNoise.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))).getOrThrow();
                n[i] = new WorldNoise(null, def.create(r.fromHashOf(Identifier.fromNamespaceAndPath("skycraft", name))));
            }
        }
        return new TamrielHeight(n[0], n[1], n[2], n[3]);
    }

    /** The console: vanilla's bootstrap later re-routes System.out/err into its (here silent) logger. */
    static final java.io.PrintStream OUT = System.out;

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            // vanilla's bootstrap routes uncaught exceptions to its logger, which is silent here
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        Path dll = Path.of(a[0]).toAbsolutePath(), data = Path.of(a[1]), out = Path.of(a[2]);
        long seed = Long.parseLong(a[3]);
        double x = Double.parseDouble(a[4]), z = Double.parseDouble(a[5]), yaw = Double.parseDouble(a[6]);
        int threads = Integer.parseInt(a[7]);

        long t0 = System.nanoTime();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        TamrielHeight height = forSeed(seed);
        MapSample probe = TamrielMap.sampleScratch((int) x, (int) z);
        double groundExact = height.height((int) x, (int) z, probe);
        OUT.printf("bootstrap + map load: %.0f ms; seed %d; ground at camera %.1f%n", (System.nanoTime() - t0) / 1e6,
                seed, groundExact);

        AetherNative n = AetherNative.load(dll);
        Path raster = data.resolve("tamriel_raster.bin");
        int[] noise = null;
        Path noisePath = data.resolve("cloud_noise.bin");
        if (Files.exists(noisePath)) {
            noise = new int[64 * 64 * 64];
            ByteBuffer.wrap(Files.readAllBytes(noisePath)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(noise);
        }
        double y = Math.max(groundExact, 63) + 90;
        int w = 1600, h = 900;
        int[][] images = new int[2][];

        try (AetherNative.Context exact = n.create(AetherNative.BACKEND_VULKAN);
             AetherNative.Context preview = n.create(AetherNative.BACKEND_VULKAN)) {
            // exact: the mod's height through AetherExactSource, the client's single-player path
            AetherExactSource src = new AetherExactSource(height, () -> raster, threads);
            src.prepare(exact);
            long c0 = System.nanoTime();
            int tiles = 0;
            for (int lod = 0; lod < AetherFarField.LODS; lod++) {
                int span = 256 << lod;
                int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                long l0 = System.nanoTime();
                src.cover(exact, lod, (cx - AetherFarField.WINDOW) * span, (cz - AetherFarField.WINDOW) * span,
                        (cx + AetherFarField.WINDOW + 1) * span, (cz + AetherFarField.WINDOW + 1) * span);
                int k = (2 * AetherFarField.WINDOW + 1) * (2 * AetherFarField.WINDOW + 1);
                tiles += k;
                OUT.printf("  lod %d (%4d m columns): %d tiles in %5.0f ms%n", lod, 1 << lod, k, (System.nanoTime() - l0) / 1e6);
            }
            double coverMs = (System.nanoTime() - c0) / 1e6;
            OUT.printf("exact window: %d tiles, %.1f M columns in %.0f ms on %d threads (%.2f us/column/thread)%n",
                    tiles, tiles * 65536 / 1e6, coverMs, threads, coverMs * 1e3 * threads / (tiles * 65536.0));
            // a second cover over the same window uploads nothing
            long r0 = System.nanoTime();
            src.cover(exact, 0, (int) x - 512, (int) z - 512, (int) x + 512, (int) z + 512);
            OUT.printf("re-cover of resident tiles: %.2f ms%n", (System.nanoTime() - r0) / 1e6);

            // preview: the seed-free stand-in, for comparison
            preview.previewLoadRaster(raster);
            for (int lod = 0; lod < AetherFarField.LODS; lod++) {
                int span = 256 << lod;
                int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                preview.previewGenerate(lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
            }
            compare(height, preview, x, z);

            int i = 0;
            for (AetherNative.Context ctx : new AetherNative.Context[] {exact, preview}) {
                if (noise != null) ctx.setCloudNoise(noise);
                ctx.update(x, y, z, 0);
                AetherNative.Frame f = new AetherNative.Frame();
                f.x = x;
                f.y = y;
                f.z = z;
                f.width = w;
                f.height = h;
                f.maxDistance = 40000;
                f.planetRadius = 6_371_000f;
                f.lodBias = 1;
                f.flags = AetherNative.FRAME_SHADOWS | AetherNative.FRAME_ATMOS | AetherNative.FRAME_SKY;
                AetherProbe.cameraMatrix(yaw, -6, 70, w / (double) h, f.invViewProj);
                double se = Math.toRadians(24), sa = Math.toRadians(yaw + 40);
                f.sunX = (float) (Math.sin(sa) * Math.cos(se));
                f.sunY = (float) Math.sin(se);
                f.sunZ = (float) (-Math.cos(sa) * Math.cos(se));
                int[] color = new int[w * h];
                ctx.render(f, color, null, null);
                ctx.render(f, color, null, null);
                images[i++] = color;
            }
            src.close();
        }

        BufferedImage img = new BufferedImage(w, h * 2 + 8, BufferedImage.TYPE_INT_RGB);
        for (int k = 0; k < 2; k++)
            for (int p = 0; p < w * h; p++) {
                int c = images[k][p];
                img.setRGB(p % w, k * (h + 8) + p / w, (c & 0xFF) << 16 | (c & 0xFF00) | (c >>> 16 & 0xFF));
            }
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(img, "png", out.toFile());
        OUT.println("wrote " + out + " (top: exact source, bottom: preview stand-in)");
    }

    /** How far the preview stand-in's surface is from the real one, over random columns within 20 km. */
    static void compare(TamrielHeight height, AetherNative.Context preview, double x, double z) {
        Random rnd = new Random(7);
        int k = 20000;
        double[] d = new double[k];
        MapSample m = new MapSample();
        double sum = 0, signed = 0, exactOverMap = 0, previewOverMap = 0;
        for (int i = 0; i < k; i++) {
            int px = (int) (x + (rnd.nextDouble() * 2 - 1) * 20000), pz = (int) (z + (rnd.nextDouble() * 2 - 1) * 20000);
            TamrielMap.sample(px, pz, m);
            double e = height.height(px, pz, m), p = preview.previewHeight(px + 0.5, pz + 0.5);
            d[i] = Math.abs(e - p);
            sum += d[i];
            signed += e - p;
            exactOverMap += e - m.elevation;
            previewOverMap += p - m.elevation;
        }
        Arrays.sort(d);
        OUT.printf("preview vs exact surface (%d columns): mean %.1f, median %.1f, p90 %.1f, p99 %.1f blocks%n", k,
                sum / k, d[k / 2], d[k * 9 / 10], d[k * 99 / 100]);
        OUT.printf("  signed: exact - preview %+.1f; over the map's macro elevation: exact %+.1f, preview %+.1f%n",
                signed / k, exactOverMap / k, previewOverMap / k);
    }
}

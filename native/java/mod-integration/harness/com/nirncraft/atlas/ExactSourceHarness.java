package com.nirncraft.atlas;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.terrain.TamrielHeight;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * Offline harness for the exact column source: the mod's real terrain height path (height-subset) with a world seed,
 * driven through AtlasExactSource into atlas exactly as the client does in single player, outside Minecraft.
 * Reports the column throughput, how far the seed-free preview stand-in is from the real surface, and renders the
 * same view from both sources side by side.
 * <pre>  .\native\java\mod-integration\harness\run_exact_harness.ps1 [-Seed 12345] [-X 0] [-Z 0] [-Yaw 100]</pre>
 */
public final class ExactSourceHarness {
    private ExactSourceHarness() {}

    static TamrielHeight forSeed(long seed) throws Exception {
        return HarnessSeed.height(seed);
    }

    /**
     * Offline stand-in for AtlasBiomes (the world's biome source needs a running game): a smooth tint field over the
     * world, the same for the single-player source and the in-process server, so the tint path is exercised end to end.
     */
    static final AtlasExactSource.Tinter TINTS = (lod, tx, tz, surface, grass, foliage, j) -> {
        int s = 1 << lod, step = AtlasNative.TILE / AtlasTints.GRID;
        for (int i = 0; i < AtlasTints.GRID; i++) {
            int x = tx * (AtlasNative.TILE << lod) + (i * step + step / 2) * s, z = tz * (AtlasNative.TILE << lod) + (j * step + step / 2) * s;
            int r = 70 + (int) (60 * (0.5 + 0.5 * Math.sin(x / 900.0))), b = 50 + (int) (40 * (0.5 + 0.5 * Math.cos(z / 700.0)));
            grass[j * AtlasTints.GRID + i] = r << 16 | 170 << 8 | b;
            foliage[j * AtlasTints.GRID + i] = (r - 20) << 16 | 130 << 8 | b;
        }
    };

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

        AtlasNative n = AtlasNative.load(dll);
        Path raster = data.resolve("tamriel_raster.bin");
        int[] noise = null;
        Path noisePath = data.resolve("cloud_noise.bin");
        if (Files.exists(noisePath)) {
            noise = new int[64 * 64 * 64];
            ByteBuffer.wrap(Files.readAllBytes(noisePath)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(noise);
        }
        double y = Math.max(groundExact, 63) + Double.parseDouble(System.getProperty("harness.above", "90"));
        int w = 1600, h = 900;
        int[][] images = new int[3][];
        float[][] depths = new float[3][];

        try (AtlasNative.Context exact = n.create(AtlasNative.BACKEND_VULKAN);
             AtlasNative.Context preview = n.create(AtlasNative.BACKEND_VULKAN);
             AtlasNative.Context served = n.create(AtlasNative.BACKEND_VULKAN)) {
            // exact: the mod's height through AtlasExactSource, the client's single-player path
            AtlasExactSource src = new AtlasExactSource(height, TINTS, () -> raster, threads);
            if (Boolean.getBoolean("harness.gpu")) {
                // the single-player source's heights on the GPU path (the server below stays on the CPU): the
                // server-fed vs single-player check at the end then compares GPU tiles with CPU tiles
                String why = src.enableGpu(n);
                OUT.println("exact heights: " + (why == null ? "GPU path " + src.gpuDevice() : "CPU (" + why + ")"));
            }
            src.prepare(exact);
            long c0 = System.nanoTime();
            int tiles = 0;
            for (int lod = 0; lod < AtlasFarField.LODS; lod++) {
                int span = 256 << lod;
                int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                long l0 = System.nanoTime();
                int calls = 0;
                while (src.cover(exact, lod, (cx - AtlasFarField.WINDOW) * span, (cz - AtlasFarField.WINDOW) * span,
                        (cx + AtlasFarField.WINDOW + 1) * span, (cz + AtlasFarField.WINDOW + 1) * span)) calls++;
                if (calls == 0) throw new IllegalStateException("refining: the first cover refined the whole window");
                int k = (2 * AtlasFarField.WINDOW + 1) * (2 * AtlasFarField.WINDOW + 1);
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
            codec(src, x, z);

            // preview: the seed-free stand-in, for comparison
            preview.previewLoadRaster(raster);
            long p0 = System.nanoTime();
            for (int lod = 0; lod < AtlasFarField.LODS; lod++) {
                int span = 256 << lod;
                int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                preview.previewGenerate(lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
            }
            long p1 = System.nanoTime();
            preview.update(x, Math.max(groundExact, 63) + 90, z, 0);
            OUT.printf("stand-in window: generate %.0f ms, clipmap build %.0f ms%n", (p1 - p0) / 1e6,
                    (System.nanoTime() - p1) / 1e6);
            compare(height, preview, x, z);
            serverFlow(n, served, height, raster, x, z, threads);

            int i = 0;
            for (AtlasNative.Context ctx : new AtlasNative.Context[] {exact, preview, served}) {
                if (noise != null) ctx.setCloudNoise(noise);
                ctx.update(x, y, z, 0);
                AtlasNative.Frame f = new AtlasNative.Frame();
                f.x = x;
                f.y = y;
                f.z = z;
                f.width = w;
                f.height = h;
                f.maxDistance = 40000;
                f.planetRadius = 6_371_000f;
                f.lodBias = 1;
                f.flags = AtlasNative.FRAME_SHADOWS | AtlasNative.FRAME_ATMOS | AtlasNative.FRAME_SKY
                        | (Boolean.getBoolean("harness.debugLod") ? AtlasNative.FRAME_DEBUG_LOD : 0);
                if (Boolean.getBoolean("harness.noShadows")) f.flags &= ~AtlasNative.FRAME_SHADOWS;
                AtlasProbe.cameraMatrix(yaw, Double.parseDouble(System.getProperty("harness.pitch", "-6")), 70, w / (double) h, f.invViewProj);
                double se = Math.toRadians(24), sa = Math.toRadians(yaw + 40);
                f.sunX = (float) (Math.sin(sa) * Math.cos(se));
                f.sunY = (float) Math.sin(se);
                f.sunZ = (float) (-Math.cos(sa) * Math.cos(se));
                int[] color = new int[w * h];
                float[] depth = new float[w * h];
                int[] normal = new int[w * h];
                ctx.render(f, color, normal, depth);
                ctx.render(f, color, normal, depth);
                String probeAt = System.getProperty("harness.probe");  // "x,y0,y1": one pixel column, first context
                if (probeAt != null && i == 0) {
                    String[] q = probeAt.split(",");
                    int px = Integer.parseInt(q[0]);
                    for (int py = Integer.parseInt(q[1]); py <= Integer.parseInt(q[2]); py++) {
                        int nv = normal[py * w + px];
                        double ex = (nv & 255) / 255.0 * 2 - 1, ez = (nv >> 8 & 255) / 255.0 * 2 - 1;
                        double ny = 1 - Math.abs(ex) - Math.abs(ez);
                        double nx = ny >= 0 ? ex : (1 - Math.abs(ez)) * Math.signum(ex), nz = ny >= 0 ? ez : (1 - Math.abs(ex)) * Math.signum(ez);
                        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        int c = color[py * w + px];
                        // world height of the hit: camera y + ray dir y * t (+ curvature drop ignored here: < 1 block at 1 km)
                        OUT.printf("  probe %d,%d: depth %.2f  n (%.2f %.2f %.2f)  cls %d  sun %d  rgb %d %d %d%n", px, py,
                                depth[py * w + px], nx / l, ny / l, nz / l, nv >> 16 & 255, nv >>> 24, c & 255, c >> 8 & 255, c >> 16 & 255);
                    }
                }
                depths[i] = depth;
                images[i++] = color;
            }
            src.close();
        }
        // multiplayer, server-fed: the same terrain as single player's exact source, to codec precision
        int off = 0, both = 0;
        for (int p = 0; p < w * h; p++) {
            float de = depths[0][p], ds = depths[2][p];
            if (de > 1e29f && ds > 1e29f) continue;
            both++;
            if (de != ds || images[0][p] != images[2][p]) off++;
        }
        OUT.printf("server-fed vs single-player exact render: %d of %d terrain px differ%n", off, both);
        if (off != 0) throw new IllegalStateException("server-fed far field differs from the single-player one");

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

    /**
     * Multiplayer, played back in process: AtlasServerSource asks (through the harness SkyNetwork), the "server"
     * answers each TileRequest with AtlasExactSource + AtlasTileCodec as AtlasTileServer does, until the window is
     * all exact. Then a second client on the same cache dir must need no traffic at all.
     */
    static void serverFlow(AtlasNative n, AtlasNative.Context served, TamrielHeight height, Path raster, double x,
                           double z, int threads) throws Exception {
        Path gameDir = Files.createTempDirectory("atlas-harness-client");
        long worldId = AtlasTileServer.worldId(12345L);
        AtlasExactSource server = new AtlasExactSource(height, TINTS, () -> null, threads);
        int[] tg = new int[AtlasTints.GRID * AtlasTints.GRID], tf = new int[tg.length];
        java.util.ArrayDeque<AtlasNet.TileRequest> wire = new java.util.ArrayDeque<>();
        com.nirncraft.network.SkyNetwork.toServer = m -> wire.add((AtlasNet.TileRequest) m);
        int nCols = AtlasNative.TILE * AtlasNative.TILE;
        float[] s = new float[nCols], w = new float[nCols];
        for (int pass = 0; pass < 2; pass++) {
            AtlasNative.Context ctx = pass == 0 ? served : n.create(AtlasNative.BACKEND_VULKAN);
            AtlasServerSource client = new AtlasServerSource(worldId, gameDir, () -> raster);
            client.prepare(ctx);
            long t0 = System.nanoTime(), bytes = 0;
            int requests = 0, tiles = 0, rounds = 0;
            boolean pending = true;
            while (pending) {
                if (++rounds > 2000) throw new IllegalStateException("server flow does not converge");
                pending = false;
                for (int lod = 0; lod < AtlasFarField.LODS; lod++) {
                    int span = 256 << lod;
                    int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                    pending |= client.cover(ctx, lod, (cx - AtlasFarField.WINDOW) * span, (cz - AtlasFarField.WINDOW) * span,
                            (cx + AtlasFarField.WINDOW + 1) * span, (cz + AtlasFarField.WINDOW + 1) * span);
                }
                for (AtlasNet.TileRequest r; (r = wire.poll()) != null; ) {
                    requests++;
                    if (r.lods().length > AtlasNet.MAX_REQUEST) throw new IllegalStateException("request too large");
                    for (int i = 0; i < r.lods().length; i++) {
                        // as AtlasTileServer.tile: fill, tints from that surface, encode
                        server.fill(r.lods()[i], r.txs()[i], r.tzs()[i], s, w);
                        server.tint(r.lods()[i], r.txs()[i], r.tzs()[i], s, tg, tf);
                        byte[] b = AtlasTileCodec.encode(r.lods()[i], s, w, tg, tf);
                        bytes += b.length;
                        tiles++;
                        client.accept(new AtlasNet.TileData(r.lods()[i], r.txs()[i], r.tzs()[i], b));
                    }
                }
            }
            OUT.printf("server-fed client %s: %d tiles in %d requests, %.1f MB on the wire, %d rounds, %.0f ms%n",
                    pass == 0 ? "(cold)" : "(cached)", tiles, requests, bytes / 1e6, rounds, (System.nanoTime() - t0) / 1e6);
            if (pass == 0 && tiles != 175) throw new IllegalStateException("server flow: " + tiles + " tiles, expected 175");
            if (pass == 1 && tiles != 0) throw new IllegalStateException("server flow: the tile cache was not used");
            client.close();
            if (pass == 1) ctx.close();
        }
        server.close();
    }

    /** The tile packet / cache format on real tiles: round trip within 1/16 block, and its size per LOD. */
    static void codec(AtlasExactSource src, double x, double z) {
        int n = AtlasNative.TILE * AtlasNative.TILE;
        float[] s = new float[n], w = new float[n], s2 = new float[n], w2 = new float[n];
        long total = 0;
        int count = 0;
        StringBuilder sizes = new StringBuilder();
        for (int lod = 0; lod < AtlasFarField.LODS; lod++) {
            int span = 256 << lod;
            int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
            long lodBytes = 0, e0 = System.nanoTime();
            double worst = 0;
            boolean waterOk = true;
            for (int k = 0; k < 3; k++) {
                src.fill(lod, cx - 1 + k, cz + k - 1, s, w);
                byte[] b = AtlasTileCodec.encode(lod, s, w);
                if (!AtlasTileCodec.decode(lod, b, s2, w2)) throw new IllegalStateException("codec: tile does not decode");
                if (AtlasTileCodec.decode(lod + 1, b, s2, w2)) throw new IllegalStateException("codec: wrong LOD accepted");
                AtlasTileCodec.decode(lod, b, s2, w2);
                float[] sq = s.clone(), wq = w.clone();
                AtlasTileCodec.quantize(lod, sq, wq);
                for (int i = 0; i < n; i++) {
                    if (Float.floatToIntBits(sq[i]) != Float.floatToIntBits(s2[i])
                            || Float.floatToIntBits(wq[i]) != Float.floatToIntBits(w2[i]))
                        throw new IllegalStateException("codec: decode differs from quantize");
                    worst = Math.max(worst, Math.abs(s[i] - s2[i]));
                    if (Float.isNaN(w[i]) != Float.isNaN(w2[i])) waterOk = false;
                    else if (!Float.isNaN(w[i])) worst = Math.max(worst, Math.abs(w[i] - w2[i]));
                }
                lodBytes += b.length;
            }
            if (worst > AtlasTileCodec.tolerance(lod) + 1e-4 || !waterOk)
                throw new IllegalStateException("codec: round trip off by " + worst);
            total += lodBytes;
            count += 3;
            sizes.append(String.format(" l%d %.0fK", lod, lodBytes / 3 / 1024.0));
        }
        AtlasTileCodec.decode(0, new byte[] {1, 2, 3}, s2, w2);  // garbage must be refused, not thrown
        // tint grids round trip exactly
        int g = AtlasTints.GRID * AtlasTints.GRID;
        int[] tg = new int[g], tf = new int[g], tg2 = new int[g], tf2 = new int[g];
        for (int i = 0; i < g; i++) {
            tg[i] = (i * 2654435761L % 0xFFFFFF > 0 ? (int) (i * 2654435761L % 0xFFFFFF) : 1);
            tf[i] = 0x10A040 + i;
        }
        src.fill(0, 0, 0, s, w);
        byte[] tb = AtlasTileCodec.encode(0, s, w, tg, tf);
        if (AtlasTileCodec.decode(0, tb, s2, w2, tg2, tf2) != 1 || !Arrays.equals(tg, tg2) || !Arrays.equals(tf, tf2))
            throw new IllegalStateException("codec: tint grids do not round trip");
        if (AtlasTileCodec.decode(0, AtlasTileCodec.encode(0, s, w), s2, w2, tg2, tf2) != 0)
            throw new IllegalStateException("codec: an untinted tile decodes as tinted");
        OUT.printf("tile codec: round trip within 1/16 voxel; mean %.0f KB per tile (raw 512 KB):%s%n",
                total / (double) count / 1024, sizes);
        OUT.printf("  a full 7-LOD window (175 tiles) is ~%.1f MB on the wire%n", total / (double) count * 175 / 1e6);
    }

    /** How far the preview stand-in's surface is from the real one, over random columns within 20 km. */
    static void compare(TamrielHeight height, AtlasNative.Context preview, double x, double z) {
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

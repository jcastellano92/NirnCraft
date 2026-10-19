package com.nirncraft.atlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Standalone check of the Java 25 boundary (no Minecraft): ABI handshake, downcall latency, preview terrain, a tile
 * uploaded from Java, weather and cloud noise from Java, one rendered frame written as PNG.
 *
 * <pre>java --enable-native-access=ALL-UNNAMED -cp native/build/java com.nirncraft.atlas.AtlasProbe
 *      native/build/Release/atlas_core.dll native/data native/out/java_probe.png [cpu|vulkan]</pre>
 */
public final class AtlasProbe {
    public static void main(String[] args) throws IOException {
        Path dll = Path.of(args.length > 0 ? args[0] : "native/build/Release/atlas_core.dll").toAbsolutePath();
        Path data = Path.of(args.length > 1 ? args[1] : "native/data");
        Path out = Path.of(args.length > 2 ? args[2] : "native/out/java_probe.png");
        int backend = args.length > 3 && args[3].equals("cpu") ? AtlasNative.BACKEND_CPU : AtlasNative.BACKEND_VULKAN;

        long t0 = System.nanoTime();
        AtlasNative n = AtlasNative.load(dll);
        System.out.printf("loaded %s in %.1f ms (java %s)%n", n.buildInfo(), (System.nanoTime() - t0) / 1e6,
                Runtime.version());

        // downcall latency: warm up, then the median of 15 batches of 200k calls
        for (int i = 0; i < 2_000_000; i++) n.abiVersion();
        double[] batch = new double[15];
        for (int b = 0; b < batch.length; b++) {
            long s = System.nanoTime();
            for (int i = 0; i < 200_000; i++) n.abiVersion();
            batch[b] = (System.nanoTime() - s) / 200_000.0;
        }
        Arrays.sort(batch);
        System.out.printf("atlas_abi_version downcall: %.2f ns median (critical)%n", batch[batch.length / 2]);

        try (AtlasNative.Context ctx = n.create(backend)) {
            ctx.previewLoadRaster(data.resolve("tamriel_raster.bin"));
            double x = 0, z = 0;
            double y = Math.max(ctx.previewHeight(x, z), 63) + 60;
            long g0 = System.nanoTime();
            for (int lod = 0; lod < 7; lod++) {
                int span = 256 << lod;
                int cx = Math.floorDiv((int) x, span), cz = Math.floorDiv((int) z, span);
                ctx.previewGenerate(lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
            }
            System.out.printf("preview tiles: %.0f ms%n", (System.nanoTime() - g0) / 1e6);

            // one LOD 0 tile authored in Java (as the mod will do from TamrielHeight): a stepped plinth east of
            // the camera, so the PNG shows the Java data landed
            int nCols = AtlasNative.TILE * AtlasNative.TILE;
            float[] surface = new float[nCols], water = new float[nCols];
            short[] top = new short[nCols], fill = new short[nCols];
            Arrays.fill(water, Float.NaN);
            for (int j = 0; j < 256; j++)
                for (int i = 0; i < 256; i++) {
                    int c = j * 256 + i;
                    int ring = Math.max(Math.abs(i - 128), Math.abs(j - 128));
                    surface[c] = ring < 40 ? 260 - (ring / 8) * 12 : ctx.previewHeight(256 + i + 0.5, j + 0.5);
                    top[c] = (short) (ring < 40 ? 10 : 2);  // sandstone plinth on grass
                    fill[c] = 3;
                }
            long u0 = System.nanoTime();
            ctx.uploadColumnTile(0, 1, 0, surface, water, top, fill, null, null);
            System.out.printf("java tile upload: %.2f ms%n", (System.nanoTime() - u0) / 1e6);

            Path noise = data.resolve("cloud_noise.bin");
            if (Files.exists(noise)) {
                int[] vol = new int[64 * 64 * 64];
                ByteBuffer.wrap(Files.readAllBytes(noise)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(vol);
                ctx.setCloudNoise(vol);
            }
            int[] nf = new int[128 * 128], nd = new int[128 * 128], ff = new int[128 * 128], fd = new int[128 * 128];
            for (int i = 0; i < nf.length; i++) {
                double wx = (i % 128) * 0.21, wz = (i / 128) * 0.17;
                double cov = 0.30 + 0.22 * Math.sin(wx) * Math.cos(wz);
                nf[i] = ff[i] = rgba(cov, 0.05, 0.0, 0.02);
                nd[i] = fd[i] = rgba(240 / 1024.0, 120 / 1024.0, 0.1024, 0.0);
            }
            ctx.setWeather(nf, nd, ff, fd, 0f);

            long b0 = System.nanoTime();
            ctx.update(x, y, z, 0);
            System.out.printf("clipmap build: %.0f ms%n", (System.nanoTime() - b0) / 1e6);

            AtlasNative.Frame f = new AtlasNative.Frame();
            f.x = x;
            f.y = y;
            f.z = z;
            f.width = 1280;
            f.height = 720;
            cameraMatrix(100, -3, 70, 1280 / 720.0, f.invViewProj);
            double se = Math.toRadians(30), sa = Math.toRadians(140);
            f.sunX = (float) (Math.sin(sa) * Math.cos(se));
            f.sunY = (float) Math.sin(se);
            f.sunZ = (float) (-Math.cos(sa) * Math.cos(se));
            int[] color = new int[f.width * f.height];
            float[] depth = new float[f.width * f.height];
            ctx.render(f, color, null, depth);  // first frame includes pipeline/buffer setup
            long r0 = System.nanoTime();
            ctx.render(f, color, null, depth);
            double wall = (System.nanoTime() - r0) / 1e6;
            AtlasNative.Stats st = ctx.stats();
            System.out.printf("frame: %.2f ms kernel, %.2f ms wall incl. readback; %d regions, %.1f MB scene%n",
                    st.frameMs(), wall, st.regions(), (st.nodeBytes() + st.leafBytes() + st.materialBytes()) / 1e6);
            // the plinth's top face is at y 260, 256..512 blocks east: check the centre column of the image
            float d = depth[(f.height / 2 + 30) * f.width + f.width / 2];
            System.out.printf("depth below centre: %.1f blocks%n", d);

            BufferedImage img = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < color.length; i++) {
                int c = color[i];
                int argb = 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >>> 16 & 0xFF);
                img.setRGB(i % f.width, i / f.width, argb);
            }
            Files.createDirectories(out.toAbsolutePath().getParent());
            ImageIO.write(img, "png", out.toFile());
            System.out.println("wrote " + out);
        }
    }

    static int rgba(double r, double g, double b, double a) {
        return q(r) | q(g) << 8 | q(b) << 16 | q(a) << 24;
    }

    static int q(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    /** Same convention as native/src/camera.hpp. */
    static void cameraMatrix(double yawDeg, double pitchDeg, double fovDeg, double aspect, float[] out) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double fx = Math.sin(yaw) * Math.cos(pitch), fy = Math.sin(pitch), fz = -Math.cos(yaw) * Math.cos(pitch);
        double rx = Math.cos(yaw), ry = 0, rz = Math.sin(yaw);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double f = 1.0 / Math.tan(Math.toRadians(fovDeg) / 2), nr = 0.05, fa = 100000;
        double[] ip = {aspect / f, 0, 0, 0, 0, 1 / f, 0, 0, 0, 0, 0, (nr - fa) / (2 * nr * fa), 0, 0, -1, (nr + fa) / (2 * nr * fa)};
        double[] iv = {rx, ry, rz, 0, ux, uy, uz, 0, -fx, -fy, -fz, 0, 0, 0, 0, 1};
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += iv[k * 4 + r] * ip[c * 4 + k];
                out[c * 4 + r] = (float) s;
            }
    }
}

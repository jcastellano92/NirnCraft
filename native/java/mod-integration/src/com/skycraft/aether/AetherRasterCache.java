package com.skycraft.aether;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds aether's raster file (the format of native/tools/export_raster.py, version 2) from the same bundled PNGs
 * MapRaster loads, once per mod version, into the game's cache directory. Used by the seed-free far-field source.
 */
final class AetherRasterCache {
    static final int W = 880, H = 672, CELL = 32, MIN_X = -15360, MIN_Z = -5120, CH = 14;
    // export_raster.py CLIMATES (part of the file contract with native/src/preview_source.cpp)
    static final List<String> CLIMATES = List.of("maritime", "skyrim_temperate", "skyrim_cold", "frozen", "desert",
            "tropical", "temperate", "ash", "swamp", "jungle", "savanna");
    private static final String ROOT = "/skycraft/tamriel/";

    private AetherRasterCache() {}

    /** Bytes of a complete file before its climate table (a shorter file is a damaged one). */
    static final long MIN_BYTES = 4 + 7 * 4 + (long) W * H * CH * 4 + (long) W * H + 4;

    /**
     * The raster file for this mod version: in the game directory's skycraft/cache, or, when that is read-only (a
     * Flatpak sandbox, a locked-down PC), in the same fallback places the native library goes (AetherLibrary).
     */
    static Path ensure(Path gameDir, String modVersion) throws IOException {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        dirs.add(gameDir.resolve("skycraft").resolve("cache"));
        for (Path p : AetherLibrary.directories(null, System.getenv())) dirs.add(p.resolveSibling(p.getFileName() + "-cache"));
        IOException first = null;
        for (Path d : dirs) {
            try {
                return ensureIn(d, modVersion);
            } catch (IOException | RuntimeException e) {
                IOException io = e instanceof IOException x ? x : new IOException(e);
                if (first == null) first = io;
                else first.addSuppressed(io);
            }
        }
        throw first != null ? first : new IOException("no directory for the raster cache");
    }

    private static Path ensureIn(Path dir, String modVersion) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("tamriel_raster-" + modVersion.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin");
        if (Files.isRegularFile(file) && Files.size(file) >= MIN_BYTES) return file;
        Files.deleteIfExists(file);  // damaged (a disk error, a cleaner): build it again
        Raster regions = read("regions.png"), elev = read("elevation.png"), feat = read("features.png"),
                ter = read("terrain.png"), wat = read("water.png"), coast = read("coast.png");
        Raster rain = readOptional("rainshadow.png"), heat = readOptional("heat.png");
        ByteBuffer b = ByteBuffer.allocate(4 + 7 * 4 + W * H * CH * 4 + W * H + 4 + 256).order(ByteOrder.LITTLE_ENDIAN);
        b.put("AETR".getBytes(StandardCharsets.US_ASCII));
        b.putInt(2).putInt(W).putInt(H).putInt(CH).putInt(MIN_X).putInt(MIN_Z).putInt(CELL);
        int[] px = new int[4];
        for (int z = 0; z < H; z++) {
            for (int x = 0; x < W; x++) {
                // channel order: elev coast riv_d riv_hw lake_sd water ridge rough crag dunes wet road rainshadow heat
                feat.getPixel(x, z, px);
                float rivD = (255 - px[0]) * 0.5f;
                if (rivD >= 127.5f) rivD = 400f;
                float ridge = px[1] / 255f, lake = (px[2] - 128) * 0.5f, road = px[3] / 255f;
                if (lake <= -64f) lake = -400f;
                int[] t = ter.getPixel(x, z, new int[4]);
                int[] w = wat.getPixel(x, z, new int[4]);
                int code = (w[0] << 8) | w[1];
                b.putFloat(elev.getSample(x, z, 0) / 32f - 64f);
                b.putFloat(coast.getSample(x, z, 0) - 32768f);
                b.putFloat(rivD);
                b.putFloat(w[2]);
                b.putFloat(lake);
                b.putFloat(code == 0 ? -1000f : code / 64f - 64f);
                b.putFloat(ridge);
                b.putFloat(t[0] / 255f);
                b.putFloat(t[1] / 255f);
                b.putFloat(t[2] / 255f);
                b.putFloat(t[3] / 255f);
                b.putFloat(road);
                b.putFloat(rain != null ? rain.getSample(x, z, 0) / 255f : 0f);
                b.putFloat(heat != null ? heat.getSample(x, z, 0) / 255f : 0f);
            }
        }
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) b.put((byte) regions.getSample(x, z, 0));
        byte[] climate = climates();
        b.putInt(climate.length);
        b.put(climate);
        b.flip();
        Path tmp = Files.createTempFile(dir, "raster", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                while (b.hasRemaining()) ch.write(b);
                ch.force(true);
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileSystemException e) {
                // another instance finished first (or the file system cannot move atomically): take a complete one
                if (!(Files.isRegularFile(file) && Files.size(file) >= MIN_BYTES))
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return file;
    }

    /** Climate code per region index, from regions.json "weather" (a tiny scan: index/weather pairs per entry). */
    private static byte[] climates() throws IOException {
        String json;
        try (InputStream in = AetherRasterCache.class.getResourceAsStream(ROOT + "regions.json")) {
            if (in == null) throw new IOException("missing regions.json");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\\{[^{}]*?\"index\"\\s*:\\s*(\\d+)[^{}]*?\"weather\"\\s*:\\s*\"([a-z_]+)\"").matcher(json);
        byte[] out = new byte[256];
        int max = 0;
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            int c = CLIMATES.indexOf(m.group(2));
            if (idx < 256) {
                out[idx] = (byte) (c >= 0 ? c : CLIMATES.indexOf("temperate"));
                max = Math.max(max, idx + 1);
            }
        }
        byte[] trimmed = new byte[Math.max(max, 1)];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private static Raster read(String name) throws IOException {
        Raster r = readOptional(name);
        if (r == null) throw new IOException("missing or malformed " + ROOT + name);
        return r;
    }

    private static Raster readOptional(String name) throws IOException {
        try (InputStream in = AetherRasterCache.class.getResourceAsStream(ROOT + name)) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            if (img == null || img.getWidth() != W || img.getHeight() != H) return null;
            return img.getRaster();
        }
    }
}

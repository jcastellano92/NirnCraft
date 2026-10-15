package com.skycraft.tamriel.terrain;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;

/**
 * The authored Tamriel raster (32-block cells), loaded once from the class path. Written by
 * {@code tools/worldgen/tamriel_map.py}; see that script for the exact encodings. Immutable after loading, so every
 * read is thread-safe.
 */
public final class MapRaster {
    static final String ROOT = "/skycraft/tamriel/";
    static final int W = TamrielMap.WIDTH_CELLS, H = TamrielMap.HEIGHT_CELLS, CELL = TamrielMap.CELL;

    // interleaved float channels per cell
    static final int ELEV = 0, COAST = 1, RIV_D = 2, RIV_HW = 3, LAKE_SD = 4, WATER = 5, RIDGE = 6, ROUGH = 7,
            CRAG = 8, DUNES = 9, WET = 10, ROAD = 11, BORDER = 12, RAINSHADOW = 13, HEAT = 14, CH = 15;
    /** Marks "no water body nearby" in the WATER channel. */
    static final float NO_WATER = -1000f;
    /** Blend partner search radius in cells, and the border blending half width in blocks. */
    static final int BLEND_CELLS = 3;
    static final float BLEND_RANGE = 80f;

    private static final MapRaster INSTANCE = load();

    final float[] data;
    final byte[] region;
    final byte[] blendRegion;
    final boolean ok;

    private MapRaster(float[] data, byte[] region, byte[] blendRegion, boolean ok) {
        this.data = data;
        this.region = region;
        this.blendRegion = blendRegion;
        this.ok = ok;
    }

    public static MapRaster get() {
        return INSTANCE;
    }

    public boolean loaded() {
        return ok;
    }

    // -----------------------------------------------------------------------------------------------------------------
    private static MapRaster load() {
        long t0 = System.nanoTime();
        float[] d = new float[W * H * CH];
        byte[] reg = new byte[W * H];
        byte[] blend = new byte[W * H];
        try {
            Raster regions = read("regions.png", 1);
            Raster elev = read("elevation.png", 1);
            Raster feat = read("features.png", 4);
            Raster ter = read("terrain.png", 4);
            Raster wat = read("water.png", 4);
            Raster coast = read("coast.png", 1);
            Raster rainshadow = readOptional("rainshadow.png", 1);
            Raster heat = readOptional("heat.png", 1);
            int[] px4 = new int[4];
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    int c = z * W + x, o = c * CH;
                    reg[c] = (byte) regions.getSample(x, z, 0);
                    d[o + ELEV] = elev.getSample(x, z, 0) / 32f - 64f;
                    d[o + COAST] = coast.getSample(x, z, 0) - 32768f;
                    feat.getPixel(x, z, px4);
                    d[o + RIV_D] = (255 - px4[0]) * 0.5f;
                    d[o + RIDGE] = px4[1] / 255f;
                    d[o + LAKE_SD] = (px4[2] - 128) * 0.5f;
                    d[o + ROAD] = px4[3] / 255f;
                    ter.getPixel(x, z, px4);
                    d[o + ROUGH] = px4[0] / 255f;
                    d[o + CRAG] = px4[1] / 255f;
                    d[o + DUNES] = px4[2] / 255f;
                    d[o + WET] = px4[3] / 255f;
                    wat.getPixel(x, z, px4);
                    int code = (px4[0] << 8) | px4[1];
                    d[o + WATER] = code == 0 ? NO_WATER : code / 64f - 64f;
                    d[o + RIV_HW] = px4[2];
                    if (d[o + RIV_D] >= 127.5f) d[o + RIV_D] = 400f;
                    if (d[o + LAKE_SD] <= -64f) d[o + LAKE_SD] = -400f;
                    d[o + RAINSHADOW] = rainshadow != null ? rainshadow.getSample(x, z, 0) / 255f : 0f;
                    d[o + HEAT] = heat != null ? heat.getSample(x, z, 0) / 255f : 0f;
                }
            }
        } catch (IOException | RuntimeException e) {
            Skycraft.LOGGER.error("Could not load the Tamriel map rasters; the world will be open sea", e);
            for (int c = 0; c < W * H; c++) {
                int o = c * CH;
                d[o + ELEV] = 20f;
                d[o + COAST] = -2000f;
                d[o + RIV_D] = 400f;
                d[o + LAKE_SD] = -400f;
                d[o + WATER] = NO_WATER;
            }
            return new MapRaster(d, reg, blend, false);
        }
        // region border distances and blend partners
        int r = BLEND_CELLS;
        for (int z = 0; z < H; z++) {
            for (int x = 0; x < W; x++) {
                int c = z * W + x;
                byte own = reg[c];
                float best = 999f;
                byte partner = own;
                for (int dz = -r; dz <= r; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= H) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= W) continue;
                        byte other = reg[zz * W + xx];
                        if (other == own) continue;
                        float dist = (float) Math.sqrt(dx * dx + dz * dz) * CELL - CELL * 0.5f;
                        if (dist < best) {
                            best = dist;
                            partner = other;
                        }
                    }
                }
                d[c * CH + BORDER] = best;
                blend[c] = partner;
            }
        }
        Skycraft.LOGGER.info("Loaded the Tamriel map ({}x{} cells) in {} ms", W, H, (System.nanoTime() - t0) / 1_000_000);
        return new MapRaster(d, reg, blend, true);
    }

    private static Raster read(String name, int bands) throws IOException {
        try (InputStream in = MapRaster.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IOException("missing " + ROOT + name);
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("unreadable " + name);
            if (img.getWidth() != W || img.getHeight() != H) {
                throw new IOException(name + " is " + img.getWidth() + "x" + img.getHeight() + ", expected " + W + "x" + H);
            }
            Raster ras = img.getRaster();
            if (ras.getNumBands() < bands) throw new IOException(name + " has " + ras.getNumBands() + " bands");
            return ras;
        }
    }

    private static Raster readOptional(String name, int bands) throws IOException {
        try (InputStream in = MapRaster.class.getResourceAsStream(ROOT + name)) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            if (img.getWidth() != W || img.getHeight() != H) return null;
            Raster ras = img.getRaster();
            return ras.getNumBands() >= bands ? ras : null;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Small seed-independent domain warp so borders and coasts don't follow the 32-block grid. */
    static double warpX(int x, int z) {
        return MapNoise.value(x, z, 96, 101) * 16 + MapNoise.value(x, z, 29, 102) * 6;
    }

    static double warpZ(int x, int z) {
        return MapNoise.value(x, z, 96, 103) * 16 + MapNoise.value(x, z, 29, 104) * 6;
    }

    /** True when the bilinear footprint of (x, z) lies inside the raster. */
    public static boolean covers(double x, double z) {
        return x >= TamrielMap.MIN_X + CELL && x < TamrielMap.MAX_X - CELL
                && z >= TamrielMap.MIN_Z + CELL && z < TamrielMap.MAX_Z - CELL;
    }

    /** Region index of the cell containing the (already warped) point. */
    int regionAt(double wx, double wz) {
        int cx = clampI((int) Math.floor((wx - TamrielMap.MIN_X) / CELL), W - 1);
        int cz = clampI((int) Math.floor((wz - TamrielMap.MIN_Z) / CELL), H - 1);
        return region[cz * W + cx] & 0xFF;
    }

    public int regionIndex(int x, int z) {
        return regionAt(x + warpX(x, z), z + warpZ(x, z));
    }

    /** The road channel saturates at this many blocks from an authored road centre line (255 - 4 * blocks). */
    public static final float ROAD_REACH = 63.75f;

    /**
     * Blocks from (x, z) to the nearest authored road centre line (or settlement pad) of the raster, or
     * {@link #ROAD_REACH} when none is near. The channel stores the distance at 32-block cells; a plain bilinear
     * read over-estimates it by up to 16 blocks right on a line, so the four surrounding cell distances are fitted
     * with the plane of a straight road (unit gradient, signs chosen for the best fit) and the answer is clamped
     * between the 1-Lipschitz lower bound and the bilinear value. Allocation free; caller checked {@link #covers}.
     */
    public float roadDistance(int x, int z) {
        double wx = x + warpX(x, z), wz = z + warpZ(x, z);
        double fx = (wx - TamrielMap.MIN_X) / CELL - 0.5, fz = (wz - TamrielMap.MIN_Z) / CELL - 0.5;
        int ix = clampI((int) Math.floor(fx), W - 2), iz = clampI((int) Math.floor(fz), H - 2);
        float tx = (float) MapNoise.clamp(fx - ix, 0, 1), tz = (float) MapNoise.clamp(fz - iz, 0, 1);
        int o00 = (iz * W + ix) * CH + ROAD, o01 = o00 + W * CH;
        float[] d = data;
        float a = (1f - d[o00]) * ROAD_REACH, b = (1f - d[o00 + CH]) * ROAD_REACH;
        float c = (1f - d[o01]) * ROAD_REACH, e = (1f - d[o01 + CH]) * ROAD_REACH;
        float min = Math.min(Math.min(a, b), Math.min(c, e));
        if (min >= ROAD_REACH - 0.01f) return ROAD_REACH;
        float lin = (1 - tz) * ((1 - tx) * a + tx * b) + tz * ((1 - tx) * c + tx * e);
        float u = tx * CELL, w = tz * CELL;
        // lower bound: distance is 1-Lipschitz, so no corner can be nearer than its value minus the way to it
        float lb = 0f;
        lb = Math.max(lb, a - (float) Math.sqrt(u * u + w * w));
        lb = Math.max(lb, b - (float) Math.sqrt((CELL - u) * (CELL - u) + w * w));
        lb = Math.max(lb, c - (float) Math.sqrt(u * u + (CELL - w) * (CELL - w)));
        lb = Math.max(lb, e - (float) Math.sqrt((CELL - u) * (CELL - u) + (CELL - w) * (CELL - w)));
        // plane of a straight line through the cell: v = a' + qx * u + qz * w with signed corner distances
        float best = Float.MAX_VALUE, bq = 0, bz = 0;
        for (int m = 0; m < 8; m++) {
            float sb = (m & 1) == 0 ? b : -b, sc = (m & 2) == 0 ? c : -c, se = (m & 4) == 0 ? e : -e;
            float resid = Math.abs(a + se - sb - sc);
            float qx = (sb - a) / CELL, qz = (sc - a) / CELL;
            float score = resid + CELL * Math.abs((float) Math.sqrt(qx * qx + qz * qz) - 1f);
            if (score < best) {
                best = score;
                bq = qx;
                bz = qz;
            }
        }
        float v = Math.min(lin, ROAD_REACH);
        if (best < 4f) v = Math.min(v, Math.abs(a + bq * u + bz * w));
        return Math.max(lb, Math.min(v, ROAD_REACH));
    }

    /** Fills {@code out} from the raster (caller checked {@link #covers}). */
    public void sample(int x, int z, MapSample out) {
        double wx = x + warpX(x, z), wz = z + warpZ(x, z);
        double fx = (wx - TamrielMap.MIN_X) / CELL - 0.5, fz = (wz - TamrielMap.MIN_Z) / CELL - 0.5;
        int ix = clampI((int) Math.floor(fx), W - 2), iz = clampI((int) Math.floor(fz), H - 2);
        float tx = (float) MapNoise.clamp(fx - ix, 0, 1), tz = (float) MapNoise.clamp(fz - iz, 0, 1);
        int o00 = (iz * W + ix) * CH, o10 = o00 + CH, o01 = o00 + W * CH, o11 = o01 + CH;
        float w00 = (1 - tx) * (1 - tz), w10 = tx * (1 - tz), w01 = (1 - tx) * tz, w11 = tx * tz;
        float[] d = data;

        out.inTamriel = true;
        elevation(x, z, out);
        out.coastDistance = lerp(d, COAST, o00, o10, o01, o11, w00, w10, w01, w11);
        out.riverDistance = riverDistance(d, o00, o10, o01, o11, tx, tz, lerp(d, RIV_D, o00, o10, o01, o11, w00, w10, w01, w11));
        out.lakeDistance = lerp(d, LAKE_SD, o00, o10, o01, o11, w00, w10, w01, w11);
        out.ridge = lerp(d, RIDGE, o00, o10, o01, o11, w00, w10, w01, w11);
        out.roughness = lerp(d, ROUGH, o00, o10, o01, o11, w00, w10, w01, w11);
        out.crag = lerp(d, CRAG, o00, o10, o01, o11, w00, w10, w01, w11);
        out.dunes = lerp(d, DUNES, o00, o10, o01, o11, w00, w10, w01, w11);
        out.wetland = lerp(d, WET, o00, o10, o01, o11, w00, w10, w01, w11);
        out.road = lerp(d, ROAD, o00, o10, o01, o11, w00, w10, w01, w11);
        out.rainshadow = lerp(d, RAINSHADOW, o00, o10, o01, o11, w00, w10, w01, w11);
        out.heat = lerp(d, HEAT, o00, o10, o01, o11, w00, w10, w01, w11);

        // water level and river width: average only the corners that have a water body
        float ws = 0, wl = 0, hw = 0;
        if (d[o00 + WATER] > NO_WATER) { ws += w00; wl += w00 * d[o00 + WATER]; hw += w00 * d[o00 + RIV_HW]; }
        if (d[o10 + WATER] > NO_WATER) { ws += w10; wl += w10 * d[o10 + WATER]; hw += w10 * d[o10 + RIV_HW]; }
        if (d[o01 + WATER] > NO_WATER) { ws += w01; wl += w01 * d[o01 + WATER]; hw += w01 * d[o01 + RIV_HW]; }
        if (d[o11 + WATER] > NO_WATER) { ws += w11; wl += w11 * d[o11 + WATER]; hw += w11 * d[o11 + RIV_HW]; }
        if (ws > 1e-4f) {
            out.waterLevel = wl / ws;
            out.riverHalfWidth = hw / ws;
        } else {
            out.waterLevel = Float.NaN;
            out.riverHalfWidth = 0f;
        }

        Hydrology.get().apply(x, z, out);

        int rc = regionAt(wx, wz);
        out.region = rc;
        int cx = clampI((int) Math.floor((wx - TamrielMap.MIN_X) / CELL), W - 1);
        int cz = clampI((int) Math.floor((wz - TamrielMap.MIN_Z) / CELL), H - 1);
        int partner = blendRegion[cz * W + cx] & 0xFF;
        float border = lerp(d, BORDER, o00, o10, o01, o11, w00, w10, w01, w11);
        if (partner != rc && border < BLEND_RANGE) {
            out.blendRegion = partner;
            out.blendWeight = 0.5f * (1f - Math.max(0f, border) / BLEND_RANGE);
        } else {
            out.blendRegion = rc;
            out.blendWeight = 0f;
        }
        TamrielMap.deriveStrengths(out);
        MapPeaks.apply(x, z, out);
    }

    /**
     * Distance to the nearest river centre line. The raster holds exact distances at the cell centres, but a bilinear
     * blend of an unsigned distance can't reach zero between them (it bottoms out up to half a cell off a line that
     * runs between two centres), which broke rivers narrower than a cell into a chain of puddles. Near a river, fit a
     * planar signed distance through the four corners instead (trying each sign pattern: the line passes between the
     * corners of opposite sign) and use it when it explains them well, i.e. a straight stretch of one river.
     */
    private static float riverDistance(float[] d, int o00, int o10, int o01, int o11, float tx, float tz, float bilinear) {
        float d00 = d[o00 + RIV_D], d10 = d[o10 + RIV_D], d01 = d[o01 + RIV_D], d11 = d[o11 + RIV_D];
        if (Math.min(Math.min(d00, d10), Math.min(d01, d11)) > CELL * 1.5f
                || Math.max(Math.max(d00, d10), Math.max(d01, d11)) >= 127f) return bilinear;
        float best = Float.MAX_VALUE, value = bilinear;
        for (int m = 0; m < 8; m++) {
            float v00 = d00, v10 = (m & 1) != 0 ? -d10 : d10, v01 = (m & 2) != 0 ? -d01 : d01, v11 = (m & 4) != 0 ? -d11 : d11;
            float b = ((v10 - v00) + (v11 - v01)) * 0.5f, c = ((v01 - v00) + (v11 - v10)) * 0.5f;
            float twist = v00 - v10 - v01 + v11;
            float score = Math.abs(twist) / CELL + Math.abs((float) Math.sqrt(b * b + c * c) / CELL - 1f);
            if (score < best) {
                best = score;
                float a = (v00 + v10 + v01 + v11) * 0.25f - (b + c) * 0.5f;
                value = Math.abs(a + b * tx + c * tz);
            }
        }
        return best < 0.25f ? Math.min(bilinear, value) : bilinear;
    }

    /**
     * Macro elevation and its slope (blocks per block) at the unwarped column: Catmull-Rom over 4x4 cells. A bilinear
     * blend creased every slope along the 32-block cell grid, and the borders' domain warp rippled steep slopes in a
     * regular lattice; the cubic is smooth, passes through the cell values and gives the slope for free.
     */
    private void elevation(int x, int z, MapSample out) {
        double fx = (x - TamrielMap.MIN_X) / (double) CELL - 0.5, fz = (z - TamrielMap.MIN_Z) / (double) CELL - 0.5;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz;
        double tx2 = tx * tx, tx3 = tx2 * tx, tz2 = tz * tz, tz3 = tz2 * tz;
        double ax0 = -0.5 * tx3 + tx2 - 0.5 * tx, ax1 = 1.5 * tx3 - 2.5 * tx2 + 1, ax2 = -1.5 * tx3 + 2 * tx2 + 0.5 * tx,
                ax3 = 0.5 * tx3 - 0.5 * tx2;
        double bx0 = -1.5 * tx2 + 2 * tx - 0.5, bx1 = 4.5 * tx2 - 5 * tx, bx2 = -4.5 * tx2 + 4 * tx + 0.5, bx3 = 1.5 * tx2 - tx;
        double az0 = -0.5 * tz3 + tz2 - 0.5 * tz, az1 = 1.5 * tz3 - 2.5 * tz2 + 1, az2 = -1.5 * tz3 + 2 * tz2 + 0.5 * tz,
                az3 = 0.5 * tz3 - 0.5 * tz2;
        double bz0 = -1.5 * tz2 + 2 * tz - 0.5, bz1 = 4.5 * tz2 - 5 * tz, bz2 = -4.5 * tz2 + 4 * tz + 0.5, bz3 = 1.5 * tz2 - tz;
        int c0 = clampI(ix - 1, W - 1), c1 = clampI(ix, W - 1), c2 = clampI(ix + 1, W - 1), c3 = clampI(ix + 2, W - 1);
        double e = 0, ex = 0, ez = 0;
        float[] d = data;
        for (int j = 0; j < 4; j++) {
            int row = clampI(iz - 1 + j, H - 1) * W;
            double v0 = d[(row + c0) * CH + ELEV], v1 = d[(row + c1) * CH + ELEV], v2 = d[(row + c2) * CH + ELEV],
                    v3 = d[(row + c3) * CH + ELEV];
            double r = ax0 * v0 + ax1 * v1 + ax2 * v2 + ax3 * v3;
            double rx = bx0 * v0 + bx1 * v1 + bx2 * v2 + bx3 * v3;
            double wz = j == 0 ? az0 : j == 1 ? az1 : j == 2 ? az2 : az3;
            double dz = j == 0 ? bz0 : j == 1 ? bz1 : j == 2 ? bz2 : bz3;
            e += wz * r;
            ex += wz * rx;
            ez += dz * r;
        }
        out.elevation = (float) e;
        double gradNorm = Math.sqrt(ex * ex + ez * ez);
        out.slope = (float) (gradNorm / CELL);
        if (gradNorm > 1e-4) {
            out.aspectX = (float) (-ex / gradNorm);
            out.aspectZ = (float) (-ez / gradNorm);
        } else {
            out.aspectX = 0f;
            out.aspectZ = 0f;
        }
    }

    private static float lerp(float[] d, int ch, int o00, int o10, int o01, int o11,
                              float w00, float w10, float w01, float w11) {
        return d[o00 + ch] * w00 + d[o10 + ch] * w10 + d[o01 + ch] * w01 + d[o11 + ch] * w11;
    }

    private static int clampI(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    /** Raw cell value, for previews and the map screen. */
    public float cell(int cx, int cz, int channel) {
        return data[(clampI(cz, H - 1) * W + clampI(cx, W - 1)) * CH + channel];
    }

    public int cellRegion(int cx, int cz) {
        return region[clampI(cz, H - 1) * W + clampI(cx, W - 1)] & 0xFF;
    }
}

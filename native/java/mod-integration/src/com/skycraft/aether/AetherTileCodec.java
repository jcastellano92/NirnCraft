package com.skycraft.aether;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Wire and cache format of one far-field column tile's geometry (256 x 256 surface and water heights), for the
 * server -> client tile packet and the per-world tile caches. Heights are quantised to 1/8 of the tile's voxel
 * (1/8 block at LOD 0, 8 blocks at LOD 6: the clipmap cannot resolve finer), by interval: a height decodes to the
 * middle of its 1/8-voxel interval, and voxel centres and faces fall on interval bounds, so a height never crosses
 * one in transit. The single-player exact source quantises the same way (quantize), so a server-fed far field is
 * bit-identical to the single-player one. The surface is predicted from its left, upper and upper-left neighbours (the median edge detector of
 * LOCO-I), water from its left neighbour (mostly none, or one flat level), and the residuals deflated.
 */
final class AetherTileCodec {
    private AetherTileCodec() {}

    static final int VERSION = 1;
    private static final int N = AetherNative.TILE * AetherNative.TILE, W = AetherNative.TILE;
    /** Quantisation: q = (h + 64) * 8 / 2^lod, so y -64 .. 8127 fits an unsigned 16-bit value at every LOD. */
    private static final float BASE = 64f;
    /** Largest encoded tile accepted (a raw tile is 256 KB of residuals; random noise would not compress). */
    static final int MAX_BYTES = 320 * 1024;

    /** Largest error of a decoded height at LOD `lod`: half a quantisation step. */
    static float tolerance(int lod) {
        return 0.5f / step(lod);
    }

    /** In place, exactly what encode + decode would give (the single-player path, without the bytes). */
    static void quantize(int lod, float[] surface, float[] water) {
        float st = step(lod);
        for (int c = 0; c < N; c++) {
            surface[c] = dequant(quant(surface[c], st), st);
            if (!Float.isNaN(water[c])) water[c] = dequant(quant(water[c], st), st);
        }
    }

    private static float step(int lod) {
        return 8f / (1 << lod);
    }

    static byte[] encode(int lod, float[] surface, float[] water) {
        byte[] raw = new byte[2 + 4 * N];
        raw[0] = (byte) VERSION;
        raw[1] = (byte) lod;
        float st = step(lod);
        int p = 2;
        int[] q = new int[N];
        for (int i = 0; i < N; i++) q[i] = quant(surface[i], st);
        for (int j = 0; j < W; j++)
            for (int i = 0; i < W; i++) {
                int c = j * W + i;
                int r = q[c] - predict(q, i, j);
                raw[p++] = (byte) r;
                raw[p++] = (byte) (r >> 8);
            }
        int prev = 0;
        for (int c = 0; c < N; c++) {
            int wq = Float.isNaN(water[c]) ? 0 : quant(water[c], st) + 1;
            int r = wq - prev;
            prev = wq;
            raw[p++] = (byte) r;
            raw[p++] = (byte) (r >> 8);
        }
        Deflater d = new Deflater(6);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    /** Decodes into surface and water (NaN = none). False when the bytes are not a tile of this version and LOD. */
    static boolean decode(int lod, byte[] bytes, float[] surface, float[] water) {
        if (bytes == null || bytes.length > MAX_BYTES) return false;
        byte[] raw = new byte[2 + 4 * N];
        Inflater in = new Inflater();
        try {
            in.setInput(bytes);
            int got = 0;
            while (got < raw.length && !in.finished()) {
                int k = in.inflate(raw, got, raw.length - got);
                if (k == 0 && (in.needsInput() || in.needsDictionary())) return false;
                got += k;
            }
            if (got != raw.length || !in.finished() || (raw[0] & 0xFF) != VERSION || raw[1] != lod) return false;
        } catch (DataFormatException e) {
            return false;
        } finally {
            in.end();
        }
        float st = step(lod);
        int p = 2;
        int[] q = new int[N];
        for (int j = 0; j < W; j++)
            for (int i = 0; i < W; i++) {
                int c = j * W + i;
                int r = (short) ((raw[p] & 0xFF) | raw[p + 1] << 8);
                p += 2;
                q[c] = (predict(q, i, j) + r) & 0xFFFF;
                surface[c] = dequant(q[c], st);
            }
        int prev = 0;
        for (int c = 0; c < N; c++) {
            int r = (short) ((raw[p] & 0xFF) | raw[p + 1] << 8);
            p += 2;
            int wq = (prev + r) & 0xFFFF;
            prev = wq;
            water[c] = wq == 0 ? Float.NaN : dequant(wq - 1, st);
        }
        return true;
    }

    /** The 1/8-voxel interval a height lies in. */
    private static int quant(float h, float step) {
        return (int) Math.max(0, Math.min(0xFFFE, Math.floor((h + BASE) * step)));
    }

    /** The middle of interval q. */
    private static float dequant(int q, float step) {
        return (q + 0.5f) / step - BASE;
    }

    private static int predict(int[] q, int i, int j) {
        if (j == 0) return i == 0 ? 0 : q[i - 1];
        if (i == 0) return q[(j - 1) * W];
        int a = q[j * W + i - 1], b = q[(j - 1) * W + i], c = q[(j - 1) * W + i - 1];
        if (c >= Math.max(a, b)) return Math.min(a, b);
        if (c <= Math.min(a, b)) return Math.max(a, b);
        return a + b - c;
    }
}

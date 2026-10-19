package com.nirncraft.atlas;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * LOCO-I), water from its left neighbour (mostly none, or one flat level), and the residuals deflated. Version 2 adds,
 * when the server knows them, the biome tint grids (AtlasTints.GRID^2 grass and foliage rgb, per-channel deltas).
 * Version 3 adds the surface blocks the world's material rules paint (AtlasSurface): a table of block names (at most
 * 255, in order of first use, so the bytes are deterministic) and per column the top block, the block under it and the
 * column flags (snow cover, frozen water), each a byte plane coded as the difference to its left neighbour.
 */
final class AtlasTileCodec {
    private AtlasTileCodec() {}

    static final int VERSION = 3;
    /** What the tiles contain (not their format): caches made before a content change are not reused. 1: roads. */
    static final int CONTENT = 3;
    private static final int N = AtlasNative.TILE * AtlasNative.TILE, W = AtlasNative.TILE;
    private static final int HEAD = 3, G = AtlasTints.GRID * AtlasTints.GRID, TINT_BYTES = 2 * 3 * G;
    private static final int FLAG_TINTS = 1, FLAG_SURFACE = 2;

    /** A tile's surface blocks: a block-name table and, per column, top / under indices into it and flags. */
    static final class Surface {
        final List<String> names = new ArrayList<>();
        private final Map<String, Integer> at = new HashMap<>();
        final byte[] top = new byte[N], under = new byte[N], flags = new byte[N];

        void clear() {
            names.clear();
            at.clear();
        }

        /** The table index of a block (added on first use; capped at 255 entries, later blocks share the last). */
        synchronized byte index(net.minecraft.world.level.block.state.BlockState s) {
            String n = AtlasSurface.name(s);
            Integer i = at.get(n);
            if (i == null) {
                if (names.size() >= 255) return (byte) 254;
                i = names.size();
                names.add(n);
                at.put(n, i);
            }
            return (byte) (int) i;
        }

        /**
         * Renumbers the table in order of first use over the columns (row-major), so the bytes do not depend on which
         * thread evaluated which row first.
         */
        void canonical() {
            int[] map = new int[256];
            java.util.Arrays.fill(map, -1);
            List<String> order = new ArrayList<>();
            for (byte[] plane : new byte[][] {top, under})
                for (int c = 0; c < N; c++) {
                    int v = plane[c] & 0xFF;
                    if (v < names.size() && map[v] < 0) {
                        map[v] = order.size();
                        order.add(names.get(v));
                    }
                }
            for (byte[] plane : new byte[][] {top, under})
                for (int c = 0; c < N; c++) {
                    int v = plane[c] & 0xFF;
                    plane[c] = (byte) (v < names.size() ? map[v] : 0);
                }
            clear();
            for (String n : order) {
                at.put(n, names.size());
                names.add(n);
            }
        }
    }
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
        return encode(lod, surface, water, null, null);
    }

    /** grass / foliage: the tile's tint grids (AtlasTints.GRID^2 rgb), or null for none. */
    static byte[] encode(int lod, float[] surface, float[] water, int[] grass, int[] foliage) {
        return encode(lod, surface, water, grass, foliage, null);
    }

    /** True when tile bytes of this codec carry surface blocks (inflates the header only). */
    static boolean hasSurface(byte[] bytes) {
        byte[] head = new byte[HEAD];
        Inflater in = new Inflater();
        try {
            in.setInput(bytes);
            int got = 0;
            while (got < HEAD && !in.finished()) {
                int k = in.inflate(head, got, HEAD - got);
                if (k == 0 && (in.needsInput() || in.needsDictionary())) return false;
                got += k;
            }
            return got == HEAD && (head[0] & 0xFF) == VERSION && (head[2] & FLAG_SURFACE) != 0;
        } catch (DataFormatException e) {
            return false;
        } finally {
            in.end();
        }
    }

    /** As encode, plus the tile's surface blocks (null for none; canonicalised in place first). */
    static byte[] encode(int lod, float[] surface, float[] water, int[] grass, int[] foliage, Surface blocks) {
        byte[] out = encodeRaw(lod, surface, water, grass, foliage, blocks);
        // never more than a client accepts (MAX_BYTES): without the surface planes (the dressing paints it) if need be
        return out.length > MAX_BYTES && blocks != null ? encodeRaw(lod, surface, water, grass, foliage, null) : out;
    }

    private static byte[] encodeRaw(int lod, float[] surface, float[] water, int[] grass, int[] foliage, Surface blocks) {
        boolean tints = grass != null && foliage != null;
        byte[] table = new byte[0];
        if (blocks != null) {
            blocks.canonical();
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            t.write(blocks.names.size());
            for (String n : blocks.names) {
                byte[] b = n.getBytes(StandardCharsets.UTF_8);
                t.write(Math.min(255, b.length));
                t.write(b, 0, Math.min(255, b.length));
            }
            table = t.toByteArray();
        }
        int surfBytes = blocks != null ? table.length + 3 * N : 0;
        byte[] raw = new byte[HEAD + 4 * N + (tints ? TINT_BYTES : 0) + surfBytes];
        raw[0] = (byte) VERSION;
        raw[1] = (byte) lod;
        raw[2] = (byte) ((tints ? FLAG_TINTS : 0) | (blocks != null ? FLAG_SURFACE : 0));
        float st = step(lod);
        int p = HEAD;
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
        if (tints)
            for (int[] plane : new int[][] {grass, foliage})
                for (int ch = 16; ch >= 0; ch -= 8) {
                    int prevC = 0;
                    for (int c = 0; c < G; c++) {
                        int v = plane[c] >> ch & 0xFF;
                        raw[p++] = (byte) (v - prevC);
                        prevC = v;
                    }
                }
        if (blocks != null) {
            System.arraycopy(table, 0, raw, p, table.length);
            p += table.length;
            for (byte[] plane : new byte[][] {blocks.top, blocks.under, blocks.flags})
                for (int j = 0; j < W; j++) {
                    byte prevB = 0;
                    for (int i = 0; i < W; i++) {
                        byte v = plane[j * W + i];
                        raw[p++] = (byte) (v - prevB);
                        prevB = v;
                    }
                }
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
        return decode(lod, bytes, surface, water, null, null) >= 0;
    }

    /**
     * Decodes into surface and water, and into grass / foliage (when given) the tint grids. Returns -1 when the bytes
     * are not a tile of this version and LOD, 0 for a tile without tints, 1 with them.
     */
    static int decode(int lod, byte[] bytes, float[] surface, float[] water, int[] grass, int[] foliage) {
        int r = decode(lod, bytes, surface, water, grass, foliage, null);
        return r < 0 ? -1 : r & FLAG_TINTS;
    }

    /**
     * Decodes into surface and water, the tint grids (when given) and the surface blocks (when given). Returns -1 when
     * the bytes are not a tile of this version and LOD, else the flags present (1: tints, 2: surface blocks).
     */
    static int decode(int lod, byte[] bytes, float[] surface, float[] water, int[] grass, int[] foliage, Surface blocks) {
        if (bytes == null || bytes.length > MAX_BYTES) return -1;
        byte[] raw = new byte[HEAD + 4 * N + TINT_BYTES + 1 + 255 * 256 + 3 * N];
        int got = 0;
        Inflater in = new Inflater();
        try {
            in.setInput(bytes);
            while (got < raw.length && !in.finished()) {
                int k = in.inflate(raw, got, raw.length - got);
                if (k == 0 && (in.needsInput() || in.needsDictionary())) return -1;
                got += k;
            }
            if (!in.finished() || got < HEAD || (raw[0] & 0xFF) != VERSION || raw[1] != lod) return -1;
        } catch (DataFormatException e) {
            return -1;
        } finally {
            in.end();
        }
        boolean tints = (raw[2] & FLAG_TINTS) != 0, surf = (raw[2] & FLAG_SURFACE) != 0;
        int fixed = HEAD + 4 * N + (tints ? TINT_BYTES : 0);
        if (surf ? got < fixed + 1 + 3 * N : got != fixed) return -1;
        float st = step(lod);
        int p = HEAD;
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
        if (tints) {
            if (grass != null && foliage != null)
                for (int[] plane : new int[][] {grass, foliage}) {
                    java.util.Arrays.fill(plane, 0, G, 0);
                    for (int ch = 16; ch >= 0; ch -= 8) {
                        int prevC = 0;
                        for (int c = 0; c < G; c++) {
                            prevC = (prevC + raw[p++]) & 0xFF;
                            plane[c] |= prevC << ch;
                        }
                    }
                }
            else p += TINT_BYTES;
        }
        if (surf) {
            int count = raw[p++] & 0xFF;
            List<String> names = new ArrayList<>(count);
            for (int k = 0; k < count; k++) {
                int len = raw[p++] & 0xFF;
                if (p + len > got) return -1;
                names.add(new String(raw, p, len, StandardCharsets.UTF_8));
                p += len;
            }
            if (got != p + 3 * N) return -1;
            if (blocks != null) {
                blocks.clear();
                for (String n : names) {
                    blocks.at.put(n, blocks.names.size());
                    blocks.names.add(n);
                }
                for (byte[] plane : new byte[][] {blocks.top, blocks.under, blocks.flags})
                    for (int j = 0; j < W; j++) {
                        byte prevB = 0;
                        for (int i = 0; i < W; i++) {
                            prevB = (byte) (prevB + raw[p++]);
                            plane[j * W + i] = prevB;
                        }
                    }
            }
        }
        return (tints ? FLAG_TINTS : 0) | (surf ? FLAG_SURFACE : 0);
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

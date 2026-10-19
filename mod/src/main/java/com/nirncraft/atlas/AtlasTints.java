package com.nirncraft.atlas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Biome grass and foliage colours as atlas palette entries, so far grass and broadleaf canopy wear the same tint the
 * near field gives them (a seam would otherwise show as a hue jump at the hand-off). A colour is quantised to 5 bits
 * per channel and registered on first use; its albedo is the tint over the vanilla texture's mean grey (grass block
 * top 0.578, leaves 0.566 and a little darker for the canopy's depth), linear.
 */
final class AtlasTints {
    static final int FIRST_ID = 1024;
    static final int GRID = 64;  // tint samples per tile side (one per 4 x 4 columns)
    private static final float GRASS_GREY = 0.578f, LEAF_GREY = 0.566f * 0.85f;

    private final Map<Integer, Short> ids = new HashMap<>();  // kind << 24 | quantised rgb -> palette id
    private final List<float[]> pending = new ArrayList<>();
    private int next = FIRST_ID;

    /** Palette ids for a tile's tint grids (GRID^2 rgb each, row-major), expanded to its 256^2 columns. */
    synchronized void materials(int[] grassRgb, int[] foliageRgb, short[] grassOut, short[] foliageOut) {
        int per = AtlasNative.TILE / GRID;
        for (int j = 0; j < GRID; j++)
            for (int i = 0; i < GRID; i++) {
                short g = id(0, grassRgb[j * GRID + i]), f = id(1, foliageRgb[j * GRID + i]);
                for (int dj = 0; dj < per; dj++)
                    for (int di = 0; di < per; di++) {
                        int c = (j * per + dj) * AtlasNative.TILE + i * per + di;
                        grassOut[c] = g;
                        foliageOut[c] = f;
                    }
            }
    }

    /** Registers the colours added since the last call (before uploading columns that use them). */
    synchronized void flush(AtlasNative.Context ctx) {
        if (pending.isEmpty()) return;
        ctx.setMaterials(next - pending.size(), pending.toArray(new float[0][]));
        pending.clear();
    }

    private short id(int kind, int rgb) {
        int q = rgb >> 3 & 0x1F1F1F;  // the top 5 bits of each channel, in place
        int key = kind << 24 | q;
        Short v = ids.get(key);
        if (v != null) return v;
        float grey = kind == 0 ? GRASS_GREY : LEAF_GREY;
        float r = lin(((q >> 16) & 31) / 31f * grey), g = lin(((q >> 8) & 31) / 31f * grey), b = lin((q & 31) / 31f * grey);
        pending.add(new float[] {r, g, b, kind == 0 ? 0.9f : 0.85f, 0f, kind == 0 ? AtlasNative.CLASS_TERRAIN : AtlasNative.CLASS_FOLIAGE});
        short id = (short) next++;
        ids.put(key, id);
        return id;
    }

    private static float lin(float c) {
        return (float) Math.pow(c, 2.2);
    }
}

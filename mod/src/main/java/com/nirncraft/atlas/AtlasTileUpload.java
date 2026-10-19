package com.nirncraft.atlas;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Decodes a far tile's bytes (AtlasTileCodec) and uploads it the same way whether it was computed here (single
 * player) or sent by a server: exact surface and water, the biome tints for grass and broadleaf crowns, and (codec v3)
 * the surface blocks the world's material rules paint, as palette ids (AtlasMaterials; a grass block wears its biome
 * tint), with the snow and frozen-water flags. One per column source; the far-field worker's thread.
 */
final class AtlasTileUpload {
    private static final int N = AtlasNative.TILE * AtlasNative.TILE;
    final float[] surface = new float[N], water = new float[N];
    private final int[] grassRgb = new int[AtlasTints.GRID * AtlasTints.GRID], foliageRgb = new int[grassRgb.length];
    private final short[] grassMat = new short[N], foliageMat = new short[N], topMat = new short[N], fillMat = new short[N];
    private final byte[] flags = new byte[N];
    private final AtlasTileCodec.Surface blocks = new AtlasTileCodec.Surface();
    private final AtlasTints tints = new AtlasTints();
    private final Map<String, Short> ids = new HashMap<>();
    private volatile AtlasMaterials mats;

    /** The context's block palette (set before the first upload; without it surface blocks are left to the dressing). */
    void materials(AtlasMaterials m) {
        mats = m;
    }

    /** Decodes and uploads tile (lod, tx, tz); false when the bytes are not a tile of this codec version and LOD. */
    boolean upload(AtlasNative.Context ctx, int lod, int tx, int tz, byte[] bytes) {
        int r = AtlasTileCodec.decode(lod, bytes, surface, water, grassRgb, foliageRgb, blocks);
        if (r < 0) return false;
        boolean tinted = (r & 1) != 0, surf = (r & 2) != 0;
        if (tinted) {
            tints.materials(grassRgb, foliageRgb, grassMat, foliageMat);
            tints.flush(ctx);
        }
        AtlasMaterials m = mats;
        if (surf && m != null) {
            short[] byIndex = new short[blocks.names.size()];
            boolean[] grass = new boolean[byIndex.length];
            for (int k = 0; k < byIndex.length; k++) {
                String name = blocks.names.get(k);
                grass[k] = name.equals("minecraft:grass_block");
                byIndex[k] = ids.computeIfAbsent(name, n -> {
                    Identifier id = Identifier.tryParse(n);
                    Block b = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                    return b == null ? (short) 0 : m.id(b.defaultBlockState());
                });
            }
            for (int c = 0; c < N; c++) {
                int t = blocks.top[c] & 0xFF, u = blocks.under[c] & 0xFF;
                // a grass block wears its biome's tint (0 = the dressing's own tinted grass)
                topMat[c] = t < byIndex.length ? grass[t] ? tinted ? grassMat[c] : 0 : byIndex[t] : 0;
                fillMat[c] = u < byIndex.length && !grass[u] ? byIndex[u] : 0;
                flags[c] = blocks.flags[c];
            }
            m.flush(ctx);
            ctx.uploadColumnHeights3(lod, tx, tz, surface, water, topMat, fillMat, tinted ? grassMat : null, tinted ? foliageMat : null, flags);
        } else if (tinted) {
            ctx.uploadColumnHeights2(lod, tx, tz, surface, water, grassMat, foliageMat);
        } else {
            ctx.uploadColumnHeights(lod, tx, tz, surface, water);
        }
        return true;
    }
}

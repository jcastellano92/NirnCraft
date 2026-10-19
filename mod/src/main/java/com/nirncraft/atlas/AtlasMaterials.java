package com.nirncraft.atlas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Palette ids for blocks the far field draws from Java (building walls and roofs, landmark silhouettes, surface
 * blocks from the real material rules), shared by every overlay of a context so a block has one id. A block's far
 * colour is the mean of its top texture as the near field shows it (AtlasBlockColors, measured from the game's and
 * the mod's textures), else its map colour; the class follows what it is (water, leaves, light sources glow).
 * Ids run FIRST .. LAST: the native preview palette and its water / lava variants sit below, biome tints above.
 */
final class AtlasMaterials {
    static final int FIRST = 64, LAST = 1023;

    private final Map<Block, Short> ids = new HashMap<>();
    private final List<float[]> pending = new ArrayList<>();
    private int next = FIRST;

    /** The palette id of a block's far colour, registered on first use (any thread). */
    synchronized short id(BlockState s) {
        Short v = ids.get(s.getBlock());
        if (v != null) return v;
        if (next > LAST) return (short) FIRST;  // full: the first entry (never in practice: a few hundred blocks)
        pending.add(material(s));
        short id = (short) next++;
        ids.put(s.getBlock(), id);
        return id;
    }

    /** Registers the ids handed out since the last call (before uploading anything that uses them). */
    synchronized void flush(AtlasNative.Context ctx) {
        if (pending.isEmpty()) return;
        ctx.setMaterials(next - pending.size(), pending.toArray(new float[0][]));
        pending.clear();
    }

    /** The class atlas draws falling water with (ATLAS_CLASS_FALLS): a white sheet streaming down. */
    static final int CLASS_FALLS = 5;

    /**
     * {r, g, b (linear), roughness, emissive, class}. The bubble column (water that moves) stands for falling water in
     * the far field's silhouettes: waterfall sheets get the falls class.
     */
    static float[] material(BlockState s) {
        if (s.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN))
            return new float[] {lin(200), lin(212), lin(222), 0.6f, 0f, CLASS_FALLS};
        String key = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        int rgb = AtlasBlockColors.rgb(key);
        if (rgb < 0) rgb = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        float r = lin((rgb >> 16) & 255), g = lin((rgb >> 8) & 255), b = lin(rgb & 255);
        int light = s.getLightEmission();
        int cls = !s.getFluidState().isEmpty() && light == 0 ? AtlasNative.CLASS_WATER
                : light >= 10 ? AtlasNative.CLASS_EMISSIVE
                : s.is(BlockTags.LEAVES) ? AtlasNative.CLASS_FOLIAGE : AtlasNative.CLASS_TERRAIN;
        float rough = cls == AtlasNative.CLASS_WATER ? 0.05f : key.contains("ice") ? 0.2f : key.contains("snow") ? 0.6f : 0.85f;
        return new float[] {r, g, b, rough, light >= 10 ? light / 15f : 0f, cls};
    }

    static float lin(int c) {
        return (float) Math.pow(c / 255.0, 2.2);
    }
}

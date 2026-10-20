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
 *
 * <p>With shapes on (the content-shaped groups, ATLAS_FRAME_GROUPS; atlas_core 0.14) every material also gets its shape
 * (AtlasShapes: the 4x4x4 occupancy and face tiles of its real model, computed once when the id is handed out) and the
 * real sections' blocks get one id per far block state (a top slab is not a bottom slab, a stair keeps its facing) from
 * EXACT_FIRST up, so the palette only grows there when the groups are on.
 */
final class AtlasMaterials {
    static final int FIRST = 64, LAST = 1023;
    /** Per far block state ids of real sections (shapes on): above the biome tints, under the brick pool's 16384. */
    static final int EXACT_FIRST = 4096, EXACT_LAST = 16383;

    private final Map<Block, Short> ids = new HashMap<>();
    private final Map<Long, Short> tinted = new HashMap<>();
    private final List<float[]> pending = new ArrayList<>();
    private int next = FIRST;
    // shapes (setShapes): per pending material its shape (null: a plain cube), and the exact ids
    private volatile boolean shapes;
    private final List<AtlasShapes.Shape> pendingShapes = new ArrayList<>();
    private final Map<Long, Short> exact = new HashMap<>();
    private final List<float[]> pendingExact = new ArrayList<>();
    private final List<AtlasShapes.Shape> pendingExactShapes = new ArrayList<>();
    private final List<int[][]> pendingExactTextures = new ArrayList<>();  // their faces' 16x16 textures (near hits)
    private int nextExact = EXACT_FIRST;

    /** Shapes on: the materials handed out from now on carry their shapes, real sections use per-state ids. */
    void enableShapes() {
        shapes = true;
    }

    boolean shapes() {
        return shapes;
    }

    private AtlasShapes.Shape shapeOf(BlockState s, int tint) {
        if (!shapes) return null;
        try {
            return AtlasShapes.of(s, tint);
        } catch (RuntimeException e) {
            return null;  // a model that cannot say: a plain cube in its colour
        }
    }

    /**
     * The palette id of a block's far colour, registered on first use (any thread). A block the near field tints with a
     * constant (spruce, birch leaves) wears it; one tinted by the biome needs leaves() / section() with the colour.
     */
    synchronized short id(BlockState s) {
        Short v = ids.get(s.getBlock());
        if (v != null) return v;
        if (next > LAST) return (short) FIRST;  // full: the first entry (never in practice: a few hundred blocks)
        int t = tint(s, -1, -1);
        pending.add(t >= 0 ? tintedMaterial(s, t & 0xF8F8F8) : material(s));
        pendingShapes.add(shapeOf(s, t >= 0 ? t & 0xF8F8F8 : -1));
        short id = (short) next++;
        ids.put(s.getBlock(), id);
        return id;
    }

    /**
     * The palette id of a tinted block (leaves, grass): its grey texture mean times the tint rgb (tint(): as the near
     * field tints it), registered per block and tint (quantized to 5 bits a channel: a few dozen per world).
     */
    synchronized short leaves(BlockState s, int rgb) {
        int q = rgb & 0xF8F8F8;
        long key = (long) BuiltInRegistries.BLOCK.getId(s.getBlock()) << 32 | q;
        Short v = tinted.get(key);
        if (v != null) return v;
        if (next > LAST) return id(s);
        pending.add(tintedMaterial(s, q));
        pendingShapes.add(shapeOf(s, q));
        short id = (short) next++;
        tinted.put(key, id);
        return id;
    }

    /** A tinted block's far colour: its grey texture mean times the tint rgb (as the near field tints it). */
    private static float[] tintedMaterial(BlockState s, int q) {
        float[] m = material(s);
        String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        int grey = AtlasBlockColors.rgb(name);
        float g = grey < 0 ? 0.48f : (0.2126f * ((grey >> 16) & 255) + 0.7152f * ((grey >> 8) & 255) + 0.0722f * (grey & 255)) / 255f;
        m[0] = lin(Math.round(((q >> 16) & 255) * g));
        m[1] = lin(Math.round(((q >> 8) & 255) * g));
        m[2] = lin(Math.round((q & 255) * g));
        return m;
    }

    /**
     * The palette id of a real section's block (AtlasEditsClient): with shapes on one per far block state and tint
     * (`tint` RGB for the blocks the game tints, -1 none) carrying its shape; else as before (leaves / id).
     */
    synchronized short section(BlockState s, int tint) {
        if (!shapes || nextExact > EXACT_LAST) return tint >= 0 ? leaves(s, tint) : id(s);
        int q = tint >= 0 ? tint & 0xF8F8F8 : -1;
        long key = (long) AtlasFarState.farId(s) << 32 | (q & 0xFFFFFFFFL);
        Short v = exact.get(key);
        if (v != null) return v;
        pendingExact.add(q >= 0 ? tintedMaterial(s, q) : material(s));
        pendingExactShapes.add(shapeOf(s, q));
        int[][] tex;
        try {
            tex = AtlasShapes.textures(s, q);
        } catch (RuntimeException e) {
            tex = null;  // a model that cannot say: its face tiles then
        }
        pendingExactTextures.add(tex);
        short id = (short) nextExact++;
        exact.put(key, id);
        return id;
    }

    /** Whether the near field tints a block with the biome's grass or foliage colour (tint(): it needs them). */
    static boolean biomeTinted(BlockState s) {
        int k = TINT_KINDS.computeIfAbsent(s, AtlasMaterials::probeTint);
        return k == TINT_GRASS || k == TINT_FOLIAGE;
    }

    /** Per state its tint source's answer: TINT_* for none or a biome colour, else the constant RGB. */
    private static final Map<BlockState, Integer> TINT_KINDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int TINT_NONE = -1, TINT_GRASS = -2, TINT_FOLIAGE = -3;

    /**
     * The tint the near field gives a block's tinted quads, as its tint source answers the chunk mesher: the biome's
     * grass or foliage colour (`grass` / `foliage` RGB, -1 when not known: untinted then), a constant (spruce 619961,
     * birch 80A755), or -1 when it has none (a texture coloured already, as NirnCraft's snowy spruce leaves: no source)
     * and for water and dry foliage (their far colours are their own). Grass, ferns, vines and leaves alike: a far
     * material is what its textured near version averages to, not its grey texture.
     */
    static int tint(BlockState s, int grass, int foliage) {
        int k = TINT_KINDS.computeIfAbsent(s, AtlasMaterials::probeTint);
        return k == TINT_GRASS ? grass : k == TINT_FOLIAGE ? foliage : k;
    }

    private static int probeTint(BlockState s) {
        try {
            var sources = net.minecraft.client.Minecraft.getInstance().getBlockColors().getTintSources(s);
            if (sources.isEmpty() || sources.get(0) == null) return TINT_NONE;
            int c = sources.get(0).colorInWorld(s, AtlasLabExport.TINT_PROBE, BlockPos.ZERO);
            int rgb = c & 0xFFFFFF;
            if (c == -1 || rgb == 0xFFFFFF || rgb == AtlasLabExport.PROBE_WATER || rgb == AtlasLabExport.PROBE_DRY) return TINT_NONE;
            if (rgb == AtlasLabExport.PROBE_GRASS || rgb == AtlasLabExport.PROBE_OTHER) return TINT_GRASS;
            return rgb == AtlasLabExport.PROBE_FOLIAGE ? TINT_FOLIAGE : rgb;
        } catch (RuntimeException e) {
            return TINT_NONE;  // no client (a test) or a source that needs a real level: as before, untinted
        }
    }

    /** Registers the ids handed out since the last call (before uploading anything that uses them). */
    synchronized void flush(AtlasNative.Context ctx) {
        if (!pending.isEmpty()) {
            int first = next - pending.size();
            ctx.setMaterials(first, pending.toArray(new float[0][]));
            if (shapes) ctx.setMaterialShapes(first, pendingShapes.toArray(new AtlasShapes.Shape[0]));
            pending.clear();
            pendingShapes.clear();
        }
        if (!pendingExact.isEmpty()) {
            int first = nextExact - pendingExact.size();
            ctx.setMaterials(first, pendingExact.toArray(new float[0][]));
            ctx.setMaterialShapes(first, pendingExactShapes.toArray(new AtlasShapes.Shape[0]));
            ctx.setMaterialTextures(first, pendingExactTextures.toArray(new int[0][][]));
            pendingExact.clear();
            pendingExactShapes.clear();
            pendingExactTextures.clear();
        }
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
                : s.is(BlockTags.LEAVES) || AtlasSections.plant(s) ? AtlasNative.CLASS_FOLIAGE : AtlasNative.CLASS_TERRAIN;
        float rough = cls == AtlasNative.CLASS_WATER ? 0.05f : key.contains("ice") ? 0.2f : key.contains("snow") ? 0.6f : 0.85f;
        return new float[] {r, g, b, rough, light >= 10 ? light / 15f : 0f, cls};
    }

    static float lin(int c) {
        return (float) Math.pow(c / 255.0, 2.2);
    }
}

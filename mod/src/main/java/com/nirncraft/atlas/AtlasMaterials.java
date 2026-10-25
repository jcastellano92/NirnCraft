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
    private final List<AtlasShapes.BrickModel> pendingExactBricks = new ArrayList<>();  // their bricks' quads (non-cube blocks)
    private final List<Integer> pendingExactOffsets = new ArrayList<>();  // their random offsets (plants)
    private int nextExact = EXACT_FIRST;

    // ---- the same ids on every launch ----
    // Ids are handed out in order of first use, from several threads (the far field, the chunk feed, the generator's
    // table), so a relaunch could number the same block differently: the generator's ingest table (and the self-baking
    // cache's fingerprint and its stored materials with it) would differ, and nothing cached would be read again. The
    // allocations are logged in order (kind, block state id, raw tint) and replayed first on the next launch, before
    // anything asks: the same calls in the same order give the same ids; new ones go on after.
    private java.nio.file.Path allocLogPath;
    private final StringBuilder allocLogBuf = new StringBuilder();
    private boolean replaying;
    private int replayed;

    private static String registrySignature() {
        long sig = 1125899906842597L;
        for (Block b : BuiltInRegistries.BLOCK) sig = 31 * sig + BuiltInRegistries.BLOCK.getKey(b).toString().hashCode();
        sig = 31 * sig + Block.BLOCK_STATE_REGISTRY.size();
        // (diagnosis: a signature that moves between launches renumbers every material and wipes the cache: the same set in
        // another order, or another set?)
        long set = 0, count = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            set += BuiltInRegistries.BLOCK.getKey(b).toString().hashCode() * 0x9E3779B97F4A7C15L;
            count++;
        }
        com.nirncraft.NirnCraft.LOGGER.info("atlas materials: block registry {} blocks, {} states, ordered {}, as a set {}", count,
                Block.BLOCK_STATE_REGISTRY.size(), Long.toHexString(sig), Long.toHexString(set));
        try {
            // (where the order moved: the names in order, kept beside the materials' log, compared with the last launch's)
            java.nio.file.Path f = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("atlas-cache").resolve("block-order.txt");
            List<String> now = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) now.add(BuiltInRegistries.BLOCK.getKey(b).toString());
            if (java.nio.file.Files.isRegularFile(f)) {
                List<String> was = java.nio.file.Files.readAllLines(f);
                int i = 0, moved = 0, first = -1;
                for (; i < Math.min(was.size(), now.size()); i++)
                    if (!was.get(i).equals(now.get(i))) {
                        if (first < 0) first = i;
                        moved++;
                    }
                com.nirncraft.NirnCraft.LOGGER.info("atlas materials: block order against the last launch: {} of {} places differ, the first at {} ({} then {}; {} then {})",
                        moved, now.size(), first, first < 0 ? "-" : was.get(first), first < 0 ? "-" : now.get(first),
                        first < 0 || first + 1 >= was.size() ? "-" : was.get(first + 1), first < 0 || first + 1 >= now.size() ? "-" : now.get(first + 1));
            }
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, now);
        } catch (java.io.IOException | RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas materials: block order not compared ({})", e.toString());
        }
        return Long.toHexString(sig);
    }

    /** Replays the log at `file` (when it is for this block registry and this mode), then logs new allocations to it. */
    synchronized void persistTo(java.nio.file.Path file) {
        String header = "NCMAT1 " + registrySignature() + " " + (shapes ? 1 : 0);
        allocLogPath = file;
        try {
            java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
            boolean same = false;
            String onDisk = "none";
            if (java.nio.file.Files.isRegularFile(file)) {
                List<String> lines = java.nio.file.Files.readAllLines(file);
                onDisk = lines.isEmpty() ? "empty" : lines.get(0);
                same = !lines.isEmpty() && lines.get(0).equals(header);
                if (same) {
                    replaying = true;
                    try {
                        for (int i = 1; i < lines.size(); i++) {
                            String[] f = lines.get(i).split(" ");
                            if (f.length != 3) continue;
                            BlockState st = Block.stateById(Integer.parseInt(f[1]));
                            int v = Integer.parseInt(f[2]);
                            switch (f[0]) {
                                case "I" -> id(st);
                                case "L" -> leaves(st, v);
                                case "S" -> section(st, v);
                                default -> { }
                            }
                            replayed++;
                        }
                    } finally {
                        replaying = false;
                    }
                }
            }
            if (!same) java.nio.file.Files.writeString(file, header + "\n");  // (another registry or mode: the ids start over)
            com.nirncraft.NirnCraft.LOGGER.info("atlas materials: {} allocations replayed from {} ({}; header {}, on disk {})", replayed,
                    file.getFileName(), same ? "same ids as the last launch" : "a new numbering", header, onDisk);
        } catch (java.io.IOException | RuntimeException e) {
            allocLogPath = null;
            com.nirncraft.NirnCraft.LOGGER.warn("atlas materials: the allocation log is off ({})", e.toString());
        }
    }

    private void logAlloc(char kind, BlockState s, int arg) {
        if (allocLogPath == null || replaying) return;
        allocLogBuf.append(kind).append(' ').append(Block.getId(s)).append(' ').append(arg).append('\n');
    }

    /** The allocations since the last call to the file (with each upload of the materials, and at shutdown). */
    synchronized void flushLog() {
        if (allocLogPath == null || allocLogBuf.length() == 0) return;
        try {
            java.nio.file.Files.writeString(allocLogPath, allocLogBuf.toString(), java.nio.file.StandardOpenOption.APPEND);
            allocLogBuf.setLength(0);
        } catch (java.io.IOException e) {
            allocLogPath = null;
            com.nirncraft.NirnCraft.LOGGER.warn("atlas materials: the allocation log is off ({})", e.toString());
        }
    }

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
        int t = tint(s, -1, -1);
        float[] m = t >= 0 ? tintedMaterial(s, t & 0xF8F8F8) : material(s);
        if (next > LAST) return nearestColour(m);  // full: the entry nearest its colour (not the first: a green field's)
        pending.add(m);
        colours.add(m);
        pendingShapes.add(shapeOf(s, t >= 0 ? t & 0xF8F8F8 : -1));
        short id = (short) next++;
        ids.put(s.getBlock(), id);
        logAlloc('I', s, 0);
        return id;
    }

    /**
     * The palette id of a tinted block (leaves, grass): its grey texture mean times the tint rgb (tint(): as the near
     * field tints it), registered per block and tint (quantized to 5 bits a channel: a few dozen per world).
     */
    synchronized short leaves(BlockState s, int rgb) {
        int q = rgb & 0xF8F8F8;
        if (biomeTinted(s)) q = snapTint(q);  // (a biome's colour from the palette in use: AtlasMaterials.snapTint)
        long key = (long) BuiltInRegistries.BLOCK.getId(s.getBlock()) << 32 | q;
        Short v = tinted.get(key);
        if (v != null) return v;
        if (next > LAST) return id(s);
        float[] m = tintedMaterial(s, q);
        pending.add(m);
        colours.add(m);
        pendingShapes.add(shapeOf(s, q));
        short id = (short) next++;
        tinted.put(key, id);
        logAlloc('L', s, rgb);
        return id;
    }

    // the colours of the palette ids handed out, FIRST on (id(): a full palette's nearest)
    private final List<float[]> colours = new ArrayList<>();

    private short nearestColour(float[] m) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < colours.size(); i++) {
            float[] c = colours.get(i);
            double d = (c[0] - m[0]) * (c[0] - m[0]) + (c[1] - m[1]) * (c[1] - m[1]) + (c[2] - m[2]) * (c[2] - m[2])
                    + (c[5] == m[5] ? 0 : 1);  // (and its class: no leaf for a stone)
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        paletteFull++;
        return (short) (FIRST + best);
    }

    private long paletteFull;

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
     * The palette id of a real section's block (AtlasEditsClient): with shapes on one per block state and tint (`tint`
     * RGB for the blocks the game tints, -1 none) carrying its shape, its textures and (a block whose model is not a full
     * cube) its micro-voxel brick; else as before (leaves / id). The state as it comes: the client's feed hands the full
     * state (a crop's age, redstone's power), the server's the far state (AtlasFarState: what does not tick). Past
     * EXACT_LAST the far state's id when it has one (a crop at age 0 rather than a plain cube).
     */
    synchronized short section(BlockState s, int tint) {
        if (!shapes) return tint >= 0 ? leaves(s, tint) : id(s);
        int q = tint >= 0 ? tint & 0xF8F8F8 : -1;
        if (q >= 0 && biomeTinted(s)) q = snapTint(q);
        int sid = net.minecraft.world.level.block.Block.getId(s);
        long key = (long) sid << 32 | (q & 0xFFFFFFFFL);
        Short have = exact.get(key);
        if (have != null) return have;
        if (nextExact > EXACT_LAST) {
            // full: the same state in its nearest tint (its shape, textures and brick), else the far state's; a plain
            // block id last (a per-block material wears the first state's shape: a fence's post, one half of a plant)
            exactFull++;
            Short near = nearestTint(sid, q);
            if (near != null) return near;
            Short far = exact.get((long) AtlasFarState.farId(s) << 32 | (q & 0xFFFFFFFFL));
            return far != null ? far : tint >= 0 ? leaves(s, tint) : id(s);
        }
        pendingExact.add(q >= 0 ? tintedMaterial(s, q) : material(s));
        pendingExactShapes.add(shapeOf(s, q));
        int[][] tex;
        try {
            tex = AtlasShapes.textures(s, q);
        } catch (RuntimeException e) {
            tex = null;  // a model that cannot say: its face tiles then
        }
        pendingExactTextures.add(tex);
        AtlasShapes.BrickModel brick;
        try {
            brick = AtlasShapes.brick(s, q);
        } catch (RuntimeException e) {
            brick = null;  // a model that cannot say: its shape then
        }
        pendingExactBricks.add(brick);
        pendingExactOffsets.add(AtlasShapes.offsetWord(s));
        short id = (short) nextExact++;
        exact.put(key, id);
        byState.computeIfAbsent(sid, k -> new ArrayList<>()).add(new int[] {q, id});
        logAlloc('S', s, tint);
        return id;
    }

    // (exact ids) per state id its tints and ids, the biome tints in use, how often the range was full
    private final Map<Integer, List<int[]>> byState = new HashMap<>();
    private final List<Integer> tintPalette = new ArrayList<>();
    private long exactFull;
    /** Distinct biome tints the exact materials take (the rest snap to the nearest): bounds tinted ids per state. */
    static final int TINT_PALETTE = 40;
    /** A biome tint within this much a channel of one in use is that one. */
    static final int TINT_SNAP = 10;

    /**
     * A biome's grass or foliage colour as the exact materials take it: the one in use within TINT_SNAP a channel, else a
     * new one while there are fewer than TINT_PALETTE, else the nearest. Vanilla blends tints per column across a
     * biome's edge: as distinct ids per state and tint they ran past EXACT_LAST, and what came after lost its shape.
     */
    private int snapTint(int q) {
        int best = -1, bd = Integer.MAX_VALUE;
        for (int t : tintPalette) {
            int d = Math.max(Math.abs(((t >> 16) & 255) - ((q >> 16) & 255)),
                    Math.max(Math.abs(((t >> 8) & 255) - ((q >> 8) & 255)), Math.abs((t & 255) - (q & 255))));
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        if (best >= 0 && (bd <= TINT_SNAP || tintPalette.size() >= TINT_PALETTE)) return best;
        tintPalette.add(q);
        return q;
    }

    /** The exact id of state sid in the tint nearest q, or null when it has none. */
    private Short nearestTint(int sid, int q) {
        List<int[]> l = byState.get(sid);
        if (l == null || l.isEmpty()) return null;
        int[] best = l.get(0);
        int bd = Integer.MAX_VALUE;
        for (int[] e : l) {
            int t = e[0];
            int d = q < 0 || t < 0 ? (q == t ? 0 : 1000) : Math.max(Math.abs(((t >> 16) & 255) - ((q >> 16) & 255)),
                    Math.max(Math.abs(((t >> 8) & 255) - ((q >> 8) & 255)), Math.abs((t & 255) - (q & 255))));
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return (short) best[1];
    }

    /** The exact ids for atlas-info: used of the range, biome tints in use, lookups past a full range. */
    synchronized String state() {
        return "exactIds{used=" + (nextExact - EXACT_FIRST) + "/" + (EXACT_LAST - EXACT_FIRST + 1) + " tints=" + tintPalette.size()
                + " fullLookups=" + exactFull + " palette=" + (next - FIRST) + "/" + (LAST - FIRST + 1) + " paletteFull=" + paletteFull + "}";
    }

    /** Whether the near field tints a block with the biome's grass or foliage colour (tint(): it needs them). */
    static boolean biomeTinted(BlockState s) {
        int k = TINT_KINDS.computeIfAbsent(s, AtlasMaterials::probeTint);
        return k == TINT_GRASS || k == TINT_FOLIAGE;
    }

    /** A state's tint source's answer (TINT_* for none or a biome colour, else the constant RGB): states alike in it tint alike. */
    static int tintKind(BlockState s) {
        return TINT_KINDS.computeIfAbsent(s, AtlasMaterials::probeTint);
    }

    /** Per state its tint source's answer: TINT_* for none or a biome colour, else the constant RGB. */
    private static final Map<BlockState, Integer> TINT_KINDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int TINT_NONE = -1, TINT_GRASS = -2, TINT_FOLIAGE = -3;
    /** Vanilla's dry foliage colour in the temperate middle of its map (leaf litter, dry grass), RGB. */
    static final int DRY_FOLIAGE = 0xA0825A;

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

    /**
     * A state's tint as its first tinting layer gives it (pink petals and wildflowers leave layer 0 blank and tint
     * layer 1 by the grass): TINT_* or the constant RGB.
     */
    private static int probeTint(BlockState s) {
        int n = layers(s);
        for (int i = 0; i < n; i++) {
            int k = probeLayer(s, i);
            if (k != TINT_NONE) return k;
        }
        return TINT_NONE;
    }

    private static int layers(BlockState s) {
        try {
            return net.minecraft.client.Minecraft.getInstance().getBlockColors().getTintSources(s).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static final Map<BlockState, int[]> LAYER_KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The colour a quad of tint layer `layer` takes on state s whose material wears the biome colour `tint` (RGB, the
     * first tinting layer's, tint()): that colour for a biome layer, the constant for a constant one, dry foliage's for
     * dry foliage, -1 for a blank layer (untinted).
     */
    static int layerTint(BlockState s, int layer, int tint) {
        if (layer < 0) return -1;
        int[] kinds = LAYER_KINDS.computeIfAbsent(s, st -> {
            int n = layers(st);
            int[] k = new int[n];
            for (int i = 0; i < n; i++) k[i] = probeLayer(st, i);
            return k;
        });
        if (layer >= kinds.length) return kinds.length == 0 ? tint : -1;
        int k = kinds[layer];
        return k == TINT_NONE ? -1 : k == TINT_GRASS || k == TINT_FOLIAGE ? tint : k;
    }

    private static int probeLayer(BlockState s, int layer) {
        try {
            var sources = net.minecraft.client.Minecraft.getInstance().getBlockColors().getTintSources(s);
            if (layer >= sources.size() || sources.get(layer) == null) return TINT_NONE;
            int c = sources.get(layer).colorInWorld(s, AtlasLabExport.TINT_PROBE, BlockPos.ZERO);
            int rgb = c & 0xFFFFFF;
            if (c == -1 || rgb == 0xFFFFFF || rgb == AtlasLabExport.PROBE_WATER) return TINT_NONE;
            // dry foliage (leaf litter, dry grass): its common colour, a constant (it was untinted: grey-white)
            if (rgb == AtlasLabExport.PROBE_DRY) return DRY_FOLIAGE;
            if (rgb == AtlasLabExport.PROBE_GRASS || rgb == AtlasLabExport.PROBE_OTHER) return TINT_GRASS;
            return rgb == AtlasLabExport.PROBE_FOLIAGE ? TINT_FOLIAGE : rgb;
        } catch (RuntimeException e) {
            return TINT_NONE;  // no client (a test) or a source that needs a real level: as before, untinted
        }
    }

    /** Registers the ids handed out since the last call (before uploading anything that uses them). */
    synchronized void flush(AtlasNative.Context ctx) {
        flushLog();
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
            ctx.setMaterialBricks(first, pendingExactBricks.toArray(new AtlasShapes.BrickModel[0]));
            ctx.setMaterialOffsets(first, pendingExactOffsets.stream().mapToInt(Integer::intValue).toArray());
            pendingExact.clear();
            pendingExactShapes.clear();
            pendingExactTextures.clear();
            pendingExactBricks.clear();
            pendingExactOffsets.clear();
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
                : light >= 1 ? AtlasNative.CLASS_EMISSIVE
                : s.is(BlockTags.LEAVES) || AtlasSections.plant(s) ? AtlasNative.CLASS_FOLIAGE : AtlasNative.CLASS_TERRAIN;
        float rough = cls == AtlasNative.CLASS_WATER ? 0.05f : key.contains("ice") ? 0.2f : key.contains("snow") ? 0.6f : 0.85f;
        return new float[] {r, g, b, rough, light >= 1 ? light / 15f : 0f, cls};
    }

    static float lin(int c) {
        return (float) Math.pow(c / 255.0, 2.2);
    }
}

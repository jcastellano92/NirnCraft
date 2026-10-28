package com.nirncraft.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * A block state's far shape (docs/ATLAS-LOD.md, level -1): its 4 x 4 x 4 micro-voxel occupancy from its outline shape and
 * a 4 x 4 tile of its real textures per face, from the baked model the near field draws. One computation for the lab's
 * library (AtlasLabExport blocks.bin) and for the far field's materials (AtlasMaterials: atlas_set_material_shapes, the
 * content-shaped groups), so the far field's micro-voxels are the lab's. Client only (baked models and sprites).
 */
final class AtlasShapes {
    private AtlasShapes() {}

    static final int TILE = 4;

    /** atlas.h AtlasMaterialShape: occupancy (bit x + 4 * (z + 4 * y)), mean ARGB, flags, 6 x 16 ARGB face texels. */
    record Shape(long occupancy, int mean, int flags, int[][] faces) {}
    record StateModel(List<BlockStateModelPart> parts, List<BakedQuad> quads, List<AABB> boxes,
                      long boxMask, boolean fullCube, boolean brickable, int offsetWord) {}
    record Extracted(Shape shape, int[][] textures, BrickModel brick, int offsetWord) {}

    private static volatile Object modelEpoch;
    private static final java.util.Map<BlockState, StateModel> STATE_MODELS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Long, Extracted> EXTRACTED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<TextureAtlasSprite, int[]> FRAME0_TEXELS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean checkEpoch() {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) return false;
        Object cur = mc.getModelManager().getBlockStateModelSet();
        if (cur == null) return false;
        if (cur != modelEpoch) {
            synchronized (AtlasShapes.class) {
                if (cur != modelEpoch) {
                    STATE_MODELS.clear();
                    EXTRACTED.clear();
                    SPRITE_TEXELS.clear();
                    FRAME0_TEXELS.clear();
                    modelEpoch = cur;
                }
            }
        }
        return true;
    }

    static StateModel stateModel(BlockState st) {
        if (!checkEpoch()) return buildStateModel(st);
        return STATE_MODELS.computeIfAbsent(st, AtlasShapes::buildStateModel);
    }

    private static StateModel buildStateModel(BlockState st) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st).collectParts(
                    net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY, BlockPos.ZERO, st, RandomSource.create(42L), parts);
        } catch (RuntimeException e) {
            parts.clear();
        }
        parts = List.copyOf(parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart p : parts) {
            for (Direction d : Direction.values()) quads.addAll(p.getQuads(d));
            quads.addAll(p.getQuads(null));
        }
        quads = List.copyOf(quads);
        VoxelShape shape;
        try {
            shape = st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            shape = net.minecraft.world.phys.shapes.Shapes.empty();
        }
        List<AABB> boxes = List.copyOf(shape.toAabbs());
        long boxMask = mask(boxes);
        boolean fullCube = fullCubeModel(quads);
        boolean brickable = computeBrickable(st, quads, fullCube);
        int offset = computeOffsetWord(st);
        return new StateModel(parts, quads, boxes, boxMask, fullCube, brickable, offset);
    }

    static Extracted extracted(BlockState st, int q) {
        if (!checkEpoch()) return buildExtracted(st, q);
        long key = (long) net.minecraft.world.level.block.Block.getId(st) << 32 | (q & 0xFFFFFFFFL);
        return EXTRACTED.computeIfAbsent(key, ignored -> buildExtracted(st, q));
    }

    private static Extracted buildExtracted(BlockState st, int q) {
        Shape shape;
        try { shape = of(st, q); } catch (RuntimeException e) { shape = null; }
        int[][] tex;
        try { tex = textures(st, q); } catch (RuntimeException e) { tex = null; }
        BrickModel brick;
        try { brick = brick(st, q); } catch (RuntimeException e) { brick = null; }
        return new Extracted(shape, tex, brick, offsetWord(st));
    }

    /** The model quads the lab's library samples a state's faces from (its parts at a fixed seed, every face and the unculled). */
    static List<BakedQuad> quads(BlockState st) {
        return stateModel(st).quads();
    }

    /** The model parts the state draws (a multipart model's every matching case; a variant's pick at a fixed seed). */
    static List<BlockStateModelPart> parts(BlockState st) {
        return stateModel(st).parts();
    }

    /** The state's outline shape as boxes (none when it cannot say). */
    static List<AABB> boxes(BlockState st) {
        return stateModel(st).boxes();
    }

    /** The micro-voxels whose centres the shape holds, bit x + 4 * (z + 4 * y). */
    static long mask(List<AABB> boxes) {
        long mask = 0;
        for (int y = 0; y < TILE; y++)
            for (int z = 0; z < TILE; z++)
                for (int x = 0; x < TILE; x++) {
                    double cx = (x + 0.5) / TILE, cy = (y + 0.5) / TILE, cz = (z + 0.5) / TILE;
                    for (AABB a : boxes)
                        if (cx > a.minX && cx < a.maxX && cy > a.minY && cy < a.maxY && cz > a.minZ && cz < a.maxZ) {
                            mask |= 1L << (x + TILE * (z + TILE * y));
                            break;
                        }
                }
        return mask;
    }

    /** The alpha-weighted mean of the face tiles, ARGB (0 when they show nothing). */
    static int mean(int[][] faces) {
        double r = 0, g = 0, bl = 0, w = 0;
        for (int[] tile : faces)
            for (int c : tile) {
                double a = (c >>> 24) / 255.0;
                r += a * ((c >> 16) & 255);
                g += a * ((c >> 8) & 255);
                bl += a * (c & 255);
                w += a;
            }
        return w <= 0 ? 0 : 0xFF000000 | (int) Math.round(r / w) << 16 | (int) Math.round(g / w) << 8 | (int) Math.round(bl / w);
    }

    /**
     * A plant: drawn as cut-out quads, nothing a neighbour hides behind. Its outline says nothing: a ripe crop's (wheat,
     * NirnCraft's garlic or cabbage) is a full cube, and as a solid cube a field of crops was a block of green.
     */
    static boolean plant(BlockState st, List<BakedQuad> quads, List<AABB> boxes) {
        boolean cut = false;
        for (BakedQuad q : quads) cut |= q.materialInfo().layer() == ChunkSectionLayer.CUTOUT;
        if (!cut) return false;
        return boxes.isEmpty() || st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(BlockTags.FLOWERS)
                || st.getBlock() instanceof net.minecraft.world.level.block.CropBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }

    /**
     * The visual hull of six face tiles (face(): up / down over (x, z), north / south over (x, y), west / east over
     * (z, y), v downwards on the sides): micro-voxel (x, y, z) is in when each axis's pair of faces covers its projection
     * (alpha a quarter or more: a 4/16 post centred on a texel boundary covers two texels by half).
     */
    static long hull(int[][] faces) {
        long m = 0;
        for (int y = 0; y < TILE; y++)
            for (int z = 0; z < TILE; z++)
                for (int x = 0; x < TILE; x++) {
                    int v = TILE - 1 - y;
                    boolean ud = covered(faces[0], x, z) || covered(faces[1], x, z);
                    boolean ns = covered(faces[2], x, v) || covered(faces[3], x, v);
                    boolean we = covered(faces[4], z, v) || covered(faces[5], z, v);
                    if (ud && ns && we) m |= 1L << (x + TILE * (z + TILE * y));
                }
        return m;
    }

    private static boolean covered(int[] tile, int u, int v) {
        return tile != null && (tile[u + TILE * v] >>> 24) >= 64;
    }

    /**
     * The far field's shape of a state: its occupancy (water and other fluids without a model: a full cube), its six face
     * tiles with `tint` (RGB, or -1 for none) applied to the quads the game tints (leaves, grass: the far field's
     * material carries the biome colour it was registered with), cut-out when leaves or cut-out quads let light through.
     * Null when it has neither a shape nor a model (a full cube in its colour then).
     */
    static Shape of(BlockState st, int tint) {
        StateModel sm = stateModel(st);
        List<BakedQuad> quads = sm.quads();
        List<AABB> boxes = sm.boxes();
        long mask = sm.boxMask();
        int[][] faces = new int[6][];
        for (Direction d : Direction.values()) faces[d.get3DDataValue()] = face(st, d, quads, tint, TILE);
        // thinner than a micro-voxel (a snow layer, a carpet, a torch, a lantern's chain): its outline fills none of the
        // 4 x 4 x 4, so its shape is the visual hull of its face tiles (a layer's bottom layer, a post's core column;
        // a chain is no floating slab)
        if (mask == 0 && !quads.isEmpty()) mask = hull(faces);
        // (and a model thinner than that still fills the micro-voxels its quads pass through: a shapeless material was a
        // plain full cube to everything that reads shapes, and the floor under a 1 px button went black)
        if (mask == 0 && !quads.isEmpty()) mask = quadMask(quads);
        boolean fluid = !st.getFluidState().isEmpty() && quads.isEmpty();
        if (fluid) mask = -1L;
        if (mask == 0 || quads.isEmpty() && !fluid) return null;
        // cut-out coverage for leaves and plants only (a grass block's cut-out overlay is no hole in the block)
        // (and a plain cube with see-through texels: a copper grate, mangrove roots, a cut-out cube's holes)
        boolean cutout = st.is(BlockTags.LEAVES) || plant(st, quads, boxes) || !st.isSolidRender() && holedCube(quads, faces);  // (a full opaque cube never gets holes)
        return new Shape(mask, mean(faces), cutout ? AtlasNative.SHAPE_CUTOUT : 0, faces);
    }

    private static int[] frame0Texels(TextureAtlasSprite sp) {
        int[] cached = FRAME0_TEXELS.get(sp);
        if (cached != null) return cached.length == 0 ? null : cached;
        NativeImage img;
        try {
            img = sp.contents().getOriginalImage();
        } catch (RuntimeException e) {
            FRAME0_TEXELS.put(sp, new int[0]);
            return null;
        }
        int fw = sp.contents().width(), fh = sp.contents().height();
        int[] t = new int[fw * fh];
        for (int y = 0; y < fh; y++)
            for (int x = 0; x < fw; x++) t[x + fw * y] = img.getPixel(x, y);
        FRAME0_TEXELS.put(sp, t);
        return t;
    }

    /**
     * Face d's 4x4 tile: each texel the outermost quad facing d over it (by its centre), its sprite averaged over the
     * texel's area (4x4 samples), alpha the share of opaque samples, the quad's tint applied when it has one and `tint`
     * is set (RGB; -1: none, the lab's raw textures). The tile's (u, v) follow the face's plane: for up and down (x, z),
     * for north and south (x, y), for west and east (z, y); v grows downwards on the side faces.
     */
    static int[] face(Direction d, List<BakedQuad> quads, int tint) {
        return face(d, quads, tint, TILE);
    }

    /** Face d's tile at res x res texels (as face(): 4 for the shapes, 16 for the near field's textures). */
    static int[] face(Direction d, List<BakedQuad> quads, int tint, int TILE) {
        return face(null, d, quads, tint, TILE);
    }

    /** face() of state st: each quad tinted by its own tint layer (AtlasMaterials.layerTint), not layer 0's for all. */
    static int[] face(BlockState st, Direction d, List<BakedQuad> quads, int tint, int TILE) {
        int sub = Math.max(1, 16 / TILE);  // samples a texel side: a 4x4 tile averages 4x4 of the 16x16 sprite
        int[] tile = new int[TILE * TILE];
        // the quads facing d, innermost first: each is laid over what is under it by its own coverage (a grass block's
        // overlay quad over its dirt side: where the overlay is half there, half of the dirt shows, and the texel stays
        // opaque; replacing it left the texel half transparent and made a full cube a cut-out with holes)
        List<BakedQuad> ordered = new ArrayList<>();
        for (BakedQuad q : quads) if (q.direction() == d) ordered.add(q);
        ordered.sort(java.util.Comparator.comparingDouble(q -> quadDepth(d, q)));
        for (BakedQuad q : ordered) {
            TextureAtlasSprite sp = q.materialInfo().sprite();
            int[] f0 = frame0Texels(sp);
            if (f0 == null) continue;
            int qt = tint < 0 || q.materialInfo().tintIndex() < 0 ? -1
                    : st == null ? tint : AtlasMaterials.layerTint(st, q.materialInfo().tintIndex(), tint);
            boolean tinted = qt >= 0;
            int fw = sp.contents().width(), fh = sp.contents().height();
            // the quad's extent in the face plane and its depth along d
            float a0 = Float.MAX_VALUE, a1 = -Float.MAX_VALUE, b0 = Float.MAX_VALUE, b1 = -Float.MAX_VALUE;
            float[] pa = new float[4], pb = new float[4], pu = new float[4], pv = new float[4];
            for (int i = 0; i < 4; i++) {
                Vector3fc p = q.position(i);
                float[] ab = plane(d, p.x(), p.y(), p.z());
                pa[i] = ab[0];
                pb[i] = ab[1];
                a0 = Math.min(a0, ab[0]);
                a1 = Math.max(a1, ab[0]);
                b0 = Math.min(b0, ab[1]);
                b1 = Math.max(b1, ab[1]);
                long uv = q.packedUV(i);
                pu[i] = (UVPair.unpackU(uv) - sp.getU0()) / (sp.getU1() - sp.getU0());
                pv[i] = (UVPair.unpackV(uv) - sp.getV0()) / (sp.getV1() - sp.getV0());
            }
            if (a1 - a0 < 1e-4f || b1 - b0 < 1e-4f) continue;  // edge-on (crossed plants seen from a side: their slant)
            // the quad's corners in its plane: bilinear (a, b) -> (u, v) from the corner nearest each extent corner
            int c00 = corner(pa, pb, a0, b0), c10 = corner(pa, pb, a1, b0), c01 = corner(pa, pb, a0, b1), c11 = corner(pa, pb, a1, b1);
            for (int tv = 0; tv < TILE; tv++)
                for (int tu = 0; tu < TILE; tu++) {
                    int k = tu + TILE * tv;
                    float ca = (tu + 0.5f) / TILE, cb = 1f - (tv + 0.5f) / TILE;  // tile v down = plane b down
                    if (d.getAxis() == Direction.Axis.Y) cb = (tv + 0.5f) / TILE;  // up/down: b is z, v grows with z
                    if (ca < a0 || ca > a1 || cb < b0 || cb > b1) continue;
                    double sr = 0, sg = 0, sb = 0, sa = 0;
                    int n = 0;
                    for (int sy = 0; sy < sub; sy++)
                        for (int sx = 0; sx < sub; sx++) {
                            float aa = (tu + (sx + 0.5f) / sub) / TILE;
                            float bb = d.getAxis() == Direction.Axis.Y ? (tv + (sy + 0.5f) / sub) / TILE : 1f - (tv + (sy + 0.5f) / sub) / TILE;
                            float s = clamp01((aa - a0) / (a1 - a0)), t = clamp01((bb - b0) / (b1 - b0));
                            float u = lerp2(pu[c00], pu[c10], pu[c01], pu[c11], s, t), v = lerp2(pv[c00], pv[c10], pv[c01], pv[c11], s, t);
                            int px = Math.min(fw - 1, Math.max(0, (int) (u * fw))), py = Math.min(fh - 1, Math.max(0, (int) (v * fh)));
                            int c = f0[px + fw * py];  // ARGB, frame 0
                            double al = (c >>> 24) / 255.0;
                            n++;
                            if (al < 0.1) continue;
                            sr += ((c >> 16) & 255);
                            sg += ((c >> 8) & 255);
                            sb += (c & 255);
                            sa += 1;
                        }
                    if (sa == 0) continue;  // the quad is see-through here: what is behind it shows
                    if (tinted) {
                        sr *= ((qt >> 16) & 255) / 255.0;
                        sg *= ((qt >> 8) & 255) / 255.0;
                        sb *= (qt & 255) / 255.0;
                    }
                    double cov = sa / n, under = (tile[k] >>> 24) / 255.0 * (1 - cov), a = cov + under;  // (over the texel so far)
                    int prev = tile[k];
                    int r = (int) Math.round((sr / sa * cov + ((prev >> 16) & 255) * under) / a);
                    int g = (int) Math.round((sg / sa * cov + ((prev >> 8) & 255) * under) / a);
                    int b = (int) Math.round((sb / sa * cov + (prev & 255) * under) / a);
                    tile[k] = (int) Math.round(255.0 * a) << 24 | r << 16 | g << 8 | b;
                }
        }
        return tile;
    }

    /** The micro-voxels (4 x 4 x 4, bit x + 4 * (z + 4 * y)) any quad's extent touches. */
    static long quadMask(List<BakedQuad> quads) {
        long m = 0;
        for (BakedQuad q : quads) {
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < 4; i++) {
                Vector3fc p = q.position(i);
                float[] c = {p.x(), p.y(), p.z()};
                for (int k = 0; k < 3; k++) {
                    lo[k] = Math.min(lo[k], c[k]);
                    hi[k] = Math.max(hi[k], c[k]);
                }
            }
            int[] a = new int[3], b = new int[3];
            for (int k = 0; k < 3; k++) {
                a[k] = Math.max(0, Math.min(TILE - 1, (int) Math.floor(lo[k] * TILE + 1e-4f)));
                b[k] = Math.max(a[k], Math.min(TILE - 1, (int) Math.ceil(hi[k] * TILE - 1e-4f) - 1));
            }
            for (int y = a[1]; y <= b[1]; y++)
                for (int z = a[2]; z <= b[2]; z++)
                    for (int x = a[0]; x <= b[0]; x++) m |= 1L << (x + TILE * (z + TILE * y));
        }
        return m;
    }

    /** How far out along d a quad lies (the mean of its corners' depth: plane()). */
    private static double quadDepth(Direction d, BakedQuad q) {
        double dep = 0;
        for (int i = 0; i < 4; i++) {
            Vector3fc p = q.position(i);
            dep += plane(d, p.x(), p.y(), p.z())[2] / 4.0;
        }
        return dep;
    }

    /** The near field's texture size per face (atlas_set_material_textures). */
    static final int TEX = 16;

    /**
     * A state's six faces at TEX x TEX texels (down, up, north, south, west, east; ARGB sRGB, `tint` applied to the
     * tinted quads; the shapes' (u, v) convention), what atlas shades its near hits with; null when it has no model.
     */
    static int[][] textures(BlockState st, int tint) {
        List<BakedQuad> quads = quads(st);
        if (quads.isEmpty()) return null;
        int[][] faces = new int[6][];
        for (Direction d : Direction.values()) faces[d.get3DDataValue()] = face(st, d, quads, tint, TEX);
        return faces;
    }

    /**
     * A model quad for the micro-voxel bricks (atlas.h AtlasBrickQuad): its corners (12 floats, block space), their
     * (u, v) in its sprite's own 0..1 (8 floats), the sprite ({width, height, frames, game ticks a frame, then the ARGB
     * texels of every frame in turn}: one array per sprite, shared), the tint (RGB, 0xFFFFFF none) and its face direction.
     */
    record BrickQuad(float[] pos, float[] uv, int[] texels, int tint, int dir) {}

    /** A state's quads for its brick and its emission (light / 15). */
    record BrickModel(List<BrickQuad> quads, float emission) {}

    private static final java.util.Map<TextureAtlasSprite, int[]> SPRITE_TEXELS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Texels before the frames in spriteTexels' arrays: width, height, frames, game ticks a frame. */
    static final int SPRITE_HEAD = 4;

    /**
     * The sprite's frames as {width, height, frames, ticks a frame, ARGB...} (cached per sprite): an animated sprite's
     * distinct frames in order (at most 64; its frame time from its .mcmeta, 1 tick by default), else its one image.
     */
    static int[] spriteTexels(TextureAtlasSprite sp) {
        return SPRITE_TEXELS.computeIfAbsent(sp, s -> {
            NativeImage img;
            try {
                img = s.contents().getOriginalImage();
            } catch (RuntimeException e) {
                return new int[] {0, 0, 1, 1};
            }
            int w = s.contents().width(), h = s.contents().height();
            int[] frames = {0};
            int ticks = 1;
            if (s.contents().isAnimated()) {
                // its .mcmeta's sequence (frames: indices or {index, time}; frametime the default), each frame repeated
                // by its time over the times' common divisor: one tick rate a sprite, the sequence as the game plays it
                int[] seq = frameSequence(s.contents().name(), s.contents().getUniqueFrames());
                frames = java.util.Arrays.copyOfRange(seq, 1, seq.length);
                ticks = seq[0];
            }
            int perRow = Math.max(1, img.getWidth() / Math.max(1, w));
            int[] t = new int[SPRITE_HEAD + w * h * frames.length];
            t[0] = w;
            t[1] = h;
            t[2] = frames.length;
            t[3] = ticks;
            for (int f = 0; f < frames.length; f++) {
                int ox = (frames[f] % perRow) * w, oy = (frames[f] / perRow) * h;
                if (ox + w > img.getWidth() || oy + h > img.getHeight()) ox = oy = 0;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) t[SPRITE_HEAD + f * w * h + x + w * y] = img.getPixel(ox + x, oy + y);  // ARGB
            }
            return t;
        });
    }

    /** Whether a sprite's texels (its first frame) are all opaque or clear: under 2 % in between. */
    static boolean binaryAlpha(TextureAtlasSprite sp) {
        int[] t = spriteTexels(sp);
        int n = t[0] * t[1], mid = 0;
        for (int i = 0; i < n; i++) {
            int a = t[SPRITE_HEAD + i] >>> 24;
            if (a > 8 && a < 247) mid++;
        }
        return n > 0 && mid * 50 <= n;
    }

    /**
     * An animated texture's frames as the game plays them: {ticks a frame, frame index, ...}, from its .png.mcmeta
     * (animation.frames: indices or {index, time}, animation.frametime the default, 1) with each frame repeated by its
     * time over the times' common divisor (at most 64 entries); its distinct frames in order when it says no sequence.
     */
    private static int[] frameSequence(net.minecraft.resources.Identifier name, it.unimi.dsi.fastutil.ints.IntList unique) {
        java.util.List<int[]> seq = new java.util.ArrayList<>();  // {index, time}
        int def = 1;
        try {
            var id = net.minecraft.resources.Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png.mcmeta");
            var res = Minecraft.getInstance().getResourceManager().getResource(id);
            if (res.isPresent())
                try (var r = res.get().openAsReader()) {
                    var o = com.google.gson.JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("animation");
                    if (o != null) {
                        if (o.has("frametime")) def = Math.max(1, o.get("frametime").getAsInt());
                        if (o.has("frames"))
                            for (var e : o.getAsJsonArray("frames")) {
                                if (e.isJsonPrimitive()) seq.add(new int[] {e.getAsInt(), -1});
                                else {
                                    var fo = e.getAsJsonObject();
                                    seq.add(new int[] {fo.get("index").getAsInt(), fo.has("time") ? Math.max(1, fo.get("time").getAsInt()) : -1});
                                }
                            }
                    }
                }
        } catch (Exception e) {
            seq.clear();
        }
        if (seq.isEmpty()) for (int i = 0; i < unique.size(); i++) seq.add(new int[] {unique.getInt(i), -1});
        int g = 0;
        for (int[] f : seq) {
            if (f[1] < 0) f[1] = def;
            g = gcd(g, f[1]);
        }
        g = Math.max(1, g);
        long total = 0;
        for (int[] f : seq) total += f[1] / g;
        while (total > 64 && g < 4096) {  // (too long a sequence: a coarser tick, frames merged; bounded)
            g *= 2;
            total = 0;
            for (int[] f : seq) total += Math.max(1, f[1] / g);
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        out.add(g);
        for (int[] f : seq) for (int k = 0, n = Math.max(1, f[1] / g); k < n && out.size() <= 64; k++) out.add(f[0]);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** Forget the cached sprite texels (resources reloaded). */
    static void clearSprites() {
        SPRITE_TEXELS.clear();
        FRAME0_TEXELS.clear();
        STATE_MODELS.clear();
        EXTRACTED.clear();
        modelEpoch = null;
    }

    /**
     * Whether a state takes a micro-voxel brick (docs/ATLAS-WORLD-STORE.md section 4): a block drawn by its model that is
     * not a full solid cube (those are textured cubes) nor leaves (a textured cut-out cube). Translucent models stay
     * vanilla's (the translucent layer: glass, panes, ice, slime, honey) unless their texels are all either opaque or
     * clear (redstone dust is in the translucent layer: its brick is its opaque texels).
     */
    static boolean brickable(BlockState st, List<BakedQuad> quads) {
        if (quads.isEmpty()) return false;
        return computeBrickable(st, quads, fullCubeModel(quads));
    }

    private static boolean computeBrickable(BlockState st, List<BakedQuad> quads, boolean cube) {
        if (quads.isEmpty()) return false;
        // leaves are textured cut-out cubes; a block tagged leaves whose model is no cube (a berry bush, a hedge's
        // twigs) is a brick like any plant (as a leaves cube it was its texture on six faces)
        if (st.is(BlockTags.LEAVES) && cube) return false;
        boolean animated = false;
        // glass, panes, ice, slime, honey stay the translucent layer's; another translucent model (redstone dust, a
        // plant with soft edges) is a brick of its texels that are half opaque or more
        boolean glassy = st.is(BlockTags.IMPERMEABLE) || st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.HalfTransparentBlock;
        for (BakedQuad q : quads) {
            if (q.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT && glassy) return false;
            animated |= q.materialInfo().sprite().contents().isAnimated();
        }
        // a full solid cube is a textured cube, unless its texture moves (magma, a sea lantern, prismarine): a brick a
        // frame then (its micro-voxels are its texels)
        return !(st.isSolidRender() || cube && st.canOcclude()) || animated;
    }

    /** Whether a plain-cube model shows the background through its faces' tiles (a grate, roots: a cut-out cube). */
    static boolean holedCube(List<BakedQuad> quads, int[][] faces) {
        boolean cut = false;
        for (BakedQuad q : quads) cut |= q.materialInfo().layer() == ChunkSectionLayer.CUTOUT;
        if (!cut || !fullCubeModel(quads)) return false;
        for (int[] tile : faces)
            if (tile != null)
                for (int c : tile)
                    if ((c >>> 24) < 250) return true;
        return false;
    }

    /**
     * Whether a model is a plain cube: on each of the six faces a quad lying on the block's boundary that covers it whole
     * (a stone, a log, leaves; not a bush, a slab, a cross).
     */
    static boolean fullCubeModel(List<BakedQuad> quads) {
        int faces = 0;
        for (BakedQuad q : quads) {
            Direction d = q.direction();
            int a = d.getAxis().ordinal();  // x 0, y 1, z 2
            float plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
            float lo0 = 1, hi0 = 0, lo1 = 1, hi1 = 0;
            boolean on = true;
            for (int i = 0; i < 4; i++) {
                Vector3fc p = q.position(i);
                float[] c = {p.x(), p.y(), p.z()};
                if (Math.abs(c[a] - plane) > 1e-3f) on = false;
                float u = c[(a + 1) % 3], v = c[(a + 2) % 3];
                lo0 = Math.min(lo0, u);
                hi0 = Math.max(hi0, u);
                lo1 = Math.min(lo1, v);
                hi1 = Math.max(hi1, v);
            }
            if (on && lo0 < 1e-3f && lo1 < 1e-3f && hi0 > 1 - 1e-3f && hi1 > 1 - 1e-3f) faces |= 1 << d.get3DDataValue();
        }
        return faces == 63;
    }

    /**
     * A state's random offset (vanilla's OffsetType) as atlas.h atlas_set_material_offsets takes it: the type and its
     * largest offsets, from the offset the state gives positions (0: none).
     */
    static int offsetWord(BlockState st) {
        return stateModel(st).offsetWord();
    }

    private static int computeOffsetWord(BlockState st) {
        try {
            if (!st.hasOffsetFunction()) return 0;
            double h = 0, v = 0;
            for (int i = 0; i < 64; i++) {
                var o = st.getOffset(new BlockPos(i * 7, 0, i * 13));
                h = Math.max(h, Math.max(Math.abs(o.x), Math.abs(o.z)));
                v = Math.max(v, -o.y);
            }
            if (h <= 0 && v <= 0) return 0;
            return (v > 0 ? 2 : 1) | (int) Math.min(255, Math.round(h * 255)) << 8 | (int) Math.min(255, Math.round(v * 255)) << 16;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A state's brick model (tint RGB for its tinted quads, -1 none), or null when it takes no brick. */
    static BrickModel brick(BlockState st, int tint) {
        StateModel sm = stateModel(st);
        List<BakedQuad> quads = sm.quads();
        if (quads.isEmpty()) return fluidBrick(st);
        if (!sm.brickable()) return null;
        List<BrickQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            TextureAtlasSprite sp = q.materialInfo().sprite();
            int[] tx = spriteTexels(sp);
            if (tx[0] == 0) continue;
            float[] pos = new float[12], uv = new float[8];
            for (int i = 0; i < 4; i++) {
                Vector3fc p = q.position(i);
                pos[3 * i] = p.x();
                pos[3 * i + 1] = p.y();
                pos[3 * i + 2] = p.z();
                long puv = q.packedUV(i);
                uv[2 * i] = (UVPair.unpackU(puv) - sp.getU0()) / (sp.getU1() - sp.getU0());
                uv[2 * i + 1] = (UVPair.unpackV(puv) - sp.getV0()) / (sp.getV1() - sp.getV0());
            }
            int lt = tint >= 0 ? AtlasMaterials.layerTint(st, q.materialInfo().tintIndex(), tint) : -1;
            int t = lt >= 0 ? lt & 0xFFFFFF : 0xFFFFFF;  // (its own tint layer's colour: AtlasMaterials.layerTint)
            out.add(new BrickQuad(pos, uv, tx, t, q.direction().get3DDataValue()));
        }
        return out.isEmpty() ? null : new BrickModel(out, st.getLightEmission() / 15f);
    }

    /**
     * A fluid other than water (lava; water is atlas's own medium) as a brick: a box to its surface (the fluid state's
     * own height: a source 8/9, its flow lower, falling full), its still texture on the top and bottom, its flowing one
     * on the sides, both animated.
     */
    static BrickModel fluidBrick(BlockState st) {
        var fs = st.getFluidState();
        if (fs.isEmpty() || fs.is(net.minecraft.tags.FluidTags.WATER)) return null;
        net.minecraft.client.renderer.block.FluidModel fm;
        try {
            fm = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fs);
        } catch (RuntimeException e) {
            return null;
        }
        if (fm == null) return null;
        int[] still = spriteTexels(fm.stillMaterial().sprite()), flow = spriteTexels(fm.flowingMaterial().sprite());
        if (still[0] == 0 || flow[0] == 0) return null;
        float h = Math.max(1f / 16f, Math.min(1f, fs.getOwnHeight()));
        List<BrickQuad> out = new ArrayList<>();
        float[] full = {0, 0, 1, 0, 1, 1, 0, 1};
        out.add(new BrickQuad(new float[] {0, h, 0, 1, h, 0, 1, h, 1, 0, h, 1}, full, still, 0xFFFFFF, Direction.UP.get3DDataValue()));
        out.add(new BrickQuad(new float[] {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, full, still, 0xFFFFFF, Direction.DOWN.get3DDataValue()));
        float[] side = {0, 0, 0.5f, 0, 0.5f, 0.5f * h, 0, 0.5f * h};  // (the flowing texture at half scale, as vanilla's)
        out.add(new BrickQuad(new float[] {0, h, 0, 1, h, 0, 1, 0, 0, 0, 0, 0}, side, flow, 0xFFFFFF, Direction.NORTH.get3DDataValue()));
        out.add(new BrickQuad(new float[] {0, h, 1, 1, h, 1, 1, 0, 1, 0, 0, 1}, side, flow, 0xFFFFFF, Direction.SOUTH.get3DDataValue()));
        out.add(new BrickQuad(new float[] {0, h, 0, 0, h, 1, 0, 0, 1, 0, 0, 0}, side, flow, 0xFFFFFF, Direction.WEST.get3DDataValue()));
        out.add(new BrickQuad(new float[] {1, h, 0, 1, h, 1, 1, 0, 1, 1, 0, 0}, side, flow, 0xFFFFFF, Direction.EAST.get3DDataValue()));
        return new BrickModel(out, st.getLightEmission() / 15f);
    }

    /** (a, b, depth along d's normal) of a block-space point for face d. */
    static float[] plane(Direction d, float x, float y, float z) {
        return switch (d) {
            case DOWN -> new float[] {x, z, -y};
            case UP -> new float[] {x, z, y};
            case NORTH -> new float[] {x, y, -z};
            case SOUTH -> new float[] {x, y, z};
            case WEST -> new float[] {z, y, -x};
            case EAST -> new float[] {z, y, x};
        };
    }

    private static int corner(float[] pa, float[] pb, float a, float b) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float dd = (pa[i] - a) * (pa[i] - a) + (pb[i] - b) * (pb[i] - b);
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        return best;
    }

    private static float lerp2(float v00, float v10, float v01, float v11, float s, float t) {
        return (v00 * (1 - s) + v10 * s) * (1 - t) + (v01 * (1 - s) + v11 * s) * t;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}

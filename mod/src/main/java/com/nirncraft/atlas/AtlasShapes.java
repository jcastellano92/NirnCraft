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

    /** The model quads the lab's library samples a state's faces from (its parts at a fixed seed, every face and the unculled). */
    static List<BakedQuad> quads(BlockState st) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st).collectParts(RandomSource.create(42L), parts);
        } catch (RuntimeException e) {
            parts.clear();
        }
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart p : parts) {
            for (Direction d : Direction.values()) quads.addAll(p.getQuads(d));
            quads.addAll(p.getQuads(null));
        }
        return quads;
    }

    /** The state's outline shape as boxes (none when it cannot say). */
    static List<AABB> boxes(BlockState st) {
        VoxelShape shape;
        try {
            shape = st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            shape = net.minecraft.world.phys.shapes.Shapes.empty();
        }
        return shape.toAabbs();
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
        List<BakedQuad> quads = quads(st);
        List<AABB> boxes = boxes(st);
        long mask = mask(boxes);
        int[][] faces = new int[6][];
        for (Direction d : Direction.values()) faces[d.get3DDataValue()] = face(d, quads, tint);
        // thinner than a micro-voxel (a snow layer, a carpet, a torch, a lantern's chain): its outline fills none of the
        // 4 x 4 x 4, so its shape is the visual hull of its face tiles (a layer's bottom layer, a post's core column;
        // a chain is no floating slab)
        if (mask == 0 && !quads.isEmpty()) mask = hull(faces);
        boolean fluid = !st.getFluidState().isEmpty() && quads.isEmpty();
        if (fluid) mask = -1L;
        if (mask == 0 || quads.isEmpty() && !fluid) return null;
        // cut-out coverage for leaves and plants only (a grass block's cut-out overlay is no hole in the block)
        boolean cutout = st.is(BlockTags.LEAVES) || plant(st, quads, boxes);
        return new Shape(mask, mean(faces), cutout ? AtlasNative.SHAPE_CUTOUT : 0, faces);
    }

    /**
     * Face d's 4x4 tile: each texel the outermost quad facing d over it (by its centre), its sprite averaged over the
     * texel's area (4x4 samples), alpha the share of opaque samples, the quad's tint applied when it has one and `tint`
     * is set (RGB; -1: none, the lab's raw textures). The tile's (u, v) follow the face's plane: for up and down (x, z),
     * for north and south (x, y), for west and east (z, y); v grows downwards on the side faces.
     */
    static int[] face(Direction d, List<BakedQuad> quads, int tint) {
        int[] tile = new int[TILE * TILE];
        float[] depth = new float[TILE * TILE];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        for (BakedQuad q : quads) {
            if (q.direction() != d) continue;
            TextureAtlasSprite sp = q.materialInfo().sprite();
            NativeImage img;
            try {
                img = sp.contents().getOriginalImage();
            } catch (RuntimeException e) {
                continue;
            }
            boolean tinted = tint >= 0 && q.materialInfo().tintIndex() >= 0;
            int fw = sp.contents().width(), fh = sp.contents().height();
            // the quad's extent in the face plane and its depth along d
            float a0 = Float.MAX_VALUE, a1 = -Float.MAX_VALUE, b0 = Float.MAX_VALUE, b1 = -Float.MAX_VALUE, dep = 0;
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
                dep += ab[2] / 4f;
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
                    if (ca < a0 || ca > a1 || cb < b0 || cb > b1 || dep < depth[k]) continue;
                    double sr = 0, sg = 0, sb = 0, sa = 0;
                    int n = 0;
                    for (int sy = 0; sy < 4; sy++)
                        for (int sx = 0; sx < 4; sx++) {
                            float aa = (tu + (sx + 0.5f) / 4f) / TILE;
                            float bb = d.getAxis() == Direction.Axis.Y ? (tv + (sy + 0.5f) / 4f) / TILE : 1f - (tv + (sy + 0.5f) / 4f) / TILE;
                            float s = clamp01((aa - a0) / (a1 - a0)), t = clamp01((bb - b0) / (b1 - b0));
                            float u = lerp2(pu[c00], pu[c10], pu[c01], pu[c11], s, t), v = lerp2(pv[c00], pv[c10], pv[c01], pv[c11], s, t);
                            int px = Math.min(fw - 1, Math.max(0, (int) (u * fw))), py = Math.min(fh - 1, Math.max(0, (int) (v * fh)));
                            int c = img.getPixel(px, py);  // ARGB, frame 0
                            double al = (c >>> 24) / 255.0;
                            n++;
                            if (al < 0.1) continue;
                            sr += ((c >> 16) & 255);
                            sg += ((c >> 8) & 255);
                            sb += (c & 255);
                            sa += 1;
                        }
                    if (sa == 0) continue;  // the quad is see-through here: what is behind it shows
                    depth[k] = dep;
                    if (tinted) {
                        sr *= ((tint >> 16) & 255) / 255.0;
                        sg *= ((tint >> 8) & 255) / 255.0;
                        sb *= (tint & 255) / 255.0;
                    }
                    tile[k] = (int) Math.round(255.0 * sa / n) << 24 | (int) Math.round(sr / sa) << 16 | (int) Math.round(sg / sa) << 8
                            | (int) Math.round(sb / sa);
                }
        }
        return tile;
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

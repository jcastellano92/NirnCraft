package com.nirncraft.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.NirnCraft;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The experiment test bed's inputs (docs/ATLAS-LOD.md, level -1): {@code /nirncraft-atlaslab blocks} writes the
 * block-state library, {@code /nirncraft-atlaslab volume <name> <x0> <y0> <z0> <x1> <y1> <z1>} (or {@code here <name>
 * <radius> <down> <up>}) a box of real block state ids from the client's level. The offline lab renderer
 * (native/tools/atlas_lab.cpp) reads both from {@code <game dir>/nirncraft-atlaslab/} and renders the far-field
 * variants against them. The same exports are the autotest verb {@code atlaslab ...}.
 *
 * <p>blocks.bin (little-endian): "ALB1", u32 count, then per state: u32 state id, u16 name length + UTF-8 name (the
 * state's string), u8 class (0 solid, 1 foliage, 2 water, 3 emissive, 4 plant / cutout, 5 other), u8 light emission,
 * u8 flags (1 tinted, 2 translucent, 4 has quads, 8 full cube), u8 tint kind (0 none, 1 grass, 2 foliage, 3 water,
 * 4 other), u64 4x4x4 occupancy (bit x + 4 * (z + 4 * y), from the outline shape), u32 mean ARGB (alpha-weighted over
 * the faces), then 6 faces (down, up, north, south, west, east) of 4x4 ARGB texels (u32 each; alpha 0 where the face
 * shows nothing), the texel (u, v) along the face as Minecraft's face UVs run.
 *
 * <p>volume (name.alv, little-endian): "ALV1", i32 x0 y0 z0, i32 sx sy sz, then sx*sy*sz u32 state ids, x fastest,
 * then z, then y; beside it name.json: the grass, foliage and water tints the game blends at its centre, and the
 * lighting the camera sees there (sky light factor and colour, block light tint, ambient colour, the gamma option).
 *
 * <p>The exact reference (native/tools/atlas_truth.cpp) reproduces Minecraft's own chunk mesh offline, from these:
 *
 * <p>{@code quads}: quads.bin, every block state's model quads exactly as the chunk mesher gets them, and sprites.bin,
 * every sprite they use at its native resolution; quads.json beside them: the options that change the mesh (smooth
 * lighting, cutout leaves, NeoForge's per-part AO and enhanced lighting, biome blend radius, gamma).
 * quads.bin (little-endian): "ALQ1", u32 model count, then per model (a unique list of model parts: the states that
 * share a model share it) u8 the first part's AO mode (0 off, 1 forced on, 2 default: on unless the block emits
 * light), u32 quad count, then per quad 4 vertices of f32 x y z (block space, 0..1 for a plain cube) and f32 u v (in
 * the sprite's own 0..1 space), 4 u32 vertex normals (NeoForge's packing: signed bytes x, y, z; the quad's normal where
 * the model gives none), i32 sprite index (into sprites.bin), u8 direction (0 down, 1 up, 2 north, 3 south, 4 west,
 * 5 east), u8 cull face (the same, 6 none: the part's getQuads(null)), u8 shade direction override (6 none), u8 layer
 * (0 solid, 1 cutout, 2 translucent), i16 tint index (-1 none), u8 light emission, u8 AO flags (bit 0 the quad allows
 * AO, bits 1-2 its part's AO mode as above). Then u32 fluid model count, per fluid model i32 still, flowing and overlay
 * sprites (-1 none), u8 layer, u8 tint kind, u32 tint colour (kinds below). Then u32 state count, per state: u32 state
 * id, u16 name length + UTF-8 name, u32 block id (states of one block share it), u16 flags (1 solid render, 2 light
 * permeable, 4 collision shape a full block, 8 emissive rendering, 16 legacy solid, 32 can occlude, 64 leaves, 128
 * rendered by its model, 256 shows the fluid overlay texture beside water, 512 air), u8 light emission, u8 light
 * dampening, f32 shade brightness (AO), u8 offset type (0 none, 1 xz, 2 xyz; then f32 max horizontal and max vertical
 * offset), i8 fluid model (-1 none; then f32 own height, u8 amount, u8 fluid flags: 1 source, 2 falling), u8 faces
 * whose occlusion shape is the full block (bit = direction), u8 faces with a partial occlusion shape, then for each of
 * those (in direction order) 16 u16 rows of a 16 x 16 mask (bit a of row b; (a, b) along the face as (x, z) for
 * down / up, (x, y) for north / south, (z, y) for west / east), u8 faces it skips against a state of its own block
 * (glass against glass, leaves against leaves without cutout leaves), u8 tint layer count, per tint index u8 kind (0
 * white, 1 grass, 2 foliage, 3 water, 4 dry foliage, 5 constant) and u32 ARGB (the constant), u8 variant count (the
 * distinct part lists its model picks by position; 0: no model), per variant i32 model index.
 * sprites.bin: "ALT1", u32 count, per sprite u16 name length + name, u16 width, u16 height, u8 flags (1 animated),
 * then width x height u32 ARGB (the first frame, rows from the top).
 *
 * <p>{@code here} / {@code volume} / {@code view} with a trailing {@code light} write the light-carrying formats
 * beside the old ones (which stay as they are): name.alv2: "ALV2", i32 x0 y0 z0, i32 sx sy sz, u32 flags (1 light,
 * 2 variants, 4 column tints), the u32 state ids as in ALV1, then sx*sy*sz u8 light (sky << 4 | block, from the
 * client's light engine), sx*sy*sz u8 variant (the index into the state's variants in quads.bin), and sx*sz columns
 * (x fastest) of 4 u32 RGB tints: grass, foliage, water, dry foliage, blended as the game blends them, at the column's
 * top block in the box. name.als2: "ALS2", u32 flags (1 column tints, raw biome colours), u32 the blend radius, u32
 * section count, then per section the ALS1 record, u8 flags (1 light, 2 variants), 4096 u8 light and 4096 u8 variants
 * when flagged (x + 16 * (z + 16 * y); light from the integrated server's light engine), then u32 chunk count, per
 * chunk i32 cx cz and 256 columns (x + 16 * z) of the 4 raw tints at the column's surface (blended offline).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class AtlasLabExport {
    private AtlasLabExport() {}

    static final int TILE = 4;

    /** Progress of the running view export (chunks done; the autotest's status carries it: liveness, not a hang). */
    public static volatile int viewProgress;

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        // one greedy argument each: the same parser as the autotest verb
        event.getDispatcher().register(Commands.literal("nirncraft-atlaslab")
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                    String r;
                    try {
                        r = autotest(StringArgumentType.getString(ctx, "args"));
                    } catch (RuntimeException e) {
                        r = "atlaslab: " + e.getMessage();
                    }
                    String msg = r;
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })));
    }

    /**
     * The autotest verb: "blocks", "quads", "volume NAME X0 Y0 Z0 X1 Y1 Z1 [light]", "here NAME RADIUS DOWN UP [light]"
     * or "view NAME DISTANCE [light]". Client thread.
     */
    public static String autotest(String args) {
        String[] a = args.trim().split("\\s+");
        boolean light = a.length > 1 && a[a.length - 1].equals("light");
        return switch (a[0]) {
            case "blocks" -> blocks();
            case "quads" -> quads();
            case "volume" -> volume(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
                    Integer.parseInt(a[5]), Integer.parseInt(a[6]), Integer.parseInt(a[7]), light);
            case "here" -> here(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]), light);
            case "view" -> "atlaslab: view started (" + view(a[1], Integer.parseInt(a[2]), light).thenAccept(NirnCraft.LOGGER::info) + ")";
            default -> throw new IllegalArgumentException("atlaslab: blocks | quads | volume NAME X0 Y0 Z0 X1 Y1 Z1 [light] | here NAME R DOWN UP [light]"
                    + " | view NAME DISTANCE [light]");
        };
    }

    static Path dir() throws IOException {
        Path d = Minecraft.getInstance().gameDirectory.toPath().resolve("nirncraft-atlaslab");
        Files.createDirectories(d);
        return d;
    }

    // ---- the block-state library --------------------------------------------------------------------------------

    static String blocks() {
        Minecraft mc = Minecraft.getInstance();
        var models = mc.getModelManager().getBlockStateModelSet();
        List<BlockState> states = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) states.addAll(b.getStateDefinition().getPossibleStates());
        Path out;
        int withQuads = 0;
        try {
            out = dir().resolve("blocks.bin");
            try (OutputStream os = Files.newOutputStream(out)) {
                ByteBuffer head = le(8);
                head.put("ALB1".getBytes(StandardCharsets.US_ASCII)).putInt(states.size());
                os.write(head.array());
                List<BlockStateModelPart> parts = new ArrayList<>();
                RandomSource rnd = RandomSource.create(42L);
                for (BlockState st : states) {
                    parts.clear();
                    rnd.setSeed(42L);
                    try {
                        models.get(st).collectParts(rnd, parts);
                    } catch (RuntimeException e) {
                        parts.clear();
                    }
                    List<BakedQuad> quads = new ArrayList<>();
                    for (BlockStateModelPart p : parts) {
                        for (Direction d : Direction.values()) quads.addAll(p.getQuads(d));
                        quads.addAll(p.getQuads(null));
                    }
                    if (!quads.isEmpty()) withQuads++;
                    os.write(entry(st, quads));
                }
            }
        } catch (IOException e) {
            return "atlaslab: blocks not written (" + e + ")";
        }
        String r = "atlaslab: " + states.size() + " block states (" + withQuads + " with quads) -> " + out;
        NirnCraft.LOGGER.info(r);
        return r;
    }

    private static byte[] entry(BlockState st, List<BakedQuad> quads) {
        byte[] name = st.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = le(4 + 2 + name.length + 4 + 8 + 4 + 6 * TILE * TILE * 4);
        b.putInt(Block.getId(st)).putShort((short) name.length).put(name);
        boolean tinted = false, translucent = false;
        for (BakedQuad q : quads) {
            tinted |= q.materialInfo().tintIndex() >= 0;
            translucent |= q.materialInfo().layer() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT;
        }
        VoxelShape shape;
        try {
            shape = st.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            shape = net.minecraft.world.phys.shapes.Shapes.empty();
        }
        long mask = 0;
        List<AABB> boxes = shape.toAabbs();
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
        boolean full = mask == -1L;
        int cls;
        if (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !st.isSolidRender()) cls = 2;
        else if (st.getLightEmission() >= 10) cls = 3;
        else if (st.is(BlockTags.LEAVES)) cls = 1;
        else if (st.isSolidRender() || full) cls = 0;
        else if (!quads.isEmpty() && boxes.isEmpty() || st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(BlockTags.FLOWERS)) cls = 4;
        else cls = 5;
        int tintKind = !tinted ? 0 : st.is(BlockTags.LEAVES) || st.getBlock() instanceof net.minecraft.world.level.block.VineBlock ? 2
                : cls == 2 ? 3 : st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(Block.byItem(net.minecraft.world.item.Items.GRASS_BLOCK)) ? 1 : 4;
        int flags = (tinted ? 1 : 0) | (translucent ? 2 : 0) | (quads.isEmpty() ? 0 : 4) | (full ? 8 : 0);
        b.put((byte) cls).put((byte) st.getLightEmission()).put((byte) flags).put((byte) tintKind);
        b.putLong(mask);
        int[][] faces = new int[6][];
        double r = 0, g = 0, bl = 0, w = 0;
        for (Direction d : Direction.values()) {
            int[] tile = face(d, quads);
            faces[d.get3DDataValue()] = tile;
            for (int c : tile) {
                double a = (c >>> 24) / 255.0;
                r += a * ((c >> 16) & 255);
                g += a * ((c >> 8) & 255);
                bl += a * (c & 255);
                w += a;
            }
        }
        int mean = w <= 0 ? 0 : 0xFF000000 | (int) Math.round(r / w) << 16 | (int) Math.round(g / w) << 8 | (int) Math.round(bl / w);
        b.putInt(mean);
        for (int[] tile : faces) for (int c : tile) b.putInt(c);
        return b.array();
    }

    /**
     * Face d's 4x4 tile: each texel the outermost quad facing d over it (by its centre), its sprite averaged over the
     * texel's area (4x4 samples), alpha the share of opaque samples. The tile's (u, v) follow the face's plane: for up
     * and down (x, z), for north and south (x, y), for west and east (z, y); v grows downwards on the side faces.
     */
    private static int[] face(Direction d, List<BakedQuad> quads) {
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
                    tile[k] = (int) Math.round(255.0 * sa / n) << 24 | (int) Math.round(sr / sa) << 16 | (int) Math.round(sg / sa) << 8
                            | (int) Math.round(sb / sa);
                }
        }
        return tile;
    }

    /** (a, b, depth along d's normal) of a block-space point for face d. */
    private static float[] plane(Direction d, float x, float y, float z) {
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

    // ---- the exact mesh: every state's quads and every sprite (atlas_truth) -----------------------------------------

    /** What a tint source answers for each biome colour resolver: the kind it used, found again in its result. */
    private static final int PROBE_GRASS = 0xFE0101, PROBE_FOLIAGE = 0xFE0102, PROBE_DRY = 0xFE0103, PROBE_WATER = 0xFE0104,
            PROBE_OTHER = 0xFE0105;
    private static final net.minecraft.client.renderer.block.BlockAndTintGetter TINT_PROBE = new net.minecraft.client.renderer.block.BlockAndTintGetter() {
        @Override
        public net.minecraft.world.level.CardinalLighting cardinalLighting() {
            return net.minecraft.world.level.CardinalLighting.DEFAULT;
        }

        @Override
        public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
            return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
        }

        @Override
        public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver color) {
            return color == net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER ? PROBE_GRASS
                    : color == net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER ? PROBE_FOLIAGE
                    : color == net.minecraft.client.renderer.BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER ? PROBE_DRY
                    : color == net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER ? PROBE_WATER : PROBE_OTHER;
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    };

    /** A tint source's answer as (kind, colour): white, a biome colour (grass, foliage, water, dry foliage) or a constant. */
    private static void putTint(ByteBuffer b, int c) {
        int rgb = c & 0xFFFFFF;
        int kind = c == -1 ? 0 : rgb == PROBE_GRASS || rgb == PROBE_OTHER ? 1 : rgb == PROBE_FOLIAGE ? 2 : rgb == PROBE_WATER ? 3
                : rgb == PROBE_DRY ? 4 : 5;
        b.put((byte) kind).putInt(kind == 5 ? c : -1);
    }

    /** The distinct part lists a state's model picks by position (in a fixed order: the index the volumes carry). */
    private record Variants(Object model, List<List<BlockStateModelPart>> lists) {}

    private static final Map<BlockState, Variants> VARIANTS = new java.util.concurrent.ConcurrentHashMap<>();

    static List<List<BlockStateModelPart>> variants(BlockState st) {
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st);
        Variants v = VARIANTS.get(st);
        if (v != null && v.model() == model) return v.lists();
        List<List<BlockStateModelPart>> lists = new ArrayList<>();
        RandomSource rnd = RandomSource.createThreadLocalInstance(0L);
        // the chunk mesher seeds its random with the state's seed at the block; any fixed list of positions finds the
        // variants (a model that gives the same parts for 16 seeds has one)
        for (int k = 0; k < 256; k++) {
            BlockPos at = new BlockPos(k & 15, 64, k >> 4);
            List<BlockStateModelPart> parts = new ArrayList<>();
            rnd.setSeed(st.getSeed(at));
            try {
                model.collectParts(net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY, at, st, rnd, parts);
            } catch (RuntimeException e) {
                parts.clear();
            }
            if (!lists.contains(parts) && lists.size() < 255) lists.add(parts);
            if (k == 15 && lists.size() == 1) break;
        }
        VARIANTS.put(st, new Variants(model, lists));
        return lists;
    }

    /** The variant the chunk mesher draws for st at pos: its index in variants(st) (0 when it has one). */
    static int variantAt(BlockState st, BlockPos pos) {
        List<List<BlockStateModelPart>> lists = variants(st);
        if (lists.size() <= 1) return 0;
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st);
        RandomSource rnd = RandomSource.createThreadLocalInstance(st.getSeed(pos));
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            model.collectParts(net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY, pos, st, rnd, parts);
        } catch (RuntimeException e) {
            return 0;
        }
        return Math.max(0, lists.indexOf(parts));
    }

    static String quads() {
        Minecraft mc = Minecraft.getInstance();
        var fluids = mc.getModelManager().getFluidStateModelSet();
        var colors = mc.getBlockColors();
        List<BlockState> states = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) states.addAll(b.getStateDefinition().getPossibleStates());
        Map<TextureAtlasSprite, Integer> sprites = new java.util.IdentityHashMap<>();
        List<TextureAtlasSprite> spriteList = new ArrayList<>();
        java.util.function.ToIntFunction<TextureAtlasSprite> sprite = sp -> sprites.computeIfAbsent(sp, k -> {
            spriteList.add(k);
            return spriteList.size() - 1;
        });
        Map<List<BlockStateModelPart>, Integer> modelIndex = new HashMap<>();
        java.io.ByteArrayOutputStream modelBytes = new java.io.ByteArrayOutputStream();
        Map<net.minecraft.client.renderer.block.FluidModel, Integer> fluidIndex = new java.util.IdentityHashMap<>();
        java.io.ByteArrayOutputStream fluidBytes = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream stateBytes = new java.io.ByteArrayOutputStream();
        int quadCount = 0, multi = 0;
        try {
            for (BlockState st : states) {
                List<List<BlockStateModelPart>> vars = st.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL ? variants(st) : List.of();
                int[] vi = new int[vars.size()];
                for (int k = 0; k < vars.size(); k++) {
                    List<BlockStateModelPart> parts = vars.get(k);
                    Integer m = modelIndex.get(parts);
                    if (m == null) {
                        m = modelIndex.size();
                        modelIndex.put(parts, m);
                        quadCount += model(modelBytes, parts, sprite);
                    }
                    vi[k] = m;
                }
                if (vars.size() > 1) multi++;
                int fluid = -1;
                var fs = st.getFluidState();
                if (!fs.isEmpty()) {
                    var fm = fluids.get(fs);
                    Integer f = fluidIndex.get(fm);
                    if (f == null) {
                        f = fluidIndex.size();
                        fluidIndex.put(fm, f);
                        ByteBuffer b = le(4 * 3 + 2 + 8);
                        b.putInt(sprite.applyAsInt(fm.stillMaterial().sprite())).putInt(sprite.applyAsInt(fm.flowingMaterial().sprite()))
                                .putInt(fm.overlayMaterial() == null ? -1 : sprite.applyAsInt(fm.overlayMaterial().sprite()));
                        b.put((byte) fm.layer().ordinal());
                        int c = -1;
                        try {
                            if (fm.fluidTintSource() != null) c = fm.fluidTintSource().colorInWorld(fs, st, TINT_PROBE, BlockPos.ZERO);
                        } catch (RuntimeException e) {
                            c = -1;
                        }
                        putTint(b, c);
                        fluidBytes.write(b.array(), 0, b.position());
                    }
                    fluid = f;
                }
                stateBytes.write(stateEntry(st, vi, fluid, colors));
            }
            Path dir = dir();
            try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(dir.resolve("quads.bin")))) {
                os.write(le(8).put("ALQ1".getBytes(StandardCharsets.US_ASCII)).putInt(modelIndex.size()).array());
                modelBytes.writeTo(os);
                os.write(le(4).putInt(fluidIndex.size()).array());
                fluidBytes.writeTo(os);
                os.write(le(4).putInt(states.size()).array());
                stateBytes.writeTo(os);
            }
            try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(dir.resolve("sprites.bin")))) {
                os.write(le(8).put("ALT1".getBytes(StandardCharsets.US_ASCII)).putInt(spriteList.size()).array());
                for (TextureAtlasSprite sp : spriteList) os.write(spriteEntry(sp));
            }
            var no = net.neoforged.neoforge.client.config.NeoForgeClientConfig.INSTANCE;
            Files.writeString(dir.resolve("quads.json"), String.format(java.util.Locale.ROOT,
                    "{\"ao\": %b, \"cutoutLeaves\": %b, \"perPartAo\": %b, \"enhancedLighting\": %b, \"biomeBlend\": %d, \"gamma\": %.3f,"
                            + " \"mipmaps\": %d}%n",
                    mc.options.ambientOcclusion().get(), mc.options.cutoutLeaves().get(), no.handleAmbientOcclusionPerPart.getAsBoolean(),
                    no.enhancedLighting.getAsBoolean(), mc.options.biomeBlendRadius().get(), mc.options.gamma().get(), mc.options.mipmapLevels().get()));
        } catch (IOException e) {
            return "atlaslab: quads not written (" + e + ")";
        }
        String r = "atlaslab: " + states.size() + " block states, " + modelIndex.size() + " models (" + quadCount + " quads, " + multi
                + " states with position variants), " + fluidIndex.size() + " fluid models, " + spriteList.size() + " sprites -> quads.bin, sprites.bin";
        NirnCraft.LOGGER.info(r);
        return r;
    }

    /** One model (a part list): its quads as the chunk mesher gets them, each part's culled faces then its unculled. */
    private static int model(java.io.ByteArrayOutputStream out, List<BlockStateModelPart> parts,
                             java.util.function.ToIntFunction<TextureAtlasSprite> sprite) {
        List<BakedQuad> quads = new ArrayList<>();
        List<int[]> meta = new ArrayList<>();  // cull face, part AO mode
        int firstAo = 2;
        for (int pi = 0; pi < parts.size(); pi++) {
            BlockStateModelPart p = parts.get(pi);
            int ao = switch (p.ambientOcclusion()) {
                case TRUE -> 1;
                case FALSE -> 0;
                default -> 2;
            };
            if (pi == 0) firstAo = ao;
            for (Direction d : Direction.values())
                for (BakedQuad q : p.getQuads(d)) {
                    quads.add(q);
                    meta.add(new int[] {d.get3DDataValue(), ao});
                }
            for (BakedQuad q : p.getQuads(null)) {
                quads.add(q);
                meta.add(new int[] {6, ao});
            }
        }
        ByteBuffer b = le(5 + quads.size() * 108);
        b.put((byte) firstAo).putInt(quads.size());
        for (int k = 0; k < quads.size(); k++) {
            BakedQuad q = quads.get(k);
            var mi = q.materialInfo();
            TextureAtlasSprite sp = mi.sprite();
            float du = sp.getU1() - sp.getU0(), dv = sp.getV1() - sp.getV0();
            for (int i = 0; i < 4; i++) {
                Vector3fc p = q.position(i);
                long uv = q.packedUV(i);
                b.putFloat(p.x()).putFloat(p.y()).putFloat(p.z());
                b.putFloat(du == 0 ? 0 : (UVPair.unpackU(uv) - sp.getU0()) / du).putFloat(dv == 0 ? 0 : (UVPair.unpackV(uv) - sp.getV0()) / dv);
            }
            int qn = net.neoforged.neoforge.client.model.quad.BakedNormals.computeQuadNormal(q.position0(), q.position1(), q.position2(), q.position3());
            for (int i = 0; i < 4; i++) {
                int n = q.bakedNormals().normal(i);
                b.putInt(net.neoforged.neoforge.client.model.quad.BakedNormals.isUnspecified(n) ? qn : n);
            }
            b.putInt(sprite.applyAsInt(sp));
            b.put((byte) q.direction().get3DDataValue()).put((byte) meta.get(k)[0])
                    .put((byte) (mi.shadeDirectionOverride() == null ? 6 : mi.shadeDirectionOverride().get3DDataValue())).put((byte) mi.layer().ordinal());
            b.putShort((short) Math.max(-1, Math.min(Short.MAX_VALUE, mi.tintIndex()))).put((byte) mi.lightEmission())
                    .put((byte) ((mi.ambientOcclusion() ? 1 : 0) | meta.get(k)[1] << 1));
        }
        out.write(b.array(), 0, b.position());
        return quads.size();
    }

    private static byte[] stateEntry(BlockState st, int[] variants, int fluid, net.minecraft.client.color.block.BlockColors colors) {
        byte[] name = st.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = le(4 + 2 + name.length + 4 + 2 + 2 + 4 + 9 + 7 + 2 + 6 * 32 + 1 + 1 + 64 * 5 + 1 + 4 * variants.length);
        b.putInt(Block.getId(st)).putShort((short) name.length).put(name).putInt(BuiltInRegistries.BLOCK.getId(st.getBlock()));
        var g = EmptyBlockGetter.INSTANCE;
        boolean full;
        try {
            full = st.isCollisionShapeFullBlock(g, BlockPos.ZERO);
        } catch (RuntimeException e) {
            full = false;
        }
        boolean overlay;
        try {
            overlay = st.shouldDisplayFluidOverlay(TINT_PROBE, BlockPos.ZERO, net.minecraft.world.level.material.Fluids.WATER.defaultFluidState());
        } catch (RuntimeException e) {
            overlay = false;
        }
        int flags = (st.isSolidRender() ? 1 : 0) | (st.isLightPermeable() ? 2 : 0) | (full ? 4 : 0) | (st.emissiveRendering() ? 8 : 0)
                | (st.isSolid() ? 16 : 0) | (st.canOcclude() ? 32 : 0) | (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock ? 64 : 0)
                | (st.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL ? 128 : 0) | (overlay ? 256 : 0) | (st.isAir() ? 512 : 0);
        b.putShort((short) flags).put((byte) st.getLightEmission()).put((byte) st.getLightDampening());
        float shade;
        try {
            shade = st.getShadeBrightness(g, BlockPos.ZERO);
        } catch (RuntimeException e) {
            shade = full ? 0.2f : 1f;
        }
        b.putFloat(shade);
        // the random offset (flowers, grass): its kind and limits found from a few positions (Mth.getSeed is public)
        if (!st.hasOffsetFunction()) {
            b.put((byte) 0);
        } else {
            boolean y = false;
            float maxH = 0.25f, maxV = 0.2f;
            for (int k = 0; k < 64; k++) {
                BlockPos at = new BlockPos(k * 7 - 200, 0, k * 13 - 300);
                var o = st.getOffset(at);
                long seed = net.minecraft.util.Mth.getSeed(at.getX(), 0, at.getZ());
                double rawX = ((float) (seed & 15L) / 15.0F - 0.5) * 0.5;
                if (Math.abs(o.x) < Math.abs(rawX) - 1e-6) maxH = (float) Math.abs(o.x);
                int ny = (int) (seed >> 4 & 15L);
                if (o.y != 0) {
                    y = true;
                    if (ny != 15) maxV = (float) (o.y / ((float) ny / 15.0F - 1.0));
                }
            }
            b.put((byte) (y ? 2 : 1)).putFloat(maxH).putFloat(maxV);
        }
        b.put((byte) fluid);
        if (fluid >= 0) {
            var fs = st.getFluidState();
            b.putFloat(fs.getOwnHeight()).put((byte) fs.getAmount()).put((byte) ((fs.isSource() ? 1 : 0)
                    | (fs.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING) && fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING) ? 2 : 0)));
        }
        // face occlusion shapes: the culling the chunk mesher does against neighbours (Block.shouldRenderFace)
        int fullFaces = 0, partialFaces = 0;
        short[][] masks = new short[6][];
        for (Direction d : Direction.values()) {
            VoxelShape s;
            try {
                s = st.getFaceOcclusionShape(d);
            } catch (RuntimeException e) {
                s = net.minecraft.world.phys.shapes.Shapes.empty();
            }
            if (s == net.minecraft.world.phys.shapes.Shapes.block()) {
                fullFaces |= 1 << d.get3DDataValue();
                continue;
            }
            if (s.isEmpty()) continue;
            short[] rows = new short[16];
            List<AABB> boxes = s.toAabbs();
            for (int bb = 0; bb < 16; bb++)
                for (int aa = 0; aa < 16; aa++) {
                    double ca = (aa + 0.5) / 16, cb = (bb + 0.5) / 16;
                    for (AABB box : boxes) {
                        float[] lo = plane(d, (float) box.minX, (float) box.minY, (float) box.minZ), hi = plane(d, (float) box.maxX, (float) box.maxY, (float) box.maxZ);
                        if (ca > Math.min(lo[0], hi[0]) && ca < Math.max(lo[0], hi[0]) && cb > Math.min(lo[1], hi[1]) && cb < Math.max(lo[1], hi[1])) {
                            rows[bb] |= (short) (1 << aa);
                            break;
                        }
                    }
                }
            partialFaces |= 1 << d.get3DDataValue();
            masks[d.get3DDataValue()] = rows;
        }
        b.put((byte) fullFaces).put((byte) partialFaces);
        for (short[] rows : masks)
            if (rows != null) for (short r : rows) b.putShort(r);
        int skip = 0;
        for (Direction d : Direction.values()) {
            try {
                if (st.skipRendering(st, d)) skip |= 1 << d.get3DDataValue();
            } catch (RuntimeException e) {
                // no skip
            }
        }
        b.put((byte) skip);
        var sources = colors.getTintSources(st);
        int nt = Math.min(64, sources.size());
        b.put((byte) nt);
        for (int t = 0; t < nt; t++) {
            int c;
            try {
                c = sources.get(t) == null ? -1 : sources.get(t).colorInWorld(st, TINT_PROBE, BlockPos.ZERO);
            } catch (RuntimeException e) {
                c = -1;
            }
            putTint(b, c);
        }
        b.put((byte) variants.length);
        for (int v : variants) b.putInt(v);
        return java.util.Arrays.copyOf(b.array(), b.position());
    }

    private static byte[] spriteEntry(TextureAtlasSprite sp) {
        var c = sp.contents();
        byte[] name = c.name().toString().getBytes(StandardCharsets.UTF_8);
        int w = c.width(), h = c.height();
        ByteBuffer b = le(2 + name.length + 5 + w * h * 4);
        b.putShort((short) name.length).put(name).putShort((short) w).putShort((short) h).put((byte) (c.isAnimated() ? 1 : 0));
        NativeImage img = null;
        try {
            img = c.getOriginalImage();
        } catch (RuntimeException e) {
            // missing: magenta
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                b.putInt(img == null || x >= img.getWidth() || y >= img.getHeight() ? 0xFFFF00FF : img.getPixel(x, y));
        return b.array();
    }

    // ---- the real blocks in the camera's view, to any distance ------------------------------------------------------

    /**
     * {@code view NAME DISTANCE}: every chunk in the camera's view (its horizontal field of view plus a margin) out to
     * DISTANCE blocks, loaded or generated by the integrated server (the same generators: the real world there, far
     * past the render distance), its sections from 16 under its lowest ground to its highest top written sparsely to
     * NAME.als (little-endian): "ALS1", u32 count, then per section i32 sx sy sz (section coordinates), u16 palette
     * size, the palette's u32 state ids, and when it has more than one entry 4096 indices (x + 16 * (z + 16 * y)),
     * u8 up to 256 entries else u16. All-air sections are left out. The server does it a few chunks a tick; the future
     * completes with the summary once the file is written. With {@code light}: NAME.als2 instead (the class Javadoc),
     * each section with its sky and block light from the server's light engine and its model variants, each chunk with
     * its columns' biome tints.
     */
    public static java.util.concurrent.CompletableFuture<String> view(String name, int distance) {
        return view(name, distance, false);
    }

    public static java.util.concurrent.CompletableFuture<String> view(String name, int distance, boolean light) {
        var done = new java.util.concurrent.CompletableFuture<String>();
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            done.complete("atlaslab: view needs a single-player world (its server generates the chunks)");
            return done;
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            done.complete("atlaslab: bad view name");
            return done;
        }
        var eye = mc.player.getEyePosition();
        double yaw = Math.toRadians(mc.player.getYRot());
        // already exported for this generation, pose and distance: nothing to redo (a test world restored from its copy
        // would otherwise regenerate the whole view every session); anything that changes generation changes the key
        String key = AtlasTileCodec.identity() + " " + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f %.1f %d %d",
                eye.x, eye.y, eye.z, mc.player.getYRot(), distance, mc.options.fov().get());
        String ext = light ? ".als2" : ".als";
        String lighting = lighting();  // the camera's lighting, read here on the client thread
        try {
            Path keyFile = dir().resolve(name + (light ? ".light.key" : ".key")), als = dir().resolve(name + ext);
            if (Files.isRegularFile(als) && Files.isRegularFile(keyFile) && Files.readString(keyFile).trim().equals(key)) {
                done.complete("atlaslab: view " + name + " is up to date (same generation, pose and distance) -> " + als
                        + " (0 sections in 0 s)");
                return done;
            }
        } catch (IOException e) {
            // export it again
        }
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double vfov = Math.toRadians(mc.options.fov().get());
        double half = Math.atan(Math.tan(vfov / 2) * aspect) + Math.toRadians(8);  // half the horizontal view, a margin
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);  // Minecraft: yaw 0 looks south (+z)
        List<long[]> chunks = new ArrayList<>();
        int pcx = Math.floorDiv((int) Math.floor(eye.x), 16), pcz = Math.floorDiv((int) Math.floor(eye.z), 16), r = distance / 16 + 1;
        for (int cz = pcz - r; cz <= pcz + r; cz++)
            for (int cx = pcx - r; cx <= pcx + r; cx++) {
                double dx = cx * 16 + 8 - eye.x, dz = cz * 16 + 8 - eye.z, d = Math.sqrt(dx * dx + dz * dz);
                if (d > distance + 12) continue;
                boolean near = Math.abs(cx - pcx) <= 2 && Math.abs(cz - pcz) <= 2;
                double ang = Math.acos(Math.max(-1, Math.min(1, (dx * fx + dz * fz) / Math.max(d, 1e-6))));
                if (near || ang <= half + Math.atan2(12, Math.max(d, 1))) chunks.add(new long[] {cx, cz});
            }
        var level = server.getLevel(mc.player.level().dimension());
        List<byte[]> sections = new ArrayList<>(), columns = new ArrayList<>();
        int[] at = {0};
        long start = System.currentTimeMillis();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                long until = System.nanoTime() + 40_000_000L;  // a share of the tick, then the next tick
                while (at[0] < chunks.size() && System.nanoTime() < until) {
                    long[] c = chunks.get(at[0]++);
                    viewProgress = at[0];
                    var chunk = level.getChunk((int) c[0], (int) c[1]);
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            lo = Math.min(lo, chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z));
                            hi = Math.max(hi, chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z));
                        }
                    int minSec = chunk.getMinSectionY();
                    int sy0 = Math.max(minSec, Math.floorDiv(lo - 16, 16)), sy1 = Math.min(chunk.getMaxSectionY(), Math.floorDiv(hi, 16));
                    for (int sy = sy0; sy <= sy1; sy++) {
                        var sec = chunk.getSection(sy - minSec);
                        if (sec == null || sec.hasOnlyAir()) continue;
                        byte[] b = sectionBytes((int) c[0], sy, (int) c[1], sec);
                        sections.add(light ? sectionLight(level, (int) c[0], sy, (int) c[1], sec, b) : b);
                    }
                    if (light) columns.add(columnTints(level, chunk, (int) c[0], (int) c[1]));
                }
                if (at[0] < chunks.size()) {
                    server.execute(step[0]);
                    return;
                }
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        Path out = dir().resolve(name + ext);
                        try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(out))) {
                            if (light) {
                                os.write(le(16).put("ALS2".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(mc.options.biomeBlendRadius().get())
                                        .putInt(sections.size()).array());
                            } else {
                                os.write(le(8).put("ALS1".getBytes(StandardCharsets.US_ASCII)).putInt(sections.size()).array());
                            }
                            for (byte[] b : sections) os.write(b);
                            if (light) {
                                os.write(le(4).putInt(columns.size()).array());
                                for (byte[] b : columns) os.write(b);
                            }
                        }
                        // the tints the game blends at the eye (one for the whole view; ALS2 carries them per column)
                        writeTints(name, net.minecraft.core.BlockPos.containing(eye), lighting);
                        Files.writeString(dir().resolve(name + (light ? ".light.key" : ".key")), key);
                        String msg = "atlaslab: view " + name + ": " + chunks.size() + " chunks to " + distance + " blocks, " + sections.size()
                                + " sections in " + (System.currentTimeMillis() - start) / 1000 + " s -> " + out;
                        NirnCraft.LOGGER.info(msg);
                        done.complete(msg);
                    } catch (IOException e) {
                        done.complete("atlaslab: view not written (" + e + ")");
                    }
                });
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        };
        server.execute(step[0]);
        return done;
    }

    private static byte[] sectionBytes(int cx, int sy, int cz, net.minecraft.world.level.chunk.LevelChunkSection sec) {
        Map<Integer, Integer> pal = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        int[] idx = new int[4096];
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int id = Block.getId(sec.getBlockState(x, y, z));
                    Integer k = pal.get(id);
                    if (k == null) {
                        k = ids.size();
                        ids.add(id);
                        pal.put(id, k);
                    }
                    idx[x + 16 * (z + 16 * y)] = k;
                }
        int n = ids.size();
        ByteBuffer b = le(12 + 2 + 4 * n + (n > 1 ? (n <= 256 ? 4096 : 8192) : 0));
        b.putInt(cx).putInt(sy).putInt(cz).putShort((short) n);
        for (int id : ids) b.putInt(id);
        if (n > 1)
            for (int v : idx) {
                if (n <= 256) b.put((byte) v);
                else b.putShort((short) v);
            }
        return b.array();
    }

    /** An ALS2 section: the ALS1 record, then its light (the server's light engine) and its model variants. */
    private static byte[] sectionLight(net.minecraft.server.level.ServerLevel level, int cx, int sy, int cz,
                                       net.minecraft.world.level.chunk.LevelChunkSection sec, byte[] ids) {
        var engine = level.getLightEngine();
        var sp = net.minecraft.core.SectionPos.of(cx, sy, cz);
        var skyL = engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        var blockL = engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        var skyD = skyL.getDataLayerData(sp);
        var blockD = blockL.getDataLayerData(sp);
        byte[] lt = new byte[4096], var = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int i = x + 16 * (z + 16 * y);
                    m.set(cx * 16 + x, sy * 16 + y, cz * 16 + z);
                    int s = skyD != null ? skyD.get(x, y, z) : skyL.getLightValue(m);
                    int bl = blockD != null ? blockD.get(x, y, z) : blockL.getLightValue(m);
                    lt[i] = (byte) (s << 4 | bl);
                    BlockState st = sec.getBlockState(x, y, z);
                    if (st.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL && variants(st).size() > 1) {
                        if (var == null) var = new byte[4096];
                        var[i] = (byte) variantAt(st, m);
                    }
                }
        ByteBuffer b = le(ids.length + 1 + 4096 + (var != null ? 4096 : 0));
        b.put(ids).put((byte) (1 | (var != null ? 2 : 0))).put(lt);
        if (var != null) b.put(var);
        return b.array();
    }

    /** An ALS2 chunk record: its 256 columns' raw biome tints (grass, foliage, water, dry foliage) at the surface. */
    private static byte[] columnTints(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk, int cx, int cz) {
        ByteBuffer b = le(8 + 256 * 16);
        b.putInt(cx).putInt(cz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int wx = cx * 16 + x, wz = cz * 16 + z;
                m.set(wx, chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z), wz);
                var biome = level.getBiome(m).value();
                b.putInt(net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER.getColor(biome, wx, wz) & 0xFFFFFF)
                        .putInt(net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER.getColor(biome, wx, wz) & 0xFFFFFF)
                        .putInt(net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER.getColor(biome, wx, wz) & 0xFFFFFF)
                        .putInt(net.minecraft.client.renderer.BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER.getColor(biome, wx, wz) & 0xFFFFFF);
            }
        return b.array();
    }

    // ---- volumes of real blocks ---------------------------------------------------------------------------------

    static String here(String name, int radius, int down, int up) {
        return here(name, radius, down, up, false);
    }

    static String here(String name, int radius, int down, int up, boolean light) {
        var p = Minecraft.getInstance().player;
        if (p == null) return "atlaslab: no player";
        BlockPos c = p.blockPosition();
        return volume(name, c.getX() - radius, c.getY() - down, c.getZ() - radius, c.getX() + radius, c.getY() + up, c.getZ() + radius, light);
    }

    static String volume(String name, int x0, int y0, int z0, int x1, int y1, int z1) {
        return volume(name, x0, y0, z0, x1, y1, z1, false);
    }

    /** The box as ALV1 (name.alv), or with light as ALV2 (name.alv2: light, model variants, column tints). */
    static String volume(String name, int x0, int y0, int z0, int x1, int y1, int z1, boolean light) {
        var level = Minecraft.getInstance().level;
        if (level == null) return "atlaslab: no level";
        if (!name.matches("[A-Za-z0-9_.-]+")) return "atlaslab: bad volume name";
        int ax = Math.min(x0, x1), ay = Math.min(y0, y1), az = Math.min(z0, z1);
        int sx = Math.abs(x1 - x0) + 1, sy = Math.abs(y1 - y0) + 1, sz = Math.abs(z1 - z0) + 1;
        if ((long) sx * sy * sz > 32L << 20) return "atlaslab: volume too big (" + sx + "x" + sy + "x" + sz + ")";
        int n = sx * sy * sz;
        ByteBuffer b = le(4 + 24 + (light ? 4 : 0) + n * 4 + (light ? 2 * n + sx * sz * 16 : 0));
        b.put((light ? "ALV2" : "ALV1").getBytes(StandardCharsets.US_ASCII)).putInt(ax).putInt(ay).putInt(az).putInt(sx).putInt(sy).putInt(sz);
        if (light) b.putInt(1 | 2 | 4);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int unloaded = 0;
        Map<Integer, Integer> seen = new HashMap<>();
        byte[] lt = light ? new byte[n] : null, var = light ? new byte[n] : null;
        int[] top = light ? new int[sx * sz] : null;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    m.set(ax + x, ay + y, az + z);
                    if (!level.hasChunkAt(m)) unloaded++;
                    BlockState st = level.getBlockState(m);
                    int id = Block.getId(st);
                    seen.merge(id, 1, Integer::sum);
                    b.putInt(id);
                    if (light) {
                        int i = x + sx * (z + sz * y);
                        lt[i] = (byte) (level.getBrightness(net.minecraft.world.level.LightLayer.SKY, m) << 4
                                | level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, m));
                        if (st.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) var[i] = (byte) variantAt(st, m);
                        if (!st.isAir()) top[x + sx * z] = y;
                    }
                }
        if (light) {
            b.put(lt).put(var);
            // each column's tints as the game blends them, at its top block in the box
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    m.set(ax + x, ay + top[x + sx * z], az + z);
                    b.putInt(net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, m) & 0xFFFFFF)
                            .putInt(net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(level, m) & 0xFFFFFF)
                            .putInt(net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, m) & 0xFFFFFF)
                            .putInt(net.minecraft.client.renderer.BiomeColors.getAverageDryFoliageColor(level, m) & 0xFFFFFF);
                }
        }
        Path out;
        try {
            out = dir().resolve(name + (light ? ".alv2" : ".alv"));
            Files.write(out, b.array());
            // the tints the near field uses at the box's centre (blended as the game blends them), for atlas_lab
            writeTints(name, new BlockPos(ax + sx / 2, ay + sy / 2, az + sz / 2), lighting());
        } catch (IOException e) {
            return "atlaslab: volume not written (" + e + ")";
        }
        String r = "atlaslab: volume " + name + " " + sx + "x" + sy + "x" + sz + " at " + ax + " " + ay + " " + az + ", " + seen.size()
                + " states" + (unloaded > 0 ? ", " + unloaded + " blocks in unloaded chunks" : "") + " -> " + out;
        NirnCraft.LOGGER.info(r);
        return r;
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * The camera's lightmap inputs now (the environment attributes LightmapRenderStateExtractor reads, the gamma
     * option, the dimension's face shading), as JSON fields for name.json. Client thread.
     */
    static String lighting() {
        Minecraft mc = Minecraft.getInstance();
        try {
            var probe = mc.gameRenderer.mainCamera().attributeProbe();
            org.joml.Vector3fc sky = probe.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_COLOR, 1f);
            org.joml.Vector3fc tint = probe.getValue(net.minecraft.world.attribute.EnvironmentAttributes.BLOCK_LIGHT_TINT, 1f);
            org.joml.Vector3fc amb = probe.getValue(net.minecraft.world.attribute.EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 1f);
            float factor = probe.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_FACTOR, 1f);
            var card = mc.level == null ? net.minecraft.world.level.CardinalLighting.DEFAULT : mc.level.cardinalLighting();
            return String.format(java.util.Locale.ROOT, ", \"skyFactor\": %.4f, \"skyLight\": [%.4f, %.4f, %.4f], \"blockTint\": [%.4f, %.4f, %.4f],"
                            + " \"ambient\": [%.4f, %.4f, %.4f], \"gamma\": %.3f, \"cardinal\": [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
                    factor, sky.x(), sky.y(), sky.z(), tint.x(), tint.y(), tint.z(), amb.x(), amb.y(), amb.z(), mc.options.gamma().get(),
                    card.down(), card.up(), card.north(), card.south(), card.west(), card.east());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** name.json beside an export: the grass, foliage and water tints the game blends at pos, and the lighting. */
    static void writeTints(String name, BlockPos c, String lighting) throws IOException {
        var cl = Minecraft.getInstance().level;
        if (cl == null) return;
        String tints = String.format(java.util.Locale.ROOT, "{\"grass\": \"%06X\", \"foliage\": \"%06X\", \"water\": \"%06X\", \"dryFoliage\": \"%06X\"%s}%n",
                net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(cl, c) & 0xFFFFFF,
                net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(cl, c) & 0xFFFFFF,
                net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(cl, c) & 0xFFFFFF,
                net.minecraft.client.renderer.BiomeColors.getAverageDryFoliageColor(cl, c) & 0xFFFFFF, lighting);
        Files.writeString(dir().resolve(name + ".json"), tints);
    }
}

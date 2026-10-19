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
 * then z, then y; beside it name.json: the grass, foliage and water tints the game blends at its centre.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class AtlasLabExport {
    private AtlasLabExport() {}

    static final int TILE = 4;

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

    /** The autotest verb: "blocks", "volume NAME X0 Y0 Z0 X1 Y1 Z1" or "here NAME RADIUS DOWN UP". Client thread. */
    public static String autotest(String args) {
        String[] a = args.trim().split("\\s+");
        return switch (a[0]) {
            case "blocks" -> blocks();
            case "volume" -> volume(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
                    Integer.parseInt(a[5]), Integer.parseInt(a[6]), Integer.parseInt(a[7]));
            case "here" -> here(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
            case "view" -> "atlaslab: view started (" + view(a[1], Integer.parseInt(a[2])).thenAccept(NirnCraft.LOGGER::info) + ")";
            default -> throw new IllegalArgumentException("atlaslab: blocks | volume NAME X0 Y0 Z0 X1 Y1 Z1 | here NAME R DOWN UP | view NAME DISTANCE");
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

    // ---- the real blocks in the camera's view, to any distance ------------------------------------------------------

    /**
     * {@code view NAME DISTANCE}: every chunk in the camera's view (its horizontal field of view plus a margin) out to
     * DISTANCE blocks, loaded or generated by the integrated server (the same generators: the real world there, far
     * past the render distance), its sections from 16 under its lowest ground to its highest top written sparsely to
     * NAME.als (little-endian): "ALS1", u32 count, then per section i32 sx sy sz (section coordinates), u16 palette
     * size, the palette's u32 state ids, and when it has more than one entry 4096 indices (x + 16 * (z + 16 * y)),
     * u8 up to 256 entries else u16. All-air sections are left out. The server does it a few chunks a tick; the future
     * completes with the summary once the file is written.
     */
    public static java.util.concurrent.CompletableFuture<String> view(String name, int distance) {
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
        List<byte[]> sections = new ArrayList<>();
        int[] at = {0};
        long start = System.currentTimeMillis();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                long until = System.nanoTime() + 40_000_000L;  // a share of the tick, then the next tick
                while (at[0] < chunks.size() && System.nanoTime() < until) {
                    long[] c = chunks.get(at[0]++);
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
                        sections.add(sectionBytes((int) c[0], sy, (int) c[1], sec));
                    }
                }
                if (at[0] < chunks.size()) {
                    server.execute(step[0]);
                    return;
                }
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        Path out = dir().resolve(name + ".als");
                        try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(out))) {
                            os.write(le(8).put("ALS1".getBytes(StandardCharsets.US_ASCII)).putInt(sections.size()).array());
                            for (byte[] b : sections) os.write(b);
                        }
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

    // ---- volumes of real blocks ---------------------------------------------------------------------------------

    static String here(String name, int radius, int down, int up) {
        var p = Minecraft.getInstance().player;
        if (p == null) return "atlaslab: no player";
        BlockPos c = p.blockPosition();
        return volume(name, c.getX() - radius, c.getY() - down, c.getZ() - radius, c.getX() + radius, c.getY() + up, c.getZ() + radius);
    }

    static String volume(String name, int x0, int y0, int z0, int x1, int y1, int z1) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return "atlaslab: no level";
        if (!name.matches("[A-Za-z0-9_.-]+")) return "atlaslab: bad volume name";
        int ax = Math.min(x0, x1), ay = Math.min(y0, y1), az = Math.min(z0, z1);
        int sx = Math.abs(x1 - x0) + 1, sy = Math.abs(y1 - y0) + 1, sz = Math.abs(z1 - z0) + 1;
        if ((long) sx * sy * sz > 32L << 20) return "atlaslab: volume too big (" + sx + "x" + sy + "x" + sz + ")";
        ByteBuffer b = le(4 + 24 + sx * sy * sz * 4);
        b.put("ALV1".getBytes(StandardCharsets.US_ASCII)).putInt(ax).putInt(ay).putInt(az).putInt(sx).putInt(sy).putInt(sz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int unloaded = 0;
        Map<Integer, Integer> seen = new HashMap<>();
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    m.set(ax + x, ay + y, az + z);
                    if (!level.hasChunkAt(m)) unloaded++;
                    int id = Block.getId(level.getBlockState(m));
                    seen.merge(id, 1, Integer::sum);
                    b.putInt(id);
                }
        Path out;
        try {
            out = dir().resolve(name + ".alv");
            Files.write(out, b.array());
            // the tints the near field uses at the box's centre (blended as the game blends them), for atlas_lab
            var cl = Minecraft.getInstance().level;
            BlockPos c = new BlockPos(ax + sx / 2, ay + sy / 2, az + sz / 2);
            String tints = String.format(java.util.Locale.ROOT, "{\"grass\": \"%06X\", \"foliage\": \"%06X\", \"water\": \"%06X\"}%n",
                    net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(cl, c) & 0xFFFFFF,
                    net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(cl, c) & 0xFFFFFF,
                    net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(cl, c) & 0xFFFFFF);
            Files.writeString(dir().resolve(name + ".json"), tints);
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
}

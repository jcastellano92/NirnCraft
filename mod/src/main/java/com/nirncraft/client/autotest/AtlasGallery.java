package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The vanilla gallery's game side (harness: tools/harness/atlas_gallery.py).
 *
 * <p>{@code gallery-index OUT.json}: every block state of the registry (vanilla and NirnCraft) grouped by the baked model
 * it draws with (states that share a model are one group), as JSON.
 *
 * <p>{@code gallery-run SCRIPT.json}: a script of steps run one tick at a time, so a whole batch costs seconds, not one
 * harness round trip per picture: {@code window} (resize), {@code goto} (the player), {@code place} (patches of a state,
 * set on the server without neighbour updates), {@code wait}, {@code mode} (vanilla's raster alone, or atlas unified) and
 * {@code shot} (an eye and a look-at point; crops around projected targets, written as PNGs under the script's out
 * folder). Cameras are exact (the player is the test spectator), crops come from the real projection.
 */
final class AtlasGallery {
    private AtlasGallery() {}

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "atlas-gallery-writer");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger WRITING = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------------- index
    static void index(Minecraft mc, String file) {
        var models = mc.getModelManager().getBlockStateModelSet();
        var colors = mc.getBlockColors();
        var out = new JsonArray();
        int states = 0, skipped = 0;
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            Map<String, JsonObject> groups = new LinkedHashMap<>();
            var coloring = colors.getColoringProperties(block);
            for (var state : block.getStateDefinition().getPossibleStates()) {
                states++;
                boolean fluid = !state.getFluidState().isEmpty();
                if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE && !fluid) {
                    skipped++;
                    continue;
                }
                var model = models.get(state);
                // the model by what it draws: every part's quads (positions, uvs, sprite, direction), not the object (one per state)
                var parts = new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                model.collectParts(net.minecraft.util.RandomSource.create(0L), parts);
                int shape = 17;
                for (var part : parts) {
                    shape = shape * 31 + part.getQuads(null).hashCode();
                    for (var dir : net.minecraft.core.Direction.values()) shape = shape * 31 + part.getQuads(dir).hashCode();
                }
                var sb = new StringBuilder().append(shape).append('|').append(parts.isEmpty() ? id : "").append('|').append(fluid ? state.getFluidState().getType() : "")
                        .append('|').append(state.getLightEmission());
                for (var p : coloring) sb.append('|').append(state.getValue(p));
                if (fluid && !state.getBlock().defaultBlockState().equals(state)) sb.append("|f").append(state.getFluidState().getAmount());
                var g = groups.get(sb.toString());
                if (g == null) {
                    g = new JsonObject();
                    g.addProperty("block", id);
                    g.addProperty("state", net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
                    g.addProperty("count", 0);
                    g.addProperty("full", state.isSolidRender());
                    g.addProperty("tinted", !colors.getTintSources(state).isEmpty());
                    g.addProperty("emits", state.getLightEmission());
                    g.addProperty("fluid", fluid);
                    g.addProperty("blockEntity", state.hasBlockEntity());
                    g.addProperty("render", state.getRenderShape().toString());
                    groups.put(sb.toString(), g);
                }
                g.addProperty("count", g.get("count").getAsInt() + 1);
            }
            int n = 0;
            for (var g : groups.values()) {
                g.addProperty("id", id.replace(':', '.') + "." + n++);
                out.add(g);
            }
        }
        var root = new JsonObject();
        root.addProperty("states", states);
        root.addProperty("invisibleSkipped", skipped);
        root.add("groups", out);
        try {
            Path p = Path.of(file.trim());
            Files.createDirectories(p.toAbsolutePath().getParent());
            Files.writeString(p, root.toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gallery index failed", e);
        }
        NirnCraft.LOGGER.info("Autotest live: gallery index: {} states in {} groups ({} invisible skipped)", states, out.size(), skipped);
    }

    // ---------------------------------------------------------------------------------------------------- script
    private static List<JsonObject> steps = new ArrayList<>();
    private static int idx, waitTicks, shots;
    private static boolean running, stepStarted, wantShot;
    private static Path outDir;
    private static String scriptName = "";
    private static long began;
    private static JsonObject current;
    private static CompletableFuture<Void> serverTask;
    private static double camYaw, camPitch;
    private static double[] camEye = new double[3];
    private static int savedW, savedH;
    private static boolean resized;

    static boolean active() { return running; }

    static String progress() { return running ? "gallery" + idx + "/" + steps.size() + "w" + waitTicks : ""; }

    static void cancel() {
        running = false;
        System.clearProperty("nirncraft.atlas.fixedExposure");
    }

    static void begin(Minecraft mc, String path) {
        try {
            var spec = JsonParser.parseString(Files.readString(Path.of(path.trim()))).getAsJsonObject();
            steps = new ArrayList<>();
            for (JsonElement e : spec.getAsJsonArray("steps")) steps.add(e.getAsJsonObject());
            outDir = Path.of(spec.get("out").getAsString());
            scriptName = spec.has("name") ? spec.get("name").getAsString() : "gallery";
            Files.createDirectories(outDir);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("gallery script unreadable: " + e);
        }
        System.setProperty("nirncraft.atlas.fixedExposure", "true");  // the eye's metering would change every state's look
        idx = waitTicks = shots = 0;
        stepStarted = wantShot = false;
        serverTask = null;
        began = System.currentTimeMillis();
        running = true;
        NirnCraft.LOGGER.info("Autotest live: gallery {} started ({} steps)", scriptName, steps.size());
    }

    private static double d(JsonObject o, String k) { return o.get(k).getAsDouble(); }

    private static double[] vec(JsonObject o, String k) {
        var a = o.getAsJsonArray(k);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    static void tick(Minecraft mc) {
        if (!running) return;
        if (serverTask != null) {
            if (!serverTask.isDone()) return;
            serverTask.join();
            serverTask = null;
        }
        if (waitTicks > 0) {
            waitTicks--;
            keepCamera(mc);
            return;
        }
        if (wantShot) {  // the picture is taken in the next frame's end (frameEnd)
            keepCamera(mc);
            return;
        }
        if (idx >= steps.size()) {
            finish(mc);
            return;
        }
        current = steps.get(idx);
        String op = current.get("op").getAsString();
        switch (op) {
            case "wait" -> {
                waitTicks = current.get("ticks").getAsInt();
                idx++;
            }
            case "window" -> {
                savedW = mc.getWindow().getWidth();
                savedH = mc.getWindow().getHeight();
                org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), current.get("w").getAsInt(), current.get("h").getAsInt());
                resized = true;
                waitTicks = 20;
                idx++;
            }
            case "goto" -> {
                double[] p = vec(current, "at");
                camEye = new double[]{p[0], p[1] + 1.62, p[2]};
                camYaw = 0;
                camPitch = 0;
                keepCamera(mc);
                waitTicks = current.has("wait") ? current.get("wait").getAsInt() : 40;
                idx++;
            }
            case "settle" -> {  // atlas's own state quiet (AtlasRoute.settle): the gallery waits while it runs
                AtlasRoute.beginSettle(mc, (current.has("max") ? current.get("max").getAsInt() : 600) + " "
                        + (current.has("min") ? current.get("min").getAsInt() : 40));
                idx++;
            }
            case "mode" -> {
                boolean atlas = current.get("name").getAsString().equals("atlas");
                // vanilla's raster alone: atlas neither owns the terrain nor draws a far field; atlas: the unified field
                System.setProperty("nirncraft.atlas.unified", String.valueOf(atlas));
                com.nirncraft.atlas.AtlasNearPass.active = atlas;
                com.nirncraft.atlas.AtlasClient.suspended = !atlas;
                waitTicks = current.has("wait") ? current.get("wait").getAsInt() : 30;
                idx++;
            }
            case "place" -> {
                var srv = mc.getSingleplayerServer();
                if (srv == null) throw new IllegalStateException("gallery placement needs the integrated server");
                var items = current.getAsJsonArray("items");
                serverTask = new CompletableFuture<>();
                var done = serverTask;
                srv.execute(() -> {
                    try {
                        place(srv, items);
                        done.complete(null);
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                    }
                });
                waitTicks = current.has("wait") ? current.get("wait").getAsInt() : 2;
                idx++;
            }
            case "shot" -> {
                double[] eye = vec(current, "eye"), look = vec(current, "look");
                camEye = eye;
                double dx = look[0] - eye[0], dy = look[1] - eye[1], dz = look[2] - eye[2];
                camYaw = Math.toDegrees(Math.atan2(-dx, dz));
                camPitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                keepCamera(mc);
                waitTicks = current.has("settle") ? current.get("settle").getAsInt() : 6;
                wantShot = true;
                idx++;
            }
            default -> throw new IllegalArgumentException("gallery: unknown op " + op);
        }
    }

    private static void keepCamera(Minecraft mc) {
        var p = mc.player;
        if (p == null) return;
        p.setPos(camEye[0], camEye[1] - 1.62, camEye[2]);
        p.setYRot((float) camYaw);
        p.setXRot((float) camPitch);
        p.setYHeadRot((float) camYaw);
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    private static void place(net.minecraft.server.MinecraftServer srv, JsonArray items) throws Exception {
        var level = srv.overworld();
        var blocks = srv.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        // 2: send to clients; 16: no neighbour shape updates; 32: no drops (an unsupported plant stays as placed)
        int flags = 2 | 16 | 32;
        for (JsonElement e : items) {
            JsonObject it = e.getAsJsonObject();
            var state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(blocks, it.get("state").getAsString(), false).blockState();
            int x = it.get("x").getAsInt(), y = it.get("y").getAsInt(), z = it.get("z").getAsInt();
            int w = it.has("w") ? it.get("w").getAsInt() : 3, dd = it.has("d") ? it.get("d").getAsInt() : 3;
            for (int i = 0; i < w; i++)
                for (int j = 0; j < dd; j++) {
                    pos.set(x - w / 2 + i, y, z - dd / 2 + j);
                    level.setBlock(pos, state, flags);
                }
        }
    }

    /** Called at the end of every rendered frame: takes the picture a shot step asked for. */
    static void frameEnd(Minecraft mc) {
        if (!running || !wantShot || waitTicks > 0 || serverTask != null) return;
        wantShot = false;
        JsonObject shot = steps.get(idx - 1);
        String name = shot.get("name").getAsString();
        double[] eye = camEye.clone();
        double yaw = camYaw, pitch = camPitch;
        int fw = mc.gameRenderer.mainRenderTarget().width, fh = mc.gameRenderer.mainRenderTarget().height;
        double fov = Math.toRadians(mc.options.fov().get());
        List<int[]> rects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<int[][]> polys = new ArrayList<>();
        if (shot.has("targets")) {
            for (JsonElement e : shot.getAsJsonArray("targets")) {
                JsonObject t = e.getAsJsonObject();
                if (t.has("quad")) {
                    var q = t.getAsJsonArray("quad");
                    int[][] pts = new int[4][];
                    int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
                    boolean ok = true;
                    for (int i = 0; i < 4 && ok; i++) {
                        var c = q.get(i).getAsJsonArray();
                        double[] p = project(eye, yaw, pitch, new double[]{c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()}, fov, fw, fh);
                        if (p == null) {
                            ok = false;
                            break;
                        }
                        pts[i] = new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])};
                        x0 = Math.min(x0, pts[i][0]);
                        y0 = Math.min(y0, pts[i][1]);
                        x1 = Math.max(x1, pts[i][0]);
                        y1 = Math.max(y1, pts[i][1]);
                    }
                    if (ok) {
                        x0 -= 1;
                        y0 -= 1;
                        for (var pt : pts) {
                            pt[0] -= x0;
                            pt[1] -= y0;
                        }
                        rects.add(new int[]{x0, y0, Math.max(2, x1 - x0 + 2), Math.max(2, y1 - y0 + 2)});
                        names.add(t.get("name").getAsString());
                        polys.add(pts);
                    }
                    continue;
                }
                int[] r = project(eye, yaw, pitch, vec(t, "at"), t.get("half").getAsDouble(), fov, fw, fh, t.has("min") ? t.get("min").getAsInt() : 8);
                if (r != null) {
                    rects.add(r);
                    names.add(t.get("name").getAsString());
                    polys.add(null);
                }
            }
        } else {
            int side = shot.has("side") ? shot.get("side").getAsInt() : Math.min(fw, fh) / 2;
            side = Math.min(side, Math.min(fw, fh));
            rects.add(new int[]{(fw - side) / 2, (fh - side) / 2, side, side});
            names.add("");
            polys.add(null);
        }
        shots++;
        WRITING.incrementAndGet();
        var rs = rects;
        var ns = names;
        var ps = polys;
        try {
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> WRITER.execute(() -> {
                try (image) {
                    for (int i = 0; i < rs.size(); i++) {
                        int[] r = rs.get(i);
                        int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]);
                        int x1 = Math.min(image.getWidth(), r[0] + r[2]), y1 = Math.min(image.getHeight(), r[1] + r[3]);
                        if (x1 <= x0 || y1 <= y0) continue;
                        BufferedImage crop = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
                        for (int y = y0; y < y1; y++)
                            for (int x = x0; x < x1; x++) crop.setRGB(x - x0, y - y0, image.getPixel(x, y) & 0xFFFFFF);
                        String file = name + (ns.get(i).isEmpty() ? "" : "__" + ns.get(i)) + ".png";
                        javax.imageio.ImageIO.write(crop, "png", outDir.resolve(file).toFile());
                    }
                    var side = new JsonObject();
                    side.addProperty("w", image.getWidth());
                    side.addProperty("h", image.getHeight());
                    var tg = new JsonObject();
                    for (int i = 0; i < rs.size(); i++) {
                        var t = new JsonObject();
                        var ra = new JsonArray();
                        for (int v : rs.get(i)) ra.add(v);
                        t.add("rect", ra);
                        if (ps.get(i) != null) {
                            var pa = new JsonArray();
                            for (int[] pt : ps.get(i)) {
                                var xy = new JsonArray();
                                xy.add(pt[0]);
                                xy.add(pt[1]);
                                pa.add(xy);
                            }
                            t.add("poly", pa);
                        }
                        tg.add(ns.get(i).isEmpty() ? "crop" : ns.get(i), t);
                    }
                    side.add("targets", tg);
                    Files.writeString(outDir.resolve(name + ".json"), side.toString());
                } catch (Exception ex) {
                    NirnCraft.LOGGER.error("gallery shot {} failed", name, ex);
                } finally {
                    WRITING.decrementAndGet();
                }
            }));
        } catch (RuntimeException ex) {
            WRITING.decrementAndGet();
            throw ex;
        }
    }

    /** The square (x, y, w, h) around a world point as the camera projects it, `half` blocks each side. */
    static int[] project(double[] eye, double yawDeg, double pitchDeg, double[] at, double half, double fovY, int w, int h, int minPx) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        // Minecraft: yaw 0 looks +z, positive pitch looks down
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);  // to the camera's right
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double dx = at[0] - eye[0], dy = at[1] - eye[1], dz = at[2] - eye[2];
        double depth = dx * fx + dy * fy + dz * fz;
        if (depth < 0.2) return null;
        double th = Math.tan(fovY / 2), aspect = (double) w / h;
        double sx = (dx * rx + dy * ry + dz * rz) / depth / (th * aspect);
        double sy = (dx * ux + dy * uy + dz * uz) / depth / th;
        double cx = (sx + 1) / 2 * w, cy = (1 - sy) / 2 * h;
        double half_px = Math.max(minPx, half / depth / th * h / 2);
        int side = (int) Math.round(half_px * 2);
        return new int[]{(int) Math.round(cx - half_px), (int) Math.round(cy - half_px), side, side};
    }

    /** A world point's pixel (x, y) as the camera projects it, or null behind the camera. */
    static double[] project(double[] eye, double yawDeg, double pitchDeg, double[] at, double fovY, int w, int h) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double dx = at[0] - eye[0], dy = at[1] - eye[1], dz = at[2] - eye[2];
        double depth = dx * fx + dy * fy + dz * fz;
        if (depth < 0.2) return null;
        double th = Math.tan(fovY / 2), aspect = (double) w / h;
        double sx = (dx * rx + dy * ry + dz * rz) / depth / (th * aspect);
        double sy = (dx * ux + dy * uy + dz * uz) / depth / th;
        return new double[]{(sx + 1) / 2 * w, (1 - sy) / 2 * h};
    }

    private static void finish(Minecraft mc) {
        if (WRITING.get() > 0) return;  // (the last pictures are still being written)
        running = false;
        System.clearProperty("nirncraft.atlas.fixedExposure");
        var o = new JsonObject();
        o.addProperty("name", scriptName);
        o.addProperty("shots", shots);
        o.addProperty("ms", System.currentTimeMillis() - began);
        o.addProperty("fov", mc.options.fov().get());
        o.addProperty("w", mc.getWindow().getWidth());
        o.addProperty("h", mc.getWindow().getHeight());
        try {
            Files.writeString(outDir.resolve("done.json"), o.toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gallery receipt failed", e);
        }
        NirnCraft.LOGGER.info("Autotest live: gallery {} done: {} shots in {} ms", scriptName, shots, System.currentTimeMillis() - began);
    }
}

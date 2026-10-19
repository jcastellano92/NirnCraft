package com.nirncraft.client.pinned;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft's own rendering as ground truth at any distance (docs/ATLAS-LOD.md, "Pinned ground truth"; the harness
 * suite tools/harness/pinned.py). Minecraft unloads chunks past its render distance and clips at its far plane; a pin
 * keeps one box of the client level (blocks, light, blended tints, block entities, and with {@code entities} the
 * entities in it) and draws it with Minecraft's own code wherever the harness puts it: the section compiler meshes it
 * (PinnedMesh), the terrain pipelines draw it with the block atlas and the lightmap, the entity and block entity
 * renderers draw what they extracted. Beyond the far plane the copy is drawn smaller and nearer (PinTransform: the
 * same picture), and every capture's receipt carries the equivalent eye for the offline reference.
 *
 * <p>Verbs (autotest; the client command is {@code /nirncraft-pin take|place|view|fly|shimmer|export|hide|clear|list
 * ...} with the same arguments, "take" for "pin"; coordinates take '~' like commands):
 * <pre>
 * pin NAME X0 Y0 Z0 X1 Y1 Z1 [entities] [fresh]  snapshot the box (~: relative to the player's block); an existing pin
 *                                                 of that name and box is kept unless fresh
 * pin-place NAME X Y Z [SCALE]       draw the box centre at X Y Z (~: relative to the eye), scaled
 * pin-view NAME DISTANCE [YAW PITCH] [cull]
 *                                    the subject DISTANCE blocks from the eye along YAW / PITCH (default: where the
 *                                    camera looks), scaled down past 32 blocks; cull: only what Minecraft itself
 *                                    would draw there (entity and block entity view distances)
 * pin-fly NAME D0 D1 FRAMES [YAW PITCH] [cull]
 *                                    pin-view at D0, then one geometric step towards D1 after every capture
 * pin-shimmer TICKS                  the same frame grabbed twice TICKS apart: how much Minecraft's picture moves by
 *                                    itself (animated textures, flicker); kept in the receipt
 * pin-export NAME                    the pin's box plus its light margin as an atlaslab ALV2 volume (offline input)
 * pin-hide NAME|all, pin-clear NAME|all, pin-list
 * </pre>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class PinnedTruth {
    private PinnedTruth() {}

    /** The copy's anchor is never drawn farther than this (blocks): everything beyond is the same picture, scaled. */
    public static final double MAX_DRAWN = 32.0;
    private static final ChunkSectionLayer[] OPAQUE = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};
    private static final ChunkSectionLayer[] TRANSLUCENT = {ChunkSectionLayer.TRANSLUCENT};

    static final class Pin {
        final PinSnapshot snap;
        final CompletableFuture<Integer> ready = new CompletableFuture<>();
        @Nullable PinTransform transform;
        @Nullable PinnedMesh mesh;
        boolean dirty, cull, failed;
        double requested = Double.NaN, yaw, pitch;
        double[] flight;
        double[][] flightEyes;
        int flightFrame;
        @Nullable JsonObject shimmer;

        Pin(PinSnapshot snap) {
            this.snap = snap;
        }

        double ox() { return snap.volume.box.cx(); }
        double oy() { return snap.volume.box.cy(); }
        double oz() { return snap.volume.box.cz(); }
    }

    private static final Map<String, Pin> PINS = new LinkedHashMap<>();
    /** The pin the last placement verb placed: its view goes into every capture receipt. */
    private static @Nullable Pin viewed;
    private static @Nullable GpuSampler sampler;
    private static int samplerAnisotropy = -1;
    private static @Nullable Shimmer shimmer;

    // ---------------------------------------------------------------------------------------------- verbs

    /** The autotest verb (the whole action line); its future completes with 1 when the verb is done. Client thread. */
    public static CompletableFuture<Integer> autotest(String action) {
        try {
            return run(action.trim());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletableFuture<Integer> run(String line) {
        String[] a = line.split("\\s+");
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("pin: no level");
        switch (a[0]) {
            case "pin" -> {
                if (a.length < 8) throw new IllegalArgumentException("pin NAME X0 Y0 Z0 X1 Y1 Z1 [entities] [fresh]");
                String name = name(a[1]);
                var feet = mc.player.blockPosition();
                PinBox box = PinBox.parse(java.util.Arrays.copyOfRange(a, 2, 8), feet.getX(), feet.getY(), feet.getZ());
                boolean entities = has(a, 8, "entities"), fresh = has(a, 8, "fresh");
                Pin old = PINS.get(name);
                if (old != null && !fresh && old.snap.volume.box.equals(box) && old.snap.withEntities == entities
                        && old.snap.source == mc.level && !old.failed) {
                    NirnCraft.LOGGER.info("pin {}: kept (box {})", name, box);
                    return old.ready;
                }
                clear(name);
                long t0 = System.nanoTime();
                PinSnapshot snap = PinSnapshot.take(name, box, mc.level, entities);
                Pin p = new Pin(snap);
                PINS.put(name, p);
                // meshed once now, while the box's chunks are loaded: it fails here rather than later, and mods' own
                // tint resolvers are answered by the level while it can (PinSnapshot.otherTint)
                Vec3 eye = mc.player.getEyePosition();
                try {
                    p.mesh = PinnedMesh.build(snap, mc.level, new double[] {eye.x, eye.y, eye.z}, Mth.floor(eye.x),
                            Mth.floor(eye.y), Mth.floor(eye.z));
                } catch (RuntimeException e) {
                    clear(name);
                    throw e;
                }
                NirnCraft.LOGGER.info("pin {}: {} ({}x{}x{}), {} blocks, {} quads, {} block entities, {} entities, {} KB, {} ms",
                        name, box, box.sx(), box.sy(), box.sz(), snap.volume.countNot(0), p.mesh.quads,
                        snap.pendingBlockEntityCount(), snap.pendingEntityCount(), snap.volume.bytes() / 1024,
                        (System.nanoTime() - t0) / 1_000_000);
                return p.ready;  // complete once the next frame extracted its entities and block entities
            }
            case "pin-place" -> {
                Pin p = pin(a, 5, "pin-place NAME X Y Z [SCALE]");
                Vec3 eye = mc.player.getEyePosition();
                double x = coord(a[2], eye.x), y = coord(a[3], eye.y), z = coord(a[4], eye.z);
                double s = a.length > 5 ? Double.parseDouble(a[5]) : 1.0;
                p.flight = null;
                p.requested = Double.NaN;
                place(p, new PinTransform(p.ox(), p.oy(), p.oz(), x, y, z, s), false);
                return CompletableFuture.completedFuture(1);
            }
            case "pin-view" -> {
                Pin p = pin(a, 3, "pin-view NAME DISTANCE [YAW PITCH] [cull]");
                double d = Double.parseDouble(a[2]);
                double[] yp = yawPitch(a, 3, mc);
                p.flight = null;
                view(p, d, yp[0], yp[1], has(a, 3, "cull"));
                return CompletableFuture.completedFuture(1);
            }
            case "pin-fly" -> {
                Pin p = pin(a, 5, "pin-fly NAME D0 D1 FRAMES [YAW PITCH] [cull]");
                double[] ds = PinTransform.flyIn(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Integer.parseInt(a[4]));
                double[] yp = yawPitch(a, 5, mc);
                view(p, ds[0], yp[0], yp[1], has(a, 5, "cull"));
                p.flight = ds;
                p.flightFrame = 0;
                // every frame's equivalent eye, from the camera as it is now (it stays: only the copy moves)
                Vec3 c = mc.player.getEyePosition();
                p.flightEyes = new double[ds.length][];
                for (int i = 0; i < ds.length; i++)
                    p.flightEyes[i] = transformFor(p, ds[i], yp[0], yp[1], mc).equivalentEye(c.x, c.y, c.z);
                return CompletableFuture.completedFuture(1);
            }
            case "pin-shimmer" -> {
                if (viewed == null || viewed.transform == null) throw new IllegalStateException("pin-shimmer: no pinned view");
                int ticks = a.length > 1 ? Math.max(1, Integer.parseInt(a[1])) : 6;
                shimmer = new Shimmer(viewed, ticks);
                return shimmer.done;
            }
            case "pin-export" -> {
                Pin p = pin(a, 2, "pin-export NAME");
                PinBox lb = p.snap.volume.lightBox;
                // the box with its light margin; the offline reference cuts the box out of it (atlas_truth box=)
                String r = com.nirncraft.atlas.AtlasLabExport.autotest("volume " + p.snap.name + " " + lb + " light");
                NirnCraft.LOGGER.info(r);
                return CompletableFuture.completedFuture(r.contains(" -> ") ? 1 : 0);
            }
            case "pin-hide" -> {
                for (Pin p : select(a)) {
                    p.transform = null;
                    p.flight = null;
                    if (viewed == p) viewed = null;
                }
                return CompletableFuture.completedFuture(1);
            }
            case "pin-clear" -> {
                if (a.length < 2 || a[1].equals("all")) clearAll();
                else clear(a[1]);
                return CompletableFuture.completedFuture(1);
            }
            case "pin-list" -> {
                NirnCraft.LOGGER.info(list());
                return CompletableFuture.completedFuture(1);
            }
            default -> throw new IllegalArgumentException("pin: unknown verb " + a[0]);
        }
    }

    private static PinTransform transformFor(Pin p, double distance, double yaw, double pitch, Minecraft mc) {
        Vec3 c = mc.player.getEyePosition();
        // the projection's far plane (Camera.update: the render distance x 4, at least the cloud range), with a margin
        double depthFar = Math.max(mc.options.getEffectiveRenderDistance() * 16.0 * 4.0, mc.options.cloudRange().get() * 16.0);
        return PinTransform.view(p.ox(), p.oy(), p.oz(), c.x, c.y, c.z, distance, yaw, pitch, p.snap.volume.box.radius(),
                MAX_DRAWN, depthFar * 0.5);
    }

    private static void view(Pin p, double distance, double yaw, double pitch, boolean cull) {
        Minecraft mc = Minecraft.getInstance();
        p.requested = distance;
        p.yaw = yaw;
        p.pitch = pitch;
        p.cull = cull;
        place(p, transformFor(p, distance, yaw, pitch, mc), cull);
        writeViewLighting(p);
    }

    private static void place(Pin p, PinTransform t, boolean cull) {
        p.transform = t;
        p.cull = cull;
        p.dirty = true;
        viewed = p;
        rebuild(p);  // now: the next frame shows the copy where it belongs, translucent quads sorted for it
    }

    /** After every capture (ClientAutotest): a flying pin takes its next step. */
    public static void captured() {
        Pin p = viewed;
        if (p == null || p.flight == null || p.transform == null) return;
        if (p.flightFrame + 1 >= p.flight.length) return;
        p.flightFrame++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        p.transform = transformFor(p, p.flight[p.flightFrame], p.yaw, p.pitch, mc);
        p.requested = p.flight[p.flightFrame];
        p.dirty = true;  // remeshed on the next tick (the waits between frames cover it)
    }

    /**
     * name.view.json beside the pin's export: its tints (at the box centre, from the snapshot) and the lighting the
     * camera sees now. The lightmap's inputs are environment attributes probed at the camera, so on the sky stage they
     * are the stage's; the offline reference of a pinned capture reads this file (json=) rather than the export's own.
     */
    private static void writeViewLighting(Pin p) {
        try {
            PinBox b = p.snap.volume.box;
            int x = (b.x0() + b.x1()) / 2, y = (b.y0() + b.y1()) / 2, z = (b.z0() + b.z1()) / 2;
            PinVolume v = p.snap.volume;
            String json = String.format(Locale.ROOT, "{\"grass\": \"%06X\", \"foliage\": \"%06X\", \"water\": \"%06X\", \"dryFoliage\": \"%06X\"%s}%n",
                    v.tint(PinVolume.TINT_GRASS, x, y, z) & 0xFFFFFF, v.tint(PinVolume.TINT_FOLIAGE, x, y, z) & 0xFFFFFF,
                    v.tint(PinVolume.TINT_WATER, x, y, z) & 0xFFFFFF, v.tint(PinVolume.TINT_DRY_FOLIAGE, x, y, z) & 0xFFFFFF,
                    com.nirncraft.atlas.AtlasLabExport.lightingJson());
            java.nio.file.Path d = Minecraft.getInstance().gameDirectory.toPath().resolve("nirncraft-atlaslab");
            java.nio.file.Files.createDirectories(d);
            java.nio.file.Files.writeString(d.resolve(p.snap.name + ".view.json"), json);
        } catch (java.io.IOException | RuntimeException e) {
            NirnCraft.LOGGER.warn("pin {}: view lighting not written: {}", p.snap.name, e.toString());
        }
    }

    private static void rebuild(Pin p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.transform == null || mc.level == null || mc.player == null || p.failed) return;
        Vec3 c = mc.player.getEyePosition();
        try {
            PinnedMesh old = p.mesh;
            p.mesh = PinnedMesh.build(p.snap, mc.level, p.transform.equivalentEye(c.x, c.y, c.z), Mth.floor(c.x),
                    Mth.floor(c.y), Mth.floor(c.z));
            if (old != null) old.close();
            p.dirty = false;
        } catch (RuntimeException e) {
            p.failed = true;
            NirnCraft.LOGGER.error("pin {}: meshing failed; the pin is not drawn", p.snap.name, e);
        }
    }

    // ---------------------------------------------------------------------------------------------- receipt

    /**
     * The pinned view for a capture receipt (VisualCamera.captureReceipt), or null: the subject, its box, where the
     * copy is drawn and how big, the true distance and the equivalent eye (what the offline reference renders from,
     * with the receipt's own yaw, pitch and fov), what Minecraft itself would draw there (entities and block entities
     * by their own view distances), the shimmer measured for this view, and a fly-in's frames.
     */
    public static @Nullable JsonObject receipt() {
        Pin p = viewed;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || p.transform == null || mc.player == null) return null;
        Vec3 c = mc.player.getEyePosition();
        PinTransform t = p.transform;
        double[] e = t.equivalentEye(c.x, c.y, c.z);
        JsonObject r = new JsonObject();
        r.addProperty("name", p.snap.name);
        PinBox b = p.snap.volume.box;
        r.addProperty("box", b.x0() + "," + b.y0() + "," + b.z0() + "," + b.x1() + "," + b.y1() + "," + b.z1());
        r.add("anchor", vec(t.ox, t.oy, t.oz));
        r.add("drawnAt", vec(t.ax, t.ay, t.az));
        r.addProperty("scale", t.scale);
        r.add("eye", vec(e[0], e[1], e[2]));
        double dist = t.equivalentDistance(c.x, c.y, c.z);
        r.addProperty("distance", dist);
        if (!Double.isNaN(p.requested)) r.addProperty("requested", p.requested);
        r.addProperty("drawnDistance", dist * t.scale);
        r.addProperty("cull", p.cull);
        r.addProperty("quads", p.mesh == null ? 0 : p.mesh.quads);
        r.addProperty("pinnedDayTime", p.snap.dayTime);
        int drawsE = 0, drawsB = 0;
        JsonArray ents = new JsonArray();
        for (var pe : p.snap.entities) {
            double d = dist(e, pe.x(), pe.y(), pe.z());
            boolean v = pe.vanillaDraws(d);
            if (v) drawsE++;
            JsonObject o = new JsonObject();
            o.addProperty("type", pe.type());
            o.addProperty("distance", Math.round(d * 10) / 10.0);
            o.addProperty("vanillaDraws", v);
            ents.add(o);
        }
        for (var pb : p.snap.blockEntities) {
            if (pb.vanillaDraws(dist(e, pb.pos().getX() + 0.5, pb.pos().getY() + 0.5, pb.pos().getZ() + 0.5))) drawsB++;
        }
        r.add("entities", ents);
        r.addProperty("entitiesVanillaDraws", drawsE);
        r.addProperty("blockEntities", p.snap.blockEntities.size());
        r.addProperty("blockEntitiesVanillaDraws", drawsB);
        if (p.shimmer != null) r.add("shimmer", p.shimmer);
        if (p.flight != null && p.flightEyes != null) {
            r.addProperty("flightFrame", p.flightFrame);
            JsonArray fl = new JsonArray();
            for (int i = 0; i < p.flight.length; i++) {
                JsonObject o = new JsonObject();
                o.addProperty("distance", p.flight[i]);
                o.add("eye", vec(p.flightEyes[i][0], p.flightEyes[i][1], p.flightEyes[i][2]));
                fl.add(o);
            }
            r.add("flight", fl);
        }
        return r;
    }

    private static double dist(double[] e, double x, double y, double z) {
        double dx = x - e[0], dy = y - e[1], dz = z - e[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    // ---------------------------------------------------------------------------------------------- events

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nirncraft-pin")
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                    // /nirncraft-pin take NAME X0 Y0 Z0 X1 Y1 Z1 [entities] | view NAME D [YAW PITCH] | place ... | list ...
                    String args = StringArgumentType.getString(ctx, "args").trim();
                    String verb = args.startsWith("take ") ? "pin " + args.substring(5) : args.startsWith("pin") ? args : "pin-" + args;
                    String msg;
                    try {
                        var f = autotest(verb);
                        msg = f.isCompletedExceptionally() ? "pin: " + f.exceptionNow().getMessage()
                                : "pin: " + verb.split("\\s+")[0] + " done" + (f.isDone() ? "" : " (entities extract on the next frame)")
                                + "\n" + list();
                    } catch (RuntimeException e) {
                        msg = "pin: " + e.getMessage();
                    }
                    String m = msg;
                    ctx.getSource().sendSuccess(() -> Component.literal(m), false);
                    return 1;
                })));
    }

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        if (PINS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        for (Pin p : PINS.values()) {
            if (!p.snap.pending()) continue;
            try {
                p.snap.extract(mc.levelRenderer.entityRenderDispatcher(), mc.levelRenderer.blockEntityRenderDispatcher(),
                        event.getCamera().position(), event.getDeltaTracker().getGameTimeDeltaPartialTick(false));
                p.ready.complete(1);
            } catch (RuntimeException e) {
                p.snap.extracted = true;
                p.ready.completeExceptionally(e);
            }
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (PINS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 c = mc.player.getEyePosition();
        Object models = mc.getModelManager().getBlockStateModelSet();
        sampler(mc);  // made (or remade for a new anisotropy) here, never in the middle of a render pass
        for (Pin p : PINS.values()) {
            if (p.snap.source != null && p.snap.source != mc.level) p.snap.source = null;  // that level is gone
            if (p.transform == null || p.failed) continue;
            PinnedMesh m = p.mesh;
            // remeshed when placed again, after a resource reload (new atlas, new models) or when the camera has
            // travelled far from the point its section uniforms are relative to (float precision)
            if (p.dirty || m == null || m.models != models || Math.abs(m.kx - c.x) > 256 || Math.abs(m.ky - c.y) > 256
                    || Math.abs(m.kz - c.z) > 256)
                rebuild(p);
        }
        if (shimmer != null) shimmer.tick(mc);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
    }

    @SubscribeEvent
    public static void afterOpaque(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        draw(event, event.getRenderPass(), OPAQUE, null);
    }

    @SubscribeEvent
    public static void afterTranslucent(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        draw(event, event.getRenderPass(), TRANSLUCENT, null);  // classic transparency (improved transparency off)
    }

    @SubscribeEvent
    public static void oitTranslucent(RenderLevelStageEvent.OitTranslucent event) {
        // improved transparency: the translucent layer through the same OIT stages vanilla's translucent terrain takes
        draw(event, event.getRenderPass(), TRANSLUCENT, RenderPipelines.OIT_TERRAIN.getPipeline(event.getOitStage()));
    }

    private static void draw(RenderLevelStageEvent event, RenderPass pass, ChunkSectionLayer[] layers, @Nullable RenderPipeline override) {
        if (PINS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 c = event.getLevelRenderState().cameraRenderState.pos;
        var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int aw = atlas.getWidth(0), ah = atlas.getHeight(0);
        for (Pin p : PINS.values()) {
            PinnedMesh m = p.mesh;
            if (p.transform == null || m == null || p.failed) continue;
            boolean any = false;
            for (ChunkSectionLayer l : layers) any |= m.has(l);
            if (!any) continue;
            try {
                PinBox b = p.snap.volume.box;
                Matrix4f mv = p.transform.terrainModelView(event.getModelViewMatrix(), c.x, c.y, c.z, m.kx, m.ky, m.kz,
                        b.x0(), b.y0(), b.z0());
                var terrain = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTerrainTransform(mv, aw, ah);
                m.draw(pass, layers, override, terrain, atlas, sampler(mc), mc.gameRenderer.lightmap());
            } catch (RuntimeException e) {
                p.failed = true;
                NirnCraft.LOGGER.error("pin {}: drawing failed; the pin is not drawn again", p.snap.name, e);
            }
        }
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        if (PINS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var cam = event.getLevelRenderState().cameraRenderState;
        Vec3 c = cam.pos;
        PoseStack ps = event.getPoseStack();
        var out = event.getSubmitNodeCollector();
        var erd = mc.levelRenderer.entityRenderDispatcher();
        var berd = mc.levelRenderer.blockEntityRenderDispatcher();
        for (Pin p : PINS.values()) {
            PinTransform t = p.transform;
            if (t == null || p.failed || !p.snap.extracted) continue;
            if (p.snap.entities.isEmpty() && p.snap.blockEntities.isEmpty()) continue;
            double[] e = t.equivalentEye(c.x, c.y, c.z);
            float s = (float) t.scale;
            try {
                for (var pe : p.snap.entities) {
                    if (p.cull && !pe.vanillaDraws(dist(e, pe.x(), pe.y(), pe.z()))) continue;
                    ps.pushPose();
                    ps.translate(t.ax - c.x, t.ay - c.y, t.az - c.z);
                    ps.scale(s, s, s);
                    erd.submit(pe.state(), cam, pe.x() - t.ox, pe.y() - t.oy, pe.z() - t.oz, ps, out);
                    ps.popPose();
                }
                for (var pb : p.snap.blockEntities) {
                    var bp = pb.pos();
                    if (p.cull && !pb.vanillaDraws(dist(e, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5))) continue;
                    ps.pushPose();
                    ps.translate(t.ax - c.x, t.ay - c.y, t.az - c.z);
                    ps.scale(s, s, s);
                    ps.translate(bp.getX() - t.ox, bp.getY() - t.oy, bp.getZ() - t.oz);
                    berd.submit(pb.state(), ps, out, cam);
                    ps.popPose();
                }
            } catch (RuntimeException ex) {
                p.failed = true;
                NirnCraft.LOGGER.error("pin {}: entities failed; the pin is not drawn again", p.snap.name, ex);
            }
        }
    }

    /** The chunk layer sampler as LevelRenderer makes it: clamped, linear with mipmaps, the anisotropy option. */
    private static GpuSampler sampler(Minecraft mc) {
        int aniso = mc.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC ? mc.options.maxAnisotropyValue() : 1;
        if (sampler == null || aniso != samplerAnisotropy) {
            if (sampler != null) sampler.close();
            sampler = com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, aniso, OptionalDouble.empty());
            samplerAnisotropy = aniso;
        }
        return sampler;
    }

    // ---------------------------------------------------------------------------------------------- shimmer

    /**
     * The same view grabbed twice, some ticks apart, nothing moving: what changes is Minecraft's own picture changing
     * by itself (animated textures: water, lava, fire, portals; the lightmap's flicker). Mean change (0..255 on the
     * largest channel) and the share of pixels that changed by more than 8.
     */
    private static final class Shimmer {
        final Pin pin;
        final int ticks;
        final CompletableFuture<Integer> done = new CompletableFuture<>();
        int waited;
        @Nullable NativeImage first;
        boolean grabbing;

        Shimmer(Pin pin, int ticks) {
            this.pin = pin;
            this.ticks = ticks;
        }

        void tick(Minecraft mc) {
            if (done.isDone() || grabbing) return;
            if (first != null && ++waited < ticks) return;
            grabbing = true;
            net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
                grabbing = false;
                if (first == null) {
                    first = img;
                    return;
                }
                try {
                    pin.shimmer = compare(first, img, ticks);
                    done.complete(1);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                } finally {
                    first.close();
                    img.close();
                    first = null;
                    shimmer = null;
                }
            });
        }

        static JsonObject compare(NativeImage a, NativeImage b, int ticks) {
            int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
            long sum = 0, changed = 0;
            int max = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int p = a.getPixel(x, y), q = b.getPixel(x, y);
                    int d = Math.max(Math.abs((p & 255) - (q & 255)), Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)),
                            Math.abs((p >> 16 & 255) - (q >> 16 & 255))));
                    sum += d;
                    if (d > 8) changed++;
                    max = Math.max(max, d);
                }
            JsonObject o = new JsonObject();
            o.addProperty("ticks", ticks);
            o.addProperty("mean", Math.round(sum * 1000.0 / Math.max(1, (long) w * h)) / 1000.0);
            o.addProperty("changed", Math.round(changed * 100000.0 / Math.max(1, (long) w * h)) / 100000.0);
            o.addProperty("max", max);
            return o;
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static String name(String n) {
        if (!n.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("pin: bad name " + n);
        return n;
    }

    private static Pin pin(String[] a, int min, String usage) {
        if (a.length < min) throw new IllegalArgumentException(usage);
        Pin p = PINS.get(a[1]);
        if (p == null) throw new IllegalStateException("pin " + a[1] + " does not exist (pin it first)");
        if (p.failed) throw new IllegalStateException("pin " + a[1] + " failed earlier (see the log)");
        return p;
    }

    private static boolean has(String[] a, int from, String word) {
        for (int i = from; i < a.length; i++) if (a[i].equals(word)) return true;
        return false;
    }

    /** YAW PITCH at a[i], a[i + 1] when both are numbers, else where the camera looks. */
    private static double[] yawPitch(String[] a, int i, Minecraft mc) {
        if (a.length > i + 1 && isNumber(a[i]) && isNumber(a[i + 1]))
            return new double[] {Double.parseDouble(a[i]), Double.parseDouble(a[i + 1])};
        return new double[] {mc.player.getYRot(), mc.player.getXRot()};
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** "12.5", "~" or "~-3" (relative to base). */
    static double coord(String token, double base) {
        String t = token.trim();
        if (t.startsWith("~")) return base + (t.length() == 1 ? 0 : Double.parseDouble(t.substring(1)));
        return Double.parseDouble(t);
    }

    private static Iterable<Pin> select(String[] a) {
        if (a.length < 2 || a[1].equals("all")) return java.util.List.copyOf(PINS.values());
        Pin p = PINS.get(a[1]);
        return p == null ? java.util.List.of() : java.util.List.of(p);
    }

    private static void clear(String name) {
        Pin p = PINS.remove(name);
        if (p == null) return;
        if (p.mesh != null) p.mesh.close();
        if (viewed == p) viewed = null;
        if (!p.ready.isDone()) p.ready.completeExceptionally(new IllegalStateException("pin " + name + " cleared"));
    }

    private static void clearAll() {
        for (String n : java.util.List.copyOf(PINS.keySet())) clear(n);
    }

    static String list() {
        if (PINS.isEmpty()) return "no pins";
        StringBuilder sb = new StringBuilder();
        for (Pin p : PINS.values()) {
            PinBox b = p.snap.volume.box;
            sb.append(p.snap.name).append(": ").append(b).append(" (").append(b.sx()).append('x').append(b.sy()).append('x')
                    .append(b.sz()).append("), ").append(p.mesh == null ? 0 : p.mesh.quads).append(" quads, ")
                    .append(p.snap.entities.size()).append(" entities, ").append(p.snap.blockEntities.size()).append(" block entities");
            if (p.failed) sb.append(", FAILED");
            else if (p.transform == null) sb.append(", hidden");
            else sb.append(String.format(Locale.ROOT, ", drawn at %.1f %.1f %.1f x%.5f", p.transform.ax, p.transform.ay,
                    p.transform.az, p.transform.scale));
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}

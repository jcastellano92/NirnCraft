package com.skycraft.atmosphere.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.util.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.joml.Matrix4f;

import com.skycraft.render.client.CpuMesh;
import com.skycraft.render.client.WorldMesh;
import com.skycraft.render.client.WorldDraw;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.util.context.ContextKey;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skycraft's clouds and distant weather, drawn in vanilla's cloud pass ({@link OverworldClouds}) and compatible
 * with Oculus shader packs unless {@link AtmosphereConfig#CLOUDS_SKIP_SHADERS} is enabled.
 *
 * <p>The meshes are built off the render thread by {@link CloudBuilder} about once a second (sooner when the camera
 * has moved), from a snapshot of the regional weather ({@link CloudClimate}) and moving {@link com.skycraft.atmosphere.WeatherFront}s;
 * the render thread only uploads the finished buffers and draws two static vertex buffers, so the per-frame cost is a
 * couple of draw calls. Between builds the sky mesh slides with the wind; the ground mesh (fog banks, peak caps,
 * plumes, storm walls) stays put. Clouds stay visible out to {@link AtmosphereConfig#CLOUD_DISTANCE} beyond the
 * projection's far plane by drawing with depth clamping: anything past the far plane lands on the far depth, behind
 * all terrain but over the sky. The GL state is restored afterwards (Embeddium and Oculus safe).</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class CloudRenderer {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/environment/clouds.png");
    private static final long BUILD_INTERVAL_MS = 1000L;
    private static final double REBUILD_DISTANCE = 14.0;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Skycraft clouds");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final CloudBuilder BUILDER = new CloudBuilder();
    private record BuildResult(long epoch, CloudBuilder.Result result) {}
    private static final com.skycraft.atmosphere.EpochMailbox<BuildResult> RESULTS =
            new com.skycraft.atmosphere.EpochMailbox<>(result->release(result.result()));
    private static long generation;
    private static ClientLevel trackedLevel;
    private static boolean building;
    private static long lastJob;
    private static double jobX, jobY, jobZ;

    private static WorldMesh skyVb;
    private static WorldMesh groundVb;
    private static boolean hasSky, hasGround;
    private static double anchorX, anchorY, anchorZ, builtDriftX, builtDriftZ;
    private static boolean textureLoaded;
    private static net.minecraft.client.renderer.texture.AbstractTexture preparedTexture;
    private static boolean renderedThisFrame;
    private static boolean textureProbe;

    // ---- wind drift (blocks), accumulated each tick
    private static double driftX, driftZ, driftXO, driftZO, highX, highZ;

    // ---- landmarks with their own clouds
    private static Place redMountain, throat;
    private static boolean placesLooked;

    // ---- profiling (read by the autotest)
    static long frameNanos;
    static long frameCount;
    static long buildNanos;
    static int builds;
    static int quads;
    static String census = "";

    private CloudRenderer() {}

    /** One-shot QA probe after the actual solid terrain draw, not a client-tick approximation. */
    public static void requestTextureProbe() { textureProbe=true; }

    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void textureProbeSky(RenderLevelStageEvent.AfterSky event) {
        textureProbe(event);
    }

    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void textureProbeTerrain(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        textureProbe(event);
    }

    private static void textureProbe(RenderLevelStageEvent event) {
        if(!textureProbe) return;
        if(event instanceof RenderLevelStageEvent.AfterSky
                || event instanceof RenderLevelStageEvent.AfterOpaqueBlocks) {
            Skycraft.LOGGER.info("Atmosphere render state probe: {}",RenderStateProbe.snapshot(event));
            if(event instanceof RenderLevelStageEvent.AfterOpaqueBlocks) {
                textureProbe=false;
                Skycraft.LOGGER.info("Atmosphere terrain texture probe: {}",TextureUploadState.probe());
            }
        }
    }

    // ================================================================== tick

    @SubscribeEvent
    public static void onRenderTickStart(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        if (event instanceof net.neoforged.neoforge.client.event.RenderFrameEvent.Pre) {
            renderedThisFrame = false;
        }
    }

    /**
     * 1 by day, down to 0.4 at night: the sun drives convection and the daytime breeze, so after dark the sky settles (the
     * clouds drift and churn more slowly), easing in and out through dusk and dawn.
     */
    static float nightCalm(ClientLevel level, float pt) {
        float sunY = Mth.cos(com.skycraft.atmosphere.client.SkyEnvironment.sunCycle(level, pt) * Mth.TWO_PI);
        float night = Mth.clamp(0.5F - sunY * 2.0F, 0.0F, 1.0F);
        return 1.0F - 0.6F * night;
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.client.event.ClientTickEvent.Post)) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != trackedLevel) resetSession(level);
        if (level == null || mc.player == null || mc.isPaused()) return;
        float wind = (ClientWeather.active() ? ClientWeather.wind() : 0.3f) * nightCalm(level, 1.0F);
        float low = 0.04f + 0.22f * wind, high = 0.08f + 0.32f * wind;
        driftXO = driftX;
        driftZO = driftZ;
        driftX += ClientWeather.windX() * low;
        driftZ += ClientWeather.windZ() * low;
        highX += ClientWeather.windX() * high;
        highZ += ClientWeather.windZ() * high;
        CloudClimate.tick();
        if (level.dimension() == Level.OVERWORLD) {
            Vec3 camPos = mc.gameRenderer.mainCamera().position();
            DistantStorms.tick(mc, camPos, level.getGameTime());
            WeatherFieldTexture.tickUpdate(level, camPos, 0f);
        }
    }

    /** Ensures the cloud sprite atlas is registered in the texture manager (used by {@link DistantStorms}). */
    static void ensureTextureLoaded() {
        if (!textureLoaded) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getTextureManager() != null) {
                textureLoaded = true;
                mc.getTextureManager().registerAndLoad(TEXTURE, new CloudTexture(TEXTURE));
            }
        }
    }

    /** Rebuild at the next frame (weather changed by a test or command). */
    public static void invalidate() {
        lastJob = 0L;
    }

    /** Test hook (visual autotest): flash the next distant storm right away. */
    public static void testLightning() {
        testLightning("cg");
    }

    public static void testLightning(String mode) {
        DistantStorms.force(mode);
    }

    /** Bounded diagnostics for real capture logs; no chunk loading. */
    public static String atmosphereDiagnostics() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return AtmosphereBudget.describe();
        var eye = mc.player.getEyePosition();
        var look = mc.player.getLookAngle();
        int x = (int) (eye.x + look.x * 1000), z = (int) (eye.z + look.z * 1000);
        String region = com.skycraft.tamriel.TamrielMap.region(x, z).id();
        var c = com.skycraft.atmosphere.WeatherPhysics.evaluate(x, z, mc.level.getGameTime() / 20.0,
                CloudClimate.snapshot().get(region), ClientWeather.kindOf(region), ClientWeather.windX(), ClientWeather.windZ(),
                ClientWeather.fronts(), mc.level.getDefaultClockTime());
        return AtmosphereBudget.describe() + " "+WeatherFieldTexture.diagnostics()+" "+FogRadianceTexture.describe()+" "+TextureUploadState.describe()+" ahead_x=" + x + " ahead_z=" + z
                + " cloud_base=" + (232f + c.cloudBaseOffset()) + " cloud_top="
                + (304f + c.cloudBaseOffset() + 120f * c.coverage() + 300f * c.stormTower())
                + " coverage=" + c.coverage() + " tower=" + c.stormTower() + " ground_fog=" + c.groundFog()
                + DistantStorms.diagnostics();
    }

    /** Profiling summary since the last reset: render-thread time per frame, worker time per build, mesh size. */
    public static String stats() {
        return String.format(java.util.Locale.ROOT, "clouds: %.3f ms/frame on the render thread and %.3f ms/frame on the GPU over %d frames;"
                        + " %.2f ms per build on the worker (%d builds); %d quads (%s); GPU query skips=%d",
                frameCount == 0 ? 0.0 : frameNanos / 1e6 / frameCount, gpuFrames == 0 ? 0.0 : gpuNanos / 1e6 / gpuFrames, frameCount,
                builds == 0 ? 0.0 : buildNanos / 1e6 / builds, builds, quads, census, querySkips);
    }

    /** Resets the counters and starts nonblocking native GPU timestamps (tests only). */
    public static void resetStats() {
        stopStats();
        frameNanos = frameCount = buildNanos = gpuNanos = 0L;
        builds = gpuFrames = 0;
        querySkips = 0;
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        timestampPeriod = device.getDeviceInfo().timestampPeriod();
        if (Float.isFinite(timestampPeriod) && timestampPeriod > 0f) {
            queryPool = device.createTimestampQueryPool(QUERY_PENDING.length * 2);
            gpuTiming = true;
        }
    }

    // Eight start/end pairs. Pending slots are never reused or synchronously awaited.
    private static boolean gpuTiming, queryActive;
    private static com.mojang.renderpearl.api.commands.GpuQueryPool queryPool;
    private static RenderPass queryPass;
    private static float timestampPeriod;
    private static final boolean[] QUERY_PENDING = new boolean[8];
    private static int query;
    private static long gpuNanos;
    private static int gpuFrames;
    private static long querySkips;

    public static void stopStats() {
        endGpuTimer();
        gpuTiming = false;
        if (queryPool != null) queryPool.close();
        queryPool = null;
        java.util.Arrays.fill(QUERY_PENDING, false);
        query = 0;
    }

    private static void beginGpuTimer(RenderPass pass) {
        queryActive = false;
        queryPass = null;
        if (!gpuTiming || queryPool == null) return;
        query = (query + 1) % QUERY_PENDING.length;
        if (QUERY_PENDING[query]) {
            var values = queryPool.getValues(query * 2, 2);
            if (values[0].isEmpty() || values[1].isEmpty()) {
                querySkips++;
                return;
            }
            gpuNanos += (long) ((values[1].getAsLong() - values[0].getAsLong()) * (double) timestampPeriod);
            gpuFrames++;
            QUERY_PENDING[query] = false;
        }
        // Borrow the caller's active pass: creating a nested encoder is invalid on native backends.
        pass.writeTimestamp(queryPool, query * 2);
        queryPass = pass;
        queryActive = true;
    }

    private static void endGpuTimer() {
        if (!queryActive) return;
        try {
            queryPass.writeTimestamp(queryPool, query * 2 + 1);
            QUERY_PENDING[query] = true;
        } finally {
            queryActive = false;
            queryPass = null;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        resetSession(null);
        SkyGeometry.close();
    }

    @net.neoforged.fml.common.EventBusSubscriber(modid=Skycraft.MODID,value=Dist.CLIENT)
    public static final class CloudResourceEvents {
        @SubscribeEvent
        public static void reloadListeners(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent event) {
            event.addListener(Identifier.fromNamespaceAndPath(Skycraft.MODID,"cloud_cache"), (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager ->
                    Minecraft.getInstance().execute(() -> {
                        WeatherFieldTexture.invalidateStaticInputs();
                        resetSession(Minecraft.getInstance().level);
                        SkyGeometry.close();
                    }));
        }
    }

    private static void resetSession(ClientLevel level) {
        generation=RESULTS.advance();
        building=false;
        trackedLevel=level;
        LocalOcclusionTexture.clear();
        CloudClimate.clear();
        SkyEnvironment.clear();
        DistantStorms.clear();
        hasSky = hasGround = false;
        preparedTexture = null;
        lastJob = 0L;
        driftX=driftZ=driftXO=driftZO=highX=highZ=0;
        if(skyVb!=null) { skyVb.close(); skyVb=null; }
        if(groundVb!=null) { groundVb.close(); groundVb=null; }
        stopStats();
        clearStormMeshes();
    }

    private static void release(CloudBuilder.Result result) {
        for(var buffer:new CpuMesh[]{result.sky,result.ground})
            if(buffer!=null) buffer.close();
    }

    // ================================================================== render

    private static final ContextKey<Frame> FRAME = new ContextKey<>(Identifier.fromNamespaceAndPath(Skycraft.MODID, "cloud_frame"));
    private static final WorldDraw.Material CLOUD = WorldDraw.material(Identifier.fromNamespaceAndPath(Skycraft.MODID, "regional_clouds"),
            DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ALPHA, true, false, true);
    private static final WorldDraw.Material LIGHTNING_GLOW = WorldDraw.material(Identifier.fromNamespaceAndPath(Skycraft.MODID, "storm_glow"),
            DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ADDITIVE, true, false, true);
    private static final WorldDraw.Material LIGHTNING_BOLT = WorldDraw.material(Identifier.fromNamespaceAndPath(Skycraft.MODID, "storm_channels"),
            DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ADDITIVE, true, false, true);
    private record Frame(long epoch, boolean enabled, boolean shaders, boolean visible, Vec3 camera,
                         double driftX, double driftZ, float alpha, float flash, DistantStorms.RenderFrame storms) {}
    private static WorldMesh stormGlow, stormBolts;

    @SubscribeEvent public static void pipelines(RegisterRenderPipelinesEvent event) {
        CLOUD.register(event); LIGHTNING_GLOW.register(event); LIGHTNING_BOLT.register(event);
    }

    @SubscribeEvent public static void extract(ExtractLevelRenderStateEvent event) {
        ClientLevel level = event.getLevel();
        if (level.dimension() != Level.OVERWORLD) return;
        if (level != trackedLevel) resetSession(level);
        var camera = event.getCamera();
        float pt = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 position = camera.position();
        boolean shaders = ShaderCompat.shadersActive();
        boolean enabled = AtmosphereConfig.CLOUDS.get();
        boolean visible = camera.getFluidInCamera() == FogType.NONE
                && (!(camera.entity() instanceof net.minecraft.world.entity.LivingEntity entity)
                    || (!entity.hasEffect(com.skycraft.platform.Effects.holder(MobEffects.BLINDNESS)) && !entity.hasEffect(com.skycraft.platform.Effects.holder(MobEffects.DARKNESS))));
        if (enabled && visible && !shaders) schedule(level, pt, position.x, position.y, position.z);
        float fog = ClientWeather.active() ? Mth.clamp(ClientWeather.fog() * AtmosphereConfig.WEATHER_FOG_STRENGTH.get().floatValue(), 0f, 1f) : 0f;
        float alpha = 1f - .85f * fog * (float)Math.sqrt(fog);
        float angle = SkyEnvironment.sunCycle(level, pt) * Mth.TWO_PI;
        float daylight = Mth.clamp(Mth.cos(angle) * 2f + .3f, 0f, 1f);
        // Native weather extraction already carries the visible lightning flash through environment attributes.
        float flash = SkyEnvironment.lightningFlash(level, pt) ? 1.35f : 1f;
        var storms = AtmosphereConfig.DISTANT_WEATHER.get() && visible
                ? DistantStorms.extract(position, pt, daylight, shaders) : null;
        event.getRenderState().setRenderData(FRAME, new Frame(generation, enabled, shaders, visible, position,
                Mth.lerp(pt, driftXO, driftX), Mth.lerp(pt, driftZO, driftZ), alpha, flash, storms));
    }

    /** Called before any active cloud render pass. CPU work remains on the single snapshot worker. */
    static void prepare(LevelRenderState state) {
        Frame frame = state.getRenderData(FRAME);
        if (frame == null || frame.epoch != generation || !frame.enabled || frame.shaders || !frame.visible) return;
        ensureTextureLoaded(); collect();
        if (skyVb != null) skyVb.prepare();
        if (groundVb != null) groundVb.prepare();
    }
    @SubscribeEvent public static void prepareStorms(PrepareRenderBuffersEvent event) {
        clearStormMeshes();
        preparedTexture = null;
        Frame frame = event.getLevelRenderState().getRenderData(FRAME);
        if (frame == null || frame.epoch != generation || !frame.enabled) return;
        ensureTextureLoaded();
        preparedTexture = Minecraft.getInstance().getTextureManager().getTexture(TEXTURE);
        if (frame.storms == null) return;
        stormGlow = frame.storms.glow().upload();
        stormBolts = frame.storms.bolts().upload();
    }
    private static void clearStormMeshes() {
        if (stormGlow != null) stormGlow.close();
        if (stormBolts != null) stormBolts.close();
        stormGlow = stormBolts = null;
    }

    static boolean render(LevelRenderState state, Matrix4fc modelView, RenderPass nativePass, OitStage stage) {
        Frame frame = state.getRenderData(FRAME);
        if (frame == null || frame.epoch != generation || !frame.enabled) return false;
        if (frame.shaders || !frame.visible) return true;
        var texture = preparedTexture;
        if (texture == null) throw new IllegalStateException("Cloud texture was not prepared before its native render pass");
        renderedThisFrame = true;
        long start = System.nanoTime();
        beginGpuTimer(nativePass);
        try (var pass = WorldDraw.borrow(nativePass)) {
            Vector4f tint = new Vector4f(frame.flash, frame.flash, frame.flash, frame.alpha);
            if (skyVb != null) draw(pass, skyVb, CLOUD, new Matrix4f(modelView).translate(
                    (float)(anchorX - frame.camera.x + frame.driftX - builtDriftX), (float)(anchorY - frame.camera.y),
                    (float)(anchorZ - frame.camera.z + frame.driftZ - builtDriftZ)), tint, texture, stage);
            if (groundVb != null) draw(pass, groundVb, CLOUD, new Matrix4f(modelView).translate(
                    (float)(anchorX - frame.camera.x), (float)(anchorY - frame.camera.y), (float)(anchorZ - frame.camera.z)), tint, texture, stage);
            renderStorms(pass, modelView, texture, stage, false);
        } finally {
            endGpuTimer(); frameNanos += System.nanoTime() - start; frameCount++;
        }
        return true;
    }
    private static void draw(WorldDraw.Pass pass, WorldMesh mesh, WorldDraw.Material material, Matrix4fc modelView,
                             Vector4f tint, net.minecraft.client.renderer.texture.AbstractTexture texture, OitStage stage) {
        if (stage == null) pass.draw(mesh, material, modelView, tint, texture);
        else pass.drawOit(mesh, material, modelView, tint, texture, stage);
    }
    private static void renderStorms(WorldDraw.Pass pass, Matrix4fc modelView,
                                     net.minecraft.client.renderer.texture.AbstractTexture texture, OitStage stage, boolean boltsOnly) {
        if (!boltsOnly && stormGlow != null) draw(pass, stormGlow, LIGHTNING_GLOW, modelView, new Vector4f(1f), texture, stage);
        if (stormBolts != null) draw(pass, stormBolts, LIGHTNING_BOLT, modelView, new Vector4f(1f), null, stage);
    }
    @SubscribeEvent public static void shaderLightning(RenderLevelStageEvent.AfterWeather event) {
        Frame frame = event.getLevelRenderState().getRenderData(FRAME);
        if (frame == null || frame.epoch != generation || !frame.shaders || !frame.visible) return;
        try (var pass = WorldDraw.borrow(event.getRenderPass())) {
            renderStorms(pass, event.getModelViewMatrix(), null, null, true);
        }
    }
    @SubscribeEvent public static void shaderLightningOit(RenderLevelStageEvent.OitTranslucent event) {
        Frame frame = event.getLevelRenderState().getRenderData(FRAME);
        if (frame == null || frame.epoch != generation || !frame.shaders || !frame.visible) return;
        try (var pass = WorldDraw.borrow(event.getRenderPass())) {
            renderStorms(pass, event.getModelViewMatrix(), null, event.getOitStage(), true);
        }
    }

    private static void collect() {
        BuildResult pending = RESULTS.poll();
        if (pending == null) return;
        building = false;
        CloudBuilder.Result result = pending.result();
        if (pending.epoch() != generation || result.failed) { release(result); return; }
        WorldMesh newSky = null, newGround = null;
        try {
            if (result.sky != null) { CpuMesh cpu = result.sky; result.sky = null; newSky = new WorldMesh(cpu); }
            if (result.ground != null) { CpuMesh cpu = result.ground; result.ground = null; newGround = new WorldMesh(cpu); }
        } catch (RuntimeException | Error error) {
            if (newSky != null) newSky.close();
            if (newGround != null) newGround.close();
            release(result); throw error;
        }
        if (skyVb != null) skyVb.close();
        if (groundVb != null) groundVb.close();
        skyVb = newSky; groundVb = newGround;
        hasSky = newSky != null; hasGround = newGround != null;
        anchorX = result.anchorX; anchorY = result.anchorY; anchorZ = result.anchorZ;
        builtDriftX = result.driftX; builtDriftZ = result.driftZ;
        buildNanos += result.nanos; builds++; quads = result.quads; census = result.census;
    }

    private static void schedule(ClientLevel level, float pt, double camX, double camY, double camZ) {
        if (building) return;
        long now = Util.getMillis();
        double dx = camX - jobX, dy = camY - jobY, dz = camZ - jobZ;
        boolean moved = dx * dx + dy * dy + dz * dz > REBUILD_DISTANCE * REBUILD_DISTANCE;
        if (lastJob != 0L && now - lastJob < BUILD_INTERVAL_MS && !(moved && now - lastJob > 120L)) return;
        lastJob = now;
        jobX = camX;
        jobY = camY;
        jobZ = camZ;
        CloudBuilder.Job job = job(level, pt, camX, camY, camZ);
        building = true;
        long epoch=generation;
        WORKER.execute(() -> {
            var result=BUILDER.build(job);
            RESULTS.publish(epoch,new BuildResult(epoch,result));
        });
    }

    private static CloudBuilder.Job job(ClientLevel level, float pt, double camX, double camY, double camZ) {
        Minecraft mc = Minecraft.getInstance();
        if (!placesLooked) {
            placesLooked = true;
            redMountain = Places.byId("red_mountain").orElse(null);
            throat = Places.byId("throat_of_the_world").orElse(null);
        }
        float angle = com.skycraft.atmosphere.client.SkyEnvironment.sunCycle(level, pt) * Mth.TWO_PI;
        float sunX = -Mth.sin(angle), sunY = Mth.cos(angle);
        Map<String, float[]> climate = CloudClimate.snapshot();
        if (!ClientWeather.active()) climate = vanillaClimate(level, pt);
        double dX = Mth.lerp(pt, driftXO, driftX), dZ = Mth.lerp(pt, driftZO, driftZ);
        float fog = ClientWeather.active() ? Mth.clamp(ClientWeather.fog() * AtmosphereConfig.WEATHER_FOG_STRENGTH.get().floatValue(), 0f, 1f) : 0f;
        boolean fancy = mc.options.getCloudStatus() == CloudStatus.FANCY;
        return new CloudBuilder.Job(camX, camY, camZ, dX, dZ, highX, highZ,
                ClientWeather.windX(), ClientWeather.windZ(), (ClientWeather.active() ? ClientWeather.wind() : 0.3f) * nightCalm(level, pt),
                sunX, sunY, 0f, ClientWeather.fogR, ClientWeather.fogG, ClientWeather.fogB, fog,
                AtmosphereConfig.CLOUD_HEIGHT.get(), AtmosphereConfig.CLOUD_DISTANCE.get(), fancy,
                AtmosphereConfig.DISTANT_WEATHER.get(), WeatherProfile.mistHour(level.getDefaultClockTime()),
                (float) ((level.getGameTime() + pt) / 20.0), climate,
                redMountain != null ? redMountain.x() : 0, redMountain != null ? redMountain.z() : 0, redMountain != null,
                throat != null ? throat.x() : 0, throat != null ? throat.z() : 0, throat != null,
                ClientWeather.fronts(),
                com.skycraft.atmosphere.AtmosphereMath.moonLight(angle,level.getDefaultClockTime()+pt,false),
                AtmosphereConfig.SECUNDA.get()?com.skycraft.atmosphere.AtmosphereMath.moonLight(angle,level.getDefaultClockTime()+pt,true)
                        :com.skycraft.atmosphere.AtmosphereMath.MoonLight.DARK);
    }

    /**
     * Without regional weather (the server has it off) the whole sky follows vanilla's rain and thunder: every region
     * the builder looks up falls back to these parameters.
     */
    private static Map<String, float[]> vanillaClimate(ClientLevel level, float pt) {
        float rain = level.getRainLevel(pt), thunder = level.getThunderLevel(pt);
        float[] clear = CloudClimate.target(WeatherKind.CLOUDY), wet = CloudClimate.target(WeatherKind.RAIN),
                storm = CloudClimate.target(WeatherKind.STORM);
        float[] mix = new float[CloudClimate.COUNT];
        for (int i = 0; i < mix.length; i++) mix[i] = Mth.lerp(thunder, Mth.lerp(rain, clear[i] * 0.6f, wet[i]), storm[i]);
        return new VanillaClimate(mix);
    }

    /** A climate map that answers the same parameters for every region. */
    private static final class VanillaClimate extends HashMap<String, float[]> {
        private final float[] all;

        VanillaClimate(float[] all) {
            this.all = all;
        }

        @Override
        public float[] get(Object key) {
            return all;
        }
    }

}

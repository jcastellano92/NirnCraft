package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

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
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class CloudRenderer {
    static final ResourceLocation TEXTURE = new ResourceLocation(Skycraft.MODID, "textures/environment/clouds.png");
    private static final long BUILD_INTERVAL_MS = 1000L;
    private static final double REBUILD_DISTANCE = 14.0;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Skycraft clouds");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final CloudBuilder BUILDER = new CloudBuilder();
    private static volatile CloudBuilder.Result ready;
    private static boolean building;
    private static long lastJob;
    private static double jobX, jobY, jobZ;

    private static VertexBuffer skyVb;
    private static VertexBuffer groundVb;
    private static boolean hasSky, hasGround;
    private static double anchorX, anchorY, anchorZ, builtDriftX, builtDriftZ;
    private static boolean textureLoaded;
    private static boolean renderedThisFrame;

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

    // ================================================================== tick

    @SubscribeEvent
    public static void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            renderedThisFrame = false;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        float wind = ClientWeather.active() ? ClientWeather.wind() : 0.3f;
        float low = 0.04f + 0.22f * wind, high = 0.08f + 0.32f * wind;
        driftXO = driftX;
        driftZO = driftZ;
        driftX += ClientWeather.windX() * low;
        driftZ += ClientWeather.windZ() * low;
        highX += ClientWeather.windX() * high;
        highZ += ClientWeather.windZ() * high;
        CloudClimate.tick();
        if (level.dimension() == Level.OVERWORLD) {
            Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
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
                mc.getTextureManager().register(TEXTURE, new CloudTexture(TEXTURE));
            }
        }
    }

    /** Rebuild at the next frame (weather changed by a test or command). */
    public static void invalidate() {
        lastJob = 0L;
    }

    /** Test hook (visual autotest): flash the next distant storm right away. */
    public static void testLightning() {
        DistantStorms.force = true;
    }

    /** Profiling summary since the last reset: render-thread time per frame, worker time per build, mesh size. */
    public static String stats() {
        return String.format(java.util.Locale.ROOT, "clouds: %.3f ms/frame on the render thread and %.3f ms/frame on the GPU over %d frames;"
                        + " %.2f ms per build on the worker (%d builds); %d quads (%s)",
                frameCount == 0 ? 0.0 : frameNanos / 1e6 / frameCount, gpuFrames == 0 ? 0.0 : gpuNanos / 1e6 / gpuFrames, frameCount,
                builds == 0 ? 0.0 : buildNanos / 1e6 / builds, builds, quads, census);
    }

    /** Resets the counters and starts timing the cloud draw on the GPU (timer queries; tests only). */
    public static void resetStats() {
        frameNanos = frameCount = buildNanos = gpuNanos = 0L;
        builds = gpuFrames = 0;
        gpuTiming = true;
    }

    // ---- GPU timing (only while a test asked for it): two queries in flight, read a frame late
    private static boolean gpuTiming;
    private static final int[] QUERIES = new int[2];
    private static final boolean[] QUERY_PENDING = new boolean[2];
    private static int query;
    private static long gpuNanos;
    private static int gpuFrames;

    private static void beginGpuTimer() {
        if (!gpuTiming) return;
        if (QUERIES[0] == 0) org.lwjgl.opengl.GL15.glGenQueries(QUERIES);
        query ^= 1;
        int id = QUERIES[query];
        if (QUERY_PENDING[query]) {
            gpuNanos += org.lwjgl.opengl.GL33.glGetQueryObjecti64(id, org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
            gpuFrames++;
        }
        org.lwjgl.opengl.GL15.glBeginQuery(org.lwjgl.opengl.GL33.GL_TIME_ELAPSED, id);
        QUERY_PENDING[query] = true;
    }

    private static void endGpuTimer() {
        if (gpuTiming) org.lwjgl.opengl.GL15.glEndQuery(org.lwjgl.opengl.GL33.GL_TIME_ELAPSED);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CloudClimate.clear();
        DistantStorms.clear();
        hasSky = hasGround = false;
        lastJob = 0L;
    }

    // ================================================================== render

    /**
     * Draws Skycraft's mesh clouds when shaders are off; returns {@code false} when an Oculus/Iris shader pack is
     * active so the shader pack's 3D volumetric raymarcher (driven by {@link WeatherFieldTexture}) draws the clouds
     * and 3D rain curtains occluded by Distant Horizons.
     */
    static boolean render(ClientLevel level, float pt, PoseStack pose, double camX, double camY, double camZ, Matrix4f proj) {
        if (!AtmosphereConfig.CLOUDS.get() || level.dimension() != Level.OVERWORLD) return false;
        if (ShaderCompat.shadersActive()) return false;
        renderedThisFrame = true;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE) return true;
        if (camera.getEntity() instanceof net.minecraft.world.entity.LivingEntity le
                && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS))) return true;
        long start = System.nanoTime();
        try {
            ensureTextureLoaded();
            collect();
            schedule(level, pt, camX, camY, camZ);
            draw(level, pt, pose, camX, camY, camZ, proj);
        } finally {
            frameNanos += System.nanoTime() - start;
            frameCount++;
        }
        return true;
    }

    /** Uploads a finished build. */
    private static void collect() {
        CloudBuilder.Result r = ready;
        if (r == null) return;
        ready = null;
        building = false;
        if (r.failed) return;
        if (skyVb == null) {
            skyVb = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            groundVb = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        hasSky = upload(skyVb, r.sky);
        hasGround = upload(groundVb, r.ground);
        anchorX = r.anchorX;
        anchorY = r.anchorY;
        anchorZ = r.anchorZ;
        builtDriftX = r.driftX;
        builtDriftZ = r.driftZ;
        DistantStorms.update(r.storms, r.stormCount);
        buildNanos += r.nanos;
        builds++;
        quads = r.quads;
        census = r.census;
    }

    private static boolean upload(VertexBuffer vb, BufferBuilder.RenderedBuffer buffer) {
        if (buffer == null) return false;
        vb.bind();
        vb.upload(buffer);
        VertexBuffer.unbind();
        return true;
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
        WORKER.execute(() -> ready = BUILDER.build(job));
    }

    private static CloudBuilder.Job job(ClientLevel level, float pt, double camX, double camY, double camZ) {
        Minecraft mc = Minecraft.getInstance();
        if (!placesLooked) {
            placesLooked = true;
            redMountain = Places.byId("red_mountain").orElse(null);
            throat = Places.byId("throat_of_the_world").orElse(null);
        }
        float angle = level.getTimeOfDay(pt) * Mth.TWO_PI;
        float sunX = -Mth.sin(angle), sunY = Mth.cos(angle);
        Map<String, float[]> climate = CloudClimate.snapshot();
        if (!ClientWeather.active()) climate = vanillaClimate(level, pt);
        double dX = Mth.lerp(pt, driftXO, driftX), dZ = Mth.lerp(pt, driftZO, driftZ);
        float fog = ClientWeather.active() ? Mth.clamp(ClientWeather.fog() * AtmosphereConfig.WEATHER_FOG_STRENGTH.get().floatValue(), 0f, 1f) : 0f;
        boolean fancy = mc.options.getCloudsType() == CloudStatus.FANCY;
        return new CloudBuilder.Job(camX, camY, camZ, dX, dZ, highX, highZ,
                ClientWeather.windX(), ClientWeather.windZ(), ClientWeather.active() ? ClientWeather.wind() : 0.3f,
                sunX, sunY, 0f, ClientWeather.fogR, ClientWeather.fogG, ClientWeather.fogB, fog,
                AtmosphereConfig.CLOUD_HEIGHT.get(), AtmosphereConfig.CLOUD_DISTANCE.get(), fancy,
                AtmosphereConfig.DISTANT_WEATHER.get(), WeatherProfile.mistHour(level.getDayTime()),
                (float) ((level.getGameTime() + pt) / 20.0), climate,
                redMountain != null ? redMountain.x() : 0, redMountain != null ? redMountain.z() : 0, redMountain != null,
                throat != null ? throat.x() : 0, throat != null ? throat.z() : 0, throat != null,
                ClientWeather.fronts());
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

    private static void draw(ClientLevel level, float pt, PoseStack pose, double camX, double camY, double camZ, Matrix4f proj) {
        if (!hasSky && !hasGround) return;
        beginGpuTimer();
        try {
            drawMeshes(level, pt, pose, camX, camY, camZ, proj);
        } finally {
            endGpuTimer();
        }
    }

    private static void drawMeshes(ClientLevel level, float pt, PoseStack pose, double camX, double camY, double camZ, Matrix4f proj) {
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) return;
        Minecraft mc = Minecraft.getInstance();
        float fog = ClientWeather.active() ? Mth.clamp(ClientWeather.fog() * AtmosphereConfig.WEATHER_FOG_STRENGTH.get().floatValue(), 0f, 1f) : 0f;
        float alpha = 1f - 0.85f * fog * (float) Math.sqrt(fog);
        float flash = level.getSkyFlashTime() > 0 ? 1.35f : 1f;
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        GL11.glEnable(GL32.GL_DEPTH_CLAMP);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        RenderSystem.setShaderColor(flash, flash, flash, alpha);
        try {
            if (hasSky) {
                double dX = Mth.lerp(pt, driftXO, driftX) - builtDriftX, dZ = Mth.lerp(pt, driftZO, driftZ) - builtDriftZ;
                pose.pushPose();
                pose.translate(anchorX - camX + dX, anchorY - camY, anchorZ - camZ + dZ);
                skyVb.bind();
                skyVb.drawWithShader(pose.last().pose(), proj, shader);
                pose.popPose();
            }
            if (hasGround) {
                pose.pushPose();
                pose.translate(anchorX - camX, anchorY - camY, anchorZ - camZ);
                groundVb.bind();
                groundVb.drawWithShader(pose.last().pose(), proj, shader);
                pose.popPose();
            }
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            float angle = level.getTimeOfDay(pt) * Mth.TWO_PI;
            float daylight = Mth.clamp(Mth.cos(angle) * 2f + 0.3f, 0f, 1f);
            DistantStorms.render(pose.last().pose(), proj, new Vec3(camX, camY, camZ), pt, daylight);
        } finally {
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }
}

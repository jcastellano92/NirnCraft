package com.nirncraft.atlas;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nirncraft.NirnCraft;
import com.nirncraft.atmosphere.NightSkyMath;
import com.nirncraft.atmosphere.NirnCelestial;
import com.nirncraft.atmosphere.client.CelestialSky;
import com.nirncraft.atmosphere.client.NightSky;
import com.nirncraft.atmosphere.client.SkyCube;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.CustomSkyboxRenderer;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Infinite ATLAS's sky while the near pass owns the frame (overworld): one sky, every layer in it, from the camera's
 * real position. Vanilla's sky dome, sunrise fan, sun, moon and stars and NirnCraft's mesh sky (SkyRenderer: the
 * fallback where this one does not draw) give way to
 * <ul>
 *   <li>the air's sky (com.nirncraft.atlas.AtlasAir; native/src/air.hpp, ported in atlas_air.glsl), the far field's own
 *       model, so its haze meets a sky of the same air and the horizon has no seam: marched each frame into a small
 *       sky-view LUT (atlas_sky_view.fsh: 192 x 108, the elevation gathered at the horizon);</li>
 *   <li>the sky's own lights through that air (atlas_night.glsl, its state from atmosphere.client.NightSky /
 *       NightSkyMath): the aurora's volume over the auroral oval, the stars, the galaxy and the birthsigns, Masser and
 *       Secunda with their phases, meteors, the airglow;</li>
 *   <li>the sun's disc in its transmitted light.</li>
 * </ul>
 * The sky (atlas_sky.fsh) evaluates all of it per pixel; the water's reflections read the emitters from a small cube
 * (atlas_sky_emit.fsh into SkyCube: six 96 x 96 faces, half float, pre-exposed) over the LUT; the land's light takes the
 * diffuse emitters' integral (atlas_sky_light.fsh: two bands of spherical harmonics, read back a frame or two late),
 * plus the moons (the brighter the key light, the other a fill) and the meteors' flashes: {@link #nightLight}, for
 * the near pass (AtlasLook.light) and the far field (atlas_set_sky_glow). Installed at extraction as the frame's custom
 * skybox renderer, only where no other one (a realm's) is set and the core has the air.
 */
final class AtlasSky implements CustomSkyboxRenderer {
    static final AtlasSky INSTANCE = new AtlasSky();
    static final int VIEW_W = 192, VIEW_H = 108;

    private static final Identifier GALAXY = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/galaxy.png");
    private static final Identifier MASSER = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/masser_phases.png");
    private static final Identifier SECUNDA = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/secunda_phases.png");

    private static BindGroupLayout.Builder night(BindGroupLayout.Builder b) {
        return b.withUniform("AirInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("AirMs", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("NightInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("NightSigns", UniformType.UNIFORM_BUFFER)
                .withUniform("CelestialInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("NightGalaxy", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("NightMasser", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("NightSecunda", UniformType.COMBINED_IMAGE_SAMPLER);
    }

    private static final BindGroupLayout SKY_INFO = night(BindGroupLayout.builder()
            .withUniform("SkyInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("SkyView", UniformType.COMBINED_IMAGE_SAMPLER)).build();
    private static final BindGroupLayout AIR_INFO = BindGroupLayout.builder()
            .withUniform("AirInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("AirMs", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final BindGroupLayout NIGHT_INFO = night(BindGroupLayout.builder()).build();

    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("nirncraft", "pipeline/atlas_sky"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("nirncraft", "core/atlas_sky"))
            .withBindGroupLayout(SKY_INFO)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    static final RenderPipeline VIEW_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("nirncraft", "pipeline/atlas_sky_view"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("nirncraft", "core/atlas_sky_view"))
            .withBindGroupLayout(AIR_INFO)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            // the sky-view LUT's own format (RGBA16F): the default state declares RGBA8, and the pass refused it at the
            // first sky (the game crashed on loading the world)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    /** The sky's emitters in every direction (the water's off-screen sky), half float, pre-exposed. */
    static final RenderPipeline EMIT_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("nirncraft", "pipeline/atlas_sky_emit"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("nirncraft", "core/atlas_sky_emit"))
            .withBindGroupLayout(NIGHT_INFO)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    /** The diffuse emitters' light as SH L1 (4 x 1, float). */
    static final RenderPipeline LIGHT_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("nirncraft", "pipeline/atlas_sky_light"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("nirncraft", "core/atlas_sky_light"))
            .withBindGroupLayout(NIGHT_INFO)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private MappableRingBuffer info, airInfo, nightInfo;
    private MappableRingBuffer celInfo;  // CelestialInfo (atlas_celestial.glsl: the stars' frame, the planets, a comet)
    private static GpuBuffer signs;
    private static TextureTarget view, lightTarget;
    private static boolean viewDrawn;

    // the light's read back: a ring of three buffers (a frame or two late, never a stall), the last SH that came back
    private static final GpuBuffer[] ring = new GpuBuffer[3];
    private static final boolean[] busy = new boolean[3];
    private static int next;
    private static final float[] diffuseSh = new float[16];  // rgb + pad, 4 coefficients
    private static volatile long lightFrame;
    private static long frameCount;
    private static volatile boolean nightDrawn;

    private AtlasSky() {}

    static void register(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(PIPELINE);
        e.registerPipeline(VIEW_PIPELINE);
        e.registerPipeline(EMIT_PIPELINE);
        e.registerPipeline(LIGHT_PIPELINE);
    }

    /** Whether this frame's sky is ours (the unified sky: the mesh sky steps aside). */
    static boolean owns(Minecraft mc) {
        if (!AtlasNearPass.active || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return false;
        if (AtlasClient.irisPackInUse() || !AtlasClient.nearLightSupported()) return false;
        return AtlasAir.coeffs() != null;  // a core without the air: vanilla's sky (and NirnCraft's mesh sky over it)
    }

    /** At extraction: our sky for this frame when the near pass will own it (and no realm has its own). */
    static void extract(ExtractLevelRenderStateEvent e) {
        if (!owns(Minecraft.getInstance())) return;
        LevelRenderState state = e.getRenderState();
        if (state.customSkyboxRenderer == null) state.customSkyboxRenderer = INSTANCE;
    }

    /** The sky-view LUT this frame's air was marched into (the water's reflections), or null when none was drawn. */
    static GpuTextureView skyView() {
        return view != null && viewDrawn ? view.getColorTextureView() : null;
    }

    @Override
    public boolean renderSky(LevelRenderState levelRenderState, SkyRenderState sky, Matrix4fc modelView, GpuBufferSlice skyFog) {
        Minecraft mc = Minecraft.getInstance();
        float[] coeffs = AtlasAir.coeffs();
        GpuTextureView ms = AtlasAir.multiscatter();
        if (coeffs == null || ms == null) return false;
        var device = RenderSystem.getDevice();
        if (info == null) info = new MappableRingBuffer(() -> "atlas sky info", 130, 112);
        if (airInfo == null) airInfo = new MappableRingBuffer(() -> "atlas air info", 130, 112);
        if (nightInfo == null) nightInfo = new MappableRingBuffer(() -> "atlas night info", 130, NightSkyMath.BLOCK_FLOATS * 4);
        if (celInfo == null) celInfo = new MappableRingBuffer(() -> "atlas celestial info", 130, NirnCelestial.BLOCK_FLOATS * 4);
        if (signs == null) {
            float[] s = NirnCelestial.catalogue();  // the birthsigns' and the named stars (NightSigns)
            ByteBuffer b = MemoryUtil.memAlloc(s.length * 4).order(ByteOrder.nativeOrder());
            try {
                for (float v : s) b.putFloat(v);
                b.flip();
                signs = device.createBuffer(() -> "atlas night signs", GpuBuffer.USAGE_UNIFORM, b);
            } finally {
                MemoryUtil.memFree(b);
            }
        }
        if (view == null) view = new TextureTarget("atlas sky view", VIEW_W, VIEW_H, GpuFormat.RGBA16_FLOAT, null);
        if (lightTarget == null) lightTarget = new TextureTarget("atlas sky light", 4, 1, GpuFormat.RGBA32_FLOAT, null);
        // (a first lookup uploads the texture: before any pass is open)
        var tm = mc.getTextureManager();
        GpuTextureView galaxy = tm.getTexture(GALAXY).getTextureView(), masser = tm.getTexture(MASSER).getTextureView(),
                secunda = tm.getTexture(SECUNDA).getTextureView();
        var cam = levelRenderState.cameraRenderState;
        Matrix4f inv = AtlasLook.levelProjection(cam).mul(modelView).invert();
        float[] sun = AtlasClient.sunDirection(sky.sunAngle);
        float eyeY = mc.player != null ? (float) mc.player.getEyeY() : 64f;
        float[] light = AtlasAir.light(sun, eyeY);
        float[] moon = AtlasAir.moonFor(sun);
        // the eye's altitude in km of air (air.hpp altitudeOf: above sea level, coeffs[11] km a block)
        float alt = Math.max(eyeY - 63f, 0f) * coeffs[11];
        float[] look = AtlasLook.frame(mc);
        // the sky's lights for this frame (the camera's real place: the aurora seen from there), pre-exposed by the eye
        float[] night = NightSky.frame(mc, sky.sunAngle, AtlasClient.sunPathRotationDeg, cam.pos.x, cam.pos.z, alt,
                mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        nightDrawn = NightSky.drawn();
        NightSkyMath.target(night, look[0], (Math.PI / 2) / SkyCube.FACE);
        // the stars, the planets, a comet: from the camera's place on the map and the calendar (NirnCelestial)
        float[] cel = CelestialSky.frame(mc, sky.sunAngle, sun[1], cam.pos.x, cam.pos.z,
                mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        var band = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        try (var v = airInfo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(v.data())
                    .putVec4(coeffs[0], coeffs[1], coeffs[2], coeffs[3])
                    .putVec4(coeffs[4], coeffs[5], coeffs[6], coeffs[7])
                    .putVec4(coeffs[8], coeffs[9], coeffs[10], coeffs[11])
                    .putVec4(coeffs[12], coeffs[13], coeffs[14], coeffs[15])
                    .putVec4(sun[0], sun[1], sun[2], alt)
                    .putVec4(moon[0], moon[1], moon[2], AtlasLook.frame(mc)[0])  // w: the LUT's pre-exposure
                    .putVec4(moon[3], moon[4], moon[5], 0f);
        }
        try (var v = nightInfo.currentBuffer().map(false, true)) {
            var b = Std140Builder.intoBuffer(v.data());
            for (int i = 0; i < night.length; i += 4) b.putVec4(night[i], night[i + 1], night[i + 2], night[i + 3]);
        }
        try (var v = celInfo.currentBuffer().map(false, true)) {
            var b = Std140Builder.intoBuffer(v.data());
            for (int i = 0; i < cel.length; i += 4) b.putVec4(cel[i], cel[i + 1], cel[i + 2], cel[i + 3]);
        }
        var encoder = device.createCommandEncoder();
        // 1. the sky-view LUT: the air marched for each of its directions
        try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS sky view", view.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(VIEW_PIPELINE));
            pass.setUniform("AirInfo", airInfo.currentBuffer());
            pass.setUniform("AirMs", ms, linear);
            pass.draw(3, 1, 0, 0);
        }
        viewDrawn = true;
        // 2. the emitters in every direction (the water's sky off screen), when the near pass will want them
        if (AtlasClient.wantsSkyCube()) {
            var cube = SkyCube.target();
            try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS sky emitters", cube.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(EMIT_PIPELINE));
                bindNight(pass, ms, linear, band, galaxy, masser, secunda);
                pass.draw(3, 1, 0, 0);
            }
            SkyCube.drawn();
        }
        // 3. the diffuse emitters' light (read back), every other frame
        if ((frameCount++ & 1) == 0) light(encoder, ms, linear, band, galaxy, masser, secunda);
        // 4. the sky: the LUT looked up per pixel (repeat across the bearing), the sky's lights, the sun's disc
        // (the eye's own exposure: the night's lights are in the same radiance as the land, one eye for both)
        boolean sunKey = sun[1] > -0.05f && light != null;
        try (var v = info.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(v.data()).putMat4f(inv).putVec4(sun[0], sun[1], sun[2], 0f)
                    .putVec4(look[0], look[1], 0f, 0f)
                    .putVec4(sunKey ? light[3] : 0f, sunKey ? light[4] : 0f, sunKey ? light[5] : 0f, 0f);
        }
        var target = mc.gameRenderer.mainRenderTarget();
        try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS sky",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("SkyInfo", info.currentBuffer());
            pass.setUniform("SkyView", view.getColorTextureView(), band);
            bindNight(pass, ms, linear, band, galaxy, masser, secunda);
            pass.draw(3, 1, 0, 0);
        }
        info.rotate();
        airInfo.rotate();
        nightInfo.rotate();
        celInfo.rotate();
        return true;
    }

    private void bindNight(com.mojang.renderpearl.api.commands.RenderPass pass, GpuTextureView ms,
                           com.mojang.renderpearl.api.textures.GpuSampler linear,
                           com.mojang.renderpearl.api.textures.GpuSampler band, GpuTextureView galaxy,
                           GpuTextureView masser, GpuTextureView secunda) {
        pass.setUniform("AirInfo", airInfo.currentBuffer());
        pass.setUniform("AirMs", ms, linear);
        pass.setUniform("NightInfo", nightInfo.currentBuffer());
        pass.setUniform("NightSigns", signs);
        pass.setUniform("CelestialInfo", celInfo.currentBuffer());
        pass.setUniform("NightGalaxy", galaxy, band);
        pass.setUniform("NightMasser", masser, linear);
        pass.setUniform("NightSecunda", secunda, linear);
    }

    private void light(com.mojang.renderpearl.api.commands.CommandEncoder encoder, GpuTextureView ms,
                       com.mojang.renderpearl.api.textures.GpuSampler linear,
                       com.mojang.renderpearl.api.textures.GpuSampler band, GpuTextureView galaxy,
                       GpuTextureView masser, GpuTextureView secunda) {
        int slot = next;
        if (busy[slot]) return;  // (the GPU is three readings behind: skip one rather than wait)
        var device = RenderSystem.getDevice();
        if (ring[slot] == null)
            ring[slot] = device.createBuffer(() -> "atlas sky light readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 64L);
        try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS sky light", lightTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(LIGHT_PIPELINE));
            bindNight(pass, ms, linear, band, galaxy, masser, secunda);
            pass.draw(3, 1, 0, 0);
        }
        busy[slot] = true;
        GpuBuffer buf = ring[slot];
        final long at = frameCount;
        encoder.copyTextureToBuffer(lightTarget.getColorTexture(), buf, 0L, () -> {
            try (var v = buf.slice(0, 64L).map(true, false)) {
                ByteBuffer b = v.data().order(ByteOrder.nativeOrder());
                synchronized (diffuseSh) {
                    for (int i = 0; i < 16; i++) {
                        float x = b.getFloat(i * 4);
                        diffuseSh[i] = Float.isFinite(x) ? x : 0f;
                    }
                }
                lightFrame = at;
            } finally {
                busy[slot] = false;
            }
        }, 0);
        next = (next + 1) % ring.length;
    }

    /**
     * The night sky's light on the land, besides the air's and the key moon's: {sky-up radiance rgb, ground radiance
     * rgb, SH L1 (4 x rgb: the constant band, x, y, z) of all of it}, to add to the air's sky light (AtlasLook.light's
     * [6..11], atlas_set_sky_glow): the diffuse emitters (the aurora, the airglow, the stars and the galaxy, read back),
     * the fill moon in its own colour (through the air), the meteors' flashes. Zeros while the sky is not ours.
     */
    static float[] nightLight() {
        float[] o = new float[18];
        if (!nightDrawn || frameCount - lightFrame > 30) return o;
        float[] sh = new float[12];
        synchronized (diffuseSh) {
            for (int k = 0; k < 4; k++)
                for (int c = 0; c < 3; c++) sh[k * 3 + c] = diffuseSh[k * 4 + c];
        }
        float[] meteors = NightSky.meteorLight();
        for (int k = 0; k < 4; k++)
            for (int c = 0; c < 3; c++) sh[k * 3 + c] += meteors[k * 4 + c];
        // the planets and a bright comet, points of light (CelestialSky: their irradiance, lifted as the sky's)
        float[] bodies = CelestialSky.light();
        for (int k = 0; k < 4; k++)
            for (int c = 0; c < 3; c++) sh[k * 3 + c] += bodies[k * 4 + c];
        // the fill moon: a point of light (its irradiance above the air, through the air's extinction to the eye)
        float[] ml = NightSky.moonlights();
        float fy = ml[7];
        if (fy > 0f) {
            float t = (float) Math.exp(-0.2 / Math.max(fy, 0.03));
            for (int c = 0; c < 3; c++) {
                float e = ml[9 + c] * t;
                sh[c] += e * 0.282095f;
                sh[3 + c] += e * 0.488603f * ml[6];
                sh[6 + c] += e * 0.488603f * ml[7];
                sh[9 + c] += e * 0.488603f * ml[8];
            }
        }
        float[] coeffs = AtlasAir.coeffs();
        float albedo = coeffs != null ? coeffs[15] : 0.25f;
        for (int c = 0; c < 3; c++) {
            // an upward face's irradiance over pi (the air's sky-up convention), the ground's radiance under it
            float up = (float) ((Math.PI * 0.282095 * sh[c] + 2 * Math.PI / 3 * 0.488603 * sh[6 + c]) / Math.PI);
            up = Math.max(up, 0f);
            o[c] = up;
            o[3 + c] = up * albedo;
        }
        System.arraycopy(sh, 0, o, 6, 12);
        return o;
    }
}

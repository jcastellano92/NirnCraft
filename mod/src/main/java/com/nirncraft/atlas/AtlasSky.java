package com.nirncraft.atlas;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
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

/**
 * Infinite ATLAS's sky while the near pass owns the frame (overworld): vanilla's sky dome, sunrise fan, sun, moon and
 * stars give way to the air's sky (com.nirncraft.atlas.AtlasAir; native/src/air.hpp, ported in atlas_air.glsl), the
 * far field's own model, so its haze meets a sky of the same air and the horizon has no seam. Each frame the air is
 * marched into a small sky-view LUT (atlas_sky_view.fsh: 192 x 108, the elevation gathered at the horizon); the sky
 * (atlas_sky.fsh) and the water's reflections (near_forward.fsh) look it up. The sun's disc wears its transmitted
 * light. NirnCraft's night sky (moons, stars, aurora) still draws over it at AfterSky. Installed at extraction as the
 * frame's custom skybox renderer, only where no other one (a realm's) is set and the core has the air.
 */
final class AtlasSky implements CustomSkyboxRenderer {
    static final AtlasSky INSTANCE = new AtlasSky();
    static final int VIEW_W = 192, VIEW_H = 108;

    private static final BindGroupLayout SKY_INFO = BindGroupLayout.builder()
            .withUniform("SkyInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("SkyView", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final BindGroupLayout AIR_INFO = BindGroupLayout.builder()
            .withUniform("AirInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("AirMs", UniformType.COMBINED_IMAGE_SAMPLER).build();

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
            .withColorTargetState(new ColorTargetState(Optional.empty(), com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT,
                    ColorTargetState.WRITE_ALL))
            .build();

    private MappableRingBuffer info, airInfo;
    private static TextureTarget view;
    private static boolean viewDrawn;

    private AtlasSky() {}

    static void register(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(PIPELINE);
        e.registerPipeline(VIEW_PIPELINE);
    }

    /** At extraction: our sky for this frame when the near pass will own it (and no realm has its own). */
    static void extract(ExtractLevelRenderStateEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!AtlasNearPass.active || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        if (AtlasClient.irisPackInUse() || !AtlasClient.nearLightSupported()) return;
        if (AtlasAir.coeffs() == null) return;  // a core without the air: vanilla's sky
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
        if (info == null) info = new MappableRingBuffer(() -> "atlas sky info", 130, 112);
        if (airInfo == null) airInfo = new MappableRingBuffer(() -> "atlas air info", 130, 112);
        if (view == null) view = new TextureTarget("atlas sky view", VIEW_W, VIEW_H, GpuFormat.RGBA16_FLOAT, null);
        var cam = levelRenderState.cameraRenderState;
        Matrix4f inv = AtlasLook.levelProjection(cam).mul(modelView).invert();
        float[] sun = AtlasClient.sunDirection(sky.sunAngle);
        float eyeY = mc.player != null ? (float) mc.player.getEyeY() : 64f;
        float[] light = AtlasAir.light(sun, eyeY);
        float[] moon = AtlasAir.moonFor(sun);
        // the eye's altitude in km of air (air.hpp altitudeOf: above sea level, coeffs[11] km a block)
        float alt = Math.max(eyeY - 63f, 0f) * coeffs[11];
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        // 1. the sky-view LUT: the air marched for each of its directions
        try (var v = airInfo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(v.data())
                    .putVec4(coeffs[0], coeffs[1], coeffs[2], coeffs[3])
                    .putVec4(coeffs[4], coeffs[5], coeffs[6], coeffs[7])
                    .putVec4(coeffs[8], coeffs[9], coeffs[10], coeffs[11])
                    .putVec4(coeffs[12], coeffs[13], coeffs[14], coeffs[15])
                    .putVec4(sun[0], sun[1], sun[2], alt)
                    .putVec4(moon[0], moon[1], moon[2], 0f)
                    .putVec4(moon[3], moon[4], moon[5], 0f);
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS sky view", view.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(VIEW_PIPELINE));
            pass.setUniform("AirInfo", airInfo.currentBuffer());
            pass.setUniform("AirMs", ms, linear);
            pass.draw(3, 1, 0, 0);
        }
        viewDrawn = true;
        airInfo.rotate();
        // 2. the sky: the LUT looked up per pixel (repeat across the bearing), the sun's disc in its transmitted light
        float[] look = AtlasLook.frame(mc);
        // the sky is not opened up with the eye at night (a dark night sky; NirnCraft's aurora and stars over it add
        // light and need the dark to show)
        look[0] = Math.min(look[0], 1.5f);
        boolean sunKey = sun[1] > -0.05f && light != null;
        try (var v = info.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(v.data()).putMat4f(inv).putVec4(sun[0], sun[1], sun[2], 0f)
                    .putVec4(look[0], look[1], 0f, 0f)
                    .putVec4(sunKey ? light[3] : 0f, sunKey ? light[4] : 0f, sunKey ? light[5] : 0f, 0f);
        }
        var bearing = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        var target = mc.gameRenderer.mainRenderTarget();
        try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS sky",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("SkyInfo", info.currentBuffer());
            pass.setUniform("SkyView", view.getColorTextureView(), bearing);
            pass.draw(3, 1, 0, 0);
        }
        info.rotate();
        return true;
    }
}

package com.nirncraft.atlas;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
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
 * stars give way to the far field's own sky model and sun disc (atlas_sky.fsh) through the terrain's display, so the
 * far field's haze meets a sky of the same model and the horizon has no seam. NirnCraft's night sky (moons, stars,
 * aurora) still draws over it at AfterSky. Installed at extraction as the frame's custom skybox renderer, only where no
 * other one (a realm's) is set.
 */
final class AtlasSky implements CustomSkyboxRenderer {
    static final AtlasSky INSTANCE = new AtlasSky();

    private static final BindGroupLayout SKY_INFO = BindGroupLayout.builder()
            .withUniform("SkyInfo", UniformType.UNIFORM_BUFFER).build();

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

    private MappableRingBuffer info;

    private AtlasSky() {}

    static void register(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(PIPELINE);
    }

    /** At extraction: our sky for this frame when the near pass will own it (and no realm has its own). */
    static void extract(ExtractLevelRenderStateEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!AtlasNearPass.active || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        if (AtlasClient.irisPackInUse() || !AtlasClient.nearLightSupported()) return;
        LevelRenderState state = e.getRenderState();
        if (state.customSkyboxRenderer == null) state.customSkyboxRenderer = INSTANCE;
    }

    @Override
    public boolean renderSky(LevelRenderState levelRenderState, SkyRenderState sky, Matrix4fc modelView, GpuBufferSlice skyFog) {
        Minecraft mc = Minecraft.getInstance();
        if (info == null) info = new MappableRingBuffer(() -> "atlas sky info", 130, 96);
        var cam = levelRenderState.cameraRenderState;
        Matrix4f inv = AtlasLook.levelProjection(cam).mul(modelView).invert();
        float[] sun = AtlasClient.sunDirection(sky.sunAngle);
        float[] look = AtlasLook.frame(mc);
        // the sky is not opened up with the eye at night (a dark night sky; NirnCraft's aurora and stars over it add
        // light and need the dark to show)
        look[0] = Math.min(look[0], 1.5f);
        try (var view = info.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putMat4f(inv).putVec4(sun[0], sun[1], sun[2], 0f)
                    .putVec4(look[0], look[1], 0f, 0f);
        }
        var target = mc.gameRenderer.mainRenderTarget();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Infinite ATLAS sky",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("SkyInfo", info.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
        info.rotate();
        return true;
    }
}

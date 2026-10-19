package com.nirncraft.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.nirncraft.render.client.WorldDraw;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * The cheap fallback compositor: without a shader pack (Iris off or absent), nobody samples atlas's targets, so this
 * draws them behind the vanilla near field. One full-screen triangle, in the AfterOpaqueBlocks pass right after
 * atlas's frame was copied into nirncraft:atlas/color and /depth: at the far plane (26.3 is reverse-Z: clip z 0,
 * depth test GREATER_THAN_OR_EQUAL, no depth write) so it lands only where no opaque terrain was drawn (the sky), and
 * everything drawn after it (entities, translucent water, clouds, weather, particles) stays on top. The fragment
 * shader (nirncraft:core/atlas_fallback) tonemaps atlas's linear premultiplied HDR like atlas's own harness,
 * blends it premultiplied over the sky, and keeps vanilla's fog continuous: the far field starts from the fog colour
 * at the edge of the loaded terrain and clears into its own haze; dense environmental fog (rain, water, lava,
 * blindness) is applied as the near field has it at that edge. In such fog the pass is skipped altogether.
 *
 * <p>Bounded and safe on 26.3: no buffers are created or uploaded inside the pass (the triangle comes from the
 * vertex index, the samplers are cached), the pass is borrowed (never opened or closed here), the pipeline is
 * registered with the others at RegisterRenderPipelinesEvent. It is off where it cannot be right: clip depth not
 * [0,1] (no glClipControl: far would not be 0), or a non-OpenGL renderer backend (atlas's bridge is GL only).
 * Any failure turns it off for the session with one WARN line (AtlasGuard). Not done: atlas's clouds (NirnCraft's
 * own cloud deck draws without a pack; atlas's clouds are turned off in this mode) and the sun map (only the pack
 * reads it).</p>
 */
final class AtlasFallback {
    static final String FEATURE = "atlas fallback compositor";

    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("nirncraft", "pipeline/atlas_fallback"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("nirncraft", "core/atlas_fallback"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();

    private AtlasFallback() {}

    static void register(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(PIPELINE);
    }

    /** Why the compositor cannot run on this device (null when it can). */
    static String blocker() {
        var info = RenderSystem.getDevice().getDeviceInfo();
        if (!info.isZZeroToOne()) return "the GL context has no [0,1] clip depth (glClipControl), so the far plane is not at depth 0";
        String backend = info.backendName();
        if (backend != null && !backend.toLowerCase(java.util.Locale.ROOT).contains("gl"))
            return "the renderer backend is " + backend + " (atlas shares images with OpenGL only)";
        return null;
    }

    /** True when the near field is wrapped in fog dense enough that nothing far would show (skip the pass). */
    static boolean fogHidesFarField(FogData fog) {
        return fog != null && fog.environmentalEnd < fog.renderDistanceStart && fog.color.w() > 0.99f;
    }

    /** Draws atlas's targets behind the near field (render thread, inside the AfterOpaqueBlocks pass). */
    static void draw(RenderPass nativePass, AtlasTargets targets) {
        AtlasTargets.Target color = targets.colorTarget(), depth = targets.depthTarget();
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        // depth is not filtered: blending a mountain's distance with the sky's 1e30 would invent fog
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        try (var pass = WorldDraw.borrow(nativePass)) {
            pass.drawScreen(PIPELINE, color.getTextureView(), linear, depth.getTextureView(), nearest);
        }
    }
}

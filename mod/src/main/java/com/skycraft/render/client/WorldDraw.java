package com.skycraft.render.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import java.util.Optional;
import java.util.OptionalDouble;

/** Explicit pipelines and batched render passes for sky, weather, smoke and world overlays. */
public final class WorldDraw {
    public enum Blend { ALPHA, ADDITIVE, REPLACE }
    public record Material(RenderPipeline pipeline, VertexFormat format, PrimitiveTopology topology, boolean textured, OitPipelineSet oit) {
        public void register(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent event) {
            event.registerPipeline(pipeline);
            event.registerPipeline(oit.depthBoundsPipeline());
            event.registerPipeline(oit.transmittancePipeline());
            event.registerPipeline(oit.accumulatePipeline());
        }
    }
    private WorldDraw() {}

    /** Call from a static material declaration, then register its pipeline in RegisterRenderPipelinesEvent. */
    public static Material material(Identifier id, VertexFormat format, PrimitiveTopology topology,
                                    Blend blend, boolean depthTest, boolean fog, boolean clampFarDepth) {
        boolean textured = format.equals(DefaultVertexFormat.POSITION_TEX_COLOR);
        if (!textured && !format.equals(DefaultVertexFormat.POSITION_COLOR))
            throw new IllegalArgumentException("World material needs POSITION_COLOR or POSITION_TEX_COLOR");
        var builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withLocation(id)
                .withVertexShader(Identifier.fromNamespaceAndPath("skycraft", "core/world_mesh"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("skycraft", "core/world_mesh"))
                .withVertexBinding(0, format).withPrimitiveTopology(topology).withCull(false)
                .withDepthStencilState(new DepthStencilState(depthTest ? CompareOp.GREATER_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS, false));
        if (textured) builder.withShaderDefine("TEXTURED").withBindGroupLayout(BindGroupLayouts.SAMPLER0);
        if (fog) builder.withShaderDefine("WORLD_FOG").withBindGroupLayout(BindGroupLayouts.FOG);
        if (clampFarDepth) builder.withShaderDefine("CLAMP_FAR_DEPTH");
        var oitBuilder = OitPipelineSet.builder(id, builder);
        if (blend == Blend.ADDITIVE) oitBuilder.withAccumulateModifier(pipeline -> pipeline.withShaderDefine("OIT_ADDITIVE"))
                .withTransmittanceModifier(pipeline -> pipeline.withShaderDefine("OIT_ADDITIVE"));
        var oit = oitBuilder.build();
        builder.withColorTargetState(switch (blend) {
            case REPLACE -> ColorTargetState.DEFAULT;
            case ALPHA -> new ColorTargetState(BlendFunction.TRANSLUCENT);
            case ADDITIVE -> new ColorTargetState(new BlendFunction(
                    BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA));
        });
        RenderPipeline pipeline = builder.build();
        IrisPipelines.assign(pipeline, IrisPipelines.forWorldMaterial(blend, textured));
        return new Material(pipeline, format, topology, textured, oit);
    }
    public static Pass open() { return open(Minecraft.getInstance().gameRenderer.mainRenderTarget()); }
    public static Pass open(RenderTarget target) { return new Pass(target); }
    /** Stages that provide a native active pass must borrow it, without closing or nesting passes. */
    public static Pass borrow(RenderPass pass) { return new Pass(pass); }

    public static final class Pass implements AutoCloseable {
        private final RenderPass pass;
        private final boolean ownsPass;
        private Pass(RenderTarget target) {
            ownsPass = true;
            pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Skycraft world geometry", target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty());
        }
        private Pass(RenderPass pass) { this.pass = pass; ownsPass = false; }
        public void draw(WorldMesh mesh, Material material, Matrix4fc modelView,
                         Vector4fc tint, @Nullable AbstractTexture texture) {
            draw(mesh, material, modelView, tint, texture, null);
        }
        /** An explicit projection slice supports secondary views; null uses the current native projection. */
        public void draw(WorldMesh mesh, Material material, Matrix4fc modelView, Vector4fc tint,
                         @Nullable AbstractTexture texture, @Nullable GpuBufferSlice projection) {
            draw(mesh, material, modelView, tint, texture, projection, null);
        }
        public void drawOit(WorldMesh mesh, Material material, Matrix4fc modelView, Vector4fc tint,
                            @Nullable AbstractTexture texture, OitStage stage) {
            draw(mesh, material, modelView, tint, texture, null, stage);
        }
        private void draw(WorldMesh mesh, Material material, Matrix4fc modelView, Vector4fc tint,
                          @Nullable AbstractTexture texture, @Nullable GpuBufferSlice projection, @Nullable OitStage stage) {
            if (!mesh.format().equals(material.format()) || mesh.topology() != material.topology())
                throw new IllegalArgumentException("World mesh and material vertex layouts/topology differ");
            if (material.textured() && texture == null) throw new IllegalArgumentException("Textured material has no texture");
            pass.setPipeline(RenderSystem.getCompiledPipeline(stage == null ? material.pipeline() : material.oit().getPipeline(stage)));
            RenderSystem.bindDefaultUniforms(pass);
            if (projection != null) pass.setUniform("Projection", projection);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms()
                    .writeTransform(new Matrix4f(modelView), new Vector4f(tint)));
            if (material.textured()) pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
            mesh.draw(pass);
        }
        @Override public void close() { if (ownsPass) pass.close(); }
    }
}

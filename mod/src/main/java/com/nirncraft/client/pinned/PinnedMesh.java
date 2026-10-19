package com.nirncraft.client.pinned;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A pin's geometry on the GPU, made by Minecraft's own section compiler (SectionCompiler.compile: the block models
 * through ModelBlockRenderer with NeoForge's lighter, smooth lighting and AO, tint sources, fluids through
 * FluidRenderer and their custom renderers, leaves forced opaque when cutout leaves are off, the solid / cutout /
 * translucent layers, translucent quads sorted for an eye) section by section from a PinnedRegion, with the compiler's
 * options taken from the game (ambient occlusion, cutout leaves, the block and fluid model sets, block colours) as
 * LevelRenderer.invalidateCompiledGeometry takes them. Drawn with the layers' own terrain pipelines (the separate-draw
 * variants, one ChunkSection uniform per section, as ChunkSectionsToRender.DrawSeparate draws), the block atlas with
 * the chunk layer sampler's settings and the lightmap.
 */
final class PinnedMesh implements AutoCloseable {
    record LayerDraw(GpuBuffer vertices, @Nullable GpuBuffer indices, @Nullable IndexType indexType, int indexCount) {}

    /** One compiled section: its offset from the box's minimum corner (blocks) and its layers. */
    record SectionDraw(int dx, int dy, int dz, Map<ChunkSectionLayer, LayerDraw> layers, GpuBuffer chunkSection,
                       double eyeDistance) {}

    final List<SectionDraw> sections = new ArrayList<>();
    /** The translucent sections far to near from the eye they were sorted for (classic blending order). */
    final List<SectionDraw> translucentOrder = new ArrayList<>();
    /** ChunkPosition = section offset + this integer point (the camera's block when the mesh was made). */
    final int kx, ky, kz;
    /** The model set the mesh was made with (a resource reload replaces it: the mesh is then remade). */
    final Object models;
    int quads, maxIndices;
    boolean translucent;

    private PinnedMesh(int kx, int ky, int kz, Object models) {
        this.kx = kx;
        this.ky = ky;
        this.kz = kz;
        this.models = models;
    }

    /**
     * Compiles and uploads every section the pin's box touches. eye: the equivalent eye in the pin's own coordinates
     * (the translucent quads are sorted for it: the copy is a scaled image of the original, so the order seen from the
     * camera is the order seen from there). k: the integer point the section uniforms are relative to. Client thread,
     * outside any render pass.
     */
    static PinnedMesh build(PinSnapshot pin, ClientLevel level, double[] eye, int kx, int ky, int kz) {
        Minecraft mc = Minecraft.getInstance();
        var models = mc.getModelManager().getBlockStateModelSet();
        SectionCompiler compiler = new SectionCompiler(mc.options.ambientOcclusion().get(), mc.options.cutoutLeaves().get(),
                models, mc.getModelManager().getFluidStateModelSet(), mc.getBlockColors());
        PinnedRegion region = new PinnedRegion(level, pin);
        PinnedMesh mesh = new PinnedMesh(kx, ky, kz, models);
        PinBox b = pin.volume.box;
        try (SectionBufferBuilderPack buffers = new SectionBufferBuilderPack()) {
            for (int sy = b.sectionY0(); sy <= b.sectionY1(); sy++)
                for (int sz = b.sectionZ0(); sz <= b.sectionZ1(); sz++)
                    for (int sx = b.sectionX0(); sx <= b.sectionX1(); sx++) {
                        SectionPos sp = SectionPos.of(sx, sy, sz);
                        int ox = sp.minBlockX(), oy = sp.minBlockY(), oz = sp.minBlockZ();
                        // SectionRenderDispatcher.createVertexSorting: the eye relative to the section's origin
                        VertexSorting sorting = VertexSorting.byDistance((float) (eye[0] - ox), (float) (eye[1] - oy),
                                (float) (eye[2] - oz));
                        @SuppressWarnings("deprecation")
                        SectionCompiler.Results results = compiler.compile(sp, region, sorting, buffers);
                        try {
                            if (!results.renderedLayers.isEmpty())
                                mesh.add(ox - b.x0(), oy - b.y0(), oz - b.z0(), results.renderedLayers, eye, ox, oy, oz);
                        } finally {
                            results.release();
                            buffers.clearAll();
                        }
                    }
        } catch (RuntimeException | Error e) {
            mesh.close();
            throw e;
        }
        mesh.translucentOrder.sort(Comparator.comparingDouble(SectionDraw::eyeDistance).reversed());
        if (mesh.maxIndices > 0) RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(mesh.maxIndices);
        return mesh;
    }

    private void add(int dx, int dy, int dz, Map<ChunkSectionLayer, MeshData> layers, double[] eye, int ox, int oy, int oz) {
        var device = RenderSystem.getDevice();
        Map<ChunkSectionLayer, LayerDraw> draws = new EnumMap<>(ChunkSectionLayer.class);
        GpuBuffer ubo;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // the vanilla ChunkSection block (ivec3 ChunkPosition, float ChunkVisibility): fully visible, no fade-in
            var data = Std140Builder.onStack(stack, DynamicGpuData.CHUNK_SECTION_UBO_SIZE)
                    .putIVec3(dx + kx, dy + ky, dz + kz).putFloat(1f).get();
            ubo = device.createBuffer(() -> "NirnCraft pinned section", GpuBuffer.USAGE_UNIFORM, data);
        }
        try {
            for (var e : layers.entrySet()) {
                MeshData m = e.getValue();
                var state = m.drawState();
                if (state.indexCount() == 0) continue;
                GpuBuffer vb = device.createBuffer(() -> "NirnCraft pinned vertices", GpuBuffer.USAGE_VERTEX, m.vertexBuffer());
                GpuBuffer ib = null;
                try {
                    if (m.indexBuffer() != null)
                        ib = device.createBuffer(() -> "NirnCraft pinned indices", GpuBuffer.USAGE_INDEX, m.indexBuffer());
                } catch (RuntimeException | Error ex) {
                    vb.close();
                    throw ex;
                }
                draws.put(e.getKey(), new LayerDraw(vb, ib, ib == null ? null : state.indexType(), state.indexCount()));
                quads += state.vertexCount() / 4;
                if (ib == null) maxIndices = Math.max(maxIndices, state.indexCount());
                if (e.getKey().translucent()) translucent = true;
            }
        } catch (RuntimeException | Error ex) {
            for (LayerDraw d : draws.values()) close(d);
            ubo.close();
            throw ex;
        }
        double cx = ox + 8 - eye[0], cy = oy + 8 - eye[1], cz = oz + 8 - eye[2];
        SectionDraw s = new SectionDraw(dx, dy, dz, draws, ubo, Math.sqrt(cx * cx + cy * cy + cz * cz));
        sections.add(s);
        if (draws.containsKey(ChunkSectionLayer.TRANSLUCENT)) translucentOrder.add(s);
    }

    /**
     * Draws the given layers into a pass that has the frame's default uniforms (projection, fog, globals): the layer's
     * own pipeline (or the override: an OIT stage's), the terrain uniform (the copy's model-view and the atlas size),
     * the atlas with the sampler, the lightmap, then each section with its ChunkSection uniform.
     */
    void draw(RenderPass pass, ChunkSectionLayer[] layers, @Nullable RenderPipeline override, GpuBufferSlice terrain,
              GpuTextureView atlas, GpuSampler sampler, GpuTextureView lightmap) {
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("TerrainUniform", terrain);
        pass.setUniform("Sampler0", atlas, sampler);
        pass.setUniform("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        for (ChunkSectionLayer layer : layers) {
            List<SectionDraw> order = layer.translucent() ? translucentOrder : sections;
            boolean any = false;
            for (SectionDraw s : order) any |= s.layers().containsKey(layer);
            if (!any) continue;
            pass.setPipeline(RenderSystem.getCompiledPipeline(override != null ? override : layer.pipeline(false)));
            for (SectionDraw s : order) {
                LayerDraw d = s.layers().get(layer);
                if (d == null) continue;
                pass.setUniform("ChunkSection", s.chunkSection().slice());
                pass.setVertexBuffer(0, d.vertices().slice());
                if (d.indices() != null) pass.setIndexBuffer(d.indices(), d.indexType());
                else pass.setIndexBuffer(sequential.getBuffer(d.indexCount()), sequential.type());
                pass.drawIndexed(d.indexCount(), 1, 0, 0, 0);
            }
        }
    }

    boolean has(ChunkSectionLayer layer) {
        for (SectionDraw s : sections) if (s.layers().containsKey(layer)) return true;
        return false;
    }

    private static void close(LayerDraw d) {
        try {
            d.vertices().close();
        } finally {
            if (d.indices() != null) d.indices().close();
        }
    }

    @Override
    public void close() {
        for (SectionDraw s : sections) {
            for (LayerDraw d : s.layers().values()) close(d);
            s.chunkSection().close();
        }
        sections.clear();
        translucentOrder.clear();
    }
}
